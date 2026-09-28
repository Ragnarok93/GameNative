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
    fun adaptiveFlowPresetOrderIsLowBalancedQuality() {
        val quickMenu = repoFile(
            "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt",
        ).readText()
        val lsfgTab = quickMenu.substring(
            quickMenu.indexOf("private fun LsfgQuickMenuTab("),
            quickMenu.indexOf("private fun ImmersiveQuickMenuTab("),
        )

        val presetRow = lsfgTab.substring(
            lsfgTab.indexOf(
                "listOf(\n                            app.gamenative.utils.LsfgQuickMenuHelper.AdaptiveFlowPreset.",
            ),
            lsfgTab.indexOf(
                ").forEach { (candidate, label) ->",
                lsfgTab.indexOf(
                    "listOf(\n                            app.gamenative.utils.LsfgQuickMenuHelper.AdaptiveFlowPreset.",
                ),
            ),
        )
        val low = presetRow.indexOf("AdaptiveFlowPreset.LOW")
        val balanced = presetRow.indexOf("AdaptiveFlowPreset.BALANCED")
        val quality = presetRow.indexOf("AdaptiveFlowPreset.QUALITY")
        assertTrue("Low must be the left preset", low >= 0)
        assertTrue("Balanced must remain in the middle", balanced > low)
        assertTrue("Quality must be the right preset", quality > balanced)
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
        ).forEach { token ->
            assertTrue("Adaptive Flow preset copy is missing $token", strings.contains(token))
        }
    }
}
