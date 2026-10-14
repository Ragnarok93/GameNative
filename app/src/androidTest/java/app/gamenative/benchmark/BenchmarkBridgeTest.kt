package app.gamenative.benchmark

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Test

/** Shell-authorized instrumentation; no exported app component or production launch API. */
class BenchmarkBridgeTest {
    @Test
    fun executeLabRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("lab_request") ?: return
        require(path.matches(Regex("cache/lab-[a-f0-9]{32}\\.json")))
        val file = File(instrumentation.targetContext.dataDir, path)
        require(file.length() in 1L..65536L)
        val request = JSONObject(file.readText())
        val result = try {
            // Application startup may create PostHog's replay logcat reader.
            // Retire the owned analytics integration, never whitelist processes
            // by name or weaken the complete-UID guest retirement guard.
            // This affects only this instrumentation process, not preferences.
            com.posthog.PostHog.close()
            val retirementDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (BenchmarkProcessGuard.remaining().isNotEmpty() &&
                android.os.SystemClock.elapsedRealtime() < retirementDeadline) {
                Thread.sleep(50)
            }
            BenchmarkProcessGuard.assertIdle()
            BenchmarkSessionManager.execute(instrumentation.targetContext, request) { container, config, lab ->
                BenchmarkWorkload(instrumentation, container, config, lab).run()
            }
        } catch (failure: Exception) {
            JSONObject().put("run_id", request.optString("run_id"))
                .put("status", "failed").put("error", failure.message ?: failure.javaClass.simpleName)
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "LAB_RESULT=$result\n") })
        check(result.getString("status") == "complete") { result.toString() }
    }
}
