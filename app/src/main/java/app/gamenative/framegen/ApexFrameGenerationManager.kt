package app.gamenative.framegen

import android.os.Build
import com.winlator.container.Container
import java.util.Locale

object ApexFrameGenerationManager {
    const val EXTRA_ARMED = "apexFrameGenerationEnabled"
    const val EXTRA_MODE = "apexFrameGenerationMode"
    const val EXTRA_FIXED_MULTIPLIER = "apexFixedMultiplier"
    const val EXTRA_ADAPTIVE_TARGET_FPS = "apexAdaptiveTargetFps"
    const val EXTRA_FLOW_SCALE = "apexFlowScale"
    const val EXTRA_QUALITY_PRESET = "apexQualityPreset"

    enum class GenerationMode(val persistedValue: String) {
        FIXED("fixed"),
        ADAPTIVE("adaptive");

        companion object {
            fun fromPersisted(value: String?): GenerationMode =
                when (value?.trim()?.lowercase(Locale.US)) {
                    "fixed" -> FIXED
                    else -> ADAPTIVE
                }
        }
    }

    enum class QualityPreset(val nativeValue: Int, val persistedValue: String) {
        FAST(0, "fast"),
        BALANCED(1, "balanced"),
        QUALITY(2, "quality");

        companion object {
            fun fromPersisted(value: String?): QualityPreset =
                when (value?.trim()?.lowercase(Locale.US)) {
                    "fast" -> FAST
                    "quality" -> QUALITY
                    else -> BALANCED
                }
        }
    }

    data class Settings(
        val mode: GenerationMode = GenerationMode.ADAPTIVE,
        val fixedMultiplier: Int = 2,
        val adaptiveTargetFps: Int = 60,
        val flowScale: Float = 1.0f,
        val qualityPreset: QualityPreset = QualityPreset.BALANCED,
    )

    @JvmStatic
    fun isSupported(displayRenderer: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            displayRenderer.equals("vulkan", ignoreCase = true)

    @JvmStatic
    fun isSupported(container: Container): Boolean =
        isSupported(container.displayRenderer)

    @JvmStatic
    fun isSelected(container: Container): Boolean =
        container.getExtra(EXTRA_ARMED, "false").toBoolean()

    @JvmStatic
    fun isRequested(container: Container): Boolean =
        isSupported(container) && isSelected(container)

    fun readSettings(container: Container): Settings =
        Settings(
            mode = GenerationMode.fromPersisted(container.getExtra(EXTRA_MODE, "adaptive")),
            fixedMultiplier = sanitizeMultiplier(
                container.getExtra(EXTRA_FIXED_MULTIPLIER, "2").toIntOrNull() ?: 2,
            ),
            adaptiveTargetFps = sanitizeTargetFps(
                container.getExtra(EXTRA_ADAPTIVE_TARGET_FPS, "60").toIntOrNull() ?: 60,
            ),
            flowScale = sanitizeFlowScale(
                container.getExtra(EXTRA_FLOW_SCALE, "1.0").toFloatOrNull() ?: 1.0f,
            ),
            qualityPreset = QualityPreset.fromPersisted(
                container.getExtra(EXTRA_QUALITY_PRESET, "balanced"),
            ),
        )

    fun persistSettings(container: Container, settings: Settings) {
        val sanitized = sanitize(settings)
        container.putExtra(EXTRA_MODE, sanitized.mode.persistedValue)
        container.putExtra(EXTRA_FIXED_MULTIPLIER, sanitized.fixedMultiplier.toString())
        container.putExtra(EXTRA_ADAPTIVE_TARGET_FPS, sanitized.adaptiveTargetFps.toString())
        container.putExtra(EXTRA_FLOW_SCALE, sanitized.flowScale.toString())
        container.putExtra(EXTRA_QUALITY_PRESET, sanitized.qualityPreset.persistedValue)
        container.saveData()
    }

    fun applyRuntimeSettings(container: Container) {
        applyRuntimeSettings(readSettings(container))
    }

    fun applyRuntimeSettings(settings: Settings) {
        val sanitized = sanitize(settings)
        ApexNativeBridge.nativeSetAdaptiveFrameGeneration(
            sanitized.mode == GenerationMode.ADAPTIVE,
        )
        ApexNativeBridge.nativeSetFixedMultiplier(sanitized.fixedMultiplier)
        ApexNativeBridge.nativeSetTargetFPS(sanitized.adaptiveTargetFps)
        ApexNativeBridge.nativeSetFlowScale(sanitized.flowScale)
        ApexNativeBridge.nativeSetQuality(sanitized.qualityPreset.nativeValue)
    }

    fun applySettings(container: Container, settings: Settings) {
        val sanitized = sanitize(settings)
        persistSettings(container, sanitized)
        applyRuntimeSettings(sanitized)
    }

    fun sanitizeMultiplier(value: Int): Int = value.coerceIn(2, 4)
    fun sanitizeTargetFps(value: Int): Int = value.coerceIn(30, 120)
    fun sanitizeFlowScale(value: Float): Float = value.coerceIn(0.25f, 1.0f)

    private fun sanitize(settings: Settings): Settings =
        settings.copy(
            fixedMultiplier = sanitizeMultiplier(settings.fixedMultiplier),
            adaptiveTargetFps = sanitizeTargetFps(settings.adaptiveTargetFps),
            flowScale = sanitizeFlowScale(settings.flowScale),
        )
}
