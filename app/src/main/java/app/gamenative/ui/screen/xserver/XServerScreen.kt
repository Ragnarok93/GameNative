Warning: truncated output (original token count: 71742)
Total output lines: 6164

package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import app.gamenative.BuildConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.gamenative.MainActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.util.applyScreenEffectsConfig
import app.gamenative.ui.util.loadScreenEffectsConfig
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.SteamBootstrap
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.gamefixes.GameInputCompatibility
import app.gamenative.data.LaunchInfo
import app.gamenative.data.LibraryItem
import app.gamenative.data.ShooterModeConfig
import app.gamenative.data.SteamApp
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet
import app.gamenative.externaldisplay.ExternalDisplayInputController
import app.gamenative.externaldisplay.ExternalDisplaySwapController
import app.gamenative.externaldisplay.SwapInputOverlayView
import app.gamenative.powercontrol.PowerManager
import app.gamenative.service.AchievementWatcher
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicOverlayManager
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.component.LsfgQuickMenuState
import app.gamenative.ui.component.PerformanceQuickMenuState
import app.gamenative.ui.component.QuickMenu
import app.gamenative.ui.component.QuickMenuAction
import app.gamenative.ui.component.SteamInviteState
import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.ui.data.XServerState
import app.gamenative.ui.widget.PerformanceHudView
import app.gamenative.utils.AssetUtils
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.downloader.CoreDriverDownloader
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.ExecutableSelectionUtils
import app.gamenative.utils.LsfgQuickMenuHelper
import app.gamenative.utils.LsfgVkManager
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.launchdependencies.BionicSteamAssetsDependency
import app.gamenative.utils.downloader.DXWrapperDownloader
import app.gamenative.utils.downloader.GraphicsDriverDownloader
import app.gamenative.utils.PreInstallSteps
import app.gamenative.utils.BrightnessManager
import app.gamenative.utils.SteamTokenLogin
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.downloader.WinComponentDownloader
import app.gamenative.utils.WineProcessSnapshotHelper
import com.posthog.PostHog
import com.winlator.alsaserver.ALSAClient
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.AppUtils
import com.winlator.core.Callback
import com.winlator.core.DXVKHelper
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.GPUHelper
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.OnExtractFileListener
import com.winlator.core.ProcessHelper
import com.winlator.core.TarCompressorUtils
import com.winlator.core.Win32AppWorkarounds
import com.winlator.core.WineInfo
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineStartMenuCreator
import com.winlator.core.WineThemeManager
import com.winlator.core.WineUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.fexcore.FEXCoreManager
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.inputcontrols.TouchMouse
import com.winlator.widget.FrameRating
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.renderer.ASurfaceRenderer
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.XServerRendererView
import com.winlator.widget.XServerView
import com.winlator.widget.XServerViewGL
import com.winlator.winhandler.WinHandler
import com.winlator.winhandler.WinHandler.PreferredInputApi
import com.winlator.winhandler.OnGetProcessInfoListener
import com.winlator.winhandler.ProcessInfo
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.ALSAServerComponent
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import com.winlator.xenvironment.components.GlibcProgramLauncherComponent
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent
import com.winlator.xenvironment.components.PulseAudioComponent
import com.winlator.xenvironment.components.SteamClientComponent
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.VirGLRendererComponent
import com.winlator.xenvironment.components.VortekRendererComponent
import com.winlator.xenvironment.components.WineRequestComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.Keyboard
import com.winlator.xserver.Property
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.ShmFramePacer
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XServer
import com.winlator.xserver.extensions.PresentExtension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Arrays
import java.util.Locale
import kotlin.math.ceil
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.name
import kotlin.math.roundToInt
import kotlin.text.lowercase
import com.winlator.PrefManager as WinlatorPrefManager

// Always re-extract drivers and DXVK on every launch to handle cases of container corruption
// where games randomly stop working. Set to false once corruption issues are resolved.
private const val ALWAYS_REEXTRACT = true

// Guard to prevent duplicate game_exited events when multiple exit triggers fire simultaneously
private val isExiting = AtomicBoolean(false)

private const val EXIT_PROCESS_TIMEOUT_MS = 30_000L
private const val EXIT_PROCESS_POLL_INTERVAL_MS = 1_000L
private const val EXIT_PROCESS_RESPONSE_TIMEOUT_MS = 2_000L
private const val QUICK_MENU_PROCESS_POLL_INTERVAL_MS = 2_000L
private const val DEFAULT_FPS_LIMITER_MAX_HZ = 60
private const val DEFAULT_FPS_LIMITER_TARGET_HZ = 60
private const val FPS_LIMITER_ENABLED_EXTRA = "fpsLimiterEnabled"
private const val FPS_LIMITER_TARGET_EXTRA = "fpsLimiterTarget"

private fun initialFpsLimiterEnabled(container: Container): Boolean =
    parseBooleanExtra(container.getExtra(FPS_LIMITER_ENABLED_EXTRA)) ?: true

private fun initialFpsLimiterTarget(container: Container): Int =
    parsePositiveFpsLimit(container.getExtra(FPS_LIMITER_TARGET_EXTRA))
        ?: DEFAULT_FPS_LIMITER_TARGET_HZ

private fun detectMaxRefreshRateHz(context: Context, attachedView: View?): Int {
    val display = attachedView?.display
        ?: context.display
        ?: ContextCompat.getSystemService(context, DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    val refreshRate = when {
        display == null -> DEFAULT_FPS_LIMITER_MAX_HZ.toFloat()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            val supportedMax = display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
            if (supportedMax.isFinite() && supportedMax > 0f) supportedMax else display.refreshRate
        }
        else -> display.refreshRate
    }

    return refreshRate
        .takeIf { it.isFinite() && it > 0f }
        ?.roundToInt()
        ?.coerceAtLeast(5)
        ?: DEFAULT_FPS_LIMITER_MAX_HZ
}

private data class XServerViewReleaseBinding(
    val xServerView: XServerRendererView,
    val windowModificationListener: WindowManager.OnWindowModificationListener,
    var gameHost: FrameLayout? = null,
    var gameHostLayoutListener: OnLayoutChangeListener? = null,
    val screenWidth: Int,
)

private data class ControllerSlotUiState(
    val slotIndex: Int,
    val enabled: Boolean,
    val status: String,
    val controllerName: String,
    val deviceId: String,
    val androidInputSource: String,
    val guestInputMethod: String,
)

private data class ConnectedControllerUiState(
    val name: String,
    val deviceId: String,
    val slotLabel: String,
    val androidInputSource: String,
)

private data class ControllerStatusSnapshot(
    val slots: List<ControllerSlotUiState>,
    val connectedControllers: List<ConnectedControllerUiState>,
    val guestApi: String,
)

private val CORE_WINE_PROCESSES = setOf(
    "wineserver",
    "services",
    "start",
    "winhandler",
    "tabtip",
    "explorer",
    "winedevice",
    "svchost",
)

private fun normalizeProcessName(name: String): String {
    val trimmed = name.trim().trim('"')
    val base = trimmed.substringAfterLast('/').substringAfterLast('\\')
    val lower = base.lowercase(Locale.getDefault())
    return if (lower.endsWith(".exe")) lower.removeSuffix(".exe") else lower
}

internal fun parseScreenSize(screenSize: String): Pair<Int, Int>? {
    val parts = screenSize.lowercase(Locale.getDefault()).split("x")
    if (parts.size != 2) return null
    val width = parts[0].trim().toIntOrNull() ?: return null
    val height = parts[1].trim().toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return width to height
}

internal fun portraitGameHostHeight(
    isPortrait: Boolean,
    screenWidth: Int,
    availableHeight: Int,
    screenSize: String,
): Int {
    if (!isPortrait) return ViewGroup.LayoutParams.MATCH_PARENT
    val (renderWidth, renderHeight) = parseScreenSize(screenSize) ?: return ViewGroup.LayoutParams.MATCH_PARENT
    val aspectHeight = ceil(screenWidth * (renderHeight.toFloat() / renderWidth.toFloat())).toInt()
    return if (availableHeight > 0) minOf(aspectHeight, availableHeight) else aspectHeight
}

private fun updatePortraitGameHostHeight(
    gameHost: View,
    isPortrait: Boolean,
    screenWidth: Int,
    screenSize: String,
) {
    val params = gameHost.layoutParams ?: return
    val height = portraitGameHostHeight(
        isPortrait,
        screenWidth,
        (gameHost.parent as? View)?.height ?: 0,
        screenSize,
    )
    if (params.height != height) {
        params.height = height
        gameHost.layoutParams = params
    }
}

private fun extractExecutableBasename(path: String): String {
    if (path.isBlank()) return ""
    return normalizeProcessName(path)
}

private fun windowMatchesExecutable(window: Window, targetExecutable: String): Boolean {
    if (targetExecutable.isBlank()) return false
    val normalizedTarget = normalizeProcessName(targetExecutable)
    val candidates = listOf(window.name, window.className)
    return candidates.any { candidate ->
        candidate.split('\u0000')
            .asSequence()
            .map { normalizeProcessName(it) }
            .any { it == normalizedTarget }
    }
}

private fun buildEssentialProcessAllowlist(): Set<String> {
    val essentialServices = WineUtils.getEssentialServiceNames()
        .map { normalizeProcessName(it) }
    return (essentialServices + CORE_WINE_PROCESSES).toSet()
}

