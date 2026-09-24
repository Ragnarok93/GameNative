package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexVulkanTargetContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun vulkanRendererExposesNonBlockingApexAhbRing() {
        val header = repoFile("app/src/main/cpp/winlator/VulkanRendererContext.h").readText()
        val source = repoFile("app/src/main/cpp/winlator/VulkanRendererContext.cpp").readText()
        val jni = repoFile("app/src/main/cpp/winlator/vulkan_jni.cpp").readText()
        val java = repoFile("app/src/main/java/com/winlator/renderer/VulkanRenderer.java").readText()

        listOf(
            "enableApexTarget",
            "disableApexTarget",
            "dequeueApexFrame",
            "apexFrameBufferPtr",
            "takeApexFrameFenceFd",
            "releaseApexFrame",
        ).forEach { token ->
            assertTrue("native Apex target API is missing $token", header.contains(token))
        }

        listOf(
            "VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME",
            "GetSemaphoreFdKHR",
            "ImportSemaphoreFdKHR",
            "apexTargetRing.acquireForProducer()",
            "apexTargetRing.markReady",
            "apexTargetRing.dequeueForConsumer()",
            "VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT",
        ).forEach { token ->
            assertTrue("Apex Vulkan handoff is missing $token", source.contains(token))
        }

        assertTrue(
            "ring saturation must defer the real capture rather than block or steal a consumer buffer",
            source.contains("apexProducerBacklogged.store(true"),
        )
        assertTrue(
            "consumer release must not create a self-sustaining source-render loop",
            source.contains("apexProducerBacklogged.exchange(false") &&
                !source.contains("slot.consumerSequence = 0;\n    needsRender.store(true"),
        )
        assertTrue(
            "Apex source frames must omit the compositor cursor so the presenter can keep cursor motion real",
            source.contains("curVis && !scanoutActive.load() && !toApex"),
        )
        assertTrue(
            "each AHB capture must carry the guest-content generation snapshot",
            header.contains("sourceGeneration") &&
                source.contains("slot.sourceGeneration = apexSourceGeneration.load"),
        )
        assertTrue(
            "duplicate compositor redraws must be collapsed before GLES history",
            source.contains("sourceGeneration <= apexLastDequeuedSourceGeneration"),
        )
        assertTrue(
            "Java must tag direct PresentExtension content separately from auxiliary redraws",
            java.contains("markApexSourceFrame(true)") &&
                java.contains("APEX_DIRECT_SOURCE_GRACE_NS"),
        )

        val enableStart = source.indexOf("bool VulkanRendererContext::enableApexTarget()")
        val disableStart = source.indexOf("bool VulkanRendererContext::disableApexTarget()")
        assertTrue(enableStart >= 0 && disableStart > enableStart)
        val enableBody = source.substring(enableStart, disableStart)
        assertFalse("Apex target enable must not globally idle the Vulkan device", enableBody.contains("DeviceWaitIdle"))

        val disableEnd = source.indexOf("int64_t VulkanRendererContext::dequeueApexFrame()", disableStart)
        assertTrue(disableEnd > disableStart)
        val disableBody = source.substring(disableStart, disableEnd)
        assertFalse("Apex target disable must not globally idle the Vulkan device", disableBody.contains("DeviceWaitIdle"))

        listOf(
            "nativeEnableApexTarget",
            "nativeDisableApexTarget",
            "nativeDequeueApexFrame",
            "nativeGetApexFrameBuffer",
            "nativeTakeApexFrameFenceFd",
            "nativeReleaseApexFrame",
        ).forEach { token ->
            assertTrue("JNI handoff is missing $token", jni.contains(token))
            assertTrue("VulkanRenderer bridge is missing $token", java.contains(token))
        }

        assertTrue(
            "Apex activation must force the compositor instead of Native Rendering+ scanout",
            java.contains("|| apexFrameTargetActive"),
        )
    }

    @Test
    fun consumerReleaseFenceIsFedBackBeforeSlotReuse() {
        val source = repoFile("app/src/main/cpp/winlator/VulkanRendererContext.cpp").readText()

        assertTrue(source.contains("consumerReleaseFenceFd"))
        assertTrue(source.contains("VK_SEMAPHORE_IMPORT_TEMPORARY_BIT"))
        assertTrue(source.contains("ImportSemaphoreFdKHR"))
        assertTrue(source.contains("consumerDoneSemaphore"))
    }
}
