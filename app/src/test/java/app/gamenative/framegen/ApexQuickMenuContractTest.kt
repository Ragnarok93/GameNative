package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexQuickMenuContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun apexSelectedBackendExposesLiveQuickMenuControls() {
        val quickMenu = repoFile("app/src/main/java/app/gamenative/ui/component/QuickMenu.kt").readText()
        val screen = repoFile("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()
        val manager = repoFile("app/src/main/java/app/gamenative/framegen/ApexFrameGenerationManager.kt").readText()
        val pacing = repoFile("app/src/main/cpp/apex/apex_pacing.cpp").readText()

        listOf(
            "ApexQuickMenuTab",
            "apexRuntimeEnabled",
            "apexMode",
            "apexFixedMultiplier",
            "apexAdaptiveTargetFps",
            "apexFlowScale",
            "apexQualityPreset",
        ).forEach { token ->
            assertTrue("Apex QuickMenu is missing $token", quickMenu.contains(token))
        }

        listOf(
            "applyApexRuntimeEnabled",
            "applyApexMode",
            "applyApexFixedMultiplier",
            "applyApexAdaptiveTargetFps",
            "applyApexFlowScale",
            "applyApexQualityPreset",
        ).forEach { token ->
            assertTrue("XServer runtime wiring is missing $token", screen.contains(token))
        }

        assertTrue(manager.contains("nativeSetAdaptiveFrameGeneration"))
        assertTrue(manager.contains("nativeSetFixedMultiplier"))
        assertTrue(pacing.contains("mAdaptiveFrameGeneration"))
        assertTrue(pacing.contains("mFixedMultiplier"))
    }
}
