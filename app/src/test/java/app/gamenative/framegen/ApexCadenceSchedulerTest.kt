package app.gamenative.framegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexCadenceSchedulerTest {
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

    private fun seedDisplay(
        scheduler: ApexCadenceScheduler,
        start: Long,
        period: Long = 8_333_333L,
    ) {
        var t = start
        repeat(24) {
            scheduler.recordDisplayOpportunity(t)
            t += period
        }
    }

    private fun seedSource(
        scheduler: ApexCadenceScheduler,
        start: Long,
        sourcePeriod: Long,
        samples: Int = 10,
    ): Long {
        var t = start
        scheduler.recordSourceFrame(t)
        repeat(samples) {
            t += sourcePeriod
            scheduler.recordSourceFrame(t)
        }
        return t
    }

    @Test
    fun fixedModeDoesNotDisableGenerationWhenProducerCadenceSlows() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 1_000_000_000L)
        var t = seedSource(scheduler, 1_000_000_000L, 33_333_333L)

        assertEquals(
            1,
            scheduler.generationBudget(
                adaptive = false,
                fixedGeneratedCeiling = 1,
                targetFps = 60,
            ),
        )

        t += 50_000_000L
        scheduler.recordSourceFrame(t)
        assertEquals(
            "fixed 2x must keep its synthetic slot when the producer slows",
            1,
            scheduler.generationBudget(
                adaptive = false,
                fixedGeneratedCeiling = 1,
                targetFps = 60,
            ),
        )
    }

    @Test
    fun adaptiveSlowdownRaisesDemandInsteadOfEnteringSourceOnlyMode() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 2_000_000_000L)
        var t = seedSource(scheduler, 2_000_000_000L, 33_333_333L)
        scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 60,
        )
        val before = scheduler.diagnostics().wantedGeneratedFrames

        t += 50_000_000L
        scheduler.recordSourceFrame(t)
        val admitted = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 60,
        )

        assertTrue(admitted > 0)
        assertTrue(scheduler.diagnostics().wantedGeneratedFrames >= before)
    }

    @Test
    fun fixedFourXRespectsRealPresentationCapacity() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 3_000_000_000L, period = 11_111_111L)
        seedSource(scheduler, 3_000_000_000L, 33_333_333L)

        val budget = scheduler.generationBudget(
            adaptive = false,
            fixedGeneratedCeiling = 3,
            targetFps = 120,
        )

        assertEquals(2, budget)
        assertEquals(3, scheduler.diagnostics().requestedSyntheticCount)
    }

    @Test
    fun adaptiveFractionalHeadroomIsDistributedWithoutCatchUpDebt() {
        val scheduler = ApexCadenceScheduler()
        val sourcePeriod = 41_530_000L
        seedDisplay(scheduler, 4_000_000_000L, period = 39_062_500L)
        var t = seedSource(scheduler, 4_000_000_000L, sourcePeriod)

        var generatedIntervals = 0
        var zeroIntervals = 0
        repeat(48) {
            t += sourcePeriod
            scheduler.recordSourceFrame(t)
            val budget = scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 30,
            )
            if (budget > 0) generatedIntervals++ else zeroIntervals++
        }

        assertTrue(generatedIntervals > 0)
        assertTrue(zeroIntervals > 0)
        assertTrue(scheduler.diagnostics().presentationOpportunityBudget >= 1)
    }

    @Test
    fun queuedSourceCanPreemptSyntheticPrefixAtItsRealDeadline() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 5_000_000_000L)
        val t = seedSource(scheduler, 5_000_000_000L, 33_333_333L)
        scheduler.generationBudget(
            adaptive = false,
            fixedGeneratedCeiling = 3,
            targetFps = 120,
        )

        val queuedSourceTimestamp = t + 39_000_000L
        assertTrue(
            scheduler.shouldPreemptForQueuedSource(
                nowNanos = t + 47_000_000L,
                queuedSourceTimestampNanos = queuedSourceTimestamp,
            ),
        )
    }

    @Test
    fun nativeCostCanDropCurrentWorkWithoutCreatingCatchUpDebt() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 6_000_000_000L)
        var t = seedSource(scheduler, 6_000_000_000L, 33_333_333L)
        scheduler.recordNativeCost(
            preparationCostNanos = 5_000_000L,
            pipelineCostNanos = 24_000_000L,
            generatedFrames = 1,
        )

        repeat(3) {
            t += 33_333_333L
            scheduler.recordSourceFrame(t)
            assertEquals(
                0,
                scheduler.generationBudget(
                    adaptive = false,
                    fixedGeneratedCeiling = 1,
                    targetFps = 60,
                ),
            )
        }
        assertEquals(0f, scheduler.diagnostics().fractionalPhase, 0.0001f)
    }

    @Test
    fun resetClearsSourceAndFractionalCadenceState() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 7_000_000_000L)
        var t = seedSource(scheduler, 7_000_000_000L, 33_333_333L)
        repeat(4) {
            t += 33_333_333L
            scheduler.recordSourceFrame(t)
            scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 45,
            )
        }

        scheduler.reset()
        val diagnostics = scheduler.diagnostics()
        assertEquals(0f, diagnostics.sourceFps, 0f)
        assertEquals(0f, diagnostics.fractionalPhase, 0f)
        assertEquals(0, diagnostics.admittedSyntheticCount)
        assertFalse(diagnostics.measuredOpportunityFps > 0f)
    }

    @Test
    fun highRefreshThreeXFinishesSyntheticPrefixBeforeQueuedSourcePreempts() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 8_000_000_000L, period = 8_333_333L)
        val t = seedSource(scheduler, 8_000_000_000L, 33_333_333L)

        assertEquals(
            2,
            scheduler.generationBudget(
                adaptive = false,
                fixedGeneratedCeiling = 2,
                targetFps = 90,
            ),
        )

        // nativePresentSourceFrame immediately displays the first admitted
        // synthetic. One synthetic remains before the buffered real frame.
        scheduler.onGeneratedPresented()
        val queuedSourceTimestamp = t + 33_333_333L

        assertFalse(
            "a fresh queued source must not truncate a 3x prefix that can still finish before its source deadline",
            scheduler.shouldPreemptForQueuedSource(
                nowNanos = queuedSourceTimestamp + 8_333_333L,
                queuedSourceTimestampNanos = queuedSourceTimestamp,
            ),
        )
        assertEquals(1, scheduler.diagnostics().remainingSyntheticSlots)
    }

    @Test
    fun queuedSourcePreemptsWhenRemainingPrefixWouldActuallyMissItsDeadline() {
        val scheduler = ApexCadenceScheduler()
        seedDisplay(scheduler, 9_000_000_000L, period = 8_333_333L)
        val t = seedSource(scheduler, 9_000_000_000L, 33_333_333L)

        assertEquals(
            3,
            scheduler.generationBudget(
                adaptive = false,
                fixedGeneratedCeiling = 3,
                targetFps = 120,
            ),
        )
        scheduler.onGeneratedPresented()
        val queuedSourceTimestamp = t + 33_333_333L

        assertTrue(
            "real-source protection must still preempt when two remaining synthetics cannot finish inside the queued source latency window",
            scheduler.shouldPreemptForQueuedSource(
                nowNanos = queuedSourceTimestamp + 20_000_000L,
                queuedSourceTimestampNanos = queuedSourceTimestamp,
            ),
        )
    }

}
