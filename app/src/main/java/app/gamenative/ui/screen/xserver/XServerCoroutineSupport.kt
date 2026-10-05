package app.gamenative.ui.screen.xserver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.gamenative.service.SteamService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal fun launchXServerIo(block: suspend () -> Unit): Job =
    CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.DEFAULT) { block() }

@Composable
internal fun rememberKeyboardEscMenuHandler(): KeyboardEscMenuHandler {
    val scope = rememberCoroutineScope()
    return remember(scope) { KeyboardEscMenuHandler(scope) }
}

@Composable
internal fun rememberKickPlayingSessionAction(): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        { scope.launch { SteamService.kickPlayingSession(onlyGame = true) } }
    }
}
