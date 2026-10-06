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
import com.winlator.winhandler.OnGetProcessInfoListener
import com.winlator.winhandler.ProcessInfo
import com.winlator.core.Win32AppWorkarounds
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.TouchMouse
import com.winlator.widget.FrameRating
import com.winlator.widget.XServerRendererView
import com.winlator.xserver.Keyboard
import com.winlator.xserver.Property
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

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

private const val EXIT_PROCESS_TIMEOUT_MS = 30_000L
private const val EXIT_PROCESS_RESPONSE_TIMEOUT_MS = 1_500L
private const val EXIT_PROCESS_POLL_INTERVAL_MS = 250L

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
    var windowModificationListener: WindowManager.OnWindowModificationListener? = null

    fun installWindowModificationListener(
        xServerView: XServerRendererView,
        onExitGame: () -> Unit,
    ): WindowManager.OnWindowModificationListener {
        windowModificationListener?.let {
            xServerView.getxServer().windowManager.removeOnWindowModificationListener(it)
        }

        val renderer = xServerView.renderer
        val wmListener = object : WindowManager.OnWindowModificationListener {
            private fun describeFrameRatingWindow(window: Window): String =
                "id=${window.id}, name=${window.name}, class=${window.className}, pid=${window.processId}"

            private fun findTopmostApplicationWindow(window: Window): Window? {
                val children = window.children
                for (i in children.indices.reversed()) {
                    val child = children[i]
                    if (!child.attributes.isMapped()) continue
                    val topmostInChild = findTopmostApplicationWindow(child)
                    if (topmostInChild != null) return topmostInChild
                    if (child.isApplicationWindow() && child.isRenderable()) return child
                }
                return null
            }

            private fun refreshFrameRatingTracking(reason: String) {
                val rating = frameRating ?: return
                val topmost = findTopmostApplicationWindow(xServerView.getxServer().windowManager.rootWindow)
                val nextId = topmost?.id ?: -1
                if (frameRatingWindowId == nextId) return

                if (topmost == null) {
                    if (frameRatingWindowId != -1) {
                        Timber.i("FrameRating tracking cleared (%s); no topmost application window remains", reason)
                    }
                    frameRatingWindowId = -1
                    (context as? Activity)?.runOnUiThread { rating.visibility = View.GONE }
                    return
                }

                frameRatingWindowId = nextId
                Timber.i(
                    "FrameRating tracking attached (%s) to topmost app window %s",
                    reason,
                    describeFrameRatingWindow(topmost),
                )
                (context as? Activity)?.runOnUiThread {
                    rating.resetSamplingEpoch()
                    rating.visibility = View.VISIBLE
                }
            }

            override fun onUpdateWindowContent(window: Window) {
                if (!xServerState.value.winStarted && window.isApplicationWindow()) {
                    if (shouldShowMouseCursor()) renderer?.setCursorVisible(true)
                    xServerState.value.winStarted = true
                }
                if (frameRatingWindowId == -1 && window.isApplicationWindow()) {
                    refreshFrameRatingTracking("content-update")
                }
                if (window.id == frameRatingWindowId) {
                    (context as? Activity)?.runOnUiThread { frameRating?.update() }
                }
            }

            override fun onModifyWindowProperty(window: Window, property: Property) {
                if (window.id == frameRatingWindowId || window.isApplicationWindow()) {
                    refreshFrameRatingTracking("property:${property.nameAsString}")
                }
            }

            override fun onMapWindow(window: Window) {
                Timber.i(
                    "onMapWindow:" +
                        "\n\twindowName: ${window.name}" +
                        "\n\twindowClassName: ${window.className}" +
                        "\n\tprocessId: ${window.processId}" +
                        "\n\thasParent: ${window.parent != null}" +
                        "\n\tchildrenSize: ${window.children.size}",
                )
                refreshFrameRatingTracking("map-window")
                win32AppWorkarounds?.applyWindowWorkarounds(window)
                onWindowMapped?.invoke(context, window)
            }

            override fun onUnmapWindow(window: Window) {
                Timber.i(
                    "onUnmapWindow:" +
                        "\n\twindowName: ${window.name}" +
                        "\n\twindowClassName: ${window.className}" +
                        "\n\tprocessId: ${window.processId}" +
                        "\n\thasParent: ${window.parent != null}" +
                        "\n\tchildrenSize: ${window.children.size}",
                )
                refreshFrameRatingTracking("unmap-window")
                startExitWatchForUnmappedGameWindow(window, onExitGame)
                onWindowUnmapped?.invoke(window)
            }

            override fun onChangeWindowZOrder(window: Window) {
                refreshFrameRatingTracking("z-order")
            }

            override fun onUpdateWindowGeometry(window: Window, resized: Boolean) {
                if (window.id == frameRatingWindowId || window.isApplicationWindow()) {
                    refreshFrameRatingTracking(if (resized) "geometry-resize" else "geometry-move")
                }
            }
        }

        xServerView.getxServer().windowManager.addOnWindowModificationListener(wmListener)
        windowModificationListener = wmListener
        return wmListener
    }

    fun removeWindowModificationListener(xServerView: XServerRendererView) {
        windowModificationListener?.let {
            xServerView.getxServer().windowManager.removeOnWindowModificationListener(it)
        }
        windowModificationListener = null
    }

    private fun shouldShowMouseCursor(): Boolean =
        !container.isDisableMouseInput &&
            (!container.isTouchscreenMode || currentGestureConfig.showCursorInTouchscreenMode)

    fun startExitWatchForUnmappedGameWindow(window: Window, onExitGame: () -> Unit) {
        val winHandler = xServerView?.getxServer()?.winHandler ?: return
        if (exitWatchJob?.isActive == true) return
        val targetExecutable = extractExecutableBasename(container.executablePath)
        if (!windowMatchesExecutable(window, targetExecutable)) return

        exitWatchJob = launchXServerIo {
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
                    if (processInfo != null) currentList.add(processInfo)
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
                    synchronized(lock) { pendingSnapshot = deferred }
                    winHandler.listProcesses()
                    val snapshot = withTimeoutOrNull(EXIT_PROCESS_RESPONSE_TIMEOUT_MS) { deferred.await() }
                    if (snapshot != null) {
                        val hasNonEssential = snapshot.any { !allowlist.contains(normalizeProcessName(it.name)) }
                        if (!hasNonEssential) {
                            withContext(Dispatchers.Main) { onExitGame() }
                            break
                        }
                    }
                    delay(EXIT_PROCESS_POLL_INTERVAL_MS)
                }
            } finally {
                winHandler.setOnGetProcessInfoListener(previousListener)
                synchronized(lock) { pendingSnapshot = null }
            }
        }
    }

    fun cleanup() {
        adaptiveCapGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        windowModificationListener?.let {
            xServerView?.getxServer()?.windowManager?.removeOnWindowModificationListener(it)
        }
        windowModificationListener = null
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
