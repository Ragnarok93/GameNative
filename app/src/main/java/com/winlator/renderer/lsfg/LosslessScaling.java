package com.winlator.renderer.lsfg;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.KeyValueSet;

import java.io.File;

public final class LosslessScaling {
    private static final String TAG = "LosslessScaling";
    private static final String JNI_TAG = "LSFG_NATIVE_JNI";
    private static final String CACHE_TAG = "LSFG_NATIVE_CACHE";
    private static final int STATUS_OK = 0;
    private static final String STORE_DIR = "lsfg";
    private static final String CACHE_FP16 = "lossless_fp16.lsfgcache";
    private static final String CACHE_FP32 = "lossless_fp32.lsfgcache";
    private static volatile boolean nativeLibraryLoaded;
    private static volatile String nativeLibraryLoadFailure = "none";

    static {
        try {
            System.loadLibrary("vulkan_renderer");
            nativeLibraryLoaded = true;
            Log.i(
                JNI_TAG,
                "event=library_load library=libvulkan_renderer.so loaded=1 abi=" + primaryAbi()
                    + " registration=exported-symbols"
            );
        } catch (Throwable t) {
            nativeLibraryLoaded = false;
            nativeLibraryLoadFailure = t.getClass().getSimpleName();
            Log.e(
                JNI_TAG,
                "event=library_load library=libvulkan_renderer.so loaded=0 abi=" + primaryAbi()
                    + " failure=" + nativeLibraryLoadFailure + " detail=" + safeMessage(t)
            );
            Log.w(TAG, "Failed to load libvulkan_renderer: " + safeMessage(t));
        }
    }

    private LosslessScaling() {}

    private static String primaryAbi() {
        return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
            ? Build.SUPPORTED_ABIS[0] : "unknown";
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "none" : message.replace(' ', '_');
    }

    private static String sourceIdentity(File source) {
        return source.getName() + ":" + source.length() + ":" + source.lastModified();
    }

    private static String statusName(int status) {
        switch (status) {
            case 0: return "ok";
            case 1: return "source-not-installed";
            case 2: return "source-unreadable";
            case 3: return "source-not-portable-executable";
            case 4: return "missing-shaders";
            case 5: return "shader-translation-failed";
            case 6: return "cache-filesystem-unusable";
            default: return "unknown-" + status;
        }
    }

    private static void logMissingNative(String function, Throwable t) {
        Log.e(
            JNI_TAG,
            "event=missing_native_function library=libvulkan_renderer.so loaded="
                + (nativeLibraryLoaded ? 1 : 0) + " abi=" + primaryAbi()
                + " function=" + function + " failure=" + t.getClass().getSimpleName()
                + " detail=" + safeMessage(t)
        );
    }

