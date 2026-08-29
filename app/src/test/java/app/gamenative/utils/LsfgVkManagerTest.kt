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
class LsfgVkManagerTest {
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("gamenative-lsfg-test").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun applyLaunchEnv_doesNotForceOrReplaceInstanceLayers() {
        val container = container(armed = true)
        val envVars = EnvVars().apply {
            put("VK_LAYER_PATH", "/existing/layers")
            put("VK_INSTANCE_LAYERS", "VK_LAYER_existing")
        }

        assertTrue(LsfgVkManager.applyLaunchEnv(container, envVars))

        val expectedLayerDir = File(
            rootDir,
            ".local/share/vulkan/implicit_layer.d",
        ).absolutePath
        assertEquals(
            "/existing/layers:$expectedLayerDir",
            envVars["VK_LAYER_PATH"],
        )
        assertEquals("VK_LAYER_existing", envVars["VK_INSTANCE_LAYERS"])
    }

    @Test
    fun applyLaunchEnv_leavesInstanceLayerEnvironmentUntouchedWhenDisabled() {
        val container = container(armed = false)
        val envVars = EnvVars().apply {
            put("VK_INSTANCE_LAYERS", "VK_LAYER_existing")
        }

        assertFalse(LsfgVkManager.applyLaunchEnv(container, envVars))
        assertEquals("VK_LAYER_existing", envVars["VK_INSTANCE_LAYERS"])
    }

    private fun container(armed: Boolean): Container {
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val container = mock<Container>()
        whenever(container.rootDir).thenReturn(rootDir)
        whenever(container.containerVariant).thenReturn(Container.BIONIC)
        whenever(container.getExtra(LsfgVkManager.EXTRA_ARMED, "false"))
            .thenReturn(armed.toString())
        whenever(container.getExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2"))
            .thenReturn("2")
        whenever(container.getExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80"))
            .thenReturn("0.80")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true"))
            .thenReturn("true")
        return container
    }
}
