package app.gamenative.utils

import com.winlator.container.Container
import java.util.Locale
import java.util.concurrent.Executors
import timber.log.Timber

/** Helpers for Quick Menu LSFG state persistence and runtime hot-reload. */
object LsfgQuickMenuHelper {
    private const val TAG = "LsfgAdaptive"
    private const val SETTINGS_APPLY_DEBOUNCE_MS = 400L
    private const val FPS_LIMITER_ENABLED_EXTRA = "fpsLimiterEnabled"
    private const val FPS_LIMITER_TARGET_EXTRA = "fpsLimiterTarget"

    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
        val adaptiveFrameGen: Boolean,
        /** Compatibility mirror of GameNative's FPS limiter target. */
        val adaptiveOutputTargetFps: Int = 0,
    )

    fun isAvailable(container: Container): Boolean {
        LsfgRuntimeGate.configure(container.rootDir)
        return LsfgVkManager.isSupported(container) && LsfgVkManager.isArmed(container)
    }

    fun readSettings(container: Container): Settings = Settings(
        multiplier = LsfgVkManager.multiplier(container),
        flowScale = LsfgVkManager.flowScale(container),
        performanceMode = LsfgVkManager.performanceMode(container),
        adaptiveFrameGen = LsfgVkManager.adaptiveFrameGen(container),
        adaptiveOutputTargetFps = container.getExtra(FPS_LIMITER_TARGET_EXTRA, "60")
            .toIntOrNull()
            ?.coerceAtLeast(5)
            ?: 60,
    )

    private val applyExecutor =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lsfg-apply").apply { isDaemon = true } }
    private val settingsApplyDebouncer =
        LsfgRuntimeUpdateDebouncer(applyExecutor, SETTINGS_APPLY_DEBOUNCE_MS)

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    /**
     * The GameNative FPS limiter is the single frame-rate authority. The same
     * resolved cap is sent to source pacing and to Adaptive's target field so a
     * stale legacy LSFG output-target value cannot diverge from the limiter.
     */
    fun applyLiveFpsCap(container: Container, capFps: Int) {
        applyExecutor.execute {
            val resolvedLimit = capFps.coerceAtLeast(0)
            val settings = readSettings(container)
            val applied = LsfgVkManager.updateConfigAtRuntime(
                container = container,
                enabled = settings.multiplier >= 2,
                multiplier = if (settings.multiplier >= 2) settings.multiplier else 2,
                flowScale = settings.flowScale,
                performanceMode = settings.performanceMode,
                adaptiveFrameGen = settings.adaptiveFrameGen,
                fpsLimitOverride = resolvedLimit,
                sourceFpsLimitOverride = resolvedLimit,
            )
            Timber.tag(TAG).d(
                "FPS limiter hot-reload: source=%d adaptiveTarget=%d applied=%s",
                resolvedLimit,
                resolvedLimit,
                applied,
            )
        }
    }

    /**
     * Compatibility path for the older LSFG Adaptive-target control. It now
     * updates GameNative's FPS limiter target, rather than a second LSFG target.
     */
    fun applyAdaptiveOutputTarget(container: Container, targetFps: Int) {
        applyExecutor.execute {
            val sanitized = targetFps.coerceAtLeast(5)
            container.putExtra(FPS_LIMITER_TARGET_EXTRA, sanitized.toString())
            container.saveData()

            val limiterEnabled = container.getExtra(FPS_LIMITER_ENABLED_EXTRA, "false")
                .let { it.equals("true", ignoreCase = true) || it == "1" }
            publishSettledRuntimeSnapshot(
                container,
                if (limiterEnabled) sanitized else 0,
            )
        }
    }

    /** Persist the present mode and hot-apply it after the current adjustment burst settles. */
    fun applyPresentMode(container: Container, mode: String) {
        applyExecutor.execute {
            container.putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, mode)
            container.saveData()
            scheduleSettledRuntimePublish(container)
        }
    }

    fun sanitizeMultiplier(multiplier: Int): Int =
        if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)

    fun sanitizeFlowScale(flowScale: Float): Float =
        flowScale.coerceIn(0.25f, 1.0f)

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)

        // Persist immediately so the UI remains authoritative, but do not publish
        // every intermediate button-repeat value to the Vulkan layer. Multiplier,
        // enable and present-mode changes may require swapchain recreation, and a
        // burst of OUT_OF_DATE transitions is hostile to some games/X clients.
        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_FRAMEGEN, settings.adaptiveFrameGen.toString())
        container.saveData()

        scheduleSettledRuntimePublish(container)
    }

    private fun scheduleSettledRuntimePublish(container: Container) {
        settingsApplyDebouncer.submit {
            publishSettledRuntimeSnapshot(container, LsfgVkManager.sourceFpsLimit(container))
        }
    }

    private fun publishSettledRuntimeSnapshot(container: Container, resolvedLimit: Int) {
        // Re-read after the settle window so every adjustment path publishes one
        // coherent newest snapshot rather than a stale captured LSFG setting.
        val latest = readSettings(container)
        val latestMultiplier = sanitizeMultiplier(latest.multiplier)
        val effectiveEnabled = latestMultiplier >= 2
        val effectiveMultiplier = if (effectiveEnabled) latestMultiplier else 2
        val limiterTarget = resolvedLimit.coerceAtLeast(0)

        val applied = LsfgVkManager.updateConfigAtRuntime(
            container = container,
            enabled = effectiveEnabled,
            multiplier = effectiveMultiplier,
            flowScale = sanitizeFlowScale(latest.flowScale),
            performanceMode = latest.performanceMode,
            adaptiveFrameGen = latest.adaptiveFrameGen,
            fpsLimitOverride = limiterTarget,
            sourceFpsLimitOverride = limiterTarget,
        )
        Timber.tag(TAG).d(
            "Settled LSFG settings hot-reload: enabled=%s multiplier=%d adaptive=%s limiterTarget=%d applied=%s",
            effectiveEnabled,
            effectiveMultiplier,
            latest.adaptiveFrameGen,
            limiterTarget,
            applied,
        )
    }
}
