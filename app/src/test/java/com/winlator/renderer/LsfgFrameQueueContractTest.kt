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
    fun persistedConfigPublishesQueueMetadataWithoutOwningLsfgGeneration() {
        val manager = repoSource("app/src/main/java/app/gamenative/utils/LsfgVkManager.kt")

        assertTrue(manager.contains("EXTRA_FRAME_QUEUE_ENABLED"))
        assertTrue(manager.contains("EXTRA_FRAME_QUEUE_TARGET"))
        assertTrue(manager.contains("frame_queue_enabled"))
        assertTrue(manager.contains("frame_queue_target"))
    }

    @Test
    fun vulkanRendererExposesBoundedFinalPresentQueue() {
        val javaRenderer = repoSource("app/src/main/java/com/winlator/renderer/VulkanRenderer.java")
        val jni = source("vulkan_jni.cpp")
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(javaRenderer.contains("setLsfgFrameQueue"))
        assertTrue(javaRenderer.contains("nativeSetLsfgFrameQueue"))
        assertTrue(jni.contains("nativeSetLsfgFrameQueue"))
        assertTrue(header.contains("PendingHostPresent"))
        assertTrue(header.contains("hostPresentQueue_"))
        assertTrue(header.contains("hostPresentThread_"))
        assertTrue(header.contains("graphicsQueueMutex_"))
        assertTrue(header.contains("framePresentPending_"))
        assertTrue(implementation.contains("waitForHostPresentCapacity"))
        assertTrue(implementation.contains("enqueueHostPresent"))
        assertTrue(implementation.contains("hostPresentLoop"))
        assertTrue(implementation.contains("presentHostFrame"))
        assertTrue(implementation.contains("LSFG_FRAME_QUEUE"))
    }

    @Test
    fun offAndUnbufferedPreserveImmediatePresentation() {
        val implementation = source("VulkanRendererContext.cpp")
        assertTrue(implementation.contains("frameQueueTarget == 0"))
        assertTrue(implementation.contains("return presentHostFrame"))
    }

    @Test
    fun queueDepthIsBoundedAndSmoothAloneActivatesThirdRenderSlot() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("BASE_FRAMES_IN_FLIGHT = 2"))
        assertTrue(header.contains("MAX_FRAMES_IN_FLIGHT = 3"))
        assertTrue(implementation.contains("static_cast<std::size_t>(frameQueueTarget) + 1U"))
        assertTrue(implementation.contains("target >= 2 ? MAX_FRAMES_IN_FLIGHT : BASE_FRAMES_IN_FLIGHT"))
        assertTrue(implementation.contains("currentFrame=(currentFrame+1)%activeFrameSlotCount()"))
    }

    @Test
    fun queueTransitionsAndSwapchainTeardownDrainBeforeReuse() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("waitForFramePresentSubmission(currentFrame)"))
        assertTrue(implementation.contains("framePresentPending_[present.frameSlot] = true"))
        assertTrue(implementation.contains("framePresentPending_[present.frameSlot] = false"))
        assertTrue(implementation.contains("void VulkanRendererContext::cleanupSwapchain() {\n    flushHostPresentQueue();"))
        assertTrue(implementation.contains("if (previousEnabled && previousTarget > 0)\n        flushHostPresentQueue();"))
    }

    @Test
    fun renderSubmitAndPresentShareExternalQueueSynchronization() {
        val implementation = source("VulkanRendererContext.cpp")
        assertTrue(implementation.contains("std::lock_guard<std::mutex> queueLock(graphicsQueueMutex_)"))
        assertTrue(implementation.contains("vk_.QueueSubmit("))
        assertTrue(implementation.contains("vk_.QueuePresentKHR(graphicsQueue, &pi)"))
    }

    @Test
    fun queuePathDoesNotAddDeviceOrQueueIdleWaits() {
        val implementation = source("VulkanRendererContext.cpp")
        val start = implementation.indexOf("void VulkanRendererContext::hostPresentLoop")
        val end = implementation.indexOf("\n}\n", start)
        assertTrue(start >= 0 && end > start)
        val queuePath = implementation.substring(start, end + 3)
        assertFalse(queuePath.contains("DeviceWaitIdle"))
        assertFalse(queuePath.contains("QueueWaitIdle"))
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
