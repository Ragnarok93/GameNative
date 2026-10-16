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

    fun isCapturing(): Boolean = window != null

    /** Start a capture window. Returns false if a capture is already active. */
    fun start(): Boolean = synchronized(lock) {
        if (window != null) return false
        window = Window(System.currentTimeMillis(), System.nanoTime())
        Log.i(TAG, "Driver performance capture started")
        true
    }

    fun defaultFileName(now: Date = Date()): String =
        "gamenative-driver-capture-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)}.zip"

    /**
     * Stop the active window and export a ZIP through Android's document picker.
     * This deliberately uses only data available inside GameNative; it does not require ADB.
     */
    fun exportAsync(
        context: Context,
        uri: Uri,
        onResult: (Result<Long>) -> Unit,
    ) {
        val appContext = context.applicationContext
        val capture = synchronized(lock) {
            val active = window
            window = null
            active
        }
        scope.launch {
            val result = runCatching {
                val frameTimestamps = LongArray(FrameTimeRing.capacity())
                val frameCount = capture?.let {
                    FrameTimeRing.copySince(it.startedAtNanos, frameTimestamps)
                } ?: 0
                val nowMillis = System.currentTimeMillis()
                val report = runCatching { LsfgDiagnosticExporter.buildReport(appContext) }
                    .getOrElse { error ->
                        "===== REPORT ERROR =====\n${error.javaClass.name}: ${error.message}\n"
                    }
                val metricsPath = PerformanceMetricsCollector.activeSessionLogPath
                val metricsFile = metricsPath?.let(::File)?.takeIf { it.isFile && it.canRead() }
                val resolver = appContext.contentResolver
                val preparedZip = File.createTempFile("driver-capture-", ".zip", appContext.cacheDir)
                try {
                    preparedZip.outputStream().use { raw ->
                        ZipOutputStream(raw).use { zip ->
                            zip.putNextEntry(ZipEntry("capture-metadata.txt"))
                            zip.write(buildMetadata(capture, nowMillis, frameCount, metricsFile).toByteArray())
                            zip.closeEntry()

                            zip.putNextEntry(ZipEntry("driver-and-runtime-report.txt"))
                            zip.write(report.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()

                            zip.putNextEntry(ZipEntry("frame-times.csv"))
                            zip.write(buildFrameCsv(capture?.startedAtNanos, frameTimestamps, frameCount).toByteArray())
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
            result.onSuccess { Log.i(TAG, "Driver performance capture exported bytes=$it") }
                .onFailure { Log.e(TAG, "Driver performance capture export failed", it) }
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
    ): String = buildString {
        appendLine("format=GameNative driver performance capture v1")
        appendLine("started_at=${capture?.startedAtMillis?.let(::formatTime) ?: "not-recorded"}")
        appendLine("ended_at=${formatTime(endedAtMillis)}")
        appendLine("duration_ms=${capture?.let { (endedAtMillis - it.startedAtMillis).coerceAtLeast(0L) } ?: 0L}")
        appendLine("frame_timestamp_count=$frameCount")
        appendLine("frame_timestamp_source=GameNative FrameTimeRing; only present while the metrics frame hook is recording")
        appendLine("performance_metrics_file=${metricsFile?.name ?: "unavailable"}")
        appendLine("gpu_hardware_counters=not directly available through the GameNative app API; driver-provided counters are included only if present in logs")
        appendLine("capture_scope=GameNative process logs, LSFG/Vulkan/runtime report, retained frame timestamps, and current metrics JSONL when available")
    }

    private fun formatTime(timeMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timeMillis))
}
