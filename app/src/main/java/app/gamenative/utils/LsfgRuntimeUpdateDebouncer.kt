package app.gamenative.utils

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Coalesces UI-driven LSFG runtime configuration bursts into the newest update.
 *
 * WSI-affecting options (notably enable/multiplier/present-mode) can require a
 * Vulkan swapchain recreation. Rebuilding the swapchain for every button repeat
 * is both unnecessary and hostile to games that are sensitive to repeated
 * VK_ERROR_OUT_OF_DATE_KHR transitions, so the Quick Menu publishes one settled
 * runtime snapshot after the user stops adjusting settings.
 */
internal class LsfgRuntimeUpdateDebouncer(
    private val scheduler: ScheduledExecutorService,
    private val delayMs: Long,
) {
    private val lock = Any()
    private var pending: ScheduledFuture<*>? = null

    fun submit(task: () -> Unit) {
        synchronized(lock) {
            pending?.cancel(false)
            pending = scheduler.schedule(
                {
                    synchronized(lock) { pending = null }
                    task()
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun cancel() {
        synchronized(lock) {
            pending?.cancel(false)
            pending = null
        }
    }
}
