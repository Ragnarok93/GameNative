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
class LsfgSourceFpsLimitTest {
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("gamenative-lsfg-source-limit-test").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun writeConfig_serializesSourceLimitSeparatelyFromAdaptiveOutputTarget() {
        val container = container(
            adaptiveTarget = "120",
            limiterEnabled = "true",
            limiterTarget = "30",
        )

        assertTrue(LsfgVkManager.writeConfig(container))

        val text = configText()
        assertTrue(text.contains("adaptive_framegen = true"))
        assertTrue(text.contains("fps_limit = 120"))
        assertTrue(text.contains("source_fps_limit = 30"))
        assertFalse(text.contains("source_fps_limit = 120"))
    }

    @Test
    fun writeConfig_disablesNativeSourcePacerWhenSourceLimiterIsOff() {
        val container = container(
            adaptiveTarget = "120",
            limiterEnabled = "false",
            limiterTarget = "30",
        )

        assertTrue(LsfgVkManager.writeConfig(container))

        val text = configText()
        assertTrue(text.contains("fps_limit = 120"))
        assertTrue(text.contains("source_fps_limit = 0"))
    }

    @Test
    fun runtimeSourceOverrideChangesOnlySourceLimit() {
        val container = container(
            adaptiveTarget = "120",
            limiterEnabled = "true",
            limiterTarget = "30",
        )

        assertTrue(LsfgVkManager.writeConfig(container))
        assertTrue(
            LsfgVkManager.updateConfigAtRuntime(
                container = container,
                enabled = true,
                multiplier = 2,
                flowScale = 0.80f,
                performanceMode = true,
                adaptiveFrameGen = true,
                fpsLimitOverride = null,
                sourceFpsLimitOverride = 45,
            ),
        )

        val text = configText()
        assertTrue(text.contains("fps_limit = 120"))
        assertTrue(text.contains("source_fps_limit = 45"))
        assertFalse(text.lineSequence().any { it == "fps_limit = 45" })
    }

    private fun configText(): String =
        File(rootDir, ".config/lsfg-vk/conf.toml").readText()

    private fun container(
        adaptiveTarget: String,
        limiterEnabled: String,
        limiterTarget: String,
    ): Container {
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val container = mock<Container>()
        whenever(container.rootDir).thenReturn(rootDir)
        whenever(container.containerVariant).thenReturn(Container.BIONIC)
        whenever(container.executablePath).thenReturn("bin/game.exe")
        whenever(container.getExtra(LsfgVkManager.EXTRA_ARMED, "false")).thenReturn("true")
        whenever(container.getExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2")).thenReturn("2")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80")).thenReturn("0.80")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true")).thenReturn("true")
        whenever(container.getExtra(LsfgVkManager.EXTRA_ADAPTIVE_FRAMEGEN, "false")).thenReturn("true")
        whenever(container.getExtra(LsfgVkManager.EXTRA_ADAPTIVE_OUTPUT_TARGET, "0"))
            .thenReturn(adaptiveTarget)
        whenever(container.getExtra(LsfgVkManager.EXTRA_PRESENT_MODE, "mailbox")).thenReturn("mailbox")
        whenever(container.getExtra("fpsLimiterEnabled", "false")).thenReturn(limiterEnabled)
        whenever(container.getExtra("fpsLimiterTarget", "0")).thenReturn(limiterTarget)
        return container
    }
}
