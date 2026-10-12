package app.gamenative.benchmark

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.AtomicFile
import app.gamenative.PluviaApp
import app.gamenative.utils.ContainerUtils
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.container.ContainerManager
import com.winlator.container.Container
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
        "extraData" to JSONObject::class.java,
        "containerVariant" to String::class.java,
        "displayRendererMode" to String::class.java,
        "execArgs" to String::class.java,
        "suspendPolicy" to String::class.java,
        "micEnabled" to java.lang.Boolean::class.java,
        "launchRealSteam" to java.lang.Boolean::class.java,
        "launchBionicSteam" to java.lang.Boolean::class.java,
    )

    private val extraKeys = setOf("fpsLimiterEnabled", "fpsLimiterTarget", "lsfgEnabled",
        "lsfgMultiplier", "lsfgFixedMultiplier", "lsfgFramegenMode", "lsfgAdaptiveTargetFps",
        "lsfgFlowScale", "lsfgFlowScaleMode", "lsfgAdaptiveFlowPreset", "lsfgPerformanceMode",
        "lsfgPresentMode", "lsfgFrameQueueEnabled", "lsfgFrameQueueTarget", "lsfgBackend")

    fun execute(context: Context, request: JSONObject,
        workload: ((Container, JSONObject, File) -> JSONObject)? = null): JSONObject {
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
                    "prepare" -> prepare(context, request)
                    "config-roundtrip" -> roundtrip(context, request, lab, journal)
                    "run" -> roundtrip(context, request, lab, journal, checkNotNull(workload))
                    "attest" -> attest(context, request)
                    "inspect" -> inspect(context, request)
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

    private fun prepare(context: Context, request: JSONObject): JSONObject {
        val sourceId = request.getString("source_container_id")
        val id = request.getString("container_id")
        require(sourceId.matches(Regex("(?:STEAM|CUSTOM_GAME)_[0-9]+")))
        require(id.matches(Regex("BENCHMARK_[A-Za-z0-9_-]+")))
        val manager = ContainerManager(context)
        check(manager.hasContainer(sourceId)) { "Source game container does not exist" }
        check(!manager.hasContainer(id)) { "Benchmark container already exists" }
        val source = manager.getContainerById(sourceId)
        val before = digest(source.configFile)
        val data = JSONObject(source.configFile.readText()).put("name", id)
        // Existing manager initializes a separate prefix. Never copy/rename the
        // source prefix or saves, and never silently reuse a production ID.
        val created = checkNotNull(manager.createContainer(id, data)) { "Could not initialize benchmark container" }
        check(created.configFile.isFile && JSONObject(created.configFile.readText()).getString("id") == id)
        check(digest(source.configFile) == before) { "Source configuration changed during preparation" }
        return JSONObject().put("container_id", id).put("source_container_id", sourceId)
            .put("source_config_sha256", before).put("config_sha256", digest(created.configFile))
            .put("prefix_initialized", true).put("game_saves_copied", false)
    }

    fun attest(context: Context, request: JSONObject): JSONObject {
        val records = JSONArray()
        val artifacts = request.getJSONArray("artifacts")
        require(artifacts.length() in 1..128)
        for (index in 0 until artifacts.length()) {
            val artifact = artifacts.getJSONObject(index)
            val path = artifact.getString("path")
            val base = if (path.startsWith("native/")) File(context.applicationInfo.nativeLibraryDir) else context.dataDir
            val file = File(base, if (path.startsWith("native/")) path.removePrefix("native/") else path).canonicalFile
            require(file.path.startsWith(base.canonicalPath + "/") && file.isFile) { "Artifact outside allowed roots or missing" }
            val hash = digest(file)
            check(hash == artifact.getString("sha256")) { "Artifact hash mismatch: $path" }
            records.put(JSONObject().put("path", path).put("sha256", hash))
        }
        return JSONObject().put("verified_artifacts", records)
    }

    private fun inspect(context: Context, request: JSONObject): JSONObject {
        val id = request.getString("container_id")
        require(id.matches(Regex("BENCHMARK_[A-Za-z0-9_-]+")))
        val container = ContainerUtils.getContainer(context, id)
        val file = container.configFile.canonicalFile
        require(file.path.startsWith(context.dataDir.canonicalPath + "/"))
        return JSONObject().put("config_sha256", digest(file))
            .put("config_path", file.path.removePrefix(context.dataDir.canonicalPath + "/"))
            .put("config", JSONObject(file.readText()))
    }

    private fun roundtrip(context: Context, request: JSONObject, lab: File, journal: File,
        workload: ((Container, JSONObject, File) -> JSONObject)? = null): JSONObject {
        val id = request.getString("container_id")
        val fault = request.optString("fault", "")
        require(fault.isEmpty() || (workload == null && fault == "process-death-after-write"))
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
            if (key == "extraData") {
                val extras = value as JSONObject
                require(extras.keys().asSequence().all { it in extraKeys }) { "Unsupported benchmark extra" }
                val existing = merged.optJSONObject(key) ?: JSONObject()
                extras.keys().forEach { extra ->
                    require(extras.get(extra) is String) { "Benchmark extras must use canonical string values" }
                    existing.put(extra, extras.getString(extra))
                }
                merged.put(key, existing)
            } else merged.put(key, value)
        }
        if (patch.has("screenSize")) {
            require(patch.getString("screenSize").matches(Regex("[1-9][0-9]{1,3}x[1-9][0-9]{1,3}")))
        }
        if (workload != null) {
            val token = request.getString("cache_token")
            require(token.matches(Regex("[a-f0-9]{32}")))
            val cache = File(container.rootDir, ".benchmark-cache/$token")
            require(cache.canonicalPath.startsWith(container.rootDir.canonicalPath + "/"))
            when (request.getString("cache_mode")) {
                "cold" -> check(!cache.exists() && cache.mkdirs()) { "Cold cache must be a fresh benchmark directory" }
                "warm" -> check(cache.isDirectory) { "Warm cache requires the paired cold trial" }
                else -> error("Unknown cache mode")
            }
            val env = com.winlator.core.envvars.EnvVars(merged.getString("envVars"))
            env.put("DXVK_STATE_CACHE_PATH", cache.path)
            env.put("MESA_SHADER_CACHE_DIR", cache.path)
            merged.put("envVars", env.toString())
            // Record the generated paths as part of the requested patch too.
            patch.put("envVars", env.toString())
            request.getJSONObject("runtime_expectations").put("DXVK_STATE_CACHE_PATH", cache.path)
                .put("MESA_SHADER_CACHE_DIR", cache.path)
        }
        val backup = File(lab, "original.json")
        atomicWrite(backup, original)
        atomicWrite(journal, JSONObject().put("container_id", id).put("run_id", request.getString("run_id"))
            .put("sha256", digest(backup)).toString().toByteArray())
        try {
            check(PluviaApp.xEnvironment == null) { "Guest started during validation" }
            container.loadData(merged)
            check(container.saveDataChecked()) { "Could not save test config" }
            if (fault == "process-death-after-write") android.os.Process.killProcess(android.os.Process.myPid())
            val persisted = JSONObject(config.readText())
            val effective = JSONObject(persisted.toString())
            // Container's serializer omits these default/empty values. Reload
            // through the manager to verify their meaning rather than reject a
            // deliberate empty patch or infer it from the requested value.
            val readback = ContainerUtils.getContainer(context, id)
            effective.put("graphicsDriverConfig", readback.getGraphicsDriverConfig())
            effective.put("dxwrapperConfig", readback.getDXWrapperConfig())
            effective.put("wineVersion", readback.getWineVersion())
            patch.keys().forEach { key ->
                if (key == "extraData") {
                    patch.getJSONObject(key).keys().forEach { extra ->
                        check(effective.getJSONObject(key).getString(extra) == patch.getJSONObject(key).getString(extra)) {
                            "Config readback mismatch: $extra"
                        }
                    }
                } else check(effective.has(key) && effective.get(key) == patch.get(key)) { "Config readback mismatch: $key" }
            }
            val result = JSONObject().put("container_id", id).put("requested_patch", patch)
                .put("persisted_config", persisted).put("effective_container_config", effective).put("runtime_verified", false)
                .put("original_sha256", digest(backup)).put("restored", true)
            if (workload != null) {
                val artifacts = attest(context, request)
                result.put("binary_attestation", artifacts)
                result.put("execution", workload(container, request, lab))
            }
            return result
        } finally {
            recover(context, lab, journal)
        }
    }

    private fun recover(context: Context, lab: File, journal: File): JSONObject {
        if (!journal.exists()) return JSONObject().put("recovered", false)
        val pending = JSONObject(journal.readText())
        val id = pending.getString("container_id")
        require(id.matches(Regex("BENCHMARK_[A-Za-z0-9_-]+")))
        check(!app.gamenative.diagnostics.BenchmarkRuntimeHooks.owns(id)) { "Benchmark runtime must retire before recovery" }
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
