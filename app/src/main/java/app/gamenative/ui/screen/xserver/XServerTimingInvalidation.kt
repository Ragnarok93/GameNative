package app.gamenative.ui.screen.xserver

import app.gamenative.PluviaApp
import app.gamenative.powercontrol.metrics.PerformanceMetricsCollector
import com.winlator.xserver.ShmFramePacer
import com.winlator.xserver.extensions.PresentExtension
import timber.log.Timber

/**
 * Invalidates host-side frame timing at a guest suspend/resume boundary without
 * recreating or reconfiguring the LSFG runtime.
 *
 * XServerScreen's local suspend helpers call this before the later composable-local
 * diagnostic wrapper is in lexical scope. Keeping this execution seam package-level
 * makes those early calls valid while preserving the same pacing-reset behavior.
 */
internal fun invalidateSuspendedTiming(reason: String) {
    PerformanceMetricsCollector.resetFrameEpoch()
    PluviaApp.xServerView?.getxServer()
        ?.getExtension<PresentExtension>(PresentExtension.MAJOR_OPCODE.toInt())
        ?.resetTiming()
    ShmFramePacer.resetTiming()
    Timber.i(
        "Timing invalidated after suspend boundary: reason=%s guest_suspended=%b timing_sample_valid=false",
        reason,
        PluviaApp.isOverlayPaused,
    )
}