// TODO logs in composables are 'unstable' which can cause recomposition (performance issues)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun XServerScreen(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    appId: String,
    bootToContainer: Boolean,
    testGraphics: Boolean = false,
    diagnostics: Boolean = false,
    isOffline: Boolean = false,
    registerBackAction: ( ( ) -> Unit ) -> Unit,
    navigateBack: () -> Unit,
    onExit: (onComplete: (() -> Unit)?) -> Unit,
    onWindowMapped: ((Context, Window) -> Unit)? = null,
    onWindowUnmapped: ((Window) -> Unit)? = null,
    onGameLaunchError: ((String) -> Unit)? = null,
    // Non-null only when hosted by ImmersiveXrActivity. One bundled parameter, not nine — this
    // composable sits at the dex verifier's register limit (see ImmersiveSessionHooks' kdoc).
    immersiveHooks: app.gamenative.ui.screen.xr.ImmersiveSessionHooks? = null,
) {
    Timber.i("Starting up XServerScreen")
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val imm = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    // PluviaApp.events.emit(AndroidEvent.SetAppBarVisibility(false))
    PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))

    // seems to be used to indicate when a custom wine is being installed (intent extra "generate_wineprefix")
    // val generateWinePrefix = false
    var firstTimeBoot = false
    var needsUnpacking = false
    var containerVariantChanged = false
    var frameRating by remember { mutableStateOf<FrameRating?>(null) }
    var frameRatingWindowId = -1
    var vkbasaltConfig = ""
    var taskAffinityMask = 0
    var taskAffinityMaskWoW64 = 0

    LaunchedEffect(appId) {
        isExiting.set(false)
    }

    val container = remember(appId) {
        ContainerUtils.getContainer(context, appId)
    }
    val activity = remember(context) { BrightnessManager.findActivity(context) }

    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }

        val contentResolver = activity.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                BrightnessManager.clearDisplayBrightnessOverride(activity)
            }
        }

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            observer,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            observer,
        )

        onDispose {
            contentResolver.unregisterContentObserver(observer)
            BrightnessManager.clearDisplayBrightnessOverride(activity)
        }
    }

    val suspendPolicy = remember(container.id) { container.suspendPolicy }
    val neverSuspend = suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)
    val manualResumeMode = suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

    SideEffect {
        PluviaApp.setActiveSuspendPolicy(suspendPolicy)
    }

    PluviaApp.events.emit(
        AndroidEvent.SetAllowedOrientation(
            if (container.isPortraitMode) EnumSet.of(Orientation.PORTRAIT)
            else PrefManager.allowedOrientation,
        ),
    )

    val xServerState = rememberSaveable(stateSaver = XServerState.Saver) {
        mutableStateOf(
            XServerState(
                graphicsDriver = container.graphicsDriver,
                graphicsDriverVersion = container.graphicsDriverVersion,
                audioDriver = container.audioDriver,
                dxwrapper = container.dxWrapper,
                dxwrapperConfig = DXVKHelper.parseConfig(container.dxWrapperConfig),
                screenSize = container.screenSize,
            ),
        )
    }

    // val xServer by remember {
    //     val result = mutableStateOf(XServer(ScreenInfo(xServerState.value.screenSize)))
    //     Log.d("XServerScreen", "Remembering xServer as $result")
    //     result
    // }
    // var xEnvironment: XEnvironment? by remember {
    //     val result = mutableStateOf<XEnvironment?>(null)
    //     Log.d("XServerScreen", "Remembering xEnvironment as $result")
    //     result
    // }
    var touchMouse by remember {
        val result = mutableStateOf<TouchMouse?>(null)
        Timber.i("Remembering touchMouse as $result")
        result
    }
    var keyboard by remember { mutableStateOf<Keyboard?>(null) }
    // var pointerEventListener by remember { mutableStateOf<Callback<MotionEvent>?>(null) }

    val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
    val appLaunchInfo = SteamService.getAppInfoOf(gameId)?.let { appInfo ->
        SteamService.getWindowsLaunchInfos(gameId).firstOrNull()
    }

    var currentAppInfo = SteamService.getAppInfoOf(gameId)

    var xServerView: XServerRendererView? by remember {
        val result = mutableStateOf<XServerRendererView?>(null)
        Timber.i("Remembering xServerView as $result")
        result
    }

    var swapInputOverlay: SwapInputOverlayView? by remember { mutableStateOf(null) }
    var imeInputReceiver: app.gamenative.externaldisplay.IMEInputReceiver? by remember { mutableStateOf(null) }

    var win32AppWorkarounds: Win32AppWorkarounds? by remember { mutableStateOf(null) }
    var physicalControllerHandler: PhysicalControllerHandler? by remember { mutableStateOf(null) }
    var exitWatchJob: Job? by remember { mutableStateOf(null) }
    val keyboardEscMenuHandler = remember(scope) { KeyboardEscMenuHandler(scope) }

    DisposableEffect(Unit) {
        onDispose {
            PluviaApp.radialMenuCoordinator?.detach()
            PluviaApp.radialMenuCoordinator = null
            physicalControllerHandler?.cleanup()
            physicalControllerHandler = null
            exitWatchJob?.cancel()
            exitWatchJob = null
            keyboardEscMenuHandler.cancel()
        }
    }
    var isKeyboardVisible = false
    var areControlsVisible by remember { mutableStateOf(false) }
    var isDisableMouseInput by remember(container.id) { mutableStateOf(container.isDisableMouseInput) }
    var isEditMode by remember { mutableStateOf(false) }
    var gameRoot by remember { mutableStateOf<View?>(null) }
    var windowModificationListener by remember { mutableStateOf<WindowManager.OnWindowModificationListener?>(null) }
    // Snapshot of element positions before entering edit mode (for cancel behavior)
    var elementPositionsSnapshot by remember { mutableStateOf<Map<com.winlator.inputcontrols.ControlElement, Pair<Int, Int>>>(emptyMap()) }
    var showElementEditor by remember { mutableStateOf(false) }
    var elementToEdit by remember { mutableStateOf<com.winlator.inputcontrols.ControlElement?>(null) }
    var showPhysicalControllerDialog by remember { mutableStateOf(false) }
    var showPlayingBlockedDialog by rememberSaveable { mutableStateOf(false) }
    var playingBlockedRemoteName by rememberSaveable { mutableStateOf<String?>(null) }
    var showTouchGestureDialog by remember { mutableStateOf(false) }
    var showShooterModeDialog by remember(container.id) { mutableStateOf(false) }
    var isTouchscreenModeActive by remember { mutableStateOf(container.isTouchscreenMode) }
    var isShooterModeActive by remember(container.id) { mutableStateOf(container.isShooterMode) }
    var currentGestureConfig by remember {
        mutableStateOf(app.gamenative.data.TouchGestureConfig.fromJson(container.getGestureConfig()))
    }
    var currentShooterConfig by remember(container.id) {
        mutableStateOf(ShooterModeConfig.fromJson(container.getShooterConfig()))
    }
    fun shouldShowMouseCursor(): Boolean {
        return !container.isDisableMouseInput &&
            (!container.isTouchscreenMode || currentGestureConfig.showCursorInTouchscreenMode)
    }
    fun applyMouseCursorVisibility() {
        xServerView?.renderer?.setCursorVisible(shouldShowMouseCursor())
    }
    val clickHighlightPoints = remember { mutableStateListOf<app.gamenative.ui.component.HighlightPoint>() }
    var debugGestureName by remember { mutableStateOf("") }
    var debugGestureKey by remember { mutableIntStateOf(0) }
    var keyboardRequestedFromOverlay by remember { mutableStateOf(false) }
    var shouldForceResumeOnMenuClose by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var quickMenuToolsVisible by remember { mutableStateOf(false) }
    var quickMenuWineProcesses by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    var quickMenuWineProcessesLoading by remember { mutableStateOf(false) }
    var hasPhysicalController by remember { mutableStateOf(false) }
    var controllerSlotStatusVersion by remember { mutableIntStateOf(0) }
    var keepPausedForEditor by remember { mutableStateOf(false) }
    var hasPhysicalKeyboard by remember { mutableStateOf(false) }
    var hasPhysicalMouse by remember { mutableStateOf(false) }
    var usingScreenMirror by remember { mutableStateOf(false) }
    var hasInternalTouchpad by remember { mutableStateOf(false) }
    var hasUpdatedScreenGamepad by remember { mutableStateOf(false) }
    var isPerformanceHudEnabled by remember { mutableStateOf(PrefManager.showFps) }
    val shouldTrackDisplayedFrames = remember { AtomicBoolean(false) }
    var detectedMaxRefreshRateHz by remember { mutableIntStateOf(detectMaxRefreshRateHz(context, null)) }
    var fpsLimiterEnabled by rememberSaveable(container.id) { mutableStateOf(initialFpsLimiterEnabled(container)) }
    var fpsLimiterTarget by rememberSaveable(container.id) { mutableIntStateOf(initialFpsLimiterTarget(container)) }

    // LSFG tab in QuickMenu only visible when enabled in container settings
    val isLsfgAvailable = LsfgQuickMenuHelper.isAvailable(container)
    val initialLsfgSettings = remember(container.id) { LsfgQuickMenuHelper.readSettings(container) }

    var lsfgMultiplier by rememberSaveable(container.id) { mutableIntStateOf(initialLsfgSettings.multiplier) }
    var lsfgFlowScale by rememberSaveable(container.id) { mutableStateOf(initialLsfgSettings.flowScale) }
    var lsfgPerformanceMode by rememberSaveable(container.id) { mutableStateOf(initialLsfgSettings.performanceMode) }
    var lsfgAdaptiveFrameGen by rememberSaveable(container.id) { mutableStateOf(initialLsfgSettings.adaptiveFrameGen) }
    var lsfgAdaptiveOutputTarget by rememberSaveable(container.id) {
        mutableIntStateOf(
            initialLsfgSettings.adaptiveOutputTargetFps
                .takeIf { it > 0 }
                ?: detectedMaxRefreshRateHz,
        )
    }

    DisposableEffect(container.id, isLsfgAvailable) {
        val provider: () -> Float? = {
            if (lsfgMultiplier >= 2) LsfgVkManager.readMeasuredSourceFps(container) else null
        }
        if (isLsfgAvailable) PowerManager.tuningFpsProvider = provider
        onDispose {
            if (PowerManager.tuningFpsProvider === provider) {
                PowerManager.tuningFpsProvider = null
            }
        }
    }

    fun persistFpsLimiterState() {
        container.putExtra(FPS_LIMITER_ENABLED_EXTRA, fpsLimiterEnabled)
        container.putExtra(FPS_LIMITER_TARGET_EXTRA, fpsLimiterTarget)
        container.saveData()
    }

    fun loadPerformanceHudConfig(): PerformanceHudConfig {
        return PerformanceHudConfig(
            showFrameRate = PrefManager.performanceHudShowFrameRate,
            showCpuUsage = PrefManager.performanceHudShowCpuUsage,
            showGpuUsage = PrefManager.performanceHudShowGpuUsage,
            showRamUsage = PrefManager.performanceHudShowRamUsage,
            showBatteryLevel = PrefManager.performanceHudShowBatteryLevel,
            showPowerDraw = PrefManager.performanceHudShowPowerDraw,
            showBatteryRuntime = PrefManager.performanceHudShowBatteryRuntime,
            showBatteryTemperature = PrefManager.performanceHudShowBatteryTemperature,
            showClockTime = PrefManager.performanceHudShowClockTime,
            showCpuTemperature = PrefManager.performanceHudShowCpuTemperature,
            showGpuTemperature = PrefManager.performanceHudShowGpuTemperature,
            showFrameRateGraph = PrefManager.performanceHudShowFrameRateGraph,
            showCpuUsageGraph = PrefManager.performanceHudShowCpuUsageGraph,
            showGpuUsageGraph = PrefManager.performanceHudShowGpuUsageGraph,
            backgroundOpacity = PrefManager.performanceHudBackgroundOpacity,
            colorIntensity = PrefManager.performanceHudColorIntensity,
            showTextOutline = PrefManager.performanceHudShowTextOutline,
            size = PerformanceHudSize.fromPrefValue(PrefManager.performanceHudSize),
        )
    }

    var performanceHudConfig by remember { mutableStateOf(loadPerformanceHudConfig()) }
    var performanceHudView by remember { mutableStateOf<PerformanceHudView?>(null) }
    var performanceHudHost by remember { mutableStateOf<FrameLayout?>(null) }
    var isDraggingPerformanceHud by remember { mutableStateOf(false) }
    var isTrackingPerformanceHudTouch by remember { mutableStateOf(false) }
    var performanceHudTouchDownRawX by remember { mutableStateOf(0f) }
    var performanceHudTouchDownRawY by remember { mutableStateOf(0f) }
    var performanceHudDragOffsetX by remember { mutableStateOf(0f) }
    var performanceHudDragOffsetY by remember { mutableStateOf(0f) }
    val performanceHudTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    fun persistPerformanceHudConfig(config: PerformanceHudConfig) {
        PrefManager.performanceHudShowFrameRate = config.showFrameRate
        PrefManager.performanceHudShowCpuUsage = config.showCpuUsage
        PrefManager.performanceHudShowGpuUsage = config.showGpuUsage
        PrefManager.performanceHudShowRamUsage = config.showRamUsage
        PrefManager.performanceHudShowBatteryLevel = config.showBatteryLevel
        PrefManager.performanceHudShowPowerDraw = config.showPowerDraw
        PrefManager.performanceHudShowBatteryRuntime = config.showBatteryRuntime
        PrefManager.performanceHudShowBatteryTemperature = config.showBatteryTemperature
        PrefManager.performanceHudShowClockTime = config.showClockTime
        PrefManager.performanceHudShowCpuTemperature = config.showCpuTemperature
        PrefManager.performanceHudShowGpuTemperature = config.showGpuTemperature
        PrefManager.performanceHudShowFrameRateGraph = config.showFrameRateGraph
        PrefManager.performanceHudShowCpuUsageGraph = config.showCpuUsageGraph
        PrefManager.performanceHudShowGpuUsageGraph = config.showGpuUsageGraph
        PrefManager.performanceHudBackgroundOpacity = config.backgroundOpacity
        PrefManager.performanceHudColorIntensity = config.colorIntensity
        PrefManager.performanceHudShowTextOutline = config.showTextOutline
        PrefManager.performanceHudSize = config.size.prefValue
    }

    fun applyPerformanceHudConfig(config: PerformanceHudConfig) {
        performanceHudConfig = config
        persistPerformanceHudConfig(config)
        performanceHudView?.setConfig(config)
    }

    LaunchedEffect(xServerView?.renderer) {
        val screenEffectsConfig = loadScreenEffectsConfig(container)
        when (val renderer = xServerView?.renderer) {
            is VulkanRenderer -> applyScreenEffectsConfig(renderer, screenEffectsConfig)
            is GLRenderer -> applyScreenEffectsConfig(renderer, screenEffectsConfig)
        }
    }

    fun applyFpsLimiterToEngines(limit: Int) {
        // With LSFG active the native layer owns Vulkan presentation pacing.
        // This limit remains the real/source-game cap; Adaptive FrameGen has
        // its own independent final-output target inside the LSFG config. Both
        // the renderer's SurfaceControl frame-rate hint and the
        // PresentExtension's scheduled idle-release pacing must stay off. The
        // hint would clamp the display to the base rate, while the extension's
        // Choreographer-scheduled pixmap
        // releases mix stale pixmaps under multiplied present traffic
        // (measured as constant multi-exposure ghosting on the X11/turnip
        // present path).
        val lsfgActive = isLsfgAvailable && lsfgMultiplier >= 2
        xServerView?.setFrameRateLimit(if (lsfgActive) 0 else limit)
        xServerView?.getxServer()
            ?.getExtension<PresentExtension>(PresentExtension.MAJOR_OPCODE.toInt())
            ?.transitionFramePacing(lsfgActive, limit)
        // Not disarmed with LSFG: the layer only multiplies Vulkan-swapchain
        // presents, so SHM-presenting games never pass through it and would
        // otherwise run uncapped whenever LSFG is armed.
        ShmFramePacer.setFrameRateLimit(limit)
        PowerManager.targetFps = limit
        if (lsfgActive) {
            LsfgQuickMenuHelper.applyLiveFpsCap(container, limit)
        }
        // keeps frame stats in base units while generated frames tick the ring
        PowerManager.frameSampleStride =
            if (lsfgActive && !lsfgAdaptiveFrameGen) lsfgMultiplier else 1
    }

    fun effectiveFpsLimit(): Int =
        if (fpsLimiterEnabled) fpsLimiterTarget else 0

    fun applyLsfgSettings() {
        LsfgQuickMenuHelper.applySettings(
            container,
            LsfgQuickMenuHelper.Settings(
                lsfgMultiplier,
                lsfgFlowScale,
                lsfgPerformanceMode,
                lsfgAdaptiveFrameGen,
                lsfgAdaptiveOutputTarget,
            ),
        )
    }

    fun applyFpsLimiterEnabled(enabled: Boolean) {
        fpsLimiterEnabled = enabled
        applyFpsLimiterToEngines(effectiveFpsLimit())
        persistFpsLimiterState()
    }

    fun applyFpsLimiterTarget(target: Int) {
        val sanitized = target.coerceAtLeast(5).coerceAtMost(detectedMaxRefreshRateHz)
        fpsLimiterTarget = sanitized
        if (fpsLimiterEnabled) {
            applyFpsLimiterToEngines(effectiveFpsLimit())
        }
        persistFpsLimiterState()
    }

    fun applyLsfgMultiplier(mult: Int) {
        lsfgMultiplier = LsfgQuickMenuHelper.sanitizeMultiplier(mult)
        applyLsfgSettings()
        applyFpsLimiterToEngines(effectiveFpsLimit())
    }

    fun applyLsfgFlowScale(scale: Float) {
        lsfgFlowScale = LsfgQuickMenuHelper.sanitizeFlowScale(scale)
        applyLsfgSettings()
    }

    fun applyLsfgPerformanceMode(enabled: Boolean) {
        lsfgPerformanceMode = enabled
        applyLsfgSettings()
    }

    fun applyLsfgAdaptiveFrameGen(enabled: Boolean) {
        lsfgAdaptiveFrameGen = enabled
        applyLsfgSettings()
        applyFpsLimiterToEngines(effectiveFpsLimit())
    }

    fun applyLsfgAdaptiveOutputTarget(target: Int) {
        val sanitized = target.coerceAtLeast(5).coerceAtMost(detectedMaxRefreshRateHz)
        if (sanitized == lsfgAdaptiveOutputTarget) return
        lsfgAdaptiveOutputTarget = sanitized
        LsfgQuickMenuHelper.applyAdaptiveOutputTarget(container, sanitized)
    }

    LaunchedEffect(xServerView) {
        // PowerManager Adaptive FPS Cap controls the real/source rate only.
        // LSFG's Adaptive final-output objective remains independently persisted.
        PowerManager.fpsCapApplier = applier@{ capFps: Int ->
            if (!isLsfgAvailable || lsfgMultiplier < 2) return@applier false
            PowerManager.targetFps = capFps
            LsfgQuickMenuHelper.applyLiveFpsCap(container, capFps)
            ShmFramePacer.setFrameRateLimit(capFps)
            true
        }
        val detectedMax = detectMaxRefreshRateHz(context, xServerView as? View)
        detectedMaxRefreshRateHz = detectedMax
        val clampedTarget = fpsLimiterTarget.coerceAtMost(detectedMax).coerceAtLeast(5)
        if (clampedTarget != fpsLimiterTarget) {
            fpsLimiterTarget = clampedTarget
        }
        val clampedAdaptiveTarget = lsfgAdaptiveOutputTarget.coerceAtMost(detectedMax).coerceAtLeast(5)
        if (clampedAdaptiveTarget != lsfgAdaptiveOutputTarget) {
            lsfgAdaptiveOutputTarget = clampedAdaptiveTarget
            if (lsfgAdaptiveFrameGen) {
                LsfgQuickMenuHelper.applyAdaptiveOutputTarget(container, clampedAdaptiveTarget)
            }
        }
        applyFpsLimiterToEngines(effectiveFpsLimit())
    }

    fun restorePerformanceHudPosition() {
        val host = performanceHudHost ?: return
        val hud = performanceHudView ?: return
        if (host.width <= 0 || host.height <= 0 || hud.width <= 0 || hud.height <= 0) return

        val maxX = (host.width - hud.width).coerceAtLeast(0).toFloat()
        val maxY = (host.height - hud.height).coerceAtLeast(0).toFloat()
        val margin = 12 * context.resources.displayMetrics.density
        val savedX = PrefManager.performanceHudXFraction
        val savedY = PrefManager.performanceHudYFraction

        hud.x = if (savedX in 0f..1f) maxX * savedX else margin.coerceAtMost(maxX)
        hud.y = if (savedY in 0f..1f) maxY * savedY else margin.coerceAtMost(maxY)

        PrefManager.performanceHudXFraction = if (maxX > 0f) hud.x / maxX else 0f
        PrefManager.performanceHudYFraction = if (maxY > 0f) hud.y / maxY else 0f
    }

    fun movePerformanceHud(rawX: Float, rawY: Float, save: Boolean) {
        val host = performanceHudHost ?: return
        val hud = performanceHudView ?: return
        if (host.width <= 0 || host.height <= 0 || hud.width <= 0 || hud.height <= 0) return

        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        val maxX = (host.width - hud.width).coerceAtLeast(0).toFloat()
        val maxY = (host.height - hud.height).coerceAtLeast(0).toFloat()

        hud.x = (rawX - hostLocation[0] - performanceHudDragOffsetX).coerceIn(0f, maxX)
        hud.y = (rawY - hostLocation[1] - performanceHudDragOffsetY).coerceIn(0f, maxY)

        if (save) {
            PrefManager.performanceHudXFraction = if (maxX > 0f) hud.x / maxX else 0f
            PrefManager.performanceHudYFraction = if (maxY > 0f) hud.y / maxY else 0f
        }
    }

    fun removePerformanceHud() {
        isDraggingPerformanceHud = false
        isTrackingPerformanceHudTouch = false
        performanceHudView?.let { hud ->
            (hud.parent as? ViewGroup)?.removeView(hud)
        }
        performanceHudView = null
    }

    fun togglePerformanceHudLayout() {
        val hud = performanceHudView ?: return
        val compactMode = !hud.isCompactMode()
        hud.setCompactMode(compactMode)
        PrefManager.performanceHudCompactMode = compactMode
        hud.post {
            if (performanceHudView === hud && !isDraggingPerformanceHud) {
                restorePerformanceHudPosition()
            }
        }
    }

    fun updatePerformanceHud(show: Boolean) {
        if (!show) {
            removePerformanceHud()
            return
        }
        if (performanceHudView != null) {
            return
        }

        val targetLayout = performanceHudHost ?: return
        val hud = PerformanceHudView(
            context = context,
            fpsProvider = {
                val raw = frameRating?.currentFPS ?: 0f
                if (isLsfgAvailable && lsfgMultiplier >= 2) {
                    // Only trust the layer's own measurement; multiplying raw
                    // fabricates fps for games the layer never attaches to
                    // (SHM-presenting games have no Vulkan swapchain).
                    LsfgVkManager.readMeasuredFps(container) ?: raw
                } else {
                    raw
                }
            },
            initialConfig = performanceHudConfig,
            initialCompactMode = PrefManager.performanceHudCompactMode,
        )
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        targetLayout.addView(hud, layoutParams)
        performanceHudView = hud
        hud.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isDraggingPerformanceHud) restorePerformanceHudPosition()
        }
        targetLayout.post {
            if (performanceHudView === hud) restorePerformanceHudPosition()
        }
        hud.bringToFront()
    }

    fun clearOverlayPauseState() {
        PluviaApp.isOverlayPaused = false
    }

    fun pauseForOverlayIfAllowed() {
        if (neverSuspend) {
            Timber.d("Skipping overlay suspend due to suspend policy=never")
            return
        }
        PluviaApp.xEnvironment?.onPause()
        PluviaApp.isOverlayPaused = true
    }

    fun resumeIfAllowedAfterOverlay() {
        if (!PluviaApp.isOverlayPaused) return
        if (neverSuspend) {
            clearOverlayPauseState()
            return
        }
        if (manualResumeMode) {
            Timber.d("Keeping game suspended until Resume is pressed")
            return
        }
        PluviaApp.xEnvironment?.onResume()
        clearOverlayPauseState()
    }

    fun forceResumeIfSuspended() {
        if (PluviaApp.isOverlayPaused && !neverSuspend) {
            PluviaApp.xEnvironment?.onResume()
        }
        clearOverlayPauseState()
    }

    fun resumeFromManualButton() {
        if (!PluviaApp.isOverlayPaused) return
        if (!neverSuspend) {
            PluviaApp.xEnvironment?.onResume()
        }
        keepPausedForEditor = false
        clearOverlayPauseState()
    }

    fun startExitWatchForUnmappedGameWindow(window: Window) {
        val winHandler = xServerView?.getxServer()?.winHandler ?: return
        if (exitWatchJob?.isActive == true) return
        val targetExecutable = extractExecutableBasename(container.executablePath)
        if (!windowMatchesExecutable(window, targetExecutable)) return

        exitWatchJob = CoroutineScope(Dispatchers.IO).launch {
            val allowlist = buildEssentialProcessAllowlist()
            val previousListener = winHandler.getOnGetProcessInfoListener()
            val lock = Any()
            var pendingSnapshot: CompletableDeferred<List<ProcessInfo>?>? = null
            var currentList = mutableListOf<ProcessInfo>()
            var expectedCount = 0

            val listener = OnGetProcessInfoListener { index, count, processInfo ->
                previousListener?.onGetProcessInfo(index, count, processInfo)
                synchronized(lock) {
                    val deferred = pendingSnapshot ?: return@synchronized
                    if (count == 0 && processInfo == null) {
                        if (!deferred.isCompleted) deferred.complete(null)
                        return@synchronized
                    }
                    if (index == 0) {
                        currentList = mutableListOf()
                        expectedCount = count
                    }
                    if (processInfo != null) {
                        currentList.add(processInfo)
                    }
                    if (currentList.size >= expectedCount && !deferred.isCompleted) {
                        deferred.complete(currentList.toList())
                    }
                }
            }

            winHandler.setOnGetProcessInfoListener(listener)
            try {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < EXIT_PROCESS_TIMEOUT_MS) {
                    val deferred = CompletableDeferred<List<ProcessInfo>?>()
                    synchronized(lock) {
                        pendingSnapshot = deferred
                    }
                    winHandler.listProcesses()
                    val snapshot = withTimeoutOrNull(EXIT_PROCESS_RESPONSE_TIMEOUT_MS) {
                        deferred.await()
                    }
                    if (snapshot != null) {
                        val hasNonEssential = snapshot.any {
                            !allowlist.contains(normalizeProcessName(it.name))
                        }
                        if (!hasNonEssential) {
                            withContext(Dispatchers.Main) {
                                exit(
                                    winHandler,
                                    frameRating,
                                    currentAppInfo,
                                    container,
                                    appId,
                                    onExit,
                                    navigateBack,
                                )
                            }
                            break
                        }
                    }
                    delay(EXIT_PROCESS_POLL_INTERVAL_MS)
                }
            } finally {
                winHandler.setOnGetProcessInfoListener(previousListener)
                synchronized(lock) {
                    pendingSnapshot = null
                }
            }
        }
    }

    val tryCapturePointer: () -> Boolean = {
        if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode &&
            !container.isTouchscreenMode) {
            PluviaApp.touchpadView?.postDelayed({
                val view = PluviaApp.touchpadView
                if (view != null) {
                    view.requestFocus()
                    view.requestPointerCapture()
                }
            }, 100)
            true
        } else {
            false
        }
    }

    fun scanForExternalDevices() {
        val deviceIds = InputDevice.getDeviceIds()
        hasPhysicalKeyboard = deviceIds.any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
            Keyboard.isKeyboardDevice(device) && !device.isVirtual && isExternal
        }
        hasPhysicalMouse = deviceIds.any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            val isMouse = device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                          device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
            isMouse && !device.isVirtual && isExternal
        }
        val controllerManager = ControllerManager.getInstance()
        controllerManager.autoAssignConnectedDevices()
        hasPhysicalController = controllerManager.getDetectedDevices().isNotEmpty()
        controllerSlotStatusVersion++
        xServerView?.getxServer()?.winHandler?.refreshControllerMappingsForHotplug()

        if (!usingScreenMirror &&
            !hasInternalTouchpad && !hasPhysicalMouse && !hasPhysicalKeyboard && !hasPhysicalController &&
            !container.isTouchscreenMode) {
            val manager = PluviaApp.inputControlsManager
            val profiles = manager?.getProfiles(false) ?: listOf()

            if (profiles.isNotEmpty()) {
                // Use current profile (custom or Profile 0)
                val profileIdStr = container.getExtra("profileId", "0")
                val profileId = profileIdStr.toIntOrNull() ?: 0
                val targetProfile = if (profileId != 0) {
                    manager?.getProfile(profileId)
                } else {
                    null
                } ?: manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()

                if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode) {
                    Timber.d("No external devices attached, showing on-screen controls")
                    if (!areControlsVisible) {
                        showInputControls(targetProfile, xServerView!!.getxServer().winHandler, container)
                        areControlsVisible = true
                    }

                    PluviaApp.touchpadView?.postDelayed({
                        val view = PluviaApp.touchpadView
                        if (view != null) {
                            // Delay technically not required for the function to work but this can
                            // race against tryCapturePointer() and end up capturing after release
                            // was already called
                            view.releasePointerCapture()
                        }
                    }, 100)
                }
                hasUpdatedScreenGamepad = false
            }
        }
    }

    fun evaluateDevice(device: InputDevice) {
        // Some devices advertise all its capabilities on onInputDeviceAdded callback
        // but some can also do basic advertise on onInputDeviceAdded and only expand on onInputDeviceChanged
        val isExternal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.isExternal else true
        if (!device.isVirtual && isExternal) {
            if (Keyboard.isKeyboardDevice(device)) {
                hasPhysicalKeyboard = true
                if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode &&
                    !container.isTouchscreenMode &&
                    !hasUpdatedScreenGamepad) {
                    hasUpdatedScreenGamepad = true

                    hideInputControls()
                    areControlsVisible = false
                }
            }
            val isMouse = device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                    device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            if (isMouse) {
                hasPhysicalMouse = true
                if (!hasUpdatedScreenGamepad && tryCapturePointer()) {
                    hasUpdatedScreenGamepad = true

                    hideInputControls()
                    areControlsVisible = false
                }
            }
        }
        val isGamepad = ExternalController.isGameController(device)
        if (isGamepad) {
            ControllerManager.getInstance().onDeviceConnected(device.id)
            controllerSlotStatusVersion++
            xServerView?.getxServer()?.winHandler?.setCurrentController(device.id)
            xServerView?.getxServer()?.winHandler?.refreshControllerMappingsForHotplug()
            if (!showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode &&
                !container.isTouchscreenMode &&
                !hasUpdatedScreenGamepad) {
                hasUpdatedScreenGamepad = true

                hideInputControls()
                areControlsVisible = false
            }
        }
    }

    val dismissOverlayMenu: () -> Unit = {
        if (!keyboardRequestedFromOverlay) {
            imeInputReceiver?.hideKeyboard()
        }
        shouldForceResumeOnMenuClose = keyboardRequestedFromOverlay && manualResumeMode && !keepPausedForEditor
        keyboardRequestedFromOverlay = false
        showQuickMenu = false
    }

    LaunchedEffect(showQuickMenu, quickMenuToolsVisible, xServerView) {
        // Piggybacks on this effect rather than its own LaunchedEffect(showQuickMenu) — this
        // composable is at the dex 255-register limit. Extra fires from the other keys repeat
        // the same value; the activity's handler is idempotent.
        immersiveHooks?.onQuickMenuVisibilityChanged?.invoke(showQuickMenu)
        if (!showQuickMenu || !quickMenuToolsVisible) {
            quickMenuWineProcesses = emptyList()
            quickMenuWineProcessesLoading = false
            return@LaunchedEffect
        }

        quickMenuWineProcessesLoading = true
        while (showQuickMenu && quickMenuToolsVisible) {
            quickMenuWineProcesses = withContext(Dispatchers.IO) {
                WineProcessSnapshotHelper.readFromProc()
            }
            quickMenuWineProcessesLoading = false
            delay(QUICK_MENU_PROCESS_POLL_INTERVAL_MS)
        }
    }

    // Shows the soft keyboard, anchored to [anchor]. Handles the Android 12+
    // post-delay quirk and routes input to the external display IME when needed.
    val showSoftKeyboard: (View, String) -> Unit = { anchor, analyticsEvent ->
        anchor.post {
            if (anchor.windowToken != null) {
                val show = {
                    if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = analyticsEvent)
                    val isExternalDisplaySession =
                        (anchor.display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY

                    if (isExternalDisplaySession) {
                        imeInputReceiver?.showKeyboard() ?: imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                    } else {
                        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                    }
                }
                if (Build.VERSION.SDK_INT > 29) {
                    anchor.postDelayed({ show() }, 500)  // Pixel/Android-12+ quirk
                } else {
                    show()
                }
            }
        }
    }

    val onQuickMenuItemSelected: (Int) -> Boolean = { itemId ->
        when (itemId) {
            QuickMenuAction.KEYBOARD -> {
                keyboardRequestedFromOverlay = true
                showSoftKeyboard(view, "onscreen_keyboard_enabled")
                true
            }

            QuickMenuAction.INPUT_CONTROLS -> {
                if (areControlsVisible) {
                    if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = "onscreen_controller_disabled")
                    hideInputControls()
                } else {
                    if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = "onscreen_controller_enabled")
                    val manager = PluviaApp.inputControlsManager
                    val profiles = manager?.getProfiles(false) ?: listOf()
                    if (profiles.isNotEmpty()) {
                        // Use current profile (custom or Profile 0)
                        val profileIdStr = container.getExtra("profileId", "0")
                        val profileId = profileIdStr.toIntOrNull() ?: 0
                        val targetProfile = if (profileId != 0) {
                            manager?.getProfile(profileId)
                        } else {
                            null
                        } ?: manager?.getProfile(0) ?: profiles.getOrNull(2) ?: profiles.first()

                        showInputControls(targetProfile, xServerView!!.getxServer().winHandler, container)
                    }
                }
                areControlsVisible = !areControlsVisible
                true
            }

            QuickMenuAction.DISABLE_MOUSE -> {
                val newValue = !isDisableMouseInput
                isDisableMouseInput = newValue
                container.setDisableMouseInput(newValue)
                container.saveData()
                PluviaApp.touchpadView?.setTouchscreenMouseDisabled(newValue)
                if (newValue) {
                    xServerView?.renderer?.setCursorVisible(false)
                } else {
                    applyMouseCursorVisibility()
                }
                true
            }

            QuickMenuAction.EDIT_CONTROLS -> {
                if (PrefManager.usageAnalyticsEnabled) PostHog.capture(event = "edit_controls_in_game")
                keepPausedForEditor = true

                // Get or create profile for this container
                val manager = PluviaApp.inputControlsManager ?: InputControlsManager(context)
                val allProfiles = manager.getProfiles(false)

                val profileIdStr = container.getExtra("profileId", "0")
                val profileId = profileIdStr.toIntOrNull() ?: 0

                var activeProfile = if (profileId != 0) {
                    manager.getProfile(profileId)
                } else {
                    null
                }

                // If no custom profile exists, create one automatically
                if (activeProfile == null) {
                    val sourceProfile = manager.getProfile(0)
                        ?: allProfiles.firstOrNull { it.id == 2 }
                        ?: allProfiles.firstOrNull()

                    if (sourceProfile != null) {
                        try {
                            // Create game-specific profile by duplicating Profile 0
                            activeProfile = manager.duplicateProfile(sourceProfile)

                            // Rename to game name
                            val gameName = currentAppInfo?.name ?: container.name
                            activeProfile.setName("$gameName - Controls")
                            activeProfile.save()

                            // Associate with container using extraData and save
                            container.putExtra("profileId", activeProfile.id.toString())
                            container.saveData()

                            // Apply the new profile to InputControlsView
                            PluviaApp.inputControlsView?.setProfile(activeProfile)
                            PluviaApp.radialMenuCoordinator?.setProfile(activeProfile)
                            physicalControllerHandler?.setProfile(activeProfile)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to auto-create profile for container %s", container.name)
                            // Fallback to existing profile
                            activeProfile = sourceProfile
                        }
                    }
                }

                // Enable edit mode and show controls if not visible
                if (activeProfile != null) {
                    // Capture snapshot of element positions before entering edit mode
                    val profile = PluviaApp.inputControlsView?.profile
                    if (profile != null) {
                        val snapshot = mutableMapOf<com.winlator.inputcontrols.ControlElement, Pair<Int, Int>>()
                        profile.elements.forEach { element ->
                            snapshot[element] = Pair(element.x.toInt(), element.y.toInt())
                        }
                        elementPositionsSnapshot = snapshot
                    }

                    isEditMode = true
                    PluviaApp.inputControlsView?.setEditMode(true)
                    PluviaApp.inputControlsView?.let { icView ->
                        // Wait for view to be laid out before loading elements
                        icView.post {
                            activeProfile.loadElements(icView)
                        }
                    }

                    if (!areControlsVisible) {
                        showInputControls(activeProfile, xServerView!!.getxServer().winHandler, container)
                        areControlsVisible = true
                    }
                }
                true
            }

            QuickMenuAction.TOUCHSCREEN_MODE -> {
                val newMode = !container.isTouchscreenMode
                container.setTouchscreenMode(newMode)
                container.saveData()
                isTouchscreenModeActive = newMode

                // Notify TouchpadView of the mode change
                PluviaApp.touchpadView?.setTouchscreenMode(newMode)

                if (newMode) {
                    // Apply gesture config when enabling
                    PluviaApp.touchpadView?.setGestureConfig(currentGestureConfig)

                    // Hide on-screen controls (mirrors startup priority logic)
                    if (areControlsVisible) {
                        hideInputContr…41742 tokens truncated…packing(false)
            container.saveData()
            Timber.d("Cleared needsUnpacking for non-Steam source after Mono/unpack pass")
        }
        return
    }
    try {
        val rootDir: File = imageFs.getRootDir()

        try {
            PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Handling DRM..."))
            // a:/.../GameDir/orig_dll_path.txt  (same dir as the EXE inside A:)
            val origTxtFile  = File("${imageFs.wineprefix}/dosdevices/a:/orig_dll_path.txt")

            if (origTxtFile.exists()) {
                val relDllPaths = origTxtFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
                if (relDllPaths.isNotEmpty()) {
                    Timber.i("Found ${relDllPaths.size} DLL path(s) in orig_dll_path.txt")
                    for (relDllPath in relDllPaths) {
                        try {
                            val origDll = File("${imageFs.wineprefix}/dosdevices/a:/$relDllPath")
                            if (origDll.exists()) {
                                val genCmd = "wine cmd /c \"z:\\generate_interfaces_file.exe A:\\" + relDllPath.replace('/', '\\') + " & wineserver -k\""
                                Timber.i("Running generate_interfaces_file $genCmd")
                                val genOutput = guestProgramLauncherComponent.execShellCommand(genCmd)

                                val origSteamInterfaces = File("${imageFs.wineprefix}/dosdevices/z:/steam_interfaces.txt")
                                if (origSteamInterfaces.exists()) {
                                    val finalSteamInterfaces = File(origDll.parent, "steam_interfaces.txt")
                                    try {
                                        Files.copy(
                                            origSteamInterfaces.toPath(),
                                            finalSteamInterfaces.toPath(),
                                            StandardCopyOption.REPLACE_EXISTING,
                                        )
                                        Timber.i("Copied steam_interfaces.txt to ${finalSteamInterfaces.absolutePath}")
                                    } catch (ioe: IOException) {
                                        Timber.w(ioe, "Failed to copy steam_interfaces.txt for $relDllPath")
                                    }
                                } else {
                                    Timber.w("steam_interfaces.txt not found at $origSteamInterfaces for $relDllPath")
                                }

                                Timber.i("Result of generate_interfaces_file command $genOutput")
                            } else {
                                Timber.w("DLL specified in orig_dll_path.txt not found: $origDll")
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to process DLL path $relDllPath, continuing with next path")
                        }
                    }
                } else {
                    Timber.i("orig_dll_path.txt is empty; skipping interface generation")
                }
            } else {
                Timber.i("orig_dll_path.txt not present; skipping interface generation")
            }
        } catch (e: Exception) {
            Timber.e("Error running generate_interfaces_file: $e")
        }

        output = StringBuilder()

        if (!container.isLaunchRealSteam && !container.isLaunchBionicSteam) {
            val exePaths = if (container.isUnpackFiles) {
                val scanned = ContainerUtils.scanExecutablesInADrive(container.drives)
                val filtered = ContainerUtils.filterExesForUnpacking(scanned)
                if (filtered.isEmpty()) listOf(container.executablePath).filter { it.isNotEmpty() } else filtered
            } else {
                listOf(container.executablePath).filter { it.isNotEmpty() }
            }
            if (exePaths.isEmpty()) {
                Timber.w("No executable path set, skipping Steamless")
            } else {
                PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Handling DRM..."))
                for ((index, executablePath) in exePaths.withIndex()) {
                    if (exePaths.size > 1) {
                        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText("Handling DRM (${index + 1}/${exePaths.size})"))
                    }
                    var batchFile: File? = null
                    try {
                        // Normalize path: use forward slashes for Unix format, backslashes for Windows
                        val normalizedPath = executablePath.replace('/', '\\')
                        val windowsPath = "A:\\$normalizedPath"

                        // Create a batch file that Wine can execute, to handle paths with spaces in them
                        batchFile = File(imageFs.getRootDir(), "tmp/steamless_wrapper.bat")
                        batchFile.parentFile?.mkdirs()
                        batchFile.writeText("@echo off\r\nz:\\Steamless\\Steamless.CLI.exe \"$windowsPath\"\r\n")

                        val slCmd = "wine z:\\tmp\\steamless_wrapper.bat"
                        val slOutput = guestProgramLauncherComponent.execShellCommand(slCmd)
                        output.append(slOutput)
                        Timber.i("Finished processing executable. Result: $output")
                    } catch (e: Exception) {
                        Timber.e(e, "Error running Steamless on $executablePath")
                        output.append("Error processing $executablePath: ${e.message}\n")
                    } finally {
                        batchFile?.delete()
                    }

                    // Process file moving for the executable
                    try {
                        val unixPath = executablePath.replace('\\', '/')
                        val exe = File(imageFs.wineprefix + "/dosdevices/a:/" + unixPath)
                        val unpackedExe = File(
                            imageFs.wineprefix + "/dosdevices/a:/" + unixPath + ".unpacked.exe",
                        )
                        val originalExe = File(
                            imageFs.wineprefix + "/dosdevices/a:/" + unixPath + ".original.exe",
                        )

                        val windowsPathForLog = "A:\\${executablePath.replace('/', '\\')}"
                        Timber.i("Moving files for $windowsPathForLog")
                        if (exe.exists() && unpackedExe.exists()) {
                            if (originalExe.exists()) {
                                Timber.i("Original backup exists for $windowsPathForLog; skipping overwrite")
                            } else {
                                Files.copy(exe.toPath(), originalExe.toPath(), REPLACE_EXISTING)
                            }
                            Files.copy(unpackedExe.toPath(), exe.toPath(), REPLACE_EXISTING)
                            Timber.i("Successfully moved files for $windowsPathForLog")
                        } else {
                            val errorMsg =
                                "Either exe or unpacked exe does not exist for $windowsPathForLog. Exe: ${exe.exists()}, Unpacked: ${unpackedExe.exists()}"
                            Timber.w(errorMsg)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error moving files for $executablePath")
                    }
                }
            }
        } else {
            Timber.i("Skipping Steamless (launchRealSteam=${container.isLaunchRealSteam}, launchBionicSteam=${container.isLaunchBionicSteam}, useLegacyDRM=${container.isUseLegacyDRM}, unpackFiles=${container.isUnpackFiles})")
        }

        output = StringBuilder()
        try {
            val wsOutput = guestProgramLauncherComponent.execShellCommand("wineserver -k")
            output.append(wsOutput)
            Timber.i("Result of wineserver -k command " + output)
        } catch (e: Exception) {
            Timber.e("Error running wineserver: $e")
        }
        container.setNeedsUnpacking(false)
        Timber.d("Setting needs unpacking to false")
        container.saveData()
    } catch (e: Exception) {
        Timber.e("Error during unpacking: $e")
        onError?.invoke("Error during unpacking: ${e.message}")
    } finally {
        // no-op
    }
}

private fun extractArm64ecInputDLLs(context: Context, container: Container) {
    val inputAsset = "arm64ec_input_dlls.tzst"
    val imageFs = ImageFs.find(context)
    val wineVersion: String? = container.getWineVersion()
    Log.d("XServerDisplayActivity", "arm64ec Input DLL Extraction Verification: Container Wine version: " + wineVersion)

    // Check if the wineVersion string is not null and contains "arm64ec"
    if (wineVersion != null && wineVersion.contains("proton-9.0-arm64ec")) {
        val wineFolder: File = File(imageFs.getWinePath() + "/lib/wine/")
        Log.d("XServerDisplayActivity", "Wine version contains arm64ec. Extracting input dlls to " + wineFolder.getPath())
        val success: Boolean = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.assets, inputAsset, wineFolder)
        if (!success) {
            Log.d("XServerDisplayActivity", "Failed to extract input dlls")
        }
    } else {
        // Updated log message for clarity
        Log.d("XServerDisplayActivity", "Wine version is not arm64ec, skipping input dlls extraction.")
    }
}

