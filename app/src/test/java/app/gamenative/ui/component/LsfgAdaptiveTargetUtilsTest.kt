package app.gamenative.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgAdaptiveTargetUtilsTest {

    @Test
    fun `adaptive target uses 30 to 120 fps in five fps steps`() {
        assertEquals((30..120 step 5).toList(), adaptiveTargetFpsSteps())
    }

    @Test
    fun `adaptive target sanitizer clamps and floors restored values to a valid step`() {
        assertEquals(30, sanitizeAdaptiveTargetFps(20))
        assertEquals(30, sanitizeAdaptiveTargetFps(30))
        assertEquals(30, sanitizeAdaptiveTargetFps(32))
        assertEquals(115, sanitizeAdaptiveTargetFps(117))
        assertEquals(120, sanitizeAdaptiveTargetFps(120))
        assertEquals(120, sanitizeAdaptiveTargetFps(145))
    }

    @Test
    fun `adaptive target plus button advances by five fps and clamps at 120`() {
        assertEquals(35, nextAdaptiveTargetFps(30))
        assertEquals(120, nextAdaptiveTargetFps(115))
        assertEquals(120, nextAdaptiveTargetFps(120))
    }

    @Test
    fun `adaptive target minus button decrements by five fps and clamps at 30`() {
        assertEquals(30, previousAdaptiveTargetFps(30))
        assertEquals(30, previousAdaptiveTargetFps(35))
        assertEquals(115, previousAdaptiveTargetFps(120))
    }

    @Test
    fun `adaptive target progress matches the frame limiter adjustment row convention`() {
        assertEquals(0f, adaptiveTargetFpsProgress(30), 0.001f)
        assertEquals(0.5f, adaptiveTargetFpsProgress(75), 0.001f)
        assertEquals(1f, adaptiveTargetFpsProgress(120), 0.001f)
    }
}
