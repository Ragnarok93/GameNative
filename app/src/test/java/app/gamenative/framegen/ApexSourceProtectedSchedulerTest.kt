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
    fun adaptivePersistentOutputDeficitCanRaiseSafeBudgetWithoutSacrificingSource() {
        val scheduler = ApexSourceProtectedScheduler()
        var time = 5_000_000_000L
        repeat(12) {
            scheduler.recordDisplayOpportunity(time)
            time += 8_333_333L
        }
        scheduler.recordSourceArrival(5_000_000_000L)
        scheduler.recordSourceArrival(5_033_333_333L)

        val underTarget = populatedTelemetry(
            sourceInFps = 30f,
            sourceOutFps = 30f,
            generatedFps = 15f,
            outputFps = 45f,
            opportunityFps = 120f,
        )
        var budget = 0
        repeat(3) {
            budget = scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 60,
                presentation = underTarget,
            )
        }
        assertEquals(2, budget)

        val recovered = populatedTelemetry(
            sourceInFps = 30f,
            sourceOutFps = 30f,
            generatedFps = 30f,
            outputFps = 60f,
            opportunityFps = 120f,
        )
        repeat(2) {
            budget = scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 60,
                presentation = recovered,
            )
        }
        assertEquals(1, budget)
    }


    @Test
    fun adaptiveSourceCollapseTripsProtectionInsteadOfIncreasingBudget() {
        val scheduler = ApexSourceProtectedScheduler()
        var t = 6_000_000_000L
        repeat(12) {
            scheduler.recordDisplayOpportunity(t)
            t += 8_333_333L
        }
        scheduler.recordSourceArrival(6_000_000_000L)
        scheduler.recordSourceArrival(6_033_333_333L)

        val healthyBudget = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 90,
            presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
        )
        assertEquals(2, healthyBudget)

        scheduler.recordSourceArrival(6_133_333_333L)
        val collapsedBudget = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 90,
            presentation = populatedTelemetry(10f, 10f, 10f, 20f, 120f),
        )

        assertEquals(
            "falling source cadence must shed synthetic work instead of requesting a larger ratio",
            0,
            collapsedBudget,
        )
    }

    @Test
    fun adaptiveRecoversGenerationGraduallyAfterSourceProtectionTrips() {
        val scheduler = ApexSourceProtectedScheduler()
        var t = 7_000_000_000L
        repeat(12) {
            scheduler.recordDisplayOpportunity(t)
            t += 8_333_333L
        }
        scheduler.recordSourceArrival(7_000_000_000L)
        scheduler.recordSourceArrival(7_033_333_333L)

        assertEquals(
            2,
            scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 90,
                presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
            ),
        )

        scheduler.recordSourceArrival(7_133_333_333L)
        assertEquals(
            0,
            scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 90,
                presentation = populatedTelemetry(10f, 10f, 10f, 20f, 120f),
            ),
        )

        scheduler.recordSourceArrival(7_166_666_666L)
        val firstRecovered = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 90,
            presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
        )
        scheduler.recordSourceArrival(7_199_999_999L)
        val secondRecovered = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 90,
            presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
        )
        scheduler.recordSourceArrival(7_233_333_332L)
        val thirdRecovered = scheduler.generationBudget(
            adaptive = true,
            fixedGeneratedCeiling = 3,
            targetFps = 90,
            presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
        )

        assertEquals(1, firstRecovered)
        assertEquals(1, secondRecovered)
        assertEquals(2, thirdRecovered)
    }


    @Test
    fun adaptiveProtectionReanchorsToAStableLowerSourceOnlyWhileSyntheticWorkIsOff() {
        val scheduler = ApexSourceProtectedScheduler()
        var t = 8_000_000_000L
        repeat(12) {
            scheduler.recordDisplayOpportunity(t)
            t += 8_333_333L
        }
        scheduler.recordSourceArrival(8_000_000_000L)
        scheduler.recordSourceArrival(8_033_333_333L)

        assertEquals(
            2,
            scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 90,
                presentation = populatedTelemetry(30f, 30f, 30f, 60f, 120f),
            ),
        )

        var sourceTime = 8_071_794_871L
        scheduler.recordSourceArrival(sourceTime)
        assertEquals(
            0,
            scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 90,
                presentation = populatedTelemetry(26f, 26f, 0f, 26f, 120f),
            ),
        )

        var resumedBudget = 0
        repeat(24) {
            sourceTime += 38_461_538L
            scheduler.recordSourceArrival(sourceTime)
            resumedBudget = scheduler.generationBudget(
                adaptive = true,
                fixedGeneratedCeiling = 3,
                targetFps = 90,
                presentation = populatedTelemetry(26f, 26f, 0f, 26f, 120f),
            )
        }

        assertEquals(
            "a sustained lower source cadence must fully re-anchor before Adaptive restores the requested ratio",
            3,
            resumedBudget,
        )
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
