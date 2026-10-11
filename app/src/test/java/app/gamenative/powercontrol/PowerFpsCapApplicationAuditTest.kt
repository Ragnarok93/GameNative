package app.gamenative.powercontrol

import android.app.Application
import android.os.Looper
import app.gamenative.PluviaApp
import com.winlator.widget.XServerRendererView
import com.winlator.xserver.ShmFramePacer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** No application startup, renderer allocation, Vulkan calls, or real X server. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PowerFpsCapApplicationAuditTest {
    private lateinit var view: XServerRendererView
    private var previousView: XServerRendererView? = null
    private var previousApplier: ((Int) -> Boolean)? = null
    private var previousTarget = 0
    private val workers = mutableListOf<Thread>()

    @Before
    fun setUp() {
        previousView = PluviaApp.xServerView
        previousApplier = PowerManager.fpsCapApplier
        previousTarget = PowerManager.targetFps
        view = mock()
        PluviaApp.xServerView = view
        PowerManager.fpsCapApplier = null
        PowerManager.targetFps = 30
        ShmFramePacer.setFrameRateLimit(0)
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        workers.forEach { it.join(5_000) }
        PowerManager.fpsCapApplier = previousApplier
        PowerManager.targetFps = previousTarget
        PluviaApp.xServerView = previousView
        ShmFramePacer.setFrameRateLimit(0)
    }

    private data class Request(val worker: Thread, val result: AtomicReference<Boolean?>)

    private fun requestOnWorker(fps: Int): Request {
        val result = AtomicReference<Boolean?>()
        val started = CountDownLatch(1)
        val worker = Thread {
            started.countDown()
            result.set(PowerManager.applyFpsCapToEngines(fps))
        }
        workers += worker
        worker.start()
        assertTrue("worker starts", started.await(1, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (shadowOf(Looper.getMainLooper()).isIdle && worker.isAlive && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertFalse("request reached the paused main queue", shadowOf(Looper.getMainLooper()).isIdle)
        return Request(worker, result)
    }

    private fun finish(request: Request): Boolean? {
        request.worker.join(5_000)
        assertFalse("request has completed", request.worker.isAlive)
        return request.result.get()
    }

    @Test
    fun installedApplierRejectionIsAuthoritativeAndCannotFallBackToAnUnownedView() {
        var attempts = 0
        PowerManager.fpsCapApplier = { attempts++; false }
        assertFalse(PowerManager.applyFpsCapToEngines(60))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, attempts)
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }

    @Test
    fun backgroundTimeoutRejectsAndLateMainQueueDrainCannotApplyTheExpiredCap() {
        val request = requestOnWorker(60)
        assertEquals(false, finish(request))
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }

    @Test
    fun successfulBackgroundRequestAcknowledgesMainThreadApplicationBeforePublishingTarget() {
        val request = requestOnWorker(60)
        // Give a wrongly fire-and-forget caller time to return while main is
        // still paused. A correct caller remains blocked for acknowledgement.
        request.worker.join(50)
        assertNull("queued work has not been acknowledged", request.result.get())
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, finish(request))
        verify(view).setFrameRateLimit(60)
        assertEquals(60, PowerManager.targetFps)
    }

    @Test
    fun ownerReplacementRejectsQueuedCapInsteadOfMutatingEitherView() {
        val request = requestOnWorker(60)
        val replacement: XServerRendererView = mock()
        PluviaApp.xServerView = replacement
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, finish(request))
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
        verify(replacement, never()).setFrameRateLimit(any())
    }

    @Test
    fun newerExternalTargetEditInvalidatesAnOlderQueuedAdaptiveCap() {
        val request = requestOnWorker(60)
        PowerManager.targetFps = 90
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, finish(request))
        assertEquals(90, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }
    @Test
    fun targetEditReturningToItsOriginalValueStillInvalidatesQueuedCap() {
        val request = requestOnWorker(60)
        PowerManager.targetFps = 90
        PowerManager.targetFps = 30
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, finish(request))
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }
    @Test
    fun installedHandlerWaitsForMainAcknowledgementAndOwnsSourceCapTranslation() {
        var attempts = 0
        PowerManager.fpsCapApplier = { fps ->
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            attempts++
            PowerManager.targetFps = fps / 2
            true
        }
        val request = requestOnWorker(60)
        assertNull(request.result.get())
        assertEquals(0, attempts)
        assertEquals(30, PowerManager.targetFps)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, finish(request))
        assertEquals(1, attempts)
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }

    @Test
    fun installedHandlerTimeoutCannotRunOrFallBackAfterItsCallerMovesOn() {
        var attempts = 0
        PowerManager.fpsCapApplier = { attempts++; true }
        val request = requestOnWorker(60)
        assertEquals(false, finish(request))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, attempts)
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }

    @Test
    fun replacingInstalledHandlerRejectsAnOlderQueuedRequest() {
        var oldAttempts = 0
        var newAttempts = 0
        PowerManager.fpsCapApplier = { oldAttempts++; true }
        val request = requestOnWorker(60)
        PowerManager.fpsCapApplier = { newAttempts++; true }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, finish(request))
        assertEquals(0, oldAttempts)
        assertEquals(0, newAttempts)
        verify(view, never()).setFrameRateLimit(any())
    }

    @Test
    fun settingsInvalidationRejectsPendingCapWithoutChangingTheTarget() {
        val request = requestOnWorker(60)
        PowerManager.invalidatePendingFpsCap()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, finish(request))
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }

    @Test
    fun newerQueuedRequestSupersedesAnOlderRequest() {
        val old = requestOnWorker(60)
        val newer = requestOnWorker(90)
        // requestOnWorker observes a nonempty queue already, so await the
        // second worker's entry by its WAITING/TIMED_WAITING state.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (newer.worker.state != Thread.State.TIMED_WAITING &&
            newer.worker.isAlive && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.TIMED_WAITING, newer.worker.state)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, finish(old))
        assertEquals(true, finish(newer))
        verify(view, never()).setFrameRateLimit(60)
        verify(view).setFrameRateLimit(90)
        assertEquals(90, PowerManager.targetFps)
    }

    @Test
    fun interruptedWaitRejectsAndCancelsTheDeferredCommand() {
        val request = requestOnWorker(60)
        request.worker.interrupt()
        assertEquals(false, finish(request))
        assertTrue(request.worker.isInterrupted)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30, PowerManager.targetFps)
        verify(view, never()).setFrameRateLimit(any())
    }
}
