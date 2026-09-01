package app.gamenative.utils

/**
 * Resolves the two independent frame-rate goals used by Adaptive Frame Generation.
 *
 * [sourceLimitFps] belongs to the game's real-frame limiter. [outputTargetFps]
 * belongs to LSFG's adaptive controller. Keeping them separate prevents a change
 * to the desired generated-output rate from silently throttling the game itself.
 */
data class LsfgAdaptiveTargets(
    val sourceLimitFps: Int,
    val outputTargetFps: Int,
)

object LsfgAdaptiveTargetPolicy {
    private const val MIN_TARGET_FPS = 5

    fun resolve(
        adaptiveEnabled: Boolean,
        sourceLimiterEnabled: Boolean,
        sourceLimiterTargetFps: Int,
        requestedOutputTargetFps: Int,
        displayRefreshRateFps: Int,
    ): LsfgAdaptiveTargets {
        val displayCeiling = displayRefreshRateFps.coerceAtLeast(MIN_TARGET_FPS)
        val sourceLimit = if (sourceLimiterEnabled) {
            sourceLimiterTargetFps.coerceIn(MIN_TARGET_FPS, displayCeiling)
        } else {
            0
        }

        val outputTarget = if (adaptiveEnabled) {
            val requested = requestedOutputTargetFps.takeIf { it > 0 } ?: displayCeiling
            requested.coerceIn(MIN_TARGET_FPS, displayCeiling)
        } else {
            0
        }

        return LsfgAdaptiveTargets(
            sourceLimitFps = sourceLimit,
            outputTargetFps = outputTarget,
        )
    }
}
