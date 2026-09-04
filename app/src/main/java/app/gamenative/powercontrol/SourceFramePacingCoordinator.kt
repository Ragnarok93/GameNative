package app.gamenative.powercontrol

import android.os.Handler
import android.os.Looper

/**
 * Coalesces writes to GameNative's source-frame pacing sinks.
 *
 * Native frame generation never owns source pacing. Callers resolve the desired source cap first,
 * then use this coordinator to decide whether the renderer/SHM pacing sinks actually need an
 * update. The target token represents the live renderer/view instance so a replacement renderer
 * receives the current cap once even when the numeric limit did not change.
 *
 * Background callers are serialized onto the main/apply thread here rather than in the caller.
 * Every queued update carries a revision, so an older background cap cannot run after a newer UI
 * cap and leave the sinks stale while the coordinator incorrectly believes the newer cap won.
 */
internal class SourceFramePacingCoordinator(
    private val isApplyThread: () -> Boolean = {
        Looper.myLooper() == Looper.getMainLooper()
    },
    private val postToApplyThread: (() -> Unit) -> Unit = { action ->
        Handler(Looper.getMainLooper()).post(action)
        Unit
    },
    private val applyPendingOnApplyThread: (Int) -> Unit = { limit ->
        // The optional session callback mirrors the same effective limiter into
        // LSFG's hot-reload config. It does not replace GameNative's source-pacing
        // ownership; PowerManager immediately re-enters below and writes both sinks.
        PowerManager.fpsCapApplier?.invoke(limit)
        PowerManager.applyFpsCapToEngines(limit)
    },
) {
    private var lastAppliedLimit: Int? = null
    private var lastTargetToken: Int? = null
    private var pendingLimit: Int? = null
    private var pendingTargetToken: Int? = null
    private var pendingRevision: Long = 0L

    /**
     * @return true only when the caller is already on the apply thread and must perform the sink
     * write itself. A false result from a background change means the coordinator consumed it and
     * scheduled the newest value; PowerManager therefore returns success without posting a second
     * unversioned Runnable.
     */
    @Synchronized
    fun shouldApply(limitFps: Int, targetToken: Int): Boolean {
        val resolvedLimit = limitFps.coerceAtLeast(0)

        if (isApplyThread()) {
            // A main-thread decision supersedes every queued background decision, including one
            // for the same numeric cap but a renderer that has since been replaced.
            pendingRevision++
            pendingLimit = null
            pendingTargetToken = null

            if (lastAppliedLimit == resolvedLimit && lastTargetToken == targetToken) {
                return false
            }

            lastAppliedLimit = resolvedLimit
            lastTargetToken = targetToken
            return true
        }

        if (pendingLimit == resolvedLimit && pendingTargetToken == targetToken) {
            return false
        }
        if (pendingLimit == null &&
            lastAppliedLimit == resolvedLimit && lastTargetToken == targetToken
        ) {
            return false
        }

        pendingLimit = resolvedLimit
        pendingTargetToken = targetToken
        val revision = ++pendingRevision

        postToApplyThread {
            val stillCurrent = synchronized(this) {
                revision == pendingRevision &&
                    pendingLimit == resolvedLimit &&
                    pendingTargetToken == targetToken
            }
            if (stillCurrent) {
                // Re-enter PowerManager on the apply thread. The second shouldApply() call promotes
                // this pending revision to lastApplied before PowerManager writes both sinks.
                applyPendingOnApplyThread(resolvedLimit)
            }
        }

        return false
    }

    @Synchronized
    fun invalidate() {
        lastAppliedLimit = null
        lastTargetToken = null
        pendingLimit = null
        pendingTargetToken = null
        pendingRevision++
    }
}
