package app.gamenative.utils

import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LsfgCompatibilityDiagnosticsTest {
    private lateinit var rootDir: File
    private val nowMs = 1_800_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("lsfg-compat-test").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun healthyRuntime_passesAllBlockingChecks() {
        writeHealthyRuntime(freshStats = true)
        val snapshot = LsfgCompatibilityDiagnostics.inspectContainer(
            container = container(armed = true),
            activeHome = rootDir,
            nowMs = nowMs,
            logs = "LSFG armed VK_LAYER_LS_frame_generation liblsfg-vk-layer.so ExynosToolsShim",
        )

        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("runtime_library")?.status)
        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("manifest_contract")?.status)
        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("lossless_dll")?.status)
        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("config_contract")?.status)
        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("active_home")?.status)
        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("vsync_fresh")?.status)
        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("stats_fresh")?.status)
        assertEquals(LsfgCompatibilityDiagnostics.Status.PASS, snapshot.check("layer_log_evidence")?.status)
        assertFalse(snapshot.checks.any { it.status == LsfgCompatibilityDiagnostics.Status.FAIL })
        assertEquals("NONE", snapshot.nextFocus)
    }

    @Test
    fun missingLayerLibrary_identifiesRuntimePackageBarrier() {
        writeHealthyRuntime(freshStats = true)
        File(rootDir, ".local/lib/liblsfg-vk-layer.so").delete()

        val snapshot = LsfgCompatibilityDiagnostics.inspectContainer(
            container = container(armed = true),
            activeHome = rootDir,
            nowMs = nowMs,
            logs = "LSFG armed",
        )

        assertEquals(LsfgCompatibilityDiagnostics.Status.FAIL, snapshot.check("runtime_library")?.status)
        assertEquals("RUNTIME_PACKAGE", snapshot.nextFocus)
    }

    @Test
    fun staleStats_identifiesPresentationEvidenceBarrier() {
        writeHealthyRuntime(freshStats = false)

        val snapshot = LsfgCompatibilityDiagnostics.inspectContainer(
            container = container(armed = true),
            activeHome = rootDir,
            nowMs = nowMs,
            logs = "LSFG armed VK_LAYER_LS_frame_generation liblsfg-vk-layer.so",
        )

        assertEquals(LsfgCompatibilityDiagnostics.Status.FAIL, snapshot.check("stats_fresh")?.status)
        assertEquals("PRESENTATION_STATS", snapshot.nextFocus)
    }

    @Test
    fun inactiveContainer_skipsLiveVsyncAndStatsAsBlockingFailures() {
        writeHealthyRuntime(freshStats = false)
        val otherHome = Files.createTempDirectory("other-active-home").toFile()
        try {
            val snapshot = LsfgCompatibilityDiagnostics.inspectContainer(
                container = container(armed = true),
                activeHome = otherHome,
                nowMs = nowMs,
                logs = "LSFG armed VK_LAYER_LS_frame_generation",
            )

            assertEquals(LsfgCompatibilityDiagnostics.Status.SKIP, snapshot.check("vsync_fresh")?.status)
            assertEquals(LsfgCompatibilityDiagnostics.Status.SKIP, snapshot.check("stats_fresh")?.status)
            assertEquals("ACTIVE_CONTAINER", snapshot.nextFocus)
        } finally {
            otherHome.deleteRecursively()
        }
    }

    private fun writeHealthyRuntime(freshStats: Boolean) {
        val layerLib = File(rootDir, ".local/lib/liblsfg-vk-layer.so").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        File(rootDir, ".local/share/vulkan/implicit_layer.d/VkLayer_LS_frame_generation.json").apply {
            parentFile?.mkdirs()
            writeText(
                """{
                  "file_format_version":"1.0.0",
                  "layer":{
                    "name":"VK_LAYER_LS_frame_generation",
                    "type":"GLOBAL",
                    "api_version":"1.3.0",
                    "library_path":"../../../lib/liblsfg-vk-layer.so",
                    "enable_environment":{"LSFG_PROCESS":"gamenative-lsfg"},
                    "disable_environment":{"DISABLE_LSFG":"1"}
                  }
                }""".trimIndent(),
            )
        }
        val dllPath = File(rootDir, ".local/share/lsfg-vk/Lossless.dll").absolutePath
        File(rootDir, ".config/lsfg-vk/conf.toml").apply {
            parentFile?.mkdirs()
            writeText(
                """version = 1
                [global]
                dll = "$dllPath"
                [[game]]
                exe = "gamenative-lsfg"
                multiplier = 2
                flow_scale = 0.80
                performance_mode = false
                fps_limit = 30
                experimental_present_mode = "mailbox"
                """.trimIndent(),
            )
        }
        File(rootDir, ".config/lsfg-vk/vsync.txt").apply {
            writeText("vsync_ns=123456789\nperiod_ns=11111111\n")
            setLastModified(nowMs - 500L)
        }
        File(rootDir, ".config/lsfg-vk/stats.txt").apply {
            writeText("fps=60.0\n")
            setLastModified(if (freshStats) nowMs - 500L else nowMs - 10_000L)
        }
        // Keep a reference so the compiler/test clearly exercises the intended file.
        check(layerLib.isFile)
    }

    private fun container(armed: Boolean): Container {
        val container = mock<Container>()
        whenever(container.id).thenReturn("CUSTOM_GAME_42")
        whenever(container.rootDir).thenReturn(rootDir)
        whenever(container.containerVariant).thenReturn(Container.BIONIC)
        whenever(container.getExtra(LsfgVkManager.EXTRA_ARMED, "false")).thenReturn(armed.toString())
        whenever(container.getExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2")).thenReturn("2")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80")).thenReturn("0.80")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true")).thenReturn("false")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PRESENT_MODE, "mailbox")).thenReturn("mailbox")
        whenever(container.getExtra("fpsLimiterEnabled", "false")).thenReturn("true")
        whenever(container.getExtra("fpsLimiterTarget", "0")).thenReturn("30")
        return container
    }
}
