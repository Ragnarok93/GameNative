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
    private var nextSourceDeadlineNanos = 0L
    private var pendingSourceFrame: VulkanRenderer.ApexFrame? = null
    private val scheduler = ApexSourceProtectedScheduler()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val handle = nativeHandle
            if (handle == 0L) {
                failPresenter()
                return
            }

            ApexPresentationTelemetry.recordDisplayOpportunity(frameTimeNanos)
            scheduler.recordDisplayOpportunity(frameTimeNanos)

            if (pendingSourceFrame == null) {
                val frame = renderer.pollApexFrame()
                if (frame != null) {
                    if (shouldAcceptSource(frameTimeNanos)) {
                        pendingSourceFrame = frame
                        ApexPresentationTelemetry.recordSourceArrival(frameTimeNanos)
                        scheduler.recordSourceArrival(frameTimeNanos)
                    } else {
                        // The source cap owns this decision. Return the producer
                        // fence unchanged because GLES never imported the AHB.
                        renderer.releaseApexFrame(frame, frame.acquireFenceFd)
                        ApexPresentationTelemetry.recordSourceDropped()
                    }
                }
            }

            if (nativeHasPendingSource(handle)) {
                // A real source frame is already buffered inside Apex. A newer
                // source arrival or the reserved deadline always wins over
                // additional synthetic work.
                if (pendingSourceFrame != null || scheduler.shouldPresentSourceNow(frameTimeNanos)) {
                    presentPendingSource(handle)
                } else {
                    presentGeneratedOpportunity(handle)
                }
            } else {
                val frame = pendingSourceFrame
                if (frame != null) {
                    pendingSourceFrame = null
                    val presentation = ApexPresentationTelemetry.snapshot(frameTimeNanos)
                    val adaptive = ApexNativeBridge.nativeIsAdaptiveFrameGeneration()
                    val requestedCeiling = if (adaptive) {
                        3
                    } else {
                        (ApexNativeBridge.nativeGetFixedMultiplier() - 1).coerceIn(0, 3)
                    }
                    val generationBudget = scheduler.generationBudget(
                        adaptive = adaptive,
                        fixedGeneratedCeiling = requestedCeiling,
                        targetFps = ApexNativeBridge.nativeGetTargetFPS(),
                        presentation = presentation,
                    )
                    ApexPresentationTelemetry.recordAdmission(generationBudget)
                    val result = nativePresentSourceFrame(
                        handle,
                        frame.hardwareBufferPtr,
                        frame.acquireFenceFd,
                        frame.width,
                        frame.height,
                        generationBudget,
                    )
                    val releaseFenceFd = result.toInt()
                    val outputKind = ((result ushr 32) and 0xffL).toInt()
                    val swapSucceeded = ((result ushr 40) and 0x1L) != 0L
                    renderer.releaseApexFrame(frame, releaseFenceFd)
                    recordPresentedOutput(outputKind, swapSucceeded)
                    hasSourceHistory = true
                    maybeLogPresentationTelemetry()
                }
            }

            if (running) choreographer?.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        ApexPresentationTelemetry.beginSession()
        callbacksSinceTelemetryLog = 0
        nextSourceDeadlineNanos = 0L
        pendingSourceFrame = null
        scheduler.reset()
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

    private fun recordPresentedOutput(outputKind: Int, swapSucceeded: Boolean) {
        if (outputKind != ApexPresentationTelemetry.OUTPUT_NONE) {
            ApexPresentationTelemetry.record(outputKind, swapSucceeded)
            if (swapSucceeded && outputKind == ApexPresentationTelemetry.OUTPUT_SOURCE) {
                scheduler.onSourcePresented()
            }
        }
    }

    private fun presentGeneratedOpportunity(handle: Long) {
        val result = nativePresentGeneratedFrame(handle)
        val outputKind = result and 0xff
        val swapSucceeded = (result and 0x100) != 0
        recordPresentedOutput(outputKind, swapSucceeded)
        maybeLogPresentationTelemetry()
    }

    private fun presentPendingSource(handle: Long) {
        val result = nativePresentPendingSourceFrame(handle)
        val outputKind = result and 0xff
        val swapSucceeded = (result and 0x100) != 0
        recordPresentedOutput(outputKind, swapSucceeded)
        maybeLogPresentationTelemetry()
    }

    private fun maybeLogPresentationTelemetry() {
        callbacksSinceTelemetryLog++
        if (callbacksSinceTelemetryLog < 120) return
        callbacksSinceTelemetryLog = 0
        val stats = ApexPresentationTelemetry.snapshot()
        val adaptive = ApexNativeBridge.nativeIsAdaptiveFrameGeneration()
        val targetFps = ApexNativeBridge.nativeGetTargetFPS()
        val fixedMultiplier = ApexNativeBridge.nativeGetFixedMultiplier()
        android.util.Log.i(
            "ApexPresenter",
            "display cadence: mode=%s target=%d fixed=%dx sourceIn=%.1f sourceOut=%.1f generated=%.1f repeats=%.1f output=%.1f opportunities=%.1f budget=%d totals(in=%d dropped=%d source=%d generated=%d repeats=%d output=%d failures=%d)".format(
                java.util.Locale.US,
                if (adaptive) "adaptive" else "fixed",
                targetFps,
                fixedMultiplier,
                stats.sourceInputFps,
                stats.sourceFps,
                stats.generatedFps,
                stats.repeatedFps,
                stats.outputFps,
                stats.opportunityFps,
                stats.admittedGenerationBudget,
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
                pendingSourceFrame?.let { pending ->
                    renderer.releaseApexFrame(pending, pending.acquireFenceFd)
                }
                pendingSourceFrame = null
                if (handle != 0L) nativeDestroyPresenter(handle)
                hasSourceHistory = false
                nextSourceDeadlineNanos = 0L
                scheduler.reset()
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

        @JvmStatic
        private external fun nativePresentPendingSourceFrame(handle: Long): Int

        @JvmStatic
        private external fun nativeHasPendingSource(handle: Long): Boolean
    }
}
