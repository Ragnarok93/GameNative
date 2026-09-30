package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgPacingCallSiteContractTest {
    @Test
    fun xServerScreenOwnsLsfgPacingHandoff() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val limiter = source.substringAfter("fun applyFpsLimiterToEngines(limit: Int)")
            .substringBefore("fun effectiveFpsLimit()")

        assertTrue(source.contains("val isLsfgRequested = isLsfgAvailable && lsfgMultiplier >= 2"))
        assertTrue(source.contains("private enum class LsfgRuntimeMode"))
        assertTrue(source.contains("var lsfgRuntimeMode by rememberSaveable(container.id)"))
        assertTrue(source.contains("var isLsfgGenerationActive by rememberSaveable(container.id)"))
        assertTrue(source.contains("var lsfgRuntimeMultiplier by rememberSaveable(container.id)"))
        assertTrue(limiter.contains("val sourceFrameCap = effectiveSourceFpsCap(limit)"))
        assertTrue(limiter.contains("val runtimeMultiplier = if (lsfgActive) lsfgRuntimeMultiplier.coerceIn(2, 4) else 1"))
        assertTrue(limiter.contains("applyLsfgPresentationFrameRateHint("))
        assertTrue(limiter.contains("LsfgQuickMenuHelper.presentMode(container) == \"fifo\""))
        assertTrue(limiter.contains("xServerView?.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("?.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("ShmFramePacer.setFrameRateLimit(sourceFrameCap)"))
        assertTrue(limiter.contains("PowerManager.targetFps = sourceFrameCap"))
        assertTrue(limiter.contains("LsfgQuickMenuHelper.generationMode(container)"))
        assertTrue(limiter.contains("LsfgQuickMenuHelper.adaptiveTargetFps(container)"))
        assertTrue(limiter.contains("PerformanceMetricsCollector.resetFrameEpoch()"))
        assertFalse(limiter.contains("if (lsfgActive) 0 else limit"))
        assertFalse(limiter.contains("transitionLsfgFramePacing"))
        assertFalse(limiter.contains("transitionFramePacing"))
        assertFalse(limiter.contains("LsfgRuntimeGate"))
    }

    @Test
    fun quickMenuPresentModeChangeReappliesPresentationVoteWithoutChangingSourceCap() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/component/QuickMenu.kt")),
            Charsets.UTF_8,
        )
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
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/component/QuickMenu.kt")),
            Charsets.UTF_8,
        )
        val tab = source.substringAfter("private fun LsfgQuickMenuTab(")

        assertTrue(tab.contains("fun reapplyPresentationHint()"))
        assertTrue(tab.contains("setAdaptiveTargetFps(it, next)"))
        assertTrue(tab.contains("reapplyPresentationHint()"))
        assertTrue(tab.contains("adaptiveTargetFps = next"))
    }

    @Test
    fun xServerScreenDoesNotRunAuxiliaryLsfgVsyncClock() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val manager = String(
            Files.readAllBytes(sourcePath("app/gamenative/utils/LsfgVkManager.kt")),
            Charsets.UTF_8,
        )

        assertTrue(source.contains("var lsfgRuntimeMode by rememberSaveable(container.id)"))
        assertFalse(source.contains("DisposableEffect(container, lsfgRuntimeMode)"))
        assertFalse(source.contains("LsfgVkManager.startVsyncClock"))
        assertFalse(source.contains("LsfgVkManager.stopVsyncClock"))
        assertFalse(manager.contains("fun startVsyncClock("))
        assertFalse(manager.contains("fun stopVsyncClock("))
        assertFalse(manager.contains("Choreographer"))
    }

    @Test
    fun quickMenuDebouncesLsfgRuntimePacingHandoff() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val handoff = source.substringAfter("fun scheduleLsfgRuntimeHandoff")
            .substringBefore("fun applyFpsLimiterEnabled")
        val multiplier = source.substringAfter("fun applyLsfgMultiplier(mult: Int)")
            .substringBefore("fun applyLsfgFlowScale")
        val applier = source.substringAfter("PowerManager.fpsCapApplier = applier@")
            .substringBefore("val detectedMax")

        assertTrue(handoff.contains("LsfgVkManager.readRuntimeState(container)"))
        assertTrue(handoff.contains("runtimeState.readyForGeneration"))
        assertTrue(handoff.contains("runtimeState.readyForSourceOnly"))
        assertTrue(handoff.contains("LSFG_RUNTIME_HANDOFF_TIMEOUT_MS"))
        assertTrue(handoff.contains("remainingSettleMs"))
        assertTrue(handoff.contains("lsfgRuntimeMode = LsfgRuntimeMode.DEGRADED"))
        assertTrue(handoff.contains("isLsfgGenerationActive = active"))
        assertTrue(handoff.contains("applyFpsLimiterToEngines(effectiveFpsLimit())"))
        assertTrue(multiplier.contains("val previousRequested = isLsfgRequested"))
        assertTrue(multiplier.contains("if (previousRequested && !nextRequested)"))
        assertTrue(multiplier.contains("setLsfgPresentationFrameRateHint(0)"))
        assertTrue(multiplier.contains("resident bypass pending"))
        assertTrue(multiplier.contains("scheduleLsfgRuntimeHandoff(nextRequested, nextMultiplier)"))
        assertFalse(multiplier.contains("SOURCE_ONLY_RESIDENT ="))
        assertFalse(applier.contains("!isLsfgGenerationActive"))
        assertTrue(applier.contains("applyFpsLimiterToEngines(capFps)"))
    }

    @Test
    fun capOnlyChangesDoNotRepublishLsfgRuntimeSettings() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val enabled = source.substringAfter("fun applyFpsLimiterEnabled(enabled: Boolean)")
            .substringBefore("fun applyFpsLimiterTarget(target: Int)")
        val target = source.substringAfter("fun applyFpsLimiterTarget(target: Int)")
            .substringBefore("fun applyLsfgMultiplier(mult: Int)")

        assertFalse(enabled.contains("applyLsfgSettings()"))
        assertFalse(target.contains("applyLsfgSettings()"))
    }

    @Test
    fun quickMenuSuspendResumeInvalidatesTimingWithoutRuntimeRecreation() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
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
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val powerManager = String(
            Files.readAllBytes(sourcePath("app/gamenative/powercontrol/PowerManager.kt")),
            Charsets.UTF_8,
        )

        assertTrue(source.contains("CountDownLatch"))
        assertTrue(source.contains("completed.await(750L, TimeUnit.MILLISECONDS)"))
        assertTrue(source.contains("adaptiveCapGeneration.compareAndSet"))
        assertTrue(powerManager.contains("completed.await(750L, TimeUnit.MILLISECONDS)"))
        assertTrue(powerManager.contains("targetFps = limitFps"))
        assertTrue(powerManager.contains("fpsCapGeneration"))
        assertTrue(powerManager.contains("generation != fpsCapGeneration.get()"))
        assertTrue(powerManager.contains("return applied.get()"))
    }

    @Test
    fun metricsAndLoaderStateRejectStaleSessionsAndPartialRuntimePublication() {
        val metrics = String(
            Files.readAllBytes(sourcePath("app/gamenative/powercontrol/metrics/PerformanceMetricsCollector.kt")),
            Charsets.UTF_8,
        )
        val manager = String(
            Files.readAllBytes(sourcePath("app/gamenative/utils/LsfgVkManager.kt")),
            Charsets.UTF_8,
        )

        assertTrue(metrics.contains("sessionGeneration"))
        assertTrue(metrics.contains("isSessionCurrent(generation)"))
        assertTrue(metrics.contains("activeSessionLogPath"))
        assertTrue(manager.contains("runtimeInstallLock"))
        assertTrue(manager.contains("copyFileAtomic"))
        assertTrue(manager.contains("filesHaveSameContents(steamDll, dllFile)"))
        assertTrue(manager.contains("expectedManifest"))
        assertTrue(manager.contains("Publish the marker last"))
        assertTrue(manager.contains("disableLayerForLaunch"))
    }


    @Test
    fun bionicLaunchPreparesLsfgTransactionallyBeforeProcessExec() {
        val launcher = String(
            Files.readAllBytes(
                sourcePath("com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"),
            ),
            Charsets.UTF_8,
        )
        val manager = String(
            Files.readAllBytes(sourcePath("app/gamenative/utils/LsfgVkManager.kt")),
            Charsets.UTF_8,
        )

        val launchBlock = launcher.substringAfter(
            "if (LsfgVkManager.isFrameGenerationRequested(container))",
        ).substringBefore("} else if (LsfgVkManager.isSupported(container))")

        assertTrue(launchBlock.contains("LsfgVkManager.prepareLaunch("))
        assertFalse(launchBlock.contains("LsfgVkManager.ensureRuntimeInstalled("))
        assertFalse(launchBlock.contains("LsfgVkManager.writeConfig("))
        assertFalse(launchBlock.contains("LsfgVkManager.applyLaunchEnv("))
        assertTrue(launchBlock.contains("continuing with native Vulkan launch"))

        val prepare = manager.substringAfter("fun prepareLaunch(")
            .substringBefore("@JvmStatic\n    fun writeConfig")
        assertTrue(prepare.contains("ensureRuntimeInstalledLocked(context, container)"))
        assertTrue(prepare.contains("writeConfig(container)"))
        assertTrue(prepare.contains("applyLaunchEnvLocked(container, envVars)"))
        assertTrue(prepare.contains("disableLayerForLaunch(container, envVars)"))
        assertTrue(prepare.contains("LSFG launch preparation complete"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
