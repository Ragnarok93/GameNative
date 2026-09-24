package app.gamenative.framegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexSourceProtectedSchedulerTest {
    private fun populatedTelemetry(
        sourceInFps: Float,
        sourceOutFps: Float,
        generatedFps: Float,
        outputFps: Float,
        opportunityFps: Float,
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
            generatedPresented = 10,
            repeatedPresented = 0,
            outputPresented = 20,
            swapFailures = 0,
        )

    private fun seedDisplayCadence(
        scheduler: ApexSourceProtectedScheduler,
        startNanos: Long,
        periodNanos: Long,
        samples: Int = 16,
    ) {
        var time = startNanos
        repeat(samples) {
            scheduler.recordDisplayOpportunity(time)
            time += periodNanos
        }
    }

    @Test
    fun fixedFourXIsAuthoritativeInsteadOfBeingClampedByCallbackCapacity() {
        val scheduler = ApexSourceProtectedScheduler()
        seedDisplayCadence(
            scheduler = scheduler,
            startNanos = 1_000_000_000L,
            periodNanos = 11_111_111L,
        )
        scheduler.recordSourceArrival(1_000_000_000L)
        scheduler.recordSourceArrival(1_033_333_333L)

        val budget = scheduler.generationBudget(
            adaptive = false,
            fixedGeneratedCeiling = 3,
            targetFps = 120,
            presentation = populatedTelemetry(
                sourceInFps = 30f,
                sourceOutFps = 30f,
                generatedFps = 60f,
                outputFps = 90f,
                opportunityFps = 90f,
            ),
        )

        assertEquals(
            "fixed 4x must continue requesting three synthetic frames; source deadlines decide whether a slot is still usable",
            3,
            budget,
        )
    }

    @Test
    fun adaptiveFortyFiveDistributesFractionalSyntheticOpportunities() {
        val scheduler = ApexSourceProtectedScheduler()
        seedDisplayCadence(
            scheduler = scheduler,
            startNanos = 2_000_000_000L,
            periodNanos = 8_333_333L,
        )

        var sourceTime = 2_000_000_000L
        scheduler.recordSourceArrival(sourceTime)
        var generated = 0
        var maxBudget = 0
        repeat(20) {
            sourceTime += 33_333_333L
            scheduler.recordSourceArrival(sourceTime)
            val budget = scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 45,
                presentation = populatedTelemetry(
                    sourceInFps = 30f,
                    sourceOutFps = 30f,
                    generatedFps = 15f,
                    outputFps = 45f,
                    opportunityFps = 120f,
                ),
            )
            generated += budget
            maxBudget = maxOf(maxBudget, budget)
        }

        assertTrue(
            "30 -> 45 Adaptive should create about one synthetic opportunity every two source intervals",
            generated in 9..11,
        )
        assertEquals(
            "fractional 1.5x cadence must be distributed over time rather than rounded to fixed 2x",
            1,
            maxBudget,
        )
    }

    @Test
    fun adaptiveOneTwentyPursuesTargetEvenWhenCallbackTelemetryReportsNinety() {
        val scheduler = ApexSourceProtectedScheduler()
        seedDisplayCadence(
            scheduler = scheduler,
            startNanos = 3_000_000_000L,
            periodNanos = 11_111_111L,
        )

        var sourceTime = 3_000_000_000L
        scheduler.recordSourceArrival(sourceTime)
        var budget = 0
        repeat(48) {
            sourceTime += 33_333_333L
            scheduler.recordSourceArrival(sourceTime)
            budget = scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 120,
                presentation = populatedTelemetry(
                    sourceInFps = 30f,
                    sourceOutFps = 30f,
                    generatedFps = 60f,
                    outputFps = 90f,
                    opportunityFps = 90f,
                ),
            )
        }

        assertEquals(
            "measured presenter callbacks are capacity evidence, not permission to rewrite a 120 FPS target to ~90 FPS",
            3,
            budget,
        )
    }

    @Test
    fun adaptiveSourceSlowdownCreatesMoreDemandInsteadOfSuppressingGeneration() {
        val scheduler = ApexSourceProtectedScheduler()
        seedDisplayCadence(
            scheduler = scheduler,
            startNanos = 4_000_000_000L,
            periodNanos = 8_333_333L,
        )

        var sourceTime = 4_000_000_000L
        scheduler.recordSourceArrival(sourceTime)
        repeat(48) {
            sourceTime += 33_333_333L
            scheduler.recordSourceArrival(sourceTime)
            scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 120,
                presentation = populatedTelemetry(30f, 30f, 60f, 90f, 120f),
            )
        }

        sourceTime += 50_000_000L
        scheduler.recordSourceArrival(sourceTime)
        val slowedBudget = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 120,
            presentation = populatedTelemetry(
                sourceInFps = 20f,
                sourceOutFps = 20f,
                generatedFps = 60f,
                outputFps = 80f,
                opportunityFps = 120f,
            ),
        )

        assertEquals(
            "a slower source raises target-relative interpolation demand; it must not activate a source-FPS backoff governor",
            3,
            slowedBudget,
        )
    }

    @Test
    fun sourceDeadlineStillReservesTheRealFrame() {
        val scheduler = ApexSourceProtectedScheduler()
        seedDisplayCadence(
            scheduler = scheduler,
            startNanos = 5_000_000_000L,
            periodNanos = 8_333_333L,
        )
        scheduler.recordSourceArrival(5_000_000_000L)
        scheduler.recordSourceArrival(5_033_333_333L)

        scheduler.generationBudget(
            adaptive = false,
            fixedGeneratedCeiling = 3,
            targetFps = 120,
            presentation = populatedTelemetry(30f, 30f, 90f, 120f, 120f),
        )

        assertTrue(
            "generated work must still yield before the next protected source deadline",
            scheduler.shouldPresentSourceNow(5_058_000_000L),
        )
    }

    @Test
    fun resetClearsFractionalAdaptivePhase() {
        val scheduler = ApexSourceProtectedScheduler()
        seedDisplayCadence(
            scheduler = scheduler,
            startNanos = 6_000_000_000L,
            periodNanos = 8_333_333L,
        )

        scheduler.recordSourceArrival(6_000_000_000L)
        scheduler.recordSourceArrival(6_033_333_333L)
        val first = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 45,
            presentation = populatedTelemetry(30f, 30f, 0f, 30f, 120f),
        )
        scheduler.reset()
        seedDisplayCadence(
            scheduler = scheduler,
            startNanos = 7_000_000_000L,
            periodNanos = 8_333_333L,
        )
        scheduler.recordSourceArrival(7_000_000_000L)
        scheduler.recordSourceArrival(7_033_333_333L)
        val afterReset = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 45,
            presentation = populatedTelemetry(30f, 30f, 0f, 30f, 120f),
        )

        assertEquals(first, afterReset)
    }
}
