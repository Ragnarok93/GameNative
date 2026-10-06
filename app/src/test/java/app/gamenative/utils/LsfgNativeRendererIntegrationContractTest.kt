package app.gamenative.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LsfgNativeRendererIntegrationContractTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "app/src/main/cpp/winlator/VulkanRendererContext.h").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir"))
    }

    @Test
    fun nativeRendererStateHasItsRequiredIntegrationSeam() {
        val root = repoRoot()
        val header = File(root, "app/src/main/cpp/winlator/VulkanRendererContext.h").readText()
        val context = File(root, "app/src/main/cpp/winlator/VulkanRendererContext.cpp").readText()
        val native = File(root, "app/src/main/cpp/winlator/VulkanRendererLsfg.cpp").readText()
        val cmake = File(root, "app/src/main/cpp/CMakeLists.txt").readText()

        assertTrue(header.contains("struct VkrLsfg;"))
        assertTrue(header.contains("VkCompositeTarget"))
        assertTrue(header.contains("VkrLsfg* lsfg"))
        assertTrue(header.contains("framegenRequested"))
        assertTrue(header.contains("createCompositeTargets"))
        assertTrue(cmake.contains("winlator/VulkanRendererLsfg.cpp"))

        assertTrue(native.contains("#include \"../lsfg/vk_dispatch.h\""))
        val createDeviceEnd = context.indexOf("vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);")
        val createDeviceBody = context.substring(0, createDeviceEnd)
        assertTrue(!createDeviceBody.contains("vkd_load(instance, device, gipa)"))

        assertTrue(native.contains("vkd_load(instance, device, gipa)"))
        assertTrue(context.contains("vkd_unload()"))
        assertTrue(native.contains("vkr_lsfg_create"))
        assertTrue(context.contains("vkr_lsfg_generate_into"))
        assertTrue(context.contains("nativeExtraAcquireSems_"))
        assertTrue(context.contains("nativeExtraAcquireSems_[currentFrame][g]"))
        assertTrue(context.contains("nativeGeneratedImgIndices"))
        assertTrue(context.contains("vkr_lsfg_plan(lsfg, nativeCapacity, nativeSourceFrame)"))
        assertTrue(context.contains("vkr_lsfg_process("))
        assertTrue(context.contains("blitCompositeToSwapchain("))
        assertTrue(context.contains("signalSemaphoreCount=signalSemaphoreCount"))
        assertTrue(context.contains("nativeRuntimeActive"))
        assertTrue(!context.contains("framegenMultiplier == 2"))
        assertTrue(context.contains("uint32_t framegenFlowMode = VKR_LSFG_FLOW_FIXED"))
        assertTrue(context.contains("nativeLsfgContextEpoch_"))
        assertTrue(header.contains("nativeLastContextReuseRevision_"))
        assertTrue(header.contains("nativePresentRateSampleNs_"))
        assertTrue(context.contains("nativeLastContextReuseRevision_ != framegenConfigRevision"))
        assertTrue(context.contains("wsi_accepted_output_fps=%.2f"))
        assertTrue(context.contains("confirmed_output_fps=%.2f"))
        assertTrue(context.contains("host_wait_total_ms=%.3f"))
        assertTrue(context.contains("event=presentation_evidence_reset reason=swapchain-recreate"))
        assertTrue(context.contains("nativeGeneratedPresentedFrames_.store(0"))
        assertTrue(context.contains("vkr_lsfg_note_presentation_drop(lsfg, 1)"))

        val vkr = File(root, "app/src/main/cpp/lsfg/vkr_lsfg.cpp").readText()
        assertTrue(vkr.contains("AdaptiveFlowController"))
        assertTrue(vkr.contains("generation_density_backoff"))
        assertTrue(vkr.contains("vkr_lsfg_set_pressure"))
        assertTrue(vkr.contains("output_deficit"))
        assertTrue(vkr.contains("bool gpu_valid{}"))
        assertTrue(vkr.contains("bool output_valid{}"))
        assertTrue(vkr.contains("observation.globalPressureValid = global_pressure_valid"))
        assertTrue(vkr.contains("pressure_fresh && lsfg->pressure.output_valid"))
        assertTrue(vkr.contains("effective_scale"))
        assertTrue(vkr.contains("transition=%d warm=%d"))

        val standaloneCmake =
            File(root, "app/src/main/cpp/vulkan-renderer-build/CMakeLists.txt").readText()
        assertTrue(standaloneCmake.contains("adaptive_flow_controller.cpp"))
        assertTrue(standaloneCmake.contains("CMAKE_CXX_STANDARD 20"))

        val manager = File(root, "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt").readText()
        val quickMenu = File(root, "app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt").readText()
        assertTrue(manager.contains("data class NativeRuntimeConfigSnapshot"))
        assertTrue(manager.contains("requested_revision"))
        assertTrue(manager.contains("applied_revision"))
        assertTrue(manager.contains("discarded_stale_revision"))
        assertTrue(manager.contains("LSFG_LEGACY_CONFIG: event=legacy_layer_state"))
        assertTrue(manager.contains("reason=native-backend"))
        assertTrue(manager.contains("LSFG_NATIVE_CONFIG: event=%s"))
        assertTrue(manager.contains("present_policy=%s queue_policy=%s legacy_present_policy=%s"))
        assertTrue(manager.contains("native-bounded-shallow"))
        assertTrue(manager.contains("snapshotIsCurrent"))
        assertTrue(manager.contains("reserveRuntimeRequest"))
        assertTrue(manager.contains("before-legacy-publication"))
        assertTrue(manager.contains("latestBackendRequest"))
        assertTrue(manager.contains("legacyAckRequired"))
        assertTrue(manager.contains("legacy_source_only_wait"))
        assertTrue(manager.contains("live-backend-transition"))
        assertTrue(quickMenu.contains("revision = request.revision"))
        assertTrue(quickMenu.contains("backendGeneration = request.backendGeneration"))
        assertTrue(quickMenu.contains("updateConfigAtRuntimeCaptured"))
        assertTrue(quickMenu.contains("snapshot.armed"))
        assertTrue(manager.contains("VulkanRenderer.LSFG_FLOW_ADAPTIVE"))
        assertTrue(manager.contains("setFrameGenerationPressure"))
        assertTrue(manager.contains("direction=native-to-legacy"))
        assertTrue(manager.contains("reason = \"native-to-legacy-handoff\""))
        assertTrue(manager.contains("reason = \"native-retired\""))
        assertTrue(manager.contains("isSnapshotRevisionCurrent"))
        val publishStart = quickMenu.indexOf("private fun publishRuntimeConfig")
        val publishBody = quickMenu.substring(publishStart)
        assertTrue(!publishBody.contains("LsfgVkManager.refreshNativeRuntime(container)"))
        assertTrue(context.contains("MAX_FRAMES_IN_FLIGHT + g"))
        assertTrue(context.contains("&composite[currentFrame]"))
        assertTrue(context.contains("extraAcquireDeadline"))
        assertTrue(context.contains("recoverNativeAcquiredFrame()"))
        assertTrue(context.contains("generatedProvenance.uniqueDelivery = true"))
        val generatedPresent = context.indexOf("const VkResult generatedPresentResult = enqueueHostPresent")
        val sourcePresent = context.indexOf("res = enqueueHostPresent(PendingHostPresent{", generatedPresent)
        assertTrue(generatedPresent >= 0 && sourcePresent > generatedPresent)
        val renderStart = context.indexOf("void VulkanRendererContext::renderFrame()")
        val renderEnd = context.indexOf("void VulkanRendererContext::", renderStart + 8)
        val renderBody = context.substring(renderStart, if (renderEnd > renderStart) renderEnd else context.length)
        assertTrue(!renderBody.contains("DeviceWaitIdle"))
        assertTrue(
            Regex("ensureNativeExtraAcquireSemaphores\\(\\)")
                .findAll(renderBody)
                .count() == 1,
        )

        val createSyncStart = context.indexOf("void VulkanRendererContext::createSyncObjects()")
        val createSyncEnd = context.indexOf(
            "bool VulkanRendererContext::ensureNativeExtraAcquireSemaphores()",
            createSyncStart,
        )
        assertTrue(createSyncStart >= 0)
        assertTrue(createSyncEnd > createSyncStart)
        val createSyncBody = context.substring(createSyncStart, createSyncEnd)
        assertTrue(!createSyncBody.contains("nativeExtraAcquireSems_"))

        assertTrue(native.contains("swapchainCapacityIncrease"))
        assertTrue(native.contains("reason=swapchain-capacity-increase"))
        assertTrue(native.contains("nextImages > previous_images"))
        assertTrue(native.contains("stage=native-renderer"))
        assertTrue(native.contains("configRevision < framegenConfigRevision"))
        val backlogAdmission = context.indexOf("rejection_reason=host-present-backlog")
        assertTrue(backlogAdmission >= 0)
        val backlogWindow = context.substring(
            (backlogAdmission - 1200).coerceAtLeast(0),
            (backlogAdmission + 400).coerceAtMost(context.length),
        )
        assertTrue(backlogWindow.contains("nativeGeneratedBacklogRejected_"))
        assertTrue(!backlogWindow.contains("nativeGeneratedDeadlineRejected_"))

        val shaderStart = native.indexOf("void VulkanRendererContext::setFrameGenerationShaders")
        val shaderEnd = native.indexOf("void VulkanRendererContext::setSourceFrameCount", shaderStart)
        assertTrue(shaderStart >= 0)
        assertTrue(shaderEnd > shaderStart)
        val shaderBody = native.substring(shaderStart, shaderEnd)
        assertTrue(shaderBody.contains("if (lsfg != nullptr && device) waitNativeResources();"))
        assertTrue(!shaderBody.contains("if (device) vk_.DeviceWaitIdle(device);"))
    }
}
