package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgAdaptiveTargetsTest {
    @Test
    fun adaptiveOutputTargetDoesNotBecomeSourceLimiter() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 120,
            displayRefreshRateFps = 120,
        )

        assertEquals(30, targets.sourceLimitFps)
        assertEquals(120, targets.outputTargetFps)
    }

    @Test
    fun adaptiveWithoutSourceLimiterLeavesGameUncapped() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = false,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 90,
            displayRefreshRateFps = 120,
        )

        assertEquals(0, targets.sourceLimitFps)
        assertEquals(90, targets.outputTargetFps)
    }

    @Test
    fun missingAdaptiveTargetDefaultsToDisplayRefresh() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 40,
            requestedOutputTargetFps = 0,
            displayRefreshRateFps = 120,
        )

        assertEquals(40, targets.sourceLimitFps)
        assertEquals(120, targets.outputTargetFps)
    }

    @Test
    fun adaptiveTargetIsClampedToDisplayRefreshWithoutChangingSourceCap() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = true,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 30,
            requestedOutputTargetFps = 165,
            displayRefreshRateFps = 120,
        )

        assertEquals(30, targets.sourceLimitFps)
        assertEquals(120, targets.outputTargetFps)
    }

    @Test
    fun disablingAdaptiveOnlyClearsTheOutputObjective() {
        val targets = LsfgAdaptiveTargetPolicy.resolve(
            adaptiveEnabled = false,
            sourceLimiterEnabled = true,
            sourceLimiterTargetFps = 45,
            requestedOutputTargetFps = 120,
            displayRefreshRateFps = 120,
        )

        assertEquals(45, targets.sourceLimitFps)
        assertEquals(0, targets.outputTargetFps)
    }
}
