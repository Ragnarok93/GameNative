package app.gamenative.utils

import kotlin.math.min

/**
 * Explicit frame-rate ownership model for the game/render pipeline.
 *
 * [sourceFpsTarget] is the game/base-render target. [outputFpsTarget]
 * is the user's requested displayed-frame target. [outputFpsCeiling]
 * is an upstream thermal/power constraint. [resolvedOutputFpsTarget]
 * is the only value that may be sent to native fps_limit.
 */
data class LsfgAdaptiveTargets(
    val sourceFpsTarget: Int,
    val outputFpsCeiling: Int,
    val outputFpsTarget: Int,
    val resolvedOutputFpsTarget: Int,
) {
    /** Compatibility alias for older callers/tests during migration. */
    val sourceLimitFps: Int get() = sourceFpsTarget
}

object LsfgAdaptiveTargetPolicy {
    private const val MIN_TARGET_FPS = 5

    fun resolve(
        adaptiveEnabled: Boolean,
        sourceLimiterEnabled: Boolean,
        sourceLimiterTargetFps: Int,
        requestedOutputTargetFps: Int,
        displayRefreshRateFps: Int,
        outputFpsCeiling: Int = 0,
    ): LsfgAdaptiveTargets {
        val displayCeiling = displayRefreshRateFps.coerceAtLeast(MIN_TARGET_FPS)

        // The user's limiter preference remains independent from Adaptive.
        // If Adaptive is enabled without an explicit source limiter, use an
        // ephemeral display-rate target so the native source target is always
        // well-defined without mutating fpsLimiterEnabled or its saved value.
        val sourceTarget = when {
            sourceLimiterEnabled -> sourceLimiterTargetFps.coerceIn(MIN_TARGET_FPS, displayCeiling)
            adaptiveEnabled -> displayCeiling
            else -> 0
        }

        val userTarget = (requestedOutputTargetFps.takeIf { it > 0 } ?: displayCeiling)
            .coerceIn(MIN_TARGET_FPS, displayCeiling)
        val thermalCeiling = (outputFpsCeiling.takeIf { it > 0 } ?: displayCeiling)
            .coerceIn(MIN_TARGET_FPS, displayCeiling)
        val resolved = min(userTarget, min(thermalCeiling, displayCeiling))

        return LsfgAdaptiveTargets(
            sourceFpsTarget = sourceTarget,
            outputFpsCeiling = thermalCeiling,
            outputFpsTarget = userTarget,
            resolvedOutputFpsTarget = resolved,
        )
    }
}
