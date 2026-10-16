package app.gamenative.diagnostics

import android.content.Context
import android.net.Uri
import android.util.Log
import app.gamenative.powercontrol.metrics.FrameTimeRing
import app.gamenative.powercontrol.metrics.PerformanceMetricsCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit, in-app capture window for reproducing frame-time spikes.
 *
 * No polling or file writes happen on the render thread. Frame timestamps come from the existing
 * allocation-free FrameTimeRing; the report includes GameNative's available app/native driver
 * logs and current driver/runtime metadata. Vulkan hardware counters are not claimed unless the
 * driver itself exposes them in those logs.
 */
object DriverPerformanceCapture {
    private const val TAG = "DriverPerformanceCapture"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private data class Window(
        val startedAtMillis: Long,
        val startedAtNanos: Long,
    )

    @Volatile
    private var window: Window? = null
    @Volatile
    private var completedWindow: Window? = null

    fun isCapturing(): Boolean = window != null

    /** Start a capture window. Returns false if a capture is already active. */
    fun start(): Boolean = synchronized(lock) {
        if (window != null) return false
        completedWindow = null
        window = Window(System.currentTimeMillis(), System.nanoTime())
        Log.i(TAG, "Driver performance capture started")
        true
    }

    /** Stop capture and retain the completed window for export from Debug Settings. */
    fun stop(): Boolean = synchronized(lock) {
        val active = window ?: return false
        window = null
        completedWindow = active
        Log.i(TAG, "Driver performance capture stopped")
        true
    }

    fun hasCompletedCapture(): Boolean = completedWindow != null

    fun defaultFileName(now: Date = Date()): String =
        "gamenative-driver-capture-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)}.zip"

