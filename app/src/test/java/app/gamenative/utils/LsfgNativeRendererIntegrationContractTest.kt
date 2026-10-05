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

        assertTrue(context.contains("#include \"../lsfg/vk_dispatch.h\""))
        assertTrue(context.contains("vkd_load(instance, device, gipa)"))
        assertTrue(context.contains("vkd_unload()"))
        assertTrue(native.contains("vkr_lsfg_create"))
        assertTrue(native.contains("vkr_lsfg_generate_into"))
        assertTrue(context.contains("nativeExtraAcquireSems_"))
        assertTrue(context.contains("nativeGeneratedAcquireSemaphore"))
        assertTrue(context.contains("nativeGeneratedImgIdx"))
        assertTrue(context.contains("vkr_lsfg_plan(lsfg, 1, nativeSourceFrame)"))
        assertTrue(context.contains("vkr_lsfg_process("))
        assertTrue(context.contains("blitCompositeToSwapchain("))
        assertTrue(context.contains("signalSemaphoreCount=signalSemaphoreCount"))
        assertTrue(context.contains("nativeRuntimeActive"))
        val renderStart = context.indexOf("void VulkanRendererContext::renderFrame()")
        val renderBody = context.substring(renderStart)
        assertTrue(!renderBody.contains("DeviceWaitIdle"))
    }
}
