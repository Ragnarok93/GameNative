package app.gamenative.powercontrol

/**
 * Coalesces writes to GameNative's source-frame pacing sinks.
 *
 * Native frame generation never owns source pacing. Callers resolve the desired source cap first,
 * then use this coordinator to decide whether the renderer/SHM pacing sinks actually need an
 * update. The target token represents the live renderer/view instance so a replacement renderer
 * receives the current cap once even when the numeric limit did not change.
 */
internal class SourceFramePacingCoordinator {
    private var lastAppliedLimit: Int? = null
    private var lastTargetToken: Int? = null

    @Synchronized
    fun shouldApply(limitFps: Int, targetToken: Int): Boolean {
        val resolvedLimit = limitFps.coerceAtLeast(0)
        if (lastAppliedLimit == resolvedLimit && lastTargetToken == targetToken) {
            return false
        }

        lastAppliedLimit = resolvedLimit
        lastTargetToken = targetToken
        return true
    }

    @Synchronized
    fun invalidate() {
        lastAppliedLimit = null
        lastTargetToken = null
    }
}
