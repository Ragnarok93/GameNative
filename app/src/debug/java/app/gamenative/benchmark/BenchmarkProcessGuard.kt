package app.gamenative.benchmark

/** Fail closed on inventory errors; guest executables need not be named Wine/FEX. */
object BenchmarkProcessGuard {
    internal fun parse(output: String, uid: Int, self: Int): List<String> {
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty() && lines[0].trim().split(Regex("\\s+")) == listOf("UID", "PID", "PPID", "NAME"))
        return lines.drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"), limit = 4)
            require(fields.size == 4)
            val owner = fields[0].toInt(); val pid = fields[1].toInt(); val parent = fields[2].toInt()
            if (owner == uid && pid != self && !(parent == self && fields[3] == "ps")) "$pid:${fields[3]}" else null
        }
    }

    fun remaining(): List<String> {
        val process = ProcessBuilder("ps", "-A", "-o", "UID,PID,PPID,NAME").start()
        try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { "Could not inventory benchmark processes" }
            return parse(output, android.os.Process.myUid(), android.os.Process.myPid())
        } finally { process.destroy() }
    }

    fun assertIdle() { check(remaining().isEmpty()) { "App-owned subprocesses must retire before configuration restoration" } }
}
