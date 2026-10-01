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
    fun telemetrySeparatesPhysicalConfirmationFromWsiAcceptance() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("LSFG_HOST_DISPLAY"))
        assertTrue(implementation.contains("host_display_confirmed="))
        assertTrue(implementation.contains("host_display_unknown="))
        assertTrue(implementation.contains("host_wsi_accepted="))
        assertTrue(implementation.contains("delivery_id="))
        assertTrue(implementation.contains("confirmation_backend="))
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
}
