package app.gamenative.benchmark

import org.json.JSONObject

/** Fixed idle-only experiment options, not arbitrary environment or path setters. */
object TurnipExperimentOptions {
    fun tracePath(runId: String): String {
        require(runId.matches(Regex("[a-f0-9]{32}")))
        return "/storage/emulated/0/Download/FF7RLogs/gpu-$runId-%p-%i.log"
    }
    fun apply(original: String, request: JSONObject): String {
        val algorithm = request.optString("turnip_autotune", "unchanged")
        require(algorithm in setOf("unchanged", "bandwidth", "profiled"))
        val trace = request.optBoolean("gpu_trace", false)
        val fields = mutableListOf<String>()
        if (algorithm != "unchanged") fields.add("TU_AUTOTUNE_ALGO=$algorithm")
        if (trace) {
            fields.add("MESA_GPU_TRACES=print")
            fields.add("MESA_GPU_TRACEFILE=" + tracePath(request.getString("run_id")))
            // Existing driver defaults retain all tracepoints, including path and load/store timing.
        }
        val existing = original.split(Regex("\\s+"))
        for (entry in fields) {
            val key = entry.substringBefore('=') + "="
            require(existing.none { it.startsWith(key) }) { "Existing experiment variable must not be overwritten: $key" }
        }
        return if (fields.isEmpty()) original else original.trimEnd() + " " + fields.joinToString(" ")
    }
}
