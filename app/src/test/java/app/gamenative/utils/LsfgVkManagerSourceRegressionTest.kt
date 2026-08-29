package app.gamenative.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LsfgVkManagerSourceRegressionTest {
    private fun source(): String =
        File("src/main/java/app/gamenative/utils/LsfgVkManager.kt").readText()

    @Test
    fun launchEnvironmentBridgesLegacyAndModernVulkanLoaders() {
        val source = source()

        assertTrue(
            source.contains("private const val VULKAN_LAYER_NAME = \"VK_LAYER_LS_frame_generation\""),
            "launch env must target the layer name declared by the bundled LSFG manifest",
        )
        assertTrue(
            source.contains("ENV_VK_INSTANCE_LAYERS"),
            "legacy Vulkan loaders must explicitly enable LSFG through VK_INSTANCE_LAYERS",
        )
        assertTrue(
            source.contains("ENV_VK_LOADER_LAYERS_ENABLE"),
            "newer Vulkan loaders must explicitly enable LSFG through VK_LOADER_LAYERS_ENABLE",
        )
        assertTrue(
            source.contains("ENV_VK_ADD_LAYER_PATH"),
            "newer Vulkan loaders should receive the per-container manifest path without replacing caller paths",
        )
    }

    @Test
    fun launchEnvironmentEmitsCompactLsfgLoaderProbe() {
        val source = source()

        assertTrue(
            source.contains("LSFG loader env:"),
            "LSFG launches need an untruncated loader-specific diagnostic independent of the full environment dump",
        )
        assertTrue(
            source.contains("VK_LOADER_DEBUG"),
            "armed LSFG launches should request loader layer diagnostics so load failures are actionable",
        )
    }
}
