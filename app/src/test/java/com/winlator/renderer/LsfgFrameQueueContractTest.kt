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
        assertTrue(implementation.contains("currentFrame=(currentFrame+1)%activeFrameSlotCount()"))
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
        assertTrue(implementation.contains("requested == 2 && !hostPresentWaitEnabled"))
        assertTrue(implementation.contains("return 1;"))
        assertTrue(implementation.contains("requested_target="))
        assertTrue(implementation.contains("effective_target="))
        assertTrue(implementation.contains("smooth_fallback="))
        assertTrue(implementation.contains("present-wait-unavailable"))
    }

    @Test
    fun trueSmoothUsesPresentWaitAsTheRetirementBoundary() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("enforceFrameQueuePresentationBudget"))
        assertTrue(header.contains("frameQueuePresentRetirementWaitTotal_"))
        assertTrue(implementation.contains("enforceFrameQueuePresentationBudget"))
        assertTrue(implementation.contains("WaitForPresentKHR"))
        assertTrue(implementation.contains("present_retirement_waits="))
        assertTrue(implementation.contains("present_retirement_wait_ms="))
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
