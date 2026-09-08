package app.gamenative.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent bridge between wrapper-side BCn counters and Settings export. */
object BcnPerformanceMetricsExporter {
    private const val METRICS_RELATIVE_DIR = "imagefs/usr/cache/bcn_metrics"
    private val safeNameRegex = Regex("[^A-Za-z0-9._-]")

    private fun metricsDir(context: Context): File =
        File(context.filesDir, METRICS_RELATIVE_DIR).apply { mkdirs() }

    fun metricsFile(context: Context, appId: String): File {
        val safeAppId = appId.ifBlank { "unknown" }.replace(safeNameRegex, "_")
        return File(metricsDir(context), "bcn_perf_${safeAppId}.txt")
    }

    fun latestMetricsFile(context: Context): File? =
        metricsDir(context)
            .listFiles { file -> file.isFile && file.name.startsWith("bcn_perf_") && file.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }

    fun buildLatestReport(context: Context): String {
        val source = latestMetricsFile(context)
            ?: error("No BCn performance metrics have been captured")
        val body = source.readText(Charsets.UTF_8)
        return buildString(body.length + 192) {
            appendLine("GAMENATIVE_BCN_EXPORT_VERSION=1")
            appendLine("exported_at_ms=${System.currentTimeMillis()}")
            appendLine("source_file=${source.name}")
            appendLine("source_modified_ms=${source.lastModified()}")
            append(body)
            if (!body.endsWith('\n')) appendLine()
        }
    }

    fun defaultExportFileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return "bcn-performance-$stamp.txt"
    }
}
