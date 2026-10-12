package app.gamenative.utils

import android.content.Context
import android.content.Intent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.DXVKHelper
import org.json.JSONObject
import timber.log.Timber

/**
 * Handles external game launch intents with container configuration overrides.
 */
object IntentLaunchManager {

    private const val EXTRA_APP_ID = "app_id"

    private const val EXTRA_GAME_SOURCE = "game_source"
    private const val EXTRA_CONTAINER_CONFIG = "container_config"
    private const val ACTION_LAUNCH_GAME = "app.gamenative.LAUNCH_GAME"
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val URI_SCHEME = "gamenative"
    private const val URI_HOST = "run"
    private const val MAX_CONFIG_JSON_SIZE = 50000 // 50KB limit to prevent memory exhaustion

    data class LaunchRequest(
        val appId: String,
        val containerConfig: ContainerData? = null,
        val suppliedConfigKeys: Set<String>? = null,
    )

    fun parseLaunchIntent(intent: Intent): LaunchRequest? {
        Timber.d("[IntentLaunchManager]: Parsing intent: action=${intent.action}")

        if (intent.action != ACTION_LAUNCH_GAME && intent.action != ACTION_VIEW) {
            Timber.d("[IntentLaunchManager]: Intent action '${intent.action}' doesn't match expected action '$ACTION_LAUNCH_GAME' or '$ACTION_VIEW'")
            return null
        }
        val gameId: Int
        val gameSource: String?
        val containerConfig: ContainerData?

        if (intent.action == ACTION_VIEW) {
            val data = intent.data
            if (data?.scheme != URI_SCHEME || data.host != URI_HOST) {
                Timber.d("[IntentLaunchManager]: URI '${intent.dataString}' doesn't match expected URI '$URI_SCHEME://$URI_HOST'")
                return null
            }
            gameId = data.getQueryParameter("appid")?.toIntOrNull() ?: -1
            gameSource = data.getQueryParameter("gamesource")?.uppercase(java.util.Locale.ROOT)
            containerConfig = null
        } else {
            gameId = intent.getIntExtra(EXTRA_APP_ID, -1)
            Timber.d("[IntentLaunchManager]: Extracted app_id: $gameId from intent extras")
            gameSource = intent.getStringExtra(EXTRA_GAME_SOURCE)?.uppercase(java.util.Locale.ROOT)
                ?.takeIf { source -> GameSource.entries.any { it.name == source } }
                ?: GameSource.STEAM.name
            containerConfig = intent.getStringExtra(EXTRA_CONTAINER_CONFIG)?.let { json ->
                try {
                    parseContainerConfig(json)
                } catch (e: Exception) {
                    Timber.e(e, "[IntentLaunchManager]: Failed to parse container configuration JSON")
                    null
                }
            }
        }

        if (gameId <= 0) {
            Timber.w("[IntentLaunchManager]: Invalid or missing app_id in launch intent: $gameId")
            return null
        }

        val isValidGameSource = GameSource.entries.any { it.name == gameSource }
        if (!isValidGameSource) {
            Timber.w("[IntentLaunchManager]: Invalid or missing game source in launch intent: $gameSource")
            return null
        }

        val appId = "${gameSource}_$gameId"
        Timber.d("[IntentLaunchManager]: Converted to appId: $appId")

        val suppliedKeys = if (containerConfig != null) intent.getStringExtra(EXTRA_CONTAINER_CONFIG)?.let {
            JSONObject(it).keys().asSequence().toSet()
        } else null
        return LaunchRequest(appId, containerConfig, suppliedKeys)
    }

