package app.gamenative.utils

import com.winlator.container.Container
import java.util.Locale
import java.util.concurrent.Executors
import timber.log.Timber

/** Quick Menu LSFG state persistence and runtime publication. */
object LsfgQuickMenuHelper {
    private const val RUNTIME_CONFIG_DEBOUNCE_MS = 120L
    private val runtimeConfigExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lsfg-runtime-config").apply { isDaemon = true }
    }
    private val runtimeConfigDebouncer = LsfgRuntimeUpdateDebouncer(
        runtimeConfigExecutor,
        RUNTIME_CONFIG_DEBOUNCE_MS,
    )
    enum class FrameGenerationMode { FIXED, ADAPTIVE }
    enum class FlowScaleMode { FIXED, ADAPTIVE }
    enum class AdaptiveFlowPreset { QUALITY, BALANCED, LOW, AUTO }

    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
    )

    internal data class RuntimeConfigSnapshot(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
        val generationMode: FrameGenerationMode,
        val adaptiveTargetFps: Int,
        val flowScaleMode: FlowScaleMode,
        val adaptiveFlowPreset: AdaptiveFlowPreset,
        val presentMode: String,
    )

    fun isAvailable(container: Container): Boolean {
        LsfgRuntimeGate.configure(container.rootDir)
        return LsfgVkManager.isAvailable(container)
    }

    fun readSettings(container: Container): Settings = Settings(
        multiplier = LsfgVkManager.multiplier(container),
        flowScale = LsfgVkManager.flowScale(container),
        performanceMode = LsfgVkManager.performanceMode(container),
    )

    fun generationMode(container: Container): FrameGenerationMode =
        if (LsfgVkManager.generationMode(container) == LsfgVkManager.MODE_ADAPTIVE) {
            FrameGenerationMode.ADAPTIVE
        } else FrameGenerationMode.FIXED

    fun fixedMultiplier(container: Container): Int = LsfgVkManager.fixedMultiplier(container)
    fun adaptiveTargetFps(container: Container): Int = LsfgVkManager.adaptiveTargetFps(container)

    fun flowScaleMode(container: Container): FlowScaleMode =
        if (LsfgVkManager.flowScaleMode(container) == LsfgVkManager.FLOW_MODE_ADAPTIVE) {
            FlowScaleMode.ADAPTIVE
        } else FlowScaleMode.FIXED

    fun adaptiveFlowPreset(container: Container): AdaptiveFlowPreset =
        when (LsfgVkManager.adaptiveFlowPreset(container)) {
            LsfgVkManager.ADAPTIVE_FLOW_PRESET_BALANCED -> AdaptiveFlowPreset.BALANCED
            LsfgVkManager.ADAPTIVE_FLOW_PRESET_LOW -> AdaptiveFlowPreset.LOW
            LsfgVkManager.ADAPTIVE_FLOW_PRESET_AUTO -> AdaptiveFlowPreset.AUTO
            else -> AdaptiveFlowPreset.QUALITY
        }

    fun setFlowScaleMode(container: Container, mode: FlowScaleMode) {
        val previousFlowMode = flowScaleMode(container)
        val preservedGenerationMode = generationMode(container)
        Timber.i(
            "LSFG setting change key=flowScaleMode previous=%s next=%s preservedGenerationMode=%s multiplier=%d",
            previousFlowMode,
            mode,
            preservedGenerationMode,
            LsfgVkManager.multiplier(container),
        )
        container.putExtra(
            LsfgVkManager.EXTRA_FLOW_SCALE_MODE,
            if (mode == FlowScaleMode.ADAPTIVE) {
                LsfgVkManager.FLOW_MODE_ADAPTIVE
            } else {
                LsfgVkManager.FLOW_MODE_FIXED
            },
        )
        container.saveData()
        if (sanitizeMultiplier(LsfgVkManager.multiplier(container)) >= 2) {
            scheduleRuntimeConfig(container)
        }
    }

    fun setAdaptiveFlowPreset(container: Container, preset: AdaptiveFlowPreset) {
        val serialized = when (preset) {
            AdaptiveFlowPreset.QUALITY -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_QUALITY
            AdaptiveFlowPreset.BALANCED -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_BALANCED
            AdaptiveFlowPreset.LOW -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_LOW
            AdaptiveFlowPreset.AUTO -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_AUTO
        }
        container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_FLOW_PRESET, serialized)
        container.saveData()
        if (flowScaleMode(container) == FlowScaleMode.ADAPTIVE &&
            sanitizeMultiplier(LsfgVkManager.multiplier(container)) >= 2
        ) {
            scheduleRuntimeConfig(container)
        }
    }

    fun setGenerationMode(container: Container, mode: FrameGenerationMode) {
        val previousGenerationMode = generationMode(container)
        Timber.i(
            "LSFG setting change key=generationMode previous=%s next=%s flowScaleMode=%s multiplier=%d",
            previousGenerationMode,
            mode,
            flowScaleMode(container),
            LsfgVkManager.multiplier(container),
        )
        container.putExtra(
            LsfgVkManager.EXTRA_FRAMEGEN_MODE,
            if (mode == FrameGenerationMode.ADAPTIVE) LsfgVkManager.MODE_ADAPTIVE else LsfgVkManager.MODE_FIXED,
        )
        container.saveData()
        scheduleRuntimeConfig(container)
    }

    fun setFixedMultiplier(container: Container, multiplier: Int) {
        container.putExtra(LsfgVkManager.EXTRA_FIXED_MULTIPLIER, multiplier.coerceIn(2, 4).toString())
        container.saveData()
        scheduleRuntimeConfig(container)
    }

    fun setAdaptiveTargetFps(container: Container, targetFps: Int) {
        val sanitized = LsfgVkManager.sanitizeAdaptiveTargetFps(targetFps)
        container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_TARGET_FPS, sanitized.toString())
        container.saveData()
        if (generationMode(container) == FrameGenerationMode.ADAPTIVE &&
            sanitizeMultiplier(LsfgVkManager.multiplier(container)) >= 2
        ) {
            scheduleRuntimeConfig(container)
        }
    }

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    fun applyPresentMode(container: Container, mode: String) {
        val sanitized = mode.takeIf { it == "mailbox" || it == "fifo" } ?: "mailbox"
        container.putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, sanitized)
        container.saveData()
        scheduleRuntimeConfig(container)
    }

    fun sanitizeMultiplier(multiplier: Int): Int = if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)
    fun sanitizeFlowScale(flowScale: Float): Float = flowScale.coerceIn(0.25f, 1.0f)

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)
        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.saveData()
        scheduleRuntimeConfig(container)
    }

    private fun snapshotRuntimeConfig(
        container: Container,
        settings: Settings = readSettings(container),
    ): RuntimeConfigSnapshot = RuntimeConfigSnapshot(
        multiplier = sanitizeMultiplier(settings.multiplier),
        flowScale = sanitizeFlowScale(settings.flowScale),
        performanceMode = settings.performanceMode,
        generationMode = generationMode(container),
        adaptiveTargetFps = adaptiveTargetFps(container),
        flowScaleMode = flowScaleMode(container),
        adaptiveFlowPreset = adaptiveFlowPreset(container),
        presentMode = presentMode(container),
    )

    private fun scheduleRuntimeConfig(container: Container) {
        // Capture every coupled LSFG mode before entering the debounce queue.
        // A Flow-only update must not reread framegen mode later and vice versa.
        val snapshot = snapshotRuntimeConfig(container)
        runtimeConfigDebouncer.submit {
            publishRuntimeConfig(container, snapshot)
        }
    }

    private fun publishRuntimeConfig(
        container: Container,
        snapshot: RuntimeConfigSnapshot,
    ) {
        val enabled = snapshot.multiplier >= 2
        val adaptive =
            enabled && snapshot.generationMode == FrameGenerationMode.ADAPTIVE
        val effectiveMultiplier = when {
            !enabled -> 2
            adaptive -> 4
            else -> snapshot.multiplier.coerceIn(2, 4)
        }
        val adaptiveFlow =
            enabled && snapshot.flowScaleMode == FlowScaleMode.ADAPTIVE
        val serializedFlowPreset = when (snapshot.adaptiveFlowPreset) {
            AdaptiveFlowPreset.QUALITY -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_QUALITY
            AdaptiveFlowPreset.BALANCED -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_BALANCED
            AdaptiveFlowPreset.LOW -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_LOW
            AdaptiveFlowPreset.AUTO -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_AUTO
        }

        Timber.i(
            "LSFG runtime snapshot generationMode=%s multiplier=%d adaptiveTarget=%d flowMode=%s flowPreset=%s flowScale=%.2f enabled=%b",
            snapshot.generationMode,
            effectiveMultiplier,
            snapshot.adaptiveTargetFps,
            snapshot.flowScaleMode,
            snapshot.adaptiveFlowPreset,
            snapshot.flowScale,
            enabled,
        )
        LsfgVkManager.updateConfigAtRuntime(
            container = container,
            enabled = enabled,
            multiplier = effectiveMultiplier,
            flowScale = snapshot.flowScale,
            performanceMode = snapshot.performanceMode,
            adaptiveFramegen = adaptive,
            fpsLimit = if (adaptive) snapshot.adaptiveTargetFps else 0,
            adaptiveFlowScale = adaptiveFlow,
            adaptiveFlowPreset = serializedFlowPreset,
            presentMode = snapshot.presentMode,
        )
    }
}
