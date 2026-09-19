package app.gamenative.diagnostics

import android.content.Context
import app.gamenative.powercontrol.PowerBaselineScripts
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LsfgDiagnosticExporterTest {
    private lateinit var context: Context
    private lateinit var home: File
    private lateinit var runtimeRoot: File
    private lateinit var wrapper: File
    private lateinit var metrics: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext

        val imageRoot = File(context.filesDir, "imagefs")
        home = File(imageRoot, "home/xuser-CUSTOM_GAME_DIAG_TEST")
        runtimeRoot = File(
            home,
            ".wine/dosdevices/z:/home/${home.name}",
        )
        val configDir = File(runtimeRoot, ".config/lsfg-vk")
        configDir.mkdirs()
        File(configDir, "conf.toml").writeText("adaptive_framegen = true\nfps_limit = 60\n")
        File(configDir, "stats.txt").writeText("adaptive=1\ntarget_fps=60\n")
        File(configDir, "vsync.txt").writeText("vsync")
        File(configDir, "present-vsync.txt").writeText("present")
        File(configDir, "diagnostics.log").writeText("native-event")

        val layer = File(runtimeRoot, ".local/lib/liblsfg-vk-layer.so")
        layer.parentFile?.mkdirs()
        layer.writeText("layer")
        val marker = File(
            runtimeRoot,
            ".local/share/vulkan/implicit_layer.d/.lsfg_vk_runtime_version",
        )
        marker.parentFile?.mkdirs()
        marker.writeText("diag-test")

        wrapper = File(imageRoot, "usr/tmp/wrapper_diag_diag-test.txt")
        wrapper.parentFile?.mkdirs()
        wrapper.writeText("wrapper")

        val metricsDir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            PowerBaselineScripts.DIRECTORY_NAME,
        )
        metricsDir.mkdirs()
        metrics = File(metricsDir, "metrics-123456789.jsonl")
        metrics.writeText("{\"fps\":60.0}\n")
    }

    @After
    fun tearDown() {
        home.deleteRecursively()
        wrapper.delete()
        metrics.delete()
    }

    @Test
    fun discoverArtifacts_findsInternalMetricsFallback_withoutBroadScan() {
        metrics.delete()

        val internalDir = File(
            context.filesDir,
            PowerBaselineScripts.DIRECTORY_NAME,
        ).apply { mkdirs() }
        val internalMetrics = File(internalDir, "metrics-987654321.jsonl").apply {
            writeText("{\"fps\":59.5}\n")
        }

        try {
            val warnings = mutableListOf<String>()
            val artifacts = LsfgDiagnosticExporter.discoverArtifacts(context, warnings)

            assertEquals(
                internalMetrics.canonicalFile,
                artifacts.performanceMetrics?.canonicalFile,
            )
            assertEquals(0, artifacts.scannedNodes)
            assertTrue(warnings.none { it.contains("node limit", ignoreCase = true) })
        } finally {
            internalMetrics.delete()
        }
    }

    @Test
    fun discoverArtifacts_missingOptionalTimeline_doesNotForceGlobalScan() {
        metrics.delete()

        val warnings = mutableListOf<String>()
        val artifacts = LsfgDiagnosticExporter.discoverArtifacts(context, warnings)

        assertEquals(
            File(runtimeRoot, ".config/lsfg-vk/conf.toml").canonicalFile,
            artifacts.config?.canonicalFile,
        )
        assertEquals(
            File(runtimeRoot, ".config/lsfg-vk/stats.txt").canonicalFile,
            artifacts.stats?.canonicalFile,
        )
        assertEquals(null, artifacts.performanceMetrics)
        assertEquals(0, artifacts.scannedNodes)
        assertTrue(warnings.none { it.contains("node limit", ignoreCase = true) })
    }

    @Test
    fun discoverArtifacts_prefersCurrentKnownPaths_withoutBroadScan() {
        val warnings = mutableListOf<String>()
        val artifacts = LsfgDiagnosticExporter.discoverArtifacts(context, warnings)

        assertEquals(
            File(runtimeRoot, ".config/lsfg-vk/conf.toml").canonicalFile,
            artifacts.config?.canonicalFile,
        )
        assertEquals(
            File(runtimeRoot, ".config/lsfg-vk/stats.txt").canonicalFile,
            artifacts.stats?.canonicalFile,
        )
        assertEquals(wrapper.canonicalFile, artifacts.wrapperDiagnostics?.canonicalFile)
        assertEquals(metrics.canonicalFile, artifacts.performanceMetrics?.canonicalFile)
        assertEquals(0, artifacts.scannedNodes)
        assertTrue(warnings.none { it.contains("node limit", ignoreCase = true) })
    }
}
