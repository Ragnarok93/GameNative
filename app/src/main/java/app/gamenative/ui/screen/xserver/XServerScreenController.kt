package app.gamenative.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.ShooterModeConfig
import app.gamenative.data.TouchGestureConfig
import app.gamenative.externaldisplay.IMEInputReceiver
import app.gamenative.externaldisplay.SwapInputOverlayView
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.ui.data.XServerState
import app.gamenative.ui.widget.PerformanceHudView
import app.gamenative.utils.LsfgQuickMenuHelper
import app.gamenative.utils.LsfgRuntimeHandoffController
import app.gamenative.utils.LsfgRuntimeMode
import com.winlator.container.Container
import com.winlator.winhandler.ProcessInfo
import com.winlator.core.Win32AppWorkarounds
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.TouchMouse
import com.winlator.widget.FrameRating
import com.winlator.widget.XServerRendererView
import com.winlator.xserver.Keyboard
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

@Stable
internal class XServerScreenPersistentState(
    val xServerState: MutableState<XServerState>,
    val fpsLimiterEnabled: MutableState<Boolean>,
    val fpsLimiterTarget: MutableState<Int>,
    val lsfgMultiplier: MutableState<Int>,
    val lsfgFlowScale: MutableState<Float>,
    val lsfgPerformanceMode: MutableState<Boolean>,
    val lsfgBackend: MutableState<String>,
    val lsfgRuntimeMode: MutableState<LsfgRuntimeMode>,
    val isLsfgGenerationActive: MutableState<Boolean>,
    val lsfgRuntimeMultiplier: MutableState<Int>,
    val runtimeConfigRevision: MutableState<Int>,
    val showPlayingBlockedDialog: MutableState<Boolean>,
    val playingBlockedRemoteName: MutableState<String?>,
)

