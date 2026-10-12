package app.gamenative.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import app.gamenative.diagnostics.BenchmarkRuntimeHooks
import app.gamenative.diagnostics.LsfgDiagnosticExporter
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import com.winlator.core.envvars.EnvVarRedaction
import com.winlator.core.envvars.EnvVars
import com.winlator.xserver.Window
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** The instrumentation thread owns the transaction until setup and guests retire. */
class BenchmarkWorkload(private val instrumentation: Instrumentation,
    override val container: Container, private val request: JSONObject, lab: File) : BenchmarkRuntimeHooks.Session {
    override val appId = request.getString("source_container_id")
    private val workload = request.getJSONObject("workload")
    private val directory = File(lab, request.getString("run_id")).apply { mkdirs() }
    private val statusFile = File(directory, "status.json")
    private val readyPattern = Regex(workload.getString("window_pattern"))
    private val frames = ArrayList<Long>()
    private var start = 0L
    @Volatile private var cancelled = false
    @Volatile private var settingUp = false
    @Volatile private var setupFinished = false
    @Volatile private var readyAt = 0L
    @Volatile private var contentCount = 0
    @Volatile private var runtimeEnvironment: JSONObject? = null
    @Volatile private var sourceCap = -1
    @Volatile private var rendererName = "unavailable"
    @Volatile private var termination: Int? = null
    @Volatile private var phase = "LAUNCHING"
    private var activity: BenchmarkGameActivity? = null
    private var stopControls: (() -> Unit)? = null
    private var server: com.winlator.xserver.XServer? = null
    private var phaseStarted = 0L
    private val sourceConfig = com.winlator.container.ContainerManager(instrumentation.targetContext).getContainerById(appId).configFile
    private val sourceConfigHash = hash(sourceConfig)

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun checkActive() { check(!cancelled) { "Benchmark cancelled" } }
    override fun setup(started: Boolean) { settingUp = started; if (!started) setupFinished = true }
    override fun environment(env: EnvVars) {
        checkActive()
        val expected = request.getJSONObject("runtime_expectations")
        expected.keys().forEach { key -> check(env.get(key) == expected.getString(key)) { "Runtime environment mismatch: $key" } }
        val actual = JSONObject()
        env.forEach { key -> actual.put(key, if (EnvVarRedaction.isRedacted(key)) "<redacted>" else env.get(key)) }
        runtimeEnvironment = actual
    }
    override fun renderer(sourceCap: Int, renderer: String) { this.sourceCap = sourceCap; rendererName = renderer }
    override fun terminated(status: Int) { termination = status }
    override fun controls(server: com.winlator.xserver.XServer, stop: () -> Unit) { this.server = server; stopControls = stop }
    override fun content(window: Window) {
        if (window.isApplicationWindow && readyPattern.containsMatchIn("${window.name} ${window.className}")) {
            contentCount++
            if (readyAt == 0L) readyAt = System.nanoTime()
        }
    }
    override fun frame() {
        if (phase == "MEASURING") synchronized(frames) { if (frames.size < 1000000) frames.add(System.nanoTime()) }
    }

    private fun status(next: String) {
        phase = next
        phaseStarted = SystemClock.uptimeMillis()
        val value = JSONObject().put("run_id", request.getString("run_id")).put("phase", next)
            .put("device_monotonic_ns", System.nanoTime()).put("content_updates", contentCount)
        File(directory, "status.tmp").apply { writeText(value.toString()); check(renameTo(statusFile)) }
        File(directory, "events.jsonl").appendText(value.toString() + "\n")
    }
    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate()) {
            check(!File(directory, "cancel").exists()) { "Host cancelled benchmark" }
            if (phase == "WARMING_UP" || phase == "MEASURING") check(activity?.hasWindowFocus() == true) { "Benchmark lost focus" }
            check(BenchmarkGameState.failure == null) { BenchmarkGameState.failure ?: "Game launch failed" }
            check(termination == null) { "Guest exited before workload completion: $termination" }
            consumeControls()
            check(SystemClock.uptimeMillis() < deadline) { "Readiness/workload timed out" }
            Thread.sleep(50)
        }
    }
    private fun safeFile(root: File, relative: String): File {
        require(!relative.startsWith("/") && relative.split('/').none { it == ".." })
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.canonicalPath + "/")) { "Path escapes benchmark prefix" }
        return file
    }
    private fun stageSaves() {
        val saves = workload.getJSONArray("saves")
        if (saves.length() == 0) return
        val source = com.winlator.container.ContainerManager(instrumentation.targetContext).getContainerById(appId)
        for (index in 0 until saves.length()) {
            val save = saves.getJSONObject(index)
            val from = safeFile(source.rootDir, save.getString("path"))
            require(from.length() in 1..67108864) { "Save exceeds staging limit" }
            check(hash(from) == save.getString("sha256")) { "Workload save hash mismatch" }
            val to = safeFile(container.rootDir, save.getString("path"))
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = true)
            check(to.setLastModified(from.lastModified())) { "Could not preserve save timestamp for Continue selection" }
            check(hash(to) == save.getString("sha256")) { "Staged save hash mismatch" }
        }
    }
    private fun inject(action: JSONObject) {
        check(activity?.hasWindowFocus() == true) { "Benchmark lost input focus" }
        val now = SystemClock.uptimeMillis()
        when (action.getString("type")) {
            "key" -> {
                val code = action.getInt("code")
                require(code in 7..16 || code in 19..22 || code in 29..62 || code == 66 || code in 96..114 || code in 131..142)
                val keyboard = checkNotNull(server).keyboard
                check(keyboard.onKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)))
                val duration = action.optLong("duration_ms", 0)
                require(duration in 0..10000)
                try { if (duration > 0) Thread.sleep(duration) }
                finally { keyboard.onKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0)) }
            }
            "tap" -> {
                val x = action.getDouble("x").toFloat(); val y = action.getDouble("y").toFloat()
                val dimensions = container.screenSize.split('x').map { it.toInt() }
                require(x >= 0 && y >= 0 && x < dimensions[0] && y < dimensions[1])
                val guest = checkNotNull(server)
                guest.injectPointerMove(x.toInt(), y.toInt())
                guest.injectPointerButtonPress(com.winlator.xserver.Pointer.Button.BUTTON_LEFT)
                guest.injectPointerButtonRelease(com.winlator.xserver.Pointer.Button.BUTTON_LEFT)
            }
            "look" -> {
                val dx = action.getInt("dx"); val dy = action.getInt("dy")
                val duration = action.getLong("duration_ms")
                require(dx in -8192..8192 && dy in -8192..8192 && duration in 16..10000)
                val steps = (duration / 16).toInt().coerceAtLeast(1)
                var lastX = 0; var lastY = 0
                for (index in 1..steps) {
                    val x = dx * index / steps; val y = dy * index / steps
                    checkNotNull(server).injectPointerMoveDelta(x - lastX, y - lastY)
                    lastX = x; lastY = y
                    Thread.sleep(duration / steps)
                }
            }
            else -> error("Unsupported workload input")
        }
    }
    private fun consumeControls() {
        if (phase != "WARMING_UP" && phase != "MEASURING") return
        val inbox = File(directory, "commands").apply { mkdirs() }
        for (file in inbox.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}\\.json")) }.take(16)) {
            require(file.length() in 1..8192)
            val command = JSONObject(file.readText())
            val id = file.name.removeSuffix(".json")
            require(command.getString("command_id") == id && command.getString("run_id") == request.getString("run_id"))
            val action = command.getJSONObject("action")
            val reply = JSONObject().put("command_id", id).put("run_id", request.getString("run_id"))
            try {
                check(workload.getString("kind") == "exploration") { "Live controls are allowed only in exploration sessions" }
                val at = SystemClock.uptimeMillis() - phaseStarted
                if (action.getString("type") == "screenshot") {
                    check(activity?.hasWindowFocus() == true)
                    val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(directory, "$id.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                    reply.put("screenshot", "files/benchmark-lab/${request.getString("run_id")}/$id.png")
                } else {
                    inject(action)
                    File(directory, "interactive-input.jsonl").appendText(JSONObject().put("phase", phase)
                        .put("at_ms", at).put("action", action).put("device_monotonic_ns", System.nanoTime()).toString() + "\n")
                }
                reply.put("status", "complete")
            } catch (error: Exception) { reply.put("status", "failed").put("error", error.message) }
            File(directory, "$id.reply.tmp").apply { writeText(reply.toString()); check(renameTo(File(directory, "$id.reply.json"))) }
            file.delete()
        }
    }
    fun run(): JSONObject {
        require(container.containerVariant == Container.BIONIC && container.emulator == "FEXCore") {
            "Runtime attestation currently supports bionic/FEXCore only"
        }
        require(appId.matches(Regex("(?:STEAM|CUSTOM_GAME)_[0-9]+")))
        require(!container.isLaunchRealSteam && !container.isLaunchBionicSteam)
        require(!container.getMicEnabled())
        BenchmarkGameState.failure = null
        stageSaves()
        BenchmarkRuntimeHooks.install(this)
        var measureStart = 0L
        var measureEnd = 0L
        try {
            start = System.nanoTime()
            status("LAUNCHING")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, BenchmarkGameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("lab_app_id", appId)) as BenchmarkGameActivity
            waitFor(workload.getLong("startup_timeout_ms")) { readyAt > 0 && contentCount >= 2 && runtimeEnvironment != null && sourceCap >= 0 }
            check(sourceCap == workload.getInt("source_fps")) { "Effective renderer source cap differs from requested cap: $sourceCap" }
            val runtimeBinaries = BenchmarkSessionManager.attest(instrumentation.targetContext,
                JSONObject().put("artifacts", request.getJSONArray("runtime_artifacts")))
            status("WARMING_UP")
            val warmup = workload.getLong("warmup_ms")
            val warmStart = SystemClock.uptimeMillis()
            val actions = workload.getJSONArray("setup_actions")
            for (index in 0 until actions.length()) {
                val action = actions.getJSONObject(index)
                waitFor(warmup + 1000) { SystemClock.uptimeMillis() - warmStart >= action.getLong("at_ms") }
                inject(action)
            }
            waitFor(warmup + 1000) { SystemClock.uptimeMillis() - warmStart >= warmup }
            val lsfg = app.gamenative.utils.LsfgVkManager.readRuntimeState(container)
            if (container.getExtra("lsfgEnabled", "false") == "true") {
                check(lsfg.readyForGeneration) { "Requested LSFG generation policy is not ready: ${lsfg.status}" }
            }
            val lsfgReadback = JSONObject().put("status", lsfg.status.name).put("fresh", lsfg.fresh)
                .put("multiplier", lsfg.multiplier).put("ready_for_generation", lsfg.readyForGeneration)
                .put("display_confirmation_available", lsfg.displayConfirmationAvailable)
                .put("display_confirmed_generating", lsfg.displayConfirmedGenerating).put("degraded", lsfg.degraded)
            val processSnapshot = JSONArray()
            ProcessHelper.listSubProcesses().forEach { process ->
                val record = JSONObject().put("pid", process.pid).put("name", process.name).put("rss_bytes", process.rssBytes)
                val cpu = runCatching { File("/proc/${process.pid}/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim() }.getOrNull()
                record.put("cpus_allowed_list", cpu ?: JSONObject.NULL)
                processSnapshot.put(record)
            }
            measureStart = System.nanoTime()
            status("MEASURING")
            val duration = workload.getLong("measure_ms")
            val measureClock = SystemClock.uptimeMillis()
            val measuredActions = workload.getJSONArray("actions")
            for (index in 0 until measuredActions.length()) {
                val action = measuredActions.getJSONObject(index)
                waitFor(duration + 1000) { SystemClock.uptimeMillis() - measureClock >= action.getLong("at_ms") }
                inject(action)
            }
            waitFor(duration + 1000) { SystemClock.uptimeMillis() - measureClock >= duration }
            measureEnd = System.nanoTime()
            check(termination == null && BenchmarkGameState.failure == null) { "Game exited during measurement" }
            status("COLLECTING_RESULTS")
            File(directory, "renderer-frames.json").writeText(synchronized(frames) { JSONArray(frames).toString() })
            LsfgDiagnosticExporter.prepareReportFile(instrumentation.targetContext, File(directory, "lsfg-diagnostics.txt"))
            return JSONObject().put("readiness", "matched_application_window_content")
                .put("time_to_first_content_ms", (readyAt - start) / 1e6)
                .put("measurement_start_ns", measureStart).put("measurement_end_ns", measureEnd)
                .put("renderer", rendererName).put("source_fps_cap", sourceCap)
                .put("runtime_environment", runtimeEnvironment).put("environment_verified", true)
                .put("runtime_binary_attestation", runtimeBinaries)
                .put("harness_build", app.gamenative.BuildConfig.VERSION_NAME)
                .put("lsfg_runtime", lsfgReadback)
                .put("guest_process_snapshot", processSnapshot).put("source_config_sha256", sourceConfigHash)
                .put("exploratory", workload.getString("kind") == "exploration")
                .put("guest_exit_status", termination ?: JSONObject.NULL)
                .put("artifacts", "files/benchmark-lab/${request.getString("run_id")}")
        } finally {
            cancelled = true
            status("RETIRING_PROCESSES")
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 120000
            while (settingUp && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            check(!settingUp) { "Setup still active; recovery journal retained" }
            stopControls?.invoke()
            PluviaApp.shutdownEnvironment()
            app.gamenative.utils.PerfSampler.stop()
            val retireDeadline = SystemClock.uptimeMillis() + 15000
            while (ProcessHelper.listSubProcesses().any { it.name.contains("wine", true) || it.name.contains("fex", true) } &&
                SystemClock.uptimeMillis() < retireDeadline) Thread.sleep(100)
            check(ProcessHelper.listSubProcesses().none { it.name.contains("wine", true) || it.name.contains("fex", true) }) {
                "Guest processes remain; recovery journal retained"
            }
            instrumentation.waitForIdleSync()
            check(hash(sourceConfig) == sourceConfigHash) { "Production source config changed during benchmark" }
            BenchmarkRuntimeHooks.retire(this)
            status("RESTORING_CONFIG")
        }
    }
}
