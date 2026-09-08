package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgPacingCallSiteContractTest {
    @Test
    fun xServerScreenOwnsLsfgPacingHandoff() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val limiter = source.substringAfter("fun applyFpsLimiterToEngines(limit: Int)")
            .substringBefore("fun effectiveFpsLimit()")

        assertTrue(source.contains("val isLsfgRequested = isLsfgAvailable && lsfgMultiplier >= 2"))
        assertTrue(source.contains("var isLsfgGenerationActive by rememberSaveable(container.id)"))
        assertTrue(source.contains("var lsfgRuntimeMultiplier by rememberSaveable(container.id)"))
        assertTrue(limiter.contains("val lsfgActive = isLsfgGenerationActive"))
        assertTrue(limiter.contains("val vulkanPresentLimit = if (lsfgActive) 0 else limit"))
        assertTrue(limiter.contains("xServerView?.setFrameRateLimit(vulkanPresentLimit)"))
        assertTrue(limiter.contains("?.setFrameRateLimit(vulkanPresentLimit)"))
        assertTrue(limiter.contains("ShmFramePacer.setFrameRateLimit(limit)"))
        assertTrue(limiter.contains("PerformanceMetricsCollector.resetFrameEpoch()"))
        assertTrue(limiter.contains("if (lsfgActive) lsfgRuntimeMultiplier.coerceIn(2, 4) else 1"))
        assertFalse(limiter.contains("transitionLsfgFramePacing"))
        assertFalse(limiter.contains("transitionFramePacing"))
        assertFalse(limiter.contains("LsfgRuntimeGate"))
    }

    @Test
    fun xServerScreenOnlyRunsLsfgVsyncClockWhileGenerationIsActive() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val vsyncEffect = source.substringAfter("DisposableEffect(container, isLsfgGenerationActive)")
            .substringBefore("// Event handlers defined in composable scope")

        assertTrue(source.contains("var isLsfgGenerationActive by rememberSaveable(container.id)"))
        assertTrue(vsyncEffect.contains("if (isLsfgGenerationActive)"))
        assertTrue(vsyncEffect.contains("LsfgVkManager.startVsyncClock(context, container)"))
        assertFalse(vsyncEffect.contains("if (isLsfgAvailable)"))
    }

    @Test
    fun quickMenuDebouncesLsfgRuntimePacingHandoff() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val handoff = source.substringAfter("fun scheduleLsfgRuntimeHandoff")
            .substringBefore("fun applyFpsLimiterEnabled")
        val multiplier = source.substringAfter("fun applyLsfgMultiplier(mult: Int)")
            .substringBefore("fun applyLsfgFlowScale")
        val applier = source.substringAfter("PowerManager.fpsCapApplier = applier@")
            .substringBefore("val detectedMax")

        assertTrue(handoff.contains("delay(LSFG_RUNTIME_HANDOFF_DELAY_MS)"))
        assertTrue(handoff.contains("isLsfgGenerationActive = active"))
        assertTrue(handoff.contains("applyFpsLimiterToEngines(effectiveFpsLimit())"))
        assertTrue(multiplier.contains("val previousRequested = isLsfgRequested"))
        assertTrue(multiplier.contains("scheduleLsfgRuntimeHandoff(nextRequested, nextMultiplier)"))
        assertTrue(applier.contains("!isLsfgGenerationActive"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
