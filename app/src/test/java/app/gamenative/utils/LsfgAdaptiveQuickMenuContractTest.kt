package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgAdaptiveQuickMenuContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun adaptiveOutputTargetIsIndependentFromSourceLimiter() {
        val xServer = repoFile(
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        ).readText()

        listOf(
            "var lsfgAdaptiveOutputTarget by rememberSaveable",
            "adaptiveOutputTarget = lsfgAdaptiveOutputTarget",
            "adaptiveOutputTargetMax = detectedMaxRefreshRateHz",
            "onAdaptiveOutputTargetChanged = ::applyLsfgAdaptiveOutputTarget",
            "LsfgQuickMenuHelper.applyAdaptiveOutputTarget(container, sanitized)",
        ).forEach { token -> assertTrue("missing independent Adaptive output target plumbing: $token", xServer.contains(token)) }

        assertFalse(
            "disabling the source FPS limiter must not disable Adaptive FrameGen",
            xServer.contains("if (!enabled && lsfgAdaptiveFrameGen)"),
        )
        assertFalse(
            "enabling Adaptive FrameGen must not force-enable the source FPS limiter",
            xServer.contains("if (enabled && !fpsLimiterEnabled)"),
        )
    }

    @Test
    fun quickMenuExposesAdaptiveOnlyOutputTargetControl() {
        val quickMenu = repoFile(
            "app/src/main/java/app/gamenative/ui/component/QuickMenu.kt",
        ).readText()

        listOf(
            "val adaptiveOutputTarget: Int = 60",
            "val adaptiveOutputTargetMax: Int = 60",
            "val onAdaptiveOutputTargetChanged: (Int) -> Unit = {}",
            "visible = adaptiveFrameGen",
            "R.string.lsfg_adaptive_output_target",
            "R.string.lsfg_adaptive_output_target_desc",
            "fpsLimiterProgress(adaptiveOutputTarget, adaptiveOutputTargetMax)",
        ).forEach { token -> assertTrue("missing Adaptive output target Quick Menu contract: $token", quickMenu.contains(token)) }

        assertFalse(
            "Performance HUD source limiter must not present itself as Adaptive's final target",
            quickMenu.contains("performance_hud_fps_limiter_lsfg_adaptive"),
        )
    }
}
