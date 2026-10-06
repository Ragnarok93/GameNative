package com.winlator.renderer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRendererDisplayConfirmationContractTest {
    @Test
    fun finalVulkanPresentHasCapabilitySelectedDisplayConfirmation() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("HostDisplayConfirmation"))
        assertTrue(header.contains("PFN_vkGetPastPresentationTimingGOOGLE"))
        assertTrue(header.contains("PFN_vkWaitForPresentKHR"))
        assertTrue(implementation.contains("VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME"))
        assertTrue(implementation.contains("VK_KHR_PRESENT_ID_EXTENSION_NAME"))
        assertTrue(implementation.contains("VK_KHR_PRESENT_WAIT_EXTENSION_NAME"))
        assertTrue(implementation.contains("pollHostDisplayConfirmations"))
        assertTrue(implementation.contains("VkPresentIdKHR"))
        assertTrue(implementation.contains("VkPresentTimesInfoGOOGLE"))
    }

    @Test
    fun confirmationNeverBlocksTheRenderThread() {
        val implementation = source("VulkanRendererContext.cpp")
        val start = implementation.indexOf("void VulkanRendererContext::pollHostDisplayConfirmations")
        assertTrue("confirmation poller must exist", start >= 0)
        val end = implementation.indexOf("\n}\n", start)
        val poller = implementation.substring(start, end + 3)

        assertFalse(poller.contains("DeviceWaitIdle"))
        assertFalse(poller.contains("WaitForFences"))
        assertTrue(
            "present-wait fallback must be a zero-time poll",
            poller.contains("WaitForPresentKHR") && poller.contains(", 0)"),
        )
    }

    @Test
    fun lsfgProvenanceIsBoundToTheAhbActuallySampledByTheHostFrame() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("LsfgFrameProvenance"))
        assertTrue(header.contains("pendingLsfgProvenance"))
        assertTrue(header.contains("frameProvenance"))
        assertTrue(implementation.contains("LSFG_PROVENANCE_SOCKET"))
        assertTrue(implementation.contains("recvfrom"))
        assertTrue(implementation.contains("drainLsfgProvenance"))
        assertTrue(implementation.contains("bindLsfgProvenance"))
        assertTrue(implementation.contains("swapchainImageIndex"))
    }


    @Test
    fun provenanceTransportUsesAbstractSocketAcrossHostGuestNamespaces() {
        val renderer = source("VulkanRendererContext.cpp")
        val javaRenderer = repoSource("app/src/main/java/com/winlator/renderer/VulkanRenderer.java")
        val bionic = repoSource("app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java")
        val glibc = repoSource("app/src/main/java/com/winlator/xenvironment/components/GlibcProgramLauncherComponent.java")

        assertTrue(renderer.contains("LSFG_PROVENANCE_SOCKET"))
        assertTrue(renderer.contains("address.sun_path[0] = '\\0'"))
        assertTrue(renderer.contains("mode=abstract"))
        assertTrue(renderer.contains("provenance-socket-bind-ok"))
        assertTrue(renderer.contains("provenance_rx_total="))
        assertTrue(renderer.contains("provenance_match_total="))
        assertTrue(renderer.contains("provenance_miss_total="))
        assertTrue(javaRenderer.contains("nativeLibDir,\n                    \"\""))
        assertFalse(bionic.contains("LSFG_PROVENANCE_SOCKET_PATH"))
        assertFalse(glibc.contains("LSFG_PROVENANCE_SOCKET_PATH"))
        assertFalse(renderer.contains("unlink(lsfgProvenanceSocketPath"))
    }


    @Test
    fun provenanceSocketOwnershipTransfersToNewestRendererAndDrainsOutsideAhbImport() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("gLsfgProvenanceSocketOwnerMutex"))
        assertTrue(implementation.contains("gLsfgProvenanceSocketOwner"))
        assertTrue(implementation.contains("provenance-socket-owner-transfer"))
        assertTrue(implementation.contains("SO_RCVBUF"))
        assertTrue(implementation.contains("drainLsfgProvenance();"))

        val renderFrameStart = implementation.indexOf("void VulkanRendererContext::renderFrame()")
        val renderFrameEnd = implementation.indexOf("void VulkanRendererContext::onSurfaceResized", renderFrameStart)
        assertTrue(renderFrameStart >= 0 && renderFrameEnd > renderFrameStart)
        val renderFrame = implementation.substring(renderFrameStart, renderFrameEnd)
        assertTrue(renderFrame.contains("drainLsfgProvenance();"))

        assertTrue(header.contains("provenanceSocketOwnerGeneration_"))
    }


    @Test
    fun provenanceSocketIsClosedAndResetWhenTheLastRendererContextLeaves() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("if (gLsfgProvenanceSocketContexts.empty())"))
        assertTrue(implementation.contains("::close(gLsfgProvenanceSocketFd)"))
        assertTrue(implementation.contains("gLsfgProvenanceSocketFd = -1"))
        assertTrue(implementation.contains("gLsfgProvenanceSocketOwner = nullptr"))
        assertTrue(implementation.contains("provenance-socket-release reason=no-renderers"))
    }

    @Test
    fun provenanceEpochChangeFlushesOldSwapchainMappings() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("contextEpoch"))
        assertTrue(header.contains("activeProvenanceContextEpoch_"))
        assertTrue(implementation.contains("provenance-epoch-reset"))
        assertTrue(implementation.contains("lsfgSwapchainImageAhbs.clear()"))
        assertTrue(implementation.contains("pendingLsfgProvenance.clear()"))
        assertTrue(implementation.contains("host_wsi_accepted_total="))
        assertTrue(implementation.contains("host_display_confirmed_total="))
        assertTrue(implementation.contains("host_display_unknown_total="))
        assertTrue(implementation.contains("display_delivery_ratio="))
    }

    @Test
    fun telemetrySeparatesPhysicalConfirmationFromWsiAcceptance() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("LSFG_HOST_DISPLAY"))
        assertTrue(implementation.contains("host_display_confirmed="))
        assertTrue(implementation.contains("host_display_unknown="))
        assertTrue(implementation.contains("host_wsi_accepted="))
        assertTrue(implementation.contains("delivery_id="))
        assertTrue(implementation.contains("confirmation_backend="))
    }

    @Test
    fun nativeTimelineBuildsExplicitFractionalSlotsAndResetsPerSurfaceEpoch() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")
        val pacer = repoSource("app/src/main/cpp/lsfg/lsfg_pacer.cpp")

        assertTrue(header.contains("NativePresentationSchedule"))
        assertTrue(implementation.contains("buildNativePresentationSchedule"))
        assertTrue(header.contains("SourceProtectedTimeline nativeSourceTimeline_"))
        assertTrue(implementation.contains("nativeSourceTimeline_.observe"))
        assertTrue(implementation.contains("nativeSourceTimeline_.syntheticDesiredTimeNs"))
        assertTrue(implementation.contains("shared_timeline=source-protected"))
        assertTrue(implementation.contains("lsfg::BuildPresentationSlots"))
        assertTrue(implementation.contains("schedule.generatedDesiredNs"))
        assertTrue(implementation.contains("schedule.sourceDesiredNs"))
        assertTrue(implementation.contains("phaseAdvanceCycles"))
        assertTrue(implementation.contains("resetNativePresentationTimeline"))
        assertTrue(implementation.contains("\"swapchain-created\""))
        assertTrue(implementation.contains("\"swapchain-recreated\""))
        assertTrue(implementation.contains("\"surface-detached\""))
        assertTrue(pacer.contains("static_cast<double>(i + 1) / denominator"))

        val validator =
            implementation.substring(
                implementation.indexOf("HostDesiredPresentDecision VulkanRendererContext::validatedHostDesiredPresentTime"),
                implementation.indexOf("void VulkanRendererContext::emitHostDisplayConfirmation"),
            )
        val provenanceAssignment =
            validator.indexOf("decision.provenanceDesiredPresentTimeNs = desired")
        val googleFallback =
            validator.indexOf("google-display-timing-unavailable")
        assertTrue(
            "Temporal intent must remain observable without GOOGLE timing",
            provenanceAssignment >= 0 && googleFallback > provenanceAssignment,
        )
    }

    @Test
    fun nativePhysicalCountersAreDistinctFromWsiAcceptanceAndDriveStateTruth() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")
        val jni = repoSource("app/src/main/cpp/winlator/vulkan_jni.cpp")

        assertTrue(header.contains("getDisplayConfirmedFrameCount"))
        assertTrue(header.contains("getGeneratedDisplayConfirmedFrameCount"))
        assertTrue(header.contains("getSourceDisplayConfirmedFrameCount"))
        assertTrue(header.contains("nativeGeneratedWsiAccepted_"))
        assertTrue(header.contains("nativeGeneratedDisplayConfirmedEpoch_"))
        assertTrue(implementation.contains("recordNativePresentationEvidence"))
        assertTrue(implementation.contains("generated_delivery_efficiency"))
        assertTrue(implementation.contains("confirmation_timeout_rate"))
        assertTrue(implementation.contains("output_target_deficit_ratio"))
        assertTrue(jni.contains("nativeGetDisplayConfirmedFrameCount"))
        assertTrue(jni.contains("nativeIsDisplayConfirmationAvailable"))

        val confirmationStart =
            implementation.indexOf("void VulkanRendererContext::emitHostDisplayConfirmation")
        val confirmationEnd =
            implementation.indexOf("void VulkanRendererContext::recordHostPresent", confirmationStart)
        val confirmationBody =
            implementation.substring(confirmationStart, confirmationEnd)
        assertTrue(
            "PresentWait confirmation must count without actualPresentTime",
            confirmationBody.contains("if (confirmed && provenance.uniqueDelivery)") &&
                confirmationBody.contains("if (confirmation.actualPresentTimeNs != 0)"),
        )
    }

    @Test
    fun legacyGeneratedCadenceUsesStableOutputSlotsInsteadOfPhaseRepair() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("outputSlotIndex"))
        assertTrue(header.contains("outputSlotIntendedPresentTimeNs"))
        assertTrue(header.contains("legacyGeneratedSlotNextNs_"))
        assertTrue(implementation.contains("assignLegacyGeneratedOutputSlot"))
        assertTrue(implementation.contains("missed-usable-output-slot"))
        assertTrue(implementation.contains("legacy-output-slot-missed-no-phase-repair"))
        assertTrue(implementation.contains("event=legacy-output-slot-confirmation"))
        assertTrue(implementation.contains("generated_frame_drop_reason="))
        assertTrue(implementation.contains("pruneMissedLegacyGeneratedOutputSlots"))
    }

    @Test
    fun persistentSuboptimalRevalidatesSurfaceBeforeDebouncedRecreation() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("observeHostPresentResult"))
        assertTrue(implementation.contains("hostSuboptimalConsecutive_ < 8"))
        assertTrue(implementation.contains("GetPhysicalDeviceSurfaceCapabilitiesKHR"))
        assertTrue(implementation.contains("GetPhysicalDeviceSurfaceFormatsKHR"))
        assertTrue(implementation.contains("GetPhysicalDeviceSurfacePresentModesKHR"))
        assertTrue(implementation.contains("hostSuboptimalRecreateGeneration_ != hostSwapchainGeneration_"))
        assertTrue(implementation.contains("event=persistent-suboptimal"))
        assertTrue(implementation.contains("event=suboptimal-transition state=start"))
        assertTrue(implementation.contains("event=suboptimal-transition state=end"))
        assertTrue(implementation.contains("event=permanent-suboptimal-observed"))
        assertTrue(implementation.contains("diagnostic-hold-100pct"))
        assertTrue(implementation.contains("ANativeWindow_getWidth"))
        assertTrue(implementation.contains("current_transform=0x%x pre_transform=0x%x"))
        assertTrue(implementation.contains("requested_present_mode=%d selected_present_mode=%d"))
        assertTrue(implementation.contains("google_display_timing=%d present_wait=%d"))
        assertTrue(implementation.contains("event=swapchain-capabilities"))
        assertTrue(implementation.contains("requested_images=%u actual_images=%u"))
        assertTrue(implementation.contains("format=%d color_space=%d"))
        assertTrue(implementation.contains("chosen_transform=0x%x"))
        assertTrue(implementation.contains("chosen_composite_alpha=0x%x"))
        assertTrue(implementation.contains("chosen_usage=0x%x"))
    }

    @Test
    fun slowPresenterBoundsSyntheticWorkBeforeSwapchainAcquire() {
        val implementation = source("VulkanRendererContext.cpp")
        val capacityStart =
            implementation.indexOf("uint32_t VulkanRendererContext::nativeHostSyntheticAdmissionCapacity")
        val capacityEnd =
            implementation.indexOf("void VulkanRendererContext::emitNativeLsfgPipelineTelemetry", capacityStart)
        assertTrue(capacityStart >= 0 && capacityEnd > capacityStart)
        val capacity = implementation.substring(capacityStart, capacityEnd)

        assertTrue(capacity.contains("rollingPercentileNs(hostPresentLatencySamplesNs_, 95)"))
        assertTrue(capacity.contains("predicted-temporal-stale"))
        assertTrue(capacity.contains("MAX_NATIVE_HOST_PRESENT_QUEUE_DEPTH - occupied - 1"))

        val renderStart = implementation.indexOf("void VulkanRendererContext::renderFrame()")
        val acquireStart = implementation.indexOf("nativeExtraAcquireSems_", renderStart)
        val admissionStart = implementation.indexOf("nativeHostSyntheticAdmissionCapacity()", renderStart)
        assertTrue(admissionStart >= 0 && acquireStart > admissionStart)
        assertTrue(implementation.contains("present_call_p95_ms="))
        assertTrue(implementation.contains("presenter_queue_age_p95_ms="))
        assertTrue(implementation.contains("event=stale-retirement"))
        assertTrue(implementation.contains("stage=pre-acquire"))
        assertTrue(implementation.contains("event=present-slot-miss"))
        assertTrue(implementation.contains("action=submit-acquired-for-wsi-ownership"))
        assertTrue(implementation.contains("nativeGeneratedSuperseded_"))
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
