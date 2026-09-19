package app.gamenative.diagnostics

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
    private lateinit var imageRoot: File
    private lateinit var homeRoot: File
    private lateinit var metricsRoot: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        imageRoot = File(context.filesDir, "imagefs")
        homeRoot = File(imageRoot, "home/diagnostic-test-${System.nanoTime()}")
        metricsRoot = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            PowerBaselineScripts.DIRECTORY_NAME,
        )

        File(homeRoot, ".config/lsfg-vk").mkdirs()
        File(homeRoot, ".local/lib").mkdirs()
        File(homeRoot, ".local/share/vulkan/implicit_layer.d").mkdirs()
        File(imageRoot, "usr/tmp").mkdirs()
        metricsRoot.mkdirs()
    }

    @After
    fun tearDown() {
        homeRoot.deleteRecursively()
        File(imageRoot, "usr/tmp/wrapper_diag_diagnostic-test.txt").delete()
        metricsRoot.listFiles()
            ?.filter { it.name.startsWith("metrics-diagnostic-test-") }
            ?.forEach(File::delete)
    }

    @Test
    fun discoverArtifacts_usesDeterministicPathsWithoutRecursiveScan() {
        val context = RuntimeEnvironment.getApplication()
        val config = File(homeRoot, ".config/lsfg-vk/conf.toml").apply {
            writeText("version = 1\n")
        }
        val stats = File(homeRoot, ".config/lsfg-vk/stats.txt").apply {
            writeText("state=generating\n")
        }
        val marker = File(
            homeRoot,
            ".local/share/vulkan/implicit_layer.d/.lsfg_vk_runtime_version",
        ).apply {
            writeText("gamenative-adaptive-test-r1")
        }
        val layer = File(homeRoot, ".local/lib/liblsfg-vk-layer.so").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val wrapper = File(
            imageRoot,
            "usr/tmp/wrapper_diag_diagnostic-test.txt",
        ).apply {
            writeText("wrapper")
        }
        val metrics = File(
            metricsRoot,
            "metrics-diagnostic-test-${System.currentTimeMillis()}.jsonl",
        ).apply {
            writeText("{\"fps\":60}\n")
        }

        val now = System.currentTimeMillis()
        listOf(config, stats, marker, layer, wrapper, metrics).forEachIndexed { index, file ->
            assertTrue(file.setLastModified(now + index))
        }

        val warnings = mutableListOf<String>()
        val artifacts = LsfgDiagnosticExporter.discoverArtifacts(context, warnings)

        assertEquals(config.canonicalPath, artifacts.config?.canonicalPath)
        assertEquals(stats.canonicalPath, artifacts.stats?.canonicalPath)
        assertEquals(marker.canonicalPath, artifacts.runtimeMarker?.canonicalPath)
        assertEquals(layer.canonicalPath, artifacts.layer?.canonicalPath)
        assertEquals(wrapper.canonicalPath, artifacts.wrapperDiagnostics?.canonicalPath)
        assertEquals(metrics.canonicalPath, artifacts.performanceMetrics?.canonicalPath)
        assertEquals(0, artifacts.scannedNodes)
        assertTrue(warnings.none { it.contains("node limit") })
    }

    @Test
    fun uidLogcatCommand_collectsSameUidProcessesWithoutPidFilter() {
        val command = LsfgDiagnosticExporter.uidLogcatCommand(
            lineCount = 12_000,
            uid = 10_774,
        )

        assertEquals("logcat", command.first())
        assertTrue(command.contains("-d"))
        assertTrue(command.contains("-t"))
        assertTrue(command.contains("12000"))
        assertTrue(command.contains("--uid=10774"))
        assertTrue(command.none { it.startsWith("--pid") })
    }
}