    /** Graphics driver the container runs with, or null when it uses the system driver. */
    public static String getDriverName(Container container) {
        if (container == null) return null;
        try {
            KeyValueSet config = new KeyValueSet(container.getGraphicsDriverConfig());
            String version = config.get("version");
            if (version != null && !version.isEmpty() && !version.equalsIgnoreCase("System")) {
                return version;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolve the compiled shader cache for {@code dll}, building it when missing or
     * stale. Returns null when no usable cache could be produced.
     */
    public static File resolveOrBuildCache(Context context, File dll, String driverName) {
        final long startedNs = System.nanoTime();
        if (context == null) {
            Log.e(CACHE_TAG, "event=cache_build_failed stage=arguments reason=context-null");
            return null;
        }
        if (dll == null || !dll.isFile()) {
            Log.e(
                CACHE_TAG,
                "event=cache_build_failed stage=source reason=missing-lsfg-source-dll"
                    + " path=" + (dll == null ? "null" : dll.getAbsolutePath())
            );
            return null;
        }
        if (!nativeLibraryLoaded) {
            Log.e(
                CACHE_TAG,
                "event=cache_build_failed stage=library-load reason=" + nativeLibraryLoadFailure
                    + " abi=" + primaryAbi()
            );
            return null;
        }

        final String sourceIdentity = sourceIdentity(dll);
        boolean fp16;
        try {
            fp16 = nativeSupportsFp16(driverName, context);
            Log.i(
                JNI_TAG,
                "event=capability_probe function=nativeSupportsFp16 resolved=1 supported="
                    + (fp16 ? 1 : 0) + " abi=" + primaryAbi()
            );
        } catch (UnsatisfiedLinkError error) {
            logMissingNative("nativeSupportsFp16", error);
            Log.e(
                CACHE_TAG,
                "event=cache_build_failed stage=jni-registration reason=missing-nativeSupportsFp16"
            );
            return null;
        } catch (Throwable t) {
            Log.e(
                CACHE_TAG,
                "event=cache_build_failed stage=capability-probe reason="
                    + t.getClass().getSimpleName() + " detail=" + safeMessage(t)
            );
            return null;
        }

        File store = new File(context.getFilesDir(), STORE_DIR);
        if (!store.isDirectory() && !store.mkdirs()) {
            Log.e(
                CACHE_TAG,
                "event=cache_build_failed stage=filesystem reason=cache-directory"
                    + " path=" + store.getAbsolutePath()
            );
            return null;
        }
        File cache = new File(store, fp16 ? CACHE_FP16 : CACHE_FP32);

        try {
            if (cache.isFile()) {
                final boolean matches;
                try {
                    matches = nativeCacheMatchesSource(
                        cache.getAbsolutePath(),
                        dll.getAbsolutePath()
                    );
                } catch (UnsatisfiedLinkError error) {
                    logMissingNative("nativeCacheMatchesSource", error);
                    Log.e(
                        CACHE_TAG,
                        "event=cache_validation_failed stage=jni-registration"
                            + " reason=missing-nativeCacheMatchesSource"
                    );
                    return null;
                }
                if (matches) {
                    Log.i(
                        CACHE_TAG,
                        "event=cache_hit validation=source-hash-match fp16=" + (fp16 ? 1 : 0)
                            + " source_identity=" + sourceIdentity
                            + " cache=" + cache.getAbsolutePath()
                    );
                    return cache;
                }
                Log.i(
                    CACHE_TAG,
                    "event=cache_stale validation=source-hash-mismatch fp16=" + (fp16 ? 1 : 0)
                        + " source_identity=" + sourceIdentity
                );
            } else {
                Log.i(
                    CACHE_TAG,
                    "event=cache_miss reason=missing fp16=" + (fp16 ? 1 : 0)
                        + " source_identity=" + sourceIdentity
                );
            }

            Log.i(
                CACHE_TAG,
                "event=cache_build_started fp16=" + (fp16 ? 1 : 0)
                    + " source_identity=" + sourceIdentity
                    + " cache=" + cache.getAbsolutePath()
            );
            final int status;
            try {
                status = nativeBuildCache(
                    dll.getAbsolutePath(),
                    cache.getAbsolutePath(),
                    fp16
                );
            } catch (UnsatisfiedLinkError error) {
                logMissingNative("nativeBuildCache", error);
                Log.e(
                    CACHE_TAG,
                    "event=cache_build_failed stage=jni-registration"
                        + " reason=missing-nativeBuildCache"
                );
                return null;
            }

            final double durationMs = (System.nanoTime() - startedNs) / 1_000_000.0;
            if (status != STATUS_OK || !cache.isFile()) {
                if (cache.isFile()) cache.delete();
                Log.e(
                    CACHE_TAG,
                    "event=cache_build_failed stage=shader-cache status=" + status
                        + " reason=" + statusName(status)
                        + " duration_ms=" + String.format(java.util.Locale.US, "%.3f", durationMs)
                        + " source_identity=" + sourceIdentity
                );
                return null;
            }
            Log.i(
                CACHE_TAG,
                "event=cache_build_complete validation=created status=ok fp16=" + (fp16 ? 1 : 0)
                    + " duration_ms=" + String.format(java.util.Locale.US, "%.3f", durationMs)
                    + " source_identity=" + sourceIdentity
                    + " cache=" + cache.getAbsolutePath()
            );
            return cache;
        } catch (Throwable t) {
            if (cache.isFile()) cache.delete();
            Log.e(
                CACHE_TAG,
                "event=cache_build_failed stage=unexpected reason="
                    + t.getClass().getSimpleName() + " detail=" + safeMessage(t)
                    + " source_identity=" + sourceIdentity
            );
            return null;
        }
    }

    private static native int nativeBuildCache(String dllPath, String cachePath, boolean preferFp16);

    private static native boolean nativeCacheMatchesSource(String cachePath, String dllPath);

    private static native boolean nativeSupportsFp16(String driverName, Context context);
}
