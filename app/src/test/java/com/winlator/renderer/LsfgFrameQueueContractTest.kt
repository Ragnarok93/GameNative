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
    fun vulkanRendererBoundsAsyncPresentToTheSplitQueuePath() {
        val javaRenderer = repoSource("app/src/main/java/com/winlator/renderer/VulkanRenderer.java")
        val jni = source("vulkan_jni.cpp")
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(javaRenderer.contains("setLsfgFrameQueue"))
        assertTrue(javaRenderer.contains("nativeSetLsfgFrameQueue"))
        assertTrue(jni.contains("nativeSetLsfgFrameQueue"))

        assertTrue(header.contains("MAX_HOST_PRESENT_QUEUE_DEPTH"))
        assertTrue(header.contains("hostPresenterThread_"))
        assertTrue(header.contains("hostAsyncPresenterActive_"))
        assertTrue(implementation.contains(
            "hostAsyncPresenterActive_ =\n        hostAsyncPresenterEnabled_ && hostSplitPresentQueueActive_"
        ))
        assertTrue(implementation.contains("enqueueHostPresent"))
        assertTrue(implementation.contains("drainHostPresenter(\"swapchain-cleanup\")"))

        val workerStart = implementation.indexOf("void VulkanRendererContext::hostPresenterLoop")
        val workerEnd = implementation.indexOf(
            "void VulkanRendererContext::processHostPresentCompletions", workerStart
        )
        assertTrue(workerStart >= 0 && workerEnd > workerStart)
        val worker = implementation.substring(workerStart, workerEnd)
        assertTrue(worker.contains("executeHostPresent"))
        assertFalse(worker.contains("recordHostPresent"))
        assertFalse(worker.contains("pollHostDisplayConfirmations"))
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

        val pressureStart =
            implementation.indexOf("void VulkanRendererContext::updateSmoothQueuePressure")
        val pressureEnd =
            implementation.indexOf("void VulkanRendererContext::drainFrameQueueSubmissions", pressureStart)
        assertTrue(pressureStart >= 0 && pressureEnd > pressureStart)
        val pressure = implementation.substring(pressureStart, pressureEnd)
        assertFalse(pressure.contains("std::this_thread::sleep_for"))
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
        assertTrue(implementation.contains("googlePresentTime.desiredPresentTime = 0;"))
        assertTrue(implementation.contains("present-worker-slot-expired"))
        assertTrue(implementation.contains("present.desiredDecision.submittedDesiredPresentTimeNs = 0;"))
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
        assertTrue(callback.contains("applyPresentMode"))
        assertFalse("Present-mode changes must join the captured configuration transaction",
            callback.contains("applyFrameQueuePolicy"))
        assertTrue(
            "Quick Menu must not bypass Native present-mode ownership",
            !callback.contains("renderer?.setVkPresentMode"),
        )
        assertFalse(quickMenu.contains("LsfgVkManager.applyFrameQueuePolicy"))
        val helper = repoSource("app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt")
        val presentModeSetter = helper.substring(
            helper.indexOf("fun applyPresentMode("), helper.indexOf("fun sanitizeMultiplier("),
        )
        assertTrue(presentModeSetter.contains("scheduleRuntimeConfig(container)"))
        val manager = repoSource("app/src/main/java/app/gamenative/utils/LsfgVkManager.kt")
        assertTrue(manager.contains("renderer.setVkPresentMode(if (snapshot.presentMode == \"mailbox\") 1 else 2)"))
        assertTrue(manager.contains("renderer.beginLsfgBackendTransition(snapshot.backendGeneration, snapshot.revision)"))
        assertFalse(quickMenu.contains("applyFrameQueueToRenderer"))
    }

    @Test
    fun legacyGeneratedSlotsPreserveLayerBatchTimingAndDropMissedSlots() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("const uint64_t intendedNs = rawDesiredNs;"))
        assertTrue(implementation.contains("const uint64_t periodNs = 0;"))
        assertTrue(implementation.contains("legacyGeneratedOutputSlotMissed"))
        assertTrue(implementation.contains("legacy-output-slot-drop"))
        assertTrue(implementation.contains("missed-usable-output-slot"))
        assertTrue(implementation.contains("legacy-generated-output-slot-missed"))
        assertFalse(implementation.contains("highRefreshGeneratedSlotFloorNs"))
        assertFalse(implementation.contains("legacyGeneratedSlotPeriodNs_"))
        assertFalse(implementation.contains("legacyGeneratedSlotNextNs_"))
    }

    @Test
    fun persistentSuboptimalAuditIsDebouncedAndResetPerSwapchainEpoch() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("hostSuboptimalLastAuditGeneration_ != hostSwapchainGeneration_"))
        assertTrue(implementation.contains("hostSuboptimalLastAuditGeneration_ = hostSwapchainGeneration_"))
        assertTrue(implementation.contains("hostSuboptimalLastAuditGeneration_ = UINT64_MAX"))
        assertTrue(implementation.contains("hostSuboptimalLastRequeryNs_ = 0"))
        assertTrue(implementation.contains("hostSuboptimalConsecutive_ = 0"))
        assertTrue(implementation.contains("hostSuboptimalWindow_.clear()"))
        assertTrue(implementation.contains("const bool transformChanged =\n        caps.currentTransform != swapchainPreTransform_"))
        assertTrue(implementation.contains("VkSurfaceTransformFlagBitsKHR pre = caps.currentTransform;"))
        assertTrue(implementation.contains("extentChanged || transformInvalid || transformChanged"))
        assertFalse(implementation.contains("? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR"))
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


    @Test
    fun staleTemporalIntentIsPhaseRescheduledUsingTheRealDisplayRefreshCycle() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("PFN_vkGetRefreshCycleDurationGOOGLE"))
        assertTrue(header.contains("hostRefreshPeriodNs_"))
        assertTrue(header.contains("phaseAdvanceCycles"))
        assertTrue(header.contains("phaseAdvanceNs"))
        assertTrue(implementation.contains("LOAD_D2(GetRefreshCycleDurationGOOGLE)"))
        assertTrue(implementation.contains("vk_.GetRefreshCycleDurationGOOGLE"))
        assertTrue(implementation.contains("advanceDesiredPresentPhase"))
        assertTrue(implementation.contains("phase-rescheduled"))
        assertTrue(implementation.contains("refresh_period_ns="))
        assertTrue(implementation.contains("phase_advance_cycles="))
        assertTrue(implementation.contains("phase_advance_ms="))

        val validationStart =
            implementation.indexOf("HostDesiredPresentDecision VulkanRendererContext::validatedHostDesiredPresentTime")
        val validationEnd =
            implementation.indexOf("bool VulkanRendererContext::nativeFeedbackIdentityCurrent", validationStart)
        assertTrue(validationStart >= 0 && validationEnd > validationStart)
        val validation = implementation.substring(validationStart, validationEnd)
        assertFalse(validation.contains("std::this_thread::sleep_for"))
    }

    @Test
    fun hostPhysicalDeliveryAccountingSeparatesGeneratedLossFromGenerationSuccess() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("sourceUniqueWsiAccepted_"))
        assertTrue(header.contains("generatedUniqueWsiAccepted_"))
        assertTrue(header.contains("sourcePhysicalUnknown_"))
        assertTrue(header.contains("generatedPhysicalUnknown_"))
        assertTrue(implementation.contains("generated_delivery_efficiency="))
        assertTrue(implementation.contains("source_delivery_efficiency="))
        assertTrue(implementation.contains("physical_delivery_unknown="))
        assertTrue(implementation.contains("physical_unknown_reason="))
        assertTrue(implementation.contains("temporal_backlog="))
    }

    @Test
    fun frameQueueRetainsOrderedLsfgDeliveriesBeforeHostComposition() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("QueuedLsfgHostDelivery"))
        assertTrue(header.contains("pendingLsfgHostDeliveries_"))
        assertTrue(implementation.contains("enqueueLsfgHostDelivery"))
        assertTrue(implementation.contains("selectQueuedLsfgHostDelivery"))
        assertTrue(implementation.contains("consumeQueuedLsfgHostDeliveries"))
        assertTrue(implementation.contains("host_snapshot_created"))
        assertTrue(implementation.contains("host_coalesced_drop"))
        assertTrue(implementation.contains("host_backlog_drop"))
        assertTrue(implementation.contains("generated_received"))
        assertTrue(implementation.contains("generated_snapshot_created"))
        assertTrue(implementation.contains("generated_backlog_drop"))
    }

    @Test
    fun phaseReschedulingRejectsCrossEpochAndExcessivelyStaleContent() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("MAX_HOST_TEMPORAL_STALE_NS"))
        assertTrue(implementation.contains("stale-beyond-host-budget"))
        assertTrue(implementation.contains("dropQueuedLsfgHostDeliveries"))
        assertTrue(implementation.contains("provenance-epoch-reset"))
        assertTrue(implementation.contains("host_stale_drop"))
    }

    @Test
    fun hostDeliveryTelemetryUsesItsOwnCompactRecordInsteadOfExtendingDisplayLines() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("\"LSFG_HOST_DELIVERY\""))
        assertTrue(implementation.contains("event=delivery-accounting"))
        assertTrue(implementation.contains("phase_rescheduled_total="))
        assertTrue(implementation.contains("scheduled_error_p50_ms="))
        assertTrue(implementation.contains("confirmation_pending_high_water="))
    }

    @Test
    fun provenanceEpochResetImmediatelyInvalidatesQueuedHostDeliveries() {
        val implementation = source("VulkanRendererContext.cpp")
        val drainStart = implementation.indexOf("void VulkanRendererContext::drainLsfgProvenance")
        val drainEnd = implementation.indexOf("void VulkanRendererContext::bindLsfgProvenance", drainStart)
        assertTrue(drainStart >= 0 && drainEnd > drainStart)
        val drain = implementation.substring(drainStart, drainEnd)

        assertTrue(drain.contains("hostDeliveryQueueContextEpoch_ = packet.contextEpoch"))
        assertTrue(drain.contains("hostSnapshottedLsfgDeliveries_.clear()"))
    }

    @Test
    fun frameQueueOffStillPreservesOneOrderedLsfgHostDelivery() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("MIN_HOST_DELIVERY_QUEUE_CAPACITY"))
        val capacityStart = implementation.indexOf("uint32_t VulkanRendererContext::hostDeliveryQueueCapacity")
        val capacityEnd = implementation.indexOf("bool VulkanRendererContext::isLsfgHostDeliveryStale", capacityStart)
        assertTrue(capacityStart >= 0 && capacityEnd > capacityStart)
        val capacity = implementation.substring(capacityStart, capacityEnd)
        assertTrue(capacity.contains("MIN_HOST_DELIVERY_QUEUE_CAPACITY"))
        assertFalse(capacity.contains("return 0;"))

        val selectStart = implementation.indexOf("bool VulkanRendererContext::selectQueuedLsfgHostDelivery")
        val selectEnd = implementation.indexOf("void VulkanRendererContext::recordHostSnapshotCreated", selectStart)
        assertTrue(selectStart >= 0 && selectEnd > selectStart)
        val select = implementation.substring(selectStart, selectEnd)
        assertFalse(select.contains("if (!lsfgFrameQueueEnabled_.load"))
        assertTrue(implementation.contains("delivery_handoff=ordered-minimum"))
    }

    @Test
    fun frameQueueOffStillRetainsOneSourcePlusMaximumSyntheticBurst() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(
            header.contains(
                "MIN_HOST_DELIVERY_QUEUE_CAPACITY =\n    VKR_LSFG_MAX_GENERATIONS + 1"
            )
        )
        val capacityStart =
            implementation.indexOf("uint32_t VulkanRendererContext::hostDeliveryQueueCapacity")
        val capacityEnd =
            implementation.indexOf("bool VulkanRendererContext::isLsfgHostDeliveryStale", capacityStart)
        val capacity = implementation.substring(capacityStart, capacityEnd)
        assertTrue(capacity.contains("MIN_HOST_DELIVERY_QUEUE_CAPACITY + requestedTarget"))
        assertTrue(capacity.contains("? std::min<uint32_t>("))
        assertTrue(capacity.contains(": 0U"))
    }

    @Test
    fun hostDeliveryQueueDepthUsesRequestedModeNotSmoothFallbackDepth() {
        val implementation = source("VulkanRendererContext.cpp")

        val capacityStart = implementation.indexOf("uint32_t VulkanRendererContext::hostDeliveryQueueCapacity")
        val capacityEnd = implementation.indexOf("bool VulkanRendererContext::isLsfgHostDeliveryStale", capacityStart)
        assertTrue(capacityStart >= 0 && capacityEnd > capacityStart)
        val capacity = implementation.substring(capacityStart, capacityEnd)

        assertTrue(capacity.contains("lsfgFrameQueueTarget_.load"))
        assertFalse(capacity.contains("effectiveFrameQueueTarget()"))
        assertTrue(implementation.contains("delivery_queue_capacity="))
        assertTrue(implementation.contains("effective_gpu_target="))
    }

    @Test
    fun queueCapabilityTelemetryPersistsBeyondRendererStartup() {
        val implementation = source("VulkanRendererContext.cpp")

        val accountingStart = implementation.indexOf("void VulkanRendererContext::emitHostDeliveryAccounting")
        val accountingEnd = implementation.indexOf("void VulkanRendererContext::dropQueuedLsfgHostDeliveriesForWindow", accountingStart)
        assertTrue(accountingStart >= 0 && accountingEnd > accountingStart)
        val accounting = implementation.substring(accountingStart, accountingEnd)

        assertTrue(accounting.contains("graphics_family="))
        assertTrue(accounting.contains("family_queue_count="))
        assertTrue(accounting.contains("present_capable_families="))
        assertTrue(accounting.contains("second_same_family_queue_available="))
    }

    @Test
    fun capableHostUsesSecondSameFamilyQueueForPresentation() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("presentQueue = VK_NULL_HANDLE"))
        assertTrue(header.contains("presentQueueMutex_"))
        assertTrue(header.contains("hostSplitPresentQueueEnabled_"))

        val deviceStart = implementation.indexOf("void VulkanRendererContext::createLogicalDevice")
        val deviceEnd = implementation.indexOf("void VulkanRendererContext::createSwapchain", deviceStart)
        assertTrue(deviceStart >= 0 && deviceEnd > deviceStart)
        val device = implementation.substring(deviceStart, deviceEnd)
        assertTrue(device.contains("graphicsQueueFamilyQueueCount >= 2"))
        assertTrue(device.contains("requestedHostQueueCount"))
        assertTrue(device.contains("qi.queueCount=requestedHostQueueCount"))
        assertTrue(device.contains("GetDeviceQueue(device,graphicsQueueFamilyIndex,1,&presentQueue)"))
        assertTrue(device.contains("presentQueue = graphicsQueue"))

        val executeStart = implementation.indexOf(
            "CompletedHostPresent VulkanRendererContext::executeHostPresent"
        )
        val executeEnd = implementation.indexOf(
            "void VulkanRendererContext::finalizeHostPresent", executeStart
        )
        assertTrue(executeStart >= 0 && executeEnd > executeStart)
        val execute = implementation.substring(executeStart, executeEnd)
        assertTrue(execute.contains("QueuePresentKHR(presentQueue"))
        assertTrue(execute.contains("presentQueueMutex_"))

        val finalizeStart = executeEnd
        val finalizeEnd = implementation.indexOf(
            "VkResult VulkanRendererContext::presentHostFrame", finalizeStart
        )
        assertTrue(finalizeStart >= 0 && finalizeEnd > finalizeStart)
        val finalize = implementation.substring(finalizeStart, finalizeEnd)
        assertTrue(finalize.contains("present_queue_split="))
        assertTrue(finalize.contains("present_queue_index="))
    }

    @Test
    fun splitPresentQueueUsesBoundedPresenterWorkerWithoutChangingLsfgSyncTopology() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("MAX_HOST_PRESENT_QUEUE_DEPTH"))
        assertTrue(header.contains("CompletedHostPresent"))
        assertTrue(header.contains("pendingHostPresents_"))
        assertTrue(header.contains("completedHostPresents_"))
        assertTrue(header.contains("hostPresenterThread_"))
        assertTrue(header.contains("hostPresentCompletionPending_"))
        assertTrue(header.contains("hostAsyncPresenterActive_"))

        assertTrue(implementation.contains("enqueueHostPresent"))
        assertTrue(implementation.contains("hostPresenterLoop"))
        assertTrue(implementation.contains("processHostPresentCompletions"))
        assertTrue(implementation.contains("drainHostPresenter"))
        assertTrue(implementation.contains("host_present_enqueue_wait_ms="))
        assertTrue(implementation.contains("host_present_queue_depth="))
        assertTrue(implementation.contains("host_present_worker=1"))
        assertTrue(implementation.contains("hostAsyncPresenterActive_ ="))
        assertTrue(implementation.contains("hostSplitPresentQueueActive_"))
        assertTrue(implementation.contains("drainHostPresenter(\"swapchain-cleanup\")"))

        val renderStart = implementation.indexOf("void VulkanRendererContext::renderFrame")
        val resizeStart = implementation.indexOf("void VulkanRendererContext::onSurfaceResized", renderStart)
        assertTrue(renderStart >= 0 && resizeStart > renderStart)
        val render = implementation.substring(renderStart, resizeStart)
        assertTrue(render.contains("enqueueHostPresent"))
        assertTrue(render.contains("hostAsyncPresenterActive_"))
        assertTrue(render.contains("frameQueuePresentSems_[imgIdx]"))
        assertFalse(render.contains("std::this_thread::sleep_for"))

        val presenterStart = implementation.indexOf("void VulkanRendererContext::hostPresenterLoop")
        val presenterEnd = implementation.indexOf("void VulkanRendererContext::processHostPresentCompletions", presenterStart)
        assertTrue(presenterStart >= 0 && presenterEnd > presenterStart)
        val presenter = implementation.substring(presenterStart, presenterEnd)
        assertTrue(presenter.contains("executeHostPresent"))
        assertFalse(presenter.contains("recordHostPresent"))
        assertFalse(presenter.contains("pollHostDisplayConfirmations"))
    }

    @Test
    fun queuedAhbTransitionOwnershipSurvivesUntilTheQueuedSnapshot() {
        val implementation = source("VulkanRendererContext.cpp")
        val updateStart = implementation.indexOf("void VulkanRendererContext::updateWindowContentAHB")
        val updateEnd = implementation.indexOf("void VulkanRendererContext::setRenderList", updateStart)
        assertTrue(updateStart >= 0 && updateEnd > updateStart)
        val update = implementation.substring(updateStart, updateEnd)

        assertTrue(update.contains("queued delivery owns first AHB transition"))
        assertTrue(update.contains("hostDeliveryQueueCapacity() >= MIN_HOST_DELIVERY_QUEUE_CAPACITY"))
        assertFalse(update.contains(
            "queuedDeliveryOwnsTransition =\n        lsfgFrameQueueEnabled_.load"
        ))
    }

    @Test
    fun missingQueuedImportReleasesTheQueueHeldAhbReference() {
        val implementation = source("VulkanRendererContext.cpp")
        val selectStart = implementation.indexOf("bool VulkanRendererContext::selectQueuedLsfgHostDelivery")
        val selectEnd = implementation.indexOf("void VulkanRendererContext::recordHostSnapshotCreated", selectStart)
        assertTrue(selectStart >= 0 && selectEnd > selectStart)
        val select = implementation.substring(selectStart, selectEnd)
        val missing = select.indexOf("queued-import-missing")
        assertTrue(missing >= 0)
        val branch = select.substring((missing - 900).coerceAtLeast(0), (missing + 400).coerceAtMost(select.length))
        assertTrue(branch.contains("releaseWindowAhbReference(queued.ahb)"))
    }

    @Test
    fun nativeReusesSharedHostPresenterInsteadOfCreatingASecondFrameQueue() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("pendingHostPresents_"))
        assertTrue(header.contains("hostPresenterThread_"))
        assertFalse(header.contains("nativePendingHostPresents_"))
        assertFalse(header.contains("NativeFrameQueueTarget"))

        val admissionStart =
            implementation.indexOf("uint32_t VulkanRendererContext::nativeHostSyntheticAdmissionCapacity")
        val admissionEnd =
            implementation.indexOf("void VulkanRendererContext::emitNativeLsfgPipelineTelemetry", admissionStart)
        assertTrue(admissionStart >= 0 && admissionEnd > admissionStart)
        val admission = implementation.substring(admissionStart, admissionEnd)
        assertTrue(admission.contains("pendingHostPresents_"))
        assertTrue(admission.contains("hostPresenterBusy_"))
        assertTrue(admission.contains("MAX_NATIVE_HOST_PRESENT_QUEUE_DEPTH"))

        val presenterStart =
            implementation.indexOf("void VulkanRendererContext::hostPresenterLoop")
        val presenterEnd =
            implementation.indexOf("void VulkanRendererContext::processHostPresentCompletions", presenterStart)
        val presenter = implementation.substring(presenterStart, presenterEnd)
        assertTrue(presenter.contains("executeHostPresent"))
        assertFalse(presenter.contains("nativeHostPresenterLoop"))
        assertTrue(header.contains("VkPresentModeKHR presentMode"))
        assertTrue(header.contains("mailboxDispatchDelayNs"))
        assertTrue(presenter.contains("present.presentMode == VK_PRESENT_MODE_MAILBOX_KHR"))
        assertTrue(presenter.contains("std::this_thread::sleep_for"))
        assertTrue(presenter.contains("excluded from presenterQueueAgeNs/backlog feedback"))
    }

    @Test
    fun nativeUsesTheSameLiveFrameQueuePolicyAsLegacy() {
        val renderer =
            repoSource("app/src/main/java/com/winlator/renderer/VulkanRenderer.java")
        val manager =
            repoSource("app/src/main/java/app/gamenative/utils/LsfgVkManager.kt")
        val implementation = source("VulkanRendererContext.cpp")

        assertFalse(renderer.contains("nativeOwnsFrameQueuePolicy"))
        assertFalse(renderer.contains("setNativeFrameQueuePolicyOwned"))
        assertFalse(renderer.contains("legacy-policy-retained owner=native"))

        val applyStart = renderer.indexOf("public boolean applyFrameGenerationSettings")
        val applyEnd = renderer.indexOf("public void setFrameGenerationShaders", applyStart)
        assertTrue(applyStart >= 0 && applyEnd > applyStart)
        val apply = renderer.substring(applyStart, applyEnd)
        assertTrue(apply.contains("pendingLsfgFrameQueueEnabled"))
        assertTrue(apply.contains("pendingLsfgFrameQueueTarget"))
        assertTrue(apply.contains("nativeSetLsfgFrameQueue("))
        assertFalse(apply.contains("nativeSetLsfgFrameQueue(handle, false, 0)"))

        assertTrue(manager.contains("renderer.setLsfgFrameQueue("))
        assertTrue(manager.contains("snapshot.frameQueueEnabled"))
        assertTrue(manager.contains("snapshot.frameQueueTarget"))
        assertTrue(manager.contains("shared-host-frame-queue+native-admission"))

        assertTrue(implementation.contains("nativeLsfgContentPending"))
        assertTrue(implementation.contains("activeFrameSlotCount()"))
        assertTrue(implementation.contains("enforceFrameQueueSubmissionBudget(frameQueueTarget)"))
        assertTrue(implementation.contains("effectiveFrameQueueTarget()"))
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

