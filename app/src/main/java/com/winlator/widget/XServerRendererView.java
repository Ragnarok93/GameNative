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
     * Transfer frame-pacing ownership to LSFG when the implementation supports
     * native LSFG readiness tracking. Renderer paths that cannot be driven by
     * the Vulkan LSFG layer keep their ordinary local limiter.
     */
    default void transitionLsfgFramePacing(boolean lsfgRequested, int localLimit) {
        setFrameRateLimit(localLimit);
    }

    void onResume();
    void onPause();
    XServer getxServer();
    XServerRenderer getRenderer();
}
