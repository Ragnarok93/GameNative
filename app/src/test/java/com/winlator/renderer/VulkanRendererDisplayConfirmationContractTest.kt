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
    fun provenanceTransportUsesSharedFilesystemSocketAndReportsLifecycle() {
        val renderer = source("VulkanRendererContext.cpp")
        val jni = source("vulkan_jni.cpp")
        val javaRenderer = repoSource("app/src/main/java/com/winlator/renderer/VulkanRenderer.java")
        val bionic = repoSource("app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java")
        val glibc = repoSource("app/src/main/java/com/winlator/xenvironment/components/GlibcProgramLauncherComponent.java")

        assertTrue(javaRenderer.contains("lsfg-provenance-v1.sock"))
        assertTrue(javaRenderer.contains("ImageFs.find"))
        assertTrue(jni.contains("jProvenanceSocketPath"))
        assertTrue(renderer.contains("provenance-socket-bind-ok"))
        assertTrue(renderer.contains("provenance-socket-bind-failed"))
        assertTrue(renderer.contains("provenance_rx_total="))
        assertTrue(renderer.contains("provenance_match_total="))
        assertTrue(renderer.contains("provenance_miss_total="))
        assertTrue(renderer.contains("unlink("))
        assertTrue(bionic.contains("LSFG_PROVENANCE_SOCKET_PATH"))
        assertTrue(glibc.contains("LSFG_PROVENANCE_SOCKET_PATH"))
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
