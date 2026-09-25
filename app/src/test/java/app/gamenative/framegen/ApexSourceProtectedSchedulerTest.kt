package app.gamenative.framegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexSourceProtectedSchedulerTest {
    private fun telemetry(
        sourceInFps: Float = 30f,
        sourceOutFps: Float = sourceInFps,
        generatedFps: Float = 0f,
        outputFps: Float = sourceOutFps + generatedFps,
        opportunityFps: Float = 120f,
    ): ApexPresentationTelemetry.Snapshot =
        ApexPresentationTelemetry.Snapshot(
            active = true,
            sourceInputFps = sourceInFps,
            sourceFps = sourceOutFps,
            generatedFps = generatedFps,
            repeatedFps = 0f,
            outputFps = outputFps,
            opportunityFps = opportunityFps,
            admittedGenerationBudget = 0,
            attempts = 10,
            sourceArrivals = 10,
            sourceDropped = 0,
            sourcePresented = 10,
            generatedPresented = generatedFps.toLong(),
            repeatedPresented = 0,
            outputPresented = 20,
            swapFailures = 0,
        )

    private fun seedDisplay(s: ApexSourceProtectedScheduler, start: Long, period: Long = 8_333_333L) {
        var t = start
        repeat(24) {
            s.recordDisplayOpportunity(t)
            t += period
        }
    }

    private fun learnBaseline(
        s: ApexSourceProtectedScheduler,
        start: Long,
        sourcePeriod: Long,
        samples: Int = 8,
    ): Long {
        var t = start
        s.recordSourceArrival(t)
        repeat(samples) {
            t += sourcePeriod
            s.recordSourceArrival(t)
            s.generationBudget(false, 0, 0, telemetry())
        }
        return t
    }

    private fun rampFixed(
        s: ApexSourceProtectedScheduler,
        start: Long,
        sourcePeriod: Long,
        ceiling: Int,
        samples: Int = 32,
        opportunityFps: Float = 120f,
    ): Pair<Long, Int> {
        var t = start
        var budget = 0
        repeat(samples) {
            t += sourcePeriod
            s.recordSourceArrival(t)
            budget = s.generationBudget(
                adaptive = false,
                fixedGeneratedCeiling = ceiling,
                targetFps = 120,
                presentation = telemetry(opportunityFps = opportunityFps),
            )
        }
        return t to budget
    }

    @Test
    fun stableBaselineWithSustainableSyntheticLoadAllowsRequestedGeneration() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 1_000_000_000L)
        var t = learnBaseline(s, 1_000_000_000L, 33_333_333L)
        val result = rampFixed(s, t, 33_333_333L, ceiling = 3)
        t = result.first
        assertEquals(3, result.second)
        assertFalse(s.diagnostics().sourceProtectionActive)
        assertTrue(t > 0L)
    }

    @Test
    fun generatedLoadThatDegradesSourceActivatesProtectionBeforeCadenceIsSacrificed() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 2_000_000_000L)
        var t = learnBaseline(s, 2_000_000_000L, 33_333_333L)
        val ramp = rampFixed(s, t, 33_333_333L, ceiling = 3)
        t = ramp.first
        assertTrue(ramp.second > 0)

        t += 50_000_000L
        s.recordSourceArrival(t)
        val budget = s.generationBudget(false, 3, 120, telemetry(sourceInFps = 20f))
        assertEquals(0, budget)
        assertTrue(s.diagnostics().sourceProtectionActive)
        assertEquals("source-degradation", s.diagnostics().backoffReason)
    }

    @Test
    fun fixedFourXIsACeilingAndPresentationCapacityMayClampIt() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 3_000_000_000L, 11_111_111L)
        val t = learnBaseline(s, 3_000_000_000L, 33_333_333L)
        val result = rampFixed(
            s,
            t,
            33_333_333L,
            ceiling = 3,
            samples = 40,
            opportunityFps = 90f,
        )
        assertTrue(result.second in 0..2)
        assertEquals(3, s.diagnostics().requestedSyntheticCount)
    }

    @Test
    fun adaptiveDemandCanRiseDuringSlowdownWhileAdmissionBacksOff() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 4_000_000_000L)
        var t = learnBaseline(s, 4_000_000_000L, 33_333_333L)
        repeat(28) {
            t += 33_333_333L
            s.recordSourceArrival(t)
            s.generationBudget(true, 3, 120, telemetry(generatedFps = 60f, outputFps = 90f))
        }
        val before = s.diagnostics().requestedSyntheticCount

        t += 50_000_000L
        s.recordSourceArrival(t)
        val admitted = s.generationBudget(
            true,
            3,
            120,
            telemetry(sourceInFps = 20f, sourceOutFps = 20f, generatedFps = 60f, outputFps = 80f),
        )
        val after = s.diagnostics()
        assertTrue(after.requestedSyntheticCount >= before)
        assertEquals(0, admitted)
        assertTrue(after.sourceProtectionActive)
    }

    @Test
    fun naturallySlowStableSourceRemainsEligibleForGeneration() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 5_000_000_000L, 8_333_333L)
        val t = learnBaseline(s, 5_000_000_000L, 50_000_000L, samples = 10)
        val result = rampFixed(s, t, 50_000_000L, ceiling = 1, samples = 20)
        assertEquals(1, result.second)
        assertFalse(s.diagnostics().sourceProtectionActive)
    }

    @Test
    fun protectedSourceOnlyIntervalsDoNotImmediatelyLearnDegradedGeneratedCadence() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 6_000_000_000L)
        var t = learnBaseline(s, 6_000_000_000L, 33_333_333L)
        val ramp = rampFixed(s, t, 33_333_333L, ceiling = 2)
        t = ramp.first
        val baselineBefore = s.diagnostics().cleanSourceBaselineIntervalMs

        t += 50_000_000L
        s.recordSourceArrival(t)
        s.generationBudget(false, 2, 120, telemetry(sourceInFps = 20f))
        repeat(3) {
            t += 50_000_000L
            s.recordSourceArrival(t)
            s.generationBudget(false, 2, 120, telemetry(sourceInFps = 20f))
        }
        val after = s.diagnostics()
        assertTrue(after.sourceProtectionActive)
        assertTrue(after.cleanSourceBaselineIntervalMs < 40f)
        assertEquals(baselineBefore, after.cleanSourceBaselineIntervalMs, 2.0f)
    }

    @Test
    fun recoveryUsesHysteresisAndProbesOneSyntheticLevelAtATime() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 7_000_000_000L)
        var t = learnBaseline(s, 7_000_000_000L, 33_333_333L)
        t = rampFixed(s, t, 33_333_333L, ceiling = 3).first
        t += 50_000_000L
        s.recordSourceArrival(t)
        assertEquals(0, s.generationBudget(false, 3, 120, telemetry(sourceInFps = 20f)))

        var firstNonZero = -1
        repeat(16) {
            t += 33_333_333L
            s.recordSourceArrival(t)
            val budget = s.generationBudget(false, 3, 120, telemetry())
            if (firstNonZero < 0 && budget > 0) firstNonZero = budget
        }
        assertEquals(1, firstNonZero)
        assertTrue(s.diagnostics().generationProbeLevel in 0..1)
    }

    @Test
    fun newlyArrivedRealSourceMayPreemptSyntheticPrefixWhenDeadlineWouldBeEndangered() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 8_000_000_000L)
        var t = learnBaseline(s, 8_000_000_000L, 33_333_333L)
        t = rampFixed(s, t, 33_333_333L, ceiling = 3).first
        s.recordSourceArrival(t + 33_333_333L)
        s.generationBudget(false, 3, 120, telemetry())

        val queuedArrival = t + 39_000_000L
        assertTrue(
            s.shouldPreemptForQueuedSource(
                nowNanos = t + 47_000_000L,
                queuedSourceArrivalNanos = queuedArrival,
            ),
        )
    }

    @Test
    fun nativeCostCanReduceAdmissionWithoutCreatingCatchUpDebt() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 9_000_000_000L)
        var t = learnBaseline(s, 9_000_000_000L, 33_333_333L)
        s.recordNativeCost(
            preparationCostNanos = 5_000_000L,
            pipelineCostNanos = 24_000_000L,
            generatedFrames = 1,
        )
        t += 33_333_333L
        s.recordSourceArrival(t)
        val budget = s.generationBudget(false, 3, 120, telemetry())
        assertTrue(budget <= 1)

        repeat(4) {
            t += 50_000_000L
            s.recordSourceArrival(t)
            assertEquals(0, s.generationBudget(false, 3, 120, telemetry(sourceInFps = 20f)))
        }
        assertEquals(0f, s.diagnostics().fractionalPhase, 0.0001f)
    }

    @Test
    fun resetClearsProtectionAndFractionalState() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 10_000_000_000L)
        var t = learnBaseline(s, 10_000_000_000L, 33_333_333L)
        repeat(10) {
            t += 33_333_333L
            s.recordSourceArrival(t)
            s.generationBudget(true, 3, 45, telemetry())
        }
        s.reset()
        val d = s.diagnostics()
        assertFalse(d.sourceProtectionActive)
        assertEquals(0f, d.fractionalPhase, 0f)
        assertEquals(0f, d.cleanSourceBaselineIntervalMs, 0f)
    }
}
