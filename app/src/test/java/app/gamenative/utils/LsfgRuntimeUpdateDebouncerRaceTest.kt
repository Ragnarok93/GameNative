package app.gamenative.utils

import java.lang.reflect.Proxy
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgRuntimeUpdateDebouncerRaceTest {
    private class ControlledScheduler {
        val queued = mutableListOf<Runnable>()
        val executor = Proxy.newProxyInstance(
            ScheduledExecutorService::class.java.classLoader,
            arrayOf(ScheduledExecutorService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "schedule" -> {
                    queued += args!![0] as Runnable
                    Proxy.newProxyInstance(ScheduledFuture::class.java.classLoader,
                        arrayOf(ScheduledFuture::class.java)) { _, futureMethod, _ ->
                        when (futureMethod.name) {
                            "cancel", "isCancelled", "isDone" -> true
                            "getDelay" -> 0L
                            "compareTo" -> 0
                            else -> null
                        }
                    }
                }
                else -> error("Unexpected executor operation ${method.name}")
            }
        } as ScheduledExecutorService
    }

    @Test fun canceledRunnableAlreadyDequeuedCannotPublishOrClearNewHandle() {
        val scheduler = ControlledScheduler()
        val debouncer = LsfgRuntimeUpdateDebouncer(scheduler.executor, 120)
        val observed = mutableListOf<Int>()
        debouncer.submit { observed += 1 }
        debouncer.submit { observed += 2 }
        // Model cancel(false) losing the dequeue race: the old Runnable still runs.
        scheduler.queued[0].run()
        assertEquals(emptyList<Int>(), observed)
        scheduler.queued[1].run()
        assertEquals(listOf(2), observed)
    }

    @Test fun cancelInvalidatesDequeuedRunnable() {
        val scheduler = ControlledScheduler()
        val debouncer = LsfgRuntimeUpdateDebouncer(scheduler.executor, 120)
        var executions = 0
        debouncer.submit { executions++ }
        debouncer.cancel()
        scheduler.queued.single().run()
        assertEquals(0, executions)
    }

    @Test fun oldRunnableCannotMakeLaterCancelLoseNewestOperation() {
        val scheduler = ControlledScheduler()
        val debouncer = LsfgRuntimeUpdateDebouncer(scheduler.executor, 120)
        var executions = 0
        debouncer.submit { executions++ }
        debouncer.submit { executions++ }
        scheduler.queued[0].run()
        debouncer.cancel()
        scheduler.queued[1].run()
        assertEquals(0, executions)
    }
}
