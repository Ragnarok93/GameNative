package app.gamenative.framegen

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import androidx.annotation.Keep
import com.winlator.renderer.VulkanRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Display-clock-driven GLES presenter for Vulkan-composited Apex source frames.
 *
 * The Vulkan renderer owns the AHardwareBuffer ring. This class only consumes a
 * ready slot, waits on its acquire fence in the GLES command stream, runs Apex,
 * exports a GLES release fence, and returns that fence to Vulkan before the slot
 * can be reused.
 */
@Keep
class ApexVulkanPresenter(
    private val renderer: VulkanRenderer,
    private val surface: Surface,
) : AutoCloseable {
    private val thread = HandlerThread("ApexVulkanPresenter", Process.THREAD_PRIORITY_DISPLAY)
    @Volatile private var running = false
    @Volatile private var nativeHandle = 0L
    private var handler: Handler? = null
    private var choreographer: Choreographer? = null
    private var hasSourceHistory = false
    private var callbacksSinceTelemetryLog = 0
    private var emptyCallbacksSinceSource = 0
    private var nextSourceDeadlineNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val handle = nativeHandle
            if (handle == 0L) {
                failPresenter()
                return
            }

            val frame = renderer.pollApexFrame()
            if (frame != null && shouldAcceptSource(frameTimeNanos)) {
                val generationOpportunities = emptyCallbacksSinceSource.coerceIn(0, 3)
                emptyCallbacksSinceSource = 0
                ApexPresentationTelemetry.recordSourceArrival(frameTimeNanos)
                val result = nativePresentSourceFrame(
                    handle,
                    frame.hardwareBufferPtr,
                    frame.acquireFenceFd,
                    frame.width,
                    frame.height,
                    generationOpportunities,
                )
                val releaseFenceFd = result.toInt()
                val outputKind = ((result ushr 32) and 0xffL).toInt()
                val swapSucceeded = ((result ushr 40) and 0x1L) != 0L
                renderer.releaseApexFrame(frame, releaseFenceFd)
                if (outputKind != ApexPresentationTelemetry.OUTPUT_NONE) {
                    ApexPresentationTelemetry.record(outputKind, swapSucceeded)
                }
                hasSourceHistory = true
                maybeLogPresentationTelemetry()
            } else {
                if (frame != null) {
                    // The user-selected source cap owns this frame. Returning the
                    // producer acquire fence as the release dependency safely
                    // discards it without importing/processing the AHB in GLES.
                    renderer.releaseApexFrame(frame, frame.acquireFenceFd)
                    ApexPresentationTelemetry.recordSourceDropped()
                }
                if (hasSourceHistory) {
                    emptyCallbacksSinceSource = (emptyCallbacksSinceSource + 1).coerceAtMost(3)
                    presentGeneratedOpportunity(handle)
                }
            }

            if (running) choreographer?.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        ApexPresentationTelemetry.beginSession()
        callbacksSinceTelemetryLog = 0
        emptyCallbacksSinceSource = 0
        nextSourceDeadlineNanos = 0L
        running = true
        thread.start()
        val localHandler = Handler(thread.looper)
        handler = localHandler
        localHandler.post {
            if (!running) return@post
            val handle = nativeCreatePresenter(surface)
            if (handle == 0L) {
                failPresenter()
                return@post
            }
            nativeHandle = handle
            choreographer = Choreographer.getInstance()
            choreographer?.postFrameCallback(frameCallback)
        }
    }

    private fun failPresenter() {
        running = false
        ApexPresentationTelemetry.endSession()
        renderer.onApexPresenterFailure()
    }

    private fun shouldAcceptSource(frameTimeNanos: Long): Boolean {
        val sourceCap = renderer.fpsLimit
        if (sourceCap <= 0) {
            nextSourceDeadlineNanos = 0L
            return true
        }
        val periodNanos = 1_000_000_000L / sourceCap.coerceAtLeast(1)
        if (nextSourceDeadlineNanos == 0L) {
            nextSourceDeadlineNanos = frameTimeNanos + periodNanos
            return true
        }
        // Half-millisecond tolerance avoids alternating accept/drop decisions
        // from normal Choreographer timestamp jitter.
        if (frameTimeNanos + 500_000L < nextSourceDeadlineNanos) return false
        do {
            nextSourceDeadlineNanos += periodNanos
        } while (nextSourceDeadlineNanos <= frameTimeNanos)
        return true
    }

    private fun presentGeneratedOpportunity(handle: Long) {
        val result = nativePresentGeneratedFrame(handle)
        val outputKind = result and 0xff
        val swapSucceeded = (result and 0x100) != 0
        if (outputKind != ApexPresentationTelemetry.OUTPUT_NONE) {
            ApexPresentationTelemetry.record(outputKind, swapSucceeded)
        }
        maybeLogPresentationTelemetry()
    }

    private fun maybeLogPresentationTelemetry() {
        callbacksSinceTelemetryLog++
        if (callbacksSinceTelemetryLog < 120) return
        callbacksSinceTelemetryLog = 0
        val stats = ApexPresentationTelemetry.snapshot()
        android.util.Log.i(
            "ApexPresenter",
            "display cadence: sourceIn=%.1f sourceOut=%.1f generated=%.1f repeats=%.1f output=%.1f totals(in=%d dropped=%d source=%d generated=%d repeats=%d output=%d failures=%d)".format(
                java.util.Locale.US,
                stats.sourceInputFps,
                stats.sourceFps,
                stats.generatedFps,
                stats.repeatedFps,
                stats.outputFps,
                stats.sourceArrivals,
                stats.sourceDropped,
                stats.sourcePresented,
                stats.generatedPresented,
                stats.repeatedPresented,
                stats.outputPresented,
                stats.swapFailures,
            ),
        )
    }

    fun stop() {
        if (!running && nativeHandle == 0L) {
            if (thread.isAlive) thread.quitSafely()
            return
        }
        running = false
        val latch = CountDownLatch(1)
        val localHandler = handler
        if (localHandler == null) {
            latch.countDown()
        } else {
            localHandler.post {
                choreographer?.removeFrameCallback(frameCallback)
                choreographer = null
                val handle = nativeHandle
                nativeHandle = 0L
                if (handle != 0L) nativeDestroyPresenter(handle)
                hasSourceHistory = false
                emptyCallbacksSinceSource = 0
                nextSourceDeadlineNanos = 0L
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
        handler = null
        ApexPresentationTelemetry.endSession()
    }

    override fun close() = stop()

    companion object {
        init {
            System.loadLibrary("gamenative_apex")
        }

        @JvmStatic private external fun nativeCreatePresenter(surface: Surface): Long
        @JvmStatic private external fun nativeDestroyPresenter(handle: Long)

        @JvmStatic
        private external fun nativePresentSourceFrame(
            handle: Long,
            hardwareBufferPtr: Long,
            acquireFenceFd: Int,
            width: Int,
            height: Int,
            generationOpportunities: Int,
        ): Long

        @JvmStatic
        private external fun nativePresentGeneratedFrame(handle: Long): Int
    }
}
