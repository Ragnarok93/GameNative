package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgAdaptiveTargetsTest {
    @Test
    fun userTargetThermalCeilingAndSourceTargetHaveIndependentOwnership() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 120,
            displayRefreshRateFps = 120,
            outputFpsCeiling = 90,
        )

        assertEquals(30, targets.sourceFpsTarget)
        assertEquals(120, targets.outputFpsTarget)
        assertEquals(90, targets.outputFpsCeiling)
        assertEquals(90, targets.resolvedOutputFpsTarget)
    }

    @Test
    fun adaptiveWithoutSourceLimiterGetsEphemeralSourceTargetWithoutChangingPreference() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = false,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 90,
            displayRefreshRateFps = 120,
        )

        assertEquals(120, targets.sourceFpsTarget)
        assertEquals(90, targets.resolvedOutputFpsTarget)
    }

    @Test
    fun missingOutputTargetDefaultsToDisplayRefresh() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 40,
            requestedOutputTargetFps = 0,
            displayRefreshRateFps = 120,
        )
        assertEquals(40, targets.sourceFpsTarget)
        assertEquals(120, targets.outputFpsTarget)
        assertEquals(120, targets.resolvedOutputFpsTarget)
    }

    @Test
    fun disablingAdaptiveDoesNotDestroyTheIndependentOutputPreference() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = false,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 45,
            requestedOutputTargetFps = 120,
            displayRefreshRateFps = 120,
        )
        assertEquals(45, targets.sourceFpsTarget)
        assertEquals(120, targets.outputFpsTarget)
        assertEquals(120, targets.resolvedOutputFpsTarget)
    }
}