private fun extractx86_64InputDlls(context: Context, container: Container) {
    val inputAsset = "x86_64_input_dlls.tzst"
    val imageFs = ImageFs.find(context)
    val wineVersion: String? = container.getWineVersion()
    Log.d("XServerDisplayActivity", "x86_64 Input DLL Extraction Verification: Container Wine version: " + wineVersion)
    if ("proton-9.0-x86_64" == wineVersion) {
        val wineFolder: File = File(imageFs.getWinePath() + "/lib/wine/")
        Log.d("XServerDisplayActivity", "Extracting input dlls to " + wineFolder.getPath())
    } else Log.d("XServerDisplayActivity", "Wine version is not proton-9.0-x86_64, skipping input dlls extraction")
}

private suspend fun setupWineSystemFiles(
    context: Context,
    firstTimeBoot: Boolean,
    screenInfo: ScreenInfo,
    xServerState: MutableState<XServerState>,
    // xServerViewModel: XServerViewModel,
    container: Container,
    containerManager: ContainerManager,
    // shortcut: Shortcut?,
    envVars: EnvVars,
    contentsManager: ContentsManager,
    onExtractFileListener: OnExtractFileListener?,
) {
    val imageFs = ImageFs.find(context)
    val appVersion = AppUtils.getVersionCode(context).toString()
    val imgVersion = imageFs.getVersion().toString()
    var containerDataChanged = false

    val appliedContainerVariant = container.getExtra("appliedContainerVariant")
    val appliedWineVersion = container.getExtra("appliedWineVersion")
    val markersMissing = appliedContainerVariant.isEmpty() || appliedWineVersion.isEmpty()
    val firstBoot = container.getExtra("appVersion").isEmpty()
    val imgVersionChanged = container.getExtra("imgVersion") != imgVersion
    val variantChanged = !markersMissing && container.containerVariant != appliedContainerVariant
    val wineVersionChanged = !markersMissing && container.wineVersion != appliedWineVersion

    if (firstBoot || imgVersionChanged || variantChanged || wineVersionChanged) {
        applyGeneralPatches(context, container, imageFs, xServerState.value.wineInfo, containerManager, onExtractFileListener)
        container.putExtra("appliedContainerVariant", container.containerVariant)
        container.putExtra("appliedWineVersion", container.wineVersion)
        container.putExtra("appVersion", appVersion)
        container.putExtra("imgVersion", imgVersion)
        containerDataChanged = true
    } else if (markersMissing) {
        // Pre-existing container: trust the on-disk prefix and adopt it as-is.
        container.putExtra("appliedContainerVariant", container.containerVariant)
        container.putExtra("appliedWineVersion", container.wineVersion)
        containerDataChanged = true
    }

    // Always refresh components files
    refreshComponentsFiles(context)

    // Normalize dxwrapper for state (dxvk includes version for extraction switch)
    if (xServerState.value.dxwrapper == "dxvk") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "dxvk-" + xServerState.value.dxwrapperConfig?.get("version"),
        )
    }

    // Also normalize VKD3D to include version like vkd3d-<version>
    if (xServerState.value.dxwrapper == "vkd3d") {
        xServerState.value = xServerState.value.copy(
            dxwrapper = "vkd3d-" + xServerState.value.dxwrapperConfig?.get("vkd3dVersion"),
        )
    }

    val needReextract = ALWAYS_REEXTRACT || xServerState.value.dxwrapper != container.getExtra("dxwrapper") || variantChanged || wineVersionChanged

    Timber.i("needReextract is " + needReextract)
    Timber.i("xServerState.value.dxwrapper is " + xServerState.value.dxwrapper)
    Timber.i("container.getExtra(\"dxwrapper\") is " + container.getExtra("dxwrapper"))

    if (needReextract) {
        extractDXWrapperFiles(
            context,
            firstTimeBoot,
            container,
            containerManager,
            xServerState.value.dxwrapper,
            imageFs,
            contentsManager,
            onExtractFileListener,
        )
        container.putExtra("dxwrapper", xServerState.value.dxwrapper)
        containerDataChanged = true
    }

    if (xServerState.value.dxwrapper == "cnc-ddraw") envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini")

    // val wincomponents = if (shortcut != null) shortcut.getExtra("wincomponents", container.winComponents) else container.winComponents
    val wincomponents = container.winComponents
    if (!wincomponents.equals(container.getExtra("wincomponents"))) {
        extractWinComponentFiles(context, firstTimeBoot, imageFs, container, containerManager, onExtractFileListener)
        container.putExtra("wincomponents", wincomponents)
        containerDataChanged = true
    }

    // OpenAL audio: extract native DLLs if WINEDLLOVERRIDES mentions openal32 or soft_oal
    val dllOverrides = EnvVars(container.envVars).get("WINEDLLOVERRIDES")
    val needsOpenalDlls = dllOverrides.contains("openal32") || dllOverrides.contains("soft_oal")
    val openalState = if (needsOpenalDlls) "yes" else "no"
    if (openalState != container.getExtra("openal_dlls") || firstTimeBoot) {
        if (needsOpenalDlls) {
            val windowsDir = File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

            // Download or use cached/bundled openal component
            val openalFile = WinComponentDownloader.ensureWinComponentAvailable(context, "openal") { progress ->
                Timber.d("Downloading openal component: ${(progress * 100).toInt()}%")
            }

            if (openalFile == null) {
                // Legacy variant: use bundled asset
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, context.assets,
                    "wincomponents/openal.tzst", windowsDir, onExtractFileListener,
                )
            } else {
                // Modern variant: use downloaded file
                TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, openalFile,
                    windowsDir, onExtractFileListener,
                )
            }
        }
        container.putExtra("openal_dlls", openalState)
        containerDataChanged = true
    }

    if (container.isLaunchRealSteam || container.isLaunchBionicSteam) {
        extractSteamFiles(context, container, onExtractFileListener)
    }

    // If bionic mode is off, scrub any bionic-installed files from a previous
    // enable. The Wine-side lsteamclient.dll comes from Proton's own tree in
    // non-bionic launches, and the native libsteamclient.so should not be
    // present at all unless bionic is on.
    if (!container.isLaunchBionicSteam) {
        cleanupBionicSteamAssets(imageFs)
    }

    val desktopTheme = container.desktopTheme
    if ((desktopTheme + "," + screenInfo) != container.getExtra("desktopTheme")) {
        WineThemeManager.apply(context, WineThemeManager.ThemeInfo(desktopTheme), screenInfo)
        container.putExtra("desktopTheme", desktopTheme + "," + screenInfo)
        containerDataChanged = true
    }

    WineStartMenuCreator.create(context, container)
    WineUtils.createDosdevicesSymlinks(context, container)

    // The EOS overlay's CEF browser needs RpcSs and BITS: without them it
    // crash-loops and EOS login fails. Force normal services only for containers
    // that actually have the overlay installed.
    val needsOverlayServices = EpicOverlayManager.isOverlayInstalled(container)
    if (needsOverlayServices) {
        // Prefix re-provisioning above (wine/proton version change) replaces user.reg,
        // so the overlay registry entries must be repaired here, not just at install time.
        EpicOverlayManager.ensureRegistryEntries(container)
    }
    val effectiveStartupSelection = if (needsOverlayServices) Container.STARTUP_SELECTION_NORMAL else container.startupSelection
    val startupSelection = effectiveStartupSelection.toString()
    if (startupSelection != container.getExtra("startupSelection")) {
        WineUtils.changeServicesStatus(container, effectiveStartupSelection != Container.STARTUP_SELECTION_NORMAL)
        container.putExtra("startupSelection", startupSelection)
        containerDataChanged = true
    }

    if (containerDataChanged) container.saveData()
}

