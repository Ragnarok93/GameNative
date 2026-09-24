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

    @Test
    fun fixedMultiplierIsCeilingAndSourceSlotIsReserved() {
        val scheduler = ApexSourceProtectedScheduler()
        var t = 1_000_000_000L
        repeat(8) {
            scheduler.recordDisplayOpportunity(t)
            t += 8_333_333L
        }
        scheduler.recordSourceArrival(1_000_000_000L)
        scheduler.recordSourceArrival(1_033_333_333L)

        val budget = scheduler.generationBudget(
            adaptive = false,
            fixedGeneratedCeiling = 3,
            targetFps = 120,
            presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
        )
        assertEquals(3, budget)
        assertTrue(scheduler.shouldPresentSourceNow(1_058_000_000L))
    }

    @Test
    fun displayCapacityBoundsRequestedFourXWithoutDroppingSource() {
        val scheduler = ApexSourceProtectedScheduler()
        var t = 2_000_000_000L
        repeat(8) {
            scheduler.recordDisplayOpportunity(t)
            t += 16_666_667L
        }
        scheduler.recordSourceArrival(2_000_000_000L)
        scheduler.recordSourceArrival(2_033_333_333L)

        val budget = scheduler.generationBudget(
            adaptive = false,
            fixedGeneratedCeiling = 3,
            targetFps = 120,
            presentation = populatedTelemetry(30f, 30f, 20f, 50f, 60f),
        )
        assertEquals(1, budget)
    }

    @Test
    fun sourceDeliveryRegressionSuppressesSyntheticAdmission() {
        val scheduler = ApexSourceProtectedScheduler()
        var t = 3_000_000_000L
        repeat(10) {
            scheduler.recordDisplayOpportunity(t)
            t += 8_333_333L
        }
        scheduler.recordSourceArrival(3_000_000_000L)
        scheduler.recordSourceArrival(3_033_333_333L)

        val budget = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 90,
            presentation = populatedTelemetry(30f, 20f, 45f, 65f, 120f).copy(
                sourcePresented = 10,
                sourceArrivals = 15,
            ),
        )
        assertEquals(0, budget)
    }

    @Test
    fun adaptiveUsesMeasuredCapacityAndOutputDeficit() {
        val scheduler = ApexSourceProtectedScheduler()
        var t = 4_000_000_000L
        repeat(10) {
            scheduler.recordDisplayOpportunity(t)
            t += 8_333_333L
        }
        scheduler.recordSourceArrival(4_000_000_000L)
        scheduler.recordSourceArrival(4_033_333_333L)

        val budget = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 90,
            presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
        )
        assertEquals(2, budget)
    }
}
