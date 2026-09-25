package app.gamenative.framegen

/**
 * Final-display telemetry for the Apex presenter.
 *
 * This deliberately lives outside FrameTimeRing. Power tuning needs source/game
 * cadence, while the HUD needs to report what the Apex EGL surface actually
 * swaps. A successful swap is counted only after eglSwapBuffers succeeds.
 */
object ApexPresentationTelemetry {
    const val OUTPUT_NONE = 0
    const val OUTPUT_SOURCE = 1
    const val OUTPUT_GENERATED = 2
    const val OUTPUT_REPEAT = 3

    private const val WINDOW_NS = 1_000_000_000L
    private const val CAPACITY = 512

    data class Snapshot(
        val active: Boolean,
        val sourceInputFps: Float,
        val sourceFps: Float,
        val generatedFps: Float,
        val repeatedFps: Float,
        val outputFps: Float,
        val opportunityFps: Float,
        val admittedGenerationBudget: Int,
        val attempts: Long,
        val sourceArrivals: Long,
        val sourceDropped: Long,
        val sourcePresented: Long,
        val generatedPresented: Long,
        val repeatedPresented: Long,
        val outputPresented: Long,
        val swapFailures: Long,
        val syntheticSlotsAbandoned: Long = 0,
        val sourceOnlyFrames: Long = 0,
    )

    data class SourceFrameStats(
        val fps: Float,
        val p50Ms: Float,
        val p95Ms: Float,
        val maxMs: Float,
        val slowFrameCount: Int,
        val totalFrameCount: Int,
    )

    private class TimestampRing {
        private val timestamps = LongArray(CAPACITY)
        private var writeIndex = 0L

        fun reset() {
            writeIndex = 0L
            java.util.Arrays.fill(timestamps, 0L)
        }

        fun record(nowNanos: Long) {
            timestamps[(writeIndex % CAPACITY).toInt()] = nowNanos
            writeIndex++
        }

        fun countSince(cutoffNanos: Long): Int {
            val end = writeIndex
            val start = (end - CAPACITY).coerceAtLeast(0L)
            var count = 0
            var index = start
            while (index < end) {
                if (timestamps[(index % CAPACITY).toInt()] >= cutoffNanos) count++
                index++
            }
            return count
        }

        fun intervalStatsSince(
            cutoffNanos: Long,
            slowThresholdNanos: Long,
        ): SourceFrameStats {
            val end = writeIndex
            val start = (end - CAPACITY).coerceAtLeast(0L)
            val deltas = LongArray(CAPACITY)
            var deltaCount = 0
            var frameCount = 0
            var first = 0L
            var last = 0L
            var previous = 0L
            var slow = 0
            var index = start
            while (index < end) {
                val timestamp = timestamps[(index % CAPACITY).toInt()]
                if (timestamp >= cutoffNanos && timestamp > 0L) {
                    if (first == 0L) first = timestamp
                    if (previous > 0L) {
                        val delta = timestamp - previous
                        if (delta > 0L && deltaCount < deltas.size) {
                            deltas[deltaCount++] = delta
                            if (slowThresholdNanos > 0L && delta > slowThresholdNanos) slow++
                        }
                    }
                    previous = timestamp
                    last = timestamp
                    frameCount++
                }
                index++
            }

            if (deltaCount == 0 || first == 0L || last <= first) {
                return SourceFrameStats(
                    fps = 0f,
                    p50Ms = 0f,
                    p95Ms = 0f,
                    maxMs = 0f,
                    slowFrameCount = 0,
                    totalFrameCount = frameCount,
                )
            }

            java.util.Arrays.sort(deltas, 0, deltaCount)
            fun percentileIndex(percentile: Double): Int =
                kotlin.math.ceil((deltaCount - 1) * percentile).toInt().coerceIn(0, deltaCount - 1)
            val p50 = deltas[percentileIndex(0.50)]
            val p95 = deltas[percentileIndex(0.95)]
            val max = deltas[deltaCount - 1]
            val elapsed = last - first
            val fps = if (elapsed > 0L) {
                ((frameCount - 1).coerceAtLeast(0) * 1_000_000_000.0 / elapsed.toDouble()).toFloat()
            } else {
                0f
            }

            return SourceFrameStats(
                fps = fps,
                p50Ms = p50 / 1_000_000f,
                p95Ms = p95 / 1_000_000f,
                maxMs = max / 1_000_000f,
                slowFrameCount = slow,
                totalFrameCount = frameCount,
            )
        }
    }

    private val lock = Any()
    private val sourceInputRing = TimestampRing()
    private val sourceRing = TimestampRing()
    private val generatedRing = TimestampRing()
    private val repeatedRing = TimestampRing()
    private val outputRing = TimestampRing()
    private val opportunityRing = TimestampRing()

    private var epochStartNanos = 0L
    private var active = false
    private var attempts = 0L
    private var sourceArrivals = 0L
    private var sourceDropped = 0L
    private var sourcePresented = 0L
    private var generatedPresented = 0L
    private var repeatedPresented = 0L
    private var outputPresented = 0L
    private var swapFailures = 0L
    private var syntheticSlotsAbandoned = 0L
    private var sourceOnlyFrames = 0L
    private var admittedGenerationBudget = 0

