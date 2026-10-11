package app.gamenative.powercontrol

import app.gamenative.powercontrol.metrics.FrameTimeRing
import org.junit.Assert.*
import org.junit.Test

class LsfgRateDomainAuditTest {
    @Test fun `collector restarts cannot reuse a pressure epoch`() {
        FrameTimeRing.start()
        val old = FrameTimeRing.generation()
        FrameTimeRing.stop()
        FrameTimeRing.start()
        try { assertTrue(FrameTimeRing.generation() > old) } finally { FrameTimeRing.stop() }
    }

    @Test fun `confirmed zero with live source is a clock deficit`() {
        val engine = app.gamenative.powercontrol.autotuning.TunerDecisionEngine()
        val input = app.gamenative.powercontrol.autotuning.TunerInput(
            fps = 0f, targetFps = 30, frameTimeP50Ms = 33f, frameTimeP95Ms = 34f,
            slowFrameCount = 0, totalFrameCount = 15, cpuUsagePercent = 50f,
            gpuUsagePercent = 90f, cpuTempC = null, gpuTempC = null,
            capabilities = app.gamenative.powercontrol.autotuning.TunerDomain.entries.associateWith {
                app.gamenative.powercontrol.autotuning.DomainCapability(true, true, true)
            },
        )
        assertEquals(app.gamenative.powercontrol.autotuning.TunerAction.RAISE, engine.decide(input).action)
    }

    @Test fun `native generated output does not divide guest source timestamps`() {
        assertEquals(1, lsfgSourceSampleStride(true, true, 4))
        assertEquals(4, lsfgSourceSampleStride(true, false, 4))
        val timestamps = LongArray(16) { 1_000_000_000L + it * 33_333_333L }
        val stats = app.gamenative.powercontrol.metrics.computeFrameWindowStats(
            timestamps, timestamps.size, 42_000_000L, LongArray(16), lsfgSourceSampleStride(true, true, 4))
        assertEquals(30f, stats.fps, 0.01f)
        assertEquals(33.333f, stats.p95Ms, 0.01f)
    }

    @Test fun `fixed multipliers compare healthy delivery in source cap units`() {
        for (multiplier in 2..4) {
            val pacing = FrameGenerationPacing(true, 1, multiplier, 0, 240)
            assertEquals(30f, powerTuningObservation(30f, 30, pacing, 30f * multiplier)!!.sourceEquivalentFps, 0.01f)
            assertEquals(15f, powerTuningObservation(30f, 30, pacing, 15f * multiplier)!!.sourceEquivalentFps, 0.01f)
        }
    }

    @Test fun `panel and adaptive contracts bound the output target`() {
        assertEquals(30f, powerTuningObservation(30f, 30, FrameGenerationPacing(true, 1, 4, 0, 60), 60f)!!.sourceEquivalentFps, 0.01f)
        assertEquals(30f, powerTuningObservation(30f, 30, FrameGenerationPacing(true, 1, 4, 90, 120), 90f)!!.sourceEquivalentFps, 0.01f)
        assertEquals(20f, powerTuningObservation(20f, 20, FrameGenerationPacing(true, 1, 4, 120, 120), 80f)!!.sourceEquivalentFps, 0.01f)
    }

    @Test fun `unknown and measured zero stay distinct at the governor boundary`() {
        val pacing = FrameGenerationPacing(true, 1, 4, 0, 120)
        assertNull(powerTuningObservation(30f, 30, pacing, null))
        assertNull(powerTuningObservation(30f, 30, pacing, Float.NaN))
        assertEquals(0f, powerTuningObservation(30f, 30, pacing, 0f)!!.sourceEquivalentFps, 0f)
    }

    @Test fun `missing legacy source tail data cannot authorize clock trims`() {
        val engine = app.gamenative.powercontrol.autotuning.TunerDecisionEngine()
        val input = app.gamenative.powercontrol.autotuning.TunerInput(
            fps = 30f, targetFps = 30, frameTimeP50Ms = 0f, frameTimeP95Ms = 0f,
            slowFrameCount = 0, totalFrameCount = 15, cpuUsagePercent = 30f,
            gpuUsagePercent = 30f, cpuTempC = null, gpuTempC = null,
            capabilities = app.gamenative.powercontrol.autotuning.TunerDomain.entries.associateWith {
                app.gamenative.powercontrol.autotuning.DomainCapability(true, true, true)
            }, sourcePacingValid = false,
        )
        repeat(60) { assertEquals(app.gamenative.powercontrol.autotuning.TunerAction.HOLD, engine.decide(input).action) }
        assertEquals(app.gamenative.powercontrol.autotuning.TunerAction.RAISE, engine.decide(input.copy(fps = 15f)).action)
    }

    @Test fun `all multiplier and panel combinations preserve healthy adaptive caps and recognize deficits`() {
        for (multiplier in 2..4) for (panel in listOf(60, 90, 120, 240)) for (adaptiveTarget in listOf(0, 90, 120)) {
            val pacing = FrameGenerationPacing(true, 1, multiplier, adaptiveTarget, panel)
            val physicalTarget = minOf(30 * multiplier, panel, if (adaptiveTarget > 0) adaptiveTarget else Int.MAX_VALUE).toFloat()
            val cap = app.gamenative.powercontrol.autotuning.AdaptiveFpsCap().apply { observeCap(30) }
            repeat(120) {
                val sample = powerTuningObservation(30f, 30, pacing, physicalTarget)!!
                assertNull(cap.onPowerTuningCycle(sample.sourceEquivalentFps, true, true))
            }
            assertEquals(30, cap.effectiveCapFps)
            var down: app.gamenative.powercontrol.autotuning.FpsCapChange? = null
            repeat(app.gamenative.powercontrol.autotuning.AdaptiveFpsCap.CYCLES_BEFORE_STEP) {
                val sample = powerTuningObservation(30f, 30, pacing, physicalTarget / 2f)!!
                down = cap.onPowerTuningCycle(sample.sourceEquivalentFps, true, true)
            }
            assertNotNull(down)
            assertTrue(down!!.toFps < 30)
        }
    }
}
