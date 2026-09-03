package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgAdaptiveConfigContractTest {
    @Test
    fun sourceTargetAndOutputTargetRemainIndependentAcrossCommonRatios() {
        val cases = listOf(
            Triple(30, 60, 60),
            Triple(30, 90, 90),
            Triple(30, 120, 120),
            Triple(40, 120, 120),
            Triple(60, 120, 120),
        )

        cases.forEach { (sourceTarget, requestedOutput, expectedOutput) ->
            val resolved = LsfgAdaptiveTargetPolicy.resolve(
                adaptiveEnabled = true,
                sourceLimiterEnabled = true,
                sourceLimiterTargetFps = sourceTarget,
                requestedOutputTargetFps = requestedOutput,
                displayRefreshRateFps = 120,
            )
            assertEquals(sourceTarget, resolved.sourceFpsTarget)
            assertEquals(expectedOutput, resolved.outputFpsTarget)
            assertEquals(expectedOutput, resolved.resolvedOutputFpsTarget)
        }
    }

    @Test
    fun adaptiveWithoutPersistedLimiterUsesEphemeralRefreshSourceTarget() {
        val resolved = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = false,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 90,
            displayRefreshRateFps = 120,
        )

        // Adaptive gets a session-only source target without mutating the user's
        // persisted source-limiter preference.
        assertEquals(120, resolved.sourceFpsTarget)
        assertEquals(90, resolved.outputFpsTarget)
        assertEquals(90, resolved.resolvedOutputFpsTarget)
    }

    @Test
    fun displayRefreshProvidesTheAutomaticOutputObjective() {
        val resolved = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 0,
            displayRefreshRateFps = 120,
        )

        assertEquals(30, resolved.sourceFpsTarget)
        assertEquals(120, resolved.outputFpsTarget)
        assertEquals(120, resolved.resolvedOutputFpsTarget)
        assertTrue(resolved.resolvedOutputFpsTarget > resolved.sourceFpsTarget)
    }

    @Test
    fun thermalCeilingConstrainsOutputWithoutChangingUserOrSourceTargets() {
        val resolved = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 120,
            displayRefreshRateFps = 120,
            outputFpsCeiling = 90,
        )

        assertEquals(30, resolved.sourceFpsTarget)
        assertEquals(120, resolved.outputFpsTarget)
        assertEquals(90, resolved.outputFpsCeiling)
        assertEquals(90, resolved.resolvedOutputFpsTarget)
    }
}
