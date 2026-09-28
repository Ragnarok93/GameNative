package app.gamenative.diagnostics

import app.gamenative.powercontrol.PowerBaselineScripts
import app.gamenative.powercontrol.PowerManager
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
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }
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
            ?.forEach { it.delete() }
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
    fun discoverArtifacts_prefersActiveContainerOverNewerStaleContainerFiles() {
        val context = RuntimeEnvironment.getApplication()
        val activeConfig = File(homeRoot, ".config/lsfg-vk/conf.toml").apply {
            writeText("active=true\n")
        }
        val activeStats = File(homeRoot, ".config/lsfg-vk/stats.txt").apply {
            writeText("active=true\n")
        }
        val activeMarker = File(
            homeRoot,
            ".local/share/vulkan/implicit_layer.d/.lsfg_vk_runtime_version",
        ).apply {
            writeText("active-runtime\n")
        }
        val activeLayer = File(homeRoot, ".local/lib/liblsfg-vk-layer.so").apply {
            writeBytes(byteArrayOf(1))
        }

        val staleRoot = File(imageRoot, "home/stale-diagnostic-container").apply {
            mkdirs()
        }
        File(staleRoot, ".config/lsfg-vk").mkdirs()
        File(staleRoot, ".local/lib").mkdirs()
        File(staleRoot, ".local/share/vulkan/implicit_layer.d").mkdirs()
        val staleConfig = File(staleRoot, ".config/lsfg-vk/conf.toml").apply {
            writeText("active=false\n")
        }
        File(staleRoot, ".config/lsfg-vk/stats.txt").writeText("stale=true\n")
        File(
            staleRoot,
            ".local/share/vulkan/implicit_layer.d/.lsfg_vk_runtime_version",
        ).writeText("stale-runtime\n")
        File(staleRoot, ".local/lib/liblsfg-vk-layer.so")
            .writeBytes(byteArrayOf(2))

        val containerField = PowerManager::class.java
            .getDeclaredField("containerDir")
            .apply { isAccessible = true }
        containerField.set(PowerManager, homeRoot)
        try {
            val now = System.currentTimeMillis()
            listOf(activeConfig, activeStats, activeMarker, activeLayer).forEach {
                assertTrue(it.setLastModified(now))
            }
            assertTrue(staleConfig.setLastModified(now + 10_000))

            val warnings = mutableListOf<String>()
            val artifacts = LsfgDiagnosticExporter.discoverArtifacts(context, warnings)

            assertEquals(activeConfig.canonicalPath, artifacts.config?.canonicalPath)
            assertEquals(activeStats.canonicalPath, artifacts.stats?.canonicalPath)
            assertEquals(activeMarker.canonicalPath, artifacts.runtimeMarker?.canonicalPath)
            assertEquals(activeLayer.canonicalPath, artifacts.layer?.canonicalPath)
        } finally {
            containerField.set(PowerManager, null)
            staleRoot.deleteRecursively()
        }
    }

    @Test
    fun segmentNativeEvents_groupsRuntimeSessionsAndConfigEpochs() {
        val input = """
            09-20 01:00:00 I/LSFG_METRICS: runtime_session_id=101 config_revision=1 output_fps=60
            09-20 01:00:01 I/LSFG_EVENT: runtime_session_id=101 config_revision=2 cost_raise=1
            09-20 01:00:02 I/LSFG_FLOW: runtime_session_id=101 config_revision=1 requested=0.9
            09-20 01:00:03 I/LSFG_METRICS: runtime_session_id=202 config_revision=1 output_fps=75
            09-20 01:00:04 I/LSFG: legacy line without epoch fields
        """.trimIndent()

        val grouped = LsfgDiagnosticExporter.segmentNativeEvents(input)

        val session101Revision1 =
            "--- runtime_session_id=101 config_revision=1 ---"
        val session101Revision2 =
            "--- runtime_session_id=101 config_revision=2 ---"
        val session202Revision1 =
            "--- runtime_session_id=202 config_revision=1 ---"
        val legacy =
            "--- runtime_session_id=unsegmented config_revision=unknown ---"

        assertTrue(grouped.contains(session101Revision1))
        assertTrue(grouped.contains(session101Revision2))
        assertTrue(grouped.contains(session202Revision1))
        assertTrue(grouped.contains(legacy))
        assertTrue(grouped.indexOf(session101Revision1) < grouped.indexOf(session101Revision2))
        assertTrue(grouped.indexOf(session101Revision2) < grouped.indexOf(session202Revision1))
        assertTrue(grouped.indexOf(session202Revision1) < grouped.indexOf(legacy))
        assertTrue(grouped.contains("LSFG_METRICS: runtime_session_id=101 config_revision=1"))
        assertTrue(grouped.contains("LSFG_FLOW: runtime_session_id=101 config_revision=1"))
    }


    @Test
    fun nativeLsfgLogFilter_keepsAllRuntimePrefixesAndRejectsUnrelatedNoise() {
        val accepted = listOf(
            "09-26 I System.out: LSFG_METRICS runtime_session_id=9 config_revision=2",
            "09-26 I System.out: LSFG_EVENT runtime_session_id=9 config_revision=2",
            "09-26 I System.out: LSFG_FLOW runtime_session_id=9 config_revision=2",
            "09-26 I System.out: LSFG_WSI runtime_session_id=9 swapchain_generation=4 actual_mode=MAILBOX",
            "09-26 I System.out: LSFG_OUTCOME runtime_session_id=9 window_ms=1000 outcome_confidence=wsi_accepted_only",
            "09-26 I System.out: LSFG_PROVENANCE invoke name=vkQueuePresentKHR",
            "09-26 I System.out: lsfg-vk: init stage=swapchain-ready",
            "09-26 I LsfgVkManager: LSFG layer armed target=game.exe multiplier=2",
        )
        accepted.forEach { line ->
            assertTrue(
                "Expected LSFG diagnostic line to survive filtering: $line",
                LsfgDiagnosticExporter.isNativeLsfgLogLine(line),
            )
        }

        assertTrue(
            !LsfgDiagnosticExporter.isNativeLsfgLogLine(
                "09-26 I RenderThread: unrelated compositor diagnostic",
            ),
        )
    }

    @Test
    fun nativeLogcatSelection_usesPidFallbackWhenUidHasNoNativeRecords() {
        val selected = LsfgDiagnosticExporter.selectNativeLogcat(
            uidLogcat = "09-28 I logcat: unrelated line",
            appLogcat = "09-28 I System.out: lsfg-vk: LSFG_OUTCOME runtime_session_id=1",
        )

        assertEquals("pid_logcat_fallback", selected.source)
        assertTrue(selected.text.contains("LSFG_OUTCOME"))
    }

    @Test
    fun nativeLogcatSelection_mergesUidAndPidRecordsWithoutDuplicates() {
        val shared = "09-28 I System.out: LSFG_WSI runtime_session_id=1"
        val selected = LsfgDiagnosticExporter.selectNativeLogcat(
            uidLogcat = "$shared\n09-28 I System.out: LSFG_OUTCOME runtime_session_id=1",
            appLogcat = "$shared\n09-28 I System.out: lsfg-vk: delivery-metrics",
        )

        assertEquals("uid+pid_logcat", selected.source)
        assertEquals(1, selected.text.lineSequence().count { it == shared })
        assertTrue(selected.text.contains("LSFG_OUTCOME"))
        assertTrue(selected.text.contains("delivery-metrics"))
    }

    @Test
    fun uidLogcatCommand_supportsFullTwentyThousandLineDiagnosticTail() {
        val command = LsfgDiagnosticExporter.uidLogcatCommand(
            lineCount = 20_000,
            uid = 10_774,
        )

        assertTrue(command.contains("20000"))
        assertTrue(command.contains("--uid=10774"))
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
    @Test
    fun diagnosticExport_isNotBoundToSettingsCompositionLifetime() {
        val source = repoFile(
            "app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupDebug.kt",
        ).readText()

        assertTrue(source.contains("LsfgDiagnosticExporter.exportAsync"))
        assertTrue(source.contains("context.applicationContext"))
        assertTrue(!source.contains("diagnosticsScope.launch"))
        assertTrue(!source.contains("rememberCoroutineScope"))
    }

}
