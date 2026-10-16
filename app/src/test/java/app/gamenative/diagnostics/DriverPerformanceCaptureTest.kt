package app.gamenative.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverPerformanceCaptureTest {
    @Test
    fun buildFrameCsv_writesElapsedTimeAndFrameDeltas() {
        val start = 1_000_000_000L
        val timestamps = longArrayOf(
            start,
            start + 16_000_000L,
            start + 50_000_000L,
        )

        val csv = DriverPerformanceCapture.buildFrameCsv(start, timestamps, timestamps.size)
        val rows = csv.trim().lines()

        assertEquals("elapsed_ms,frame_delta_ms,timestamp_ns", rows.first())
        assertEquals("0.000,,1000000000", rows[1])
        assertEquals("16.000,16.000,1016000000", rows[2])
        assertEquals("50.000,34.000,1050000000", rows[3])
    }

    @Test
    fun buildFrameCsv_emptyCaptureStillHasHeader() {
        val csv = DriverPerformanceCapture.buildFrameCsv(null, LongArray(0), 0)

        assertEquals("elapsed_ms,frame_delta_ms,timestamp_ns", csv.trim())
        assertTrue(csv.endsWith("\n"))
    }

    @Test
    fun findDxvkLogFiles_returnsOnlyLogFilesInStableNameOrder() {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "dxvk-log-test-${System.nanoTime()}",
        )
        assertTrue(directory.mkdirs())
        try {
            File(directory, "z_game_dxgi.log").writeText("dxgi")
            File(directory, "a_game_d3d11.LOG").writeText("d3d11")
            File(directory, "notes.txt").writeText("not a DXVK log")
            File(directory, "ignored.log").mkdir()

            val files = DriverPerformanceCapture.findDxvkLogFiles(directory)

            assertEquals(listOf("a_game_d3d11.LOG", "z_game_dxgi.log"), files.map { it.name })
        } finally {
            directory.deleteRecursively()
        }
    }
}
