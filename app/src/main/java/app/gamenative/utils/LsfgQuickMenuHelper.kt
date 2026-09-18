package app.gamenative.utils

import com.winlator.container.Container
import java.util.Locale

/** Quick Menu LSFG state persistence and runtime publication. */
object LsfgQuickMenuHelper {
    enum class FrameGenerationMode { FIXED, ADAPTIVE }
    enum class FlowScaleMode { FIXED, ADAPTIVE }
    enum class AdaptiveFlowPreset { QUALITY, BALANCED, LOW }

    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
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
            else -> AdaptiveFlowPreset.QUALITY
        }

    fun setFlowScaleMode(container: Container, mode: FlowScaleMode) {
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
            publishRuntimeConfig(container, readSettings(container))
        }
    }

    fun setAdaptiveFlowPreset(container: Container, preset: AdaptiveFlowPreset) {
        val serialized = when (preset) {
            AdaptiveFlowPreset.QUALITY -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_QUALITY
            AdaptiveFlowPreset.BALANCED -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_BALANCED
            AdaptiveFlowPreset.LOW -> LsfgVkManager.ADAPTIVE_FLOW_PRESET_LOW
        }
        container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_FLOW_PRESET, serialized)
        container.saveData()
        if (flowScaleMode(container) == FlowScaleMode.ADAPTIVE &&
            sanitizeMultiplier(LsfgVkManager.multiplier(container)) >= 2
        ) {
            publishRuntimeConfig(container, readSettings(container))
        }
    }

    fun setGenerationMode(container: Container, mode: FrameGenerationMode) {
        container.putExtra(
            LsfgVkManager.EXTRA_FRAMEGEN_MODE,
            if (mode == FrameGenerationMode.ADAPTIVE) LsfgVkManager.MODE_ADAPTIVE else LsfgVkManager.MODE_FIXED,
        )
        container.saveData()
    }

    fun setFixedMultiplier(container: Container, multiplier: Int) {
        container.putExtra(LsfgVkManager.EXTRA_FIXED_MULTIPLIER, multiplier.coerceIn(2, 4).toString())
        container.saveData()
    }

    fun setAdaptiveTargetFps(container: Container, targetFps: Int) {
        val sanitized = LsfgVkManager.sanitizeAdaptiveTargetFps(targetFps)
        container.putExtra(LsfgVkManager.EXTRA_ADAPTIVE_TARGET_FPS, sanitized.toString())
        container.saveData()
        if (generationMode(container) == FrameGenerationMode.ADAPTIVE &&
            sanitizeMultiplier(LsfgVkManager.multiplier(container)) >= 2
        ) {
            publishRuntimeConfig(container, readSettings(container))
        }
    }

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    fun applyPresentMode(container: Container, mode: String) {
        val sanitized = mode.takeIf { it == "mailbox" || it == "fifo" } ?: "mailbox"
        container.putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, sanitized)
        container.saveData()
        publishRuntimeConfig(container, readSettings(container))
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
        publishRuntimeConfig(container, settings.copy(multiplier = multiplier, flowScale = flowScale))
    }

    private fun publishRuntimeConfig(container: Container, settings: Settings) {
        val enabled = sanitizeMultiplier(settings.multiplier) >= 2
        val adaptive = enabled && generationMode(container) == FrameGenerationMode.ADAPTIVE
        val effectiveMultiplier = when {
            !enabled -> 2
            adaptive -> 4
            else -> sanitizeMultiplier(settings.multiplier).coerceIn(2, 4)
        }
        LsfgVkManager.updateConfigAtRuntime(
            container,
            enabled,
            effectiveMultiplier,
            sanitizeFlowScale(settings.flowScale),
            settings.performanceMode,
        )
    }
}