internal class XServerScreenController(
    val context: Context,
    val view: View,
    val appId: String,
    val bootToContainer: Boolean,
    val testGraphics: Boolean,
    val diagnostics: Boolean,
    val debugRun: Boolean,
    val isOffline: Boolean,
    val lifecycleOwner: LifecycleOwner,
    val registerBackAction: ((() -> Unit) -> Unit),
    val navigateBack: () -> Unit,
    val onExit: (onComplete: (() -> Unit)?) -> Unit,
    val onWindowMapped: ((Context, Window) -> Unit)?,
    val onWindowUnmapped: ((Window) -> Unit)?,
    val onGameLaunchError: ((String) -> Unit)?,
    val container: Container,
    val activity: Activity?,
    val persistentState: XServerScreenPersistentState,
) {
    val xServerState: MutableState<XServerState> get() = persistentState.xServerState
    val adaptiveCapGeneration = AtomicLong(0L)
    val mainHandler = Handler(Looper.getMainLooper())
    val suspendPolicy: String = container.suspendPolicy
    val neverSuspend = suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)
    val manualResumeMode = suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

    var physicalControllerHandler: PhysicalControllerHandler? by mutableStateOf(null)
    var touchMouse by mutableStateOf<TouchMouse?>(null)
    var keyboard by mutableStateOf<Keyboard?>(null)
    var xServerView: XServerRendererView? by mutableStateOf(null)
    var swapInputOverlay: SwapInputOverlayView? by mutableStateOf(null)
    var imeInputReceiver: IMEInputReceiver? by mutableStateOf(null)
    var win32AppWorkarounds: Win32AppWorkarounds? by mutableStateOf(null)
    var physicalControllerHandler: PhysicalControllerHandler? by mutableStateOf(null)
    var exitWatchJob: Job? by mutableStateOf(null)
    var areControlsVisible by mutableStateOf(false)
    var isDisableMouseInput by mutableStateOf(container.isDisableMouseInput)
    var isEditMode by mutableStateOf(false)
    var gameRoot by mutableStateOf<View?>(null)
    var windowModificationListener by mutableStateOf<WindowManager.OnWindowModificationListener?>(null)
    var elementPositionsSnapshot by mutableStateOf<Map<ControlElement, Pair<Int, Int>>>(emptyMap())
    var showElementEditor by mutableStateOf(false)
    var elementToEdit by mutableStateOf<ControlElement?>(null)
    var showPhysicalControllerDialog by mutableStateOf(false)
    var showTouchGestureDialog by mutableStateOf(false)
    var showShooterModeDialog by mutableStateOf(false)
    var isTouchscreenModeActive by mutableStateOf(container.isTouchscreenMode)
    var isShooterModeActive by mutableStateOf(container.isShooterMode)
    var currentGestureConfig by mutableStateOf(TouchGestureConfig.fromJson(container.getGestureConfig()))
    var currentShooterConfig by mutableStateOf(ShooterModeConfig.fromJson(container.getShooterConfig()))
    var debugGestureName by mutableStateOf("")
    var debugGestureKey by mutableIntStateOf(0)
    var keyboardRequestedFromOverlay by mutableStateOf(false)
    var shouldForceResumeOnMenuClose by mutableStateOf(false)
    var showQuickMenu by mutableStateOf(false)
    var quickMenuToolsVisible by mutableStateOf(false)
    var quickMenuWineProcesses by mutableStateOf<List<ProcessInfo>>(emptyList())
    var quickMenuWineProcessesLoading by mutableStateOf(false)
    var hasPhysicalController by mutableStateOf(false)
    var controllerSlotStatusVersion by mutableIntStateOf(0)
    var keepPausedForEditor by mutableStateOf(false)
    var hasPhysicalKeyboard by mutableStateOf(false)
    var hasPhysicalMouse by mutableStateOf(false)
    var usingScreenMirror by mutableStateOf(false)
    var hasInternalTouchpad by mutableStateOf(false)
    var hasUpdatedScreenGamepad by mutableStateOf(false)
    var isPerformanceHudEnabled by mutableStateOf(PrefManager.showFps)
    var detectedMaxRefreshRateHz by mutableIntStateOf(60)
    var lastLsfgPacingActive by mutableStateOf(false)
    var lastLoggedOutputBudget by mutableStateOf<String?>(null)
    val clickHighlightPoints = mutableStateListOf<app.gamenative.ui.component.HighlightPoint>()
    val shouldTrackDisplayedFrames = AtomicBoolean(false)
    var performanceHudConfig by mutableStateOf(loadPerformanceHudConfigForScreen())
    var performanceHudView by mutableStateOf<PerformanceHudView?>(null)
    var performanceHudHost by mutableStateOf<FrameLayout?>(null)
    var isDraggingPerformanceHud by mutableStateOf(false)
    var isTrackingPerformanceHudTouch by mutableStateOf(false)
    var performanceHudTouchDownRawX by mutableStateOf(0f)
    var performanceHudTouchDownRawY by mutableStateOf(0f)
    var performanceHudDragOffsetX by mutableStateOf(0f)
    var performanceHudDragOffsetY by mutableStateOf(0f)
    var firstTimeBoot = false
    var needsUnpacking = false
    var containerVariantChanged = false
    var frameRating: FrameRating? by mutableStateOf(null)
    var frameRatingWindowId = -1
    var vkbasaltConfig = ""
    var taskAffinityMask = 0
    var taskAffinityMaskWoW64 = 0
    var isKeyboardVisible = false
    var fpsLimiterEnabled: Boolean by persistentState.fpsLimiterEnabled
    var fpsLimiterTarget: Int by persistentState.fpsLimiterTarget
    var lsfgMultiplier: Int by persistentState.lsfgMultiplier
    var lsfgFlowScale: Float by persistentState.lsfgFlowScale
    var lsfgPerformanceMode: Boolean by persistentState.lsfgPerformanceMode
    var lsfgBackend: String by persistentState.lsfgBackend
    var lsfgRuntimeMode: LsfgRuntimeMode by persistentState.lsfgRuntimeMode
    var isLsfgGenerationActive: Boolean by persistentState.isLsfgGenerationActive
    var lsfgRuntimeMultiplier: Int by persistentState.lsfgRuntimeMultiplier
    var runtimeConfigRevision: Int by persistentState.runtimeConfigRevision
    var showPlayingBlockedDialog: Boolean by persistentState.showPlayingBlockedDialog
    var playingBlockedRemoteName: String? by persistentState.playingBlockedRemoteName
    val performanceHudTouchSlop: Float = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    val isLsfgAvailable: Boolean = LsfgQuickMenuHelper.isAvailable(container)
    val initialLsfgSettings = LsfgQuickMenuHelper.readSettings(container)
    val isLsfgRequested: Boolean get() = isLsfgAvailable && lsfgMultiplier >= 2
    val lsfgRuntimeHandoffController = LsfgRuntimeHandoffController(container) { PluviaApp.isOverlayPaused }

    fun cleanup() {
        adaptiveCapGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        PluviaApp.radialMenuCoordinator?.detach()
        PluviaApp.radialMenuCoordinator = null
        physicalControllerHandler?.cleanup()
        physicalControllerHandler = null
        exitWatchJob?.cancel()
        exitWatchJob = null
        lsfgRuntimeHandoffController.cancel()
    }
}

private fun loadPerformanceHudConfigForScreen(): PerformanceHudConfig = PerformanceHudConfig(
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