    private fun resetLocked(nowNanos: Long) {
        sourceInputRing.reset()
        sourceRing.reset()
        generatedRing.reset()
        repeatedRing.reset()
        outputRing.reset()
        opportunityRing.reset()
        epochStartNanos = nowNanos
        attempts = 0L
        sourceArrivals = 0L
        sourceDropped = 0L
        sourcePresented = 0L
        generatedPresented = 0L
        repeatedPresented = 0L
        outputPresented = 0L
        swapFailures = 0L
        syntheticSlotsAbandoned = 0L
        sourceOnlyFrames = 0L
        admittedGenerationBudget = 0
    }

    fun reset(nowNanos: Long = System.nanoTime()) = synchronized(lock) {
        active = false
        resetLocked(nowNanos)
    }

    fun beginSession(nowNanos: Long = System.nanoTime()) = synchronized(lock) {
        resetLocked(nowNanos)
        active = true
    }

    fun endSession(nowNanos: Long = System.nanoTime()) = synchronized(lock) {
        active = false
        resetLocked(nowNanos)
    }

    /**
     * Records a source frame only after the Vulkan compositor has identified a
     * new guest-content generation and the optional source cap has admitted it.
     * Cursor/transform redraws and cap-rejected source frames are not SRC FPS.
     */
    fun recordSourceArrival(nowNanos: Long = System.nanoTime()) = synchronized(lock) {
        if (!active) return@synchronized
        sourceArrivals++
        sourceInputRing.record(nowNanos)
    }

    fun recordSourceDropped() = synchronized(lock) {
        if (!active) return@synchronized
        sourceDropped++
    }

    /** One actual Choreographer callback observed by the Apex presenter. */
    fun recordDisplayOpportunity(nowNanos: Long = System.nanoTime()) = synchronized(lock) {
        if (!active) return@synchronized
        opportunityRing.record(nowNanos)
    }

    fun recordAdmission(generatedBudget: Int) = synchronized(lock) {
        if (!active) return@synchronized
        admittedGenerationBudget = generatedBudget.coerceIn(0, 3)
        if (admittedGenerationBudget == 0) sourceOnlyFrames++
    }

    fun recordSyntheticSlotsAbandoned(count: Int) = synchronized(lock) {
        if (!active || count <= 0) return@synchronized
        syntheticSlotsAbandoned += count.toLong()
    }

    fun record(outputKind: Int, swapSucceeded: Boolean, nowNanos: Long = System.nanoTime()) =
        synchronized(lock) {
            if (!active) return@synchronized
            if (epochStartNanos == 0L) epochStartNanos = nowNanos
            attempts++
            if (!swapSucceeded) {
                swapFailures++
                return@synchronized
            }

            outputPresented++
            outputRing.record(nowNanos)
            when (outputKind) {
                OUTPUT_SOURCE -> {
                    sourcePresented++
                    sourceRing.record(nowNanos)
                }
                OUTPUT_GENERATED -> {
                    generatedPresented++
                    generatedRing.record(nowNanos)
                }
                OUTPUT_REPEAT -> {
                    repeatedPresented++
                    repeatedRing.record(nowNanos)
                }
            }
        }

    fun sourceFrameStats(
        nowNanos: Long = System.nanoTime(),
        windowNanos: Long = 2_000_000_000L,
        slowThresholdNanos: Long = 0L,
    ): SourceFrameStats = synchronized(lock) {
        if (!active) {
            return@synchronized SourceFrameStats(0f, 0f, 0f, 0f, 0, 0)
        }
        sourceInputRing.intervalStatsSince(
            cutoffNanos = nowNanos - windowNanos.coerceAtLeast(1L),
            slowThresholdNanos = slowThresholdNanos,
        )
    }

    fun snapshot(nowNanos: Long = System.nanoTime()): Snapshot = synchronized(lock) {
        val start = if (epochStartNanos == 0L) nowNanos else epochStartNanos
        val activeWindowNs = (nowNanos - start).coerceIn(1_000_000L, WINDOW_NS)
        val cutoff = nowNanos - activeWindowNs
        val scale = 1_000_000_000.0 / activeWindowNs.toDouble()

        Snapshot(
            active = active,
            sourceInputFps = (sourceInputRing.countSince(cutoff) * scale).toFloat(),
            sourceFps = (sourceRing.countSince(cutoff) * scale).toFloat(),
            generatedFps = (generatedRing.countSince(cutoff) * scale).toFloat(),
            repeatedFps = (repeatedRing.countSince(cutoff) * scale).toFloat(),
            outputFps = (outputRing.countSince(cutoff) * scale).toFloat(),
            opportunityFps = (opportunityRing.countSince(cutoff) * scale).toFloat(),
            admittedGenerationBudget = admittedGenerationBudget,
            attempts = attempts,
            sourceArrivals = sourceArrivals,
            sourceDropped = sourceDropped,
            sourcePresented = sourcePresented,
            generatedPresented = generatedPresented,
            repeatedPresented = repeatedPresented,
            outputPresented = outputPresented,
            swapFailures = swapFailures,
            syntheticSlotsAbandoned = syntheticSlotsAbandoned,
            sourceOnlyFrames = sourceOnlyFrames,
        )
    }
}