private suspend fun applyGeneralPatches(
    context: Context,
    container: Container,
    imageFs: ImageFs,
    wineInfo: WineInfo,
    containerManager: ContainerManager,
    onExtractFileListener: OnExtractFileListener?,
) {
    Timber.i("Applying general patches")
    val rootDir = imageFs.getRootDir()
    val contentsManager = ContentsManager(context)
    if (container.containerVariant.equals(Container.GLIBC)) {
        FileUtils.delete(File(rootDir, "/opt/apps"))
        val downloaded = File(imageFs.getFilesDir(), "imagefs_patches_gamenative.tzst")
        Timber.i("Extracting imagefs_patches_gamenative.tzst")
        if (Arrays.asList<String?>(*context.getAssets().list("")).contains("imagefs_patches_gamenative.tzst") == true) {
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context.assets,
                "imagefs_patches_gamenative.tzst",
                rootDir,
                onExtractFileListener,
            )
        } else if (downloaded.exists()){
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                downloaded,
                rootDir,
                onExtractFileListener,
            );
        }
        Timber.i("Extracting WFM from container_pattern_common.tzst")
        check(containerManager.extractContainerPatternCommonWfm(rootDir, onExtractFileListener)) {
            "Failed to extract WFM from container_pattern_common.tzst"
        }
    } else {
        Timber.i("Extracting container_pattern_common.tzst")
        containerManager.extractContainerPatternCommon(rootDir, onExtractFileListener)
        Timber.i("Attempting to extract _container_pattern.tzst with wine version " + container.wineVersion)
    }
    containerManager.extractContainerPatternFile(container.wineVersion, contentsManager, container.rootDir, onExtractFileListener)
    WineUtils.applySystemTweaks(context, wineInfo)
    container.putExtra("graphicsDriver", null)
    container.putExtra("desktopTheme", null)
    container.putExtra("xaudioDllsExtracted", null)
    container.putExtra("wincomponents", null)
    container.putExtra("audioDriver", null)
    container.putExtra("startupSelection", null)
    WinlatorPrefManager.init(context)
    WinlatorPrefManager.putString("current_box64_version", "")
}

