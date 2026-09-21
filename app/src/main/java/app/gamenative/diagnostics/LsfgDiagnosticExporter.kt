package app.gamenative.diagnostics

import android.content.Context
import android.os.Build
import app.gamenative.CrashHandler
import app.gamenative.powercontrol.PowerBaselineScripts
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.metrics.PerformanceMetricsCollector
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the single-file, best-effort LSFG diagnostic report exported from Debug settings.
 *
 * This exporter is intentionally independent from Adaptive Frame Generation lifecycle state. It
 * discovers the fixed-mode runtime artifacts at export time so diagnostics remain available for
 * multiplier 2x/3x/4x and for the disabled-path baseline.
 */
object LsfgDiagnosticExporter {
    private const val TEXT_TAIL_BYTES = 2L * 1024L * 1024L
    private const val NATIVE_EVENT_TAIL_BYTES = 4L * 1024L * 1024L
    private const val LOGCAT_LINES = 8_000
    private const val UID_LOGCAT_LINES = 12_000
    private const val PRESENTATION_LOG_LINES = 4_000
    private const val MAX_SCAN_DEPTH = 8
    private const val MAX_SCAN_NODES = 4_000

    private val graphicsEnvironmentKeys = listOf(
        "VK_ICD_FILENAMES",
        "ADRENOTOOLS_DRIVER_NAME",
        "ADRENOTOOLS_DRIVER_PATH",
        "VK_LAYER_PATH",
        "VK_INSTANCE_LAYERS",
        "VK_LOADER_LAYERS_ENABLE",
        "VK_LOADER_DEBUG",
        "LSFG_CONFIG",
        "LSFG_PROCESS_EXE",
        "LD_LIBRARY_PATH",
    )

    private val presentationKeywords = listOf(
        "lsfg",
        "vulkan",
        "vkqueuepresentkhr",
        "present",
        "swapchain",
        "ahardwarebuffer",
        " ahb",
        "fence",
        "semaphore",
        "sync",
        "acquire",
        "generated",
        "framegen",
        "frame gen",
        "multiplier",
        "image",
        "black",
        "error",
        "warning",
        "failed",
    )

    fun defaultFileName(now: Date = Date()): String =
        "gamenative-lsfg-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)}.txt"

    private val runtimeSessionPattern = Regex("""\bruntime_session_id=(\d+)\b""")
    private val configRevisionPattern = Regex("""\bconfig_revision=(\d+)\b""")

    /**
     * Group same-UID native LSFG logcat by runtime session and configuration epoch.
     * Legacy lines remain visible in an explicit unsegmented bucket instead of
     * being discarded.
     */
    internal fun segmentNativeEvents(nativeLogcat: String): String {
        if (nativeLogcat.isBlank()) return ""

        val grouped = linkedMapOf<Pair<String, String>, MutableList<String>>()
        nativeLogcat.lineSequence()
            .filter(String::isNotBlank)
            .forEach { line ->
                val session = runtimeSessionPattern.find(line)?.groupValues?.getOrNull(1)
                val revision = configRevisionPattern.find(line)?.groupValues?.getOrNull(1)
                val key = if (session != null && revision != null) {
                    session to revision
                } else {
                    "unsegmented" to "unknown"
                }
                grouped.getOrPut(key) { mutableListOf() }.add(line)
            }

        return buildString {
            grouped.forEach { (key, lines) ->
                appendLine(
                    "--- runtime_session_id=${key.first} config_revision=${key.second} ---",
                )
                lines.forEach(::appendLine)
            }
        }.trimEnd()
    }

