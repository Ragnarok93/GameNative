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

        assertTrue(limiter.contains("val lsfgActive = isLsfgAvailable && lsfgMultiplier >= 2"))
        assertTrue(limiter.contains("val vulkanPresentLimit = if (lsfgActive) 0 else limit"))
        assertTrue(limiter.contains("xServerView?.setFrameRateLimit(vulkanPresentLimit)"))
        assertTrue(limiter.contains("?.setFrameRateLimit(vulkanPresentLimit)"))
        assertTrue(limiter.contains("ShmFramePacer.setFrameRateLimit(limit)"))
        assertTrue(limiter.contains("PerformanceMetricsCollector.resetFrameEpoch()"))
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

        assertTrue(source.contains("val isLsfgGenerationActive = isLsfgAvailable && lsfgMultiplier >= 2"))
        assertTrue(vsyncEffect.contains("if (isLsfgGenerationActive)"))
        assertTrue(vsyncEffect.contains("LsfgVkManager.startVsyncClock(context, container)"))
        assertFalse(vsyncEffect.contains("if (isLsfgAvailable)"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
