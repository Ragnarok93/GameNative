package app.gamenative.diagnostics

import android.content.Context
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.CrashHandler
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.metrics.PerformanceMetricsCollector
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the single-file, best-effort LSFG diagnostic report exported from Debug settings. */
object LsfgDiagnosticExporter {
    private const val TEXT_TAIL_BYTES = 2L * 1024L * 1024L
    private const val NATIVE_EVENT_TAIL_BYTES = 4L * 1024L * 1024L
    private const val LOGCAT_LINES = 2_000
    private const val LSFG_LOGCAT_LINES = 4_000

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

    fun defaultFileName(now: Date = Date()): String =
        "gamenative-lsfg-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)}.txt"

    fun buildReport(context: Context): String {
        val appContext = context.applicationContext
        val warnings = mutableListOf<String>()
        val report = StringBuilder(64 * 1024)
        val session = LsfgDiagnosticSession.currentOrLatest(appContext)
        val root = session?.containerRoot
        val lsfgTaggedLog = runCatching { CrashHandler.getTaggedLogs("LSFG", LSFG_LOGCAT_LINES) }
            .getOrElse {
                warnings += "LSFG-tag logcat capture failed: ${safeMessage(it)}"
                ""
            }

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

        section("SESSION") {
            val value = session ?: throw SourceUnavailable("no active or completed diagnostic session")
            buildString {
                appendLine("session_start_ms=${value.sessionStartMillis}")
                appendLine("session_end_ms=${value.sessionEndMillis ?: "active"}")
                appendLine("active=${if (value.active) 1 else 0}")
                appendLine("container_root=${value.containerRoot.absolutePath}")
                appendLine("manifest=${value.manifestFile.absolutePath}")
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
                appendLine("version_code=${packageInfo.longVersionCode}")
                appendLine("build_type=${BuildConfig.BUILD_TYPE}")
                appendLine("process_pid=${android.os.Process.myPid()}")
                appendLine("latest_metrics=${PowerManager.latestMetrics ?: "none"}")
                if (root != null) {
                    val marker = File(root, ".local/share/vulkan/implicit_layer.d/.lsfg_vk_runtime_version")
                    val layer = File(root, ".local/lib/liblsfg-vk-layer.so")
                    appendLine("lsfg_runtime_marker=${readSmallFile(marker) ?: "unavailable"}")
                    appendLine("lsfg_layer_sha256=${sha256(layer) ?: "unavailable"}")
                    appendLine("lsfg_layer_bytes=${layer.takeIf(File::isFile)?.length() ?: 0L}")
                } else {
                    appendLine("lsfg_runtime_marker=unavailable")
                    appendLine("lsfg_layer_sha256=unavailable")
                }
                appendLine("selected_environment:")
                graphicsEnvironmentKeys.forEach { key ->
                    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { appendLine("  $key=$it") }
                }
            }
        }

        section("GPU / VULKAN CAPABILITIES") {
            val capabilityLines = lsfgTaggedLog.lineSequence()
                .filter { line ->
                    line.contains("capability", ignoreCase = true) ||
                        line.contains("AHB", ignoreCase = true) ||
                        line.contains("vulkan", ignoreCase = true) ||
                        line.contains("driver", ignoreCase = true)
                }
                .joinToString("\n")
            if (capabilityLines.isBlank()) {
                warnings += "GPU / VULKAN CAPABILITIES: no LSFG capability lines remained in logcat"
                "No LSFG capability lines captured. See APP / RUNTIME selected_environment and LSFG CURRENT STATE."
            } else capabilityLines
        }

        section("LSFG CONFIGURATION") {
            val file = root?.let { File(it, ".config/lsfg-vk/conf.toml") }
                ?: throw SourceUnavailable("session container root unavailable")
            labeledFile(file, TEXT_TAIL_BYTES)
        }

        section("LSFG CURRENT STATE") {
            val containerRoot = root ?: throw SourceUnavailable("session container root unavailable")
            val stats = File(containerRoot, ".config/lsfg-vk/stats.txt")
            val vsync = File(containerRoot, ".config/lsfg-vk/vsync.txt")
            buildString {
                appendLine("--- stats.txt ---")
                appendLine(readTailOrUnavailable(stats, TEXT_TAIL_BYTES, warnings, "stats.txt"))
                appendLine("--- vsync.txt ---")
                appendLine(readTailOrUnavailable(vsync, TEXT_TAIL_BYTES, warnings, "vsync.txt"))
            }
        }

        section("PERFORMANCE TIMELINE") {
            val start = session?.sessionStartMillis
                ?: throw SourceUnavailable("session timestamp unavailable")
            val metrics = PerformanceMetricsCollector.diagnosticLogFor(appContext, start)
                ?: throw SourceUnavailable("no rolling performance metrics file found")
            labeledFile(metrics, NATIVE_EVENT_TAIL_BYTES)
        }

        section("LSFG NATIVE EVENTS") {
            val containerRoot = root ?: throw SourceUnavailable("session container root unavailable")
            val nativeFile = File(containerRoot, ".config/lsfg-vk/diagnostics.log")
            buildString {
                appendLine("--- rolling native diagnostics ---")
                appendLine(readTailOrUnavailable(nativeFile, NATIVE_EVENT_TAIL_BYTES, warnings, "diagnostics.log"))
                appendLine("--- LSFG-tag logcat snapshot ---")
                if (lsfgTaggedLog.isBlank()) appendLine("unavailable") else append(lsfgTaggedLog.trimEnd()).append('\n')
            }
        }

        section("WRAPPER DIAGNOSTICS") {
            val tmpDir = File(ImageFs.find(appContext).rootDir, "usr/tmp")
            val wrapper = tmpDir.listFiles { file ->
                file.isFile && file.name.startsWith("wrapper_diag_") && file.name.endsWith(".txt")
            }?.maxByOrNull { it.lastModified() }
                ?: throw SourceUnavailable("no wrapper diagnostic file found")
            labeledFile(wrapper, TEXT_TAIL_BYTES)
        }

        section("APP LOGCAT") {
            CrashHandler.getAppLogs(LOGCAT_LINES).also {
                if (it.startsWith("Failed to capture logs:")) warnings += "APP LOGCAT: $it"
            }
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

    private fun labeledFile(file: File, maxBytes: Long): String {
        if (!file.isFile) throw SourceUnavailable("missing ${file.absolutePath}")
        return buildString {
            appendLine("file=${file.absolutePath}")
            appendLine("bytes=${file.length()}")
            append(readTail(file, maxBytes))
        }
    }

    private fun readTailOrUnavailable(
        file: File,
        maxBytes: Long,
        warnings: MutableList<String>,
        label: String,
    ): String {
        if (!file.isFile) {
            warnings += "$label unavailable: missing ${file.absolutePath}"
            return "unavailable: missing ${file.absolutePath}"
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

    private fun readSmallFile(file: File): String? = runCatching {
        file.takeIf { it.isFile && it.length() <= 64L * 1024L }?.readText()?.trim()
    }.getOrNull()

    private fun sha256(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
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

    private fun safeMessage(t: Throwable): String =
        t.message?.replace('\n', ' ')?.take(240) ?: t.javaClass.simpleName

    private class SourceUnavailable(message: String) : IllegalStateException(message)
}