private fun refreshComponentsFiles(context: Context) {
    val extractionPairs = listOf(
        "pulseaudio-gamenative-20260612.tzst" to File(context.filesDir, "pulseaudio")
    )

    AssetUtils.extractComponentsWithVersionCheck(
        extractionPairs,
        context.assets,
        TarCompressorUtils.Type.ZSTD
    )
}

/**
 * Helper function to extract a graphics driver component, downloading if needed (modern variant)
 * or using bundled assets (legacy variant).
 */
private suspend fun extractGraphicsDriverComponent(
    context: Context,
    componentId: String,
    rootDir: File,
    onExtractFileListener: OnExtractFileListener? = null
) {
    val componentFile = GraphicsDriverDownloader.ensureGraphicsDriverAvailable(context, componentId) { progress ->
        Timber.d("Downloading graphics driver $componentId: ${(progress * 100).toInt()}%")
    }

    if (componentFile == null) {
        // Legacy variant: use bundled asset
        Timber.d("Extracting graphics driver $componentId from bundled assets")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, context.assets,
            "graphics_driver/$componentId.tzst", rootDir, onExtractFileListener,
        )
    } else {
        // Modern variant: use downloaded file
        Timber.d("Extracting graphics driver $componentId from downloaded file: ${componentFile.absolutePath}")
        val extractType = if (componentFile.name.endsWith(".tar.xz")) {
            TarCompressorUtils.Type.XZ
        } else {
            TarCompressorUtils.Type.ZSTD
        }
        TarCompressorUtils.extract(
            extractType, componentFile,
            rootDir, onExtractFileListener,
        )
    }
}

