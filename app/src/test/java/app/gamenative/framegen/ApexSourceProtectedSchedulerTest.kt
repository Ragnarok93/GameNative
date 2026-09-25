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
    fun adaptiveThirtyFromTwentyFourPointOneCanUseFractionalDisplayHeadroom() {
        val s = ApexSourceProtectedScheduler()
        val sourcePeriod = 41_530_000L
        seedDisplay(s, 11_000_000_000L, 39_062_500L)
        var t = learnBaseline(s, 11_000_000_000L, sourcePeriod, samples = 10)

        var generatedIntervals = 0
        var zeroIntervals = 0
        repeat(48) {
            t += sourcePeriod
            s.recordSourceArrival(t)
            val budget = s.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 30,
                presentation = telemetry(
                    sourceInFps = 24.1f,
                    sourceOutFps = 24.1f,
                    generatedFps = 0f,
                    outputFps = 24.1f,
                    opportunityFps = 25.6f,
                ),
            )
            if (budget > 0) generatedIntervals++ else zeroIntervals++
        }

        assertTrue(
            "fractional headroom above the source rate must allow some synthetic intervals",
            generatedIntervals > 0,
        )
        assertTrue(
            "30 FPS from a ~24 FPS source is fractional; it must not generate every interval",
            zeroIntervals > 0,
        )
        assertTrue(
            "presentation capacity is a ceiling, not a per-source whole-slot requirement",
            s.diagnostics().presentationOpportunityBudget >= 1,
        )
    }

    @Test
    fun oneMildGeneratedIntervalDoesNotCreateALongProtectionLockout() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 12_000_000_000L, 16_666_667L)
        var t = learnBaseline(s, 12_000_000_000L, 41_530_000L, samples = 10)

        // Establish a generation probe, then reproduce the observed single
        // ~49.8 ms interval against a ~41.5 ms clean baseline.
        repeat(8) {
            t += 41_530_000L
            s.recordSourceArrival(t)
            s.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 30,
                presentation = telemetry(opportunityFps = 60f),
            )
        }
        t += 49_830_000L
        s.recordSourceArrival(t)
        s.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 30,
            presentation = telemetry(sourceInFps = 20f, opportunityFps = 60f),
        )

        var firstRecoveredBudget = 0
        repeat(3) {
            t += 41_530_000L
            s.recordSourceArrival(t)
            val budget = s.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 30,
                presentation = telemetry(opportunityFps = 60f),
            )
            if (firstRecoveredBudget == 0) firstRecoveredBudget = budget
        }

        assertTrue(
            "a single mild jitter interval must source-protect briefly, not suppress generation for a long hold",
            firstRecoveredBudget > 0,
        )
    }



    @Test
    fun fixedModeDoesNotDisableGenerationWhenProducerCadenceSlows() {
        val s = ApexSourceProtectedScheduler()
        seedDisplay(s, 13_000_000_000L)
        var t = learnBaseline(s, 13_000_000_000L, 33_333_333L)
        t = rampFixed(s, t, 33_333_333L, ceiling = 1).first

        t += 50_000_000L
        s.recordSourceArrival(t)
        val budget = s.generationBudget(
            adaptive = false,
            fixedGeneratedCeiling = 1,
            targetFps = 60,
            presentation = telemetry(
                sourceInFps = 20f,
                sourceOutFps = 20f,
                opportunityFps = 120f,
            ),
        )

        assertEquals(
            "fixed 2x must keep requesting its one synthetic slot; source slowdown is not a backoff signal",
            1,
            budget,
        )
        assertFalse(
            "source cadence must not activate a source-only protection mode",
            s.diagnostics().sourceProtectionActive,
        )
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
