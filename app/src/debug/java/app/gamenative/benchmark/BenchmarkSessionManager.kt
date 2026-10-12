package app.gamenative.benchmark

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.AtomicFile
import app.gamenative.PluviaApp
import app.gamenative.utils.ContainerUtils
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Only packaged in debug variants; invoked by the separately installed instrumentation APK. */
object BenchmarkSessionManager {
    private val configTypes = mapOf(
        "screenSize" to String::class.java,
        "envVars" to String::class.java,
        "cpuList" to String::class.java,
        "cpuListWoW64" to String::class.java,
        "graphicsDriver" to String::class.java,
        "graphicsDriverVersion" to String::class.java,
        "graphicsDriverConfig" to String::class.java,
        "rendererPresentMode" to String::class.java,
        "dxwrapper" to String::class.java,
        "dxwrapperConfig" to String::class.java,
        "emulator" to String::class.java,
        "fexcoreVersion" to String::class.java,
        "fexcorePreset" to String::class.java,
        "wineVersion" to String::class.java,
        "showFPS" to java.lang.Boolean::class.java,
        "wow64Mode" to java.lang.Boolean::class.java,
    )

    fun execute(context: Context, request: JSONObject): JSONObject {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debug build required" }
        check(request.getInt("protocol_version") == 1) { "Unsupported protocol" }
        val runId = request.getString("run_id")
        require(runId.matches(Regex("[a-f0-9]{32}"))) { "Invalid run ID" }
        val lab = File(context.filesDir, "benchmark-lab").apply { mkdirs() }
        RandomAccessFile(File(lab, "session.lock"), "rw").use { file ->
            val lock = file.channel.tryLock() ?: error("Another lab operation is active")
            lock.use {
                check(PluviaApp.xEnvironment == null) { "Guest environment is active" }
                val journal = File(lab, "pending.json")
                val operation = request.getString("operation")
                check(!journal.exists() || operation == "recover") { "Pending recovery must complete first" }
                val result = when (operation) {
                    "list" -> JSONObject().put("components", listContents(context))
                    "install" -> install(context, request)
                    "config-roundtrip" -> roundtrip(context, request, lab, journal)
                    "recover" -> recover(context, lab, journal)
                    else -> error("Unsupported Phase 1 operation")
                }
                return result.put("protocol_version", 1).put("run_id", runId).put("status", "complete")
            }
        }
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun listContents(context: Context): JSONArray {
        val manager = ContentsManager(context)
        manager.syncContents()
        val contents = JSONArray()
        ContentProfile.ContentType.values().forEach { type ->
            manager.getProfiles(type).forEach { profile ->
                contents.put(
                    JSONObject().put("type", profile.type.toString()).put("version", profile.verName)
                        .put("version_code", profile.verCode),
                )
            }
        }
        return contents
    }

    private fun install(context: Context, request: JSONObject): JSONObject {
        val path = request.getString("package")
        require(path.matches(Regex("cache/lab-component-[a-f0-9]{32}\\.wcp"))) { "Invalid staged package path" }
        val archive = File(context.dataDir, path)
        require(archive.isFile && archive.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        val expectedHash = request.getString("sha256")
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        check(digest(archive) == expectedHash) { "Device package hash mismatch" }
        val manager = ContentsManager(context)
        val type = request.getString("type")
        require(type in setOf("DXVK", "FEXCore", "Turnip", "Proton"))
        require(request.getString("version").matches(Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,127}")))
        manager.syncContents()
        check(manager.getProfiles(ContentProfile.ContentType.getTypeByName(type)).none { it.verName == request.getString("version") }) {
            "Version already exists; immutable installs never replace content"
        }
        var extracted: ContentProfile? = null
        var failure: String? = null
        val extraction = object : ContentsManager.OnInstallFinishedCallback {
            override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                failure = reason.toString()
            }

            override fun onSucceed(profile: ContentProfile) {
                extracted = profile
            }
        }
        try {
            manager.extraContentFile(Uri.fromFile(archive), extraction)
            check(failure == null) { "Extraction rejected: $failure" }
            val profile = checkNotNull(extracted) { "No extracted profile" }
            check(profile.type.toString() == type && profile.verName == request.getString("version") &&
                profile.verCode == request.getInt("version_code")) { "Device manifest does not match package pin" }
            var installed = false
            manager.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    failure = reason.toString()
                }

                override fun onSucceed(profile: ContentProfile) {
                    installed = true
                }
            })
            check(installed && failure == null) { "Install rejected: $failure" }
            manager.syncContents()
            check(manager.getProfiles(profile.type).any { it.verName == profile.verName && it.verCode == profile.verCode }) {
                "Installed content was not registered"
            }
            return JSONObject().put("type", type).put("version", profile.verName)
                .put("version_code", profile.verCode).put("package_sha256", expectedHash)
        } finally {
            ContentsManager.cleanTmpDir(context)
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun roundtrip(context: Context, request: JSONObject, lab: File, journal: File): JSONObject {
        val id = request.getString("container_id")
        require(id.matches(Regex("BENCHMARK_[A-Za-z0-9_-]+"))) { "Only dedicated benchmark containers may be changed" }
        check(ContainerUtils.hasContainer(context, id)) { "Create a dedicated benchmark clone before testing" }
        val container = ContainerUtils.getContainer(context, id)
        val config = container.configFile
        val original = config.readBytes()
        val merged = JSONObject(String(original, Charsets.UTF_8))
        val patch = request.getJSONObject("config")
        require(patch.length() > 0)
        patch.keys().forEach { key ->
            val expected = configTypes[key] ?: error("Unsupported config key: $key")
            val value = patch.get(key)
            require(expected.isInstance(value)) { "Wrong type for $key" }
            merged.put(key, value)
        }
        if (patch.has("screenSize")) {
            require(patch.getString("screenSize").matches(Regex("[1-9][0-9]{1,3}x[1-9][0-9]{1,3}")))
        }
        val backup = File(lab, "original.json")
        atomicWrite(backup, original)
        atomicWrite(journal, JSONObject().put("container_id", id).put("run_id", request.getString("run_id"))
            .put("sha256", digest(backup)).toString().toByteArray())
        try {
            check(PluviaApp.xEnvironment == null) { "Guest started during validation" }
            container.loadData(merged)
            check(container.saveDataChecked()) { "Could not save test config" }
            val effective = JSONObject(config.readText())
            patch.keys().forEach { key ->
                check(effective.has(key) && effective.get(key) == patch.get(key)) { "Config readback mismatch: $key" }
            }
            return JSONObject().put("container_id", id).put("requested_patch", patch)
                .put("effective_persisted_config", effective).put("runtime_verified", false)
                .put("original_sha256", digest(backup)).put("restored", true)
        } finally {
            recover(context, lab, journal)
        }
    }

    private fun recover(context: Context, lab: File, journal: File): JSONObject {
        if (!journal.exists()) return JSONObject().put("recovered", false)
        val pending = JSONObject(journal.readText())
        val id = pending.getString("container_id")
        require(id.matches(Regex("BENCHMARK_[A-Za-z0-9_-]+")))
        val backup = File(lab, "original.json")
        check(digest(backup) == pending.getString("sha256")) { "Snapshot hash mismatch; manual recovery required" }
        val container = ContainerUtils.getContainer(context, id)
        val config = container.configFile
        check(PluviaApp.xEnvironment == null) { "Cannot restore an active guest" }
        atomicWrite(config, backup.readBytes())
        check(digest(config) == pending.getString("sha256")) { "Restore verification failed" }
        container.loadData(JSONObject(config.readText()))
        check(journal.delete()) { "Could not retire recovery journal" }
        check(backup.delete()) { "Could not retire snapshot" }
        return JSONObject().put("recovered", true).put("container_id", id)
    }
}
