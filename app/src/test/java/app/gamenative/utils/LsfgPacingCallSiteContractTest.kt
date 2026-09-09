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
        assertTrue(source.contains("private enum class LsfgRuntimeMode"))
        assertTrue(source.contains("var lsfgRuntimeMode by rememberSaveable(container.id)"))
        assertTrue(source.contains("var isLsfgGenerationActive by rememberSaveable(container.id)"))
        assertTrue(source.contains("var lsfgRuntimeMultiplier by rememberSaveable(container.id)"))
        assertTrue(limiter.contains("val sourceFrameCap = effectiveSourceFpsCap(limit)"))
        assertTrue(limiter.contains("val runtimeMultiplier = if (lsfgActive) lsfgRuntimeMultiplier.coerceIn(2, 4) else 1"))
        assertTrue(limiter.contains("xServerView?.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("?.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("ShmFramePacer.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("PerformanceMetricsCollector.resetFrameEpoch()"))
        assertFalse(limiter.contains("if (lsfgActive) 0 else limit"))
        assertFalse(limiter.contains("transitionLsfgFramePacing"))
        assertFalse(limiter.contains("transitionFramePacing"))
        assertFalse(limiter.contains("LsfgRuntimeGate"))
    }

    @Test
    fun xServerScreenRunsLsfgVsyncClockDuringGenerationHandoff() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val vsyncEffect = source.substringAfter("DisposableEffect(container, lsfgRuntimeMode)")
            .substringBefore("// Event handlers defined in composable scope")

        assertTrue(source.contains("var lsfgRuntimeMode by rememberSaveable(container.id)"))
        assertTrue(vsyncEffect.contains("lsfgRuntimeMode == LsfgRuntimeMode.TURNING_ON"))
        assertTrue(vsyncEffect.contains("lsfgRuntimeMode == LsfgRuntimeMode.GENERATING"))
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

        assertTrue(handoff.contains("LsfgVkManager.readRuntimeState(container)"))
        assertTrue(handoff.contains("runtimeState.readyForGeneration"))
        assertTrue(handoff.contains("runtimeState.readyForSourceOnly"))
        assertTrue(handoff.contains("LSFG_RUNTIME_HANDOFF_TIMEOUT_MS"))
        assertTrue(handoff.contains("remainingSettleMs"))
        assertTrue(handoff.contains("lsfgRuntimeMode = LsfgRuntimeMode.DEGRADED"))
        assertTrue(handoff.contains("isLsfgGenerationActive = active"))
        assertTrue(handoff.contains("applyFpsLimiterToEngines(effectiveFpsLimit())"))
        assertTrue(multiplier.contains("val previousRequested = isLsfgRequested"))
        assertTrue(multiplier.contains("scheduleLsfgRuntimeHandoff(nextRequested, nextMultiplier)"))
        assertFalse(applier.contains("!isLsfgGenerationActive"))
        assertTrue(applier.contains("applyFpsLimiterToEngines(capFps)"))
    }

    @Test
    fun capOnlyChangesDoNotRepublishLsfgRuntimeSettings() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val enabled = source.substringAfter("fun applyFpsLimiterEnabled(enabled: Boolean)")
            .substringBefore("fun applyFpsLimiterTarget(target: Int)")
        val target = source.substringAfter("fun applyFpsLimiterTarget(target: Int)")
            .substringBefore("fun applyLsfgMultiplier(mult: Int)")

        assertFalse(enabled.contains("applyLsfgSettings()"))
        assertFalse(target.contains("applyLsfgSettings()"))
    }

    @Test
    fun quickMenuSuspendResumeInvalidatesTimingWithoutRuntimeRecreation() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val invalidator = source.substringAfter("fun invalidateSuspendedTiming(reason: String)")
            .substringBefore("fun startExitWatchForUnmappedGameWindow")

        assertTrue(invalidator.contains("PerformanceMetricsCollector.resetFrameEpoch()"))
        assertTrue(invalidator.contains("?.resetTiming()"))
        assertTrue(invalidator.contains("ShmFramePacer.resetTiming()"))
        assertFalse(invalidator.contains("applyLsfgSettings()"))
        assertFalse(invalidator.contains("scheduleLsfgRuntimeHandoff"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
