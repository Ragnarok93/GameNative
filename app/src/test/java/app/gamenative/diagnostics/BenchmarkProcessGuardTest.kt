package app.gamenative.diagnostics

import app.gamenative.benchmark.BenchmarkProcessGuard
import org.junit.Assert.*
import org.junit.Test

class BenchmarkProcessGuardTest {
    @Test fun detectsDetachedGameExeAndIgnoresOnlyOwnInspectionProcess() {
        val inventory = "UID PID PPID NAME\n10208 10 1 app.gamenative\n10208 11 10 ps\n10208 12 1 ff7remake_.exe\n2000 13 1 perfetto\n"
        assertEquals(listOf("12:ff7remake_.exe"), BenchmarkProcessGuard.parse(inventory, 10208, 10))
    }
    @Test fun malformedInventoryCannotProveRetirement() {
        assertThrows(IllegalArgumentException::class.java) { BenchmarkProcessGuard.parse("",10208,10) }
        assertThrows(IllegalArgumentException::class.java) { BenchmarkProcessGuard.parse("UID PID PPID NAME\nunparseable",10208,10) }
    }
}
