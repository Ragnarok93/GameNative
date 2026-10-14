package app.gamenative.benchmark

import android.content.Context
import android.util.AtomicFile
import app.gamenative.utils.ContainerUtils
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, shell instrumentation only. No UI, exported component or game launch. */
object ExistingDxvkControl {
    const val ID = "CUSTOM_GAME_227938910"
    private const val FLAG = "DXVK_FF7R_RENDER_PASSES=1"
    fun hash(file: File): String {
        val h = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; h.update(buffer, 0, n) }
        }
        return h.digest().joinToString("") { "%02x".format(it) }
    }
    private fun write(file: File, value: JSONObject) {
        val a = AtomicFile(file); val out = a.startWrite()
        try { out.write(value.toString().toByteArray()); a.finishWrite(out) }
        catch (e: Throwable) { a.failWrite(out); throw e }
    }
    private fun checkedSave(config: File, action: () -> Unit) {
        val pristine = config.readBytes()
        try { action() } catch (failure: Throwable) {
            val atomic = AtomicFile(config)
            val out = atomic.startWrite()
            try { out.write(pristine); atomic.finishWrite(out) }
            catch (restore: Throwable) { atomic.failWrite(out); failure.addSuppressed(restore); throw failure }
            check(config.readBytes().contentEquals(pristine)) { "Immediate rollback verification failed" }
            throw failure
        }
    }
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun otherFields(value: JSONObject): String = canonical(JSONObject(value.toString()).apply {
        remove("dxwrapperConfig"); remove("envVars")
    })
    fun component(context: Context, request: JSONObject): JSONObject {
        val typeName = request.getString("type")
        require(typeName in setOf("DXVK", "FEXCore", "Turnip", "Proton"))
        val version = request.getString("version")
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,127}")))
        val manager = ContentsManager(context); manager.syncContents()
        val profile = manager.getProfiles(ContentProfile.ContentType.getTypeByName(typeName))
            ?.singleOrNull { it.verName == version } ?: return JSONObject().put("installed", false)
        check(profile.verCode == request.getInt("version_code")) { "Immutable version-code conflict" }
        val pins = request.getJSONObject("payload_sha256")
        require(pins.length() in 1..128)
        if (typeName == "DXVK") require(setOf("x64/d3d11.dll", "x64/dxgi.dll", "x32/d3d11.dll", "x32/dxgi.dll").all { pins.has(it) })
        val root = ContentsManager.getInstallDir(context, profile).canonicalFile
        val records = JSONArray()
        pins.keys().forEach { path ->
            require(path.matches(Regex("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")) && path.split('/').none { it == ".." })
            val file = ContentsManager.getSourceFile(context, profile, path).canonicalFile
            require(file.path.startsWith(root.path + "/") && file.isFile)
            val expected = pins.getString(path); require(expected.matches(Regex("[a-f0-9]{64}")))
            check(hash(file) == expected) { "Installed payload mismatch: $path" }
            records.put(JSONObject().put("path", path).put("sha256", expected))
        }
        return JSONObject().put("installed", true).put("type", typeName).put("version", profile.verName)
            .put("version_code", profile.verCode).put("verified_payloads", records)
    }
    fun execute(context: Context, request: JSONObject, lab: File): JSONObject {
        BenchmarkProcessGuard.assertIdle()
        val operation = request.getString("operation")
        if (operation == "component-inspect") return component(context, request)
        require(request.getString("container_id") == ID) { "Only explicitly authorized existing FF7R container supported" }
        val container = ContainerUtils.getContainer(context, ID)
        val config = container.configFile
        require(config.canonicalPath.startsWith(context.dataDir.canonicalPath + "/"))
        val engine = File(container.rootDir, ".wine/drive_c/users/xuser/Documents/My Games/FINAL FANTASY VII REMAKE/Saved/Config/WindowsNoEditor/Engine.ini")
        require(engine.isFile && engine.canonicalPath.startsWith(container.rootDir.canonicalPath + "/"))
        val journal = File(lab, "existing-dxvk-pending.json")
        val before = JSONObject(config.readText())
        if (operation == "existing-dxvk-inspect") return JSONObject().put("config", before)
            .put("config_sha256", hash(config)).put("engine_sha256", hash(engine))
            .put("selection", if (journal.exists()) JSONObject(journal.readText()) else JSONObject.NULL)
            .put("runtime_loaded_verified", false)
        require(request.getBoolean("allow_existing_container")) { "Explicit existing-container authorization required" }
        if (operation == "existing-dxvk-restore") {
            check(journal.exists()) { "No pending selection" }
            val pending = JSONObject(journal.readText())
            check(request.getString("selection_run_id") == pending.getString("run_id")) { "Wrong selection owner" }
            check(hash(engine) == pending.getString("engine_sha256")) { "Engine.ini changed; manual inspection required" }
            val currentWrapper = before.getString("dxwrapperConfig"); val currentEnv = before.getString("envVars")
            val alreadyRestored = currentWrapper == pending.getString("original_wrapper") && currentEnv == pending.getString("original_env")
            check(alreadyRestored || (currentWrapper == pending.getString("selected_wrapper") && currentEnv == pending.getString("selected_env"))) {
                "Selected fields changed outside this transaction; refusing to overwrite"
            }
            if (!alreadyRestored) checkedSave(config) {
                container.setDXWrapperConfig(pending.getString("original_wrapper"))
                container.setEnvVars(pending.getString("original_env"))
                check(container.saveDataChecked())
                val after = JSONObject(config.readText())
                check(otherFields(after) == otherFields(before)) { "Unrelated field changed during restore" }
                check(after.getString("dxwrapperConfig") == pending.getString("original_wrapper") && after.getString("envVars") == pending.getString("original_env"))
            }
            check(hash(engine) == pending.getString("engine_sha256"))
            check(journal.delete())
            return JSONObject().put("restored", true).put("config", JSONObject(config.readText()))
                .put("engine_sha256", hash(engine)).put("runtime_loaded_verified", false)
        }
        require(operation == "existing-dxvk-select")
        check(!journal.exists()) { "Restore pending selection first" }
        check(hash(config) == request.getString("expected_config_sha256")) { "Configuration changed since preflight" }
        check(hash(engine) == request.getString("expected_engine_sha256")) { "Engine.ini changed since preflight" }
        check(before.getString("dxwrapper") == "dxvk")
        val pin = request.getJSONObject("component")
        require(pin.getString("type") == "DXVK")
        val attested = component(context, pin); check(attested.getBoolean("installed")) { "Pinned component not installed" }
        val source = pin.getString("source_commit"); require(source.matches(Regex("[a-f0-9]{40}")))
        val installed = ContentsManager(context).apply { syncContents() }.getProfiles(ContentProfile.ContentType.getTypeByName("DXVK"))
            .single { it.verName == pin.getString("version") && it.verCode == pin.getInt("version_code") }
        for (path in listOf("x64/d3d11.dll", "x64/dxgi.dll", "x32/d3d11.dll", "x32/dxgi.dll")) {
            check(ContentsManager.getSourceFile(context, installed, path).readBytes().toString(Charsets.ISO_8859_1).contains(source)) {
                "Installed source stamp mismatch: $path"
            }
        }
        val selector = pin.getString("version") + "-" + pin.getInt("version_code")
        val parts = before.getString("dxwrapperConfig").split(',')
        require(parts.count { it.startsWith("version=") } == 1)
        val selectedWrapper = parts.joinToString(",") { if (it.startsWith("version=")) "version=$selector" else it }
        val env = before.getString("envVars"); require(!env.split(' ').any { it.startsWith("DXVK_FF7R_RENDER_PASSES=") })
        val selectedEnv = if (request.getBoolean("render_passes")) "$env $FLAG" else env
        val pending = JSONObject().put("container_id", ID).put("run_id", request.getString("run_id"))
            .put("engine_sha256", hash(engine)).put("original_wrapper", before.getString("dxwrapperConfig"))
            .put("original_env", env).put("selected_wrapper", selectedWrapper).put("selected_env", selectedEnv)
            .put("component", pin).put("state", "prepared")
            .put("original_config_sha256", hash(config))
            .put("original_config_base64", android.util.Base64.encodeToString(config.readBytes(), android.util.Base64.NO_WRAP))
        write(journal, pending)
        // Existing native manager serializer, never externally constructed config files.
        checkedSave(config) {
        container.setDXWrapperConfig(selectedWrapper); container.setEnvVars(selectedEnv)
        check(container.saveDataChecked()) { "Selection write failed; pending recovery retained" }
        val after = JSONObject(config.readText())
        check(otherFields(after) == otherFields(before)) { "Unrelated field changed; pending recovery retained" }
        check(after.getString("dxwrapperConfig") == selectedWrapper && after.getString("envVars") == selectedEnv)
        check(hash(engine) == pending.getString("engine_sha256"))
        }
        write(journal, pending.put("state", "applied"))
        return JSONObject().put("selected", true).put("selection_run_id", pending.getString("run_id"))
            .put("persisted_config", after).put("config_sha256", hash(config)).put("engine_sha256", hash(engine))
            .put("component", attested).put("source_commit", source).put("runtime_loaded_verified", false)
    }
}
