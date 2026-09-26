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
    private var pendingSourceTimestampNanos = 0L
    private var queuedSourcePreemptCount = 0L
    private var sourceDeadlinePreemptCount = 0L
    private var queuedSourceAbandonedSlots = 0L
    private var sourceDeadlineAbandonedSlots = 0L
    private val scheduler = ApexCadenceScheduler()

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
                    if (shouldAcceptSource(frame.sourceTimestampNanos)) {
                        pendingSourceFrame = frame
                        pendingSourceTimestampNanos = frame.sourceTimestampNanos
                        ApexPresentationTelemetry.recordSourceArrival(
                            frame.sourceTimestampNanos,
                        )
                    } else {
                        // The source cap owns this decision. Return the producer
                        // fence unchanged because GLES never imported the AHB.
                        renderer.releaseApexFrame(frame, frame.acquireFenceFd)
                        ApexPresentationTelemetry.recordSourceDropped()
                    }
                }
            }

            if (nativeHasPendingSource(handle)) {
                // Synthetic slots are opportunistic. A newly queued source may
                // terminate the remaining prefix when another generated slot
                // would endanger real-source latency; unused slots are dropped,
                // never carried as catch-up debt.
                val queuedSourcePreempt =
                    pendingSourceFrame != null &&
                        scheduler.shouldPreemptForQueuedSource(
                            nowNanos = frameTimeNanos,
                            queuedSourceTimestampNanos = pendingSourceTimestampNanos,
                        )
                val sourceDeadlinePreempt =
                    !queuedSourcePreempt &&
                        scheduler.shouldPresentSourceNow(frameTimeNanos)
                if (queuedSourcePreempt || sourceDeadlinePreempt) {
                    presentPendingSource(handle)
                    val abandoned =
                        ApexNativeBridge.nativeConsumeAbandonedSyntheticSlots()
                    ApexPresentationTelemetry.recordSyntheticSlotsAbandoned(abandoned)
                    if (queuedSourcePreempt) {
                        queuedSourcePreemptCount++
                        queuedSourceAbandonedSlots += abandoned.toLong()
                    } else {
                        sourceDeadlinePreemptCount++
                        sourceDeadlineAbandonedSlots += abandoned.toLong()
                    }
                } else {
                    presentGeneratedOpportunity(handle)
                }
            } else {
                val frame = pendingSourceFrame
                if (frame != null) {
                    val sourceTimestampNanos =
                        pendingSourceTimestampNanos.takeIf { it > 0L }
                            ?: frame.sourceTimestampNanos
                    pendingSourceFrame = null
                    pendingSourceTimestampNanos = 0L
                    // Source cadence is captured at the producer boundary.
                    // Choreographer remains only the display-opportunity clock.
                    scheduler.recordSourceFrame(sourceTimestampNanos)
                    val presentation = ApexPresentationTelemetry.snapshot(frameTimeNanos)
                    val adaptive = ApexNativeBridge.nativeIsAdaptiveFrameGeneration()
                    val requestedCeiling = if (adaptive) {
                        3
                    } else {
                        (ApexNativeBridge.nativeGetFixedMultiplier() - 1).coerceIn(0, 3)
                    }
                    scheduler.recordNativeCost(
                        preparationCostNanos =
                            ApexNativeBridge.nativeGetLastPreparationCostNanos(),
                        pipelineCostNanos =
                            ApexNativeBridge.nativeGetLastSyntheticCostNanos(),
                        generatedFrames =
                            ApexNativeBridge.nativeGetLastSyntheticCostBudget(),
                    )
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
                        sourceTimestampNanos,
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
        pendingSourceTimestampNanos = 0L
        queuedSourcePreemptCount = 0L
        sourceDeadlinePreemptCount = 0L
        queuedSourceAbandonedSlots = 0L
        sourceDeadlineAbandonedSlots = 0L
        scheduler.reset()
        running = true
        thread.start()
        val localHandler = Handler(thread.looper)
        handler = localHandler
        localHandler.post {
            if (!running) return@post
            val processingWidth =
                renderer.getApexTargetWidth().takeIf { it > 0 } ?: renderer.surfaceWidth
            val processingHeight =
                renderer.getApexTargetHeight().takeIf { it > 0 } ?: renderer.surfaceHeight
            val handle = nativeCreatePresenter(
                surface,
                processingWidth,
                processingHeight,
            )
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

    private fun shouldAcceptSource(sourceTimestampNanos: Long): Boolean {
        val sourceCap = renderer.fpsLimit
        if (sourceCap <= 0) {
            nextSourceDeadlineNanos = 0L
            return true
        }
        val periodNanos = 1_000_000_000L / sourceCap.coerceAtLeast(1)
        if (nextSourceDeadlineNanos == 0L) {
            nextSourceDeadlineNanos = sourceTimestampNanos + periodNanos
            return true
        }
        // Half-millisecond tolerance avoids alternating accept/drop decisions
        // from normal Choreographer timestamp jitter.
        if (sourceTimestampNanos + 500_000L < nextSourceDeadlineNanos) return false
        do {
            nextSourceDeadlineNanos += periodNanos
        } while (nextSourceDeadlineNanos <= sourceTimestampNanos)
        return true
    }

    private fun recordPresentedOutput(outputKind: Int, swapSucceeded: Boolean) {
        if (outputKind != ApexPresentationTelemetry.OUTPUT_NONE) {
            ApexPresentationTelemetry.record(outputKind, swapSucceeded)
            if (swapSucceeded) {
                when (outputKind) {
                    ApexPresentationTelemetry.OUTPUT_SOURCE ->
                        scheduler.onSourcePresented()
                    ApexPresentationTelemetry.OUTPUT_GENERATED ->
                        scheduler.onGeneratedPresented()
                }
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
        val schedulerDiagnostics = scheduler.diagnostics()
        val adaptive = ApexNativeBridge.nativeIsAdaptiveFrameGeneration()
        val targetFps = ApexNativeBridge.nativeGetTargetFPS()
        val fixedMultiplier = ApexNativeBridge.nativeGetFixedMultiplier()
        android.util.Log.i(
            "ApexPresenter",
            "display cadence: mode=%s target=%d fixed=%dx source_fps=%.1f source_ms=%.2f requested=%d admitted=%d opportunity_budget=%d cost_budget=%d wanted=%.3f phase=%.3f measuredOpportunities=%.1f sourceIn=%.1f generated=%.1f output=%.1f abandoned=%d no_generation=%d native_no_generation=%d remaining=%d queuedSourcePreempt=%d sourceDeadlinePreempt=%d abandonedQueued=%d abandonedDeadline=%d".format(
                java.util.Locale.US,
                if (adaptive) "adaptive" else "fixed",
                targetFps,
                fixedMultiplier,
                schedulerDiagnostics.sourceFps,
                schedulerDiagnostics.currentSourceIntervalMs,
                schedulerDiagnostics.requestedSyntheticCount,
                schedulerDiagnostics.admittedSyntheticCount,
                schedulerDiagnostics.presentationOpportunityBudget,
                schedulerDiagnostics.pipelineCostBudget,
                schedulerDiagnostics.wantedGeneratedFrames,
                schedulerDiagnostics.fractionalPhase,
                schedulerDiagnostics.measuredOpportunityFps,
                stats.sourceInputFps,
                stats.generatedFps,
                stats.outputFps,
                stats.syntheticSlotsAbandoned,
                stats.sourceOnlyFrames,
                ApexNativeBridge.nativeGetNoGenerationSourceFrameCount(),
                schedulerDiagnostics.remainingSyntheticSlots,
                queuedSourcePreemptCount,
                sourceDeadlinePreemptCount,
                queuedSourceAbandonedSlots,
                sourceDeadlineAbandonedSlots,
            ),
        )
    }

    private fun scheduleThreadShutdown(localHandler: Handler?) {
        if (!thread.isAlive) {
            handler = null
            return
        }
        if (localHandler == null) {
            thread.quitSafely()
            handler = null
            return
        }

        localHandler.postDelayed(
            {
                if (thread.isAlive) thread.quitSafely()
                handler = null
            },
            THREAD_DRAIN_DELAY_MS,
        )
    }

    fun stop() {
        if (!running && nativeHandle == 0L) {
            scheduleThreadShutdown(handler)
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
                pendingSourceTimestampNanos = 0L
                if (handle != 0L) nativeDestroyPresenter(handle)
                hasSourceHistory = false
                nextSourceDeadlineNanos = 0L
                scheduler.reset()
                scheduleThreadShutdown(localHandler)
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            android.util.Log.w(
                "ApexPresenter",
                "Timed out draining presenter state; scheduling display-thread shutdown",
            )
            scheduleThreadShutdown(localHandler)
        }
        ApexPresentationTelemetry.endSession()
    }

    override fun close() = stop()

    companion object {
        private const val THREAD_DRAIN_DELAY_MS = 50L

        init {
            System.loadLibrary("gamenative_apex")
        }

        @JvmStatic
        private external fun nativeCreatePresenter(
            surface: Surface,
            outputWidth: Int,
            outputHeight: Int,
        ): Long
        @JvmStatic private external fun nativeDestroyPresenter(handle: Long)

        @JvmStatic
        private external fun nativePresentSourceFrame(
            handle: Long,
            hardwareBufferPtr: Long,
            acquireFenceFd: Int,
            width: Int,
            height: Int,
            sourceTimestampNanos: Long,
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
