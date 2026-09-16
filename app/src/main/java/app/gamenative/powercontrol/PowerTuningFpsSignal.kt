package app.gamenative.powercontrol

import app.gamenative.powercontrol.autotuning.AdaptiveFpsCap
import app.gamenative.powercontrol.autotuning.FpsCapChange

/** Chooses the frame-rate signal used by FPS-based power-control decisions. */
internal fun selectPowerTuningFps(
    sourceFps: Float,
    frameGenerationActive: Boolean,
    postLsfgOutputFps: Float?,
): Float? = if (frameGenerationActive) postLsfgOutputFps else sourceFps

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
