package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexPowerMetricsContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun powerMetricsUsesApexSourceClockWhileApexOwnsPresentation() {
        val collector = repoFile(
            "app/src/main/java/app/gamenative/powercontrol/metrics/PerformanceMetricsCollector.kt",
        ).readText()
        assertTrue(collector.contains("ApexPresentationTelemetry.snapshot(now)"))
        assertTrue(collector.contains("ApexPresentationTelemetry.sourceFrameStats"))
        assertTrue(collector.contains("source=\$%s").not()) // guard accidental interpolation typo
        assertTrue(collector.contains("\"apex-source\""))
    }
}