    /**
     * Export the most recently stopped capture through Android's document picker.
     * This deliberately uses only data available inside GameNative; it does not require ADB.
     */
    fun exportAsync(
        context: Context,
        uri: Uri,
        onResult: (Result<Long>) -> Unit,
    ) {
        val appContext = context.applicationContext
        val capture = synchronized(lock) { completedWindow }
        scope.launch {
            val result = runCatching {
                check(capture != null) {
                    if (isCapturing()) "Stop driver capture from the in-game Quick Menu before exporting"
                    else "No completed driver capture is available; start and stop one from the in-game Quick Menu first"
                }
                val frameTimestamps = LongArray(FrameTimeRing.capacity())
                val frameCount = FrameTimeRing.copySince(capture.startedAtNanos, frameTimestamps)
                val nowMillis = System.currentTimeMillis()
                val report = runCatching { LsfgDiagnosticExporter.buildReport(appContext) }
                    .getOrElse { error ->
                        "===== REPORT ERROR =====\n${error.javaClass.name}: ${error.message}\n"
                    }
                val metricsPath = PerformanceMetricsCollector.activeSessionLogPath
                val metricsFile = metricsPath?.let(::File)?.takeIf { it.isFile && it.canRead() }
                val dxvkLogDir = File(appContext.filesDir, "imagefs/tmp/dxvk_logs")
                val dxvkLogs = findDxvkLogFiles(dxvkLogDir)
                val resolver = appContext.contentResolver
                val preparedZip = File.createTempFile("driver-capture-", ".zip", appContext.cacheDir)
                try {
                    preparedZip.outputStream().use { raw ->
                        ZipOutputStream(raw).use { zip ->
                            zip.putNextEntry(ZipEntry("capture-metadata.txt"))
                            zip.write(buildMetadata(capture, nowMillis, frameCount, metricsFile, dxvkLogs.size).toByteArray())
                            zip.closeEntry()

                            zip.putNextEntry(ZipEntry("driver-and-runtime-report.txt"))
                            zip.write(report.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()

                            zip.putNextEntry(ZipEntry("dxvk-logs/README.txt"))
                            zip.write(
                                buildDxvkLogReadme(dxvkLogDir, dxvkLogs).toByteArray(Charsets.UTF_8),
                            )
                            zip.closeEntry()
                            dxvkLogs.forEach { logFile ->
                                zip.putNextEntry(ZipEntry("dxvk-logs/${logFile.name}"))
                                logFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }

                            zip.putNextEntry(ZipEntry("frame-times.csv"))
                            zip.write(buildFrameCsv(capture.startedAtNanos, frameTimestamps, frameCount).toByteArray())
                            zip.closeEntry()

                            if (metricsFile != null) {
                                zip.putNextEntry(ZipEntry("performance-metrics.jsonl"))
                                metricsFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                    val destination = runCatching { resolver.openOutputStream(uri, "rwt") }.getOrNull()
                        ?: resolver.openOutputStream(uri)
                        ?: error("Unable to open selected destination")
                    destination.use { output ->
                        preparedZip.inputStream().use { input -> input.copyTo(output) }
                        output.flush()
                    }
                    preparedZip.length().also { check(it > 0L) { "Driver diagnostic ZIP was empty" } }
                } finally {
                    if (!preparedZip.delete()) {
                        Log.w(TAG, "Unable to delete temporary driver capture ZIP")
                    }
                }
            }
            result.onSuccess {
                synchronized(lock) { if (completedWindow === capture) completedWindow = null }
                Log.i(TAG, "Driver performance capture exported bytes=$it")
            }.onFailure { Log.e(TAG, "Driver performance capture export failed", it) }
            withContext(Dispatchers.Main.immediate) { onResult(result) }
        }
    }

    internal fun buildFrameCsv(startNanos: Long?, timestamps: LongArray, count: Int): String =
        buildString {
            appendLine("elapsed_ms,frame_delta_ms,timestamp_ns")
            var previous = 0L
            for (index in 0 until count.coerceIn(0, timestamps.size)) {
                val timestamp = timestamps[index]
                val elapsedMs = if (startNanos == null) 0.0 else
                    (timestamp - startNanos).coerceAtLeast(0L) / 1_000_000.0
                val deltaMs = if (index == 0) "" else
                    String.format(Locale.US, "%.3f", (timestamp - previous).coerceAtLeast(0L) / 1_000_000.0)
                append(String.format(Locale.US, "%.3f", elapsedMs))
                append(',')
                append(deltaMs)
                append(',')
                appendLine(timestamp)
                previous = timestamp
            }
        }

    private fun buildMetadata(
        capture: Window?,
        endedAtMillis: Long,
        frameCount: Int,
        metricsFile: File?,
        dxvkLogCount: Int,
    ): String = buildString {
        appendLine("format=GameNative driver performance capture v1")
        appendLine("started_at=${capture?.startedAtMillis?.let(::formatTime) ?: "not-recorded"}")
        appendLine("ended_at=${formatTime(endedAtMillis)}")
        appendLine("duration_ms=${capture?.let { (endedAtMillis - it.startedAtMillis).coerceAtLeast(0L) } ?: 0L}")
        appendLine("frame_timestamp_count=$frameCount")
        appendLine("frame_timestamp_source=GameNative FrameTimeRing; only present while the metrics frame hook is recording")
        appendLine("performance_metrics_file=${metricsFile?.name ?: "unavailable"}")
        appendLine("dxvk_log_count=$dxvkLogCount")
        appendLine("dxvk_log_scope=DXVK initializes file logging at game-process startup; these files include startup messages and are not limited to the in-game capture window")
        appendLine("gpu_hardware_counters=not directly available through the GameNative app API; driver-provided counters are included only if present in logs")
        appendLine("capture_scope=GameNative/Turnip logcat, DXVK startup log files, LSFG/Vulkan/runtime report, retained frame timestamps, and current metrics JSONL when available")
    }

    internal fun findDxvkLogFiles(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension.equals("log", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun buildDxvkLogReadme(directory: File, files: List<File>): String = buildString {
        appendLine("DXVK diagnostic logs")
        appendLine("source_directory=${directory.absolutePath}")
        appendLine("file_count=${files.size}")
        appendLine("capture_window_note=DXVK loggers initialize when the game process starts, so startup messages cannot be toggled on from the in-game Quick Menu. These files cover the process lifetime up to export, while the capture metadata/frame timestamps describe the selected in-game window.")
        if (files.isEmpty()) {
            appendLine("status=no DXVK .log files were found")
            appendLine("check=Confirm the game uses DXVK and inspect the app/container startup log for log-directory write errors.")
        } else {
            appendLine("files=${files.joinToString(",") { it.name }}")
        }
    }

    private fun formatTime(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timeMillis))
}
