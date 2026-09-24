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
        val attempts: Long,
        val sourceArrivals: Long,
        val sourcePresented: Long,
        val generatedPresented: Long,
        val repeatedPresented: Long,
        val outputPresented: Long,
        val swapFailures: Long,
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
    }

    private val lock = Any()
    private val sourceInputRing = TimestampRing()
    private val sourceRing = TimestampRing()
    private val generatedRing = TimestampRing()
    private val repeatedRing = TimestampRing()
    private val outputRing = TimestampRing()

    private var epochStartNanos = 0L
    private var active = false
    private var attempts = 0L
    private var sourceArrivals = 0L
    private var sourcePresented = 0L
    private var generatedPresented = 0L
    private var repeatedPresented = 0L
    private var outputPresented = 0L
    private var swapFailures = 0L

    private fun resetLocked(nowNanos: Long) {
        sourceInputRing.reset()
        sourceRing.reset()
        generatedRing.reset()
        repeatedRing.reset()
        outputRing.reset()
        epochStartNanos = nowNanos
        attempts = 0L
        sourceArrivals = 0L
        sourcePresented = 0L
        generatedPresented = 0L
        repeatedPresented = 0L
        outputPresented = 0L
        swapFailures = 0L
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

    fun recordSourceArrival(nowNanos: Long = System.nanoTime()) = synchronized(lock) {
        if (!active) return@synchronized
        sourceArrivals++
        sourceInputRing.record(nowNanos)
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
            attempts = attempts,
            sourceArrivals = sourceArrivals,
            sourcePresented = sourcePresented,
            generatedPresented = generatedPresented,
            repeatedPresented = repeatedPresented,
            outputPresented = outputPresented,
            swapFailures = swapFailures,
        )
    }
}
