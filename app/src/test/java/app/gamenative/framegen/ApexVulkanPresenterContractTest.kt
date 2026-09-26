package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertEquals
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
                presenter.contains("scheduler.generationBudget"),
        )
        assertTrue(
            "a newer source must remain queued until the admitted prefix and buffered real frame finish naturally",
            presenter.contains("pendingSourceFrame") &&
                presenter.contains("nativeHasPendingSource"),
        )
        assertFalse(
            "source-protection preemption must not cancel admitted synthetic work",
            presenter.contains("shouldPreemptForQueuedSource") ||
                presenter.contains("shouldPresentSourceNow") ||
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
        assertTrue(engine.contains("mQualityPreset.store"))
        assertTrue(pipeline.contains("sw == mScaledWidth") && pipeline.contains("fw == mFlowWidth") && pipeline.contains("fh == mFlowHeight"))
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
        assertFalse(engine.contains("presentPendingReal"))
        assertFalse(pipeline.contains("sourcePreemptsPending"))
        assertTrue(pipeline.contains("generatedOpportunityBudget < 0"))
        assertFalse(pipeline.contains("presentPendingReal"))
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
    fun queuedSourceNeverPreemptsAnAdmittedSyntheticPrefix() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val scheduler = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexCadenceScheduler.kt",
        ).readText()
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val nativePresenter = repoFile(
            "app/src/main/cpp/apex/apex_vulkan_presenter.cpp",
        ).readText()
        val bridge = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexNativeBridge.kt",
        ).readText()

        val pendingStart = presenter.indexOf("if (nativeHasPendingSource(handle))")
        val sourceBranch = presenter.indexOf("} else {", pendingStart)
        assertTrue(pendingStart >= 0 && sourceBranch > pendingStart)
        val pendingBranch = presenter.substring(pendingStart, sourceBranch)

        assertTrue(
            "an active admitted prefix must advance only through generated presentation opportunities",
            pendingBranch.contains("presentGeneratedOpportunity(handle"),
        )
        listOf(
            "shouldPreemptForQueuedSource",
            "shouldPresentSourceNow",
            "queuedSourcePreempt",
            "sourceDeadlinePreempt",
            "nativePresentPendingSourceFrame",
            "nativeConsumeAbandonedSyntheticSlots",
            "recordSyntheticSlotsAbandoned",
        ).forEach { token ->
            assertFalse("presenter still contains source-protection token $token", presenter.contains(token))
        }
        listOf(
            "shouldPreemptForQueuedSource",
            "shouldPresentSourceNow",
            "pendingSourceDeadlineNanos",
            "presentationWindowDeadlineNanos",
            "QUEUED_SOURCE_SLOT_ALLOWANCE",
            "QUEUED_SOURCE_CADENCE_ALLOWANCE",
        ).forEach { token ->
            assertFalse("scheduler still contains source-protection token $token", scheduler.contains(token))
        }
        assertFalse(engine.contains("presentPendingReal"))
        assertFalse(engine.contains("mAbandonedSyntheticSlots"))
        assertFalse(pipeline.contains("presentPendingReal"))
        assertFalse(nativePresenter.contains("nativePresentPendingSourceFrame"))
        assertFalse(bridge.contains("nativeConsumeAbandonedSyntheticSlots"))

        assertTrue(
            "source cadence must still be recorded from producer time when the queued frame becomes active",
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
    fun presenterTracksDeliveredSyntheticProgressWithoutSourceProtectionPolicy() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val scheduler = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexCadenceScheduler.kt",
        ).readText()

        assertTrue(
            "successful generated presentation must advance scheduler prefix progress",
            presenter.contains("OUTPUT_GENERATED") &&
                presenter.contains("scheduler.onGeneratedPresented"),
        )
        assertTrue(
            "cadence diagnostics must expose remaining synthetic work",
            scheduler.contains("remainingSyntheticSlots"),
        )
        assertFalse(presenter.contains("queuedSourcePreempt"))
        assertFalse(presenter.contains("sourceDeadlinePreempt"))
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
    fun boundedReadyAheadKeepsGeneratedDisplayCallbacksComputeFree() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val presenter = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()

        val newSourceStart = pipeline.indexOf("if (isNewRealFrame)")
        val pulseStart = pipeline.indexOf(
            "// Display opportunity with no newly accepted source.",
            newSourceStart,
        )
        assertTrue(newSourceStart >= 0 && pulseStart > newSourceStart)
        val sourcePath = pipeline.substring(newSourceStart, pulseStart)
        assertTrue(sourcePath.contains("prepareGeneratedSlot(0"))
        assertFalse(sourcePath.contains("dispatchInterpolateBatch("))

        val readyStart = pipeline.indexOf("void ApexEngine::presentGeneratedReady")
        val processWithData = pipeline.indexOf("void ApexEngine::processFrameWithData", readyStart)
        assertTrue(readyStart >= 0 && processWithData > readyStart)
        val readyPath = pipeline.substring(readyStart, processWithData)
        assertFalse(
            "display-time ready-frame selection must not submit interpolation compute",
            readyPath.contains("dispatchInterpolate("),
        )
        assertTrue(readyPath.contains("mGeneratedBatchTex[fs]"))

        assertTrue(
            "the native presenter must refill future interpolation only after the current swap",
            presenter.contains("prepareNextGeneratedReady()"),
        )
    }


    @Test
    fun dormantVrResourcesAreNotAllocatedByActiveApexPipeline() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        listOf(
            "mNativeWarpTex",
            "vrATex",
            "vrBTex",
            "vrDWTex",
            "\"NativeWarpTex\"",
            "\"vrATex\"",
            "\"vrBTex\"",
            "\"vrDWTex0\"",
            "\"vrDWTex1\"",
        ).forEach { token ->
            assertFalse(
                "active Apex pipeline still allocates dormant resource $token",
                engine.contains(token) || pipeline.contains(token),
            )
        }
    }


    @Test
    fun generatedBatchIsRefilledOneSlotAtATimeAfterSwap() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val presenter = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()

        val newSourceStart = pipeline.indexOf("if (isNewRealFrame)")
        val pulseStart = pipeline.indexOf(
            "// Display opportunity with no newly accepted source.",
            newSourceStart,
        )
        assertTrue(newSourceStart >= 0 && pulseStart > newSourceStart)
        val sourcePath = pipeline.substring(newSourceStart, pulseStart)

        assertTrue(
            "new-source work must prepare only the first synthetic required for immediate generation-first presentation",
            sourcePath.contains("prepareGeneratedSlot(0") ||
                sourcePath.contains("prepareGeneratedSlot(\n            0"),
        )
        assertFalse(
            "new-source work must not submit the entire admitted 2x/3x/4x interpolation batch in one GPU burst",
            sourcePath.contains("dispatchInterpolateBatch("),
        )

        assertTrue(
            "native presenter must refill at most one future synthetic after a successful swap",
            presenter.contains("prepareNextGeneratedReady("),
        )
        val generatedPulseStart = presenter.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentGeneratedFrame",
        )
        assertTrue(generatedPulseStart >= 0)
        val generatedPulse = presenter.substring(generatedPulseStart)
        val swap = generatedPulse.indexOf("eglSwapBuffers")
        val refill = generatedPulse.indexOf("prepareNextGeneratedReady", swap)
        assertTrue(
            "refill must happen after swap so interpolation submission is spread across presentation intervals",
            swap >= 0 && refill > swap,
        )
    }


    @Test
    fun generatedPrefixProgressCommitsOnlyAfterSuccessfulSwap() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val presenter = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()

        assertTrue(
            "ApexEngine must expose an explicit successful-presentation commit seam",
            engine.contains("commitPresentedOutput"),
        )

        val readyStart = pipeline.indexOf("void ApexEngine::presentGeneratedReady")
        val processWithData = pipeline.indexOf("void ApexEngine::processFrameWithData", readyStart)
        assertTrue(readyStart >= 0 && processWithData > readyStart)
        val readyPath = pipeline.substring(readyStart, processWithData)
        assertFalse(
            "generated prefix progress must not advance before eglSwapBuffers succeeds",
            readyPath.contains("mFramesSinceReal.store("),
        )
        assertFalse(
            "source-prefix ownership must not clear before source swap succeeds",
            readyPath.contains("mPendingRealPresentation.store(false"),
        )

        val sourcePresentStart = presenter.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentSourceFrame",
        )
        val generatedPresentStart = presenter.indexOf(
            "Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentGeneratedFrame",
        )
        assertTrue(sourcePresentStart >= 0 && generatedPresentStart > sourcePresentStart)
        val sourcePresent = presenter.substring(sourcePresentStart, generatedPresentStart)
        val generatedPresent = presenter.substring(generatedPresentStart)

        assertTrue(
            "source-path state must commit only after a successful swap",
            sourcePresent.contains("if (swapSucceeded") &&
                sourcePresent.contains("commitPresentedOutput(outputKind)"),
        )
        assertTrue(
            "generated-path state must commit only after a successful swap",
            generatedPresent.contains("if (swapSucceeded") &&
                generatedPresent.contains("commitPresentedOutput(outputKind)"),
        )
    }

    @Test
    fun flowScaleChangesProcessingResolutionWithoutShrinkingMotionVectors() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val shaders = repoFile("app/src/main/cpp/apex/apex_shaders.h").readText()

        assertTrue(
            "Flow Scale must remain part of optical-flow resource sizing",
            pipeline.contains("mFlowScale.load") &&
                engine.contains("setFlowScale"),
        )
        assertFalse(
            "LSFG-style Flow Scale must not also attenuate the computed motion-vector magnitude",
            shaders.contains("sampleFlow(denseFlow, uv) * (u_flowScale"),
        )
        val interpolateStart = pipeline.indexOf("void ApexEngine::dispatchInterpolate")
        val rcasStart = pipeline.indexOf("void ApexEngine::dispatchRcas", interpolateStart)
        assertTrue(interpolateStart >= 0 && rcasStart > interpolateStart)
        val interpolate = pipeline.substring(interpolateStart, rcasStart)
        assertFalse(
            "interpolation must not feed the processing-resolution scale back into motion magnitude",
            interpolate.contains("mFlowScale.load"),
        )
    }

    @Test
    fun readyAheadInterpolationCostCountsActualPreparedSyntheticFrames() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        val prepareStart = pipeline.indexOf("bool ApexEngine::prepareGeneratedSlot")
        val refillStart = pipeline.indexOf("bool ApexEngine::prepareNextGeneratedReady", prepareStart)
        assertTrue(prepareStart >= 0 && refillStart > prepareStart)
        val prepare = pipeline.substring(prepareStart, refillStart)

        assertTrue(
            "every prepared synthetic slot must contribute to the measured interpolation cost",
            prepare.contains("mLastSyntheticCostNanos.store"),
        )
        assertTrue(
            "the cost sample count must track prepared synthetics rather than the admitted budget",
            prepare.contains("mLastSyntheticCostBudget.store"),
        )
        assertFalse(
            "admitted 3x/4x budget must not be reported as if every synthetic had already been measured",
            pipeline.contains("mLastSyntheticCostBudget.store(generationBudget"),
        )
    }


    @Test
    fun presenterRefreshesPhysicalPresentationCeilingDuringRuntime() {
        val presenter = repoFile(
            "app/src/main/java/app/gamenative/framegen/ApexVulkanPresenter.kt",
        ).readText()
        val renderer = repoFile(
            "app/src/main/java/com/winlator/renderer/VulkanRenderer.java",
        ).readText()

        assertTrue(
            "VulkanRenderer must expose the Apex layer's requested physical presentation rate",
            renderer.contains("getApexPresentationRefreshRate"),
        )
        assertTrue(
            "presenter must periodically refresh scheduler ceiling after Android display-mode changes",
            presenter.contains("refreshPresentationCeiling") &&
                presenter.contains("REFRESH_RATE_RECHECK_CALLBACKS") &&
                presenter.contains("renderer.getApexPresentationRefreshRate()"),
        )
    }

    @Test
    fun flowAndQualityChangesRebuildOnlyWhenEffectiveResourceDimensionsChange() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        for (setter in listOf("setQualityPreset", "setFlowScale", "setFlowShortSideCap", "setRenderScale")) {
            val start = engine.indexOf("void $setter")
            assertTrue("$setter missing", start >= 0)
            val end = engine.indexOf("\n    }", start)
            assertTrue(end > start)
            val body = engine.substring(start, end)
            assertFalse(
                "$setter must not force a full temporal-history rebuild when the resulting dimensions are unchanged",
                body.contains("mResourcesDirty.store(true"),
            )
        }
        assertTrue(
            "ensureResources must retain dimension equality as the authoritative rebuild check",
            pipeline.contains("sw == mScaledWidth") &&
                pipeline.contains("fw == mFlowWidth") &&
                pipeline.contains("fh == mFlowHeight"),
        )
    }

    @Test
    fun interpolationControlsAreSnapshottedForTheWholeAdmittedSourcePair() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        assertTrue(engine.contains("InterpolationSettings"))
        assertTrue(engine.contains("mActiveInterpolationSettings"))
        assertTrue(pipeline.contains("snapshotInterpolationSettings"))
        val interpolateStart = pipeline.indexOf("void ApexEngine::dispatchInterpolate")
        val next = pipeline.indexOf("bool ApexEngine::isHealthy", interpolateStart)
        assertTrue(interpolateStart >= 0 && next > interpolateStart)
        val interpolate = pipeline.substring(interpolateStart, next)
        assertTrue(interpolate.contains("mActiveInterpolationSettings"))
        assertFalse(interpolate.contains("mLiquidFeel.load"))
        assertFalse(interpolate.contains("mShutterGain.load"))
        assertFalse(interpolate.contains("mEdgeGuard.load"))
    }

    @Test
    fun activeApexHealthCompilesOnlyShadersUsedByTheRuntimePath() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        listOf("DisVrSetup", "DisVrSor", "DisRcas").forEach { dormant ->
            assertFalse(
                "dormant compute shader $dormant must not be compiled or required for Apex health",
                pipeline.contains("compileOne(\"$dormant\""),
            )
        }
        assertTrue(pipeline.contains("mCompiledShaderCount == 5"))
        assertTrue(pipeline.contains("5/5 compute shaders"))
        assertFalse(engine.contains("mProgVrSetup"))
        assertFalse(engine.contains("mProgVrSor"))
        assertFalse(engine.contains("mProgRcas"))
    }

    @Test
    fun apexDiagnosticsAreSampledWithoutPermanentHotPathCounterTraffic() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        assertTrue(
            "GPU timing should remain available but at a sparse optimization/debug cadence",
            engine.contains("GPU_TIMER_SAMPLE_INTERVAL = 120"),
        )
        assertTrue(
            "hot pass counters must be conditional rather than atomic work on every dispatch",
            pipeline.contains("if (collectTelemetry) mPassLumaGrad") &&
                pipeline.contains("if (collectTelemetry) mPassInterpolate"),
        )
        assertTrue(
            "resource telemetry must report requested/effective Flow Scale dimensions",
            pipeline.contains("Apex flow config:") &&
                pipeline.contains("requestedScale=") &&
                pipeline.contains("effectiveShortSide="),
        )
    }

    @Test
    fun adreno650FlowScaleHasAQualityFloorWithoutRestoringMotionAttenuation() {
        val presenter = repoFile("app/src/main/cpp/apex/apex_vulkan_presenter.cpp").readText()
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        assertTrue(presenter.contains("setFlowShortSideFloor(120)"))
        assertTrue(engine.contains("setFlowShortSideFloor"))
        assertTrue(pipeline.contains("mFlowShortSideFloor.load"))
        assertTrue(pipeline.contains("std::max(minSide"))
    }

    @Test
    fun presenterBudgetPathSkipsDuplicateLegacyNativeCadenceWork() {
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()
        val frameCaptured = pipeline.indexOf("onFrameCaptured(sourceClockNanos")
        assertTrue(frameCaptured >= 0)
        val context = pipeline.substring(
            kotlin.math.max(0, frameCaptured - 500),
            kotlin.math.min(pipeline.length, frameCaptured + 500),
        )
        assertTrue(
            "legacy native pacing should run only when no presenter budget is supplied",
            context.contains("generatedOpportunityBudget < 0"),
        )
    }

}
