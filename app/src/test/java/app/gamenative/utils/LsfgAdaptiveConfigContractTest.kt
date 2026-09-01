package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgAdaptiveConfigContractTest {
    @Test
    fun sourceCapAndOutputTargetRemainIndependentAcrossCommonRatios() {
        val cases = listOf(
            Triple(30, 60, 60),
            Triple(30, 90, 90),
            Triple(30, 120, 120),
            Triple(40, 120, 120),
            Triple(60, 120, 120),
        )

        cases.forEach { (sourceCap, requestedOutput, expectedOutput) ->
            val resolved = LsfgAdaptiveTargetPolicy.resolve(
                adaptiveEnabled = true,
                sourceLimiterEnabled = true,
                sourceLimiterTargetFps = sourceCap,
                requestedOutputTargetFps = requestedOutput,
                displayRefreshRateFps = 120,
            )
            assertEquals(sourceCap, resolved.sourceLimitFps)
            assertEquals(expectedOutput, resolved.outputTargetFps)
        }
    }

    @Test
    fun adaptiveOutputMayRunWithoutAnySourceLimiter() {
        val resolved = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = false,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 120,
            displayRefreshRateFps = 120,
        )

        assertEquals(0, resolved.sourceLimitFps)
        assertEquals(120, resolved.outputTargetFps)
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

        assertEquals(30, resolved.sourceLimitFps)
        assertEquals(120, resolved.outputTargetFps)
        assertTrue(resolved.outputTargetFps > resolved.sourceLimitFps)
    }
}
