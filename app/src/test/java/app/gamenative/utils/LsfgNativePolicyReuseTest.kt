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
        // FIFO changes host swapchain policy even though generation parameters
        // match. Only the newest reversion to the applied Mailbox policy can reuse.
        assertFalse(active.hasSameEffectiveNativePolicy(queuedFifo))
        assertTrue(active.hasSameEffectiveNativePolicy(newestMailbox))
    }

    @Test
    fun aDiscardedRealChangeCannotBecomeTheBasisForReuse() {
        val active = applied()
        val unappliedQueueChange = active.copy(revision = 11L, frameQueueTarget = 2)
        val newest = unappliedQueueChange.copy(revision = 12L, presentMode = "fifo")
        assertFalse(unappliedQueueChange.hasSameEffectiveNativePolicy(newest))
        assertFalse(active.hasSameEffectiveNativePolicy(newest))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(displayRefresh = 90f)))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(targetFps = 90)))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(backendGeneration = 4L)))
        assertFalse(active.hasSameEffectiveNativePolicy(active.copy(enabled = false)))
    }

    private fun legacyState(
        fresh: Boolean = false,
        resident: Boolean = false,
        sourceOnly: Boolean = false,
        generating: Boolean = false,
        degraded: Boolean = false,
    ) = LsfgVkManager.RuntimeState(
        status = if (generating) LsfgVkManager.RuntimeStatus.GENERATING
            else if (sourceOnly) LsfgVkManager.RuntimeStatus.SOURCE_ONLY
            else LsfgVkManager.RuntimeStatus.UNKNOWN,
        resident = resident,
        sourceOnly = sourceOnly,
        generationReady = generating,
        generationInitialized = generating,
        generatedPresented = generating,
        degraded = degraded,
        multiplier = if (generating) 4 else 1,
        fresh = fresh,
    )

    @Test
    fun inPlaceNativeSettingsDoNotRepeatAnAcknowledgedBarrierWhenGuestIsPaused() {
        // Quick Menu stops guest publication. After one source-only ack, target,
        // queue and Flow changes have newer revisions but the same Native owner.
        // The original previousBackend=Legacy remains historical metadata.
        repeat(8) {
            assertFalse(LsfgVkManager.shouldAwaitLegacySourceOnly(
                liveLegacyTransition = true,
                legacyState = legacyState(),
                acknowledgedForOwner = true,
            ))
        }
    }

    @Test
    fun aNewRendererContainerOrBackendGenerationMustEstablishItsOwnBarrier() {
        // Manager ownership checks supply false for any new renderer, container,
        // or backend generation; an older owner's ack cannot arm this handoff.
        repeat(3) {
            assertTrue(LsfgVkManager.shouldAwaitLegacySourceOnly(
                liveLegacyTransition = true,
                legacyState = legacyState(),
                acknowledgedForOwner = false,
            ))
        }
    }

    @Test
    fun freshLegacyGenerationRequiresBarrierEvenWhenTheOldOwnerWasAcknowledged() {
        // Fresh contradictory evidence is stronger than the saved handoff ack.
        for (acknowledged in listOf(false, true)) {
            assertTrue(LsfgVkManager.shouldAwaitLegacySourceOnly(
                liveLegacyTransition = false,
                legacyState = legacyState(fresh = true, resident = true, generating = true),
                acknowledgedForOwner = acknowledged,
            ))
        }
    }

    @Test
    fun firstNativeLaunchWithoutLegacyOwnerDoesNotWaitForAbsentLayer() {
        assertFalse(LsfgVkManager.shouldAwaitLegacySourceOnly(
            liveLegacyTransition = false,
            legacyState = legacyState(),
            acknowledgedForOwner = false,
        ))
    }

    @Test
    fun freshSourceOnlyAcknowledgementAllowsContinuedNativeOwnership() {
        assertFalse(LsfgVkManager.shouldAwaitLegacySourceOnly(
            liveLegacyTransition = true,
            legacyState = legacyState(fresh = true, resident = true, sourceOnly = true),
            acknowledgedForOwner = true,
        ))
    }

    @Test
    fun degradedResidentLayerCannotMasqueradeAsSafeSourceOnlyState() {
        assertTrue(LsfgVkManager.shouldAwaitLegacySourceOnly(
            liveLegacyTransition = false,
            legacyState = legacyState(fresh = true, resident = true, sourceOnly = true, degraded = true),
            acknowledgedForOwner = true,
        ))
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

        // The barrier predicate's acknowledgement input must describe this
        // exact owner. A configuration revision alone cannot prove ownership.
        val owner = source.substringAfter("val acknowledgedForOwner = sourceOnlyOwner?.let {")
            .substringBefore("} == true")
        assertTrue(owner.contains("it.renderer.get() === renderer"))
        assertTrue(owner.contains("it.container === container"))
        assertTrue(owner.contains("it.backendGeneration == snapshot.backendGeneration"))
        assertTrue(source.contains("liveLegacyTransition, legacyStateBefore, acknowledgedForOwner)"))

        // The changed-policy path must publish host mode before Native settings
        // and commit; rejecting FIFO reuse is useful only if FIFO reaches WSI.
        val beforeNativeArm = source.indexOf("discardStaleSnapshot(snapshot, \"before-native-arm\")", mutation)
        val present = source.indexOf("renderer.setVkPresentMode(", beforeNativeArm)
        val settings = source.indexOf("renderer.applyFrameGenerationSettings(", present)
        val commit = source.indexOf("renderer.commitLsfgBackendTransitionPolicy(", settings)
        assertTrue(beforeNativeArm > mutation)
        assertTrue(present > beforeNativeArm)
        assertTrue(settings > present)
        assertTrue(commit > settings)
    }
}
