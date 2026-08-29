package app.gamenative.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.dialog.CrashLogDialog
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.LsfgCompatibilityDiagnostics
import com.alorma.compose.settings.ui.SettingsMenuLink
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-button, read-only LSFG field diagnostics.
 *
 * Nothing here changes a container, LSFG config, Vulkan environment, wrapper,
 * or driver. The generated report can therefore be collected from a known-good
 * stable container without altering the condition being investigated.
 */
@Composable
fun SettingsLsfgDiagnostics() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by rememberSaveable { mutableStateOf(false) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    var reportText by rememberSaveable { mutableStateOf<String?>(null) }
    var reportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }

    val saveReport = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val source = reportPath?.let(::File) ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
        }.onFailure {
            SnackbarManager.show(context.getString(R.string.settings_lsfg_diagnostics_export_failed))
        }
    }

    SettingsMenuLink(
        colors = settingsTileColors(),
        enabled = !running,
        title = { Text(text = stringResource(R.string.settings_lsfg_diagnostics_title)) },
        subtitle = {
            Text(
                text = when {
                    running -> stringResource(R.string.settings_lsfg_diagnostics_running)
                    lastFocus != null -> stringResource(R.string.settings_lsfg_diagnostics_last_focus, lastFocus!!)
                    else -> stringResource(R.string.settings_lsfg_diagnostics_subtitle)
                },
            )
        },
        onClick = {
            if (running) return@SettingsMenuLink
            running = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val report = LsfgCompatibilityDiagnostics.run(context)
                        val file = LsfgCompatibilityDiagnostics.writeReport(context, report)
                        Triple(report, file, report.toText())
                    }
                }
                running = false
                result.onSuccess { (report, file, text) ->
                    reportText = text
                    reportPath = file.absolutePath
                    lastFocus = report.containers
                        .firstOrNull { it.nextFocus != "NONE" }
                        ?.nextFocus
                        ?: report.containers.firstOrNull()?.nextFocus
                        ?: "NO_CONTAINERS"
                    showReport = true
                }.onFailure {
                    SnackbarManager.show(
                        context.getString(
                            R.string.settings_lsfg_diagnostics_failed,
                            it.message ?: it.javaClass.simpleName,
                        ),
                    )
                }
            }
        },
    )

    if (showReport && reportText != null) {
        val source = reportPath?.let(::File)
        CrashLogDialog(
            visible = true,
            fileName = source?.name ?: "lsfg_compatibility.txt",
            fileText = reportText ?: "",
            onSave = {
                saveReport.launch(source?.name ?: "lsfg_compatibility.txt")
            },
            onDismissRequest = { showReport = false },
        )
    }
}
