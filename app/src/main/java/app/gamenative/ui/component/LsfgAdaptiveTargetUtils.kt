package app.gamenative.ui.component

internal const val LSFG_ADAPTIVE_TARGET_MIN_FPS = 30
internal const val LSFG_ADAPTIVE_TARGET_MAX_FPS = 120
internal const val LSFG_ADAPTIVE_TARGET_STEP_FPS = 5

internal fun adaptiveTargetFpsSteps(): List<Int> =
    (LSFG_ADAPTIVE_TARGET_MIN_FPS..LSFG_ADAPTIVE_TARGET_MAX_FPS step LSFG_ADAPTIVE_TARGET_STEP_FPS).toList()

internal fun sanitizeAdaptiveTargetFps(value: Int): Int {
    val clamped = value.coerceIn(LSFG_ADAPTIVE_TARGET_MIN_FPS, LSFG_ADAPTIVE_TARGET_MAX_FPS)
    val offset = clamped - LSFG_ADAPTIVE_TARGET_MIN_FPS
    return LSFG_ADAPTIVE_TARGET_MIN_FPS + (offset / LSFG_ADAPTIVE_TARGET_STEP_FPS) * LSFG_ADAPTIVE_TARGET_STEP_FPS
}

internal fun nextAdaptiveTargetFps(currentValue: Int): Int =
    (sanitizeAdaptiveTargetFps(currentValue) + LSFG_ADAPTIVE_TARGET_STEP_FPS)
        .coerceAtMost(LSFG_ADAPTIVE_TARGET_MAX_FPS)

internal fun previousAdaptiveTargetFps(currentValue: Int): Int {
    val sanitized = sanitizeAdaptiveTargetFps(currentValue)
    return (sanitized - LSFG_ADAPTIVE_TARGET_STEP_FPS)
        .coerceAtLeast(LSFG_ADAPTIVE_TARGET_MIN_FPS)
}

internal fun adaptiveTargetFpsProgress(currentValue: Int): Float {
    val sanitized = sanitizeAdaptiveTargetFps(currentValue)
    return (sanitized - LSFG_ADAPTIVE_TARGET_MIN_FPS).toFloat() /
        (LSFG_ADAPTIVE_TARGET_MAX_FPS - LSFG_ADAPTIVE_TARGET_MIN_FPS).toFloat()
}
