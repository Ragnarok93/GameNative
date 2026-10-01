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
                "vk_.FreeDescriptorSets(device, ahbTexPool, 1, &retired.texture.ds)"
            ),
        )
        assertTrue(
            "AHardwareBuffer ownership must be released during retirement",
            implementation.contains("AHardwareBuffer_release(retired.ahb)"),
        )
        assertTrue(
            "AHB imports must carry the serial of their last successful use",
            header.contains("lastUseSubmissionSerial"),
        )
        assertFalse(
            "raising the fixed descriptor-pool ceiling is not a lifetime fix",
            implementation.contains(
                "VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 512}"
            ),
        )
    }

    @Test
    fun retirementWaitsForObservedFenceCompletion() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("RendererSubmissionTimeline"))
        assertTrue(header.contains("completedSubmissionSerial"))
        assertTrue(implementation.contains("submissionTimeline.completeFrame"))
        assertTrue(implementation.contains("reclaimRetiredAhbImports"))
        assertFalse(implementation.contains("renderSubmissionSerial + MAX_FRAMES_IN_FLIGHT"))
    }

    @Test
    fun importsUseGlobalCapacityThatIncludesRetiredResources() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("MAX_AHB_IMPORTS_TOTAL"))
        assertTrue(implementation.contains("AhbImportBudget::canAllocate"))
        assertTrue(implementation.contains("createAhbTexPool"))
        assertTrue(implementation.contains("descriptorPool=ahbTexPool"))
        assertTrue(implementation.contains("ahbImportCache.size(), retiredAhbImports.size()"))
    }

    @Test
    fun ordinaryTexturesWaitForTheirLastUseBeforeDeletion() {
        val header = source("VulkanRendererContext.h")
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(header.contains("RetiredWindowTexture"))
        assertTrue(header.contains("lastUseSubmissionSerial"))
        assertTrue(implementation.contains("reclaimRetiredWindowTextures"))
    }

    @Test
    fun transitionsBetweenCpuAndAhbBuffersReleasePreviousOwnership() {
        val implementation = source("VulkanRendererContext.cpp")

        assertTrue(implementation.contains("releaseWindowAhbImports(id)"))
        assertTrue(implementation.contains("updateWindowContentAHB"))
        assertTrue(implementation.contains("destroyWinTex(wt)"))
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
