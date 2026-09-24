package app.gamenative.powercontrol

import app.gamenative.powercontrol.autotuning.AdaptiveFpsCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTuningFpsSignalTest {
    @Test
    fun `adaptive fps cap never retunes source pacing while frame generation is active`() {
        assertFalse(adaptiveFpsCapMayAdjustSource(frameGenerationActive = true))
        assertTrue(adaptiveFpsCapMayAdjustSource(frameGenerationActive = false))
    }

    @Test
    fun `active frame generation uses fresh post-LSFG output cadence`() {
        assertEquals(60f, selectPowerTuningFps(15f, true, 60f))
    }

    @Test
    fun `active frame generation holds without a fresh output sample`() {
        assertNull(selectPowerTuningFps(15f, true, null))
    }

    @Test
    fun `inactive frame generation keeps the normal metric`() {
        assertEquals(47f, selectPowerTuningFps(47f, false, null))
    }

    @Test
    fun `sixty fps LSFG output cannot step a sixty fps cap down to forty-five`() {
        val cap = AdaptiveFpsCap().apply { observeCap(60) }

        repeat(AdaptiveFpsCap.CYCLES_BEFORE_STEP) {
            val tuningFps = selectPowerTuningFps(
                sourceFps = 15f,
                frameGenerationActive = true,
                postLsfgOutputFps = 60f,
            )
            val change = cap.onPowerTuningCycle(
                tuningFps,
                clocksOpen = true,
                clockHeadroom = true,
            )
            assertNull("LSFG output met the target but requested $change", change)
        }

        assertEquals(60, cap.effectiveCapFps)
        assertEquals(0, cap.triggerCycles)
    }

    @Test
    fun `missing LSFG output clears prior deficit evidence instead of falling back`() {
        val cap = AdaptiveFpsCap().apply { observeCap(60) }

        repeat(AdaptiveFpsCap.CYCLES_BEFORE_STEP - 1) {
            assertNull(cap.onPowerTuningCycle(15f, clocksOpen = true, clockHeadroom = true))
        }
        assertEquals(AdaptiveFpsCap.CYCLES_BEFORE_STEP - 1, cap.triggerCycles)

        assertNull(cap.onPowerTuningCycle(null, clocksOpen = true, clockHeadroom = true))
        assertEquals(0, cap.triggerCycles)
        assertNull(cap.onPowerTuningCycle(15f, clocksOpen = true, clockHeadroom = true))
        assertEquals(60, cap.effectiveCapFps)
    }
}
