package app.gamenative.utils

import android.app.Application
import app.gamenative.powercontrol.metrics.CpuUsageSource
import app.gamenative.powercontrol.metrics.FrameTimeRing
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LsfgPressureAvailabilityAuditTest {
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("lsfg-pressure-audit").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    private fun sample(frames: Int = 20, fps: Float = 30f, gpu: Float? = null) = MetricsSnapshot(
        System.currentTimeMillis(), fps, 33f, 35f, 40f, 1, frames,
        50f, CpuUsageSource.PROC_STAT, gpu, 55, 54, 4)
    private fun pressure() = File(root, ".config/lsfg-vk/runtime-pressure.txt").readText()
    private fun stats(text: String, modified: Long = System.currentTimeMillis()) =
        File(root, ".config/lsfg-vk/stats.txt").apply {
            parentFile!!.mkdirs(); writeText(text); assertTrue(setLastModified(modified))
        }

    @Test fun missingGpuDoesNotSuppressIndependentThermalOrSourcePressure() {
        assertTrue(LsfgVkManager.publishRuntimePressure(root, sample()))
        assertTrue(pressure().contains("gpu_usage_percent=-1.0"))
        assertTrue(pressure().contains("thermal_status=4"))
        assertTrue(pressure().contains("source_metrics_valid=1"))
        assertTrue(pressure().contains("source_fps=30.00"))
    }
    @Test fun emptySourceWindowIsUnavailableInsteadOfHealthyZero() {
        assertTrue(LsfgVkManager.publishRuntimePressure(root, sample(frames = 0, fps = 0f, gpu = 80f)))
        assertTrue(pressure().contains("source_metrics_valid=0"))
        assertTrue(pressure().contains("source_fps=-1.00"))
        assertTrue(pressure().contains("frame_time_p95_ms=-1.00"))
        assertTrue(pressure().contains("slow_frame_ratio=-1.0000"))
    }
    @Test fun measuredZeroWithFrameEvidenceRemainsValid() {
        assertTrue(LsfgVkManager.publishRuntimePressure(root, sample(fps = 0f, gpu = 0f)))
        assertTrue(pressure().contains("source_metrics_valid=1"))
        assertTrue(pressure().contains("source_fps=0.00"))
        assertTrue(pressure().contains("gpu_usage_percent=0.0"))
    }
    @Test fun rejectedEpochCannotOverwriteCurrentPressureFile() {
        assertTrue(LsfgVkManager.publishRuntimePressure(root, sample(gpu = 80f)))
        val before = pressure()
        assertFalse(LsfgVkManager.publishRuntimePressure(root, sample(gpu = 99f),
            expectedFrameEpoch = FrameTimeRing.generation() + 1))
        assertEquals(before, pressure())
    }
    @Test fun legacySourceStatsRejectEmptyFutureStaleAndNonFiniteMeasurements() {
        val now = System.currentTimeMillis()
        for ((text, modified) in listOf(
            "source_frames_total=0\nsource_fps=30" to now,
            "source_frames_total=10\nsource_fps=NaN" to now,
            "source_frames_total=10\nsource_fps=30" to now + 500,
            "source_frames_total=10\nsource_fps=30" to now - 2001)) {
            stats(text, modified)
            assertNull(LsfgVkManager.readFreshLegacySourceFps(root, now))
        }
    }
    @Test fun sourceAndPhysicalOutputZeroAreValidWithTheirOwnEvidence() {
        stats("source_frames_total=10\nsource_fps=0\nconfirmed_output_valid=1\nfps=0")
        assertEquals(0f, LsfgVkManager.readFreshLegacySourceFps(root) ?: -1f, 0f)
        assertEquals(0f, LsfgVkManager.readFreshOutputFps(root) ?: -1f, 0f)
    }
    @Test fun logicalFpsCannotBecomePhysicalOutputWithoutConfirmationFlag() {
        stats("source_frames_total=10\nsource_fps=30\nfps=120")
        assertNull(LsfgVkManager.readFreshOutputFps(root))
    }
}
