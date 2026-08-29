package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
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
import org.robolectric.RuntimeEnvironment

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
    fun applyLaunchEnv_explicitlyDiscoversAndEnablesLsfgWithoutDroppingCallerLayers() {
        val container = container(armed = true)
        val envVars = EnvVars().apply {
            put("VK_LAYER_PATH", "/existing/explicit-layers")
            put("VK_INSTANCE_LAYERS", "VK_LAYER_existing")
        }

        assertTrue(LsfgVkManager.applyLaunchEnv(container, envVars))

        val containerLayerDir =
            File(rootDir, ".local/share/vulkan/implicit_layer.d").absolutePath
        assertEquals(
            "/existing/explicit-layers:$containerLayerDir",
            envVars["VK_LAYER_PATH"],
        )
        assertEquals(
            "VK_LAYER_existing:VK_LAYER_LS_frame_generation",
            envVars["VK_INSTANCE_LAYERS"],
        )
        assertFalse(envVars.has("LSFG_PROCESS"))
        assertEquals(
            File(rootDir, ".config/lsfg-vk/conf.toml").absolutePath,
            envVars["LSFG_CONFIG"],
        )
    }

    @Test
    fun applyLaunchEnv_doesNotDuplicateLayerDiscoveryOrActivationEntries() {
        val container = container(armed = true)
        val containerLayerDir =
            File(rootDir, ".local/share/vulkan/implicit_layer.d").absolutePath
        val envVars = EnvVars().apply {
            put("VK_LAYER_PATH", "/existing/explicit-layers:$containerLayerDir")
            put("VK_INSTANCE_LAYERS", "VK_LAYER_LS_frame_generation:VK_LAYER_existing")
        }

        assertTrue(LsfgVkManager.applyLaunchEnv(container, envVars))

        assertEquals(
            "/existing/explicit-layers:$containerLayerDir",
            envVars["VK_LAYER_PATH"],
        )
        assertEquals(
            "VK_LAYER_LS_frame_generation:VK_LAYER_existing",
            envVars["VK_INSTANCE_LAYERS"],
        )
    }

    @Test
    fun writeConfig_addsLinuxCommAliasForLongExecutableNames() {
        File(rootDir, ".local/share/lsfg-vk/Lossless.dll").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val container = Container("lsfg-long-name").apply {
            setRootDir(rootDir)
            setContainerVariant(Container.BIONIC)
            setExecutablePath("bin/FFVIII_LAUNCHER.exe")
            putExtra(LsfgVkManager.EXTRA_ARMED, "true")
            putExtra(LsfgVkManager.EXTRA_MULTIPLIER, "2")
            putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, "0.80")
            putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true")
            putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, "mailbox")
            putExtra("fpsLimiterEnabled", "false")
        }

        assertTrue(LsfgVkManager.writeConfig(container))
        val text = File(rootDir, ".config/lsfg-vk/conf.toml").readText()
        assertTrue(text.contains("exe = \"FFVIII_LAUNCHER.exe\""))
        assertTrue(text.contains("exe = \"FFVIII_LAUNCHER\""))
    }

    @Test
    fun applyLaunchEnv_clearsOnlyLsfgEnvironmentWhenDisabled() {
        val container = container(armed = false)
        val envVars = EnvVars().apply {
            put("VK_LAYER_PATH", "/existing/explicit-layers")
            put("VK_INSTANCE_LAYERS", "VK_LAYER_existing")
            put("LSFG_PROCESS", "stale")
            put("LSFG_CONFIG", "/stale/conf.toml")
        }

        assertFalse(LsfgVkManager.applyLaunchEnv(container, envVars))
        assertEquals("/existing/explicit-layers", envVars["VK_LAYER_PATH"])
        assertEquals("VK_LAYER_existing", envVars["VK_INSTANCE_LAYERS"])
        // EnvVars.get() intentionally returns an empty string for absent keys; use
        // has() to distinguish removal from an explicitly stored empty value.
        assertFalse(envVars.has("LSFG_PROCESS"))
        assertFalse(envVars.has("LSFG_CONFIG"))
    }

    @Test
    fun bundledManifest_isVulkan13ImplicitLayerGatedToGameNativeProcess() {
        val context = RuntimeEnvironment.getApplication()
        val json = context.assets
            .open("lsfg_vk/android_arm64_v8a/VkLayer_LS_frame_generation.json")
            .bufferedReader()
            .use { JSONObject(it.readText()) }
        val layer = json.getJSONObject("layer")

        assertEquals("VK_LAYER_LS_frame_generation", layer.getString("name"))
        assertEquals("GLOBAL", layer.getString("type"))
        assertEquals("1.3.0", layer.getString("api_version"))
        assertFalse(layer.has("enable_environment"))
        assertEquals(
            "1",
            layer.getJSONObject("disable_environment").getString("DISABLE_LSFG"),
        )
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
            .thenReturn("0.80")
        whenever(container.getExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, "true"))
            .thenReturn("true")
        return container
    }
}
