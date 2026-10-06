package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgPacingCallSiteContractTest {
    @Test
    fun xServerScreenAndControllerOwnLsfgPacingHandoff() {
        val source = read("app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val controller = read("app/gamenative/ui/screen/xserver/XServerScreenController.kt")
        val limiter = source.substringAfter("fun applyFpsLimiterToEngines(limit: Int)")
            .substringBefore("fun effectiveFpsLimit()")

        assertTrue(controller.contains("val isLsfgRequested: Boolean get()"))
        assertTrue(controller.contains("var lsfgRuntimeMode: LsfgRuntimeMode"))
        assertTrue(controller.contains("var isLsfgGenerationActive: Boolean"))
        assertTrue(controller.contains("var lsfgRuntimeMultiplier: Int"))
        assertTrue(limiter.contains("val sourceFrameCap = effectiveSourceFpsCap(limit)"))
        assertTrue(limiter.contains("val runtimeMultiplier = if (lsfgActive) lsfgRuntimeMultiplier.coerceIn(2, 4) else 1"))
        assertTrue(limiter.contains("applyLsfgPresentationFrameRateHint("))
        assertTrue(limiter.contains("xServerView?.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("ShmFramePacer.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("PowerManager.targetFps = sourceFrameCap"))
        assertTrue(limiter.contains("PerformanceMetricsCollector.resetFrameEpoch()"))
        assertFalse(limiter.contains("transitionLsfgFramePacing"))
        assertFalse(limiter.contains("transitionFramePacing"))
    }

    @Test
    fun backendSelectionSubmitsExactlyOneManagerTransaction() {
        val source = read("app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val quickMenu = read("app/gamenative/ui/component/QuickMenu.kt")
        val backend = source.substringAfter("fun applyLsfgBackend(requestedBackend: String)")
            .substringBefore("fun applyAdaptiveFpsCapOnMain")

        assertTrue(backend.contains("LsfgVkManager.submitBackendTransition("))
        assertTrue(backend.contains("lsfgBackend = transition.backend"))
        assertTrue(backend.contains("scheduleLsfgRuntimeHandoff("))
        assertTrue(backend.contains("transition = transition"))
        assertFalse(backend.contains("renderer.setFrameGenerationEnabled"))
        assertFalse(backend.contains("refreshNativeRuntime"))
        assertFalse(backend.contains("applyNativeRuntime"))
        assertFalse(backend.contains("updateConfigAtRuntime"))
        assertTrue(quickMenu.contains("onBackendChanged = onLsfgBackendChanged"))
        assertTrue(quickMenu.contains("selected = backend == value"))
        assertTrue(quickMenu.contains("singleLine = true"))
    }

    @Test
    fun rendererAttachmentIsManagerOwnedAndCannotDuplicateRuntimeApplication() {
        val source = read("app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val attachment = source.substringAfter("LaunchedEffect(xServerView?.renderer)")
            .substringBefore("fun applyFpsLimiterToEngines")
        val manager = read("app/gamenative/utils/LsfgVkManager.kt")

        assertTrue(attachment.contains("LsfgVkManager.attachRenderer("))
        assertFalse(attachment.contains("LsfgQuickMenuHelper.applyFrameQueueToRenderer"))
        assertFalse(attachment.contains("LsfgVkManager.applyNativeRuntime"))
        assertTrue(manager.contains("fun attachRenderer("))
    }

    @Test
    fun backendTransactionTelemetryCarriesOneRevisionAndCompletionReason() {
        val manager = read("app/gamenative/utils/LsfgVkManager.kt")

        assertTrue(manager.contains("data class BackendTransitionRequest"))
        assertTrue(manager.contains("val token = reserveRuntimeRequest(container)"))
        assertTrue(manager.contains("event=start transaction_id=%d revision=%d"))
        assertTrue(manager.contains("native_drain_result=%s"))
        assertTrue(manager.contains("event=handoff_complete transaction_id=%d revision=%d"))
        assertTrue(manager.contains("completion_reason=%s"))
        assertTrue(manager.contains("generationReady = activationReady"))
        assertTrue(manager.contains("NativeHealthState.CONFIRMATION_LAGGING"))
    }

    @Test
    fun quickMenuRevisionIsAllocatedOnlyWhenDebouncedPublicationCommits() {
        val helper = read("app/gamenative/utils/LsfgQuickMenuHelper.kt")
        val snapshot = helper.substringAfter("private fun snapshotRuntimeConfig")
            .substringBefore("private fun scheduleRuntimeConfig")
        val publish = helper.substringAfter("private fun publishRuntimeConfig")

        assertFalse(snapshot.contains("reserveRuntimeRequest"))
        assertFalse(snapshot.contains("revision: Long"))
        assertTrue(publish.contains("val request = LsfgVkManager.reserveRuntimeRequest(container)"))
        assertTrue(publish.contains("requestToken = request"))
    }

    @Test
    fun runtimeHandoffIsBackendAwareAndSuspendAware() {
        val handoff = read("app/gamenative/utils/LsfgRuntimeHandoffController.kt")
        val controller = read("app/gamenative/ui/screen/xserver/XServerScreenController.kt")
        val manager = read("app/gamenative/utils/LsfgVkManager.kt")

        assertTrue(handoff.contains("LsfgVkManager.readRuntimeState(container)"))
        assertTrue(handoff.contains("runtimeState.nativeActivationReady"))
        assertTrue(manager.contains("val nativeActivationReady"))
        assertTrue(handoff.contains("runtimeState.readyForSourceOnly"))
        assertTrue(handoff.contains("LsfgVkManager.backend(container) == backend"))
        assertTrue(handoff.contains("isBackendTransitionPresentationReady"))
        assertTrue(handoff.contains("completeBackendTransition"))
        assertTrue(handoff.contains("\$backend-source-only-timeout"))
        assertTrue(controller.contains("registerGuestSuspensionProbe"))
        assertTrue(controller.contains("PluviaApp.isOverlayPaused"))
        assertTrue(manager.contains("legacy_source_only_wait_suspended"))
        assertTrue(manager.contains("activeAckWaitNs"))
    }

    @Test
    fun quickMenuPresentModeChangeReappliesPresentationVoteWithoutChangingSourceCap() {
        val source = read("app/gamenative/ui/component/QuickMenu.kt")
        val callback = source.substringAfter("onPresentModeChanged = { mode ->")
            .substringBefore("},", missingDelimiterValue = source.substringAfter("onPresentModeChanged = { mode ->"))

        assertTrue(source.contains("applyPresentMode(it, mode)"))
        assertTrue(source.contains("applyLsfgPresentationFrameRateHint("))
        assertTrue(source.contains("getFrameRateLimit()"))
        assertFalse(callback.contains("PresentExtension"))
        assertFalse(callback.contains("ShmFramePacer"))
    }

    @Test
    fun adaptiveTargetChangesRefreshFifoPresentationVote() {
        val source = read("app/gamenative/ui/component/QuickMenu.kt")
        val tab = source.substringAfter("private fun LsfgQuickMenuTab(")

        assertTrue(tab.contains("fun reapplyPresentationHint()"))
        assertTrue(tab.contains("setAdaptiveTargetFps(it, next)"))
        assertTrue(tab.contains("adaptiveTargetFps = next"))
    }

    @Test
    fun xServerScreenDoesNotRunAuxiliaryLsfgVsyncClock() {
        val source = read("app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val manager = read("app/gamenative/utils/LsfgVkManager.kt")

        assertFalse(source.contains("DisposableEffect(container, lsfgRuntimeMode)"))
        assertFalse(source.contains("LsfgVkManager.startVsyncClock"))
        assertFalse(source.contains("LsfgVkManager.stopVsyncClock"))
        assertFalse(manager.contains("fun startVsyncClock("))
        assertFalse(manager.contains("fun stopVsyncClock("))
        assertFalse(manager.contains("Choreographer"))
    }

    @Test
    fun capOnlyChangesDoNotRepublishLsfgRuntimeSettings() {
        val source = read("app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val enabled = source.substringAfter("fun applyFpsLimiterEnabled(enabled: Boolean)")
            .substringBefore("fun applyFpsLimiterTarget(target: Int)")
        val target = source.substringAfter("fun applyFpsLimiterTarget(target: Int)")
            .substringBefore("fun applyLsfgMultiplier(mult: Int)")

        assertFalse(enabled.contains("applyLsfgSettings()"))
        assertFalse(target.contains("applyLsfgSettings()"))
    }

    @Test
    fun quickMenuSuspendResumeInvalidatesTimingWithoutRuntimeRecreation() {
        val source = read("app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val invalidator = source.substringAfter("fun invalidateSuspendedTiming(reason: String)")
            .substringBefore("fun startExitWatchForUnmappedGameWindow")

        assertTrue(invalidator.contains("PerformanceMetricsCollector.resetFrameEpoch()"))
        assertTrue(invalidator.contains("?.resetTiming()"))
        assertTrue(invalidator.contains("ShmFramePacer.resetTiming()"))
        assertFalse(invalidator.contains("applyLsfgSettings()"))
        assertFalse(invalidator.contains("scheduleLsfgRuntimeHandoff"))
    }

    @Test
    fun adaptiveCapApplicationAcknowledgesMainThreadCompletion() {
        val source = read("app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val powerManager = read("app/gamenative/powercontrol/PowerManager.kt")

        assertTrue(source.contains("completed.await(750L, TimeUnit.MILLISECONDS)"))
        assertTrue(source.contains("adaptiveCapGeneration.compareAndSet"))
        assertTrue(powerManager.contains("completed.await(750L, TimeUnit.MILLISECONDS)"))
        assertTrue(powerManager.contains("generation != fpsCapGeneration.get()"))
        assertTrue(powerManager.contains("return applied.get()"))
    }

    @Test
    fun metricsAndLoaderStateRejectStaleSessionsAndPartialRuntimePublication() {
        val metrics = read("app/gamenative/powercontrol/metrics/PerformanceMetricsCollector.kt")
        val manager = read("app/gamenative/utils/LsfgVkManager.kt")

        assertTrue(metrics.contains("sessionGeneration"))
        assertTrue(metrics.contains("isSessionCurrent(generation)"))
        assertTrue(manager.contains("runtimeInstallLock"))
        assertTrue(manager.contains("copyFileAtomic"))
        assertTrue(manager.contains("filesHaveSameContents(steamDll, dllFile)"))
        assertTrue(manager.contains("Publish the marker last"))
        assertTrue(manager.contains("disableLayerForLaunch"))
    }

    private fun read(relative: String): String =
        String(Files.readAllBytes(sourcePath(relative)), Charsets.UTF_8)

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
