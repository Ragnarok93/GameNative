package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LsfgRuntimeStatsTest {
    @Test
    fun parse_separatesDisplayedOutputFromSourceFeedback() {
        val stats = LsfgRuntimeStats.parse(
            """
            active=1
            generation_ready=1
            fps=60.000
            source_fps=30.000
            generated_fps=30.000
            """.trimIndent(),
        )

        assertEquals(60f, stats?.outputFps)
        assertEquals(30f, stats?.sourceFps)
    }

    @Test
    fun parse_rejectsInactiveOrMalformedFeedback() {
        assertNull(LsfgRuntimeStats.parse("active=0\nfps=60\nsource_fps=30"))
        assertNull(LsfgRuntimeStats.parse("active=1\nfps=bad\nsource_fps=30"))
        assertNull(LsfgRuntimeStats.parse("active=1\nfps=60\nsource_fps=-1"))
    }
}
