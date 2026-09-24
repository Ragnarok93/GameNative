package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexHudTelemetryContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun userFacingHudShowsApexOutputWhilePowerMetricsKeepSourceCadence() {
        val screen = repoFile(
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        ).readText()
        val collector = repoFile(
            "app/src/main/java/app/gamenative/powercontrol/metrics/PerformanceMetricsCollector.kt",
        ).readText()

        assertTrue(screen.contains("if (presentation.active)"))
        assertTrue(screen.contains("presentation.outputFps"))
        assertTrue(screen.contains("\"FPS %.1f | SRC %.1f | GEN %.1f%s\""))
        assertFalse(
            "Apex-owned HUD must not fall back to source FPS just because no swap has completed yet",
            screen.contains("presentation.active && presentation.outputPresented > 0L"),
        )

        assertTrue(
            "Power/adaptive metrics must remain source-cadence based",
            collector.contains("\"apex-source\"") &&
                collector.contains("ApexPresentationTelemetry.sourceFrameStats"),
        )
    }
}