    fun buildReport(context: Context): String {
        val appContext = context.applicationContext
        val warnings = mutableListOf<String>()
        val report = StringBuilder(128 * 1024)
        val artifacts = discoverArtifacts(appContext, warnings)
        val captureState = readCaptureState(artifacts.config, artifacts.stats)
        val appLogcat = runCatching { CrashHandler.getAppLogs(LOGCAT_LINES) }
            .getOrElse {
                warnings += "APP LOGCAT capture failed: ${safeMessage(it)}"
                ""
            }
        val uidLogcat = runCatching { captureUidLogcat(UID_LOGCAT_LINES) }
            .getOrElse {
                warnings += "UID LOGCAT capture failed: ${safeMessage(it)}"
                ""
            }
        val combinedLogcat = sequenceOf(appLogcat, uidLogcat)
            .filter { it.isNotBlank() }
            .joinToString("\n")

        fun section(name: String, body: () -> String) {
            report.append("===== ").append(name).append(" =====\n")
            val text = try {
                body().trimEnd().ifBlank { "unavailable" }
            } catch (t: Throwable) {
                val message = safeMessage(t)
                warnings += "$name unavailable: $message"
                "unavailable: $message"
            }
            report.append(text).append("\n\n")
        }

        section("CAPTURE") {
            buildString {
                appendLine("captured_at=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())}")
                appendLine("mode=${captureState.mode}")
                appendLine("adaptive_frame_generation=${if (captureState.adaptive) "enabled" else "disabled"}")
                appendLine("adaptive_target_fps=${captureState.targetFps ?: "unavailable"}")
                appendLine("scan_roots=${artifacts.scanRoots.joinToString(";")}")
                appendLine("scanned_nodes=${artifacts.scannedNodes}")
            }
        }

        section("DEVICE") {
            buildString {
                appendLine("manufacturer=${Build.MANUFACTURER}")
                appendLine("brand=${Build.BRAND}")
                appendLine("model=${Build.MODEL}")
                appendLine("device=${Build.DEVICE}")
                appendLine("hardware=${Build.HARDWARE}")
                appendLine("board=${Build.BOARD}")
                appendLine("android_release=${Build.VERSION.RELEASE}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
                if (Build.VERSION.SDK_INT >= 31) {
                    appendLine("soc_manufacturer=${Build.SOC_MANUFACTURER}")
                    appendLine("soc_model=${Build.SOC_MODEL}")
                }
            }
        }

        section("APP / RUNTIME") {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            buildString {
                appendLine("package=${appContext.packageName}")
                appendLine("version_name=${packageInfo.versionName}")
                val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                appendLine("version_code=$versionCode")
                appendLine("process_pid=${android.os.Process.myPid()}")
                appendLine("lsfg_runtime_marker=${readSmallFile(artifacts.runtimeMarker) ?: "unavailable"}")
                appendLine("lsfg_layer=${artifacts.layer?.absolutePath ?: "unavailable"}")
                appendLine("lsfg_layer_sha256=${sha256(artifacts.layer) ?: "unavailable"}")
                appendLine("lsfg_layer_bytes=${artifacts.layer?.takeIf(File::isFile)?.length() ?: 0L}")
                appendLine("selected_environment:")
                var environmentFound = false
                graphicsEnvironmentKeys.forEach { key ->
                    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { value ->
                        environmentFound = true
                        appendLine("  $key=$value")
                    }
                }
                if (!environmentFound) appendLine("  unavailable")
            }
        }

        section("GPU / VULKAN CAPABILITIES") {
            val capabilityLines = combinedLogcat.lineSequence()
                .filter { line ->
                    line.contains("capability", ignoreCase = true) ||
                        line.contains("AHB", ignoreCase = true) ||
                        line.contains("AHardwareBuffer", ignoreCase = true) ||
                        line.contains("vulkan", ignoreCase = true) ||
                        line.contains("driver", ignoreCase = true) ||
                        line.contains("device", ignoreCase = true) && line.contains("LSFG", ignoreCase = true)
                }
                .takeLastLines(PRESENTATION_LOG_LINES)
            capabilityLines.ifBlank {
                warnings += "GPU / VULKAN CAPABILITIES: no matching lines remained in app logcat"
                "No matching GPU/Vulkan capability lines captured."
            }
        }

        section("LSFG CONFIGURATION") {
            labeledFileOrUnavailable(artifacts.config, TEXT_TAIL_BYTES, warnings, "conf.toml")
        }

        section("LSFG CURRENT STATE") {
            buildString {
                appendLine("--- stats.txt ---")
                appendLine(readTailOrUnavailable(artifacts.stats, TEXT_TAIL_BYTES, warnings, "stats.txt"))
                appendLine("--- vsync.txt ---")
                appendLine(readTailOrUnavailable(artifacts.vsync, TEXT_TAIL_BYTES, warnings, "vsync.txt"))
                appendLine("--- present-vsync.txt ---")
                appendLine(readTailOrUnavailable(artifacts.presentVsync, TEXT_TAIL_BYTES, warnings, "present-vsync.txt"))
            }
        }

        section("3X / 4X PRESENTATION LOGCAT") {
            val filtered = combinedLogcat.lineSequence()
                .filter { line ->
                    val normalized = line.lowercase(Locale.US)
                    presentationKeywords.any(normalized::contains)
                }
                .takeLastLines(PRESENTATION_LOG_LINES)
            filtered.ifBlank {
                warnings += "3X / 4X PRESENTATION LOGCAT: no matching lines remained in app logcat"
                "No LSFG/present/swapchain/synchronization lines captured."
            }
        }

        section("LSFG NATIVE EVENTS") {
            val nativeLogcat = uidLogcat.lineSequence()
                .filter { line ->
                    line.contains("LSFG_METRICS") ||
                        line.contains("LSFG_EVENT") ||
                        line.contains("LSFG_FLOW") ||
                        line.contains(" LSFG ") ||
                        line.contains("LSFG:")
                }
                .takeLastLines(PRESENTATION_LOG_LINES)
            when {
                nativeLogcat.isNotBlank() ->
                    "source=same_uid_logcat\n${segmentNativeEvents(nativeLogcat)}"
                artifacts.nativeDiagnostics?.isFile == true ->
                    labeledFile(artifacts.nativeDiagnostics, NATIVE_EVENT_TAIL_BYTES)
                else -> {
                    warnings +=
                        "LSFG native events unavailable: no same-UID LSFG logcat or diagnostics.log"
                    "unavailable: no same-UID LSFG telemetry captured"
                }
            }
        }

        section("WRAPPER DIAGNOSTICS") {
            labeledFileOrUnavailable(
                artifacts.wrapperDiagnostics,
                TEXT_TAIL_BYTES,
                warnings,
                "wrapper_diag_*",
            )
        }

        section("PERFORMANCE TIMELINE") {
            labeledFileOrUnavailable(
                artifacts.performanceMetrics,
                NATIVE_EVENT_TAIL_BYTES,
                warnings,
                "metrics-*.jsonl / performance_metrics_*.jsonl",
            )
        }

        section("APP LOGCAT") {
            if (appLogcat.isBlank()) "unavailable" else appLogcat
        }

        report.append("===== EXPORT WARNINGS =====\n")
        if (warnings.isEmpty()) {
            report.append("none\n")
        } else {
            warnings.distinct().forEach { report.append("- ").append(it).append('\n') }
        }
        report.append('\n')

        return report.toString()
    }

    private data class CaptureState(
        val mode: String,
        val adaptive: Boolean,
        val targetFps: Int?,
    )

    private fun readCaptureState(config: File?, stats: File?): CaptureState {
        fun values(file: File?): Map<String, String> = runCatching {
            file?.takeIf(File::isFile)?.readLines()?.mapNotNull { line ->
                val cleaned = line.substringBefore('#').trim()
                val separator = cleaned.indexOf('=')
                if (separator <= 0) null else cleaned.substring(0, separator).trim() to
                    cleaned.substring(separator + 1).trim().trim('"')
            }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())

        val statsValues = values(stats)
        val configValues = values(config)
        val adaptive = when (statsValues["adaptive"]) {
            "1" -> true
            "0" -> false
            else -> configValues["adaptive_framegen"].equals("true", ignoreCase = true)
        }
        val target = statsValues["target_fps"]?.toIntOrNull()
            ?: configValues["fps_limit"]?.toIntOrNull()
        return CaptureState(
            mode = if (adaptive) "adaptive" else "fixed-multiplier",
            adaptive = adaptive,
            targetFps = target?.takeIf { it > 0 },
        )
    }

    internal data class DiscoveredArtifacts(
        val scanRoots: List<String>,
        val scannedNodes: Int,
        val config: File?,
        val stats: File?,
        val vsync: File?,
        val presentVsync: File?,
        val nativeDiagnostics: File?,
        val wrapperDiagnostics: File?,
        val performanceMetrics: File?,
        val runtimeMarker: File?,
        val layer: File?,
    )

    internal fun discoverArtifacts(
        context: Context,
        warnings: MutableList<String>,
    ): DiscoveredArtifacts {
        val roots = linkedMapOf<String, File>()
        val candidates = linkedMapOf<String, File>()
        var activeContainerArtifactRoot: File? = null

        fun canonical(file: File): String =
            runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

        fun addRoot(file: File?) {
            if (file == null || !file.exists()) return
            roots[canonical(file)] = file
        }

        fun addCandidate(file: File?) {
            if (file == null || !file.isFile) return
            candidates[canonical(file)] = file
        }

        fun addMatchingFiles(directory: File?, predicate: (File) -> Boolean) {
            if (directory == null || !directory.isDirectory) return
            directory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && predicate(it) }
                ?.forEach(::addCandidate)
        }

        fun addContainerArtifacts(root: File?, active: Boolean = false) {
            if (root == null || !root.isDirectory) return
            addRoot(root)
            if (active) activeContainerArtifactRoot = root
            val configDir = File(root, ".config/lsfg-vk")
            listOf(
                "conf.toml",
                "stats.txt",
                "vsync.txt",
                "present-vsync.txt",
                "diagnostics.log",
            ).forEach { name -> addCandidate(File(configDir, name)) }
            addCandidate(File(root, ".local/lib/liblsfg-vk-layer.so"))
            addCandidate(
                File(
                    root,
                    ".local/share/vulkan/implicit_layer.d/.lsfg_vk_runtime_version",
                ),
            )
        }

        fun isPerformanceMetrics(file: File): Boolean =
            file.name.endsWith(".jsonl") &&
                (file.name.startsWith("metrics-") ||
                    file.name.startsWith("performance_metrics_"))

        // Common path: direct probes only. These are resolved when diagnostics
        // are explicitly exported and never run during launch/gameplay.
        val activeContainerRoot = runCatching {
            PowerManager.activeContainerRootDir()
        }.getOrNull()
        addContainerArtifacts(activeContainerRoot, active = true)

        val imageRoot = File(context.filesDir, "imagefs")
        addRoot(imageRoot)
        File(imageRoot, "home").listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.take(256)
            ?.forEach(::addContainerArtifacts)

        addMatchingFiles(File(imageRoot, "usr/tmp")) { file ->
            file.name.startsWith("wrapper_diag_") && file.name.endsWith(".txt")
        }
        addMatchingFiles(File(imageRoot, "tmp")) { file ->
            file.name.startsWith("wrapper_diag_") && file.name.endsWith(".txt")
        }

        val metricsRoot = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            PowerBaselineScripts.DIRECTORY_NAME,
        )
        addRoot(metricsRoot)
        addMatchingFiles(metricsRoot, ::isPerformanceMetrics)
        // When a collector is active, retain the exact session selected at
        // start time. A newer file from a previous container must not replace
        // it merely because it has a later mtime.
        PerformanceMetricsCollector.activeSessionLogPath
            ?.let(::File)
            ?.let(::addCandidate)

