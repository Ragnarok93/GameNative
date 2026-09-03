package app.gamenative.diagnostics

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Lightweight session manifest for the unified LSFG diagnostics exporter.
 *
 * High-frequency telemetry is intentionally NOT duplicated here. PerformanceMetricsCollector
 * already owns the bounded 500 ms metrics stream and the native layer owns its own telemetry.
 * This manifest gives those streams one shared session timestamp and preserves the container
 * root after the game exits so a completed session can still be exported.
 */
data class LsfgDiagnosticSessionSnapshot(
    val sessionStartMillis: Long,
    val sessionEndMillis: Long?,
    val containerRoot: File,
    val manifestFile: File,
) {
    val active: Boolean get() = sessionEndMillis == null
}

object LsfgDiagnosticSession {
    private const val DIRECTORY_NAME = "lsfg_diagnostics"
    private const val FILE_PREFIX = "session-"
    private const val FILE_SUFFIX = ".properties"
    private const val MAX_SESSION_FILES = 5

    @Volatile
    private var activeSession: LsfgDiagnosticSessionSnapshot? = null

    @Synchronized
    fun start(
        context: Context,
        containerRoot: File,
        sessionStartMillis: Long = System.currentTimeMillis(),
    ): LsfgDiagnosticSessionSnapshot {
        activeSession?.let { finishSnapshot(it, System.currentTimeMillis()) }

        val directory = directory(context).apply { mkdirs() }
        pruneOldSessions(directory, MAX_SESSION_FILES - 1)
        val manifest = File(directory, "$FILE_PREFIX$sessionStartMillis$FILE_SUFFIX")
        val snapshot = LsfgDiagnosticSessionSnapshot(
            sessionStartMillis = sessionStartMillis,
            sessionEndMillis = null,
            containerRoot = containerRoot.absoluteFile,
            manifestFile = manifest,
        )
        writeSnapshot(snapshot)
        activeSession = snapshot
        return snapshot
    }

    @Synchronized
    fun stop(sessionEndMillis: Long = System.currentTimeMillis()) {
        activeSession?.let { finishSnapshot(it, sessionEndMillis) }
        activeSession = null
    }

    fun currentOrLatest(context: Context): LsfgDiagnosticSessionSnapshot? {
        activeSession?.let { return it }
        return directory(context)
            .listFiles { file ->
                file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
            }
            ?.sortedByDescending { it.lastModified() }
            ?.firstNotNullOfOrNull(::readSnapshot)
    }

    internal fun directory(context: Context): File {
        val parent = context.getExternalFilesDir(null) ?: context.filesDir
        return File(parent, DIRECTORY_NAME)
    }

    private fun finishSnapshot(snapshot: LsfgDiagnosticSessionSnapshot, endMillis: Long) {
        writeSnapshot(snapshot.copy(sessionEndMillis = endMillis.coerceAtLeast(snapshot.sessionStartMillis)))
    }

    private fun writeSnapshot(snapshot: LsfgDiagnosticSessionSnapshot) {
        snapshot.manifestFile.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty("session_start_ms", snapshot.sessionStartMillis.toString())
            snapshot.sessionEndMillis?.let { setProperty("session_end_ms", it.toString()) }
            setProperty("container_root", snapshot.containerRoot.absolutePath)
        }
        val temp = File(snapshot.manifestFile.parentFile, snapshot.manifestFile.name + ".tmp")
        FileOutputStream(temp).use { properties.store(it, "GameNative LSFG diagnostic session") }
        if (!temp.renameTo(snapshot.manifestFile)) {
            snapshot.manifestFile.delete()
            if (!temp.renameTo(snapshot.manifestFile)) {
                temp.delete()
                throw IllegalStateException("Unable to publish LSFG diagnostic session manifest")
            }
        }
    }

    private fun readSnapshot(file: File): LsfgDiagnosticSessionSnapshot? = runCatching {
        val properties = Properties().also { props ->
            FileInputStream(file).use(props::load)
        }
        val start = properties.getProperty("session_start_ms")?.toLongOrNull() ?: return@runCatching null
        val end = properties.getProperty("session_end_ms")?.toLongOrNull()
        val root = properties.getProperty("container_root")?.takeIf { it.isNotBlank() }
            ?.let(::File) ?: return@runCatching null
        LsfgDiagnosticSessionSnapshot(start, end, root, file)
    }.getOrNull()

    private fun pruneOldSessions(directory: File, keepExisting: Int) {
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keepExisting.coerceAtLeast(0))
            ?.forEach(File::delete)
    }
}