    fun applyTemporaryConfigOverride(context: Context, appId: String, configOverride: ContainerData,
        suppliedKeys: Set<String>? = null) {
        try {
            TemporaryConfigStore.setOverride(appId, configOverride, suppliedKeys)

            if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)

                // Backup original config before applying override (only once)
                if (TemporaryConfigStore.getOriginalConfig(appId) == null) {
                    val originalConfig = ContainerUtils.toContainerData(container)
                    TemporaryConfigStore.setOriginalConfig(appId, originalConfig)
                }

                // Get the effective config (merge base with override)
                val effectiveConfig = getEffectiveContainerConfig(context, appId)
                if (effectiveConfig != null) {
                    ContainerUtils.applyToContainer(context, container, effectiveConfig, saveToDisk = false)
                    Timber.i("[IntentLaunchManager]: Applied temporary config override for app $appId (in-memory only)")
                }
            } else {
                Timber.i("[IntentLaunchManager]: Stored temporary config override for app $appId (container will be created on launch)")
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to apply temporary config override for app $appId")
            throw e
        }
    }

    fun getEffectiveContainerConfig(context: Context, appId: String): ContainerData? {
        return try {
            val baseConfig = if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.toContainerData(container)
            } else {
                null
            }

            val override = TemporaryConfigStore.getOverride(appId)

            when {
                override != null && baseConfig != null -> mergeConfigurations(baseConfig, override, TemporaryConfigStore.getSuppliedKeys(appId))
                override != null -> override
                else -> baseConfig
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to get effective container config for app $appId")
            null
        }
    }

    fun clearTemporaryOverride(appId: String) {
        TemporaryConfigStore.clearOverride(appId)
        Timber.d("[IntentLaunchManager]: Cleared temporary config override for app $appId")
    }

    fun clearAllTemporaryOverrides() {
        TemporaryConfigStore.clearAll()
        Timber.d("[IntentLaunchManager]: Cleared all temporary config overrides")
    }

    fun restoreOriginalConfiguration(context: Context, appId: String) {
        try {
            val originalConfig = TemporaryConfigStore.getOriginalConfig(appId)
            if (originalConfig != null && ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.applyToContainer(context, container, originalConfig, saveToDisk = false)
                Timber.i("[IntentLaunchManager]: Restored original configuration for app $appId")
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to restore original configuration for app $appId")
        }
    }

    fun hasTemporaryOverride(appId: String): Boolean {
        return TemporaryConfigStore.hasOverride(appId)
    }

    fun getTemporaryOverride(appId: String): ContainerData? {
        return TemporaryConfigStore.getOverride(appId)
    }

    fun getOriginalConfig(appId: String): ContainerData? {
        return TemporaryConfigStore.getOriginalConfig(appId)
    }

    fun setOriginalConfig(appId: String, config: ContainerData) {
        TemporaryConfigStore.setOriginalConfig(appId, config)
    }

    private fun validateContainerConfig(config: ContainerData): List<String> {
        val issues = mutableListOf<String>()

        if (!config.screenSize.matches(Regex("\\d+x\\d+"))) {
            issues.add("Invalid screen size format: ${config.screenSize}. Expected format: WIDTHxHEIGHT (e.g., 1920x1080)")
        }

        if (config.cpuList.isNotEmpty() && !config.cpuList.matches(Regex("\\d+(,\\d+)*"))) {
            issues.add("Invalid CPU list format: ${config.cpuList}. Expected comma-separated numbers (e.g., 0,1,2,3)")
        }

        if (!config.videoMemorySize.matches(Regex("\\d+"))) {
            issues.add("Invalid video memory size: ${config.videoMemorySize}. Expected numeric value in MB")
        }

        if (config.drives.isNotEmpty() && !config.drives.matches(Regex("([A-Z]:([^:]+))*"))) {
            issues.add("Invalid drives format: ${config.drives}. Expected format: LETTER:PATH (e.g., C:/path/to/drive)")
        }

        return issues
    }

    private fun parseContainerConfig(jsonString: String): ContainerData {
        if (jsonString.length > MAX_CONFIG_JSON_SIZE) {
            throw IllegalArgumentException("Container configuration JSON too large (max ${MAX_CONFIG_JSON_SIZE / 1000}KB)")
        }

        val json = JSONObject(jsonString)

        // Only include non-default values to avoid overriding existing container settings
        val config = ContainerData(
            name = if (json.has("name")) json.getString("name") else "",
            screenSize = if (json.has("screenSize")) json.getString("screenSize") else PluviaApp.getDefaultScreenSize(),
            envVars = if (json.has("envVars")) json.getString("envVars") else Container.DEFAULT_ENV_VARS,
            graphicsDriver = if (json.has("graphicsDriver")) json.getString("graphicsDriver") else Container.DEFAULT_GRAPHICS_DRIVER,
            graphicsDriverVersion = if (json.has("graphicsDriverVersion")) json.getString("graphicsDriverVersion") else "",
            dxwrapper = if (json.has("dxwrapper")) json.getString("dxwrapper") else Container.DEFAULT_DXWRAPPER,
            dxwrapperConfig = if (json.has("dxwrapperConfig")) {
                "version=" + json.getString("dxwrapperConfig")
            } else {
                ""
            },
            audioDriver = if (json.has("audioDriver")) json.getString("audioDriver") else Container.DEFAULT_AUDIO_DRIVER,
            wincomponents = if (json.has("wincomponents")) json.getString("wincomponents") else Container.DEFAULT_WINCOMPONENTS,
            drives = if (json.has("drives")) json.getString("drives") else Container.DEFAULT_DRIVES,
            execArgs = if (json.has("execArgs")) json.getString("execArgs") else "",
            executablePath = if (json.has("executablePath")) json.getString("executablePath") else "",
            installPath = if (json.has("installPath")) json.getString("installPath") else "",
            showFPS = if (json.has("showFPS")) json.getBoolean("showFPS") else false,
            launchRealSteam = if (json.has("launchRealSteam")) json.getBoolean("launchRealSteam") else false,
            launchBionicSteam = if (json.has("launchBionicSteam")) json.getBoolean("launchBionicSteam") else false,
            cpuList = if (json.has("cpuList")) json.getString("cpuList") else Container.getFallbackCPUList(),
            cpuListWoW64 = if (json.has("cpuListWoW64")) json.getString("cpuListWoW64") else Container.getFallbackCPUListWoW64(),
            wow64Mode = if (json.has("wow64Mode")) json.getBoolean("wow64Mode") else true,
            startupSelection = if (json.has("startupSelection")) {
                json.getInt("startupSelection").toByte()
            } else {
                Container.STARTUP_SELECTION_ESSENTIAL.toInt().toByte()
            },
            box86Version = if (json.has("box86Version")) json.getString("box86Version") else "",
            box64Version = if (json.has("box64Version")) json.getString("box64Version") else "",
            box86Preset = if (json.has("box86Preset")) json.getString("box86Preset") else "",
            box64Preset = if (json.has("box64Preset")) json.getString("box64Preset") else "",
            desktopTheme = if (json.has("desktopTheme")) json.getString("desktopTheme") else "",
            csmt = if (json.has("csmt")) json.getBoolean("csmt") else true,
            videoPciDeviceID = if (json.has("videoPciDeviceID")) json.getInt("videoPciDeviceID") else 1728,
            offScreenRenderingMode = if (json.has("offScreenRenderingMode")) json.getString("offScreenRenderingMode") else "fbo",
            strictShaderMath = if (json.has("strictShaderMath")) json.getBoolean("strictShaderMath") else true,
            videoMemorySize = if (json.has("videoMemorySize")) json.getString("videoMemorySize") else "2048",
            mouseWarpOverride = if (json.has("mouseWarpOverride")) json.getString("mouseWarpOverride") else "disable",
            sdlControllerAPI = if (json.has("sdlControllerAPI")) json.getBoolean("sdlControllerAPI") else true,
            enableXInput = if (json.has("enableXInput")) json.getBoolean("enableXInput") else true,
            enableDInput = if (json.has("enableDInput")) json.getBoolean("enableDInput") else true,
            dinputMapperType = if (json.has("dinputMapperType")) {
                json.getInt("dinputMapperType").toByte()
            } else {
                1.toByte()
            },
            disableMouseInput = if (json.has("disableMouseInput")) json.getBoolean("disableMouseInput") else false,
            suspendPolicy = if (json.has("suspendPolicy")) {
                Container.normalizeSuspendPolicy(json.getString("suspendPolicy"))
            } else {
                PrefManager.suspendPolicy
            },
            shaderBackend = if (json.has("shaderBackend")) json.getString("shaderBackend") else "glsl",
            useGLSL = if (json.has("useGLSL")) json.getString("useGLSL") else "enabled",
        )

        val validationIssues = validateContainerConfig(config)
        if (validationIssues.isNotEmpty()) {
            Timber.w("[IntentLaunchManager]: Container configuration validation issues: ${validationIssues.joinToString("; ")}")
        }

        return config
    }

    internal fun mergeConfigurations(base: ContainerData, override: ContainerData, suppliedKeys: Set<String>? = null): ContainerData {
        if (suppliedKeys != null) return explicitPatch(base, override, suppliedKeys)
        // Quick return if no actual overrides
        if (override == base) return base

        return ContainerData(
            name = override.name.ifEmpty { base.name },
            screenSize = if (override.screenSize != base.screenSize) {
                override.screenSize
            } else {
                base.screenSize
            },
            envVars = if (override.envVars != Container.DEFAULT_ENV_VARS) override.envVars else base.envVars,
            graphicsDriver = if (override.graphicsDriver != Container.DEFAULT_GRAPHICS_DRIVER) {
                override.graphicsDriver
            } else {
                base.graphicsDriver
            },
            graphicsDriverVersion = override.graphicsDriverVersion.ifEmpty { base.graphicsDriverVersion },
            dxwrapper = if (override.dxwrapper != Container.DEFAULT_DXWRAPPER) override.dxwrapper else base.dxwrapper,
            dxwrapperConfig = when {
                override.dxwrapperConfig.isNotEmpty() -> override.dxwrapperConfig
                base.dxwrapperConfig.isNotEmpty() -> base.dxwrapperConfig
                else -> DXVKHelper.DEFAULT_CONFIG
            },
            audioDriver = if (override.audioDriver != Container.DEFAULT_AUDIO_DRIVER) override.audioDriver else base.audioDriver,
            wincomponents = if (override.wincomponents != Container.DEFAULT_WINCOMPONENTS) {
                override.wincomponents
            } else {
                base.wincomponents
            },
            drives = if (override.drives != Container.DEFAULT_DRIVES) override.drives else base.drives,
            execArgs = override.execArgs.ifEmpty { base.execArgs },
            executablePath = override.executablePath.ifEmpty { base.executablePath },
            installPath = override.installPath.ifEmpty { base.installPath },
            // Boolean fields: only override if different from parsing defaults
            showFPS = if (override.showFPS != false) override.showFPS else base.showFPS,
            launchRealSteam = if (override.launchRealSteam != false) override.launchRealSteam else base.launchRealSteam,
            launchBionicSteam = if (override.launchBionicSteam != false) override.launchBionicSteam else base.launchBionicSteam,
            cpuList = if (override.cpuList != Container.getFallbackCPUList()) override.cpuList else base.cpuList,
            cpuListWoW64 = if (override.cpuListWoW64 != Container.getFallbackCPUListWoW64()) {
                override.cpuListWoW64
            } else {
                base.cpuListWoW64
            },
            wow64Mode = if (override.wow64Mode != true) override.wow64Mode else base.wow64Mode,
            startupSelection = if (override.startupSelection != Container.STARTUP_SELECTION_ESSENTIAL.toInt().toByte()) {
                override.startupSelection
            } else {
                base.startupSelection
            },
            box86Version = override.box86Version.ifEmpty { base.box86Version },
            box64Version = override.box64Version.ifEmpty { base.box64Version },
            box86Preset = override.box86Preset.ifEmpty { base.box86Preset },
            box64Preset = override.box64Preset.ifEmpty { base.box64Preset },
            desktopTheme = override.desktopTheme.ifEmpty { base.desktopTheme },
            csmt = if (override.csmt != true) override.csmt else base.csmt,
            videoPciDeviceID = if (override.videoPciDeviceID != 1728) override.videoPciDeviceID else base.videoPciDeviceID,
            offScreenRenderingMode = if (override.offScreenRenderingMode != "fbo") {
                override.offScreenRenderingMode
            } else {
                base.offScreenRenderingMode
            },
            strictShaderMath = if (override.strictShaderMath != true) override.strictShaderMath else base.strictShaderMath,
            videoMemorySize = if (override.videoMemorySize != "2048") override.videoMemorySize else base.videoMemorySize,
            mouseWarpOverride = if (override.mouseWarpOverride != "disable") override.mouseWarpOverride else base.mouseWarpOverride,
            sdlControllerAPI = if (override.sdlControllerAPI != true) override.sdlControllerAPI else base.sdlControllerAPI,
            enableXInput = if (override.enableXInput != true) override.enableXInput else base.enableXInput,
            enableDInput = if (override.enableDInput != true) override.enableDInput else base.enableDInput,
            dinputMapperType = if (override.dinputMapperType != 1.toByte()) override.dinputMapperType else base.dinputMapperType,
            disableMouseInput = if (override.disableMouseInput != false) override.disableMouseInput else base.disableMouseInput,
            suspendPolicy = base.suspendPolicy,
            shaderBackend = if (override.shaderBackend != "glsl") override.shaderBackend else base.shaderBackend,
            useGLSL = if (override.useGLSL != "enabled") override.useGLSL else base.useGLSL,
        )
    }

    private fun explicitPatch(base: ContainerData, patch: ContainerData, keys: Set<String>): ContainerData {
        fun <T> select(key: String, original: T, value: T): T = if (key in keys) value else original
        return base.copy(
            name = select("name", base.name, patch.name), screenSize = select("screenSize", base.screenSize, patch.screenSize),
            envVars = select("envVars", base.envVars, patch.envVars), graphicsDriver = select("graphicsDriver", base.graphicsDriver, patch.graphicsDriver),
            graphicsDriverVersion = select("graphicsDriverVersion", base.graphicsDriverVersion, patch.graphicsDriverVersion),
            dxwrapper = select("dxwrapper", base.dxwrapper, patch.dxwrapper), dxwrapperConfig = select("dxwrapperConfig", base.dxwrapperConfig, patch.dxwrapperConfig),
            audioDriver = select("audioDriver", base.audioDriver, patch.audioDriver), wincomponents = select("wincomponents", base.wincomponents, patch.wincomponents),
            drives = select("drives", base.drives, patch.drives), execArgs = select("execArgs", base.execArgs, patch.execArgs),
            executablePath = select("executablePath", base.executablePath, patch.executablePath), installPath = select("installPath", base.installPath, patch.installPath),
            showFPS = select("showFPS", base.showFPS, patch.showFPS), launchRealSteam = select("launchRealSteam", base.launchRealSteam, patch.launchRealSteam),
            launchBionicSteam = select("launchBionicSteam", base.launchBionicSteam, patch.launchBionicSteam),
            cpuList = select("cpuList", base.cpuList, patch.cpuList), cpuListWoW64 = select("cpuListWoW64", base.cpuListWoW64, patch.cpuListWoW64),
            wow64Mode = select("wow64Mode", base.wow64Mode, patch.wow64Mode), startupSelection = select("startupSelection", base.startupSelection, patch.startupSelection),
            box86Version = select("box86Version", base.box86Version, patch.box86Version), box64Version = select("box64Version", base.box64Version, patch.box64Version),
            box86Preset = select("box86Preset", base.box86Preset, patch.box86Preset), box64Preset = select("box64Preset", base.box64Preset, patch.box64Preset),
            desktopTheme = select("desktopTheme", base.desktopTheme, patch.desktopTheme), csmt = select("csmt", base.csmt, patch.csmt),
            videoPciDeviceID = select("videoPciDeviceID", base.videoPciDeviceID, patch.videoPciDeviceID),
            offScreenRenderingMode = select("offScreenRenderingMode", base.offScreenRenderingMode, patch.offScreenRenderingMode),
            strictShaderMath = select("strictShaderMath", base.strictShaderMath, patch.strictShaderMath), videoMemorySize = select("videoMemorySize", base.videoMemorySize, patch.videoMemorySize),
            mouseWarpOverride = select("mouseWarpOverride", base.mouseWarpOverride, patch.mouseWarpOverride),
            sdlControllerAPI = select("sdlControllerAPI", base.sdlControllerAPI, patch.sdlControllerAPI), enableXInput = select("enableXInput", base.enableXInput, patch.enableXInput),
            enableDInput = select("enableDInput", base.enableDInput, patch.enableDInput), dinputMapperType = select("dinputMapperType", base.dinputMapperType, patch.dinputMapperType),
            disableMouseInput = select("disableMouseInput", base.disableMouseInput, patch.disableMouseInput), suspendPolicy = select("suspendPolicy", base.suspendPolicy, patch.suspendPolicy),
            shaderBackend = select("shaderBackend", base.shaderBackend, patch.shaderBackend), useGLSL = select("useGLSL", base.useGLSL, patch.useGLSL),
        )
    }
}