/**
 * Helper function to extract a dxwrapper component, downloading if needed (modern variant)
 * or using bundled assets (legacy variant).
 */
private suspend fun extractDXWrapperComponent(
    context: Context,
    componentId: String,
    windowsDir: File,
    onExtractFileListener: OnExtractFileListener?
) {
    val componentFile = DXWrapperDownloader.ensureDXWrapperAvailable(context, componentId) { progress ->
        Timber.d("Downloading dxwrapper $componentId: ${(progress * 100).toInt()}%")
    }

    if (componentFile == null) {
        // Legacy variant: use bundled asset
        Timber.d("Extracting dxwrapper $componentId from bundled assets")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, context.assets,
            "dxwrapper/$componentId.tzst", windowsDir, onExtractFileListener,
        )
    } else {
        // Modern variant: use downloaded file
        Timber.d("Extracting dxwrapper $componentId from downloaded file: ${componentFile.absolutePath}")
        TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, componentFile,
            windowsDir, onExtractFileListener,
        )
    }
}

private suspend fun extractDXWrapperFiles(
    context: Context,
    firstTimeBoot: Boolean,
    container: Container,
    containerManager: ContainerManager,
    dxwrapper: String,
    imageFs: ImageFs,
    contentsManager: ContentsManager,
    onExtractFileListener: OnExtractFileListener?,
) {
    val dlls = arrayOf(
        "d3d10.dll",
        "d3d10_1.dll",
        "d3d10core.dll",
        "d3d11.dll",
        "d3d12.dll",
        "d3d12core.dll",
        "d3d8.dll",
        "d3d9.dll",
        "dxgi.dll",
        "ddraw.dll",
    )
    val splitDxWrapper = dxwrapper.split("-")[0]
    if (firstTimeBoot && splitDxWrapper != "vkd3d") cloneOriginalDllFiles(imageFs, *dlls)
    val rootDir = imageFs.getRootDir()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")

    when (splitDxWrapper) {
        "wined3d" -> {
            restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls)
        }
        "cnc-ddraw" -> {
            restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls)
            val assetDir = "dxwrapper/cnc-ddraw-" + DefaultVersion.CNC_DDRAW
            val configFile = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/ProgramData/cnc-ddraw/ddraw.ini")
            if (!configFile.isFile) FileUtils.copy(context, "$assetDir/ddraw.ini", configFile)
            val shadersDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/ProgramData/cnc-ddraw/Shaders")
            FileUtils.delete(shadersDir)
            FileUtils.copy(context, "$assetDir/Shaders", shadersDir)
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context.assets,
                "$assetDir/ddraw.tzst", windowsDir, onExtractFileListener,
            )
        }
        "vkd3d" -> {
            Timber.i("Extracting VKD3D D3D12 DLLs for dxwrapper: $dxwrapper")
            val profile: ContentProfile? = contentsManager.getProfileByEntryName(dxwrapper)
            // Determine graphics driver to choose DXVK version
            val vortekLike = container.graphicsDriver == "vortek" || container.graphicsDriver == "adreno" || container.graphicsDriver == "sd-8-elite"
            val dxvkMinVersion = "2.6.1-gplasync"
            val dxwrapperConfig = DXVKHelper.parseConfig(container.dxWrapperConfig)
            val dxvkVersion = dxwrapperConfig.get("version", dxvkMinVersion)
            val dxvkVersionForVkd3d = if (vortekLike && GPUHelper.vkGetApiVersionSafe() < GPUHelper.vkMakeVersion(1, 3, 0)) {
                "1.10.3"
            } else if (ManifestComponentHelper.isAtLeastVersion(dxvkVersion, 2, 1, 0)) {
                dxvkVersion
            } else {
                dxvkMinVersion
            }
            Timber.i("Extracting VKD3D DX version for dxwrapper: $dxvkVersionForVkd3d")
            extractDXWrapperComponent(context, "dxvk-$dxvkVersionForVkd3d", windowsDir, onExtractFileListener)

            if (profile != null) {
                Timber.d("Applying user-defined VKD3D content profile: " + dxwrapper)
                contentsManager.applyContent(profile);
            } else {
                // Determine VKD3D version from state config
                Timber.i("Extracting VKD3D D3D12 DLLs version: $dxwrapper")
                extractDXWrapperComponent(context, dxwrapper, windowsDir, onExtractFileListener)
            }
        }
        else -> {
            val profile: ContentProfile? = contentsManager.getProfileByEntryName(dxwrapper)
            // This block handles dxvk-VERSION strings
            Timber.i("Extracting DXVK/D8VK DLLs for dxwrapper: $dxwrapper")
            restoreOriginalDllFiles(context, container, containerManager, imageFs, "d3d12.dll", "d3d12core.dll", "ddraw.dll")
            if (profile != null) {
                Timber.d("Applying user-defined DXVK content profile: " + dxwrapper)
                contentsManager.applyContent(profile);
            } else {
                extractDXWrapperComponent(context, dxwrapper, windowsDir, onExtractFileListener)
            }
            extractDXWrapperComponent(context, "d8vk-${DefaultVersion.D8VK}", windowsDir, onExtractFileListener)
        }
    }
}
private fun cloneOriginalDllFiles(imageFs: ImageFs, vararg dlls: String) {
    val rootDir = imageFs.rootDir
    val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
    if (!cacheDir.isDirectory) cacheDir.mkdirs()
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
    val dirnames = arrayOf("system32", "syswow64")

    for (dll in dlls) {
        for (dirname in dirnames) {
            val dllFile = File(windowsDir, "$dirname/$dll")
            if (dllFile.isFile) FileUtils.copy(dllFile, File(cacheDir, "$dirname/$dll"))
        }
    }
}
private fun restoreOriginalDllFiles(
    context: Context,
    container: Container,
    containerManager: ContainerManager,
    imageFs: ImageFs,
    vararg dlls: String,
) {
    val rootDir = imageFs.rootDir
    if (container.containerVariant.equals(Container.GLIBC)) {
        val cacheDir = File(rootDir, ImageFs.CACHE_PATH + "/original_dlls")
        val contentsManager = ContentsManager(context)
        if (cacheDir.isDirectory) {
            val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
            val dirnames = cacheDir.list()
            var filesCopied = 0

            for (dll in dlls) {
                var success = false
                for (dirname in dirnames!!) {
                    val srcFile = File(cacheDir, "$dirname/$dll")
                    val dstFile = File(windowsDir, "$dirname/$dll")
                    if (FileUtils.copy(srcFile, dstFile)) success = true
                }
                if (success) filesCopied++
            }

            if (filesCopied == dlls.size) return
        }

        containerManager.extractContainerPatternFile(
            container.wineVersion, contentsManager, container.rootDir,
            object : OnExtractFileListener {
                override fun onExtractFile(file: File, size: Long): File? {
                    val path = file.path
                    if (path.contains("system32/") || path.contains("syswow64/")) {
                        for (dll in dlls) {
                            if (path.endsWith("system32/$dll") || path.endsWith("syswow64/$dll")) return file
                        }
                    }
                    return null
                }
            },
        )

        cloneOriginalDllFiles(imageFs, *dlls)
    } else {
        val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
        var system32dlls: File? = null
        var syswow64dlls: File? = null

        if (container.wineVersion.contains("arm64ec")) system32dlls = File(imageFs.getWinePath() + "/lib/wine/aarch64-windows")
        else system32dlls = File(imageFs.getWinePath() + "/lib/wine/x86_64-windows")

        syswow64dlls = File(imageFs.getWinePath() + "/lib/wine/i386-windows")

        for (dll in dlls) {
            var srcFile = File(system32dlls, dll)
            var dstFile = File(windowsDir, "system32/" + dll)
            FileUtils.copy(srcFile, dstFile)
            srcFile = File(syswow64dlls, dll)
            dstFile = File(windowsDir, "syswow64/" + dll)
            FileUtils.copy(srcFile, dstFile)
        }
    }
}
private suspend fun extractWinComponentFiles(
    context: Context,
    firstTimeBoot: Boolean,
    imageFs: ImageFs,
    container: Container,
    containerManager: ContainerManager,
    // shortcut: Shortcut?,
    onExtractFileListener: OnExtractFileListener?,
) {
    val rootDir = imageFs.rootDir
    val windowsDir = File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
    val systemRegFile = File(rootDir, ImageFs.WINEPREFIX + "/system.reg")

    try {
        val wincomponentsJSONObject = JSONObject(FileUtils.readString(context, "wincomponents/wincomponents.json"))
        val dlls = mutableListOf<String>()
        // val wincomponents = if (shortcut != null) shortcut.getExtra("wincomponents", container.winComponents) else container.winComponents
        val wincomponents = container.winComponents

        if (firstTimeBoot) {
            for (wincomponent in KeyValueSet(wincomponents)) {
                val dlnames = wincomponentsJSONObject.getJSONArray(wincomponent[0])
                for (i in 0 until dlnames.length()) {
                    val dlname = dlnames.getString(i)
                    dlls.add(if (!dlname.endsWith(".exe")) "$dlname.dll" else dlname)
                }
            }

            cloneOriginalDllFiles(imageFs, *dlls.toTypedArray())
            dlls.clear()
        }

        val oldWinComponentsMap = KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).associate { it[0] to it[1] }

        for (wincomponent in KeyValueSet(wincomponents)) {
            val oldValue = oldWinComponentsMap[wincomponent[0]]
            if (oldValue == null){

                Timber.d("Wincomponent ${wincomponent[0]} does not exist in oldwincomponents, skipping")
            }
            if (oldValue == wincomponent[1] && !firstTimeBoot) continue
            val identifier = wincomponent[0]
            val useNative = wincomponent[1].equals("1")

            if (!container.wineVersion.contains("arm64ec") && identifier.contains("opengl") && useNative) continue

            // Note: GameNative do not bundle directinput and directinput8 dlls, need to skip them and use wine/proton dll instead
            if (useNative && (identifier != "directinput8" && identifier != "directinput")) {
                // Download or use cached/bundled wincomponent
                val componentFile = WinComponentDownloader.ensureWinComponentAvailable(
                    context, identifier
                ) { progress ->
                    Timber.d("Downloading wincomponent $identifier: ${(progress * 100).toInt()}%")
                }

                if (componentFile == null) {
                    // Legacy variant: use bundled asset
                    Timber.d("Extracting wincomponent $identifier from bundled assets")
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD, context.assets,
                        "wincomponents/$identifier.tzst", windowsDir, onExtractFileListener,
                    )
                } else {
                    // Modern variant: use downloaded file
                    Timber.d("Extracting wincomponent $identifier from downloaded file: ${componentFile.absolutePath}")
                    TarCompressorUtils.extract(
                        TarCompressorUtils.Type.ZSTD, componentFile,
                        windowsDir, onExtractFileListener,
                    )
                }
            } else {
                val dlnames = wincomponentsJSONObject.getJSONArray(identifier)
                for (i in 0 until dlnames.length()) {
                    val dlname = dlnames.getString(i)
                    dlls.add(if (!dlname.endsWith(".exe")) "$dlname.dll" else dlname)
                }
            }
            WineUtils.overrideWinComponentDlls(context, container, identifier, useNative)
            WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative)
        }

        if (!dlls.isEmpty()) restoreOriginalDllFiles(context, container, containerManager, imageFs, *dlls.toTypedArray())
    } catch (e: JSONException) {
        Timber.e("Failed to read JSON: $e")
    }
}

