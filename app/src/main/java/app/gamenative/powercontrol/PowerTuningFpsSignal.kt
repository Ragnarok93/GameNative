package app.gamenative.powercontrol

import app.gamenative.powercontrol.autotuning.AdaptiveFpsCap
import app.gamenative.powercontrol.autotuning.FpsCapChange

internal fun AdaptiveFpsCap.onPowerTuningCycle(
    tuningFps: Float?,
    clocksOpen: Boolean,
    clockHeadroom: Boolean,
): FpsCapChange? {
    if (tuningFps == null) {
        interrupt()
        return null
    }
    return onCycle(tuningFps, clocksOpen, clockHeadroom)
}

/** Installed renderer policy. Native generated frames never enter the guest timestamp ring. */
data class FrameGenerationPacing(
    val active: Boolean = false,
    val sourceSampleStride: Int = 1,
    val multiplier: Int = 1,
    val adaptiveTargetFps: Int = 0,
    val displayRefreshFps: Int = 0,
)

internal fun lsfgSourceSampleStride(active: Boolean, nativeBackend: Boolean, multiplier: Int): Int =
    if (active && !nativeBackend) multiplier.coerceIn(2, 4) else 1

data class PowerTuningObservation(val sourceEquivalentFps: Float, val sourceTargetFps: Int)

/** Compare displayed delivery with its attainable contract, expressed in source-cap units. */
internal fun powerTuningObservation(
    sourceFps: Float,
    sourceCap: Int,
    pacing: FrameGenerationPacing,
    confirmedOutputFps: Float?,
): PowerTuningObservation? {
    if (sourceCap <= 0) return null
    if (!pacing.active) return sourceFps.takeIf { it.isFinite() && it >= 0f }
        ?.let { PowerTuningObservation(it, sourceCap) }
    val output = confirmedOutputFps?.takeIf { it.isFinite() && it >= 0f } ?: return null
    var outputTarget = sourceCap.toDouble() * pacing.multiplier.coerceIn(2, 4)
    if (pacing.adaptiveTargetFps > 0) outputTarget = minOf(outputTarget, pacing.adaptiveTargetFps.toDouble())
    if (pacing.displayRefreshFps > 0) outputTarget = minOf(outputTarget, pacing.displayRefreshFps.toDouble())
    return PowerTuningObservation((output * sourceCap / outputTarget).toFloat(), sourceCap)
}
