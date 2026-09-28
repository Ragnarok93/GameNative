package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgAdaptiveFlowUiContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun quickMenuOwnsAdaptiveFlowControlsWithoutAddingXServerCallbacks() {
        val quickMenu = repoFile(
            "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt",
        ).readText()
        val xServer = repoFile(
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        ).readText()

        listOf(
            "FlowScaleMode.FIXED",
            "FlowScaleMode.ADAPTIVE",
            "AdaptiveFlowPreset.QUALITY",
            "AdaptiveFlowPreset.BALANCED",
            "AdaptiveFlowPreset.LOW",
            "AdaptiveFlowPreset.AUTO",
            "setFlowScaleMode",
            "setAdaptiveFlowPreset",
        ).forEach { token ->
            assertTrue("Quick Menu must expose $token", quickMenu.contains(token))
        }

        assertFalse(
            "Adaptive Flow must not add another XServerScreen callback surface",
            xServer.contains("onFlowScaleModeChanged"),
        )
        assertFalse(
            "Adaptive Flow preset must remain owned by the LSFG helper/Quick Menu",
            xServer.contains("onAdaptiveFlowPresetChanged"),
        )
    }

    @Test
    fun lsfgChoiceRowsAreResponsiveAndLabelsStaySingleLine() {
        val quickMenu = repoFile(
            "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt",
        ).readText()

        val lsfgTab = quickMenu.substring(
            quickMenu.indexOf("private fun LsfgQuickMenuTab("),
            quickMenu.indexOf("private fun ImmersiveQuickMenuTab("),
        )
        val choiceChip = quickMenu.substring(
            quickMenu.indexOf("private fun QuickMenuChoiceChip("),
            quickMenu.indexOf("internal fun QuickMenuAdjustmentRow("),
        )
        val adjustmentRow = quickMenu.substring(
            quickMenu.indexOf("internal fun QuickMenuAdjustmentRow("),
            quickMenu.indexOf("private fun QuickMenuAdjustmentButton("),
        )

        assertFalse(
            "LSFG text choices must not use a device-specific 96dp width",
            lsfgTab.contains("modifier = Modifier.width(96.dp)"),
        )
        assertTrue(
            "LSFG option groups must divide the available row width",
            lsfgTab.windowed("modifier = Modifier.weight(1f)".length)
                .count { it == "modifier = Modifier.weight(1f)" } >= 4,
        )
        assertTrue(choiceChip.contains("singleLine: Boolean = false"))
        assertTrue(choiceChip.contains("maxLines = if (singleLine) 1 else Int.MAX_VALUE"))
        assertTrue(choiceChip.contains("softWrap = !singleLine"))
        assertTrue(
            choiceChip.contains(
                "overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip",
            ),
        )
        assertTrue(
            "Every LSFG choice group must opt into single-line labels",
            lsfgTab.windowed("singleLine = true".length)
                .count { it == "singleLine = true" } >= 5,
        )
        assertTrue(
            "Adjustment-row titles must yield space to the value column",
            adjustmentRow.contains(".weight(1f)"),
        )
        assertTrue(
            "Adjustment values must not wrap to a second line",
            adjustmentRow.contains("maxLines = 1"),
        )
    }

    @Test
    fun adaptiveFlowPresetOrderIsAutoQualityLowBalanced() {
        val quickMenu = repoFile(
            "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt",
        ).readText()
        val lsfgTab = quickMenu.substring(
            quickMenu.indexOf("private fun LsfgQuickMenuTab("),
            quickMenu.indexOf("private fun ImmersiveQuickMenuTab("),
        )

        val presetBlockStart = lsfgTab.indexOf("val presetDescription")
        val presetBlockEnd = lsfgTab.indexOf(
            "Spacer(modifier = Modifier.height(4.dp))",
            presetBlockStart,
        )
        assertTrue(
            "Adaptive Flow preset 2x2 block must exist",
            presetBlockStart >= 0 && presetBlockEnd > presetBlockStart,
        )
        val presetBlock = lsfgTab.substring(presetBlockStart, presetBlockEnd)
        val auto = presetBlock.indexOf("AdaptiveFlowPreset.AUTO to")
        val quality = presetBlock.indexOf("AdaptiveFlowPreset.QUALITY to")
        val low = presetBlock.indexOf("AdaptiveFlowPreset.LOW to")
        val balanced = presetBlock.indexOf("AdaptiveFlowPreset.BALANCED to")

        assertTrue("Auto must be present", auto >= 0)
        assertTrue("Quality must be present", quality >= 0)
        assertTrue("Low must be present", low >= 0)
        assertTrue("Balanced must be present", balanced >= 0)
        assertTrue("top row must be Auto then Quality", auto < quality)
        assertTrue("bottom row must be Low then Balanced", low < balanced)
        assertTrue("top row must precede bottom row", quality < low)
        assertTrue("presets must use a two-row column", presetBlock.contains("Column("))
    }

    @Test
    fun presetCopyMatchesNativeTargetAndFloorContract() {
        val strings = repoFile(
            "app/src/main/res/values/strings_lsfg_adaptive.xml",
        ).readText()

        listOf(
            "Target 1.00 · minimum 0.70",
            "Target 0.80 · minimum 0.55",
            "Target 0.55 · minimum 0.25",
            "Target 1.00 · minimum 0.25",
        ).forEach { token ->
            assertTrue("Adaptive Flow preset copy is missing $token", strings.contains(token))
        }
        assertTrue(
            "Auto copy must explain sustainable full-range selection",
            strings.contains("highest sustainable"),
        )
    }
}
