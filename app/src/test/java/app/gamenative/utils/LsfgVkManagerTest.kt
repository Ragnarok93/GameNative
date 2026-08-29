package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
    fun applyLaunchEnv_explicitlyEnablesLsfgLayerWithoutDroppingExistingLayers() {
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val container = Container("lsfg-test").apply {
            setRootDir(rootDir)
            setContainerVariant(Container.BIONIC)
            putExtra(LsfgVkManager.EXTRA_ARMED, true)
        }
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
        assertEquals(
            "VK_LAYER_existing:VK_LAYER_LS_frame_generation",
            envVars["VK_INSTANCE_LAYERS"],
        )
    }

    @Test
    fun applyLaunchEnv_doesNotDuplicateLsfgLayerWhenAppliedTwice() {
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val container = Container("lsfg-test").apply {
            setRootDir(rootDir)
            setContainerVariant(Container.BIONIC)
            putExtra(LsfgVkManager.EXTRA_ARMED, true)
        }
        val envVars = EnvVars()

        assertTrue(LsfgVkManager.applyLaunchEnv(container, envVars))
        assertTrue(LsfgVkManager.applyLaunchEnv(container, envVars))

        assertEquals(
            "VK_LAYER_LS_frame_generation",
            envVars["VK_INSTANCE_LAYERS"],
        )
    }
}
