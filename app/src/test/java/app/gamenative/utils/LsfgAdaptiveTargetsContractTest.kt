package app.gamenative.utils

import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LsfgAdaptiveTargetsContractTest {
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("gamenative-lsfg-targets-test").toFile()
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun writeConfig_adaptiveOutputCeilingConstrainsUserTargetWithoutChangingSourceTarget() {
        val container = container(
            adaptiveTarget = "120",
            adaptiveCeiling = "90",
            limiterEnabled = "true",
            limiterTarget = "30",
        )

        assertTrue(LsfgVkManager.writeConfig(container))

        val text = File(rootDir, ".config/lsfg-vk/conf.toml").readText()
        assertTrue(text.contains("adaptive_framegen = true"))
        assertTrue(text.contains("fps_limit = 90"))
        assertTrue(text.contains("source_fps_limit = 30"))
        assertFalse(text.lineSequence().any { it == "fps_limit = 120" })
    }

    private fun container(
        adaptiveTarget: String,
        adaptiveCeiling: String,
        limiterEnabled: String,
        limiterTarget: String,
    ): Container {
        val container = mock<Container>()
        whenever(container.rootDir).thenReturn(rootDir)
        whenever(container.containerVariant).thenReturn(Container.BIONIC)
        whenever(container.executablePath).thenReturn("bin/game.exe")
        whenever(container.getExtra(LsfgVkManager.EXTRA_ARMED, "false")).thenReturn("true")
        whenever(container.getExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2")).thenReturn("2")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80")).thenReturn("0.80")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true")).thenReturn("true")
        whenever(container.getExtra(LsfgVkManager.EXTRA_ADAPTIVE_FRAMEGEN, "false")).thenReturn("true")
        whenever(container.getExtra(LsfgVkManager.EXTRA_ADAPTIVE_OUTPUT_TARGET, "0")).thenReturn(adaptiveTarget)
        whenever(container.getExtra("lsfgAdaptiveOutputCeiling", "0")).thenReturn(adaptiveCeiling)
        whenever(container.getExtra(LsfgVkManager.EXTRA_PRESENT_MODE, "mailbox")).thenReturn("mailbox")
        whenever(container.getExtra("fpsLimiterEnabled", "false")).thenReturn(limiterEnabled)
        whenever(container.getExtra("fpsLimiterTarget", "0")).thenReturn(limiterTarget)
        return container
    }
}
