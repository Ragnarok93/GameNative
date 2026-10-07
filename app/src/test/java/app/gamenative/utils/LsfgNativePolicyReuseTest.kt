package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgNativePolicyReuseTest {
    private fun applied() = LsfgVkManager.NativeRuntimeConfigSnapshot(
        revision = 10L, backendGeneration = 3L, backend = LsfgVkManager.BACKEND_NATIVE,
        enabled = true, generationMode = LsfgVkManager.MODE_ADAPTIVE, multiplier = 4,
        targetFps = 60, flowMode = LsfgVkManager.FLOW_MODE_ADAPTIVE,
        flowPreset = LsfgVkManager.ADAPTIVE_FLOW_PRESET_QUALITY, requestedFlowScale = 0.7f,
        performanceMode = false, presentMode = "mailbox", frameQueueEnabled = true,
        frameQueueTarget = 1, displayRefresh = 60f,
    )

    @Test
    fun rapidPreferenceRequestsReuseTheAppliedPolicyWhenIntermediateRequestIsDiscarded() {
        val active = applied()
        val queuedFifo = active.copy(revision = 11L, presentMode = "fifo")
        val newestMailbox = queuedFifo.copy(revision = 12L, presentMode = "mailbox")
        assertTrue(active.hasSameEffectiveNativePolicy(queuedFifo))
        assertTrue(active.hasSameEffectiveNativePolicy(newestMailbox))
    }

    @Test
    fun aDiscardedRealChangeCannotBecomeTheBasisForReuse() {
        val active = applied()
        val unappliedQueueChange = active.copy(revision = 11L, frameQueueTarget = 2)
        val newest = unappliedQueueChange.copy(revision = 12L, presentMode = "fifo")
        assertTrue(unappliedQueueChange.hasSameEffectiveNativePolicy(newest))
        assertFalse(active.hasSameEffectiveNativePolicy(newest))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(displayRefresh = 90f)))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(targetFps = 90)))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(backendGeneration = 4L)))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(enabled = false)))
    }

    @Test
    fun reuseIsDecidedInsideExecutorAgainstRendererOwnedAppliedSnapshot() {
        var root = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (!File(root, "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt").isFile)
                root = root.parentFile ?: root
        }
        val source = File(root, "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt").readText()
        val start = source.indexOf("private fun applyNativeRuntime(")
        val executor = source.indexOf("nativeApplyExecutor.execute", start)
        val reuse = source.indexOf("if (nativePolicyMatches(renderer, container, snapshot))", start)
        assertTrue(reuse > executor)
        assertTrue(source.contains("applied.renderer.get() === renderer && applied.container === container"))
        assertTrue(source.contains("applied.snapshot.hasSameEffectiveNativePolicy(snapshot)"))
        assertFalse(source.contains("val previousSnapshot = latestNativeSnapshot"))
        val mutation = source.indexOf("val requested = snapshot.backend == BACKEND_NATIVE", reuse)
        assertTrue(source.indexOf("appliedNativePolicy = null", reuse) < mutation)
    }
}
