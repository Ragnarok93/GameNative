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

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val handle = nativeHandle
            if (handle == 0L) {
                failPresenter()
                return
            }

            val frame = renderer.pollApexFrame()
            if (frame != null) {
                val result = nativePresentSourceFrame(
                    handle,
                    frame.hardwareBufferPtr,
                    frame.acquireFenceFd,
                    frame.width,
                    frame.height,
                )
                val releaseFenceFd = result.toInt()
                val outputKind = ((result ushr 32) and 0xffL).toInt()
                val swapSucceeded = ((result ushr 40) and 0x1L) != 0L
                renderer.releaseApexFrame(frame, releaseFenceFd)
                ApexPresentationTelemetry.record(outputKind, swapSucceeded)
                hasSourceHistory = true
                maybeLogPresentationTelemetry()
            } else if (hasSourceHistory) {
                val result = nativePresentGeneratedFrame(handle)
                val outputKind = result and 0xff
                val swapSucceeded = (result and 0x100) != 0
                ApexPresentationTelemetry.record(outputKind, swapSucceeded)
                maybeLogPresentationTelemetry()
            }

            if (running) choreographer?.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        ApexPresentationTelemetry.reset()
        callbacksSinceTelemetryLog = 0
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
        renderer.onApexPresenterFailure()
    }

    private fun maybeLogPresentationTelemetry() {
        callbacksSinceTelemetryLog++
        if (callbacksSinceTelemetryLog < 120) return
        callbacksSinceTelemetryLog = 0
        val stats = ApexPresentationTelemetry.snapshot()
        android.util.Log.i(
            "ApexPresenter",
            "display cadence: source=%.1f generated=%.1f repeats=%.1f output=%.1f totals(source=%d generated=%d repeats=%d output=%d failures=%d)".format(
                java.util.Locale.US,
                stats.sourceFps,
                stats.generatedFps,
                stats.repeatedFps,
                stats.outputFps,
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
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
        handler = null
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
        ): Long

        @JvmStatic
        private external fun nativePresentGeneratedFrame(handle: Long): Int
    }
}
