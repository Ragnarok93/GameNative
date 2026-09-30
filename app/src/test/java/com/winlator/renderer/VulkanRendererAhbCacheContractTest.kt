package com.winlator.renderer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRendererAhbCacheContractTest {
    @Test
    fun ahbImportsAreBoundedAndRetiredAfterRendererFenceProgress() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(
            "AHB imports must have an explicit bounded steady-state cache",
            header.contains("MAX_AHB_IMPORTS_PER_WINDOW"),
        )
        assertTrue(
            "AHB retirement must carry a submission serial rather than free resources immediately",
            header.contains("RetiredAhbImport") &&
                header.contains("retireAfterSubmissionSerial"),
        )
        assertTrue(
            "renderer must track successful submissions for safe AHB retirement",
            header.contains("renderSubmissionSerial"),
        )
        assertTrue(
            "renderer must have a dedicated deferred AHB retirement queue",
            header.contains("retiredAhbImports"),
        )
        assertTrue(
            "old window AHB imports must be explicitly evicted",
            implementation.contains("evictWindowAhbImports"),
        )
        assertTrue(
            "deferred AHB resources must be reclaimed from renderer progress",
            implementation.contains("reclaimRetiredAhbImports"),
        )
        assertTrue(
            "descriptor sets must be returned during deferred AHB retirement",
            implementation.contains(
                "vk_.FreeDescriptorSets(device, winTexPool, 1, &retired.texture.ds)"
            ),
        )
        assertTrue(
            "AHardwareBuffer ownership must be released during retirement",
            implementation.contains("AHardwareBuffer_release(retired.ahb)"),
        )
        assertTrue(
            "a retired AHB must survive at least the in-flight frame window",
            implementation.contains(
                "renderSubmissionSerial + MAX_FRAMES_IN_FLIGHT"
            ),
        )
        assertFalse(
            "raising the fixed descriptor-pool ceiling is not a lifetime fix",
            implementation.contains(
                "VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 512}"
            ),
        )
    }

    private fun source(name: String): String {
        val candidates = listOf(
            Paths.get("src/main/cpp/winlator").resolve(name),
            Paths.get("app/src/main/cpp/winlator").resolve(name),
        )
        val path: Path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("Unable to locate Vulkan renderer source: $name")
        return Files.readString(path)
    }
}