private suspend fun extractGraphicsDriverFiles(
    context: Context,
    graphicsDriver: String,
    dxwrapper: String,
    dxwrapperConfig: KeyValueSet,
    container: Container,
    envVars: EnvVars,
    firstTimeBoot: Boolean,
    vkbasaltConfig: String,
) {
    if (container.containerVariant.equals(Container.GLIBC)) {
        // Get the configured driver version or use default
        val turnipVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "turnip" } ?: DefaultVersion.TURNIP
        val virglVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "virgl" } ?: DefaultVersion.VIRGL
        val zinkVersion = container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "zink" } ?: DefaultVersion.ZINK
        val adrenoVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "adreno" } ?: DefaultVersion.ADRENO
        val sd8EliteVersion =
            container.graphicsDriverVersion.takeIf { it.isNotEmpty() && graphicsDriver == "sd-8-elite" } ?: DefaultVersion.SD8ELITE

        var cacheId = graphicsDriver
        if (graphicsDriver == "turnip") {
            cacheId += "-" + turnipVersion + "-" + zinkVersion
            if (GPUInformation.isAdreno710_720_732(context)) {
                val userEnvVars = EnvVars(container.envVars)
                val tuDebug = userEnvVars.get("TU_DEBUG")
                if (!tuDebug.contains("gmem")) userEnvVars.put("TU_DEBUG", (if (!tuDebug.isEmpty()) "$tuDebug," else "") + "gmem")
                container.envVars = userEnvVars.toString()
            } else if (turnipVersion == "25.2.0" || turnipVersion == "25.3.0") {
                envVars.put("TU_DEBUG", "sysmem");
            }
        } else if (graphicsDriver == "virgl") {
            cacheId += "-" + DefaultVersion.VIRGL
        } else if (graphicsDriver == "vortek" || graphicsDriver == "adreno" || graphicsDriver == "sd-8-elite") {
            cacheId += "-" + DefaultVersion.VORTEK
        }

        val imageFs = ImageFs.find(context)
        val configDir = imageFs.configDir
        val sentinel = File(configDir, ".current_graphics_driver")   // lives in shared tree
        val onDiskId = sentinel.takeIf { it.exists() }?.readText() ?: ""
        val changed = ALWAYS_REEXTRACT || cacheId != container.getExtra("graphicsDriver") || cacheId != onDiskId
        Timber.i("Changed is " + changed + " will re-extract drivers accordingly.")
        val rootDir = imageFs.rootDir
        envVars.put("vblank_mode", "0")

        if (changed) {
            FileUtils.delete(File(imageFs.lib32Dir, "libvulkan_freedreno.so"))
            FileUtils.delete(File(imageFs.lib64Dir, "libvulkan_freedreno.so"))
            FileUtils.delete(File(imageFs.lib64Dir, "libvulkan_vortek.so"))
            FileUtils.delete(File(imageFs.lib32Dir, "libvulkan_vortek.so"))
            FileUtils.delete(File(imageFs.lib32Dir, "libGL.so.1.7.0"))
            FileUtils.delete(File(imageFs.lib64Dir, "libGL.so.1.7.0"))
            val vulkanICDDir = File(rootDir, "/usr/share/vulkan/icd.d")
            FileUtils.delete(vulkanICDDir)
            vulkanICDDir.mkdirs()
            container.putExtra("graphicsDriver", cacheId)
            container.saveData()
            if (!sentinel.exists()) {
                sentinel.parentFile?.mkdirs()
                sentinel.createNewFile()
            }
            sentinel.writeText(cacheId)
        }
        if (dxwrapper.contains("dxvk")) {
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
        } else if (dxwrapper.contains("vkd3d")) {
            DXVKHelper.setVKD3DEnvVars(context, dxwrapperConfig, envVars)
        }

        if (graphicsDriver == "turnip") {
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("TU_OVERRIDE_HEAP_SIZE", "4096")
            if (!envVars.has("MESA_VK_WSI_PRESENT_MODE")) envVars.put("MESA_VK_WSI_PRESENT_MODE", "mailbox")
            envVars.put("vblank_mode", "0")

            if (!GPUInformation.isAdreno6xx(context) && !GPUInformation.isAdreno710_720_732(context)) {
                val userEnvVars = EnvVars(container.envVars)
                val tuDebug = userEnvVars.get("TU_DEBUG")
                if (!tuDebug.contains("sysmem")) userEnvVars.put("TU_DEBUG", (if (!tuDebug.isEmpty()) "$tuDebug," else "") + "sysmem")
                container.envVars = userEnvVars.toString()
            }

            if (changed) {
                extractGraphicsDriverComponent(context, "turnip-$turnipVersion", rootDir)
                extractGraphicsDriverComponent(context, "zink-$zinkVersion", rootDir)
            }
        } else if (graphicsDriver == "virgl") {
            envVars.put("GALLIUM_DRIVER", "virpipe")
            envVars.put("VIRGL_NO_READBACK", "true")
            envVars.put("VIRGL_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VIRGL_SERVER_PATH)
            envVars.put("MESA_EXTENSION_OVERRIDE", "-GL_EXT_vertex_array_bgra")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.1")
            envVars.put("vblank_mode", "0")
            if (changed) {
                extractGraphicsDriverComponent(context, "virgl-$virglVersion", rootDir)
            }
        } else if (graphicsDriver == "vortek") {
            Timber.i("Setting Vortek env vars")
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("ZINK_CONTEXT_THREADED", "1")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3")
            envVars.put("WINEVKUSEPLACEDADDR", "1")
            envVars.put("VORTEK_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VORTEK_SERVER_PATH)
            Timber.i("dxwrapper is " + dxwrapper)
            if (dxwrapper.contains("dxvk")) {
                envVars.put("WINE_D3D_CONFIG", "renderer=gdi")
            }
            if (changed) {
                extractGraphicsDriverComponent(context, "vortek-2.1", rootDir)
                extractGraphicsDriverComponent(context, "zink-22.2.5", rootDir)
            }
        } else if (graphicsDriver == "adreno" || graphicsDriver == "sd-8-elite") {
            val assetZip = if (graphicsDriver == "adreno") "Adreno_${adrenoVersion}_adpkg.zip" else "SD8Elite_${sd8EliteVersion}.zip"

            val componentRoot = com.winlator.core.GeneralComponents.getComponentDir(
                com.winlator.core.GeneralComponents.Type.ADRENOTOOLS_DRIVER,
                context,
            )

            // Download or get cached core driver
            val driverFile = CoreDriverDownloader.ensureCoreDriverAvailable(context, assetZip) { progress ->
                Timber.d("Downloading core driver $assetZip: ${(progress * 100).toInt()}%")
            }

            // Read manifest name from zip to determine folder name
            val identifier = if (driverFile != null) {
                // Modern variant: read from downloaded file
                com.winlator.core.FileUtils.readZipManifestNameFromFile(driverFile) ?: assetZip.substringBeforeLast('.')
            } else {
                // Legacy variant: read from assets
                readZipManifestNameFromAssets(context, assetZip) ?: assetZip.substringBeforeLast('.')
            }

            // Only (re)extract if changed
            val adrenoCacheId = "${graphicsDriver}-${identifier}"
            val needsExtract = changed || adrenoCacheId != container.getExtra("graphicsDriverAdreno")

            if (needsExtract) {
                val destinationDir = File(componentRoot.toString())
                if (destinationDir.isDirectory) {
                    FileUtils.delete(destinationDir)
                }
                destinationDir.mkdirs()

                if (driverFile != null) {
                    // Modern variant: extract from downloaded file
                    Timber.d("Extracting core driver from downloaded file: ${driverFile.absolutePath}")
                    com.winlator.core.FileUtils.extractZipFromFile(driverFile, destinationDir)
                } else {
                    // Legacy variant: extract from assets
                    Timber.d("Extracting core driver from bundled assets: $assetZip")
                    com.winlator.core.FileUtils.extractZipFromAssets(context, assetZip, destinationDir)
                }

                val targetLibName = "vulkan.adreno.so"

                // Update cache and only the adrenotoolsDriver key within graphics driver config
                container.putExtra("graphicsDriverAdreno", adrenoCacheId)
                container.saveData()
            }
            envVars.put("GALLIUM_DRIVER", "zink")
            envVars.put("ZINK_CONTEXT_THREADED", "1")
            envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3")
            envVars.put("WINEVKUSEPLACEDADDR", "1")
            envVars.put("VORTEK_SERVER_PATH", imageFs.getRootDir().getPath() + UnixSocketConfig.VORTEK_SERVER_PATH)
            Timber.i("dxwrapper is " + dxwrapper)
            if (dxwrapper.contains("dxvk")) {
                envVars.put("WINE_D3D_CONFIG", "renderer=gdi")
            }
            if (changed) {
                extractGraphicsDriverComponent(context, "vortek-2.1", rootDir)
                extractGraphicsDriverComponent(context, "zink-22.2.5", rootDir)
            }
        }
    } else {
        var adrenoToolsDriverId: String? = ""
        val selectedDriverVersion: String?
        val graphicsDriverConfig = KeyValueSet(container.getGraphicsDriverConfig())
        val imageFs = ImageFs.find(context)

        val currentWrapperVersion: String? = graphicsDriverConfig.get("version", DefaultVersion.WRAPPER)
        val isAdrenotoolsTurnip: String? = graphicsDriverConfig.get("adrenotoolsTurnip", "1") // Default to "1"

        selectedDriverVersion = currentWrapperVersion

        adrenoToolsDriverId =
            if (selectedDriverVersion!!.contains(DefaultVersion.WRAPPER)) DefaultVersion.WRAPPER else selectedDriverVersion
        Log.d("GraphicsDriverExtraction", "Adrenotools DriverID: " + adrenoToolsDriverId)

        val rootDir: File? = imageFs.getRootDir()

        if (dxwrapper.contains("dxvk")) {
            DXVKHelper.setEnvVars(context, dxwrapperConfig, envVars)
            val version = dxwrapperConfig.get("version")
            if (version == "1.11.1-sarek") {
                Timber.tag("GraphicsDriverExtraction").d("Disabling Wrapper PATCH_OPCONSTCOMP SPIR-V pass")
                envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1")
            }
        } else if (dxwrapper.contains("vkd3d")) {
            DXVKHelper.setVKD3DEnvVars(context, dxwrapperConfig, envVars)
        }

        val useDRI3: Boolean = container.isUseDRI3
        if (!useDRI3) {
            envVars.put("MESA_VK_WSI_DEBUG", "sw")
        }

        if (currentWrapperVersion.lowercase(Locale.getDefault())
                .contains("turnip") && isAdrenotoolsTurnip == "0"
        ) envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/freedreno_icd.aarch64.json")
        else envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir().path + "/vulkan/icd.d/wrapper_icd.aarch64.json")
        envVars.put("GALLIUM_DRIVER", "zink")
        envVars.put("LIBGL_KOPPER_DISABLE", "true")

        if (currentWrapperVersion.lowercase(Locale.getDefault()).contains("turnip")
            && GPUInformation.isAdreno710_720_732(context)) {
            var tuDebug = envVars.get("TU_DEBUG").replace("sysmem", "gmem")
            if (!tuDebug.contains("gmem")) tuDebug = (if (tuDebug.isEmpty()) "" else "$tuDebug,") + "gmem"
            envVars.put("TU_DEBUG", tuDebug)
        }

        // 1. Get the main WRAPPER selection (e.g., "Wrapper-v2") from the class field.
        val mainWrapperSelection: String = graphicsDriver

        // 2. Get the WRAPPER that was last saved to the container's settings.
        val lastInstalledMainWrapper = container.getExtra("lastInstalledMainWrapper")

        // 3. Check if we need to extract a new wrapper file.
        if (ALWAYS_REEXTRACT || firstTimeBoot || mainWrapperSelection != lastInstalledMainWrapper) {
            // We only extract if the selection is actually a wrapper file.
            if (mainWrapperSelection.lowercase(Locale.getDefault()).startsWith("wrapper")) {
                val wrapperComponentId = mainWrapperSelection.lowercase(Locale.getDefault())
                Log.d("GraphicsDriverExtraction", "WRAPPER selection changed or first boot. Extracting: $wrapperComponentId")
                try {
                    val wrapperContentsManager = ContentsManager(context)
                    val wrapperProfile: ContentProfile? =
                        wrapperContentsManager.getProfileByEntryName(wrapperComponentId)
                    if (wrapperProfile != null) {
                        Timber.d("Applying user-defined wrapper content profile: $wrapperComponentId")
                        wrapperContentsManager.applyContent(wrapperProfile)
                    } else {
                        extractGraphicsDriverComponent(context, wrapperComponentId, rootDir!!)
                    }
                    // After success, save the new version so we don't re-extract next time.
                    container.putExtra("lastInstalledMainWrapper", mainWrapperSelection)
                    container.saveData()
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Failed to install graphics driver '$wrapperComponentId'. An internet connection is required the first time this driver is used.",
                        e,
                    )
                }
                Log.d("XServerDisplayActivity", "First time container boot, extracting extra_libs.tzst")
                extractGraphicsDriverComponent(context, "extra_libs", rootDir!!)
                val renderer = GPUInformation.getRenderer(null, null)
                if (container.wineVersion.contains("arm64ec") && renderer?.contains("Mali") != true) {
                    extractGraphicsDriverComponent(
                        context,
                        "zink_dlls",
                        File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows")
                    )
                }
            }
        }

        if (adrenoToolsDriverId !== "System") {
            val adrenotoolsManager: AdrenotoolsManager = AdrenotoolsManager(context)
            adrenotoolsManager.setDriverById(envVars, imageFs, adrenoToolsDriverId)
        }

        var vulkanVersion = graphicsDriverConfig.get("vulkanVersion") ?: "1.0"
        val vulkanVersionPatch = GPUHelper.vkVersionPatch()

        vulkanVersion = "$vulkanVersion.$vulkanVersionPatch"
        envVars.put("WRAPPER_VK_VERSION", vulkanVersion)

        val blacklistedExtensions: String? = graphicsDriverConfig.get("blacklistedExtensions")
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", blacklistedExtensions)

        val gpuName = graphicsDriverConfig.get("gpuName")
        if (gpuName != "Device") {
            envVars.put("WRAPPER_DEVICE_NAME", gpuName)
            envVars.put("WRAPPER_DEVICE_ID", GPUInformation.getDeviceIdFromGPUName(context, gpuName))
            envVars.put("WRAPPER_VENDOR_ID", GPUInformation.getVendorIdFromGPUName(context, gpuName))
        }

        val maxDeviceMemory: String? = graphicsDriverConfig.get("maxDeviceMemory", "0")
        if (maxDeviceMemory != null && maxDeviceMemory.toInt() > 0)
            envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory)

        val presentMode = graphicsDriverConfig.get("presentMode")
        if (presentMode.contains("immediate")) {
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "1")
        }
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode)

        val resourceType = graphicsDriverConfig.get("resourceType")
        envVars.put("WRAPPER_RESOURCE_TYPE", resourceType)

        val syncFrame = graphicsDriverConfig.get("syncFrame")
        if (syncFrame == "1") envVars.put("MESA_VK_WSI_DEBUG", "forcesync")

        val disablePresentWait = graphicsDriverConfig.get("disablePresentWait")
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait)

        val isWrapperGamenative = graphicsDriver.equals("wrapper-gamenative", ignoreCase = true)
        val vendorId = GPUInformation.getVendorID(null, null)
        val isAdreno = vendorId == 0x5143
        val isXclipse = vendorId == 0x144D
        val excludeBcnCompute = isAdreno || (isWrapperGamenative && isXclipse)
        val bcnEmulation = graphicsDriverConfig.get("bcnEmulation")
        val bcnEmulationType = graphicsDriverConfig.get("bcnEmulationType")
        when (bcnEmulation) {
            "auto" -> {
                if (bcnEmulationType.equals("compute") && !excludeBcnCompute) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "1");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "3");
            }
            "full" -> {
                if (bcnEmulationType.equals("compute") && !excludeBcnCompute) {
                    envVars.put("ENABLE_BCN_COMPUTE", "1");
                    envVars.put("BCN_COMPUTE_AUTO", "0");
                }
                envVars.put("WRAPPER_EMULATE_BCN", "2");
            }
            "none" -> envVars.put("WRAPPER_EMULATE_BCN", "0")
            else -> envVars.put("WRAPPER_EMULATE_BCN", "1")
        }

        val bcnEmulationCache = graphicsDriverConfig.get("bcnEmulationCache")
        envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache)

        val transcoder = graphicsDriverConfig.get("transcoder", "cpu")
        envVars.put("WRAPPER_BCN_GPU", if (transcoder.equals("gpu", ignoreCase = true)) "1" else "0")

        val wrapperQuality = graphicsDriverConfig.get("quality", "low")
        envVars.put("WRAPPER_ASTC_BLOCK", if (wrapperQuality.equals("high", ignoreCase = true)) "4x4" else "8x8")

        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1")
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig)
        }
    }
}

