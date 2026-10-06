package app.gamenative.utils

import android.os.SystemClock
import com.winlator.container.Container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val LSFG_RUNTIME_HANDOFF_DELAY_MS = 1_200L
private const val LSFG_RUNTIME_HANDOFF_TIMEOUT_MS = 2_500L
private const val LSFG_RUNTIME_HANDOFF_POLL_MS = 100L

enum class LsfgRuntimeMode(val label: String) {
    OFF("Off"),
    TURNING_ON("Turning on"),
    GENERATING("Generating"),
    TURNING_OFF("Turning off"),
    SOURCE_ONLY_RESIDENT("Source only"),
    DEGRADED("Degraded"),
}

/**
 * Owns the asynchronous native-LSFG runtime handoff lifecycle.
 *
 * This deliberately lives outside XServerScreen's large Compose-generated method. The
 * composable supplies state/application callbacks, while this class owns the coroutine,
 * transition generation and runtime polling state.
 */
class LsfgRuntimeHandoffController(
    private val container: Container,
    private val isOverlayPaused: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transitionGeneration = 0L

    fun schedule(
        active: Boolean,
        multiplier: Int,
        backend: String = LsfgVkManager.backend(container),
        transition: LsfgVkManager.BackendTransitionRequest? = null,
        onStateChanged: (active: Boolean, multiplier: Int, mode: LsfgRuntimeMode) -> Unit,
        applyFpsLimiter: () -> Unit,
    ) {
        val generation = ++transitionGeneration
        onStateChanged(
            false,
            1,
            if (active) LsfgRuntimeMode.TURNING_ON else LsfgRuntimeMode.TURNING_OFF,
        )

        scope.launch {
            var activePollingElapsedMs = 0L
            var observed = false
            var suspensionLogged = false

            while (activePollingElapsedMs < LSFG_RUNTIME_HANDOFF_TIMEOUT_MS) {
                if (generation != transitionGeneration) return@launch

                // Quick Menu normally SIGSTOPs the guest. The native LSFG layer cannot consume
                // conf.toml or publish fresh stats while stopped, so wall-clock timeout here
                // would manufacture a DEGRADED state.
                if (isOverlayPaused()) {
                    if (!suspensionLogged) {
                        Timber.i(
                            "LSFG runtime handoff waiting for guest resume: generation=%d active=%b multiplier=%d",
                            generation,
                            active,
                            multiplier,
                        )
                        suspensionLogged = true
                    }
                    delay(LSFG_RUNTIME_HANDOFF_POLL_MS)
                    continue
                }

                if (suspensionLogged) {
                    Timber.i(
                        "LSFG runtime handoff polling resumed: generation=%d active=%b elapsed_active_ms=%d",
                        generation,
                        active,
                        activePollingElapsedMs,
                    )
                    suspensionLogged = false
                }

                val pollStartedAt = SystemClock.elapsedRealtime()
                val runtimeState = withContext(Dispatchers.IO) {
                    LsfgVkManager.readRuntimeState(container)
                }
                val backendMatches = LsfgVkManager.backend(container) == backend
                val presentationReady = transition?.let {
                    LsfgVkManager.isBackendTransitionPresentationReady(it)
                } ?: true
                observed = backendMatches && presentationReady && if (active) {
                    runtimeState.readyForGeneration
                } else {
                    runtimeState.readyForSourceOnly
                }
                activePollingElapsedMs +=
                    (SystemClock.elapsedRealtime() - pollStartedAt).coerceAtLeast(0L)

                if (observed) break

                delay(LSFG_RUNTIME_HANDOFF_POLL_MS)
                activePollingElapsedMs += LSFG_RUNTIME_HANDOFF_POLL_MS
            }

            val remainingSettleMs =
                LSFG_RUNTIME_HANDOFF_DELAY_MS - activePollingElapsedMs
            if (remainingSettleMs > 0L) delay(remainingSettleMs)
            if (generation != transitionGeneration) return@launch

            if (!observed) {
                onStateChanged(
                    false,
                    1,
                    LsfgRuntimeMode.DEGRADED,
                )
                Timber.w(
                    "LSFG runtime handoff timed out after %d active ms: generation=%d active=%b backend=%s multiplier=%d",
                    activePollingElapsedMs,
                    generation,
                    active,
                    backend,
                    multiplier,
                )
                transition?.let {
                    LsfgVkManager.completeBackendTransition(
                        it,
                        completionReason =
                            if (active) "$backend-activation-timeout"
                            else "$backend-source-only-timeout",
                        effectiveMultiplier = 1,
                    )
                }
                applyFpsLimiter()
                return@launch
            }

            val effectiveMultiplier = if (active) multiplier.coerceIn(2, 4) else 1
            onStateChanged(
                active,
                effectiveMultiplier,
                if (active) LsfgRuntimeMode.GENERATING else LsfgRuntimeMode.SOURCE_ONLY_RESIDENT,
            )
            transition?.let {
                LsfgVkManager.completeBackendTransition(
                    it,
                    completionReason =
                        if (active) "$backend-activation-ready"
                        else "$backend-source-only-ready",
                    effectiveMultiplier = effectiveMultiplier,
                )
            }
            applyFpsLimiter()
        }
    }

    fun cancel() {
        transitionGeneration++
        scope.cancel()
    }
}
