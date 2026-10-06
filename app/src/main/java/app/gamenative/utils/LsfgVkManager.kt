package app.gamenative.utils

import android.content.Context
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import java.util.concurrent.Executors
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import com.winlator.renderer.lsfg.LosslessScaling
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Locale
import timber.log.Timber
import kotlin.jvm.JvmStatic

/**
 * Manages the lsfg-vk Vulkan implicit layer for frame generation.
 *
 * The layer works by intercepting vkQueuePresentKHR inside the container's
 * Vulkan driver and running Lossless Scaling frame generation (LSFG_3_1 /
 * LSFG_3_1P) transparently. No overlay, no MediaProjection — it hooks the
 * real swapchain presentation path.
 *
 * Flow:
 * 1. At launch time: install the layer .so + manifest into the active
 *    container HOME where the Vulkan loader discovers implicit layers.
 * 2. Copy Lossless.dll from the Steam install dir (app 993090) into the
 *    container's ~/.local/share/lsfg-vk/ directory.
 * 3. Write conf.toml with the DLL path, multiplier, flow scale, and
 *    performance mode. Set env vars so the layer finds its config.
 * 4. At runtime: the Vulkan loader loads the layer, which hooks
 *    vkCreateSwapchainKHR / vkQueuePresentKHR and runs framegen on the
 *    game's actual swapchain images.
 */
object LsfgVkManager {
    private const val TAG = "LsfgVkManager"

    // Steam app ID for Lossless Scaling (used to auto-find the DLL)
    const val LOSSLESS_SCALING_APP_ID = 993090
    private const val LOSSLESS_DLL_NAME = "Lossless.dll"

    // Paths inside the container's HOME (relative to rootDir)
    private const val CONFIG_RELATIVE_PATH = ".config/lsfg-vk/conf.toml"
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val DLL_RELATIVE_DIR = ".local/share/lsfg-vk"
    private const val LIB_FILENAME = "liblsfg-vk-layer.so"
    private const val MANIFEST_FILENAME = "VkLayer_LS_frame_generation.json"
    private const val VERSION_FILENAME = ".lsfg_vk_runtime_version"
    private const val VULKAN_LAYER_NAME = "VK_LAYER_LS_frame_generation"

    // Relative path from implicit_layer.d back to lib/
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"

    // Process identifier retained for compatibility with older generated config.
    private const val PROCESS_EXE_IDENTIFIER = "gamenative-lsfg"

    // Container extra keys
    const val EXTRA_ARMED = "lsfgEnabled"
    const val EXTRA_MULTIPLIER = "lsfgMultiplier"
    const val EXTRA_FLOW_SCALE = "lsfgFlowScale"
    const val EXTRA_FLOW_SCALE_MODE = "lsfgFlowScaleMode"
    const val EXTRA_ADAPTIVE_FLOW_PRESET = "lsfgAdaptiveFlowPreset"
    const val EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode"
    const val EXTRA_PRESENT_MODE = "lsfgPresentMode"
    const val EXTRA_FRAME_QUEUE_ENABLED = "lsfgFrameQueueEnabled"
    const val EXTRA_FRAME_QUEUE_TARGET = "lsfgFrameQueueTarget"
    const val EXTRA_FRAMEGEN_MODE = "lsfgFramegenMode"
    const val EXTRA_FIXED_MULTIPLIER = "lsfgFixedMultiplier"
    const val EXTRA_ADAPTIVE_TARGET_FPS = "lsfgAdaptiveTargetFps"
    const val EXTRA_BACKEND = "lsfgBackend"

    const val BACKEND_LEGACY = "legacy"
    const val BACKEND_NATIVE = "native"

    data class BackendRequest(
        val serial: Long,
        val previousBackend: String,
        val backend: String,
        val requestedAtNs: Long,
    )

    data class RuntimeRequestToken(
        val revision: Long,
        val backendGeneration: Long,
        val backend: String,
    )

    data class NativeRuntimeConfigSnapshot(
        val revision: Long,
        val backendGeneration: Long,
        val backend: String,
        val enabled: Boolean,
        val generationMode: String,
        val multiplier: Int,
        val targetFps: Int,
        val flowMode: String,
        val flowPreset: String,
        val requestedFlowScale: Float,
        val performanceMode: Boolean,
        val presentMode: String,
        val frameQueueEnabled: Boolean,
        val frameQueueTarget: Int,
        val displayRefresh: Float,
    )

    enum class NativeBackendPhase {
        NATIVE_REQUESTED,
        NATIVE_INITIALIZING,
        NATIVE_READY,
        NATIVE_WSI_GENERATING,
        NATIVE_GENERATING,
        NATIVE_PRESENTATION_DEGRADED,
        NATIVE_CONFIRMATION_UNAVAILABLE,
        NATIVE_DEGRADED,
        NATIVE_FAILED,
    }

    private val backendRequestSerial = AtomicLong(0L)
    private val nativeConfigRevision = AtomicLong(0L)
    @Volatile private var latestBackendRequest: BackendRequest? = null

    const val MODE_FIXED = "fixed"
    const val MODE_ADAPTIVE = "adaptive"
    const val FLOW_MODE_FIXED = "fixed"
    const val FLOW_MODE_ADAPTIVE = "adaptive"
    const val ADAPTIVE_FLOW_PRESET_QUALITY = "quality"
    const val ADAPTIVE_FLOW_PRESET_BALANCED = "balanced"
    const val ADAPTIVE_FLOW_PRESET_LOW = "low"
    const val ADAPTIVE_FLOW_PRESET_AUTO = "auto"
    const val MIN_ADAPTIVE_TARGET_FPS = 30
    const val MAX_ADAPTIVE_TARGET_FPS = 120
    const val ADAPTIVE_TARGET_FPS_STEP = 5
    const val DEFAULT_ADAPTIVE_TARGET_FPS = 60

