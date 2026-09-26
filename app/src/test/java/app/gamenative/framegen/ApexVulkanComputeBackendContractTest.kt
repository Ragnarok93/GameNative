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
                "Vulkan renderer build must compile $name to SPIR-V",
                cmake.contains("apex_vk_${name}_code"),
            )
        }
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
}
