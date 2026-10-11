package app.gamenative.powercontrol.autotuning

import app.gamenative.powercontrol.FrameGenerationPacing
import app.gamenative.powercontrol.powerTuningObservation
import app.gamenative.powercontrol.metrics.CpuUsageSource
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual owner, decision engine, and cap applications without its worker clock. */
class LsfgClusterInteractionAuditTest {
    private class Fixture {
        var output: Float? = 120f
        var source = 30f
        var sourcePacingValid = true
        val applied = ArrayList<Long>()
        val tuner = ClusterTuner(
            primeSteps = listOf(100L, 200L, 300L, 400L, 500L),
            performanceSteps = emptyList(), gpuSteps = emptyList(),
            applyPrimeCapKhz = { applied.add(it); true }, applyPerformanceCapKhz = { true }, applyGpuMaxLevel = { true },
            metricsProvider = { MetricsSnapshot(System.currentTimeMillis(), source, 33f, 34f, 34f, 0, 15,
                40f, CpuUsageSource.PROC_STAT, 40f, null, null) },
            targetFpsProvider = { 30 },
            tuningObservationProvider = { powerTuningObservation(it, 30, FrameGenerationPacing(true, 1, 4, 0, 120), output) },
            sourcePacingValidProvider = { sourcePacingValid }, stateFile = null, logDirectory = null,
        )
        fun cycle() { ClusterTuner::class.java.getDeclaredMethod("runCycle").apply { isAccessible = true }.invoke(tuner) }
    }

    @Test fun `healthy four-times display delivery does not repeatedly downclock as 120 source fps`() {
        val f = Fixture()
        repeat(5) { f.cycle() }
        assertEquals(listOf(400L), f.applied)
        repeat(3) { f.cycle() }
        assertEquals(listOf(400L), f.applied)
    }

    @Test fun `unknown output interrupts trim qualification`() {
        val f = Fixture()
        repeat(4) { f.cycle() }
        f.output = null
        f.cycle()
        f.output = 120f
        f.cycle()
        assertTrue(f.applied.isEmpty())
    }

    @Test fun `confirmed display outage with live source reopens an applied trim`() {
        val f = Fixture()
        repeat(5) { f.cycle() }
        f.output = 0f
        f.cycle()
        assertEquals(listOf(400L, 500L), f.applied)
    }

    @Test fun `unknown legacy tail pacing cannot authorize a trim`() {
        val f = Fixture()
        f.sourcePacingValid = false
        repeat(60) { f.cycle() }
        assertTrue(f.applied.isEmpty())
    }

    @Test fun `policy transition reopens clocks before using new qualification`() {
        val f = Fixture()
        repeat(5) { f.cycle() }
        f.tuner.requestOpenClocks()
        f.cycle()
        assertEquals(listOf(400L, 500L), f.applied)
        repeat(3) { f.cycle() }
        assertEquals(listOf(400L, 500L), f.applied)
    }
}
