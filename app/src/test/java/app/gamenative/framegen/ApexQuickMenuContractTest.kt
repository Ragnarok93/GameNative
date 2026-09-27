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
    @Test
    fun disablingLsfgClearsFifoPresentationHintBeforeRuntimeHandoff() {
        val screen = repoFile(
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        ).readText()
        val multiplier = screen.substringAfter("fun applyLsfgMultiplier(mult: Int)")
            .substringBefore("fun applyLsfgFlowScale")

        assertTrue(
            "LSFG Off transition must have an explicit immediate cleanup branch",
            multiplier.contains("if (previousRequested && !nextRequested)"),
        )
        val transition = multiplier
            .substringAfter("if (previousRequested != nextRequested) {")
            .substringBefore("} else if (nextRequested)")
        val clear = transition.indexOf("setLsfgPresentationFrameRateHint(0)")
        val handoff = transition.indexOf(
            "scheduleLsfgRuntimeHandoff(nextRequested, nextMultiplier)",
        )

        assertTrue("FIFO presentation hint is not cleared on LSFG Off", clear >= 0)
        assertTrue("Runtime handoff is missing from LSFG Off", handoff >= 0)
        assertTrue(
            "FIFO presentation hint must be cleared before asynchronous Off handoff",
            clear < handoff,
        )
        assertTrue(!transition.contains("PresentExtension"))
        assertTrue(!transition.contains("ShmFramePacer"))
    }

}
