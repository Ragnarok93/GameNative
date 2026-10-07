package app.gamenative.powercontrol

import app.gamenative.powercontrol.autotuning.AdaptiveFpsCap
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerTuningFpsSignalTest {
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
    @Test
    fun `confirmed zero remains a deficit instead of source-FPS fallback`() {
        assertEquals(0f, selectPowerTuningFps(70f, true, 0f))
        val cap = AdaptiveFpsCap().apply { observeCap(60) }
        var change: app.gamenative.powercontrol.autotuning.FpsCapChange? = null
        repeat(AdaptiveFpsCap.CYCLES_BEFORE_STEP) {
            change = cap.onPowerTuningCycle(0f, clocksOpen = true, clockHeadroom = true)
        }
        assertEquals(45, change?.toFps)
    }

    @Test
    fun `all power-controller paths use the same confirmed output selection`() {
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (!File(root, "app/src/main/java/app/gamenative/powercontrol/PowerManager.kt").isFile)
                root = root.parentFile ?: root
        }
        val base = File(root, "app/src/main/java/app/gamenative/powercontrol")
        val owner = File(base, "PowerManager.kt").readText()
        val cap = File(base, "AdaptiveFpsCapController.kt").readText()
        val tuner = File(base, "autotuning/PerformanceAutoTuner.kt").readText()
        assertTrue(owner.contains("tuningFpsProvider = { fpsForTuning(it) }"))
        assertTrue(cap.contains("PowerManager.fpsForTuning(snapshot.fps)"))
        assertTrue(cap.contains("cap.onCycle(tuningFps, clocksOpen, clockHeadroom)"))
        assertTrue(!cap.contains("cap.onCycle(snapshot.fps,"))
        assertTrue(tuner.contains("PowerManager.fpsForTuning(PowerManager.currentFps)"))
        assertTrue(tuner.contains("currentFps == 0.0 && PowerManager.frameSampleStride <= 1"))
    }

}