private fun buildVkBasaltConfig(
    effect: String,
    sharpnessLevel: Int,
    sharpnessDenoise: Int,
): String {
    val normalizedEffect = effect.trim().lowercase(Locale.getDefault())
    val normalizedSharpness = sharpnessLevel.coerceIn(0, 100) / 100.0
    val normalizedDenoise = sharpnessDenoise.coerceIn(0, 100) / 100.0
    return when (normalizedEffect) {
        "cas" -> "effects=cas;casSharpness=$normalizedSharpness;enableOnLaunch=True"
        "dls" -> "effects=dls;dlsSharpness=$normalizedSharpness;dlsDenoise=$normalizedDenoise;enableOnLaunch=True"
        else -> ""
    }
}

private fun extractSteamFiles(
    context: Context,
    container: Container,
    onExtractFileListener: OnExtractFileListener?,
) {
    val imageFs = ImageFs.find(context)
    val steamExe = File(
        imageFs.rootDir.absolutePath,
        ImageFs.WINEPREFIX + "/drive_c/Program Files (x86)/Steam/steam.exe",
    )

    if (container.isLaunchBionicSteam) {
        val steamDir = steamExe.parentFile ?: return
        steamDir.mkdirs()
        val staleSessionFiles = listOf(
            File(steamDir, "config/config.vdf"),
            File(steamDir, "config/loginusers.vdf"),
            File(steamDir, "local.vdf"),
            File(
                imageFs.rootDir,
                ImageFs.WINEPREFIX + "/drive_c/users/${ImageFs.USER}/AppData/Local/Steam/local.vdf",
            ),
            File(imageFs.rootDir, "/opt/apps/steam-token.exe"),
        )
        for (f in staleSessionFiles) {
            try {
                if (Files.deleteIfExists(f.toPath())) {
                    Timber.i("Deleted stale session file ${f.absolutePath} (bionic mode)")
                }
            } catch (e: IOException) {
                Timber.w(e, "Failed to delete ${f.absolutePath}")
            }
        }

        val steamclientDllsArchive = File(imageFs.getFilesDir(), "steamclient-dlls-20260619.tzst")
        if (steamclientDllsArchive.exists()) {
            Timber.i("Extracting steamclient-dlls.tzst (genuine Valve steamclient.dll for SteamStub)")
            TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                steamclientDllsArchive,
                imageFs.getRootDir(),
                onExtractFileListener,
            )
        } else {
            Timber.e("steamclient-dlls-20260619.tzst missing at ${steamclientDllsArchive.absolutePath}")
        }

        try {
            val steamExeSource = File(imageFs.getFilesDir(), BionicSteamAssetsDependency.steamExeAssetFor(container))
            if (!steamExeSource.exists()) {
                Timber.e("steam.exe cache missing at ${steamExeSource.absolutePath} (expected from BionicSteamAssetsDependency)")
            } else {
                steamExeSource.inputStream().use { input ->
                    Files.copy(input, steamExe.toPath(), REPLACE_EXISTING)
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Failed to copy cached steam.exe")
        }

        // Re-extract the active Proton's lsteamclient into its tree + prefix every boot,
        // so switching a container's Proton version can't leave a stale ABI-mismatched build.
        BionicSteamAssetsDependency.extractLsteamclientIntoPrefix(context, container)

        try {
            val accountId = SteamService.userSteamId?.accountID?.toInt() ?: 0
            val userRegFile = File(container.rootDir, ".wine/user.reg")
            val steamRoot = "C:\\Program Files (x86)\\Steam"
            val activeProcessKey = "Software\\Valve\\Steam\\ActiveProcess"
            WineRegistryEditor(userRegFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                editor.setDwordValue(activeProcessKey, "ActiveUser", accountId)
                editor.setStringValue(activeProcessKey, "SteamClientDll", "$steamRoot\\steamclient.dll")
                editor.setStringValue(activeProcessKey, "SteamClientDll64", "$steamRoot\\steamclient64.dll")
                editor.setStringValue(activeProcessKey, "Universe", "Public")
            }
            Timber.i("Set HKCU\\Software\\Valve\\Steam\\ActiveProcess registry values (bionic mode, ActiveUser=$accountId)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to write ActiveProcess registry values")
        }
        return
    }

    // Real-Steam mode: extract the full real-Steam tree once; subsequent boots
    val installedIsBionic = BionicSteamAssetsDependency.bionicSteamExeNames().any { name ->
        val cached = File(imageFs.getFilesDir(), name)
        cached.exists() && FileUtils.contentEquals(steamExe, cached)
    }
    if (steamExe.exists() && !installedIsBionic) return

    val downloaded = File(imageFs.getFilesDir(), "steam.tzst")
    Timber.i("Extracting steam.tzst")
    TarCompressorUtils.extract(
        TarCompressorUtils.Type.ZSTD,
        downloaded,
        imageFs.getRootDir(),
        onExtractFileListener,
    )
}

private fun cleanupBionicSteamAssets(imageFs: ImageFs) {
    val targets = listOf(
        File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/system32/lsteamclient.dll"),
        File(imageFs.rootDir, ImageFs.WINEPREFIX + "/drive_c/windows/syswow64/lsteamclient.dll"),
        File(imageFs.libDir, "libsteamclient.so"),
    )
    for (target in targets) {
        if (target.exists() && !target.delete()) {
            Timber.w("Failed to delete bionic-Steam asset at ${target.absolutePath}")
        }
    }
}

private fun readZipManifestNameFromAssets(context: Context, assetName: String): String? {
    return com.winlator.core.FileUtils.readZipManifestNameFromAssets(context, assetName)
}

private fun readLibraryNameFromExtractedDir(destinationDir: File): String? {
    return try {
        val manifests = destinationDir.listFiles { _, name -> name.endsWith(".json") }
        if (manifests != null && manifests.isNotEmpty()) {
            val manifest = manifests[0]
            val content = com.winlator.core.FileUtils.readString(manifest)
            val json = org.json.JSONObject(content)
            val libraryName = json.optString("libraryName", "").trim()
            if (libraryName.isNotEmpty()) libraryName else null
        } else null
    } catch (_: Exception) {
        null
    }
}
private fun changeWineAudioDriver(audioDriver: String, container: Container, imageFs: ImageFs) {
    if (audioDriver != container.getExtra("audioDriver")) {
        val rootDir = imageFs.rootDir
        val userRegFile = File(rootDir, ImageFs.WINEPREFIX + "/user.reg")
        WineRegistryEditor(userRegFile).use { registryEditor ->
            if (audioDriver == "alsa") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa")
            } else if (audioDriver == "pulseaudio") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse")
            } else if (audioDriver == "disabled") {
                registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "")
            }
        }
        container.putExtra("audioDriver", audioDriver)
        container.saveData()
    }
}
private fun setImagefsContainerVariant(context: Context, container: Container) {
    val imageFs = ImageFs.find(context)
    val containerVariant = container.containerVariant
    imageFs.createVariantFile(containerVariant)
}
