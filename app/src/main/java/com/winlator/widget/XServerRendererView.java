package com.winlator.widget;

import android.content.Context;

import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.XServer;

/**
 * Renderer-agnostic view methods that the X server and shared infra need.
 * Implemented by {@link XServerView} (Vulkan/SurfaceView) and
 * {@link XServerViewGL} (legacy GLSurfaceView, used for the VirGL path).
 */
public interface XServerRendererView {
    Context getContext();
    void queueEvent(Runnable r);
    void requestRender();
    void setFrameRateLimit(int limit);

    /**
     * Transition renderer-side pacing when LSFG requests ownership.
     *
     * Non-Vulkan renderers do not pass their presents through the LSFG Vulkan
     * layer, so their safe default is to retain the ordinary local limiter.
     * {@link XServerView} overrides this with the native-readiness handoff used
     * by the Vulkan path.
     */
    default void transitionLsfgFramePacing(boolean lsfgRequested, int localLimit) {
        setFrameRateLimit(localLimit);
    }

    void onResume();
    void onPause();
    XServer getxServer();
    XServerRenderer getRenderer();
}
