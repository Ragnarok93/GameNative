package com.winlator.renderer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgFrameQueueContractTest {
    @Test
    fun quickMenuOwnsAnIndependentFrameQueueToggleAndThreeTargets() {
        val quickMenu = repoSource("app/src/main/java/app/gamenative/ui/component/QuickMenu.kt")
        val helper = repoSource("app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt")

        assertTrue(helper.contains("enum class FrameQueueTarget"))
        assertTrue(helper.contains("UNBUFFERED"))
        assertTrue(helper.contains("BALANCED"))
        assertTrue(helper.contains("SMOOTH"))
        assertTrue(helper.contains("frameQueueEnabled"))
        assertTrue(helper.contains("frameQueueTarget"))

        val queueUi = quickMenu.indexOf("lsfg_frame_queue")
        val presentUi = quickMenu.indexOf("lsfg_present_mode")
        assertTrue("Frame Queue UI must be above Mailbox/FIFO", queueUi >= 0 && presentUi > queueUi)
        assertTrue(quickMenu.contains("lsfg_frame_queue_unbuffered"))
        assertTrue(quickMenu.contains("lsfg_frame_queue_balanced"))
        assertTrue(quickMenu.contains("lsfg_frame_queue_smooth"))
    }

    @Test
    fun frameQueueControlsUseCompactTwoRowLayoutWithoutSingleLineClipping() {
        val quickMenu = repoSource("app/src/main/java/app/gamenative/ui/component/QuickMenu.kt")
        val strings = repoSource("app/src/main/res/values/strings_lsfg_adaptive.xml")

        assertTrue(quickMenu.contains("private fun LsfgFrameQueueTargetControls"))
        assertTrue(quickMenu.contains("FrameQueueTarget.UNBUFFERED"))
        assertTrue(quickMenu.contains("Modifier.fillMaxWidth()"))
        assertTrue(quickMenu.contains("FrameQueueTarget.BALANCED"))
        assertTrue(quickMenu.contains("FrameQueueTarget.SMOOTH"))
        assertTrue(quickMenu.contains("singleLine = false"))
        assertTrue(strings.contains(">Buffers final output frames to smooth brief stalls.<"))
        assertTrue(strings.contains(">More depth adds latency.<"))
    }

    @Test
    fun persistedConfigPublishesQueueMetadataWithoutOwningLsfgGeneration() {
        val manager = repoSource("app/src/main/java/app/gamenative/utils/LsfgVkManager.kt")

        assertTrue(manager.contains("EXTRA_FRAME_QUEUE_ENABLED"))
        assertTrue(manager.contains("EXTRA_FRAME_QUEUE_TARGET"))
        assertTrue(manager.contains("frame_queue_enabled"))
        assertTrue(manager.contains("frame_queue_target"))
    }

    @Test
    fun vulkanRendererKeepsFinalPresentInlineInsteadOfUsingASecondPresenterThread() {
        val javaRenderer = repoSource("app/src/main/java/com/winlator/renderer/VulkanRenderer.java")
        val jni = source("vulkan_jni.cpp")
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(javaRenderer.contains("setLsfgFrameQueue"))
        assertTrue(javaRenderer.contains("nativeSetLsfgFrameQueue"))
        assertTrue(jni.contains("nativeSetLsfgFrameQueue"))

        assertFalse(header.contains("hostPresentThread_"))
        assertFalse(header.contains("hostPresentQueue_"))
        assertFalse(header.contains("framePresentPending_"))
        assertFalse(header.contains("queuedRenderDoneSems_"))
        assertFalse(implementation.contains("hostPresentLoop"))
        assertFalse(implementation.contains("enqueueHostPresent"))
        assertFalse(implementation.contains("waitForHostPresentCapacity"))

        val renderFrameStart = implementation.indexOf("void VulkanRendererContext::renderFrame()")
        val renderFrameEnd = implementation.indexOf("void VulkanRendererContext::onSurfaceResized", renderFrameStart)
        assertTrue(renderFrameStart >= 0 && renderFrameEnd > renderFrameStart)
        val renderFrame = implementation.substring(renderFrameStart, renderFrameEnd)
        val submit = renderFrame.indexOf("vk_.QueueSubmit(")
        val present = renderFrame.indexOf("presentHostFrame(")
        assertTrue("Host present must remain inline after the matching submit", submit >= 0 && present > submit)
    }

    @Test
    fun enabledQueueTargetsMapToOneTwoAndThreeFrameSlotsWhileOffPreservesBaseline() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("if (!lsfgFrameQueueEnabled_.load"))
        assertTrue(implementation.contains("return BASE_FRAMES_IN_FLIGHT;"))
        assertTrue(implementation.contains("return target + 1U;"))
        assertTrue(implementation.contains("target = std::min<uint32_t>(2"))
        assertTrue(implementation.contains("uniqueLsfgContentPending"))
        assertTrue(implementation.contains("frameQueueEnabled && uniqueLsfgContentPending"))
        assertTrue(implementation.contains("hasUniqueLsfgDelivery"))
        assertTrue(implementation.contains("BASE_FRAMES_IN_FLIGHT"))
        assertTrue(implementation.contains("immutable composite snapshot"))
    }

    @Test
    fun bufferedModesNeverBuildMoreThanTwoUnfinishedGpuCompositorSubmissions() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("MAX_BUFFERED_GPU_SUBMISSIONS = 2"))
        assertTrue(header.contains("frameQueueRetirementWaitTotal_"))
        assertTrue(header.contains("frameQueueRetirementWaitNsTotal_"))
        assertTrue(implementation.contains("enforceFrameQueueSubmissionBudget"))
        assertTrue(implementation.contains("GetFenceStatus"))
        assertTrue(implementation.contains("WaitForFences"))
        assertTrue(implementation.contains("frameQueueRetirementWaitTotal_"))
        assertTrue(implementation.contains("retirement_waits="))
        assertTrue(implementation.contains("retirement_wait_ms="))
    }

    @Test
    fun queueTransitionsDrainGpuSubmissionsWithoutDeviceWideIdle() {
        val implementation = source("VulkanRendererContext.cpp")
        val start = implementation.indexOf("void VulkanRendererContext::setLsfgFrameQueue")
        assertTrue(start >= 0)
        val end = implementation.indexOf("\n}\n", start)
        assertTrue(end > start)
        val transition = implementation.substring(start, end + 3)

        assertTrue(transition.contains("std::unique_lock<std::shared_mutex>"))
        assertTrue(transition.contains("drainFrameQueueSubmissions"))
        assertFalse(transition.contains("DeviceWaitIdle"))
        assertFalse(transition.contains("QueueWaitIdle"))
        assertTrue(implementation.contains("event=transition-drain"))
    }


    @Test
    fun smoothRequiresPresentRetirementSupportAndFallsBackToBalancedDepthOtherwise() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("effectiveFrameQueueTarget"))
        assertTrue(implementation.contains("uint32_t VulkanRendererContext::effectiveFrameQueueTarget() const"))
        assertTrue(implementation.contains("frameQueueSmoothRuntimeSuppressed_"))
        assertTrue(implementation.contains("return 1;"))
        assertTrue(implementation.contains("requested_target="))
        assertTrue(implementation.contains("effective_target="))
        assertTrue(implementation.contains("smooth_fallback="))
        assertTrue(implementation.contains("present-stall"))
        assertFalse(implementation.contains("present-retirement-unavailable"))
    }

    @Test
    fun smoothBacksOffFromMeasuredPresentStallsWithoutPollingOrBlockingRetirement() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("SMOOTH_PRESENT_STALL_NS"))
        assertTrue(header.contains("frameQueueSmoothRuntimeSuppressed_"))
        assertTrue(implementation.contains("updateSmoothQueuePressure"))
        assertTrue(implementation.contains("presentNs < SMOOTH_PRESENT_STALL_NS"))
        assertTrue(implementation.contains("event=smooth-runtime-fallback reason=present-stall"))
        assertFalse(implementation.contains("enforceFrameQueuePresentationBudget"))
        assertFalse(implementation.contains("drainFrameQueuePresentations"))
        assertFalse(implementation.contains("FrameQueuePendingPresentation"))
        assertFalse(implementation.contains("kFrameQueuePresentRetirementTimeoutNs"))
        assertFalse(implementation.contains("kFrameQueueGooglePollInterval"))
        assertFalse(implementation.contains("std::this_thread::sleep_for"))
    }

    @Test
    fun smoothNeverUsesThreeSlotsWithFifoBecauseQueuePresentCanBecomeTheThrottle() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("activePresentMode == VK_PRESENT_MODE_FIFO_KHR"))
        assertTrue(implementation.contains("frameQueueSmoothFifoFallback_"))
        assertTrue(implementation.contains("fifo-present-blocking"))
        assertTrue(implementation.contains("if (requested == 2 && fifoPresent)"))
        assertTrue(implementation.contains("return 1;"))
    }

    @Test
    fun frameQueueModeChangesResetPressureTelemetryForCleanABIntervals() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("frameQueueTelemetryEpoch_"))
        assertTrue(implementation.contains("resetFrameQueueTelemetry"))
        assertTrue(implementation.contains("frameQueueMaxGpuOutstanding_.store(0"))
        assertTrue(implementation.contains("frameQueueRetirementWaitTotal_.store(0"))
        assertTrue(implementation.contains("frameQueuePresentSamples_.store(0"))
        assertTrue(implementation.contains("telemetry_epoch="))
    }


    @Test
    fun smoothRequiresTwoPressureStrikesBeforeStickyFallback() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("SMOOTH_PRESENT_STALL_STRIKES = 2"))
        assertTrue(header.contains("frameQueueSmoothPressureStrikes_"))
        assertTrue(implementation.contains("fetch_add(1"))
        assertTrue(implementation.contains("SMOOTH_PRESENT_STALL_STRIKES"))
        assertTrue(implementation.contains("pressure_strikes="))
        assertTrue(implementation.contains("frameQueueSmoothPressureStrikes_.store(0"))
    }

    @Test
    fun smoothFallbackIsStickyUntilAnExplicitQueueOrPresentModeChange() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("frameQueueSmoothRuntimeSuppressed_.store("))
        assertTrue(implementation.contains("false, std::memory_order_release"))
        assertTrue(implementation.contains("void VulkanRendererContext::setLsfgFrameQueue"))
        assertTrue(implementation.contains("void VulkanRendererContext::setPresentMode"))
        assertTrue(implementation.contains("fallback_reason=%s"))
        assertTrue(implementation.contains("present-stall"))
    }

    @Test
    fun queueTelemetryReportsActualGpuAndPresentPressureRatherThanCpuDequeDepth() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("event=present"))
        assertTrue(implementation.contains("gpu_outstanding="))
        assertTrue(implementation.contains("max_gpu_outstanding="))
        assertTrue(implementation.contains("present_ms="))
        assertTrue(implementation.contains("acquire_ms="))
        assertFalse(implementation.contains("queue_depth=%zu"))
    }

    @Test
    fun provenanceIsConsumedOnceAndRepeatedContentIsClassifiedSeparately() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("uniqueDelivery"))
        assertTrue(header.contains("repeatedContentPresent"))
        assertTrue(implementation.contains("classifyHostPresentProvenance"))
        assertTrue(implementation.contains("consumedLsfgDeliveries_"))
        assertTrue(implementation.contains("repeated_content_present="))
        assertTrue(implementation.contains("unique_delivery="))
    }

    @Test
    fun temporalIntentReachesFinalGoogleDisplayTimingWithConservativeValidation() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("desiredPresentTimeNs"))
        assertTrue(header.contains("lastAcceptedDesiredPresentTimeNs_"))
        assertTrue(implementation.contains("validatedHostDesiredPresentTime"))
        assertTrue(implementation.contains("googlePresentTime.desiredPresentTime ="))
        assertFalse(implementation.contains("googlePresentTime.desiredPresentTime = 0;"))
        assertTrue(implementation.contains("CLOCK_MONOTONIC"))
        assertTrue(implementation.contains("desired_vs_actual"))
        assertTrue(implementation.contains("actual_present_time="))
        assertTrue(implementation.contains("earliest_present_time="))
        assertTrue(implementation.contains("present_margin="))
    }

    @Test
    fun presentModePolicyUsesLiveSwapchainModeAndSerializesRequests() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("activePresentMode"))
        assertTrue(implementation.contains("activePresentMode = presentMode"))
        assertTrue(implementation.contains("activePresentMode == VK_PRESENT_MODE_FIFO_KHR"))
        assertFalse(implementation.contains("requestedPresentMode == VK_PRESENT_MODE_FIFO_KHR"))
        val start = implementation.indexOf("void VulkanRendererContext::setPresentMode")
        val end = implementation.indexOf("\n}\n", start)
        assertTrue(start >= 0 && end > start)
        val transition = implementation.substring(start, end + 3)
        assertTrue(transition.contains("std::unique_lock<std::shared_mutex>"))
    }

    @Test
    fun balancedAndFifoSmoothFallbackReuseBaselinePresentSemaphoreOwnership() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("useFrameQueuePresentSemaphore"))
        assertTrue(implementation.contains("activeFrameSlotCount() > BASE_FRAMES_IN_FLIGHT"))
        assertTrue(implementation.contains("hasUniqueLsfgDelivery"))
        assertTrue(implementation.contains("renderDoneSems[currentFrame]"))
    }

    @Test
    fun frameQueueTelemetrySeparatesUniquePhysicalCadenceFromHostRedrawRate() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("lastUniquePhysicalPresentNs_"))
        assertTrue(header.contains("physicalCadenceErrorsNs_"))
        assertTrue(implementation.contains("unique_physical_fps="))
        assertTrue(implementation.contains("generated_physical_fps="))
        assertTrue(implementation.contains("source_physical_fps="))
        assertTrue(implementation.contains("cadence_error_p50_ms="))
        assertTrue(implementation.contains("cadence_error_p95_ms="))
        assertTrue(implementation.contains("provenance_superseded_total="))
    }

    @Test
    fun quickMenuPresentModeChangeReachesTheFinalVulkanCompositor() {
        val quickMenu = repoSource("app/src/main/java/app/gamenative/ui/component/QuickMenu.kt")

        val callback = quickMenu.substring(
            quickMenu.indexOf("onPresentModeChanged = { mode ->"),
            quickMenu.indexOf("scrollState = lsfgScrollState"),
        )
        assertTrue(callback.contains("renderer?.setVkPresentMode"))
        assertTrue(callback.contains("mode == \"mailbox\""))
        assertTrue(quickMenu.contains("LaunchedEffect(lsfgPresentMode, renderer)"))
    }

    @Test
    fun hostDisplayTelemetryTracksTemporalFallbackAndResetsPhysicalCadencePerEpoch() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("HostDesiredPresentDecision"))
        assertTrue(header.contains("provenanceDesiredPresentTimeNs"))
        assertTrue(header.contains("submittedDesiredPresentTimeNs"))
        assertTrue(header.contains("desiredStaleByNs"))
        assertTrue(header.contains("enqueuedAtNs"))
        assertTrue(header.contains("swapchainGeneration"))
        assertTrue(implementation.contains("resetHostPhysicalCadenceTelemetry"))
        assertTrue(implementation.contains("provenance-epoch-reset"))
        assertTrue(implementation.contains("swapchain-recreated"))
        assertTrue(implementation.contains("desired_fallback_reason="))
        assertTrue(implementation.contains("desired_stale_by_ms="))
        assertTrue(implementation.contains("present_margin_valid="))
        assertTrue(implementation.contains("confirmation_pending_high_water="))
        assertTrue(implementation.contains("swapchain_generation="))
    }

    @Test
    fun hostDisplayConfirmationFeedbackIsBestEffortAndNonblocking() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("publishLsfgHostDisplayFeedback"))
        assertTrue(implementation.contains("gamenative-lsfg-display-feedback-v1"))
        assertTrue(implementation.contains("SOCK_NONBLOCK"))
        assertTrue(implementation.contains("MSG_DONTWAIT"))
        assertTrue(implementation.contains("host-feedback-send"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            Paths.get("src/main/cpp/winlator").resolve(name),
            Paths.get("app/src/main/cpp/winlator").resolve(name),
        )
        val path: Path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("Unable to locate Vulkan renderer source: $name")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }

    private fun repoSource(path: String): String {
        val candidates = listOf(Paths.get(path), Paths.get("..").resolve(path))
        val source: Path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("Unable to locate repository source: $path")
        return String(Files.readAllBytes(source), Charsets.UTF_8)
    }
}
