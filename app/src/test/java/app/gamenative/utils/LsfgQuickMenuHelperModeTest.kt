package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgQuickMenuHelperModeTest {
    @Test
    fun offWinsWhenMultiplierIsDisabled() {
        assertEquals(
            LsfgQuickMenuHelper.FrameGenerationMode.OFF,
            LsfgQuickMenuHelper.frameGenerationMode(0, adaptiveEnabled = true),
        )
    }

    @Test
    fun enabledNonAdaptiveStateIsFixed() {
        assertEquals(
            LsfgQuickMenuHelper.FrameGenerationMode.FIXED,
            LsfgQuickMenuHelper.frameGenerationMode(2, adaptiveEnabled = false),
        )
    }

    @Test
    fun enabledAdaptiveStateIsAdaptive() {
        assertEquals(
            LsfgQuickMenuHelper.FrameGenerationMode.ADAPTIVE,
            LsfgQuickMenuHelper.frameGenerationMode(4, adaptiveEnabled = true),
        )
    }
}
