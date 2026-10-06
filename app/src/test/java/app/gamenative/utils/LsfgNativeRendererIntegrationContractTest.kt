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
        assertTrue(context.contains("nativeGeneratedAcquireSemaphore"))
        assertTrue(context.contains("nativeGeneratedImgIdx"))
        assertTrue(context.contains("vkr_lsfg_plan(lsfg, 1, nativeSourceFrame)"))
        assertTrue(context.contains("vkr_lsfg_process("))
        assertTrue(context.contains("blitCompositeToSwapchain("))
        assertTrue(context.contains("signalSemaphoreCount=signalSemaphoreCount"))
        assertTrue(context.contains("nativeRuntimeActive"))
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

        val shaderStart = native.indexOf("void VulkanRendererContext::setFrameGenerationShaders")
        val shaderEnd = native.indexOf("void VulkanRendererContext::setSourceFrameCount", shaderStart)
        assertTrue(shaderStart >= 0)
        assertTrue(shaderEnd > shaderStart)
        val shaderBody = native.substring(shaderStart, shaderEnd)
        assertTrue(shaderBody.contains("if (lsfg != nullptr && device) vk_.DeviceWaitIdle(device);"))
        assertTrue(!shaderBody.contains("if (device) vk_.DeviceWaitIdle(device);"))
    }
}