        addRoot(File(context.applicationInfo.dataDir))
        addRoot(context.getExternalFilesDir(null))

        fun newest(predicate: (File) -> Boolean): File? =
            candidates.values.asSequence()
                .filter(predicate)
                .maxByOrNull { it.lastModified() }

        fun isLsfgPath(file: File): Boolean =
            file.absolutePath.replace('\\', '/').lowercase(Locale.US).let { path ->
                path.contains("lsfg-vk") || path.contains("lsfg_vk")
            }

        fun activeLsfgNamed(name: String): File? {
            val root = activeContainerArtifactRoot ?: return null
            val file = when (name) {
                ".lsfg_vk_runtime_version" -> File(
                    root,
                    ".local/share/vulkan/implicit_layer.d/$name",
                )
                "liblsfg-vk-layer.so" -> File(root, ".local/lib/$name")
                else -> File(root, ".config/lsfg-vk/$name")
            }
            return file.takeIf { it.isFile }
        }

        // The active container is the authoritative diagnostic session. Once
        // one is known, never fill a missing member with a newer artifact from
        // another container; that would produce a mixed-runtime report.
        fun lsfgNamed(name: String): File? =
            if (activeContainerArtifactRoot != null) {
                activeLsfgNamed(name)
            } else {
                newest { file -> file.name == name && isLsfgPath(file) }
            }

