package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexVulkanPresenterContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun presenterOwnsDedicatedEglSurfaceAndGpuFenceHandoff() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val native = repoFile(
            "app/src/main/cpp/apex/apex_vulkan_presenter.cpp",
        ).readText()
        val renderer = repoFile(
            "app/src/main/java/com/winlator/renderer/VulkanRenderer.java",
        ).readText()

        listOf(
            "HandlerThread",
            "Choreographer",
            "nativeCreatePresenter",
            "nativePresentSourceFrame",
            "nativePresentGeneratedFrame",
            "renderer.releaseApexFrame",
        ).forEach { token ->
            assertTrue("presenter is missing $token", presenter.contains(token))
        }

        listOf(
            "eglGetNativeClientBufferANDROID",
            "EGL_SYNC_NATIVE_FENCE_ANDROID",
            "EGL_SYNC_NATIVE_FENCE_FD_ANDROID",
            "eglWaitSyncKHR",
            "eglDupNativeFenceFDANDROID",
            "ApexEngine::getInstance().processFrame",
        ).forEach { token ->
            assertTrue("native presenter is missing $token", native.contains(token))
        }

        assertFalse(
            "normal acquire-fence handoff must stay GPU-side",
            native.contains("poll(") || native.contains("sync_wait("),
        )

        listOf(
            "ApexVulkanPresenter",
            "apexGameSurface",
            "apexGameSurfaceControl",
            "gamenative_apex_presenter",
        ).forEach { token ->
            assertTrue("VulkanRenderer presenter lifecycle is missing $token", renderer.contains(token))
        }
    }

    @Test
    fun generatedPulseDoesNotDependOnReleasedSourceAhb() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val native = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()

        assertTrue(
            "generated pulses must be legal without retaining the source AHB texture",
            pipeline.contains("if (isNewRealFrame && inputTextureId == 0)"),
        )
        assertTrue(
            "generated presentation must invoke Apex with no source texture dependency",
            native.contains("processFrame(0, 0,"),
        )
        assertTrue(
            "source imports must be released after a release fence is exported",
            native.contains("destroyImportedSource"),
        )
        assertTrue(
            "Vulkan AHB source ingestion must request the one-time vertical-origin correction",
            native.contains("true,\n        true,\n        std::clamp"),
        )
        assertTrue(
            "presenter telemetry must classify the frame only after swap outcome is known",
            native.contains("recordPresentation(*presenter, outputKind, swapSucceeded)"),
        )
        assertTrue(
            "no-output pulses must not issue redundant EGL swaps",
            native.contains("if (outputKind == apex::APEX_OUTPUT_NONE)"),
        )
        assertTrue(
            "presenter must count source arrivals independently from displayed source frames",
            presenter.contains("ApexPresentationTelemetry.recordSourceArrival"),
        )
        assertTrue(
            "source admission must honor the source cap before DIS history advances",
            presenter.contains("shouldAcceptSource(frameTimeNanos)") &&
                presenter.contains("renderer.fpsLimit"),
        )
        assertTrue(
            "generated work must be admitted by the source-protected future-slot scheduler",
            presenter.contains("ApexSourceProtectedScheduler") &&
                presenter.contains("scheduler.generationBudget") &&
                presenter.contains("scheduler.shouldPresentSourceNow"),
        )
        assertTrue(
            "a newer source must be held until the previously buffered real frame is presented",
            presenter.contains("pendingSourceFrame") &&
                presenter.contains("nativeHasPendingSource") &&
                presenter.contains("nativePresentPendingSourceFrame"),
        )
    }

    @Test
    fun presenterDrainsPendingDisplayCallbacksBeforeQuittingItsLooper() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()

        val stopStart = presenter.indexOf("fun stop()")
        val companionStart = presenter.indexOf("companion object", stopStart)
        assertTrue(stopStart >= 0 && companionStart > stopStart)
        val stopMethod = presenter.substring(stopStart, companionStart)

        assertTrue(
            "presenter teardown must keep the display looper alive briefly after removing callbacks",
            stopMethod.contains("scheduleThreadShutdown") &&
                presenter.contains("THREAD_DRAIN_DELAY_MS") &&
                presenter.contains("postDelayed"),
        )
        assertFalse(
            "stop() must not synchronously quit the Choreographer looper after removing its callback",
            stopMethod.contains("latch.await(2, TimeUnit.SECONDS)\n        thread.quitSafely()"),
        )
    }


    @Test
    fun sourceOrientationAndRuntimeSettingsAreStableContracts() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val screen = repoFile("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()

        assertTrue(pipeline.contains("sourceVerticalFlip"))
        assertTrue(pipeline.contains("const float sourceUSpan = sourceUScale"))
        assertTrue(pipeline.contains("sourceVSpan = sourceVerticalFlip ? -sourceVScale : sourceVScale"))
        assertFalse(
            "Apex Vulkan ingestion must not horizontally mirror the source",
            pipeline.contains("sourceUSpan = sourceVerticalFlip ? -sourceUScale"),
        )
        assertTrue(engine.contains("mQualityPreset.exchange"))
        assertTrue(engine.contains("mResourcesDirty.store(true"))
        assertTrue(engine.contains("mPendingRealPresentation"))
        assertTrue(engine.contains("mActiveGenerationBudget"))
        assertTrue(engine.contains("presentPendingReal"))
        assertFalse(pipeline.contains("sourcePreemptsPending"))
        assertTrue(pipeline.contains("generatedOpportunityBudget < 0"))
        assertTrue(pipeline.contains("presentPendingReal"))
        assertTrue(
            "resource rebuilds must invalidate temporal history before new DIS work",
            pipeline.contains("Any resource rebuild invalidates color/luma/flow history") &&
                pipeline.contains("mRealFramesCaptured.store(0"),
        )
        assertTrue(screen.contains("ApexPresentationTelemetry.snapshot()"))
        assertTrue(screen.contains("presentation.active"))
        assertTrue(screen.contains("presentation.sourceInputFps"))
        assertTrue(screen.contains("SRC %.1f | OUT %.1f | GEN %.1f%s"))
    }

    @Test
    fun presenterStopsBeforeNativeTargetTeardown() {
        val renderer = repoFile(
            "app/src/main/java/com/winlator/renderer/VulkanRenderer.java",
        ).readText()

        val methodStart = renderer.indexOf("public boolean setApexFrameTargetEnabled(boolean enabled)")
        val pollStart = renderer.indexOf("public ApexFrame pollApexFrame()", methodStart)
        assertTrue(methodStart >= 0 && pollStart > methodStart)
        val method = renderer.substring(methodStart, pollStart)

        val retirePresenter = method.indexOf("retireApexPresenter()")
        val nativeDisable = method.indexOf("nativeDisableApexTarget", retirePresenter)

        assertTrue("transactional disable must retire the presenter", retirePresenter >= 0)
        assertTrue(
            "presenter must stop and release consumer-owned frames before native target teardown",
            nativeDisable > retirePresenter,
        )
        assertFalse(
            "transactional disable must keep the last Apex child layer latched until a normal present",
            method.substring(retirePresenter, nativeDisable)
                .contains("releaseApexPresenterLayer()"),
        )
    }

}
