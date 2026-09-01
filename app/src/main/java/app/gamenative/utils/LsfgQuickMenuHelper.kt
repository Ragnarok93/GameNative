package app.gamenative.utils

import com.winlator.container.Container
import java.util.Locale
import java.util.concurrent.Executors
import timber.log.Timber

/** Helpers for Quick Menu LSFG state persistence and runtime hot-reload. */
object LsfgQuickMenuHelper {
    private const val TAG = "LsfgAdaptive"

    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
        val adaptiveFrameGen: Boolean,
        /** Zero means preserve/auto-resolve the existing Adaptive output target. */
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
        adaptiveOutputTargetFps = LsfgVkManager.adaptiveOutputTarget(container),
    )

    private val applyExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-apply").apply { isDaemon = true } }

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    /**
     * GameNative's Adaptive FPS Cap controls the real/source limiter. LSFG's
     * Adaptive output objective is independent, so a PowerManager cap probe must
     * never rewrite native fps_limit. XServerScreen still applies the source-side
     * pacing pieces that are safe while LSFG owns Vulkan presentation.
     */
    fun applyLiveFpsCap(container: Container, capFps: Int) {
        applyExecutor.execute {
            val sourceLimit = capFps.coerceAtLeast(0)
            val settings = readSettings(container)
            val applied = LsfgVkManager.updateConfigAtRuntime(
                container = container,
                enabled = settings.multiplier >= 2,
                multiplier = if (settings.multiplier >= 2) settings.multiplier else 2,
                flowScale = settings.flowScale,
                performanceMode = settings.performanceMode,
                adaptiveFrameGen = settings.adaptiveFrameGen,
                fpsLimitOverride = null,
                sourceFpsLimitOverride = sourceLimit,
            )
            Timber.tag(TAG).d(
                "Source FPS cap hot-reload: source=%d output=%d applied=%s",
                sourceLimit,
                LsfgVkManager.adaptiveOutputTarget(container),
                applied,
            )
        }
    }

    /** Persist and hot-apply an explicit Adaptive FrameGen output objective. */
    fun applyAdaptiveOutputTarget(container: Container, targetFps: Int) {
        applyExecutor.execute {
            val sanitized = targetFps.coerceAtLeast(5)
            LsfgVkManager.setAdaptiveOutputTarget(container, sanitized)
            val settings = readSettings(container)
            LsfgVkManager.updateConfigAtRuntime(
                container = container,
                enabled = settings.multiplier >= 2,
                multiplier = if (settings.multiplier >= 2) settings.multiplier else 2,
                flowScale = settings.flowScale,
                performanceMode = settings.performanceMode,
                adaptiveFrameGen = settings.adaptiveFrameGen,
                fpsLimitOverride = sanitized,
            )
        }
    }

    /** Persist the present mode and hot-apply it. */
    fun applyPresentMode(container: Container, mode: String) {
        applyExecutor.execute {
            container.putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, mode)
            container.saveData()
            applySettings(container, readSettings(container))
        }
    }

    fun sanitizeMultiplier(multiplier: Int): Int =
        if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)

    fun sanitizeFlowScale(flowScale: Float): Float =
        flowScale.coerceIn(0.25f, 1.0f)

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)
        val explicitOutputTarget = settings.adaptiveOutputTargetFps.takeIf { it > 0 }

        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_FRAMEGEN, settings.adaptiveFrameGen.toString())
        explicitOutputTarget?.let {
            container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_OUTPUT_TARGET, it.toString())
        }
        container.saveData()

        val effectiveEnabled = multiplier >= 2
        val effectiveMultiplier = if (effectiveEnabled) multiplier else 2
        LsfgVkManager.updateConfigAtRuntime(
            container = container,
            enabled = effectiveEnabled,
            multiplier = effectiveMultiplier,
            flowScale = flowScale,
            performanceMode = settings.performanceMode,
            adaptiveFrameGen = settings.adaptiveFrameGen,
            fpsLimitOverride = explicitOutputTarget,
        )
    }
}