        // Legacy/unusual layouts get a small bounded fallback only when the
        // core runtime could not be resolved directly. Optional missing logs do
        // not trigger a 25k-node walk.
        val coreResolved = listOf(
            "conf.toml",
            "stats.txt",
            ".lsfg_vk_runtime_version",
            "liblsfg-vk-layer.so",
        ).all { lsfgNamed(it) != null }

        var scanned = 0
        if (!coreResolved && activeContainerArtifactRoot == null) {
            val excludedDirectories = setOf(
                "cache",
                "code_cache",
                ".wine",
                "drive_c",
                "windows",
                "steamapps",
                ".gradle",
            )
            roots.values.distinctBy(::canonical).forEach { root ->
                if (scanned >= MAX_SCAN_NODES) return@forEach
                runCatching {
                    val walk = root.walkTopDown()
                        .maxDepth(MAX_SCAN_DEPTH)
                        .onEnter { dir ->
                            dir == root ||
                                dir.name.lowercase(Locale.US) !in excludedDirectories
                        }
                    for (file in walk) {
                        if (scanned++ >= MAX_SCAN_NODES) break
                        if (!file.isFile) continue
                        val name = file.name
                        if (
                            (isLsfgPath(file) && name in setOf(
                                "conf.toml",
                                "stats.txt",
                                "vsync.txt",
                                "present-vsync.txt",
                                "diagnostics.log",
                                ".lsfg_vk_runtime_version",
                                "liblsfg-vk-layer.so",
                            )) ||
                            (name.startsWith("wrapper_diag_") &&
                                name.endsWith(".txt")) ||
                            isPerformanceMetrics(file)
                        ) {
                            addCandidate(file)
                        }
                    }
                }.onFailure {
                    warnings +=
                        "Artifact fallback scan failed under ${root.absolutePath}: ${safeMessage(it)}"
                }
            }
            if (scanned >= MAX_SCAN_NODES) {
                warnings +=
                    "Artifact fallback scan reached node limit ($MAX_SCAN_NODES)"
            }
        } else if (!coreResolved && activeContainerArtifactRoot != null) {
            warnings +=
                "Active container LSFG runtime is incomplete; stale artifacts were not mixed in"
        }

