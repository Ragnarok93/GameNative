package app.gamenative.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.gamenative.PluviaApp
import app.gamenative.diagnostics.BenchmarkRuntimeHooks
import app.gamenative.ui.screen.xserver.XServerScreen
import app.gamenative.ui.theme.PluviaTheme

/** Non-exported. A live instrumentation-owned session is mandatory. */
class BenchmarkGameActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val consumed = PluviaApp.events.emit(app.gamenative.events.AndroidEvent.KeyEvent(event)) { results -> results.any { it } } == true
        return consumed || super.dispatchKeyEvent(event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = checkNotNull(intent.getStringExtra("lab_app_id"))
        check(BenchmarkRuntimeHooks.container(appId) != null)
        // The lab bypasses MainActivity, which normally initializes this
        // singleton before XServerScreen scans physical input devices.
        com.winlator.inputcontrols.ControllerManager.getInstance().init(applicationContext)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PluviaTheme {
                XServerScreen(appId = appId, bootToContainer = false, isOffline = true,
                    debugRun = false, registerBackAction = {}, navigateBack = { finish() },
                    onExit = { complete -> complete?.invoke(); finish() },
                    onGameLaunchError = { error -> BenchmarkGameState.failure = error })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PluviaApp.isActivityInForeground = true
    }
    override fun onPause() {
        PluviaApp.isActivityInForeground = false
        super.onPause()
    }
}

object BenchmarkGameState {
    @Volatile var failure: String? = null
}
