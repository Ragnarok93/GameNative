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
            "generated presentation must use the ready-batch path with no source texture dependency",
            native.contains("presentGeneratedReady("),
        )
        val readyStart = pipeline.indexOf("void ApexEngine::presentGeneratedReady")
        val readyEnd = pipeline.indexOf("void ApexEngine::processFrameWithData", readyStart)
        assertTrue(readyStart >= 0 && readyEnd > readyStart)
        val readyPath = pipeline.substring(readyStart, readyEnd)
        assertFalse(
            "ready generated presentation must not read the released source AHB texture",
            readyPath.contains("inputTextureId") ||
                readyPath.contains("ImportedSource") ||
                readyPath.contains("getOrImportSource"),
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
            "source admission must honor the source cap at the producer timestamp before DIS history advances",
            presenter.contains("shouldAcceptSource(frame.sourceTimestampNanos)") &&
                presenter.contains("renderer.fpsLimit"),
        )
        assertTrue(
            "generated work must be admitted by the target-authoritative future-slot scheduler",
            presenter.contains("ApexCadenceScheduler") &&
                presenter.contains("scheduler.generationBudget") &&
                presenter.contains("scheduler.shouldPresentSourceNow"),
        )
        assertTrue(
            "a newer source must be held until the previously buffered real frame is presented",
            presenter.contains("pendingSourceFrame") &&
                presenter.contains("nativeHasPendingSource") &&
                presenter.contains("nativePresentPendingSourceFrame"),
        )
        assertTrue(
            "source cadence and source-cap admission must use the producer timestamp",
            presenter.contains("frame.sourceTimestampNanos") &&
                presenter.contains("scheduler.recordSourceFrame(sourceTimestampNanos)") &&
                presenter.contains("shouldAcceptSource(frame.sourceTimestampNanos)"),
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
        assertFalse(
            "zero-generation intervals must not invalidate flow history",
            engine.contains("mFlowHistoryReady") ||
                engine.contains("mGenerationReprimeCount") ||
                pipeline.contains("mFlowHistoryReady") ||
                pipeline.contains("mGenerationReprimeCount"),
        )
        assertFalse(
            "native diagnostics must not claim the deleted source-protection architecture is active",
            pipeline.contains("source-protected"),
        )
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


    @Test
    fun queuedSourceCanPreemptSyntheticPrefixToProtectRealSource() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()

        assertTrue(presenter.contains("pendingSourceTimestampNanos"))
        val pendingStart = presenter.indexOf("if (nativeHasPendingSource(handle))")
        val pendingEnd = presenter.indexOf("} else {", pendingStart)
        assertTrue(pendingStart >= 0 && pendingEnd > pendingStart)
        val pendingBranch = presenter.substring(pendingStart, pendingEnd)
        assertTrue(
            "queued real input must participate in the preemption decision",
            pendingBranch.contains("pendingSourceFrame != null") &&
                pendingBranch.contains("scheduler.shouldPreemptForQueuedSource") &&
                pendingBranch.contains("queuedSourceTimestampNanos = pendingSourceTimestampNanos"),
        )
        assertTrue(
            "preemption must abandon unused synthetic slots instead of carrying debt",
            pendingBranch.contains("nativeConsumeAbandonedSyntheticSlots") &&
                pendingBranch.contains("recordSyntheticSlotsAbandoned"),
        )
        assertTrue(
            "source cadence must be recorded from producer time only when the queued frame becomes active",
            presenter.contains("scheduler.recordSourceFrame(sourceTimestampNanos)"),
        )
    }

    @Test
    fun zeroBudgetKeepsTemporalPyramidWarmButSkipsSyntheticFlowWork() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val budget = pipeline.indexOf("const int generationBudget")
        val currentPyramid = pipeline.indexOf(
            "dispatchLumaGrad(0, mFlowColorTex[mCurrentSlot]",
            budget,
        )
        val zero = pipeline.indexOf("generationBudget <= 0", currentPyramid)
        val search = pipeline.indexOf("dispatchHierarchicalSearch(", zero)

        assertTrue(
            "source pyramid must stay warm before the zero-generation early return",
            budget >= 0 && currentPyramid > budget && zero > currentPyramid,
        )
        assertTrue(
            "expensive optical-flow search must remain behind the zero-generation gate",
            search > zero,
        )
        assertTrue(
            "zero-generation intervals must record that synthetic work was skipped",
            pipeline.contains("mNoGenerationSourceFrames.fetch_add"),
        )
        assertFalse(
            "zero-generation intervals must not reintroduce history invalidation/reprime state",
            pipeline.contains("mFlowHistoryReady") ||
                pipeline.contains("mGenerationReprimeCount"),
        )
    }

    @Test
    fun presenterSeparatesProcessingAndPresentationExtentsAndCachesPersistentAhbImports() {
        val native = repoFile(
            "app/src/main/cpp/apex/apex_vulkan_presenter.cpp",
        ).readText()
        val renderer = repoFile(
            "app/src/main/cpp/winlator/VulkanRendererContext.cpp",
        ).readText()

        assertTrue(
            "Apex Vulkan target must derive a processing extent that is independent of the presentation extent",
            renderer.contains("computeApexProcessingExtent") &&
                renderer.contains("APEX_MIN_PROCESSING_SHORT_SIDE") &&
                renderer.contains("APEX_PROCESSING_SCALE"),
        )
        assertTrue(
            "presenter must track source and output dimensions independently",
            native.contains("sourceWidth") &&
                native.contains("sourceHeight") &&
                native.contains("outputWidth") &&
                native.contains("outputHeight") &&
                native.contains("eglQuerySurface"),
        )
        val sourcePresentStart = native.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentSourceFrame",
        )
        val pendingPresentStart = native.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativeHasPendingSource",
            sourcePresentStart,
        )
        assertTrue(sourcePresentStart >= 0 && pendingPresentStart > sourcePresentStart)
        val sourcePresent = native.substring(sourcePresentStart, pendingPresentStart)
        assertFalse(
            "source AHB dimensions must not resize the Android presentation surface",
            sourcePresent.contains("ANativeWindow_setBuffersGeometry"),
        )
        assertTrue(
            "presenter creation must size the EGL child surface from the real presentation extent",
            native.contains("jint outputWidth") &&
                native.contains("jint outputHeight") &&
                native.contains("ANativeWindow_setBuffersGeometry"),
        )
        assertTrue(
            "persistent Vulkan AHB ring entries must keep one persistent EGL/GL import per AHB",
            native.contains("std::unordered_map<AHardwareBuffer*, ImportedSource>") &&
                native.contains("getOrImportSource") &&
                native.contains("destroyImportedSources"),
        )
    }

    @Test
    fun processingExtentDoesNotExpandSourceUvSpanWhenPresentationIsLarger() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val presenter = repoFile(
            "app/src/main/cpp/apex/apex_vulkan_presenter.cpp",
        ).readText()

        assertTrue(
            "Apex processFrame must expose presentation dimensions separately from source crop dimensions",
            engine.contains("int outputViewWidth = 0") &&
                engine.contains("int outputViewHeight = 0"),
        )
        assertTrue(
            "source UV span must continue to derive from the source crop, not the presentation extent",
            pipeline.contains(
                "const float sourceUScale = static_cast<float>(viewWidth) / static_cast<float>(width)",
            ) &&
                pipeline.contains(
                    "const int presentationWidth = outputViewWidth > 0 ? outputViewWidth : viewWidth",
                ),
        )
        assertTrue(
            "the Vulkan presenter must pass source-sized crop coordinates and native presentation dimensions separately",
            presenter.contains("width,\n        height,\n        true,") &&
                presenter.contains("presenter->outputWidth,\n        presenter->outputHeight"),
        )
    }

    @Test
    fun presenterUsesProcessingSizedBuffersAndSamplesCriticalPathWithoutHotLoopEglRebinds() {
        val javaPresenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val renderer = repoFile(
            "app/src/main/java/com/winlator/renderer/VulkanRenderer.java",
        ).readText()
        val native = repoFile(
            "app/src/main/cpp/apex/apex_vulkan_presenter.cpp",
        ).readText()
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        assertTrue(
            "SurfaceFlinger must scale the processing-sized Apex layer to the display extent",
            renderer.contains(".setScale(") &&
                renderer.contains("getApexTargetWidth") &&
                renderer.contains("getApexTargetHeight"),
        )
        assertTrue(
            "the EGL window must be created at the processing extent, not the physical display extent",
            javaPresenter.contains("renderer.getApexTargetWidth()") &&
                javaPresenter.contains("renderer.getApexTargetHeight()"),
        )

        val sourceStart = native.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentSourceFrame",
        )
        val sourceEnd = native.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativeHasPendingSource",
            sourceStart,
        )
        val generatedStart = native.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentGeneratedFrame",
        )
        assertTrue(sourceStart >= 0 && sourceEnd > sourceStart && generatedStart > sourceEnd)
        val sourceHotPath = native.substring(sourceStart, sourceEnd)
        val generatedHotPath = native.substring(generatedStart)

        assertFalse(
            "dedicated presenter thread must not call eglMakeCurrent for every source frame",
            sourceHotPath.contains("makeCurrent(*presenter)"),
        )
        assertFalse(
            "dedicated presenter thread must not query EGL surface extent for every source frame",
            sourceHotPath.contains("refreshOutputExtent(*presenter)"),
        )
        assertFalse(
            "generated pulses must not call eglMakeCurrent on every display callback",
            generatedHotPath.contains("makeCurrent(*presenter)"),
        )

        listOf(
            "Apex presenter cost:",
            "acquire_ms=",
            "process_ms=",
            "release_ms=",
            "swap_ms=",
            "total_ms=",
        ).forEach { token ->
            assertTrue("presenter critical-path telemetry is missing $token", native.contains(token))
        }

        assertTrue(
            "Adreno 650 must use the demonstrated 180p flow ceiling",
            native.contains("Adreno (TM) 650") &&
                native.contains("setFlowShortSideCap(180)"),
        )
        assertTrue(
            "flow scale must affect resource sizing and invalidate resources when changed",
            engine.contains("setFlowShortSideCap") &&
                engine.contains("mResourcesDirty.store(true") &&
                pipeline.contains("mFlowScale.load") &&
                pipeline.contains("mFlowShortSideCap.load"),
        )
    }


    @Test
    fun presenterTracksDeliveredSyntheticProgressBeforeMakingPreemptionDecisions() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val scheduler = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexCadenceScheduler.kt",
        ).readText()

        assertTrue(
            "successful generated presentation must advance scheduler prefix progress",
            presenter.contains("OUTPUT_GENERATED") &&
                presenter.contains("scheduler.onGeneratedPresented()"),
        )
        assertTrue(
            "cadence diagnostics must expose remaining synthetic work",
            scheduler.contains("remainingSyntheticSlots"),
        )
        assertTrue(
            "abandonment telemetry must distinguish queued-source and active-source deadline preemption",
            presenter.contains("queuedSourcePreempt") &&
                presenter.contains("sourceDeadlinePreempt"),
        )
    }

    @Test
    fun apexHotGlPathsCacheUniformsAndAvoidRedundantBlitStateWork() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        val blitStart = pipeline.indexOf("void ApexEngine::blitQuad")
        val dispatchStart = pipeline.indexOf("void ApexEngine::dispatchLumaGrad", blitStart)
        val processStart = pipeline.indexOf("void ApexEngine::processFrame", dispatchStart)
        assertTrue(blitStart >= 0 && dispatchStart > blitStart && processStart > dispatchStart)

        val blitHotPath = pipeline.substring(blitStart, dispatchStart)
        val dispatchHotPath = pipeline.substring(dispatchStart, processStart)

        assertFalse(
            "fullscreen output blits must not query GL enable state every presented frame",
            blitHotPath.contains("glIsEnabled"),
        )
        assertFalse(
            "texture sampling parameters are immutable and must not be re-applied on every blit",
            blitHotPath.contains("glTexParameteri"),
        )
        assertFalse(
            "uniform locations must be cached after program link, not looked up inside compute dispatches",
            dispatchHotPath.contains("glGetUniformLocation"),
        )
        assertFalse(
            "blit uniform locations must be cached after program link",
            blitHotPath.contains("glGetUniformLocation"),
        )
        assertTrue(
            "ApexEngine must retain cached uniform locations",
            engine.contains("UniformLocations") ||
                engine.contains("mQuadTexLoc"),
        )
    }

    @Test
    fun disabledMathTelemetryDoesNotPayPerDispatchSsboSynchronizationCost() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val dispatchStart = pipeline.indexOf("void ApexEngine::dispatchLumaGrad")
        val processStart = pipeline.indexOf("void ApexEngine::processFrame", dispatchStart)
        assertTrue(dispatchStart >= 0 && processStart > dispatchStart)
        val dispatchHotPath = pipeline.substring(dispatchStart, processStart)

        assertTrue(
            "shader-storage barriers must be conditional on telemetry collection",
            dispatchHotPath.contains("telemetryBarrier") ||
                dispatchHotPath.contains("collectTelemetry ? GL_SHADER_STORAGE_BARRIER_BIT"),
        )
        assertFalse(
            "telemetry SSBO must be bound once outside individual dispatch functions",
            dispatchHotPath.contains(
                "glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, mTelemetrySsbo)",
            ),
        )
    }


    @Test
    fun schedulerAdmissionDoesNotScanFullPresentationTelemetryOnEverySource() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val scheduler = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexCadenceScheduler.kt",
        ).readText()

        assertFalse(
            "admission must use the scheduler's local display-period measurement instead of rescanning six telemetry rings for every accepted source",
            presenter.contains("ApexPresentationTelemetry.snapshot(frameTimeNanos)"),
        )
        assertFalse(
            "scheduler generationBudget must not require a full presentation telemetry snapshot",
            scheduler.contains("presentation: ApexPresentationTelemetry.Snapshot"),
        )
    }

    @Test
    fun dedicatedPresenterAvoidsRedundantGlCleanupAcrossGeneratedPulses() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        val interpolateStart = pipeline.indexOf("void ApexEngine::dispatchInterpolate")
        val rcasStart = pipeline.indexOf("void ApexEngine::dispatchRcas", interpolateStart)
        val healthyStart = pipeline.indexOf("bool ApexEngine::isHealthy", rcasStart)
        assertTrue(interpolateStart >= 0 && rcasStart > interpolateStart && healthyStart > rcasStart)
        val interpolate = pipeline.substring(interpolateStart, rcasStart)
        val rcas = pipeline.substring(rcasStart, healthyStart)

        assertTrue(
            "dedicated Apex context must skip texture/image unbind churn after interpolation",
            interpolate.contains("if (!mDedicatedPresentationContext)"),
        )
        assertTrue(
            "dedicated Apex context must skip RCAS cleanup that the next pass overwrites",
            rcas.contains("if (!mDedicatedPresentationContext)"),
        )
        assertTrue(
            "program-cache state must be reset whenever dedicated-context ownership changes",
            pipeline.substring(
                pipeline.indexOf("void ApexEngine::setDedicatedPresentationContext"),
                pipeline.indexOf("ApexEngine::BlitStateSnapshot", pipeline.indexOf("void ApexEngine::setDedicatedPresentationContext")),
            ).contains("mBoundProgram = 0"),
        )
        assertTrue(
            "dedicated fullscreen quad VAO must stay bound rather than bind/unbind on every output",
            pipeline.contains("if (!mDedicatedPresentationContext && mQuadVao) glBindVertexArray(mQuadVao)") &&
                pipeline.contains("if (!mDedicatedPresentationContext && mQuadVao) glBindVertexArray(0)"),
        )
    }


    @Test
    fun admittedSyntheticBatchIsPreparedBeforeDisplayCallbacks() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        assertTrue(
            "Apex must retain one ready texture per possible generated slot",
            engine.contains("mGeneratedBatchTex") &&
                engine.contains("MAX_GENERATED_FRAMES"),
        )

        val newSourceStart = pipeline.indexOf("if (isNewRealFrame)")
        val pulseStart = pipeline.indexOf(
            "// Display opportunity with no newly accepted source.",
            newSourceStart,
        )
        assertTrue(newSourceStart >= 0 && pulseStart > newSourceStart)
        val sourcePath = pipeline.substring(newSourceStart, pulseStart)
        val pulsePath = pipeline.substring(pulseStart)

        assertTrue(
            "source processing must prepare every admitted interpolation position before presenting the first synthetic",
            sourcePath.contains("for (int generatedIndex = 0; generatedIndex < generationBudget; ++generatedIndex)") &&
                sourcePath.contains("mGeneratedBatchTex[generatedIndex]"),
        )
        assertFalse(
            "generated display callbacks must not run interpolation compute on the display deadline",
            pulsePath.contains("dispatchInterpolate("),
        )
        assertTrue(
            "generated display callbacks must present an already prepared batch texture",
            pulsePath.contains("mGeneratedBatchTex[fs]"),
        )
    }

}