        return DiscoveredArtifacts(
            scanRoots = roots.keys.toList(),
            scannedNodes = scanned.coerceAtMost(MAX_SCAN_NODES),
            config = lsfgNamed("conf.toml"),
            stats = lsfgNamed("stats.txt"),
            vsync = lsfgNamed("vsync.txt"),
            presentVsync = lsfgNamed("present-vsync.txt"),
            nativeDiagnostics = lsfgNamed("diagnostics.log"),
            wrapperDiagnostics = newest {
                it.name.startsWith("wrapper_diag_") && it.name.endsWith(".txt")
            },
            performanceMetrics = PerformanceMetricsCollector.activeSessionLogPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?: newest(::isPerformanceMetrics),
            runtimeMarker = lsfgNamed(".lsfg_vk_runtime_version"),
            layer = lsfgNamed("liblsfg-vk-layer.so"),
        )
    }

    internal fun uidLogcatCommand(lineCount: Int, uid: Int): List<String> =
        listOf(
            "logcat",
            "-d",
            "-t",
            lineCount.coerceIn(1, 20_000).toString(),
            "--uid=$uid",
        )

    private fun captureUidLogcat(lineCount: Int): String {
        val process = ProcessBuilder(
            uidLogcatCommand(lineCount, android.os.Process.myUid()),
        )
            .redirectErrorStream(true)
            .start()
        return process.inputStream.bufferedReader().use { it.readText() }
    }

    private fun labeledFileOrUnavailable(
        file: File?,
        maxBytes: Long,
        warnings: MutableList<String>,
        label: String,
    ): String {
        if (file == null || !file.isFile) {
            warnings += "$label unavailable: matching artifact not found"
            return "unavailable: matching artifact not found"
        }
        return runCatching { labeledFile(file, maxBytes) }
            .getOrElse {
                warnings += "$label unavailable: ${safeMessage(it)}"
                "unavailable: ${safeMessage(it)}"
            }
    }

    private fun labeledFile(file: File, maxBytes: Long): String = buildString {
        appendLine("file=${file.absolutePath}")
        appendLine("bytes=${file.length()}")
        append(readTail(file, maxBytes))
    }

    private fun readTailOrUnavailable(
        file: File?,
        maxBytes: Long,
        warnings: MutableList<String>,
        label: String,
    ): String {
        if (file == null || !file.isFile) {
            warnings += "$label unavailable: matching artifact not found"
            return "unavailable: matching artifact not found"
        }
        return runCatching { readTail(file, maxBytes) }
            .getOrElse {
                warnings += "$label unavailable: ${safeMessage(it)}"
                "unavailable: ${safeMessage(it)}"
            }
    }

    private fun readTail(file: File, maxBytes: Long): String {
        val length = file.length()
        if (length <= maxBytes) return file.readText(Charsets.UTF_8)
        val start = (length - maxBytes).coerceAtLeast(0L)
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val count = (length - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(count)
            raf.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            val firstNewline = text.indexOf('\n')
            val body = if (firstNewline >= 0) text.substring(firstNewline + 1) else text
            "... truncated; showing last $maxBytes bytes ...\n$body"
        }
    }

    private fun readSmallFile(file: File?): String? = runCatching {
        file?.takeIf { it.isFile && it.length() <= 64L * 1024L }?.readText()?.trim()
    }.getOrNull()

    private fun sha256(file: File?): String? = runCatching {
        if (file == null || !file.isFile) return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }.getOrNull()

    private fun Sequence<String>.takeLastLines(maxLines: Int): String {
        val lines = ArrayDeque<String>(maxLines)
        forEach { line ->
            if (lines.size == maxLines) lines.removeFirst()
            lines.addLast(line)
        }
        return lines.joinToString("\n")
    }

    private fun safeMessage(t: Throwable): String =
        t.message?.replace('\n', ' ')?.take(240) ?: t.javaClass.simpleName
}
