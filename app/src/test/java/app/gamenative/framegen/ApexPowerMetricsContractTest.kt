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
    @Test
    fun apexGpuTimingUsesSampledNonBlockingDisjointTimerQueries() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        listOf(
            "GL_EXT_disjoint_timer_query",
            "GL_TIME_ELAPSED_EXT",
            "GL_QUERY_RESULT_AVAILABLE",
            "GL_GPU_DISJOINT_EXT",
            "glGetQueryObjectui64vEXT",
            "GPU_TIMER_SAMPLE_INTERVAL",
            "pollGpuTimerQueries",
            "Apex GPU timing:",
        ).forEach { token ->
            assertTrue("Apex GPU timing instrumentation is missing $token",
                pipeline.contains(token) || engine.contains(token))
        }

        val availabilityPoll = pipeline.indexOf(
            "glGetQueryObjectuiv(lastQuery, GL_QUERY_RESULT_AVAILABLE",
        )
        val resultRead = pipeline.indexOf(
            "mGetQueryObjectui64vEXT(query, GL_QUERY_RESULT",
            availabilityPoll,
        )
        assertTrue(
            "timer result collection must poll availability before reading a result",
            availabilityPoll >= 0 && resultRead > availabilityPoll,
        )
        assertTrue(
            "timer queries must use 64-bit nanosecond results",
            pipeline.contains("GLuint64") &&
                pipeline.contains("glGetQueryObjectui64vEXT"),
        )
        assertTrue(
            "GPU timing instrumentation must never synchronize the pipeline with glFinish",
            !pipeline.contains("glFinish()"),
        )
    }

    @Test
    fun apexGpuTimingUsesExtEntryPointsConsistently() {
        val engine = repoFile("app/src/main/cpp/apex/apex_engine.h").readText()
        val pipeline = repoFile("app/src/main/cpp/apex/apex_pipeline.cpp").readText()

        listOf(
            "glGenQueriesEXT",
            "glDeleteQueriesEXT",
            "glBeginQueryEXT",
            "glEndQueryEXT",
            "glGetQueryObjectuivEXT",
            "glGetQueryObjectui64vEXT",
        ).forEach { token ->
            assertTrue("EXT timer-query plumbing is missing $token",
                pipeline.contains(token) || engine.contains(token))
        }
        assertTrue(
            "timer extension support must require every EXT entry point used by the sampler",
            pipeline.contains("mGenQueriesEXT") &&
                pipeline.contains("mDeleteQueriesEXT") &&
                pipeline.contains("mBeginQueryEXT") &&
                pipeline.contains("mEndQueryEXT") &&
                pipeline.contains("mGetQueryObjectuivEXT"),
        )
        assertTrue(
            "timer implementation must not mix core query entry points with EXT query targets",
            !pipeline.contains("glGenQueries(") &&
                !pipeline.contains("glDeleteQueries(") &&
                !pipeline.contains("glBeginQuery(") &&
                !pipeline.contains("glEndQuery("),
        )
    }

}
