package app.gamenative.framegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGenerationBackendTest {
    @Test
    fun persistedBackend_defaultsToLsfgForLegacyContainers() {
        assertEquals(FrameGenerationBackend.LSFG_VK, FrameGenerationBackend.fromPersisted(null))
        assertEquals(FrameGenerationBackend.LSFG_VK, FrameGenerationBackend.fromPersisted(""))
        assertEquals(FrameGenerationBackend.LSFG_VK, FrameGenerationBackend.fromPersisted("unknown"))
        assertEquals(FrameGenerationBackend.LSFG_VK, FrameGenerationBackend.fromPersisted("lsfg_vk"))
    }

    @Test
    fun persistedBackend_selectsApexWithoutLsfgSpecificRequirements() {
        val backend = FrameGenerationBackend.fromPersisted("APEX")
        assertEquals(FrameGenerationBackend.APEX, backend)
        assertFalse(backend.requiresLosslessScaling)
        assertFalse(backend.supportsVulkanPresentModeControl)
    }

    @Test
    fun lsfgRetainsCurrentBackendSpecificRequirements() {
        assertTrue(FrameGenerationBackend.LSFG_VK.requiresLosslessScaling)
        assertTrue(FrameGenerationBackend.LSFG_VK.supportsVulkanPresentModeControl)
    }
}
