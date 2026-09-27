package app.gamenative.ui.component

import com.winlator.renderer.VulkanRenderer
import timber.log.Timber
import java.util.Locale

internal fun parsePositiveFpsLimit(value: String): Int? = value.toIntOrNull()?.takeIf { it > 0 }

internal fun parseBooleanExtra(value: String): Boolean? =
    when (value.trim().lowercase(Locale.US)) {
        "true" -> true
        "false" -> false
        else -> null
    }

internal fun fpsLimiterSteps(maxFps: Int): List<Int> {
    val sanitizedMax = maxFps.coerceAtLeast(5)
    val flooredMax = (sanitizedMax / 5) * 5
    return buildList {
        var value = 5
        while (value <= flooredMax) {
            add(value)
            value += 5
        }
        if (sanitizedMax != flooredMax) add(sanitizedMax)
    }
}

/**
 * Returns the index of the step that is the floor of [currentValue] — i.e. the highest
 * step that is still ≤ currentValue.  Falls back to 0 if currentValue is below the
 * first step, so navigation never goes in the wrong direction on a restored value that
 * isn't an exact multiple of 5.
 */
internal fun fpsLimiterCurrentIndex(steps: List<Int>, currentValue: Int): Int =
    steps.indexOfLast { it <= currentValue }.coerceAtLeast(0)

internal fun fpsLimiterProgress(currentValue: Int, maxFps: Int): Float {
    val steps = fpsLimiterSteps(maxFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return if (steps.lastIndex <= 0) 1f else currentIndex.toFloat() / steps.lastIndex.toFloat()
}

internal fun nextFpsLimiterValue(currentValue: Int, maxFps: Int): Int {
    val steps = fpsLimiterSteps(maxFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return steps[(currentIndex + 1).coerceAtMost(steps.lastIndex)]
}

internal fun previousFpsLimiterValue(currentValue: Int, maxFps: Int): Int {
    val steps = fpsLimiterSteps(maxFps)
    val currentIndex = fpsLimiterCurrentIndex(steps, currentValue)
    return steps[(currentIndex - 1).coerceAtLeast(0)]
}

/**
 * The FPS limiter always describes real/source game frames. LSFG fixed
 * multipliers may increase display output, but they never disable or multiply
 * the upstream source cap. A value of 0 remains the explicit unlimited state.
 */
internal fun effectiveSourceFpsCap(requestedSourceCap: Int): Int =
    requestedSourceCap.coerceAtLeast(0)

internal fun predictedLsfgOutputFps(sourceFpsCap: Int, lsfgMultiplier: Int): Int {
    if (sourceFpsCap <= 0) return 0
    return sourceFpsCap * lsfgMultiplier.coerceAtLeast(1)
}


/**
 * Returns the Android presentation-layer frame-rate vote.
 *
 * Source pacing stays independent. Only strict FIFO + active LSFG needs the
 * generated-output cadence advertised to SurfaceFlinger; Mailbox and LSFG-off
 * preserve the legacy source-rate vote.
 */
internal fun presentationFrameRateVote(
    sourceFpsCap: Int,
    lsfgActive: Boolean,
    strictFifo: Boolean,
    adaptive: Boolean,
    adaptiveTargetFps: Int,
    lsfgMultiplier: Int,
    maxRefreshRateHz: Int,
): Int {
    val source = effectiveSourceFpsCap(sourceFpsCap)
    if (!lsfgActive || !strictFifo) return source

    val displayCeiling = maxRefreshRateHz.coerceAtLeast(1)
    val requestedOutput = if (adaptive) {
        adaptiveTargetFps.takeIf { it > 0 } ?: displayCeiling
    } else if (source > 0) {
        (source.toLong() * lsfgMultiplier.coerceIn(2, 4).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    } else {
        displayCeiling
    }
    return requestedOutput.coerceIn(1, displayCeiling)
}

/**
 * Applies only the presentation-side LSFG hint. The renderer keeps the source
 * cap separately and restores it automatically when this hint is cleared.
 */
internal fun applyLsfgPresentationFrameRateHint(
    renderer: VulkanRenderer?,
    sourceFpsCap: Int,
    lsfgActive: Boolean,
    strictFifo: Boolean,
    adaptive: Boolean,
    adaptiveTargetFps: Int,
    lsfgMultiplier: Int,
    maxRefreshRateHz: Int,
): Int {
    val vote = presentationFrameRateVote(
        sourceFpsCap = sourceFpsCap,
        lsfgActive = lsfgActive,
        strictFifo = strictFifo,
        adaptive = adaptive,
        adaptiveTargetFps = adaptiveTargetFps,
        lsfgMultiplier = lsfgMultiplier,
        maxRefreshRateHz = maxRefreshRateHz,
    )
    renderer?.setLsfgPresentationFrameRateHint(
        if (lsfgActive && strictFifo) vote else 0,
    )
    Timber.i(
        "LSFG presentation pacing: source_cap=%d fifo=%b adaptive=%b adaptive_target=%d multiplier=%d presentation_vote=%d display_refresh=%d",
        sourceFpsCap,
        strictFifo,
        adaptive,
        adaptiveTargetFps,
        lsfgMultiplier,
        vote,
        maxRefreshRateHz,
    )
    return vote
}
