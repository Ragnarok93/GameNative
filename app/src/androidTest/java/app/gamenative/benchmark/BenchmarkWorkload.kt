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
    private val start = System.nanoTime()
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
        val value = JSONObject().put("run_id", request.getString("run_id")).put("phase", next)
            .put("device_monotonic_ns", System.nanoTime()).put("content_updates", contentCount)
        File(directory, "status.tmp").apply { writeText(value.toString()); check(renameTo(statusFile)) }
        File(directory, "events.jsonl").appendText(value.toString() + "\n")
    }
    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!predicate()) {
            check(BenchmarkGameState.failure == null) { BenchmarkGameState.failure ?: "Game launch failed" }
            check(termination == null) { "Guest exited before workload completion: $termination" }
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
            val hash = MessageDigest.getInstance("SHA-256").digest(from.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == save.getString("sha256")) { "Workload save hash mismatch" }
            val to = safeFile(container.rootDir, save.getString("path"))
            to.parentFile?.mkdirs()
            from.copyTo(to, overwrite = true)
        }
    }
    private fun inject(action: JSONObject) {
        check(activity?.hasWindowFocus() == true) { "Benchmark lost input focus" }
        val now = SystemClock.uptimeMillis()
        when (action.getString("type")) {
            "key" -> {
                val code = action.getInt("code")
                require(code in 7..288 && code !in setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH))
                instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
            }
            "tap" -> {
                val view = checkNotNull(activity).window.decorView
                val x = action.getDouble("x").toFloat(); val y = action.getDouble("y").toFloat()
                require(x >= 0 && y >= 0 && x < view.width && y < view.height)
                for (event in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val motion = MotionEvent.obtain(now, SystemClock.uptimeMillis(), event, x, y, 0)
                    try { instrumentation.sendPointerSync(motion) } finally { motion.recycle() }
                }
            }
            else -> error("Unsupported workload input")
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
            status("LAUNCHING")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, BenchmarkGameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("lab_app_id", appId)) as BenchmarkGameActivity
            waitFor(workload.getLong("startup_timeout_ms")) { readyAt > 0 && contentCount >= 2 && runtimeEnvironment != null && sourceCap >= 0 }
            check(sourceCap == workload.getInt("source_fps")) { "Effective renderer source cap differs from requested cap: $sourceCap" }
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
            PluviaApp.shutdownEnvironment()
            app.gamenative.utils.PerfSampler.stop()
            val retireDeadline = SystemClock.uptimeMillis() + 15000
            while (ProcessHelper.listSubProcesses().any { it.name.contains("wine", true) || it.name.contains("fex", true) } &&
                SystemClock.uptimeMillis() < retireDeadline) Thread.sleep(100)
            check(ProcessHelper.listSubProcesses().none { it.name.contains("wine", true) || it.name.contains("fex", true) }) {
                "Guest processes remain; recovery journal retained"
            }
            BenchmarkRuntimeHooks.retire(this)
            status("RESTORING_CONFIG")
        }
    }
}
