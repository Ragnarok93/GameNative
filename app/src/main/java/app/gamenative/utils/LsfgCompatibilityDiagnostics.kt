package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Read-only LSFG compatibility diagnostics.
 *
 * This object intentionally does not mutate container state, launch environment,
 * manifests, configuration, or Vulkan ownership. It only inspects files and
 * recent log evidence so field reports can identify the boundary where LSFG stops.
 */
object LsfgCompatibilityDiagnostics {
    private const val LAYER_NAME = "VK_LAYER_LS_frame_generation"
    private const val PROCESS_ID = "gamenative-lsfg"
    private const val EXPECTED_MANIFEST_API = "1.3.0"
    private const val LAYER_LIBRARY_RELATIVE = ".local/lib/liblsfg-vk-layer.so"
    private const val MANIFEST_RELATIVE =
        ".local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json"
    private const val DLL_RELATIVE = ".local/share/lsfg-vk/Lossless.dll"
    private const val CONFIG_RELATIVE = ".config/lsfg-vk/conf.toml"
    private const val VSYNC_RELATIVE = ".config/lsfg-vk/vsync.txt"
    private const val STATS_RELATIVE = ".config/lsfg-vk/stats.txt"
    private const val STATS_FRESHNESS_MS = 2_000L
    private const val VSYNC_FRESHNESS_MS = 3_500L
    private const val LOGCAT_LINES = 3_000
    private const val MAX_RELEVANT_LOG_LINES = 700

    enum class Status { PASS, WARN, FAIL, SKIP, INFO }

    data class Check(
        val id: String,
        val status: Status,
        val detail: String,
    )

    data class ContainerSnapshot(
        val containerId: String,
        val checks: List<Check>,
        val nextFocus: String,
    ) {
        fun check(id: String): Check? = checks.firstOrNull { it.id == id }
    }

    data class Report(
        val generatedAtMs: Long,
        val activeHome: String?,
        val logCaptureDetail: String,
        val containers: List<ContainerSnapshot>,
        val relevantLogs: String,
    ) {
        fun toText(): String = buildString {
            appendLine("GameNative LSFG debug compatibility report")
            appendLine("generated_at_ms=$generatedAtMs")
            appendLine("active_home=${activeHome ?: "unavailable"}")
            appendLine("log_capture=$logCaptureDetail")
            appendLine()
            if (containers.isEmpty()) {
                appendLine("No containers found.")
            }
            containers.forEach { snapshot ->
                appendLine("=== container ${snapshot.containerId} ===")
                appendLine("next_focus=${snapshot.nextFocus}")
                snapshot.checks.forEach { check ->
                    appendLine("[${check.status}] ${check.id}: ${check.detail}")
                }
                appendLine()
            }
            appendLine("=== relevant recent logs ===")
            appendLine(if (relevantLogs.isBlank()) "<none captured>" else relevantLogs.trimEnd())
        }
    }

    /**
     * Run the suite against every configured container. File/log work belongs on
     * a background dispatcher; callers must not invoke this from the UI thread.
     */
    fun run(context: Context, nowMs: Long = System.currentTimeMillis()): Report {
        val appContext = context.applicationContext
        val activeHome = runCatching { File(ImageFs.find(appContext).home_path).canonicalFile }.getOrNull()
        val (logs, logDetail) = captureRelevantLogs()
        val containers = runCatching { ContainerManager(appContext).containers.toList() }
            .getOrDefault(emptyList())
            .map { inspectContainer(it, activeHome, nowMs, logs) }
        return Report(
            generatedAtMs = nowMs,
            activeHome = activeHome?.absolutePath,
            logCaptureDetail = logDetail,
            containers = containers,
            relevantLogs = logs,
        )
    }

