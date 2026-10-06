package com.winlator.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.widget.Toast;

import app.gamenative.R;
import com.winlator.widget.FrameRating;
import com.winlator.widget.XServerRendererView;
import com.winlator.widget.XServerView;
import com.winlator.xserver.Bitmask;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class VulkanRenderer implements WindowManager.OnWindowModificationListener,
                                       Pointer.OnPointerMotionListener,
                                       XServerRenderer {

    static { System.loadLibrary("vulkan_renderer"); }
    public static final int EFFECT_NONE = 0;
    public static final int EFFECT_FSR = 1;
    public static final int EFFECT_DLS = 2;
    public static final int EFFECT_CRT = 3;
    public static final int EFFECT_HDR = 4;
    public static final int EFFECT_NATURAL = 5;
    public static final int SCALE_FIT = 0;
    public static final int SCALE_STRETCH = 1;
    public static final int SCALE_FILL = 2;
    public static final int EFFECT_MASK_TOON = 1;
    public static final int EFFECT_MASK_FXAA = 1 << 1;
    public static final int EFFECT_MASK_VIVID = 1 << 2;
    public static final int EFFECT_MASK_CRT = 1 << 3;
    public static final int EFFECT_MASK_NTSC = 1 << 4;

    public final XServerView xServerView;
    private final XServer xServer;
    private long nativeHandle = 0;
    private final ReentrantReadWriteLock nativeLifetimeLock =
        new ReentrantReadWriteLock();
    private volatile boolean frameGenerationSupportedSnapshot = false;
    private volatile boolean nativeSurfaceSnapshot = false;
    private volatile boolean pendingFramegenArmed = false;
    private volatile boolean pendingFramegenEnabled = false;
    private volatile String pendingFramegenShaders = "";
    public static final int LSFG_FLOW_FIXED = 0;
    public static final int LSFG_FLOW_ADAPTIVE = 1;
    public static final int LSFG_FLOW_PRESET_QUALITY = 0;
    public static final int LSFG_FLOW_PRESET_BALANCED = 1;
    public static final int LSFG_FLOW_PRESET_LOW = 2;
    public static final int LSFG_FLOW_PRESET_AUTO = 3;

    private volatile int pendingFramegenMultiplier = 2, pendingFramegenTargetRate = 0, pendingFramegenFlowScale = 70;
    private volatile int pendingFramegenFlowMode = LSFG_FLOW_FIXED;
    private volatile int pendingFramegenFlowPreset = LSFG_FLOW_PRESET_QUALITY;
    private volatile long pendingFramegenConfigRevision = 0L;
    private volatile float pendingFramegenRefreshRate = 60.0f;
    private volatile float pendingFramegenGpuUsage = -1.0f, pendingFramegenSourceFps = 0.0f;
    private volatile float pendingFramegenOutputFps = 0.0f, pendingFramegenP95Ms = 0.0f;
    private volatile float pendingFramegenSlowRatio = 0.0f;
    private volatile int pendingFramegenThermalStatus = -1;
    private final Object lock = new Object();

    public final ViewTransformation viewTransformation = new ViewTransformation();
    private boolean fullscreen = false;
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private String forceFullscreenWMClass = null;
    private boolean cursorVisible = false;
    private boolean nativeMode = false;
    private String driverPath = null;
    private int outputScalingMode = SCALE_FIT;
    private java.util.concurrent.ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private boolean xRenderingPausedForScanout = false;
    private volatile VulkanXrFrameBridge xrFrameBridge = null;
    private volatile long xrTargetAhbPtr = 0;
    private volatile boolean flatPresentationEnabled = true;

    public void setFlatPresentationEnabled(boolean enabled) {
        if (flatPresentationEnabled == enabled) return;
        flatPresentationEnabled = enabled;
        if (!enabled) {
            scenePending.set(false);
        } else {
            onPointerMove(xServer.pointer.getX(), xServer.pointer.getY());
            queueSceneUpdate();
            xServerView.requestRender();
        }
    }

    /** See VulkanXrFrameBridge's kdoc — null except for the Meta Quest immersive path. */
    public void setVulkanXrFrameBridge(VulkanXrFrameBridge xrFrameBridge) {
        if (!app.gamenative.BuildConfig.XR_BUILD) return;
        this.xrFrameBridge = xrFrameBridge;
        synchronized (lock) {
            if (xrFrameBridge == null) {
                if (nativeHandle != 0 && xrTargetAhbPtr != 0) nativeDisableXrTarget(nativeHandle);
                xrTargetAhbPtr = 0;
            } else {
                enableXrTargetLocked();
            }
        }
    }

    /**
     * Points the immersive session at a persistent scene-sized AHardwareBuffer that the native
     * compositor renders into instead of the (hidden) swapchain. Covers every present path,
     * including SHM/CPU ones that never produce a per-window AHardwareBuffer.
     */
    private void enableXrTargetLocked() {
        VulkanXrFrameBridge bridge = xrFrameBridge;
        if (bridge == null || nativeHandle == 0) return;
        xrTargetAhbPtr = nativeEnableXrTarget(nativeHandle);
        if (xrTargetAhbPtr != 0) {
            long ext = nativeGetXrTargetExtent(nativeHandle);
            bridge.onScanoutBuffer(xrTargetAhbPtr, (int)(ext >>> 32), (int)(ext & 0xFFFFFFFFL));
        } else {
            android.util.Log.w("VulkanRenderer", "XR scene target unavailable, falling back to per-window forwarding");
        }
    }

    private volatile ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private static final java.util.concurrent.atomic.AtomicLong ID_GEN =
        new java.util.concurrent.atomic.AtomicLong(1);
    private final java.util.WeakHashMap<Drawable, Long> drawableIds =
        new java.util.WeakHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean scenePending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private android.view.SurfaceControl scanoutGameSC;
    private android.view.SurfaceControl scanoutCursorSC;
    private android.view.Surface        scanoutGameSurface;
    private android.view.Surface        scanoutCursorSurface;

    public VulkanRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            Context context = xServerView.getContext();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) { return null; }
    }

    private native long nativeInit(Surface surface, int screenWidth, int screenHeight, String driverPath, String libraryName, String nativeLibDir, String provenanceSocketPath);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeUpdateWindowContent(long handle, long id, java.nio.ByteBuffer pixels,
        short width, short height, short stride, int x, int y);
    private native void nativeUpdateWindowContentAHB(long handle, long id, long ahbPtr,
        short width, short height, int x, int y);
    private native void nativeSetTransform(long handle, float ox, float oy, float sx, float sy);
    private native void nativeSetPointerPos(long handle, short x, short y);
    private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle, java.nio.ByteBuffer pixels,
        short width, short height, short hotX, short hotY);
    private native void nativeSetRenderList(long handle, long[] ids, int[] xs, int[] ys, int count);
    private native void nativeRemoveWindow(long handle, long id);

    private native void nativeInitScanout(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, android.view.Surface surface);
    private native boolean nativeHasPresentationSurface(long handle);
    private native void nativeDestroyScanout(long handle);
    private native void nativeScanoutSetBuffer(long handle, long ahbPtr, int x, int y, int w, int h, int fenceFd);
    private native void nativeScanoutSetCursorImage(long handle, java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(long handle, short x, short y, short hotX, short hotY);
    private native boolean nativeIsScanoutActive(long handle);
    private native boolean nativeIsGameFrameDelivered(long handle);
    private native void nativeSetScanoutWindow(long handle, android.view.Surface game, android.view.Surface cursor);
    private native void nativeScanoutSetDst(long handle, int x, int y, int w, int h);
    private native void nativeSetVerboseLog(long handle, boolean v);
    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native void nativeSetLsfgFrameQueue(long handle, boolean enabled, int target);
    private native void nativeArmFrameGeneration(long handle);
    private native boolean nativeIsFrameGenerationSupported(long handle);
    private native long nativeGetGeneratedPresentedFrameCount(long handle);
    private native long nativeGetDisplayConfirmedFrameCount(long handle);
    private native long nativeGetGeneratedDisplayConfirmedFrameCount(long handle);
    private native long nativeGetSourceDisplayConfirmedFrameCount(long handle);
    private native boolean nativeIsDisplayConfirmationAvailable(long handle);
    private native void nativeSetFrameGenerationEnabled(long handle, boolean enabled);
    private native void nativeBeginLsfgBackendTransition(long handle, long transactionId, long revision);
    private native void nativeCommitLsfgBackendTransitionPolicy(long handle, long transactionId);
    private native boolean nativeIsLsfgBackendTransitionPolicyApplied(long handle, long transactionId);
    private native void nativeCompleteLsfgBackendTransition(long handle, long transactionId, String reason);
    private native void nativeSetFrameGenerationShaders(long handle, String cachePath);
    private native void nativeSetFrameGenerationRefreshRate(long handle, float hz);
    private native void nativeSetFrameGenerationMode(long handle, int multiplier, int targetRate,
        int flowScalePct, int flowMode, int flowPreset, long configRevision);
    private native void nativeSetFrameGenerationPressure(long handle, float gpuUsagePercent,
        int thermalStatus, float sourceFps, float outputFps, float frameTimeP95Ms,
        float slowFrameRatio);
    private native long nativeGetGeneratedFrameCount(long handle);
    private native long nativeGetPresentedFrameCount(long handle);
    private native long nativeGetRealFrameCount(long handle);
    private native long nativeGetSourceFrameCount(long handle);
    private native void nativeSetEffect(long handle, int effectId, float sharpness,
        int effectMask, float brightness, float contrast, float gamma);
    private native long nativeEnableXrTarget(long handle);
    private native void nativeDisableXrTarget(long handle);
    private native long nativeGetXrTargetExtent(long handle);

    private static volatile boolean gpuImageChecked = false;

    private long did(Drawable d) {
        return drawableIds.computeIfAbsent(d, k -> ID_GEN.getAndIncrement());
    }

    public void queueSceneUpdate() {
        if (scenePending.compareAndSet(false, true)) {
            xServerView.queueEvent(() -> {
                scenePending.set(false);
                updateScene();
            });
        }
    }

    public void onSurfaceCreated(Surface surface) {
        if (!gpuImageChecked) { GPUImage.checkIsSupported(); gpuImageChecked = true; }
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        nativeSurfaceSnapshot = false;
        frameGenerationSupportedSnapshot = false;
        initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        initExecutor.execute(() -> {
            synchronized (lock) {
                nativeLifetimeLock.writeLock().lock();
                try {
                if (nativeHandle != 0) {
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                        xrTargetAhbPtr = 0;
                    } else {
                        nativeSetPresentMode(nativeHandle, pendingPresentMode);
                        nativeSetLsfgFrameQueue(nativeHandle, pendingLsfgFrameQueueEnabled, pendingLsfgFrameQueueTarget);
                        replayFrameGenerationLocked();
                        nativeSurfaceSnapshot = true;
                        frameGenerationSupportedSnapshot =
                            nativeIsFrameGenerationSupported(nativeHandle);
                        enableXrTargetLocked();
                        initComplete = true;
                        xServerView.queueEvent(this::updateScene);
                        return;
                    }
                }
                nativeHandle = nativeInit(
                    surface,
                    xServer.screenInfo.width,
                    xServer.screenInfo.height,
                    driverPath,
                    driverLibraryName,
                    nativeLibDir,
                    "");
                if (nativeHandle != 0) {
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetLsfgFrameQueue(
                        nativeHandle,
                        pendingLsfgFrameQueueEnabled,
                        pendingLsfgFrameQueueTarget);
                    replayFrameGenerationLocked();
                    nativeSurfaceSnapshot = true;
                    frameGenerationSupportedSnapshot =
                        nativeIsFrameGenerationSupported(nativeHandle);
                    nativeSetFilterMode(nativeHandle, pendingFilterMode);
                    nativeSetSwapRB(nativeHandle, pendingSwapRB);
                    nativeSetEffect(nativeHandle, pendingEffectId, pendingSharpness,
                        pendingEffectMask, pendingBrightness, pendingContrast, pendingGamma);
                    updateTransform();
                    nativeSetCursorVisible(nativeHandle, cursorVisible);
                    if (nativeMode && !effectsRequireCompositor) {
                        xServerView.post(() -> {
                            releaseScanoutSurfaces();
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                try {
                                    android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                                    scanoutGameSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                                    scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                                    scanoutCursorSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                                    scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                                    new android.view.SurfaceControl.Transaction()
                                        .setLayer(scanoutGameSC,   1)
                                        .setLayer(scanoutCursorSC, 2)
                                        .setVisibility(scanoutGameSC,   true)
                                        .setVisibility(scanoutCursorSC, true)
                                        .apply();
                                    applyScanoutFrameRateHint();
                                    applyScanoutSwapTransform();
                                    synchronized (lock) {
                                        if (nativeHandle != 0) {
                                            nativeSetScanoutWindow(nativeHandle, scanoutGameSurface, scanoutCursorSurface);
                                            updateTransform();
                                        }
                                    }
                                } catch (Exception e) {
                                    android.util.Log.w("VulkanRenderer", "SC recreate failed on surface restore: " + e);
                                    synchronized (lock) {
                                        if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                                    }
                                }
                            } else {
                                synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
                            }
                        });
                    }
                }
                } finally {
                    nativeLifetimeLock.writeLock().unlock();
                }
            }
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeSetVerboseLog(nativeHandle, true);
                    nativeDumpRendererInfo(nativeHandle);
                    enableXrTargetLocked();
                }
            }
            initComplete = true;
            xServerView.queueEvent(this::updateScene);
        });
    }

    public void onSurfaceChanged(int width, int height) {
        surfaceWidth = width; surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeResize(nativeHandle, width, height);
                updateTransform();
                // Recreate the XR scene target at the new extent and republish the
                // replacement buffer; a no-op (same pointer back) when the size is unchanged.
                if (xrFrameBridge != null) enableXrTargetLocked();
            }
        }
    }

    public void onSurfaceDestroyed() {
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            nativeLifetimeLock.writeLock().lock();
            try {
                if (nativeHandle != 0) {
                    if (nativeMode) {
                        nativeDestroyScanout(nativeHandle);
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                        xrTargetAhbPtr = 0;
                    } else {
                        nativeDetachSurface(nativeHandle);
                    }
                }
                nativeSurfaceSnapshot = false;
                frameGenerationSupportedSnapshot = false;
            } finally {
                nativeLifetimeLock.writeLock().unlock();
            }
        }
        if (nativeMode) xServerView.post(this::releaseScanoutSurfaces);
    }

    private void releaseScanoutSurfaces() {
        if (scanoutGameSurface   != null) { scanoutGameSurface.release();   scanoutGameSurface   = null; }
        if (scanoutCursorSurface != null) { scanoutCursorSurface.release(); scanoutCursorSurface = null; }
        if (scanoutGameSC        != null) { scanoutGameSC.release();        scanoutGameSC        = null; }
        if (scanoutCursorSC      != null) { scanoutCursorSC.release();      scanoutCursorSC      = null; }
    }

    private void applyScanoutSwapTransform() {
        if (scanoutGameSC == null || android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction();
            float[] matrix = pendingSwapRB
                ? new float[]{0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f}
                : new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
            float[] translation = new float[]{0f, 0f, 0f};
            java.lang.reflect.Method setColorTransform = android.view.SurfaceControl.Transaction.class.getMethod(
                "setColorTransform",
                android.view.SurfaceControl.class,
                float[].class,
                float[].class
            );
            setColorTransform.invoke(txn, scanoutGameSC, matrix, translation);
            txn.apply();
            txn.close();
        } catch (Exception e) {
            android.util.Log.w("VulkanRenderer", "Scanout color transform unavailable: " + e);
        }
    }

    private void updateTransform() {
        if (nativeHandle == 0) return;
        if (fullscreen || outputScalingMode == SCALE_STRETCH) {
            nativeSetTransform(nativeHandle, 0, 0, 1.0f, 1.0f);
            nativeScanoutSetDst(nativeHandle,
                0,
                0,
                surfaceWidth,
                surfaceHeight);
        } else if (outputScalingMode == SCALE_FILL && surfaceWidth > 0 && surfaceHeight > 0) {
            float scale = Math.max(
                (float) surfaceWidth / (float) xServer.screenInfo.width,
                (float) surfaceHeight / (float) xServer.screenInfo.height
            );
            float sceneScaleX = (xServer.screenInfo.width * scale) / surfaceWidth;
            float sceneScaleY = (xServer.screenInfo.height * scale) / surfaceHeight;
            float sceneOffsetX = (xServer.screenInfo.width - xServer.screenInfo.width * sceneScaleX) * 0.5f;
            float sceneOffsetY = (xServer.screenInfo.height - xServer.screenInfo.height * sceneScaleY) * 0.5f;
            nativeSetTransform(nativeHandle, sceneOffsetX, sceneOffsetY, sceneScaleX, sceneScaleY);

            int dstW = Math.round(xServer.screenInfo.width * scale);
            int dstH = Math.round(xServer.screenInfo.height * scale);
            nativeScanoutSetDst(nativeHandle,
                Math.round((surfaceWidth - dstW) * 0.5f),
                Math.round((surfaceHeight - dstH) * 0.5f),
                dstW,
                dstH);
        } else {
            float py = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfH = (short)(xServer.screenInfo.height / 2);
                py = Math.max(0, Math.min(xServer.pointer.getY() - halfH / 2.0f, halfH));
            }
            nativeSetTransform(nativeHandle,
                viewTransformation.sceneOffsetX,
                viewTransformation.sceneOffsetY - py,
                viewTransformation.sceneScaleX,
                viewTransformation.sceneScaleY);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        }
    }

    public void updateScene() {
        ArrayList<RenderableWindow> newList = new ArrayList<>();
        try (XLock xl = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectWindows(newList, xServer.windowManager.rootWindow,
                xServer.windowManager.rootWindow.getX(),
                xServer.windowManager.rootWindow.getY());
        }
        synchronized (lock) {
            renderableWindows = newList;
            pushRenderList(newList);
        }
    }

    private void collectWindows(ArrayList<RenderableWindow> list, Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;
            if (unviewableWMClasses != null) {
                String wc = window.getClassName();
                for (String cls : unviewableWMClasses) {
                    if (wc.contains(cls)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false; break;
                    }
                }
            }
            if (viewable) list.add(new RenderableWindow(window.getContent(), x, y));
        }
        for (Window child : window.getChildren())
            collectWindows(list, child, child.getX() + x, child.getY() + y);
    }

    private void pushRenderList(ArrayList<RenderableWindow> list) {
        if (nativeHandle == 0) return;
        int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;

        int start = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            RenderableWindow rw = list.get(i);
            if (rw.content != null && rw.content.width >= screenW && rw.content.height >= screenH) {
                start = i; break;
            }
        }

        if (nativeMode) {
            ArrayList<RenderableWindow> ns = new ArrayList<>();
            for (int i = start; i < list.size(); i++) {
                RenderableWindow rw = list.get(i);
                if (rw.content != null && (effectsRequireCompositor || !rw.content.isDirectScanout())) ns.add(rw);
            }
            int n = ns.size();
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = did(ns.get(i).content); xs[i] = ns.get(i).rootX; ys[i] = ns.get(i).rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }
        if (fullscreen) {
            int n = list.size() - start;
            if (n <= 0) { nativeSetRenderList(nativeHandle, new long[0], new int[0], new int[0], 0); return; }
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                RenderableWindow rw = list.get(start + i);
                ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }

        int n = list.size() - start;
        long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            RenderableWindow rw = list.get(start + i);
            ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
        }
        nativeSetRenderList(nativeHandle, ids, xs, ys, n);
    }

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        Drawable cd; short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd = cursor.cursorImage; hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY;
        } else { cd = rootCursorDrawable; }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null && cd.getBuffer() != null) {
            synchronized (cd.renderLock) {
                nativeUpdateCursorImage(nativeHandle, cd.getBuffer(), cd.width, cd.height, hotX, hotY);
                if (nativeMode) {
                    java.nio.ByteBuffer buf = cd.getBuffer();
                    short stride = (short)(buf.capacity() / (cd.height * 4));
                    nativeScanoutSetCursorImage(nativeHandle, buf, cd.width, cd.height, stride);
                }
            }
        }
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
        if (hudRef != null && !nativeMode) hudRef.update();
        if (nativeHandle == 0 || pixmap == null) return;
        Drawable targetDrawable = window.getContent();
        long targetId = did(targetDrawable);
        int rx = window.getRootX() + xOff;
        int ry = window.getRootY() + yOff;
        synchronized (pixmap.renderLock) {
            Texture texture = pixmap.getTexture();
            if (texture instanceof GPUImage) {
                GPUImage g = (GPUImage) texture;
                long ahbPtr = g.getHardwareBufferPtr();
                if (ahbPtr != 0) {
                    if (nativeMode && pixmap.isDirectScanout() && nativeIsScanoutActive(nativeHandle)) {
                        int fenceFd = g.unlock();
                        nativeScanoutSetBuffer(nativeHandle, ahbPtr,
                            rx, ry, pixmap.width, pixmap.height, fenceFd);
                        g.lock();
                    } else {
                        if (xrFrameBridge != null && xrTargetAhbPtr == 0) {
                            // The immersive session samples this AHB directly; skipping the
                            // native update leaves the render loop parked on its condvar, so
                            // no scene is composited or presented to the invisible surface.
                            // Only used when the scene target could not be created.
                            xrFrameBridge.onScanoutBuffer(ahbPtr, pixmap.width, pixmap.height);
                            return;
                        }
                        nativeUpdateWindowContentAHB(nativeHandle, targetId, ahbPtr,
                            pixmap.width, pixmap.height, rx, ry);
                    }
                    return;
                }
                java.nio.ByteBuffer vd = g.getVirtualData();
                if (vd != null) {
                    short s = g.getStride() > 0 ? g.getStride() : pixmap.width;
                    nativeUpdateWindowContent(nativeHandle, targetId, vd,
                        pixmap.width, pixmap.height, s, rx, ry);
                    return;
                }
            }
            java.nio.ByteBuffer buf = pixmap.getBuffer();
            if (buf == null) return;
            short stride = (short)(buf.capacity() / (pixmap.height * 4));
            nativeUpdateWindowContent(nativeHandle, targetId, buf,
                pixmap.width, pixmap.height, stride, rx, ry);
        }
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        if (hudRef != null) hudRef.update();
        final long handle;
        synchronized (lock) { handle = nativeHandle; }
        if (handle == 0) return;

        Drawable drawable = window.getContent();
        if (drawable == null || !window.attributes.isMapped()) return;
        if (unviewableWMClasses != null) {
            String wc = window.getClassName();
            for (String cls : unviewableWMClasses) if (wc.contains(cls)) return;
        }
        int rx = window.getRootX();
        int ry = window.getRootY();
        long drawableId = did(drawable);

        synchronized (drawable.renderLock) {
            if (drawable.getTexture() instanceof GPUImage) {
                GPUImage g = (GPUImage) drawable.getTexture();
                long ahbPtr = g.getHardwareBufferPtr();
                if (ahbPtr != 0) {
                    boolean scanoutNow = nativeMode && nativeIsScanoutActive(handle);
                    if (nativeMode && drawable.isDirectScanout() && scanoutNow) {
                        boolean wasDelivered = nativeIsGameFrameDelivered(handle);
                        int fenceFd = g.unlock();
                        nativeScanoutSetBuffer(handle, ahbPtr,
                            rx, ry, drawable.width, drawable.height, fenceFd);
                        g.lock();
                        boolean delivered = nativeIsGameFrameDelivered(handle);
                        if (!xRenderingPausedForScanout && !wasDelivered && delivered) {
                            xServer.setRenderingEnabled(false);
                            xRenderingPausedForScanout = true;
                        }
                    } else if (!scanoutNow) {
                        nativeUpdateWindowContentAHB(handle, drawableId, ahbPtr,
                            drawable.width, drawable.height, rx, ry);
                    }
                    return;
                }
                java.nio.ByteBuffer vd = g.getVirtualData();
                if (vd != null) {
                    short s = g.getStride() > 0 ? g.getStride() : drawable.width;
                    nativeUpdateWindowContent(handle, drawableId, vd,
                        drawable.width, drawable.height, s, rx, ry);
                    return;
                }
            }
            java.nio.ByteBuffer buf = drawable.getBuffer();
            if (buf == null) return;
            short stride = (short)(buf.capacity() / (drawable.height * 4));
            nativeUpdateWindowContent(handle, drawableId, buf,
                drawable.width, drawable.height, stride, rx, ry);
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor) { lastCursor = cursor; sendCursorToNative(cursor); }
            if (nativeMode) {
                short hotX = 0, hotY = 0;
                if (cursor != null) { hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY; }
                nativeScanoutSetCursorPos(nativeHandle, x, y, hotX, hotY);
            }
            if (screenOffsetYRelativeToCursor) updateTransform();
        }
    }

    @Override
    public void onDestroyWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) { if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id); }
            queueSceneUpdate();
        });
    }

    @Override public void onMapWindow(Window window) { queueSceneUpdate(); }

    @Override
    public void onUnmapWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) { if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id); }
            queueSceneUpdate();
        });
    }

    @Override public void onChangeWindowZOrder(Window window) { queueSceneUpdate(); }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        queueSceneUpdate();
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            synchronized (lock) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) { lastCursor = window.attributes.getCursor(); sendCursorToNative(lastCursor); }
            }
        }
    }

    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) { nativeSetCursorVisible(nativeHandle, visible); if (visible) sendCursorToNative(lastCursor); }
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

    /**
     * Updates only the visible cursor position in the renderer.
     * This is useful for relative mouse mode where movement is forwarded to Wine
     * without necessarily changing the XServer pointer state.
     */
    public void updateVisualCursorPosition(int x, int y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, (short) x, (short) y);
        }
    }

    public void setNativeMode(boolean mode) {
        if (this.nativeMode == mode) return;
        this.nativeMode = mode;
        xRenderingPausedForScanout = false;
        if (mode) {
            // Only stand up the zero-copy scanout path when no compositor-only
            // effect is active; otherwise keep X rendering through the compositor.
            if (!effectsRequireCompositor) establishScanout();
            else xServer.setRenderingEnabled(true);
        } else {
            tearDownScanout();
        }
        xServerView.queueEvent(this::updateScene);
        final String msg = mode ? "Native Rendering+ Enabled" : "Native Rendering+ Disabled";
        xServerView.post(() -> Toast.makeText(xServerView.getContext(), msg, Toast.LENGTH_SHORT).show());
    }

    // Stands up the SurfaceControl layers and hands them to native for the
    // zero-copy scanout fast-path. Safe to call only when nativeMode is on.
    private void establishScanout() {
        xRenderingPausedForScanout = false;
        xServer.setRenderingEnabled(true);
        xServerView.post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                try {
                    android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                    scanoutGameSC = new android.view.SurfaceControl.Builder()
                        .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                    scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                    scanoutCursorSC = new android.view.SurfaceControl.Builder()
                        .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                    scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                    android.view.SurfaceControl.Transaction scTxn =
                        new android.view.SurfaceControl.Transaction()
                        .setLayer(scanoutGameSC,   1)
                        .setLayer(scanoutCursorSC, 2)
                        .setVisibility(scanoutGameSC,   true)
                        .setVisibility(scanoutCursorSC, true);
                    scTxn.apply();
                    applyScanoutFrameRateHint();
                    applyScanoutSwapTransform();
                    synchronized (lock) {
                        if (nativeHandle != 0) {
                            nativeSetScanoutWindow(nativeHandle,
                                scanoutGameSurface, scanoutCursorSurface);
                            updateTransform();
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("VulkanRenderer", "Sibling SC failed, using child SC: " + e);
                    synchronized (lock) {
                        if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                    }
                }
            } else {
                synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
            }
        });
    }

    // Tears down the scanout path and resumes compositor (X) rendering so that
    // window.frag-based effects and scaling take effect again.
    private void tearDownScanout() {
        xRenderingPausedForScanout = false;
        synchronized (lock) {
            if (nativeHandle != 0) nativeDestroyScanout(nativeHandle);
        }
        xServerView.post(() -> {
            xServer.setRenderingEnabled(true);
            releaseScanoutSurfaces();
        });
    }

    public boolean isNativeMode() { return nativeMode; }

    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
        android.util.Log.d("Winlator_Renderer",
            "setDriverInfo: path=" + driverPath + " lib=" + libraryName);
    }

    public void setVerboseLog(boolean v) {
        synchronized (lock) { if (nativeHandle != 0) nativeSetVerboseLog(nativeHandle, v); }
    }

    public void dumpRendererInfo() {
        synchronized (lock) { if (nativeHandle != 0) nativeDumpRendererInfo(nativeHandle); }
    }

    public void setFilterMode(int mode) {
        pendingFilterMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode); }
    }

    public void setSwapRB(boolean enabled) {
        pendingSwapRB = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSwapRB(nativeHandle, enabled); }
    }

    public void setEffect(int effectId, float sharpness) {
        setEffect(effectId, sharpness, SCALE_FIT);
    }

    public void setEffect(int effectId, float sharpness, boolean preserveAspectFit) {
        setEffect(effectId, sharpness, preserveAspectFit ? SCALE_FIT : SCALE_STRETCH);
    }

    public void setEffect(int effectId, float sharpness, int scalingMode) {
        setEffect(effectId, sharpness, scalingMode, 0, 0.0f, 0.0f, 1.0f);
    }

    public void setEffect(int effectId, float sharpness, int scalingMode,
                          int effectMask, float brightness, float contrast, float gamma) {
        pendingEffectId = Math.max(EFFECT_NONE, Math.min(EFFECT_NATURAL, effectId));
        pendingSharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
        pendingEffectMask = Math.max(0, effectMask);
        pendingBrightness = Math.max(-1.0f, Math.min(1.0f, brightness));
        pendingContrast = Math.max(-1.0f, Math.min(1.0f, contrast));
        pendingGamma = Math.max(0.1f, Math.min(4.0f, gamma));
        outputScalingMode = Math.max(SCALE_FIT, Math.min(SCALE_FILL, scalingMode));
        boolean wasRequireCompositor = effectsRequireCompositor;
        effectsRequireCompositor = computeEffectsRequireCompositor();
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeSetEffect(nativeHandle, pendingEffectId, pendingSharpness,
                    pendingEffectMask, pendingBrightness, pendingContrast, pendingGamma);
                updateTransform();
            }
        }
        // If the effect state crossed the compositor/scanout boundary, switch
        // presentation paths so shader work actually reaches the screen.
        if (nativeMode && wasRequireCompositor != effectsRequireCompositor) {
            if (effectsRequireCompositor) tearDownScanout();
            else establishScanout();
        }
    }

    // Returns true if any active effect, filter, or color adjustment needs the
    // compositor (window.frag). Scanout bypasses the shader, so these can only
    // take visible effect when content is routed through the textured-quad path.
    private boolean computeEffectsRequireCompositor() {
        return pendingFramegenEnabled || pendingEffectId != EFFECT_NONE
            || pendingEffectMask != 0
            || pendingBrightness != 0.0f
            || pendingContrast != 0.0f
            || Math.abs(pendingGamma - 1.0f) > 1e-3f
            || pendingFilterMode != 0;
    }

    /** Whether any active effect/filter/color adjustment is currently forcing the compositor
     * path, blocking the zero-copy scanout fast-path. See {@link #resetScreenEffects()}. */
    public boolean isEffectsRequireCompositor() { return effectsRequireCompositor; }

    /** Turns off every effect, filter, and color adjustment (back to defaults), letting scanout
     * re-establish itself if nothing else is blocking it. Used by the immersive quick-menu tab's
     * "reset" action — screen effects are the single most common reason a user would otherwise
     * need to hunt through several unrelated settings to get the zero-copy path back. */
    public void resetScreenEffects() {
        setFilterMode(0);
        setEffect(EFFECT_NONE, 0.0f, outputScalingMode, 0, 0.0f, 0.0f, 1.0f);
    }
    public int getEffectId() { return pendingEffectId; }
    public float getSharpness() { return pendingSharpness; }

    public void setVkPresentMode(int mode) {
        pendingPresentMode = mode;
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    private void replayFrameGenerationLocked() {
        if (!pendingFramegenArmed || nativeHandle == 0) return;
        nativeArmFrameGeneration(nativeHandle);
        nativeSetFrameGenerationMode(nativeHandle, pendingFramegenMultiplier,
            pendingFramegenTargetRate, pendingFramegenFlowScale, pendingFramegenFlowMode,
            pendingFramegenFlowPreset, pendingFramegenConfigRevision);
        nativeSetFrameGenerationRefreshRate(nativeHandle, pendingFramegenRefreshRate);
        nativeSetFrameGenerationPressure(nativeHandle, pendingFramegenGpuUsage,
            pendingFramegenThermalStatus, pendingFramegenSourceFps, pendingFramegenOutputFps,
            pendingFramegenP95Ms, pendingFramegenSlowRatio);
        if (!pendingFramegenShaders.isEmpty()) nativeSetFrameGenerationShaders(nativeHandle, pendingFramegenShaders);
        nativeSetFrameGenerationEnabled(nativeHandle, pendingFramegenEnabled);
        nativeSurfaceSnapshot = nativeHasPresentationSurface(nativeHandle);
        frameGenerationSupportedSnapshot =
            nativeSurfaceSnapshot && nativeIsFrameGenerationSupported(nativeHandle);
        android.util.Log.i(
            "LSFG_NATIVE",
            "event=surface_settings_replayed initialized="
                + frameGenerationSupportedSnapshot
                + " backend=native config_revision=" + pendingFramegenConfigRevision
                + " multiplier=" + pendingFramegenMultiplier
                + " target_fps=" + pendingFramegenTargetRate
                + " flow_mode=" + (pendingFramegenFlowMode == LSFG_FLOW_ADAPTIVE ? "adaptive" : "fixed")
                + " flow_preset=" + pendingFramegenFlowPreset
                + " frame_queue_enabled=" + (pendingLsfgFrameQueueEnabled ? 1 : 0)
                + " frame_queue_target=" + pendingLsfgFrameQueueTarget
                + " requested_present_mode=" + pendingPresentMode);
    }

    public boolean isFrameGenerationSupported() {
        return nativeSurfaceSnapshot && frameGenerationSupportedSnapshot;
    }

    public boolean hasNativeSurface() {
        return nativeSurfaceSnapshot;
    }

    public long getGeneratedPresentedFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0
                ? nativeGetGeneratedPresentedFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public long getDisplayConfirmedFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0
                ? nativeGetDisplayConfirmedFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public long getGeneratedDisplayConfirmedFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0
                ? nativeGetGeneratedDisplayConfirmedFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public long getSourceDisplayConfirmedFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0
                ? nativeGetSourceDisplayConfirmedFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public boolean isDisplayConfirmationAvailable() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0
                && nativeIsDisplayConfirmationAvailable(nativeHandle);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void armFrameGeneration() {
        pendingFramegenArmed = true;
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0) nativeArmFrameGeneration(nativeHandle);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void beginLsfgBackendTransition(long transactionId, long revision) {
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0)
                nativeBeginLsfgBackendTransition(nativeHandle, transactionId, revision);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void commitLsfgBackendTransitionPolicy(long transactionId) {
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0)
                nativeCommitLsfgBackendTransitionPolicy(nativeHandle, transactionId);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public boolean isLsfgBackendTransitionPolicyApplied(long transactionId) {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle == 0
                || nativeIsLsfgBackendTransitionPolicyApplied(nativeHandle, transactionId);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void completeLsfgBackendTransition(long transactionId, String reason) {
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0)
                nativeCompleteLsfgBackendTransition(
                    nativeHandle, transactionId, reason == null ? "unknown" : reason);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void setFrameGenerationEnabled(boolean enabled) {
        pendingFramegenEnabled = enabled;
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0) {
                nativeSetFrameGenerationEnabled(nativeHandle, enabled);
                frameGenerationSupportedSnapshot =
                    nativeSurfaceSnapshot
                    && nativeIsFrameGenerationSupported(nativeHandle);
            } else {
                frameGenerationSupportedSnapshot = false;
            }
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
        // Native Rendering+ scanout bypasses compute entirely. Keep the
        // compositor active for LSFG, and restore scanout on disable.
        xServerView.post(() -> {
            setEffect(pendingEffectId, pendingSharpness, outputScalingMode,
                pendingEffectMask, pendingBrightness, pendingContrast, pendingGamma);
            queueSceneUpdate();
            xServerView.requestRender();
        });
    }

    /** Apply one native request atomically against surface teardown/backend disable. */
    public boolean applyFrameGenerationSettings(String cachePath, int multiplier, int targetRate,
            int flowScalePct, int flowMode, int flowPreset, long configRevision, float refreshRate,
            java.util.function.BooleanSupplier stillRequested) {
        if (!stillRequested.getAsBoolean()) return false;

        pendingFramegenArmed = true;
        pendingFramegenEnabled = true;
        pendingFramegenShaders = cachePath == null ? "" : cachePath;
        pendingFramegenMultiplier = Math.max(2, Math.min(4, multiplier));
        pendingFramegenTargetRate = Math.max(0, targetRate);
        pendingFramegenFlowScale = Math.max(25, Math.min(100, flowScalePct));
        pendingFramegenFlowMode =
            flowMode == LSFG_FLOW_ADAPTIVE ? LSFG_FLOW_ADAPTIVE : LSFG_FLOW_FIXED;
        pendingFramegenFlowPreset = Math.max(
            LSFG_FLOW_PRESET_QUALITY, Math.min(LSFG_FLOW_PRESET_AUTO, flowPreset));
        pendingFramegenConfigRevision = Math.max(0L, configRevision);
        pendingFramegenRefreshRate = refreshRate;

        // applyFrameGenerationSettings used to route through
        // setFrameGenerationEnabled(), whose post also forces compositor
        // presentation while Native LSFG owns generation. Preserve that
        // behavior without holding the renderer monitor or native lifetime
        // lock across UI routing.
        xServerView.post(() -> {
            setEffect(pendingEffectId, pendingSharpness, outputScalingMode,
                pendingEffectMask, pendingBrightness, pendingContrast, pendingGamma);
            queueSceneUpdate();
            xServerView.requestRender();
        });

        nativeLifetimeLock.readLock().lock();
        try {
            if (!stillRequested.getAsBoolean() || nativeHandle == 0) return false;
            final long handle = nativeHandle;
            // Native and Legacy share the same configured present-mode policy.
            // The caller updates pendingPresentMode before this atomic Native
            // apply, so do not silently force FIFO here.
            nativeSetPresentMode(handle, pendingPresentMode);
            // Native and Legacy share the same GameNative Frame Queue policy.
            // Native adds only pre-acquire stale-slot admission; it does not
            // substitute a second buffering state machine.
            nativeSetLsfgFrameQueue(
                handle,
                pendingLsfgFrameQueueEnabled,
                pendingLsfgFrameQueueTarget);
            android.util.Log.i(
                "LSFG_NATIVE",
                "event=runtime_apply backend=native config_revision="
                    + pendingFramegenConfigRevision
                    + " multiplier=" + pendingFramegenMultiplier
                    + " target_fps=" + pendingFramegenTargetRate
                    + " flow_mode=" + (pendingFramegenFlowMode == LSFG_FLOW_ADAPTIVE ? "adaptive" : "fixed")
                    + " flow_preset=" + pendingFramegenFlowPreset
                    + " requested_scale_pct=" + pendingFramegenFlowScale
                    + " refresh_hz=" + pendingFramegenRefreshRate
                    + " requested_present_mode=" + pendingPresentMode
                    + " frame_queue_enabled=" + (pendingLsfgFrameQueueEnabled ? 1 : 0)
                    + " frame_queue_target=" + pendingLsfgFrameQueueTarget);
            android.util.Log.i(
                "LSFG_FRAME_QUEUE",
                "event=policy-ownership owner=shared-host-frame-queue backend=native"
                    + " config_revision=" + pendingFramegenConfigRevision
                    + " requested_present_mode=" + pendingPresentMode
                    + " generation_mode=" + (pendingFramegenTargetRate > 0 ? "adaptive" : "fixed")
                    + " enabled=" + (pendingLsfgFrameQueueEnabled ? 1 : 0)
                    + " requested_target=" + pendingLsfgFrameQueueTarget);
            nativeArmFrameGeneration(handle);
            nativeSetFrameGenerationMode(
                handle, pendingFramegenMultiplier, pendingFramegenTargetRate,
                pendingFramegenFlowScale, pendingFramegenFlowMode,
                pendingFramegenFlowPreset, pendingFramegenConfigRevision);
            nativeSetFrameGenerationRefreshRate(handle, pendingFramegenRefreshRate);
            if (!pendingFramegenShaders.isEmpty())
                nativeSetFrameGenerationShaders(handle, pendingFramegenShaders);
            if (!stillRequested.getAsBoolean()) return false;
            nativeSetFrameGenerationEnabled(handle, true);
            nativeSurfaceSnapshot = nativeHasPresentationSurface(handle);
            frameGenerationSupportedSnapshot =
                nativeSurfaceSnapshot && nativeIsFrameGenerationSupported(handle);
            return frameGenerationSupportedSnapshot;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void setFrameGenerationShaders(String cachePath) {
        if (cachePath == null || cachePath.isEmpty()) return;
        pendingFramegenShaders = cachePath;
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0)
                nativeSetFrameGenerationShaders(nativeHandle, cachePath);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void setFrameGenerationRefreshRate(float hz) {
        pendingFramegenRefreshRate = hz;
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0)
                nativeSetFrameGenerationRefreshRate(nativeHandle, hz);
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct) {
        setFrameGenerationMode(
            multiplier, targetRate, flowScalePct, pendingFramegenFlowMode,
            pendingFramegenFlowPreset, pendingFramegenConfigRevision);
    }

    public void setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct,
            int flowMode, int flowPreset) {
        setFrameGenerationMode(
            multiplier, targetRate, flowScalePct, flowMode, flowPreset,
            pendingFramegenConfigRevision);
    }

    public void setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct,
            int flowMode, int flowPreset, long configRevision) {
        pendingFramegenMultiplier = Math.max(2, Math.min(4, multiplier));
        pendingFramegenTargetRate = Math.max(0, targetRate);
        pendingFramegenFlowScale = Math.max(25, Math.min(100, flowScalePct));
        pendingFramegenFlowMode =
            flowMode == LSFG_FLOW_ADAPTIVE ? LSFG_FLOW_ADAPTIVE : LSFG_FLOW_FIXED;
        pendingFramegenFlowPreset = Math.max(
            LSFG_FLOW_PRESET_QUALITY, Math.min(LSFG_FLOW_PRESET_AUTO, flowPreset));
        pendingFramegenConfigRevision = Math.max(0L, configRevision);
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0) {
                nativeSetFrameGenerationMode(nativeHandle, pendingFramegenMultiplier,
                    pendingFramegenTargetRate, pendingFramegenFlowScale,
                    pendingFramegenFlowMode, pendingFramegenFlowPreset,
                    pendingFramegenConfigRevision);
            }
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void setFrameGenerationPressure(float gpuUsagePercent, int thermalStatus,
            float sourceFps, float outputFps, float frameTimeP95Ms, float slowFrameRatio) {
        pendingFramegenGpuUsage = gpuUsagePercent;
        pendingFramegenThermalStatus = thermalStatus;
        pendingFramegenSourceFps = sourceFps;
        pendingFramegenOutputFps = outputFps;
        pendingFramegenP95Ms = frameTimeP95Ms;
        pendingFramegenSlowRatio = slowFrameRatio;
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0) {
                nativeSetFrameGenerationPressure(nativeHandle, gpuUsagePercent, thermalStatus,
                    sourceFps, outputFps, frameTimeP95Ms, slowFrameRatio);
            }
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public long getGeneratedFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0 ? nativeGetGeneratedFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public long getPresentedFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0 ? nativeGetPresentedFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public long getRealFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0 ? nativeGetRealFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public long getSourceFrameCount() {
        nativeLifetimeLock.readLock().lock();
        try {
            return nativeHandle != 0 ? nativeGetSourceFrameCount(nativeHandle) : 0L;
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    public void setLsfgFrameQueue(boolean enabled, int target) {
        pendingLsfgFrameQueueEnabled = enabled;
        pendingLsfgFrameQueueTarget = Math.max(0, Math.min(2, target));
        nativeLifetimeLock.readLock().lock();
        try {
            if (nativeHandle != 0) {
                nativeSetLsfgFrameQueue(
                    nativeHandle,
                    pendingLsfgFrameQueueEnabled,
                    pendingLsfgFrameQueueTarget);
            }
        } finally {
            nativeLifetimeLock.readLock().unlock();
        }
    }

    private FrameRating hudRef = null;

    @Override
    public void setFrameRating(FrameRating fr) {
        hudRef = fr;
    }

    @Override
    public String getForceFullscreenWMClass() { return forceFullscreenWMClass; }

    @Override
    public void setForceFullscreenWMClass(String wmClass) { this.forceFullscreenWMClass = wmClass; }

    @Override
    public void setOnFrameRenderedListener(Runnable r) { /* no-op: Vulkan compositor does not expose per-frame callbacks */ }

    @Override
    public XServerRendererView getRendererView() { return xServerView; }

    @Override
    public boolean isFullscreen() { return fullscreen; }
    public void toggleFullscreen() { fullscreen = !fullscreen; synchronized (lock) { updateTransform(); } xServerView.queueEvent(this::updateScene); }
    public void setScreenOffsetYRelativeToCursor(boolean b) { screenOffsetYRelativeToCursor = b; synchronized (lock) { updateTransform(); } }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    public void setMagnifierZoom(float zoom) { magnifierZoom = zoom; }
    public float getMagnifierZoom() { return magnifierZoom; }
    public void setUnviewableWMClasses(String... classes) { this.unviewableWMClasses = classes; }
    private int fpsLimit = 0;
    private int refreshRateLimit = 60;
    // Strict-FIFO LSFG presentation cadence. This is intentionally separate
    // from fpsLimit, which remains the real/source frame-rate contract.
    private int lsfgPresentationFrameRateHint = 0;
    private int     pendingPresentMode    = 2;
    private volatile boolean pendingLsfgFrameQueueEnabled = false;
    private volatile int     pendingLsfgFrameQueueTarget = 0;
    private int     pendingFilterMode     = 0;
    private boolean pendingSwapRB         = false;
    private int     pendingEffectId       = EFFECT_NONE;
    private float   pendingSharpness      = 1.0f;
    private int     pendingEffectMask     = 0;
    private float   pendingBrightness     = 0.0f;
    private float   pendingContrast       = 0.0f;
    private float   pendingGamma          = 1.0f;
    // When any screen effect / filter / color adjustment is active we must route
    // fullscreen content through the compositor (window.frag) instead of the
    // zero-copy scanout fast-path, because scanout bypasses the shader entirely.
    private volatile boolean effectsRequireCompositor = false;
    public int getFpsLimit() { return fpsLimit; }
    public void setFpsLimit(int limit) {
        this.fpsLimit = Math.max(0, limit);
        applySourceOrLsfgFrameRateHint();
    }

    public void setLsfgPresentationFrameRateHint(int limit) {
        this.lsfgPresentationFrameRateHint = Math.max(0, limit);
        applySourceOrLsfgFrameRateHint();
    }

    private void applySourceOrLsfgFrameRateHint() {
        if (android.os.Build.VERSION.SDK_INT < 30 || scanoutGameSC == null) return;
        float targetFps = lsfgPresentationFrameRateHint > 0
            ? (float)lsfgPresentationFrameRateHint
            : fpsLimit > 0
                ? (float)fpsLimit
                : xServerView.getDisplay() != null
                    ? xServerView.getDisplay().getRefreshRate() : 60f;
        new android.view.SurfaceControl.Transaction()
            .setFrameRate(scanoutGameSC, targetFps,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            .apply();
    }

    public int getRefreshRateLimit() { return refreshRateLimit; }
    public void setRefreshRateLimit(int limit) {
        this.refreshRateLimit = limit > 0 ? limit : 0;
        applyScanoutFrameRateHint();
    }

    private void applyScanoutFrameRateHint() {
        if (android.os.Build.VERSION.SDK_INT < 30 || scanoutGameSC == null) return;
        float targetFps = lsfgPresentationFrameRateHint > 0
            ? (float)lsfgPresentationFrameRateHint
            : refreshRateLimit > 0 ? (float)refreshRateLimit
                : xServerView.getDisplay() != null
                    ? xServerView.getDisplay().getRefreshRate() : 60f;
        new android.view.SurfaceControl.Transaction()
            .setFrameRate(scanoutGameSC, targetFps,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            .apply();
    }
    private static class RenderableWindow {
        public final Drawable content; public int rootX, rootY;
        public RenderableWindow(Drawable c, int x, int y) { content=c; rootX=x; rootY=y; }
    }
}
