package app.gamenative.powercontrol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgPowerFeedbackTest {
    @After
    fun tearDown() {
        PowerManager.tuningFpsProvider = null
        PowerManager.currentFps = 0f
    }

    @Test
    fun tuningUsesSourceFpsInsteadOfMultipliedOutputEvents() {
        PowerManager.currentFps = 162f
        PowerManager.tuningFpsProvider = { 58f }

        assertEquals(58f, PowerManager.currentTuningFps())
    }

    @Test
    fun tuningFallsBackWhenLsfgFeedbackIsUnavailable() {
        PowerManager.currentFps = 57f
        PowerManager.tuningFpsProvider = { null }

        assertEquals(57f, PowerManager.currentTuningFps())
    }
}
