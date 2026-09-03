package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LsfgRuntimeStatsTest {
    @Test
    fun parse_separatesOutputSourceAndMeasuredGenerationRatio() {
        val stats = LsfgRuntimeStats.parse(
            """
            active=1
            generation_ready=1
            fps=60.000
            source_fps=30.000
            generated_fps=30.000
            source_frames=30
            generated_frames=15
            generated_per_source=0.500
            """.trimIndent(),
        )

        assertEquals(60f, stats?.outputFps)
        assertEquals(30f, stats?.sourceFps)
        assertEquals(30, stats?.sourceFrames)
        assertEquals(15, stats?.generatedFrames)
        assertEquals(0.5f, stats?.generatedPerSource)
    }

    @Test
    fun parse_derivesRatioFromCountsForTransitionalNativeStats() {
        val stats = LsfgRuntimeStats.parse(
            "active=1\nfps=45\nsource_fps=30\ngenerated_fps=15\nsource_frames=30\ngenerated_frames=15",
        )
        assertEquals(0.5f, stats?.generatedPerSource)
    }

    @Test
    fun parse_rejectsInactiveOrMalformedFeedback() {
        assertNull(LsfgRuntimeStats.parse("active=0\nfps=60\nsource_fps=30"))
        assertNull(LsfgRuntimeStats.parse("active=1\nfps=bad\nsource_fps=30"))
        assertNull(LsfgRuntimeStats.parse("active=1\nfps=60\nsource_fps=-1"))
    }
}