private object TemporaryConfigStore {
    private val overrides = mutableMapOf<String, ContainerData>()
    private val originalConfigs = mutableMapOf<String, ContainerData>()
    private val suppliedKeys = mutableMapOf<String, Set<String>>()
    private val lock = Any()

    fun setOverride(appId: String, config: ContainerData, keys: Set<String>?) = synchronized(lock) {
        overrides[appId] = config
        if (keys == null) suppliedKeys.remove(appId) else suppliedKeys[appId] = keys.toSet()
    }
    fun getSuppliedKeys(appId: String): Set<String>? = synchronized(lock) { suppliedKeys[appId] }

    fun getOverride(appId: String): ContainerData? = synchronized(lock) {
        overrides[appId]
    }

    fun clearOverride(appId: String) = synchronized(lock) {
        overrides.remove(appId)
        originalConfigs.remove(appId)
        suppliedKeys.remove(appId)
    }

    fun hasOverride(appId: String): Boolean = synchronized(lock) {
        overrides.containsKey(appId)
    }

    fun setOriginalConfig(appId: String, config: ContainerData) = synchronized(lock) {
        originalConfigs[appId] = config
    }

    fun getOriginalConfig(appId: String): ContainerData? = synchronized(lock) {
        originalConfigs[appId]
    }

    fun clearAll() = synchronized(lock) {
        overrides.clear()
        originalConfigs.clear()
        suppliedKeys.clear()
    }
}
