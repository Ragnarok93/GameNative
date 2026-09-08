#!/usr/bin/env python3
"""Wire BCn performance metrics collection/export into GameNative Android UI."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
settings_path = root / "app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt"
xserver_path = root / "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt"
strings_path = root / "app/src/main/res/values/strings.xml"
exporter_path = root / "app/src/main/java/app/gamenative/diagnostics/BcnPerformanceMetricsExporter.kt"


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


settings = settings_path.read_text()
xserver = xserver_path.read_text()
strings = strings_path.read_text()

if exporter_path.exists() or "settings_save_bcn_metrics_title" in strings:
    raise RuntimeError("BCn metrics Android UI already appears to be applied")

# Runtime wiring: only wrapper-gamenative consumes these metrics env vars.
xserver = one(
    xserver,
    """        val bcnEmulationCache = graphicsDriverConfig.get(\"bcnEmulationCache\")
        envVars.put(\"WRAPPER_USE_BCN_CACHE\", bcnEmulationCache)

        val transcoder = graphicsDriverConfig.get(\"transcoder\", \"cpu\")
""",
    """        val bcnEmulationCache = graphicsDriverConfig.get(\"bcnEmulationCache\")
        envVars.put(\"WRAPPER_USE_BCN_CACHE\", bcnEmulationCache)

        if (isWrapperGamenative) {
            val bcnMetricsFile = app.gamenative.diagnostics.BcnPerformanceMetricsExporter.metricsFile(context, appId)
            envVars.put(\"WRAPPER_BCN_METRICS\", \"1\")
            envVars.put(\"WRAPPER_BCN_METRICS_FILE\", bcnMetricsFile.absolutePath)
            envVars.put(\"WRAPPER_BCN_METRICS_APPID\", appId)
            // Cache hit/miss accounting feeds the same performance snapshot.
            envVars.put(\"WRAPPER_BCN_TELEMETRY\", \"1\")
        }

        val transcoder = graphicsDriverConfig.get(\"transcoder\", \"cpu\")
""",
    "wrapper metrics env wiring",
)

# Import exporter next to the existing LSFG diagnostics exporter.
settings = one(
    settings,
    "import app.gamenative.diagnostics.LsfgDiagnosticExporter\n",
    "import app.gamenative.diagnostics.BcnPerformanceMetricsExporter\nimport app.gamenative.diagnostics.LsfgDiagnosticExporter\n",
    "metrics exporter import",
)

# Document save launcher mirrors Save logcat / LSFG export behavior.
settings = one(
    settings,
    """    /* Unified LSFG diagnostics export. Capture/assembly happens only on explicit export. */
    val saveLsfgDiagnostics = rememberLauncherForActivityResult(
""",
    """    /* Save the latest persistent BCn emulation performance snapshot. */
    val saveBcnMetrics = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(\"text/plain\"),
    ) { resultUri ->
        resultUri ?: return@rememberLauncherForActivityResult
        diagnosticsScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = BcnPerformanceMetricsExporter.buildLatestReport(context)
                    context.contentResolver.openOutputStream(resultUri)?.use { outputStream ->
                        outputStream.write(report.toByteArray(Charsets.UTF_8))
                    } ?: error(\"Unable to open selected destination\")
                }
            }
            result.onSuccess {
                SnackbarManager.show(context.getString(R.string.toast_bcn_metrics_saved))
            }.onFailure {
                SnackbarManager.show(context.getString(R.string.toast_failed_bcn_metrics_save))
            }
        }
    }

    /* Unified LSFG diagnostics export. Capture/assembly happens only on explicit export. */
    val saveLsfgDiagnostics = rememberLauncherForActivityResult(
""",
    "metrics document launcher",
)

# Requirement: exactly below the existing Save logcat tile.
settings = one(
    settings,
    """        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_save_logcat_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_save_logcat_subtitle)) },
            onClick = { saveLogCat.launch(\"app_logs_${CrashHandler.timestamp}.txt\") },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = \"Export LSFG Diagnostics\") },
""",
    """        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_save_logcat_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_save_logcat_subtitle)) },
            onClick = { saveLogCat.launch(\"app_logs_${CrashHandler.timestamp}.txt\") },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_save_bcn_metrics_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_save_bcn_metrics_subtitle)) },
            onClick = {
                if (BcnPerformanceMetricsExporter.latestMetricsFile(context) == null) {
                    SnackbarManager.show(context.getString(R.string.toast_no_bcn_metrics))
                } else {
                    saveBcnMetrics.launch(BcnPerformanceMetricsExporter.defaultExportFileName())
                }
            },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = \"Export LSFG Diagnostics\") },
""",
    "metrics settings tile placement",
)

strings = one(
    strings,
    """    <string name=\"settings_save_logcat_subtitle\">Saves a snapshot of the logcat only for this app\\'s PID</string>
    <string name=\"settings_save_logcat_title\">Save logcat</string>
""",
    """    <string name=\"settings_save_logcat_subtitle\">Saves a snapshot of the logcat only for this app\\'s PID</string>
    <string name=\"settings_save_logcat_title\">Save logcat</string>
    <string name=\"settings_save_bcn_metrics_title\">Save BCn performance metrics</string>
    <string name=\"settings_save_bcn_metrics_subtitle\">Export timing, cache, descriptor, and staging-reuse counters from the latest BCn emulation run</string>
    <string name=\"toast_bcn_metrics_saved\">BCn performance metrics exported</string>
    <string name=\"toast_failed_bcn_metrics_save\">Failed to export BCn performance metrics</string>
    <string name=\"toast_no_bcn_metrics\">No BCn performance metrics have been captured yet</string>
""",
    "metrics strings",
)

exporter_path.parent.mkdir(parents=True, exist_ok=True)
exporter_path.write_text(r'''package app.gamenative.diagnostics

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
''')

settings_path.write_text(settings)
xserver_path.write_text(xserver)
strings_path.write_text(strings)

print("Applied BCn metrics Android wiring and Settings export tile")