    // Written by the layer next to conf.toml; measured presented/base fps
    private const val STATS_RELATIVE_PATH = ".config/lsfg-vk/stats.txt"
    private const val RUNTIME_PRESSURE_RELATIVE_PATH =
        ".config/lsfg-vk/runtime-pressure.txt"
    private const val STATS_FRESHNESS_MS = 2000L
    private val statsReadExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-stats").apply { isDaemon = true } }
    }
    private val runtimeInstallLock = Any()

    // Environment variables consumed by the lsfg-vk layer / Vulkan loader.
    private const val ENV_DISABLE = "DISABLE_LSFG"
    private const val ENV_CONFIG = "LSFG_CONFIG"
    private const val ENV_PROCESS = "LSFG_PROCESS"
    private const val ENV_PROCESS_EXE = "LSFG_PROCESS_EXE"
    private const val ENV_HOME = "HOME"
    private const val ENV_VK_LAYER_PATH = "VK_LAYER_PATH"
    private const val ENV_VK_INSTANCE_LAYERS = "VK_INSTANCE_LAYERS"
    private const val ENV_VK_LOADER_LAYERS_ENABLE = "VK_LOADER_LAYERS_ENABLE"

    // Current runtime package revision. Keep the exact native gitlink revision
    // in the marker so loader-visible copies cannot masquerade as another build.
    private const val RUNTIME_VERSION =
        "gamenative-bannerlator-engine-c4ea747352046023be838e3bf8ac967bcad155e1-r20"

    // Asset path for manifest (still in assets)
    private const val ASSET_DIR = "lsfg_vk/android_arm64_v8a"
    private const val ASSET_MANIFEST = "$ASSET_DIR/$MANIFEST_FILENAME"

    // ---- Public API --------------------------------------------------------

    /** Whether LSFG is supported for this container's runtime variant. */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /** Whether the container has LSFG enabled in settings and can expose LSFG controls. */
    @JvmStatic
    fun isAvailable(container: Container): Boolean =
        isFrameGenerationRequested(container) &&
            containerDllPath(container) != null

    /** Whether launch should prepare the resident native LSFG layer for this container. */
    @JvmStatic
    fun isFrameGenerationRequested(container: Container): Boolean =
        isSupported(container) &&
            layerRequested(container)

    /** Whether the LSFG layer should be resident in the launched process. */
    @JvmStatic
    fun isArmed(container: Container): Boolean =
        isFrameGenerationRequested(container) &&
            containerDllPath(container) != null

    /** Whether Lossless Scaling is installed (Lossless.dll exists in Steam dir). */
    @JvmStatic
    fun isDllAvailable(): Boolean = findSteamDll() != null

    /** Whether the user owns Lossless Scaling in their Steam library. */
    @JvmStatic
    fun ownsLosslessScaling(): Boolean =
        SteamService.getAppInfoOf(LOSSLESS_SCALING_APP_ID) != null

    /** Get the DLL path inside the container, or null if the copy doesn't exist. */
    @JvmStatic
    fun containerDllPath(container: Container): String? {
        val dllFile = File(container.rootDir, "$DLL_RELATIVE_DIR/$LOSSLESS_DLL_NAME")
        return dllFile.absolutePath.takeIf { dllFile.isFile }
    }

    /** Get the multiplier (0=Off, 2-4, default 2). */
    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2
        return if (raw == 0) 0 else raw.coerceIn(2, 4)
    }

    fun generationMode(container: Container): String =
        container.getExtra(EXTRA_FRAMEGEN_MODE, MODE_FIXED)
            .lowercase(Locale.US)
            .takeIf { it == MODE_FIXED || it == MODE_ADAPTIVE }
            ?: MODE_FIXED

    fun fixedMultiplier(container: Container): Int =
        (container.getExtra(EXTRA_FIXED_MULTIPLIER, "2").toIntOrNull() ?: 2).coerceIn(2, 4)

    fun sanitizeAdaptiveTargetFps(targetFps: Int): Int {
        val clamped = targetFps.coerceIn(MIN_ADAPTIVE_TARGET_FPS, MAX_ADAPTIVE_TARGET_FPS)
        val offset = clamped - MIN_ADAPTIVE_TARGET_FPS
        return MIN_ADAPTIVE_TARGET_FPS + (offset / ADAPTIVE_TARGET_FPS_STEP) * ADAPTIVE_TARGET_FPS_STEP
    }

    fun adaptiveTargetFps(container: Container): Int =
        sanitizeAdaptiveTargetFps(
            container.getExtra(EXTRA_ADAPTIVE_TARGET_FPS, DEFAULT_ADAPTIVE_TARGET_FPS.toString())
                .toIntOrNull() ?: DEFAULT_ADAPTIVE_TARGET_FPS,
        )

    enum class RuntimeStatus {
        UNKNOWN,
        PASS_THROUGH,
        SOURCE_ONLY,
        GENERATING,
        DEGRADED,
    }

    data class RuntimeState(
        val status: RuntimeStatus,
        val resident: Boolean,
        val sourceOnly: Boolean,
        val generationReady: Boolean,
        val generationInitialized: Boolean,
        val generatedPresented: Boolean,
        val degraded: Boolean,
        val multiplier: Int,
        val fresh: Boolean,
        val framegenSupportKnown: Boolean = false,
        val framegenSupported: Boolean = false,
        val vulkanPath: String? = null,
        val spirvTarget: String? = null,
        val synchronizationPath: String? = null,
        val ahbMode: String? = null,
        val rejectionReason: String? = null,
        val wsiGenerating: Boolean = false,
        val displayConfirmedGenerating: Boolean = false,
        val displayConfirmationAvailable: Boolean = false,
        val presentationDegraded: Boolean = false,
    ) {
        val readyForGeneration: Boolean
            get() = fresh && resident && generationReady && multiplier >= 2 && !degraded &&
                (!framegenSupportKnown || framegenSupported)

        val readyForSourceOnly: Boolean
            get() = fresh && resident && sourceOnly && !generationReady && !degraded
    }

    private fun layerRequested(container: Container): Boolean =
        parseBool(container.getExtra(EXTRA_ARMED, "false"))

    private fun frameGenerationActive(container: Container): Boolean =
        !isNativeBackend(container) && isArmed(container) && multiplier(container) >= 2

    /** Get the persisted Fixed Flow Scale (0.25-1.0, default 0.80). */
    fun flowScale(container: Container): Float =
        container.getExtra(EXTRA_FLOW_SCALE, "0.80").toFloatOrNull()?.coerceIn(0.25f, 1.0f) ?: 0.80f

    /** Fixed preserves the saved slider; Adaptive selects a preset-bounded runtime scale. */
    fun flowScaleMode(container: Container): String =
        container.getExtra(EXTRA_FLOW_SCALE_MODE, FLOW_MODE_FIXED)
            .lowercase(Locale.US)
            .takeIf { it == FLOW_MODE_FIXED || it == FLOW_MODE_ADAPTIVE }
            ?: FLOW_MODE_FIXED

    fun sanitizeAdaptiveFlowPreset(preset: String): String =
        when (preset.lowercase(Locale.US)) {
            ADAPTIVE_FLOW_PRESET_BALANCED -> ADAPTIVE_FLOW_PRESET_BALANCED
            ADAPTIVE_FLOW_PRESET_LOW -> ADAPTIVE_FLOW_PRESET_LOW
            ADAPTIVE_FLOW_PRESET_AUTO -> ADAPTIVE_FLOW_PRESET_AUTO
            else -> ADAPTIVE_FLOW_PRESET_QUALITY
        }

    fun adaptiveFlowPreset(container: Container): String =
        sanitizeAdaptiveFlowPreset(
            container.getExtra(
                EXTRA_ADAPTIVE_FLOW_PRESET,
                ADAPTIVE_FLOW_PRESET_QUALITY,
            ),
        )

    /** Get whether performance mode is enabled (default true). */
    fun performanceMode(container: Container): Boolean =
        parseBool(container.getExtra(EXTRA_PERFORMANCE_MODE, "true"))

    /**
     * Swapchain present mode while frame generation runs ("mailbox" or
     * "fifo"). Mailbox is the default: the layer already paces vsync-locked,
     * and mesa's FIFO queue underneath it breaks the display cadence.
     */
    fun presentMode(container: Container): String =
        container.getExtra(EXTRA_PRESENT_MODE, "mailbox")
            .takeIf { it == "fifo" || it == "mailbox" } ?: "mailbox"

    fun sanitizeBackend(backend: String): String =
        backend.lowercase(Locale.US).takeIf { it == BACKEND_NATIVE } ?: BACKEND_LEGACY

    fun backend(container: Container): String =
        sanitizeBackend(container.getExtra(EXTRA_BACKEND, BACKEND_LEGACY))

    fun isNativeBackend(container: Container): Boolean = backend(container) == BACKEND_NATIVE

    @Synchronized
    fun setBackend(container: Container, backend: String): BackendRequest {
        nativeApplyGeneration.incrementAndGet()
        nativeConfigRevision.incrementAndGet()
        val sanitized = sanitizeBackend(backend)
        val previous = this.backend(container)
        val request = BackendRequest(
            serial = backendRequestSerial.incrementAndGet(),
            previousBackend = previous,
            backend = sanitized,
            requestedAtNs = System.nanoTime(),
        )
        latestBackendRequest = request
        Timber.i(
            "LSFG_BACKEND: event=backend_request backend_generation=%d request_serial=%d requested_backend=%s previous_backend=%s",
            request.serial,
            request.serial,
            sanitized,
            previous,
        )
        container.putExtra(EXTRA_BACKEND, sanitized)
        container.saveData()
        Timber.i(
            "LSFG_BACKEND: event=backend_state_changed backend_generation=%d request_serial=%d backend_state=%s",
            request.serial,
            request.serial,
            sanitized,
        )
        return request
    }

    @Synchronized
    internal fun reserveRuntimeRequest(container: Container): RuntimeRequestToken =
        RuntimeRequestToken(
            revision = nativeConfigRevision.incrementAndGet(),
            backendGeneration = backendRequestSerial.get(),
            backend = backend(container),
        )

    fun recordBackendRuntimeApplied(
        request: BackendRequest,
        result: String,
        runtimeBackend: String = request.backend,
        appliedAtNs: Long = System.nanoTime(),
    ) {
        val latencyMs = ((appliedAtNs - request.requestedAtNs).coerceAtLeast(0L)) / 1_000_000.0
        Timber.i(
            "LSFG_BACKEND: event=backend_runtime_applied backend_generation=%d request_serial=%d backend_apply_serial=%d " +
                "backend=%s runtime_backend=%s backend_apply_latency_ms=%.3f backend_apply_result=%s",
            request.serial,
            request.serial,
            if (result == "runtime-applied") request.serial else 0L,
            request.backend,
            sanitizeBackend(runtimeBackend),
            latencyMs,
            result,
        )
    }

    fun frameQueueEnabled(container: Container): Boolean =
        parseBool(container.getExtra(EXTRA_FRAME_QUEUE_ENABLED, "false"))

    fun frameQueueTarget(container: Container): Int =
        (container.getExtra(EXTRA_FRAME_QUEUE_TARGET, "0")?.toIntOrNull() ?: 0)
            .coerceIn(0, 2)


    /**
     * Publish coarse whole-device pressure telemetry for Adaptive Flow.
     *
     * This file is intentionally separate from conf.toml: updating it must never
     * trigger an LSFG context rebuild. PerformanceMetricsCollector already samples
     * these values every 500 ms, so this adds no second GPU/sysfs polling path.
     */
    internal fun publishRuntimePressure(
        rootDir: File?,
        snapshot: MetricsSnapshot,
    ): Boolean {
        val root = rootDir ?: return false
        val gpu = snapshot.gpuUsagePercent
            ?.takeIf { it.isFinite() && it in 0f..100f }
            ?: return false
        val totalFrames = snapshot.totalFrameCount.coerceAtLeast(0)
        val slowRatio = if (totalFrames > 0) {
            snapshot.slowFrameCount.coerceIn(0, totalFrames).toDouble() /
                totalFrames.toDouble()
        } else {
            0.0
        }
        val text = buildString {
            appendLine("timestamp_ms=${snapshot.timestampMs}")
            appendLine("gpu_usage_percent=${String.format(Locale.US, "%.1f", gpu)}")
            snapshot.thermalStatus?.takeIf { it in 0..6 }?.let {
                appendLine("thermal_status=$it")
            }
            appendLine("source_fps=${String.format(Locale.US, "%.2f", snapshot.fps)}")
            appendLine("frame_time_p95_ms=${String.format(Locale.US, "%.2f", snapshot.frameTimeP95Ms)}")
            appendLine("slow_frame_ratio=${String.format(Locale.US, "%.4f", slowRatio)}")
        }
        val written = writeConfigAtomic(
            File(root, RUNTIME_PRESSURE_RELATIVE_PATH),
            text,
        )
        val nativeContainer = nativeRendererContainer
        val renderer = nativeRendererRef?.get()
        if (renderer != null && nativeContainer != null &&
            nativeContainer.rootDir.absolutePath == root.absolutePath &&
            latestNativeSnapshot?.backend == BACKEND_NATIVE
        ) {
            renderer.setFrameGenerationPressure(
                gpu,
                snapshot.thermalStatus ?: -1,
                snapshot.fps,
                readNativeOutputFps(nativeContainer) ?: 0f,
                snapshot.frameTimeP95Ms,
                slowRatio.toFloat(),
            )
        }
        return written
    }

    /**
     * Read the fps the layer actually presented, measured on-device.
     * Returns null when the stats file is missing or stale (layer not running),
     * in which case callers should fall back to their own estimate.
     */
    @JvmStatic
    @Volatile private var cachedMeasuredFps: Float? = null
    @Volatile private var lastStatsReadMs: Long = 0L
    @Volatile private var cachedMeasuredRoot: String? = null
    private val measuredFpsCacheLock = Any()

    /** Served from a cache refreshed off the main thread; callers poll ~1/s. */
    fun readMeasuredFps(container: Container): Float? = synchronized(measuredFpsCacheLock) {
        if (isNativeBackend(container)) return@synchronized readNativeOutputFps(container)
        val now = System.currentTimeMillis()
        val rootKey = runCatching { container.rootDir.canonicalPath }
            .getOrElse { container.rootDir.absolutePath }
        if (rootKey != cachedMeasuredRoot || now - lastStatsReadMs >= 500L) {
            val rootChanged = rootKey != cachedMeasuredRoot
            lastStatsReadMs = now
            cachedMeasuredRoot = rootKey
            if (rootChanged)
                cachedMeasuredFps = null
            val requestedRoot = container.rootDir
            statsReadExecutor.execute {
                val measured = try {
                    val statsFile = File(requestedRoot, STATS_RELATIVE_PATH)
                    if (statsFile.isFile &&
                        System.currentTimeMillis() - statsFile.lastModified() <= STATS_FRESHNESS_MS
                    ) {
                        statsFile.readLines()
                            .firstOrNull { it.startsWith("fps=") }
                            ?.substringAfter("fps=")
                            ?.toFloatOrNull()
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                }
                // A queued read for a previous container must not overwrite
                // the HUD value after the active container has changed.
                synchronized(measuredFpsCacheLock) {
                    if (cachedMeasuredRoot == rootKey) {
                        cachedMeasuredFps = measured
                    }
                }
            }
        }
        cachedMeasuredFps
    }

    /**
     * Returns a fresh cadence measured after LSFG presentation for power-control decisions.
     * This read is synchronous because its callers already run on one-second background loops.
     * A missing, stale, source-only, or not-yet-presented sample is deliberately unusable: an
     * active frame-generation session must never fall back to the lower source cadence.
     */
    fun readFreshOutputFps(rootDir: File?, nowMs: Long = System.currentTimeMillis()): Float? {
        val nativeContainer = nativeRendererContainer
        if (nativeContainer != null && isNativeBackend(nativeContainer) && rootDir == nativeContainer.rootDir) {
            return synchronized(measuredFpsCacheLock) {
                readNativeOutputFps(nativeContainer)?.takeIf { it > 0f }
            }
        }
        val statsFile = rootDir?.let { File(it, STATS_RELATIVE_PATH) } ?: return null
        if (!statsFile.isFile || nowMs - statsFile.lastModified() !in 0L..STATS_FRESHNESS_MS) {
            return null
        }

        return runCatching {
            val values = statsFile.useLines { lines ->
                lines.mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator) to line.substring(separator + 1)
                }.toMap()
            }
            val generating = values["state"] == "generating" &&
                values["active"] == "1" &&
                values["generated_presented"] == "1"
            if (!generating) return@runCatching null

            (values["output_fps"] ?: values["fps"])
                ?.toFloatOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
        }.getOrNull()
    }

    fun readRuntimeState(container: Container): RuntimeState {
        if (!isNativeBackend(container)) return readLegacyRuntimeState(container)
        val renderer = nativeRendererRef?.get() ?: return unknownRuntimeState(false)
        if (nativeRendererContainer !== container) return unknownRuntimeState(false)

        val initialized = renderer.isFrameGenerationSupported()
        val wsiGeneratedCount = renderer.getGeneratedPresentedFrameCount()
        val confirmedGeneratedCount =
            renderer.getGeneratedDisplayConfirmedFrameCount()
        val confirmationAvailable = renderer.isDisplayConfirmationAvailable()
        val snapshot = latestNativeSnapshot?.takeIf {
            nativeRendererContainer === container
        }
        val enabled = snapshot?.let {
            it.backend == BACKEND_NATIVE && it.enabled && it.multiplier >= 2
        } ?: (isArmed(container) && multiplier(container) >= 2)
        val failed =
            nativeApplyFailed ||
                (nativeApplyComplete && renderer.hasNativeSurface() &&
                    !initialized && enabled)

        val nowNs = System.nanoTime()
        if (nativeStateRenderer?.get() !== renderer ||
            wsiGeneratedCount < nativeStateSampleWsiGenerated ||
            confirmedGeneratedCount < nativeStateSampleConfirmedGenerated
        ) {
            nativeStateRenderer = WeakReference(renderer)
            nativeStateSampleNs = nowNs
            nativeStateSampleWsiGenerated = wsiGeneratedCount
            nativeStateSampleConfirmedGenerated = confirmedGeneratedCount
            nativeConfirmedDeliveryWindows = 0
            nativeDeliveryLossWindows = 0
        } else if (nativeStateSampleNs == 0L) {
            nativeStateSampleNs = nowNs
            nativeStateSampleWsiGenerated = wsiGeneratedCount
            nativeStateSampleConfirmedGenerated = confirmedGeneratedCount
        } else if (nowNs - nativeStateSampleNs >= 500_000_000L) {
            val wsiDelta =
                (wsiGeneratedCount - nativeStateSampleWsiGenerated)
                    .coerceAtLeast(0L)
            val confirmedDelta =
                (confirmedGeneratedCount - nativeStateSampleConfirmedGenerated)
                    .coerceAtLeast(0L)
            if (confirmationAvailable && wsiDelta > 0L && confirmedDelta > 0L) {
                nativeConfirmedDeliveryWindows =
                    (nativeConfirmedDeliveryWindows + 1).coerceAtMost(4)
                nativeDeliveryLossWindows =
                    (nativeDeliveryLossWindows - 1).coerceAtLeast(0)
            } else if (confirmationAvailable && wsiDelta >= 2L &&
                confirmedDelta == 0L
            ) {
                nativeDeliveryLossWindows =
                    (nativeDeliveryLossWindows + 1).coerceAtMost(4)
                nativeConfirmedDeliveryWindows = 0
            } else if (!confirmationAvailable) {
                nativeConfirmedDeliveryWindows = 0
                nativeDeliveryLossWindows = 0
            }
            nativeStateSampleNs = nowNs
            nativeStateSampleWsiGenerated = wsiGeneratedCount
            nativeStateSampleConfirmedGenerated = confirmedGeneratedCount
        }

        val wsiGenerating = initialized && wsiGeneratedCount > 0L
        val confirmedGenerating =
            initialized && confirmationAvailable &&
                nativeConfirmedDeliveryWindows >= 2
        val presentationDegraded =
            initialized && confirmationAvailable &&
                nativeDeliveryLossWindows >= 2 && wsiGenerating

        if (snapshot != null && snapshot.revision == nativeAppliedRevision) {
            when {
                presentationDegraded -> transitionNativePhase(
                    snapshot,
                    NativeBackendPhase.NATIVE_PRESENTATION_DEGRADED,
                    "generated-wsi-without-sustained-display-delivery",
                )
                confirmedGenerating -> transitionNativePhase(
                    snapshot,
                    NativeBackendPhase.NATIVE_GENERATING,
                    "sustained-generated-display-confirmation",
                )
                wsiGenerating && !confirmationAvailable -> transitionNativePhase(
                    snapshot,
                    NativeBackendPhase.NATIVE_CONFIRMATION_UNAVAILABLE,
                    "wsi-generating-display-confirmation-unavailable",
                )
                wsiGenerating -> transitionNativePhase(
                    snapshot,
                    NativeBackendPhase.NATIVE_WSI_GENERATING,
                    "generated-output-wsi-accepted-awaiting-confirmation",
                )
            }
        }

        return RuntimeState(
            status = when {
                !enabled -> RuntimeStatus.SOURCE_ONLY
                presentationDegraded -> RuntimeStatus.DEGRADED
                confirmedGenerating -> RuntimeStatus.GENERATING
                failed -> RuntimeStatus.DEGRADED
                else -> RuntimeStatus.UNKNOWN
            },
            resident = initialized || !enabled,
            sourceOnly = !enabled,
            generationReady = confirmedGenerating,
            generationInitialized = initialized,
            generatedPresented =
                if (confirmationAvailable)
                    confirmedGeneratedCount > 0L
                else
                    wsiGeneratedCount > 0L,
            degraded = enabled && (failed || presentationDegraded),
            multiplier =
                if (enabled) snapshot?.multiplier ?: multiplier(container)
                else 1,
            fresh = true,
            framegenSupportKnown = initialized || failed,
            framegenSupported = initialized,
            vulkanPath = "native-host-compositor",
            rejectionReason = when {
                presentationDegraded -> "native-presentation-degraded"
                failed -> nativeFailureReason ?: "native-initialization-failed"
                else -> null
            },
            wsiGenerating = wsiGenerating,
            displayConfirmedGenerating = confirmedGenerating,
            displayConfirmationAvailable = confirmationAvailable,
            presentationDegraded = presentationDegraded,
        )
    }

    private fun readLegacyRuntimeState(container: Container): RuntimeState {
        val statsFile = File(container.rootDir, STATS_RELATIVE_PATH)
        if (!statsFile.isFile) return unknownRuntimeState(fresh = false)
        val fresh = System.currentTimeMillis() - statsFile.lastModified() in 0L..STATS_FRESHNESS_MS
        if (!fresh) return unknownRuntimeState(fresh = false)

        return runCatching {
            val values = statsFile.readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            val degraded = values["degraded"] == "1"
            val sourceOnly = values["source_only"] == "1"
            val generationReady = values["generation_ready"] == "1"
            val resident = values["resident"] == "1" || values["active"] == "1" || sourceOnly
            val framegenSupportKnown = values["framegen_support_known"] == "1"
            val framegenSupported = values["framegen_supported"] == "1"
            val unsupported = framegenSupportKnown && !framegenSupported
            val status = if (degraded || unsupported) {
                RuntimeStatus.DEGRADED
            } else {
                when (values["state"]) {
                    "source_only" -> RuntimeStatus.SOURCE_ONLY
                    "generating" -> RuntimeStatus.GENERATING
                    "degraded" -> RuntimeStatus.DEGRADED
                    "pass_through" -> RuntimeStatus.PASS_THROUGH
                    else -> when {
                        generationReady -> RuntimeStatus.GENERATING
                        sourceOnly -> RuntimeStatus.SOURCE_ONLY
                        resident -> RuntimeStatus.PASS_THROUGH
                        else -> RuntimeStatus.UNKNOWN
                    }
                }
            }
            RuntimeState(
                status = status,
                resident = resident,
                sourceOnly = sourceOnly,
                generationReady = generationReady,
                generationInitialized = values["generation_initialized"] == "1",
                generatedPresented = values["generated_presented"] == "1",
                degraded = degraded,
                multiplier = values["multiplier"]?.toIntOrNull() ?: 0,
                fresh = true,
                framegenSupportKnown = framegenSupportKnown,
                framegenSupported = framegenSupported,
                vulkanPath = values["framegen_vulkan_path"]?.takeIf { it.isNotBlank() },
                spirvTarget = values["framegen_spirv_target"]?.takeIf { it.isNotBlank() },
                synchronizationPath = values["framegen_sync_path"]?.takeIf { it.isNotBlank() },
                ahbMode = values["framegen_ahb_mode"]?.takeIf { it.isNotBlank() },
                rejectionReason = values["framegen_rejection_reason"]?.takeIf { it.isNotBlank() },
            )
        }.getOrElse {
            unknownRuntimeState(fresh = false)
        }
    }

    private fun unknownRuntimeState(fresh: Boolean) = RuntimeState(
        status = RuntimeStatus.UNKNOWN,
        resident = false,
        sourceOnly = false,
        generationReady = false,
        generationInitialized = false,
        generatedPresented = false,
        degraded = false,
        multiplier = 0,
        fresh = fresh,
    )

    /**
     * Warm the native renderer shader cache off the launch-critical path.
     * Upstream 1.3 invokes this during Bionic startup; keep it best-effort so
     * cache generation can never prevent the LSFG Vulkan layer from launching.
     */
    @JvmStatic
    @Synchronized
    fun prepareNativeCache(context: Context, container: Container): String? {
        val dll = containerDllPath(container)?.let { File(it) } ?: findSteamDll()
        if (dll == null || !dll.isFile) return null
        return runCatching {
            val driverName = LosslessScaling.getDriverName(container)
            LosslessScaling.resolveOrBuildCache(context, dll, driverName)?.absolutePath
        }.onFailure { error ->
            Timber.e(
                error,
                "LSFG_NATIVE_CACHE: event=cache_build_failed stage=java-bridge reason=exception",
            )
        }.getOrNull()
    }

    // Native host-renderer bridge. Kept weak so the renderer lifecycle owns the
    // actual Vulkan context; runtime refreshes are best-effort.
    @Volatile private var nativeRendererRef: WeakReference<VulkanRenderer>? = null
    @Volatile private var nativeRendererContext: Context? = null
    @Volatile private var nativeRendererContainer: Container? = null
    @Volatile private var latestNativeSnapshot: NativeRuntimeConfigSnapshot? = null
    @Volatile private var nativeAppliedRevision = 0L
    @Volatile private var nativeBackendPhase = NativeBackendPhase.NATIVE_REQUESTED
    @Volatile private var nativeFailureReason: String? = null
    private var nativeStateSampleNs = 0L
    private var nativeStateSampleWsiGenerated = 0L
    private var nativeStateSampleConfirmedGenerated = 0L
    private var nativeConfirmedDeliveryWindows = 0
    private var nativeDeliveryLossWindows = 0
    private var nativeStateRenderer: WeakReference<VulkanRenderer>? = null
    private var nativeFpsSampleNs = 0L
    private var nativeFpsSampleCount = 0L
    private var nativeFpsRenderer: WeakReference<VulkanRenderer>? = null
    private var nativeFpsValue: Float? = null

    // Prefer physical display confirmation whenever the renderer exposes a
    // trustworthy asynchronous confirmation backend. WSI acceptance remains
    // available as pipeline telemetry and is only the rate fallback when
    // physical confirmation is unavailable on the device.
    private fun readNativeOutputFps(container: Container): Float? {
        val renderer = nativeRendererRef?.get() ?: return null
        if (nativeRendererContainer !== container ||
            !renderer.isFrameGenerationSupported()
        ) return null
        val confirmationAvailable = renderer.isDisplayConfirmationAvailable()
        val count = if (confirmationAvailable) {
            renderer.getDisplayConfirmedFrameCount()
        } else {
            renderer.getPresentedFrameCount()
        }
        val now = System.nanoTime()
        if (nativeFpsRenderer?.get() !== renderer ||
            count < nativeFpsSampleCount || nativeFpsSampleNs == 0L
        ) {
            nativeFpsRenderer = WeakReference(renderer)
            nativeFpsSampleCount = count
            nativeFpsSampleNs = now
            nativeFpsValue = null
            return null
        }
        val elapsed = now - nativeFpsSampleNs
        if (elapsed >= 500_000_000L) {
            nativeFpsValue =
                ((count - nativeFpsSampleCount) * 1_000_000_000.0 / elapsed)
                    .toFloat()
            nativeFpsSampleCount = count
            nativeFpsSampleNs = now
            Timber.d(
                "LSFG_NATIVE_PRESENT: event=output_rate output_fps=%.2f measurement=%s " +
                    "display_confirmation_available=%d",
                nativeFpsValue,
                if (confirmationAvailable) "display-confirmed"
                else "wsi-accepted-confirmation-unavailable",
                if (confirmationAvailable) 1 else 0,
            )
        }
        return nativeFpsValue
    }

    @Volatile private var nativeApplyFailed = false
    @Volatile private var nativeApplyComplete = false
    private val nativeApplyGeneration = AtomicLong()
    private val nativeApplyExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-native-apply").apply { isDaemon = true } }
    }

    private fun displayRefreshRate(context: Context): Float =
        runCatching {
            val display = context.getSystemService(Context.WINDOW_SERVICE)
                as? android.view.WindowManager
            display?.defaultDisplay?.refreshRate
        }.getOrNull()?.takeIf { it > 1f } ?: 60f

    private fun captureNativeRuntimeSnapshot(
        container: Container,
        context: Context,
    ): NativeRuntimeConfigSnapshot {
        val generationMode = generationMode(container)
        val enabled = isArmed(container) && multiplier(container) >= 2
        return NativeRuntimeConfigSnapshot(
            revision = nativeConfigRevision.incrementAndGet(),
            backendGeneration = backendRequestSerial.get(),
            backend = backend(container),
            enabled = enabled,
            generationMode = generationMode,
            multiplier = if (generationMode == MODE_ADAPTIVE) 4 else multiplier(container).coerceIn(2, 4),
            targetFps = if (generationMode == MODE_ADAPTIVE) adaptiveTargetFps(container) else 0,
            flowMode = flowScaleMode(container),
            flowPreset = adaptiveFlowPreset(container),
            requestedFlowScale = flowScale(container),
            performanceMode = performanceMode(container),
            presentMode = presentMode(container),
            frameQueueEnabled = frameQueueEnabled(container),
            frameQueueTarget = frameQueueTarget(container),
            displayRefresh = displayRefreshRate(context),
        )
    }

    private fun captureNativeRuntimeSnapshot(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
        adaptiveFramegen: Boolean,
        fpsLimit: Int,
        adaptiveFlowScale: Boolean,
        adaptiveFlowPreset: String,
        presentMode: String,
        frameQueueEnabled: Boolean,
        frameQueueTarget: Int,
        requestToken: RuntimeRequestToken = reserveRuntimeRequest(container),
    ): NativeRuntimeConfigSnapshot {
        val context = nativeRendererContext
        return NativeRuntimeConfigSnapshot(
            revision = requestToken.revision,
            backendGeneration = requestToken.backendGeneration,
            backend = requestToken.backend,
            enabled = enabled && multiplier >= 2,
            generationMode = if (adaptiveFramegen) MODE_ADAPTIVE else MODE_FIXED,
            multiplier = if (adaptiveFramegen) 4 else multiplier.coerceIn(2, 4),
            targetFps = if (adaptiveFramegen) sanitizeAdaptiveTargetFps(fpsLimit) else 0,
            flowMode = if (adaptiveFlowScale) FLOW_MODE_ADAPTIVE else FLOW_MODE_FIXED,
            flowPreset = sanitizeAdaptiveFlowPreset(adaptiveFlowPreset),
            requestedFlowScale = flowScale.coerceIn(0.25f, 1.0f),
            performanceMode = performanceMode,
            presentMode = presentMode.takeIf { it == "fifo" || it == "mailbox" } ?: "mailbox",
            frameQueueEnabled = frameQueueEnabled,
            frameQueueTarget = frameQueueTarget.coerceIn(0, 2),
            displayRefresh = context?.let(::displayRefreshRate) ?: 60f,
        )
    }

    private fun logNativeSnapshot(snapshot: NativeRuntimeConfigSnapshot, event: String) {
        val nativeEnabled =
            snapshot.backend == BACKEND_NATIVE && snapshot.enabled && snapshot.multiplier >= 2
        Timber.i(
            "LSFG_NATIVE_CONFIG: event=%s requested_revision=%d applied_revision=%d backend_generation=%d " +
                "backend=%s enabled=%d requested_enabled=%d generation_mode=%s multiplier=%d target_fps=%d " +
                "flow_mode=%s flow_preset=%s requested_scale=%.2f display_refresh=%.2f " +
                "present_policy=%s queue_policy=%s legacy_present_policy=%s " +
                "legacy_frame_queue_requested=%d legacy_queue_target=%d",
            event,
            snapshot.revision,
            nativeAppliedRevision,
            snapshot.backendGeneration,
            snapshot.backend,
            if (nativeEnabled) 1 else 0,
            if (snapshot.enabled) 1 else 0,
            snapshot.generationMode,
            snapshot.multiplier,
            snapshot.targetFps,
            snapshot.flowMode,
            snapshot.flowPreset,
            snapshot.requestedFlowScale,
            snapshot.displayRefresh,
            if (nativeEnabled) snapshot.presentMode else "disabled",
            if (nativeEnabled) "shared-host-frame-queue+native-admission" else "disabled",
            snapshot.presentMode,
            if (snapshot.frameQueueEnabled) 1 else 0,
            snapshot.frameQueueTarget,
        )
    }

    private fun transitionNativePhase(
        snapshot: NativeRuntimeConfigSnapshot,
        phase: NativeBackendPhase,
        reason: String,
    ) {
        if (snapshot.revision < nativeAppliedRevision) return
        nativeBackendPhase = phase
        Timber.i(
            "LSFG_NATIVE_STATE: state=%s reason=%s requested_revision=%d applied_revision=%d backend_generation=%d",
            phase.name,
            reason,
            snapshot.revision,
            nativeAppliedRevision,
            snapshot.backendGeneration,
        )
    }

    private fun snapshotIsCurrent(
        snapshot: NativeRuntimeConfigSnapshot,
        applyGeneration: Long,
    ): Boolean =
        applyGeneration == nativeApplyGeneration.get() &&
            snapshot.revision == nativeConfigRevision.get() &&
            snapshot.backendGeneration == backendRequestSerial.get()

    private fun discardStaleSnapshot(snapshot: NativeRuntimeConfigSnapshot, stage: String) {
        Timber.i(
            "LSFG_NATIVE_CONFIG: event=discarded_stale_revision requested_revision=%d applied_revision=%d " +
                "current_revision=%d backend_generation=%d current_backend_generation=%d stage=%s",
            snapshot.revision,
            nativeAppliedRevision,
            nativeConfigRevision.get(),
            snapshot.backendGeneration,
            backendRequestSerial.get(),
            stage,
        )
    }

    private fun isSnapshotRevisionCurrent(snapshot: NativeRuntimeConfigSnapshot): Boolean =
        snapshot.revision == nativeConfigRevision.get() &&
            snapshot.backendGeneration == backendRequestSerial.get()

    /**
     * Publish only the implicit-layer half of one captured runtime snapshot.
     * allowGeneration=false is the inter-backend safety barrier: the Legacy
     * layer remains resident but source-only until the current backend owner
     * has been safely retired.
     */
    private fun publishLegacyRuntimeConfig(
        container: Container,
        snapshot: NativeRuntimeConfigSnapshot,
        allowGeneration: Boolean,
        reason: String,
    ): Boolean {
        if (!isSupported(container)) return false
        return try {
            val dllPath = containerDllPath(container)
            val processExecutable = targetExecutable(container)
            val frameGenActive =
                allowGeneration &&
                    snapshot.backend == BACKEND_LEGACY &&
                    snapshot.enabled &&
                    snapshot.multiplier >= 2 &&
                    dllPath != null &&
                    processExecutable != null
            val adaptive = frameGenActive && snapshot.generationMode == MODE_ADAPTIVE
            val adaptiveFlow = frameGenActive && snapshot.flowMode == FLOW_MODE_ADAPTIVE
            val runtimeMultiplier =
                if (adaptive) 4 else snapshot.multiplier.coerceIn(2, 4)
            val configText = buildConfigToml(
                dllPath = dllPath,
                processExecutable = processExecutable,
                enabled = frameGenActive,
                multiplier = if (frameGenActive) runtimeMultiplier else 1,
                flowScale = snapshot.requestedFlowScale,
                adaptiveFlowScale = adaptiveFlow,
                adaptiveFlowPreset = snapshot.flowPreset,
                performanceMode = snapshot.performanceMode,
                adaptiveFramegen = adaptive,
                fpsLimit = if (adaptive) snapshot.targetFps else 0,
                presentMode = snapshot.presentMode,
                frameQueueEnabled = frameGenActive && snapshot.frameQueueEnabled,
                frameQueueTarget = snapshot.frameQueueTarget,
            )
            val ok = writeConfigAtomic(configFile(container), configText)
            Timber.i(
                "LSFG_LEGACY_CONFIG: event=legacy_layer_state state=%s reason=%s " +
                    "requested_revision=%d backend_generation=%d write_ok=%d enabled=%d " +
                    "multiplier=%d adaptive_framegen=%d target_fps=%d adaptive_flow=%d " +
                    "flow_preset=%s requested_scale=%.2f present_mode=%s frame_queue=%d " +
                    "frame_queue_target=%d",
                if (frameGenActive) "generating" else "source-only",
                reason,
                snapshot.revision,
                snapshot.backendGeneration,
                if (ok) 1 else 0,
                if (frameGenActive) 1 else 0,
                if (frameGenActive) runtimeMultiplier else 1,
                if (adaptive) 1 else 0,
                if (adaptive) snapshot.targetFps else 0,
                if (adaptiveFlow) 1 else 0,
                snapshot.flowPreset,
                snapshot.requestedFlowScale,
                snapshot.presentMode,
                if (frameGenActive && snapshot.frameQueueEnabled) 1 else 0,
                snapshot.frameQueueTarget,
            )
            ok
        } catch (error: Throwable) {
            Timber.e(
                error,
                "LSFG_LEGACY_CONFIG: event=legacy_layer_state state=unknown reason=%s " +
                    "requested_revision=%d backend_generation=%d",
                reason,
                snapshot.revision,
                snapshot.backendGeneration,
            )
            false
        }
    }

    /**
     * Publish the implicit layer's half of a Native handoff. This operation is
     * deliberately separate from Native renderer configuration: it can only
     * force the Legacy layer into source-only mode.
     */
    private fun publishLegacySourceOnlyForNative(
        container: Container,
        snapshot: NativeRuntimeConfigSnapshot,
    ): Boolean = publishLegacyRuntimeConfig(
        container = container,
        snapshot = snapshot,
        allowGeneration = false,
        reason = "native-backend",
    )

    /**
     * Push one immutable Native-LSFG settings snapshot into the host compositor.
     * Shader-cache construction is kept off the render/launch critical path.
     */
    private fun applyNativeRuntime(
        renderer: VulkanRenderer,
        container: Container,
        context: Context,
        snapshot: NativeRuntimeConfigSnapshot,
        onApplied: ((String) -> Unit)? = null,
    ) {
        nativeRendererRef = WeakReference(renderer)
        nativeRendererContext = context.applicationContext
        nativeRendererContainer = container
        latestNativeSnapshot = snapshot
        val generation = nativeApplyGeneration.incrementAndGet()
        nativeApplyFailed = false
        nativeApplyComplete = false
        nativeFailureReason = null
        logNativeSnapshot(snapshot, "requested")
        if (snapshot.backend == BACKEND_NATIVE && snapshot.enabled) {
            transitionNativePhase(snapshot, NativeBackendPhase.NATIVE_REQUESTED, "configuration-requested")
        }

        nativeApplyExecutor.execute {
            try {
                if (!snapshotIsCurrent(snapshot, generation)) {
                    discardStaleSnapshot(snapshot, "before-apply")
                    return@execute
                }
                val requested = snapshot.backend == BACKEND_NATIVE
                val enabled = requested && snapshot.enabled && snapshot.multiplier >= 2
                if (!requested || !enabled) {
                    renderer.setFrameGenerationEnabled(false)
                    renderer.setVkPresentMode(if (snapshot.presentMode == "mailbox") 1 else 2)
                    renderer.setLsfgFrameQueue(
                        !requested && snapshot.frameQueueEnabled && snapshot.enabled,
                        snapshot.frameQueueTarget,
                    )
                    if (!snapshotIsCurrent(snapshot, generation)) {
                        discardStaleSnapshot(snapshot, "source-only")
                        return@execute
                    }

                    // Native -> Legacy restoration must be owned by the newest
                    // current snapshot, not by a callback tied to an older
                    // revision. Rapid Quick Menu updates can supersede the
                    // transition snapshot after Native has already retired.
                    var result = "source-only-applied"
                    if (!requested && snapshot.backend == BACKEND_LEGACY) {
                        val restored = publishLegacyRuntimeConfig(
                            container,
                            snapshot,
                            allowGeneration = snapshot.enabled,
                            reason = "native-retired-current-snapshot",
                        )
                        result = if (restored) {
                            "legacy-restored"
                        } else {
                            "legacy-restore-failed"
                        }
                        Timber.i(
                            "LSFG_BACKEND: event=legacy_restore_after_native_retire " +
                                "requested_revision=%d backend_generation=%d enabled=%d restored=%d",
                            snapshot.revision,
                            snapshot.backendGeneration,
                            if (snapshot.enabled) 1 else 0,
                            if (restored) 1 else 0,
                        )
                    }

                    nativeApplyComplete = true
                    nativeAppliedRevision = snapshot.revision
                    logNativeSnapshot(snapshot, "applied")
                    onApplied?.invoke(result)
                    return@execute
                }

                transitionNativePhase(snapshot, NativeBackendPhase.NATIVE_INITIALIZING, "handoff-started")
                val legacyStateBefore = readLegacyRuntimeState(container)
                val backendRequest = latestBackendRequest?.takeIf {
                    it.serial == snapshot.backendGeneration && it.backend == BACKEND_NATIVE
                }
                val liveLegacyTransition =
                    backendRequest?.previousBackend == BACKEND_LEGACY
                val legacyAckRequired =
                    liveLegacyTransition ||
                        (legacyStateBefore.fresh &&
                            legacyStateBefore.resident &&
                            !legacyStateBefore.readyForSourceOnly)

                // Native compute cannot arm until the resident implicit layer has
                // been commanded source-only. This prevents double generation.
                if (!publishLegacySourceOnlyForNative(container, snapshot)) {
                    renderer.setFrameGenerationEnabled(false)
                    nativeApplyFailed = true
                    nativeApplyComplete = true
                    nativeFailureReason = "legacy-disable-failed"
                    transitionNativePhase(snapshot, NativeBackendPhase.NATIVE_FAILED, nativeFailureReason!!)
                    onApplied?.invoke(nativeFailureReason!!)
                    return@execute
                }

                val cache = prepareNativeCache(context.applicationContext, container)
                if (!snapshotIsCurrent(snapshot, generation)) {
                    discardStaleSnapshot(snapshot, "after-cache")
                    return@execute
                }
                if (cache == null) {
                    renderer.setFrameGenerationEnabled(false)
                    nativeApplyFailed = true
                    nativeApplyComplete = true
                    nativeFailureReason = "native-cache-unavailable"
                    transitionNativePhase(snapshot, NativeBackendPhase.NATIVE_FAILED, nativeFailureReason!!)
                    onApplied?.invoke(nativeFailureReason!!)
                    return@execute
                }

                val deadline = System.nanoTime() + 30_000_000_000L
                if (legacyAckRequired) {
                    Timber.i(
                        "LSFG_BACKEND: event=legacy_source_only_wait requested_revision=%d " +
                            "backend_generation=%d reason=%s legacy_fresh=%d legacy_resident=%d",
                        snapshot.revision,
                        snapshot.backendGeneration,
                        if (liveLegacyTransition) "live-backend-transition" else "observed-legacy-active",
                        if (legacyStateBefore.fresh) 1 else 0,
                        if (legacyStateBefore.resident) 1 else 0,
                    )
                    while (!readLegacyRuntimeState(container).readyForSourceOnly) {
                        if (!snapshotIsCurrent(snapshot, generation)) {
                            discardStaleSnapshot(snapshot, "legacy-ack")
                            return@execute
                        }
                        if (System.nanoTime() >= deadline) {
                            renderer.setFrameGenerationEnabled(false)
                            nativeApplyFailed = true
                            nativeApplyComplete = true
                            nativeFailureReason = "legacy-disable-timeout"
                            transitionNativePhase(snapshot, NativeBackendPhase.NATIVE_FAILED, nativeFailureReason!!)
                            onApplied?.invoke(nativeFailureReason!!)
                            return@execute
                        }
                        // Control-plane acknowledgement polling only; never used
                        // for render pacing or generated-frame scheduling.
                        Thread.sleep(100L)
                    }
                    Timber.i(
                        "LSFG_BACKEND: event=legacy_source_only_ack requested_revision=%d backend_generation=%d",
                        snapshot.revision,
                        snapshot.backendGeneration,
                    )
                }

                if (!snapshotIsCurrent(snapshot, generation)) {
                    discardStaleSnapshot(snapshot, "before-native-arm")
                    return@execute
                }

                // Native reuses the shared Vulkan present-mode selection path,
                // but timed synthetic delivery must prefer Mailbox. Android FIFO
                // vkQueuePresentKHR can block for multiple refresh periods and a
                // single serial presenter cannot sustain 2x/3x/4x output then.
                // nativeSetPresentMode already falls back to FIFO when Mailbox is
                // unsupported. The stored Legacy preference is left untouched and
                // is restored by the source-only/Legacy branch.
                renderer.setVkPresentMode(1)
                // Native stale-slot admission is an additional pre-acquire safety
                // guard, not a replacement Frame Queue implementation.
                renderer.setLsfgFrameQueue(
                    snapshot.frameQueueEnabled,
                    snapshot.frameQueueTarget,
                )
                val initialized = renderer.applyFrameGenerationSettings(
                    cache,
                    snapshot.multiplier,
                    snapshot.targetFps,
                    (snapshot.requestedFlowScale * 100f).toInt(),
                    if (snapshot.flowMode == FLOW_MODE_ADAPTIVE) {
                        VulkanRenderer.LSFG_FLOW_ADAPTIVE
                    } else {
                        VulkanRenderer.LSFG_FLOW_FIXED
                    },
                    when (snapshot.flowPreset) {
                        ADAPTIVE_FLOW_PRESET_BALANCED -> VulkanRenderer.LSFG_FLOW_PRESET_BALANCED
                        ADAPTIVE_FLOW_PRESET_LOW -> VulkanRenderer.LSFG_FLOW_PRESET_LOW
                        ADAPTIVE_FLOW_PRESET_AUTO -> VulkanRenderer.LSFG_FLOW_PRESET_AUTO
                        else -> VulkanRenderer.LSFG_FLOW_PRESET_QUALITY
                    },
                    snapshot.revision,
                    snapshot.displayRefresh,
                ) { snapshotIsCurrent(snapshot, generation) }

                if (!snapshotIsCurrent(snapshot, generation)) {
                    discardStaleSnapshot(snapshot, "after-native-arm")
                    return@execute
                }
                nativeApplyFailed = renderer.hasNativeSurface() && !initialized
                nativeApplyComplete = true
                nativeAppliedRevision = snapshot.revision
                nativeFailureReason = if (nativeApplyFailed) {
                    "native-capability-or-cache-rejected"
                } else null
                logNativeSnapshot(snapshot, "applied")
                when {
                    initialized -> transitionNativePhase(
                        snapshot,
                        NativeBackendPhase.NATIVE_READY,
                        "context-initialized",
                    )
                    nativeApplyFailed -> transitionNativePhase(
                        snapshot,
                        NativeBackendPhase.NATIVE_FAILED,
                        nativeFailureReason!!,
                    )
                    else -> transitionNativePhase(
                        snapshot,
                        NativeBackendPhase.NATIVE_INITIALIZING,
                        "native-surface-pending",
                    )
                }
                onApplied?.invoke(
                    when {
                        initialized -> "runtime-initialized"
                        nativeApplyFailed -> nativeFailureReason!!
                        else -> "native-surface-pending"
                    },
                )
            } catch (error: Exception) {
                if (snapshotIsCurrent(snapshot, generation)) {
                    nativeApplyFailed = true
                    nativeApplyComplete = true
                    nativeFailureReason = "native-initialization-failed"
                    transitionNativePhase(
                        snapshot,
                        NativeBackendPhase.NATIVE_FAILED,
                        nativeFailureReason!!,
                    )
                    Timber.e(
                        error,
                        "LSFG_NATIVE_STATE: event=runtime_initialization_failed requested_revision=%d backend_generation=%d",
                        snapshot.revision,
                        snapshot.backendGeneration,
                    )
                    onApplied?.invoke(nativeFailureReason!!)
                } else {
                    discardStaleSnapshot(snapshot, "exception-after-stale")
                }
            }
        }
    }

    @JvmStatic
    fun applyNativeRuntime(
        renderer: VulkanRenderer,
        container: Container,
        context: Context,
        onApplied: ((String) -> Unit)? = null,
    ) {
        val snapshot = captureNativeRuntimeSnapshot(container, context)
        applyNativeRuntime(renderer, container, context, snapshot, onApplied)
    }

    /** Re-apply native settings after runtime changes without retaining a strong renderer ref. */
    @JvmStatic
    fun refreshNativeRuntime(container: Container) {
        val renderer = nativeRendererRef?.get() ?: return
        val context = nativeRendererContext ?: return
        if (nativeRendererContainer !== container) return
        val snapshot = captureNativeRuntimeSnapshot(container, context)
        applyNativeRuntime(renderer, container, context, snapshot)
    }

    private fun refreshNativeRuntime(
        container: Container,
        snapshot: NativeRuntimeConfigSnapshot,
        onApplied: ((String) -> Unit)? = null,
    ) {
        val renderer = nativeRendererRef?.get() ?: run {
            onApplied?.invoke("native-renderer-absent")
            return
        }
        val context = nativeRendererContext ?: run {
            onApplied?.invoke("native-context-absent")
            return
        }
        if (nativeRendererContainer !== container) {
            onApplied?.invoke("native-container-mismatch")
            return
        }
        applyNativeRuntime(renderer, container, context, snapshot, onApplied)
    }

    /**
     * Install the layer runtime + DLL into the container's filesystem.
     * Called during container startup in BionicProgramLauncherComponent.
     *
     * Installs:
     * - liblsfg-vk-layer.so → ~/.local/lib/
     * - VkLayer_LS_frame_generation.json → ~/.local/share/vulkan/implicit_layer.d/
     * - Lossless.dll → ~/.local/share/lsfg-vk/  (copied from Steam install dir)
     */
    @JvmStatic
    fun ensureRuntimeInstalled(context: Context, container: Container): Boolean {
        if (!isSupported(container)) return false

        return synchronized(runtimeInstallLock) {
            ensureRuntimeInstalledLocked(context, container)
        }
    }

    private fun ensureRuntimeInstalledLocked(context: Context, container: Container): Boolean {

        val rootDir = container.rootDir
        val localLibDir = File(rootDir, LIB_RELATIVE_DIR)
        val layerDir = File(rootDir, LAYER_RELATIVE_DIR)
        val dllDir = File(rootDir, DLL_RELATIVE_DIR)
        val libFile = File(localLibDir, LIB_FILENAME)
        val manifestFile = File(layerDir, MANIFEST_FILENAME)
        val versionFile = File(layerDir, VERSION_FILENAME)
        val sourceLib = File(context.applicationInfo.nativeLibraryDir, LIB_FILENAME)

        if (!sourceLib.isFile) {
            Timber.tag(TAG).e("Native library not found: %s", sourceLib.absolutePath)
            return false
        }

        val expectedManifest = runCatching {
            context.assets.open(ASSET_MANIFEST)
                .bufferedReader().use { it.readText() }
                .replace(
                    "\"library_path\": \"$LIB_FILENAME\"",
                    "\"library_path\": \"$MANIFEST_LIBRARY_PATH\"",
                )
        }.getOrElse {
            Timber.tag(TAG).e(it, "LSFG layer manifest asset is unavailable")
            return false
        }

        val installedVersion = runCatching {
            versionFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        }.getOrDefault("")
        val installedManifest = runCatching {
            manifestFile.takeIf { it.isFile }?.readText().orEmpty()
        }.getOrDefault("")
        val needsInstall = installedVersion != RUNTIME_VERSION ||
            installedManifest != expectedManifest ||
            !filesHaveSameContents(sourceLib, libFile)

        var success = true

        if (needsInstall) {
            try {
                localLibDir.mkdirs()
                layerDir.mkdirs()

                if (!copyFileAtomic(sourceLib, libFile, 0b111101101))
                    throw IllegalStateException("Failed to publish LSFG native library")
                if (!writeTextAtomic(manifestFile, expectedManifest, 0b110100100))
                    throw IllegalStateException("Failed to publish LSFG layer manifest")
                // Publish the marker last. It is the commit record consumed by
                // provenance checks and must never describe a partially staged
                // library or manifest.
                if (!writeTextAtomic(versionFile, RUNTIME_VERSION, 0b110100100))
                    throw IllegalStateException("Failed to publish LSFG runtime marker")

                if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101)
                if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
                if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

                val ok = libFile.isFile &&
                    manifestFile.isFile &&
                    manifestFile.readText() == expectedManifest &&
                    versionFile.isFile &&
                    versionFile.readText().trim() == RUNTIME_VERSION &&
                    filesHaveSameContents(sourceLib, libFile)
                if (ok) {
                    Timber.tag(TAG).i("Installed LSFG runtime %s into %s", RUNTIME_VERSION, rootDir)
                } else {
                    Timber.tag(TAG).e("Runtime installation verification failed")
                    success = false
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to install LSFG runtime")
                success = false
            }
        } else {
            Timber.tag(TAG).d("Runtime %s already installed in %s", RUNTIME_VERSION, rootDir)
        }

        // Runtime installation must not delete or mutate unrelated containers.
        // Legacy cleanup is intentionally left to the normal container-management UI.
        val dllFile = File(dllDir, LOSSLESS_DLL_NAME)
        val steamDll = findSteamDll()
        if (steamDll != null) {
            try {
                if (!filesHaveSameContents(steamDll, dllFile)) {
                    dllDir.mkdirs()
                    if (!copyFileAtomic(steamDll, dllFile, 0b110100100))
                        throw IllegalStateException("Failed to publish Lossless.dll")
                    Timber.tag(TAG).i("Copied Lossless.dll (%d bytes) into %s", dllFile.length(), dllDir)
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to copy Lossless.dll into container")
                success = false
            }
        } else if (layerRequested(container)) {
            Timber.tag(TAG).w("LSFG layer requested but Lossless.dll not found in Steam dir")
            success = false
        }

        return success
    }

    @JvmStatic
    fun writeConfig(container: Container): Boolean {
        if (!isSupported(container)) return false

        return try {
            val dllPath = containerDllPath(container)
            val processExecutable = targetExecutable(container)
            val savedMultiplier = multiplier(container)
            // The implicit layer remains resident under Native so a later
            // Native -> Legacy handoff is cheap, but it must start source-only.
            // Native owns generation only after its separate handoff succeeds.
            val legacyGenerationAllowed = !isNativeBackend(container)
            val frameGenActive =
                legacyGenerationAllowed &&
                    frameGenerationActive(container) &&
                    processExecutable != null
            val adaptive = frameGenActive && generationMode(container) == MODE_ADAPTIVE
            val runtimeMultiplier = if (adaptive) 4 else savedMultiplier
            val adaptiveTarget = if (adaptive) adaptiveTargetFps(container) else 0
            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
            val configText = buildConfigToml(
                dllPath = dllPath,
                processExecutable = processExecutable,
                enabled = frameGenActive,
                multiplier = if (frameGenActive) runtimeMultiplier else 1,
                flowScale = flowScale(container),
                adaptiveFlowScale = flowScaleMode(container) == FLOW_MODE_ADAPTIVE,
                adaptiveFlowPreset = adaptiveFlowPreset(container),
                performanceMode = performanceMode(container),
                adaptiveFramegen = adaptive,
                fpsLimit = adaptiveTarget,
                presentMode = presentMode(container),
                frameQueueEnabled = frameQueueEnabled(container),
                frameQueueTarget = frameQueueTarget(container),
            )
            val ok = writeConfigAtomic(configFile, configText)
            Timber.i(
                "LSFG_LEGACY_CONFIG: event=legacy_layer_state state=%s reason=launch-config " +
                    "backend=%s enabled=%d multiplier=%d adaptive_framegen=%d target_fps=%d",
                if (frameGenActive) "generating" else "source-only",
                backend(container),
                if (frameGenActive) 1 else 0,
                if (frameGenActive) runtimeMultiplier else 1,
                if (adaptive) 1 else 0,
                if (adaptive) adaptiveTarget else 0,
            )
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write LSFG conf.toml")
            false
        }
    }

    /**
     * Apply LSFG-related environment variables to the launch environment.
     * Called during container startup in BionicProgramLauncherComponent.
     */
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean =
        applyLaunchEnv(
            container,
            envVars,
            protectedAdrenoPresentation = false,
        )

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun applyLaunchEnv(
        container: Container,
        envVars: EnvVars,
        protectedAdrenoPresentation: Boolean,
    ): Boolean =
        synchronized(runtimeInstallLock) {
            // The Adreno-specific FIFO override belonged to the discarded
            // deferred-single-queue experiment. The current Sept-18 execution
            // topology must preserve the renderer's process WSI policy so a
            // runtime LSFG Off toggle returns to the same source presentation
            // cadence the game launched with.
            applyLaunchEnvLocked(container, envVars)
        }

    private fun applyLaunchEnvLocked(
        container: Container,
        envVars: EnvVars,
    ): Boolean {
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)
        envVars.remove(ENV_PROCESS)
        envVars.remove(ENV_PROCESS_EXE)

        if (!isSupported(container) || !isFrameGenerationRequested(container)) {
            disableLayerForLaunch(container, envVars)
            Timber.tag(TAG).i(
                "LSFG layer disabled for launch (requested=%s, multiplier=%d)",
                container.getExtra(EXTRA_ARMED, "false"),
                multiplier(container),
            )
            return false
        }

        val dllPath = containerDllPath(container)
        val armed = isArmed(container)

        if (!armed) {
            disableLayerForLaunch(container, envVars)
            Timber.tag(TAG).i(
                "LSFG layer disabled (requested=%s, dll=%s)",
                container.getExtra(EXTRA_ARMED, "false"),
                dllPath ?: "null",
            )
            return false
        }

        val processExecutable = targetExecutable(container)
        if (processExecutable == null) {
            disableLayerForLaunch(container, envVars)
            Timber.tag(TAG).w("LSFG layer armed but target executable could not be resolved")
            return false
        }

        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        envVars.put(ENV_PROCESS_EXE, processExecutable)

        val loaderHomeConfigured = envVars[ENV_HOME]?.trim()?.isNotEmpty() == true
        val loaderLayerDir = if (loaderHomeConfigured) {
            synchronizeLoaderVisibleRuntime(container, envVars) ?: run {
                // A configured loader HOME can shadow the per-container layer.
                // Do not arm the process if that copy could not be published
                // and verified byte-for-byte.
                envVars.remove(ENV_CONFIG)
                envVars.remove(ENV_PROCESS_EXE)
                disableLayerForLaunch(container, envVars)
                Timber.tag(TAG).e("LSFG layer disabled: loader-visible runtime sync failed")
                return false
            }
        } else {
            File(container.rootDir, LAYER_RELATIVE_DIR)
        }
        appendUniqueEnvEntry(envVars, ENV_VK_LAYER_PATH, loaderLayerDir.absolutePath)
        appendUniqueEnvEntry(envVars, ENV_VK_INSTANCE_LAYERS, VULKAN_LAYER_NAME)

        Timber.tag(TAG).i(
            "LSFG layer armed target=%s multiplier=%d",
            processExecutable,
            multiplier(container),
        )
        return true
    }

    /**
     * Bionic launches with a loader HOME that may differ from the per-game
     * container root used by GameNative. Vulkan searches HOME implicit layers
     * before our explicit path and duplicate layer names are resolved by the
     * first manifest, so a stale base-HOME copy can shadow the verified runtime.
     * Keep the loader-visible copy byte-identical to the per-game source.
     */
    private fun synchronizeLoaderVisibleRuntime(container: Container, envVars: EnvVars): File? {
        val loaderHomePath = envVars[ENV_HOME]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val loaderHome = File(loaderHomePath)
        if (!loaderHome.isAbsolute) return null

        val sourceLib = File(container.rootDir, "$LIB_RELATIVE_DIR/$LIB_FILENAME")
        val sourceManifest = File(container.rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        if (!sourceLib.isFile || !sourceManifest.isFile) {
            Timber.tag(TAG).w(
                "LSFG loader runtime sync skipped: source runtime incomplete lib=%s manifest=%s",
                sourceLib.isFile,
                sourceManifest.isFile,
            )
            return null
        }

        val targetLib = File(loaderHome, "$LIB_RELATIVE_DIR/$LIB_FILENAME")
        val targetLayerDir = File(loaderHome, LAYER_RELATIVE_DIR)
        val targetManifest = File(targetLayerDir, MANIFEST_FILENAME)
        val targetVersion = File(targetLayerDir, VERSION_FILENAME)

        return synchronized(runtimeInstallLock) {
            try {
                val sourceLibCanonical = sourceLib.canonicalFile
                val targetLibCanonical = targetLib.canonicalFile
                val sourceManifestCanonical = sourceManifest.canonicalFile
                val targetManifestCanonical = targetManifest.canonicalFile

                targetLib.parentFile?.mkdirs()
                targetLayerDir.mkdirs()

                if (sourceLibCanonical != targetLibCanonical &&
                    !filesHaveSameContents(sourceLib, targetLib)
                ) {
                    if (!copyFileAtomic(sourceLib, targetLib, 0b111101101))
                        throw IllegalStateException("Failed to publish loader-visible LSFG library")
                }

                if (sourceManifestCanonical != targetManifestCanonical) {
                    val sourceText = sourceManifest.readText()
                    if (!targetManifest.isFile || targetManifest.readText() != sourceText) {
                        if (!writeTextAtomic(targetManifest, sourceText, 0b110100100))
                            throw IllegalStateException("Failed to publish loader-visible LSFG manifest")
                    }
                }
                if (!writeTextAtomic(targetVersion, RUNTIME_VERSION, 0b110100100))
                    throw IllegalStateException("Failed to publish loader-visible LSFG marker")

                if (targetLib.exists()) FileUtils.chmod(targetLib, 0b111101101)
                if (targetManifest.exists()) FileUtils.chmod(targetManifest, 0b110100100)
                if (targetVersion.exists()) FileUtils.chmod(targetVersion, 0b110100100)

                val verified = targetLib.isFile &&
                    targetManifest.isFile &&
                    targetManifest.readText() == sourceManifest.readText() &&
                    targetVersion.isFile &&
                    targetVersion.readText().trim() == RUNTIME_VERSION &&
                    filesHaveSameContents(sourceLib, targetLib)
                if (!verified) {
                    Timber.tag(TAG).e(
                        "LSFG loader runtime sync verification failed home=%s",
                        loaderHome.absolutePath,
                    )
                    null
                } else {
                    targetLayerDir
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to synchronize LSFG runtime into Vulkan loader HOME")
                null
            }
        }
    }


    private fun appendUniqueEnvEntry(envVars: EnvVars, key: String, value: String) {
        val current = envVars[key].orEmpty()
        val entries = current
            .split(':', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (entries.none { it == value }) {
            envVars.put(key, if (current.isBlank()) value else "$current:$value")
        }
    }

    private fun removeLsfgVulkanLayerActivation(envVars: EnvVars) {
        removeSeparatedEnvEntry(envVars, ENV_VK_INSTANCE_LAYERS, VULKAN_LAYER_NAME, ":")
        removeSeparatedEnvEntry(envVars, ENV_VK_LOADER_LAYERS_ENABLE, VULKAN_LAYER_NAME, ",")
    }

    private fun disableLayerForLaunch(container: Container, envVars: EnvVars) {
        // The loader-visible HOME copy uses a GLOBAL manifest. Removing only
        // the per-container manifest is therefore insufficient: a shared HOME
        // can still auto-discover the stale layer. Keep the manifest available
        // for other launches, but disable it for this process explicitly.
        envVars.put(ENV_DISABLE, "1")
        removeLsfgVulkanLayerActivation(envVars)
        disableLayerInContainer(container)
    }

    private fun removeSeparatedEnvEntry(envVars: EnvVars, key: String, value: String, separator: String) {
        val current = envVars[key].orEmpty()
        if (current.isBlank()) return
        val next = current
            .split(separator)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != value }
            .joinToString(separator)
        envVars.remove(key)
        if (next.isNotEmpty()) envVars.put(key, next)
    }

    private fun disableLayerInContainer(container: Container) {
        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val manifest = File(layerDir, MANIFEST_FILENAME)
        if (manifest.exists()) {
            manifest.delete()
            Timber.tag(TAG).d("Removed LSFG manifest to disable layer")
        }
    }

    // ---- DLL discovery -----------------------------------------------------

    private fun findSteamDll(): File? = findSteamDllDirect()

    private fun findSteamDllDirect(): File? {
        val appInfo = SteamService.getAppInfoOf(LOSSLESS_SCALING_APP_ID)
        val installDirName = appInfo?.installDir?.takeIf { it.isNotBlank() } ?: "Lossless Scaling"
        val searchPaths = SteamService.allInstallPaths

        for (basePath in searchPaths) {
            val appDir = File(basePath, installDirName)
            val dll = File(appDir, LOSSLESS_DLL_NAME)
            if (dll.isFile) {
                return dll
            }
        }

        for (basePath in searchPaths) {
            val baseDir = File(basePath)
            if (!baseDir.exists() || !baseDir.isDirectory) continue
            baseDir.listFiles()?.forEach { subDir ->
                if (subDir.isDirectory) {
                    val dll = File(subDir, LOSSLESS_DLL_NAME)
                    if (dll.isFile) {
                        return dll
                    }
                }
            }
        }

        Timber.tag(TAG).w("Lossless.dll not found in any Steam install path")
        return null
    }

    // ---- Helpers -----------------------------------------------------------

    private fun filesHaveSameContents(leftFile: File, rightFile: File): Boolean {
        if (!leftFile.isFile || !rightFile.isFile || leftFile.length() != rightFile.length()) {
            return false
        }

        return try {
            digestFile(leftFile).contentEquals(digestFile(rightFile))
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to compare LSFG runtime libraries")
            false
        }
    }

    private fun digestFile(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun moveIntoPlace(temp: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, REPLACE_EXISTING)
        }
    }

    private fun copyFileAtomic(source: File, target: File, mode: Int): Boolean {
        val parent = target.parentFile ?: return false
        var temp: java.nio.file.Path? = null
        return try {
            parent.mkdirs()
            temp = Files.createTempFile(parent.toPath(), ".${target.name}.", ".tmp")
            FileOutputStream(temp.toFile()).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            FileUtils.chmod(temp.toFile(), mode)
            moveIntoPlace(temp, target.toPath())
            temp = null
            FileUtils.chmod(target, mode)
            target.isFile
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to atomically publish %s", target.absolutePath)
            false
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun writeTextAtomic(target: File, text: String, mode: Int): Boolean {
        val parent = target.parentFile ?: return false
        var temp: java.nio.file.Path? = null
        return try {
            parent.mkdirs()
            temp = Files.createTempFile(parent.toPath(), ".${target.name}.", ".tmp")
            FileOutputStream(temp.toFile()).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            FileUtils.chmod(temp.toFile(), mode)
            moveIntoPlace(temp, target.toPath())
            temp = null
            FileUtils.chmod(target, mode)
            target.isFile
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to atomically publish %s", target.absolutePath)
            false
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    internal fun targetExecutable(container: Container): String? =
        container.executablePath
            .trim()
            .trim('"')
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotEmpty() }

    private val configWriteLock = Any()

    private fun writeConfigAtomic(file: File, text: String): Boolean = synchronized(configWriteLock) {
        val parent = file.parentFile ?: return@synchronized false
        val target = file.toPath()
        var temp: java.nio.file.Path? = null
        try {
            parent.mkdirs()
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(target) &&
                runCatching { file.readText() }.getOrNull() == text
            ) {
                return@synchronized true
            }

            temp = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp")
            temp.toFile().writeText(text)
            FileUtils.chmod(temp.toFile(), 0b110100100)
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
            temp = null
            FileUtils.chmod(file, 0b110100100)
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed to atomically publish LSFG conf.toml")
            false
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    @Suppress("unused")
    private fun buildConfigToml(
        dllPath: String?,
        processExecutable: String?,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        adaptiveFlowScale: Boolean,
        adaptiveFlowPreset: String,
        performanceMode: Boolean,
        adaptiveFramegen: Boolean,
        fpsLimit: Int,
        presentMode: String,
    ): String = buildConfigToml(
        dllPath = dllPath,
        processExecutable = processExecutable,
        enabled = enabled,
        multiplier = multiplier,
        flowScale = flowScale,
        adaptiveFlowScale = adaptiveFlowScale,
        adaptiveFlowPreset = adaptiveFlowPreset,
        performanceMode = performanceMode,
        adaptiveFramegen = adaptiveFramegen,
        fpsLimit = fpsLimit,
        presentMode = presentMode,
        frameQueueEnabled = false,
        frameQueueTarget = 0,
    )

    private fun buildConfigToml(
        dllPath: String?,
        processExecutable: String?,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        adaptiveFlowScale: Boolean,
        adaptiveFlowPreset: String,
        performanceMode: Boolean,
        adaptiveFramegen: Boolean,
        fpsLimit: Int,
        presentMode: String,
        frameQueueEnabled: Boolean,
        frameQueueTarget: Int,
    ): String = buildString {
        appendLine("version = 1")
        appendLine()
        appendLine("[global]")
        if (!dllPath.isNullOrBlank()) appendLine("dll = ${tomlString(dllPath)}")
        appendLine("no_fp16 = false")
        appendLine()

        if (!dllPath.isNullOrBlank() && !processExecutable.isNullOrBlank()) {
            val effectiveMultiplier = if (enabled) multiplier.coerceIn(2, 4) else 1
            val processNames = listOf(processExecutable, processExecutable.take(15)).distinct()
            processNames.forEach { processName ->
                appendLine("[[game]]")
                appendLine("exe = ${tomlString(processName)}")
                appendLine("multiplier = $effectiveMultiplier")
                appendLine("flow_scale = ${formatFlowScale(flowScale)}")
                appendLine("adaptive_flow_scale = ${if (adaptiveFlowScale) "true" else "false"}")
                appendLine("adaptive_flow_preset = ${tomlString(sanitizeAdaptiveFlowPreset(adaptiveFlowPreset))}")
                appendLine("performance_mode = ${if (performanceMode) "true" else "false"}")
                appendLine("hdr_mode = false")
                appendLine("adaptive_framegen = ${if (adaptiveFramegen) "true" else "false"}")
                appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")
                appendLine("frame_queue_enabled = ${if (enabled && frameQueueEnabled) "true" else "false"}")
                appendLine("frame_queue_target = ${frameQueueTarget.coerceIn(0, 2)}")
                appendLine("experimental_present_mode = ${tomlString(presentMode)}")
            }
        }
    }

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun formatFlowScale(value: Float): String =
        String.format(Locale.US, "%.2f", value.coerceIn(0.25f, 1.0f))

    private fun parseBool(value: String?): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"

    // ---- Runtime hot-reload -----------------------------------------------


    /**
     * Update conf.toml while the container is running. The layer observes the
     * timestamp change and recreates its swapchain context with the new values.
     * GameNative's real/source FPS limiter is intentionally not forwarded here.
     * In Adaptive mode fps_limit is the requested output target only.
     */
    @JvmStatic
    fun updateConfigAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
    ): Boolean {
        val adaptiveFramegen =
            enabled && multiplier >= 2 && generationMode(container) == MODE_ADAPTIVE
        return updateConfigAtRuntime(
            container = container,
            enabled = enabled,
            multiplier = multiplier,
            flowScale = flowScale,
            performanceMode = performanceMode,
            adaptiveFramegen = adaptiveFramegen,
            fpsLimit = if (adaptiveFramegen) adaptiveTargetFps(container) else 0,
            adaptiveFlowScale = flowScaleMode(container) == FLOW_MODE_ADAPTIVE,
            adaptiveFlowPreset = adaptiveFlowPreset(container),
            presentMode = presentMode(container),
            frameQueueEnabled = frameQueueEnabled(container),
            frameQueueTarget = frameQueueTarget(container),
        )
    }

    /**
     * ABI-compatible coherent runtime snapshot used by existing callers.
     * Frame Queue remains host-owned and is captured from persisted container
     * state without changing any generation-mode semantics.
     */
    @JvmStatic
    fun updateConfigAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
        adaptiveFramegen: Boolean,
        fpsLimit: Int,
        adaptiveFlowScale: Boolean,
        adaptiveFlowPreset: String,
        presentMode: String,
    ): Boolean = updateConfigAtRuntime(
        container = container,
        enabled = enabled,
        multiplier = multiplier,
        flowScale = flowScale,
        performanceMode = performanceMode,
        adaptiveFramegen = adaptiveFramegen,
        fpsLimit = fpsLimit,
        adaptiveFlowScale = adaptiveFlowScale,
        adaptiveFlowPreset = adaptiveFlowPreset,
        presentMode = presentMode,
        frameQueueEnabled = frameQueueEnabled(container),
        frameQueueTarget = frameQueueTarget(container),
    )

    /**
     * Publish one coherent LSFG runtime snapshot. Callers that already captured
     * Quick Menu state must use this overload so a debounced Flow update cannot
     * reread a newer/older frame-generation mode and silently change modes.
     */
    @JvmStatic
    @Synchronized
    fun updateConfigAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
        adaptiveFramegen: Boolean,
        fpsLimit: Int,
        adaptiveFlowScale: Boolean,
        adaptiveFlowPreset: String,
        presentMode: String,
        frameQueueEnabled: Boolean,
        frameQueueTarget: Int,
    ): Boolean = updateConfigAtRuntimeCaptured(
        container = container,
        enabled = enabled,
        multiplier = multiplier,
        flowScale = flowScale,
        performanceMode = performanceMode,
        adaptiveFramegen = adaptiveFramegen,
        fpsLimit = fpsLimit,
        adaptiveFlowScale = adaptiveFlowScale,
        adaptiveFlowPreset = adaptiveFlowPreset,
        presentMode = presentMode,
        frameQueueEnabled = frameQueueEnabled,
        frameQueueTarget = frameQueueTarget,
        requestToken = reserveRuntimeRequest(container),
    )

    @Synchronized
    internal fun updateConfigAtRuntimeCaptured(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
        adaptiveFramegen: Boolean,
        fpsLimit: Int,
        adaptiveFlowScale: Boolean,
        adaptiveFlowPreset: String,
        presentMode: String,
        frameQueueEnabled: Boolean,
        frameQueueTarget: Int,
        requestToken: RuntimeRequestToken,
    ): Boolean {
        if (!isSupported(container)) return false
        if (requestToken.revision != nativeConfigRevision.get()
            || requestToken.backendGeneration != backendRequestSerial.get()
        ) {
            Timber.i(
                "LSFG_NATIVE_CONFIG: event=discarded_stale_revision requested_revision=%d " +
                    "current_revision=%d backend_generation=%d current_backend_generation=%d " +
                    "stage=before-legacy-publication",
                requestToken.revision,
                nativeConfigRevision.get(),
                requestToken.backendGeneration,
                backendRequestSerial.get(),
            )
            return true
        }

        val snapshot = captureNativeRuntimeSnapshot(
            container = container,
            enabled = enabled,
            multiplier = multiplier,
            flowScale = flowScale,
            performanceMode = performanceMode,
            adaptiveFramegen = adaptiveFramegen,
            fpsLimit = fpsLimit,
            adaptiveFlowScale = adaptiveFlowScale,
            adaptiveFlowPreset = adaptiveFlowPreset,
            presentMode = presentMode,
            frameQueueEnabled = frameQueueEnabled,
            frameQueueTarget = frameQueueTarget,
            requestToken = requestToken,
        )
        if (!configFile(container).exists()) {
            Timber.tag(TAG).w("conf.toml not found, cannot hot-reload")
            return false
        }

        val rendererAttached =
            nativeRendererRef?.get() != null && nativeRendererContainer === container
        val nativeOwnerMayBeLive =
            snapshot.backend == BACKEND_LEGACY &&
                rendererAttached &&
                latestNativeSnapshot?.backend == BACKEND_NATIVE

        if (nativeOwnerMayBeLive) {
            // Transactional Native -> Legacy handoff:
            // keep Legacy source-only until Native stops admission, drains its
            // owned GPU work and disables generation.
            if (!publishLegacyRuntimeConfig(
                    container,
                    snapshot,
                    allowGeneration = false,
                    reason = "native-to-legacy-handoff",
                )
            ) {
                return false
            }
            Timber.i(
                "LSFG_BACKEND: event=handoff_start direction=native-to-legacy " +
                    "requested_revision=%d backend_generation=%d",
                snapshot.revision,
                snapshot.backendGeneration,
            )
            refreshNativeRuntime(container, snapshot) { result ->
                if ((result == "source-only-applied" || result == "legacy-restored")
                    && isSnapshotRevisionCurrent(snapshot)) {
                    val restored = publishLegacyRuntimeConfig(
                        container,
                        snapshot,
                        allowGeneration = true,
                        reason = "native-retired",
                    )
                    Timber.i(
                        "LSFG_BACKEND: event=handoff_complete direction=native-to-legacy " +
                            "requested_revision=%d backend_generation=%d legacy_restored=%d",
                        snapshot.revision,
                        snapshot.backendGeneration,
                        if (restored) 1 else 0,
                    )
                } else {
                    Timber.w(
                        "LSFG_BACKEND: event=handoff_abort direction=native-to-legacy " +
                            "requested_revision=%d backend_generation=%d result=%s current=%d",
                        snapshot.revision,
                        snapshot.backendGeneration,
                        result,
                        if (isSnapshotRevisionCurrent(snapshot)) 1 else 0,
                    )
                }
            }
            return true
        }

        val allowLegacyGeneration = snapshot.backend == BACKEND_LEGACY
        val published = publishLegacyRuntimeConfig(
            container,
            snapshot,
            allowGeneration = allowLegacyGeneration,
            reason = if (snapshot.backend == BACKEND_NATIVE) {
                "native-backend"
            } else {
                "runtime-config"
            },
        )
        if (published) {
            // Native sees the identical immutable snapshot. For Native
            // activation this occurs only after Legacy has been made source-only.
            refreshNativeRuntime(container, snapshot)
        }
        return published
    }
}
