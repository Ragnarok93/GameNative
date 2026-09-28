package app.gamenative.ui.component

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgAdaptiveFlowUiContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun adaptivePresetButtonsUseRequestedTwoByTwoOrder() {
        val source = repoFile(
            "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt",
        ).readText()
        val start = source.indexOf("val presetDescription")
        val end = source.indexOf(
            "Spacer(modifier = Modifier.height(4.dp))",
            start,
        )
        assertTrue("adaptive preset UI block must exist", start >= 0 && end > start)
        val block = source.substring(start, end)

        val auto = block.indexOf("AdaptiveFlowPreset.AUTO to")
        val quality = block.indexOf("AdaptiveFlowPreset.QUALITY to")
        val low = block.indexOf("AdaptiveFlowPreset.LOW to")
        val balanced = block.indexOf("AdaptiveFlowPreset.BALANCED to")

        assertTrue("Auto must be present", auto >= 0)
        assertTrue("Quality must be present", quality >= 0)
        assertTrue("Low must be present", low >= 0)
        assertTrue("Balanced must be present", balanced >= 0)
        assertTrue("top row must be Auto then Quality", auto < quality)
        assertTrue("bottom row must be Low then Balanced", low < balanced)
        assertTrue("top row must precede bottom row", quality < low)
        assertTrue("presets must use a two-row column", block.contains("Column("))
    }

    @Test
    fun autoPresetDescriptionStatesFullRangeSustainableTarget() {
        val strings = repoFile(
            "app/src/main/res/values/strings_lsfg_adaptive.xml",
        ).readText()
        assertTrue(strings.contains("lsfg_flow_preset_auto"))
        assertTrue(strings.contains("Target 1.00"))
        assertTrue(strings.contains("minimum 0.25"))
        assertTrue(strings.contains("highest sustainable"))
    }
}
