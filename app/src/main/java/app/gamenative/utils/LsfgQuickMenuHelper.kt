package app.gamenative.utils

import com.winlator.container.Container
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.concurrent.Executors

/** Helpers for Quick Menu LSFG state persistence and runtime hot-reload. */
object LsfgQuickMenuHelper {
    private const val SETTINGS_APPLY_DEBOUNCE_MS = 400L
    private const val ADAPTIVE_OVERLAY_RELATIVE_PATH = ".config/lsfg-vk/gamenative-adaptive.toml"
    private const val EXTRA_ADAPTIVE_FRAMEGEN = "lsfgAdaptiveFramegen"
    private const val EXTRA_ADAPTIVE_TARGET_FPS = "lsfgAdaptiveTargetFps"
    private const val DEFAULT_ADAPTIVE_TARGET_FPS = 60

    val ADAPTIVE_TARGET_OPTIONS: IntArray = intArrayOf(60, 90, 120, 144)

    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
        // Nullable so existing fixed-mode call sites can update multiplier/flow/perf
        // without implicitly changing the independent Adaptive controls.
        val adaptiveFramegen: Boolean? = null,
        val adaptiveTargetFps: Int? = null,
    )

    fun isAvailable(container: Container): Boolean {
        LsfgRuntimeGate.configure(container.rootDir)
        return LsfgVkManager.isAvailable(container)
    }

    fun readSettings(container: Container): Settings {
        val adaptiveEnabled = adaptiveFramegen(container)
        val adaptiveTarget = adaptiveTargetFps(container)
        writeAdaptiveOverlay(container, adaptiveEnabled, adaptiveTarget)
        return Settings(
            multiplier = LsfgVkManager.multiplier(container),
            flowScale = LsfgVkManager.flowScale(container),
            performanceMode = LsfgVkManager.performanceMode(container),
            adaptiveFramegen = adaptiveEnabled,
            adaptiveTargetFps = adaptiveTarget,
        )
    }

    private val applyExecutor =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lsfg-apply").apply { isDaemon = true } }
    private val settingsApplyDebouncer =
        LsfgRuntimeUpdateDebouncer(applyExecutor, SETTINGS_APPLY_DEBOUNCE_MS)

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    /**
     * Source-frame pacing belongs to XServerScreen/Present/SHM. Keep this hook
     * for existing callers, but never forward that source cap into LSFG's
     * Adaptive target-output control.
     */
    fun applyLiveFpsCap(container: Container, capFps: Int) {
        applyExecutor.execute {
            val settings = readSettings(container)
            LsfgVkManager.updateConfigAtRuntime(
                container,
                settings.multiplier >= 2,
                if (settings.multiplier >= 2) settings.multiplier else 2,
                settings.flowScale,
                settings.performanceMode,
            )
        }
    }

    /** Persist the present mode and publish it once the adjustment burst settles. */
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

    fun sanitizeAdaptiveTargetFps(targetFps: Int): Int =
        if (targetFps in ADAPTIVE_TARGET_OPTIONS) targetFps else DEFAULT_ADAPTIVE_TARGET_FPS

    fun applyAdaptiveSettings(container: Container, enabled: Boolean, targetFps: Int) {
        val current = readSettings(container)
        applySettings(
            container,
            current.copy(
                adaptiveFramegen = enabled,
                adaptiveTargetFps = sanitizeAdaptiveTargetFps(targetFps),
            ),
        )
    }

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)
        val adaptiveEnabled = settings.adaptiveFramegen ?: adaptiveFramegen(container)
        val adaptiveTarget = sanitizeAdaptiveTargetFps(
            settings.adaptiveTargetFps ?: adaptiveTargetFps(container),
        )

        // Persist immediately so the UI remains authoritative, but avoid
        // publishing every intermediate button-repeat/slider value to the
        // Vulkan layer. Multiplier and present-mode changes may recreate the
        // swapchain, and repeated OUT_OF_DATE transitions destabilize some
        // games and WSI paths.
        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.putExtra(EXTRA_ADAPTIVE_FRAMEGEN, adaptiveEnabled.toString())
        container.putExtra(EXTRA_ADAPTIVE_TARGET_FPS, adaptiveTarget.toString())
        container.saveData()
        writeAdaptiveOverlay(container, adaptiveEnabled, adaptiveTarget)

        scheduleSettledRuntimePublish(container)
    }

    private fun adaptiveFramegen(container: Container): Boolean =
        container.getExtra(EXTRA_ADAPTIVE_FRAMEGEN, "false").equals("true", ignoreCase = true) ||
            container.getExtra(EXTRA_ADAPTIVE_FRAMEGEN, "false") == "1"

    private fun adaptiveTargetFps(container: Container): Int =
        sanitizeAdaptiveTargetFps(
            container.getExtra(EXTRA_ADAPTIVE_TARGET_FPS, DEFAULT_ADAPTIVE_TARGET_FPS.toString())
                .toIntOrNull()
                ?: DEFAULT_ADAPTIVE_TARGET_FPS,
        )

    private fun writeAdaptiveOverlay(
        container: Container,
        enabled: Boolean,
        targetFps: Int,
    ): Boolean {
        val file = java.io.File(container.rootDir, ADAPTIVE_OVERLAY_RELATIVE_PATH)
        val text = buildString {
            appendLine("version = 1")
            appendLine("adaptive_framegen = ${if (enabled) "true" else "false"}")
            appendLine("target_output_fps = ${sanitizeAdaptiveTargetFps(targetFps)}")
        }
        val parent = file.parentFile ?: return false
        var temp: java.nio.file.Path? = null
        return try {
            parent.mkdirs()
            if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return true
            temp = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp")
            temp.toFile().writeText(text)
            try {
                Files.move(temp, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), REPLACE_EXISTING)
            }
            temp = null
            true
        } catch (_: Throwable) {
            false
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun scheduleSettledRuntimePublish(container: Container) {
        settingsApplyDebouncer.submit {
            publishSettledRuntimeSnapshot(container)
        }
    }

    private fun publishSettledRuntimeSnapshot(container: Container) {
        // Re-read after the settle window so the newest persisted state wins;
        // don't publish a stale snapshot captured by an earlier UI event.
        val latest = readSettings(container)
        val multiplier = sanitizeMultiplier(latest.multiplier)
        val effectiveEnabled = multiplier >= 2
        val effectiveMultiplier = if (effectiveEnabled) multiplier else 2
        LsfgVkManager.updateConfigAtRuntime(
            container,
            effectiveEnabled,
            effectiveMultiplier,
            sanitizeFlowScale(latest.flowScale),
            latest.performanceMode,
        )
    }
}
