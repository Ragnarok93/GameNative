package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LsfgIr3ProfilingEnvTest {
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("gamenative-lsfg-ir3-test").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun applyLaunchEnv_enablesTurnipComputeShaderDebugForArmedLsfg() {
        val envVars = EnvVars()

        assertTrue(LsfgVkManager.applyLaunchEnv(container(armed = true), envVars))
        assertEquals("cs", envVars["IR3_SHADER_DEBUG"])
    }

    @Test
    fun applyLaunchEnv_doesNotEnableTurnipDebugWhenLsfgIsDisabled() {
        val envVars = EnvVars()

        assertFalse(LsfgVkManager.applyLaunchEnv(container(armed = false), envVars))
        assertFalse(envVars.has("IR3_SHADER_DEBUG"))
    }

    private fun container(armed: Boolean): Container {
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val container = mock<Container>()
        whenever(container.rootDir).thenReturn(rootDir)
        whenever(container.containerVariant).thenReturn(Container.BIONIC)
        whenever(container.executablePath).thenReturn("bin/game.exe")
        whenever(container.getExtra(LsfgVkManager.EXTRA_ARMED, "false"))
            .thenReturn(armed.toString())
        whenever(container.getExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2"))
            .thenReturn("2")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80"))
            .thenReturn("0.70")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true"))
            .thenReturn("true")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PRESENT_MODE, "mailbox"))
            .thenReturn("mailbox")
        whenever(container.getExtra("fpsLimiterEnabled", "false"))
            .thenReturn("false")
        whenever(container.getExtra("fpsLimiterTarget", "0"))
            .thenReturn("0")
        return container
    }
}
