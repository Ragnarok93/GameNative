package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexVulkanComputeBackendContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun allApexComputeStagesHaveBuildTimeSpirvPorts() {
        val shaders = listOf(
            "luma_grad",
            "inverse_search",
            "propagate",
            "densify",
            "vr_setup",
            "vr_sor",
            "interpolate",
            "rcas",
        )
        shaders.forEach { name ->
            val source = repoFile(
                "app/src/main/cpp/apex/vulkan/shaders/$name.comp",
            ).readText()
            assertTrue("$name must target Vulkan GLSL", source.contains("#version 450"))
            assertTrue(
                "$name must use explicit Vulkan descriptor-set bindings",
                source.contains("set = 0") && source.contains("binding ="),
            )
        }

        val interpolate = repoFile(
            "app/src/main/cpp/apex/vulkan/shaders/interpolate.comp",
        ).readText()
        assertFalse(
            "Vulkan interpolation must not resurrect the dead dW input",
            interpolate.contains("dW"),
        )

        val cmake = repoFile(
            "app/src/main/cpp/vulkan_renderer_build/CMakeLists.txt",
        ).readText()
        shaders.forEach { name ->
            assertTrue(
                "Vulkan renderer build must enumerate $name",
                cmake.contains(name),
            )
        }
        assertTrue(
            "Vulkan renderer build must generate stable embedded SPIR-V symbols",
            cmake.contains("--vn apex_vk_\${APEX_VK_SHADER}_code"),
        )
        assertTrue(cmake.contains("apex/vulkan/apex_vk_backend.cpp"))
    }

    @Test
    fun backendReusesRendererDeviceAndHasNoGlesDependency() {
        val header = repoFile(
            "app/src/main/cpp/apex/vulkan/apex_vk_backend.h",
        ).readText()
        val source = repoFile(
            "app/src/main/cpp/apex/vulkan/apex_vk_backend.cpp",
        ).readText()
        val rendererHeader = repoFile(
            "app/src/main/cpp/winlator/VulkanRendererContext.h",
        ).readText()
        val fallbackCmake = repoFile(
            "app/src/main/cpp/apex/CMakeLists.txt",
        ).readText()

        listOf("VkPhysicalDevice", "VkDevice", "VkQueue", "queueFamilyIndex").forEach { token ->
            assertTrue("backend context is missing $token", header.contains(token))
        }
        assertTrue(rendererHeader.contains("PFN_vkCreateComputePipelines"))
        assertTrue(rendererHeader.contains("PFN_vkCmdDispatch"))
        assertTrue(source.contains("CreateComputePipelines"))
        assertTrue(source.contains("CmdDispatch"))

        listOf("vkCreateInstance", "vkCreateDevice", "egl", "EGL", "GLES", "glDispatchCompute").forEach { token ->
            assertFalse("Vulkan backend must not depend on $token", source.contains(token))
        }

        assertTrue(
            "GLES implementation must remain available as the compatibility fallback",
            fallbackCmake.contains("EGL") && fallbackCmake.contains("GLESv3"),
        )
    }

    @Test
    fun rendererOwnedSourceFeedsVulkanHistoryAndFullActiveDisGraph() {
        val header = repoFile(
            "app/src/main/cpp/apex/vulkan/apex_vk_backend.h",
        ).readText()
        val source = repoFile(
            "app/src/main/cpp/apex/vulkan/apex_vk_backend.cpp",
        ).readText()
        val renderer = repoFile(
            "app/src/main/cpp/winlator/VulkanRendererContext.cpp",
        ).readText()

        listOf(
            "struct ImageResource",
            "struct LevelResources",
            "ensureResources",
            "recordSourceGraph",
            "generatedImage",
            "generatedImageView",
        ).forEach { token ->
            assertTrue("Vulkan resource graph is missing $token", header.contains(token))
        }

        listOf(
            "VK_IMAGE_USAGE_STORAGE_BIT",
            "VK_IMAGE_USAGE_TRANSFER_DST_BIT",
            "CreateDescriptorPool",
            "ResetDescriptorPool",
            "UpdateDescriptorSets",
            "CmdCopyImage",
            "CmdPipelineBarrier",
            "Stage::LumaGrad",
            "Stage::InverseSearch",
            "Stage::Propagate",
            "Stage::Densify",
            "Stage::Interpolate",
        ).forEach { token ->
            assertTrue("Vulkan resource/dispatch implementation is missing $token", source.contains(token))
        }

        assertTrue(
            "Apex renderer target must be copyable directly into Vulkan history",
            renderer.contains("VK_IMAGE_USAGE_TRANSFER_SRC_BIT"),
        )
        assertTrue(
            "shadow Vulkan graph must consume the renderer-owned source image and view",
            renderer.contains("recordSourceGraph(") &&
                renderer.contains("slot.image") &&
                renderer.contains("slot.view"),
        )
        assertTrue(
            "Vulkan migration must stay behind an explicit runtime gate until presentation is ported",
            renderer.contains("debug.gamenative.apex_vk_shadow"),
        )

        listOf("AHardwareBuffer", "EGL", "GLES", "glDispatchCompute").forEach { token ->
            assertFalse(
                "native Vulkan graph must not depend on compatibility API $token",
                source.contains(token),
            )
        }
    }

}