    fun writeReport(context: Context, report: Report): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(report.generatedAtMs))
        return File(dir, "lsfg_compat_$stamp.txt").apply { writeText(report.toText()) }
    }

    internal fun inspectContainer(
        container: Container,
        activeHome: File?,
        nowMs: Long,
        logs: String,
    ): ContainerSnapshot {
        val root = container.rootDir
        val checks = mutableListOf<Check>()
        val isBionic = container.containerVariant.equals(Container.BIONIC, ignoreCase = true)
        val armed = parseBool(container.getExtra(LsfgVkManager.EXTRA_ARMED, "false"))

        checks += Check(
            "container_variant",
            if (isBionic) Status.PASS else Status.FAIL,
            "variant=${container.containerVariant}; LSFG is supported only for bionic containers",
        )
        checks += Check(
            "lsfg_armed",
            if (armed) Status.PASS else Status.WARN,
            "enabled=${container.getExtra(LsfgVkManager.EXTRA_ARMED, "false")}",
        )

        val layerLibrary = File(root, LAYER_LIBRARY_RELATIVE)
        checks += fileCheck("runtime_library", layerLibrary)

        val manifest = File(root, MANIFEST_RELATIVE)
        checks += manifestCheck(manifest, layerLibrary)

        val dll = File(root, DLL_RELATIVE)
        checks += fileCheck("lossless_dll", dll)

        val config = File(root, CONFIG_RELATIVE)
        checks += configCheck(config, dll, armed, targetExecutable(container))

        val rootCanonical = runCatching { root.canonicalFile }.getOrNull()
        val activeCanonical = runCatching { activeHome?.canonicalFile }.getOrNull()
        val isActive = rootCanonical != null && activeCanonical != null && rootCanonical == activeCanonical
        checks += Check(
            "active_home",
            if (isActive) Status.PASS else Status.WARN,
            when {
                activeCanonical == null -> "active HOME could not be resolved"
                rootCanonical == null -> "container root could not be resolved"
                else -> "container=${rootCanonical.absolutePath}; active=${activeCanonical.absolutePath}"
            },
        )

        val layerLog = layerLogCheck(logs)
        checks += layerLog
        val nativeEntry = nativeFramegenEntryCheck(logs)
        checks += nativeEntry
        val featureCheck = framegenFeatureCheck(logs)
        checks += featureCheck
        val fatalCheck = framegenFatalCheck(logs)
        checks += fatalCheck

        val vsyncCheck = if (!isActive || !armed) {
            Check("vsync_fresh", Status.SKIP, "live check requires the armed container to be active")
        } else {
            freshnessCheck(
                id = "vsync_fresh",
                file = File(root, VSYNC_RELATIVE),
                nowMs = nowMs,
                maxAgeMs = VSYNC_FRESHNESS_MS,
                requiredPrefix = "period_ns=",
            )
        }
        checks += vsyncCheck

        val statsCheck = if (!isActive || !armed) {
            Check("stats_fresh", Status.SKIP, "live check requires the armed container to be active")
        } else {
            statsCheck(File(root, STATS_RELATIVE), nowMs)
        }
        checks += statsCheck

        val exynosEvidence = when {
            logs.contains("ExynosToolsShim", ignoreCase = true) ||
                logs.contains("VortekXclipse", ignoreCase = true) ->
                Check("exynos_driver_evidence", Status.PASS, "recent ExynosTools/Xclipse driver evidence found")
            logs.isBlank() -> Check("exynos_driver_evidence", Status.INFO, "no recent log evidence available")
            else -> Check("exynos_driver_evidence", Status.INFO, "no ExynosTools marker in captured log window")
        }
        checks += exynosEvidence

        val nextFocus = when {
            !isBionic -> "CONTAINER_VARIANT"
            !armed -> "ENABLE_LSFG"
            checks.failed("runtime_library") -> "RUNTIME_PACKAGE"
            checks.failed("manifest_contract") -> "MANIFEST_CONTRACT"
            checks.failed("lossless_dll") -> "LOSSLESS_DLL"
            checks.failed("config_contract") -> "CONFIG_CONTRACT"
            !isActive -> "ACTIVE_CONTAINER"
            fatalCheck.status == Status.FAIL || featureCheck.status == Status.FAIL -> "FRAMEGEN_DEVICE_CAPABILITIES"
            statsCheck.status == Status.FAIL && layerLog.status != Status.PASS -> "LAYER_DISCOVERY"
            statsCheck.status == Status.FAIL && nativeEntry.status != Status.PASS -> "FRAMEGEN_INIT"
            vsyncCheck.status == Status.FAIL -> "VSYNC_CLOCK"
            statsCheck.status == Status.FAIL -> "PRESENTATION_STATS"
            else -> "NONE"
        }

        return ContainerSnapshot(container.id, checks, nextFocus)
    }

    private fun fileCheck(id: String, file: File): Check = when {
        !file.isFile -> Check(id, Status.FAIL, "missing: ${file.absolutePath}")
        file.length() <= 0L -> Check(id, Status.FAIL, "empty file: ${file.absolutePath}")
        else -> Check(id, Status.PASS, "${file.absolutePath} (${file.length()} bytes)")
    }

    private fun manifestCheck(manifest: File, expectedLibrary: File): Check {
        if (!manifest.isFile) return Check("manifest_contract", Status.FAIL, "missing: ${manifest.absolutePath}")
        return runCatching {
            val layer = JSONObject(manifest.readText()).getJSONObject("layer")
            val libraryPath = layer.getString("library_path")
            val resolvedLibrary = File(manifest.parentFile, libraryPath).canonicalFile
            val problems = buildList {
                if (layer.optString("name") != LAYER_NAME) add("name=${layer.optString("name")}")
                if (layer.optString("type") != "GLOBAL") add("type=${layer.optString("type")}")
                if (layer.optString("api_version") != EXPECTED_MANIFEST_API) {
                    add("api_version=${layer.optString("api_version")}")
                }
                if (resolvedLibrary != expectedLibrary.canonicalFile) add("library_path resolves to $resolvedLibrary")
                if (layer.has("enable_environment")) {
                    add("enable_environment must be absent; LSFG activation is config/process targeted")
                }
                if (layer.optJSONObject("disable_environment")?.optString("DISABLE_LSFG") != "1") {
                    add("disable_environment.DISABLE_LSFG mismatch")
                }
            }
            if (problems.isEmpty()) {
                Check("manifest_contract", Status.PASS, "implicit layer contract valid; library=$resolvedLibrary")
            } else {
                Check("manifest_contract", Status.FAIL, problems.joinToString("; "))
            }
        }.getOrElse { Check("manifest_contract", Status.FAIL, "manifest parse failed: ${it.message}") }
    }

    private fun configCheck(
        config: File,
        dll: File,
        armed: Boolean,
        expectedExecutable: String?,
    ): Check {
        if (!config.isFile) return Check("config_contract", Status.FAIL, "missing: ${config.absolutePath}")
        return runCatching {
            val text = config.readText()
            val dllValue = tomlString(text, "dll")
            val exe = tomlString(text, "exe")
            val multiplier = tomlInt(text, "multiplier")
            val fpsLimit = tomlInt(text, "fps_limit")
            val flowScale = tomlNumber(text, "flow_scale")
            val presentMode = tomlString(text, "experimental_present_mode")
            val problems = buildList {
                if (dllValue == null || runCatching { File(dllValue).canonicalFile }.getOrNull() != dll.canonicalFile) {
                    add("dll path does not resolve to copied Lossless.dll")
                }
                if (expectedExecutable == null) add("container executable is unresolved")
                else if (!exe.equals(expectedExecutable, ignoreCase = true)) {
                    add("exe=$exe expected=$expectedExecutable")
                }
                if (armed && (multiplier == null || multiplier < 2)) add("multiplier=$multiplier while LSFG is armed")
            }
            val detail = "dll=${dllValue ?: "missing"}, exe=${exe ?: "missing"}, multiplier=${multiplier ?: "missing"}, " +
                "flow_scale=${flowScale ?: "missing"}, fps_limit=${fpsLimit ?: "missing"}, present_mode=${presentMode ?: "missing"}"
            if (problems.isEmpty()) Check("config_contract", Status.PASS, detail)
            else Check("config_contract", Status.FAIL, problems.joinToString("; ") + "; $detail")
        }.getOrElse { Check("config_contract", Status.FAIL, "config read failed: ${it.message}") }
    }

    private fun layerLogCheck(logs: String): Check {
        if (logs.isBlank()) return Check("layer_log_evidence", Status.WARN, "no relevant logs captured")
        // Install/chmod/path messages mentioning the .so are not attachment proof.
        val found = logs.lineSequence().any { line ->
            line.contains("lsfg-vk:", ignoreCase = true) ||
                line.contains("lsfg-vk-framegen", ignoreCase = true)
        }
        return if (found) Check("layer_log_evidence", Status.PASS, "native LSFG layer output found in recent logs")
        else Check("layer_log_evidence", Status.WARN, "no native lsfg-vk runtime output in captured log window")
    }

    private fun nativeFramegenEntryCheck(logs: String): Check {
        val found = logs.contains("Entering Device::Device", ignoreCase = true) ||
            logs.contains("framegen build stamp", ignoreCase = true)
        return when {
            found -> Check("native_framegen_entry", Status.PASS, "native framegen Device::Device entry observed")
            logs.isBlank() -> Check("native_framegen_entry", Status.WARN, "no relevant logs captured")
            else -> Check("native_framegen_entry", Status.WARN, "layer evidence may exist, but Device::Device entry was not observed")
        }
    }

    private fun framegenFeatureCheck(logs: String): Check {
        val line = logs.lineSequence().lastOrNull { it.contains("Device features probe:") }
            ?: return Check("framegen_device_features", Status.WARN, "native device feature probe not found in captured logs")
        fun value(name: String): Int? = Regex("${Regex.escape(name)}=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        val extended = value("storageImageExtendedFormats")
        val readWithoutFormat = value("storageImageReadWithoutFormat")
        val writeWithoutFormat = value("storageImageWriteWithoutFormat")
        val hardMissing = listOf(
            "storageImageExtendedFormats" to extended,
            "storageImageReadWithoutFormat" to readWithoutFormat,
            "storageImageWriteWithoutFormat" to writeWithoutFormat,
        ).filter { it.second == 0 }.map { it.first }
        return if (hardMissing.isNotEmpty()) {
            Check("framegen_device_features", Status.FAIL, "required storage-image features missing: ${hardMissing.joinToString()}; $line")
        } else {
            Check("framegen_device_features", Status.PASS, line.substringAfter("Device features probe:").trim())
        }
    }

    private fun framegenFatalCheck(logs: String): Check {
        val patterns = listOf(
            "Missing required device extension:",
            "Could not find physical device with UUID",
            "No compute queue family found",
            "VK_KHR_timeline_semaphore not available",
            "VK_ERROR_DEVICE_LOST",
            "DEVICE_LOST",
        )
        val hit = logs.lineSequence().lastOrNull { line -> patterns.any { line.contains(it, ignoreCase = true) } }
        return when {
            hit != null -> Check("framegen_fatal_error", Status.FAIL, hit.trim())
            logs.isBlank() -> Check("framegen_fatal_error", Status.WARN, "no relevant logs captured")
            else -> Check("framegen_fatal_error", Status.PASS, "no known framegen fatal marker in captured log window")
        }
    }

    private fun freshnessCheck(
        id: String,
        file: File,
        nowMs: Long,
        maxAgeMs: Long,
        requiredPrefix: String,
    ): Check {
        if (!file.isFile) return Check(id, Status.FAIL, "missing: ${file.absolutePath}")
        val age = (nowMs - file.lastModified()).coerceAtLeast(0L)
        val hasRequiredLine = runCatching { file.readLines().any { it.startsWith(requiredPrefix) } }.getOrDefault(false)
        return if (age <= maxAgeMs && hasRequiredLine) {
            Check(id, Status.PASS, "age_ms=$age; ${file.readLines().firstOrNull { it.startsWith(requiredPrefix) } ?: ""}")
        } else {
            Check(id, Status.FAIL, "age_ms=$age (max=$maxAgeMs); expected $requiredPrefix")
        }
    }

    private fun statsCheck(file: File, nowMs: Long): Check {
        if (!file.isFile) return Check("stats_fresh", Status.FAIL, "missing: ${file.absolutePath}")
        val age = (nowMs - file.lastModified()).coerceAtLeast(0L)
        val fps = runCatching {
            file.readLines().firstOrNull { it.startsWith("fps=") }?.substringAfter("fps=")?.toFloatOrNull()
        }.getOrNull()
        return if (age <= STATS_FRESHNESS_MS && fps != null && fps > 0f) {
            Check("stats_fresh", Status.PASS, "age_ms=$age; fps=$fps")
        } else {
            Check("stats_fresh", Status.FAIL, "age_ms=$age (max=$STATS_FRESHNESS_MS); fps=${fps ?: "missing"}")
        }
    }

    private fun captureRelevantLogs(): Pair<String, String> {
        var process: Process? = null
        return try {
            process = ProcessBuilder("logcat", "-d", "-t", LOGCAT_LINES.toString())
                .redirectErrorStream(true)
                .start()
            val all = process.inputStream.bufferedReader().use { it.readLines() }
            val keywords = listOf(
                "lsfg", LAYER_NAME, "liblsfg-vk-layer.so", "Device features probe",
                "DEVICE_LOST", "VK_ERROR", "ExynosTools", "VortekXclipse",
                "vkCreateDevice", "DXVK", "Winlator_Renderer", "AndroidRuntime",
                "FATAL EXCEPTION", "Fatal signal", "SIGSEGV", "SIGABRT", "has died", "tombstone",
            )
            val relevant = all.asSequence()
                .filter { line -> keywords.any { line.contains(it, ignoreCase = true) } }
                .takeLastCompat(MAX_RELEVANT_LOG_LINES)
                .joinToString("\n")
            relevant to "captured ${all.size} lines; retained ${if (relevant.isBlank()) 0 else relevant.lineSequence().count()} relevant lines"
        } catch (t: Throwable) {
            "" to "logcat capture unavailable: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            process?.destroy()
        }
    }

    private fun Sequence<String>.takeLastCompat(count: Int): List<String> {
        val buffer = ArrayDeque<String>(count)
        for (line in this) {
            if (buffer.size == count) buffer.removeFirst()
            buffer.addLast(line)
        }
        return buffer.toList()
    }

    private fun targetExecutable(container: Container): String? =
        container.executablePath
            .trim()
            .trim('"')
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun MutableList<Check>.failed(id: String): Boolean =
        firstOrNull { it.id == id }?.status == Status.FAIL

    private fun tomlString(text: String, key: String): String? =
        Regex("(?m)^\\s*${Regex.escape(key)}\\s*=\\s*\"([^\"]*)\"\\s*$")
            .find(text)?.groupValues?.get(1)

    private fun tomlInt(text: String, key: String): Int? =
        Regex("(?m)^\\s*${Regex.escape(key)}\\s*=\\s*(-?\\d+)\\s*$")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun tomlNumber(text: String, key: String): String? =
        Regex("(?m)^\\s*${Regex.escape(key)}\\s*=\\s*([0-9.]+)\\s*$")
            .find(text)?.groupValues?.get(1)

    private fun parseBool(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"
}
