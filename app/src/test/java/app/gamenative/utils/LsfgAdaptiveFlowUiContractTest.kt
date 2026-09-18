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
