package app.gamenative.framegen

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Presentation admission policy for Apex.
 *
 * A source interval owns its final display opportunity. Synthetic frames are
 * admitted only into opportunities that fit before that reserved source slot.
 * Fixed multipliers are ceilings; Adaptive chooses the minimum synthetic count
 * needed for the requested output cadence, bounded by observed Choreographer
 * capacity and by recent source-delivery health.
 */
class ApexSourceProtectedScheduler {
    private var lastOpportunityNanos = 0L
    private var displayPeriodNanos = 0.0
    private var lastSourceArrivalNanos = 0L
    private var sourcePeriodNanos = 0.0
    private var pendingSourceDeadlineNanos = 0L

    fun reset() {
        lastOpportunityNanos = 0L
        displayPeriodNanos = 0.0
        lastSourceArrivalNanos = 0L
        sourcePeriodNanos = 0.0
        pendingSourceDeadlineNanos = 0L
    }

    fun recordDisplayOpportunity(nowNanos: Long) {
        val previous = lastOpportunityNanos
        lastOpportunityNanos = nowNanos
        if (previous <= 0L) return
        val delta = nowNanos - previous
        if (delta !in MIN_DISPLAY_PERIOD_NS..MAX_DISPLAY_PERIOD_NS) return
        displayPeriodNanos = if (displayPeriodNanos <= 0.0) {
            delta.toDouble()
        } else {
            displayPeriodNanos * 0.80 + delta.toDouble() * 0.20
        }
    }

    fun recordSourceArrival(nowNanos: Long) {
        val previous = lastSourceArrivalNanos
        lastSourceArrivalNanos = nowNanos
        if (previous > 0L) {
            val delta = nowNanos - previous
            if (delta in MIN_SOURCE_PERIOD_NS..MAX_SOURCE_PERIOD_NS) {
                sourcePeriodNanos = if (sourcePeriodNanos <= 0.0) {
                    delta.toDouble()
                } else {
                    sourcePeriodNanos * 0.80 + delta.toDouble() * 0.20
                }
            }
        }
        if (sourcePeriodNanos > 0.0) {
            pendingSourceDeadlineNanos = nowNanos + sourcePeriodNanos.toLong()
        }
    }

    fun onSourcePresented() {
        pendingSourceDeadlineNanos = 0L
    }

    fun measuredCapacityFps(): Float =
        if (displayPeriodNanos > 0.0) (1_000_000_000.0 / displayPeriodNanos).toFloat() else 0f

    fun shouldPresentSourceNow(nowNanos: Long): Boolean {
        if (pendingSourceDeadlineNanos <= 0L || displayPeriodNanos <= 0.0) return false
        val reserve = max(MIN_RESERVE_NS.toDouble(), displayPeriodNanos * 0.15).toLong()
        return nowNanos + displayPeriodNanos.toLong() + reserve >= pendingSourceDeadlineNanos
    }

    fun generationBudget(
        adaptive: Boolean,
        fixedGeneratedCeiling: Int,
        targetFps: Int,
        presentation: ApexPresentationTelemetry.Snapshot,
    ): Int {
        val sourceFps = when {
            presentation.sourceInputFps > 1f -> presentation.sourceInputFps
            sourcePeriodNanos > 0.0 -> (1_000_000_000.0 / sourcePeriodNanos).toFloat()
            else -> 0f
        }
        val capacityFps = max(
            presentation.opportunityFps,
            measuredCapacityFps(),
        )

        if (sourceFps <= 1f || capacityFps <= 1f || displayPeriodNanos <= 0.0 || sourcePeriodNanos <= 0.0) {
            return 0
        }

        val reserve = max(MIN_RESERVE_NS.toDouble(), displayPeriodNanos * 0.15)
        val safeByDeadline = floor(
            max(0.0, sourcePeriodNanos - reserve) / displayPeriodNanos,
        ).toInt().coerceIn(0, 3)
        val safeByCapacity = (floor(capacityFps / sourceFps).toInt() - 1).coerceIn(0, 3)

        val requested = if (adaptive) {
            val boundedTarget = min(targetFps.coerceAtLeast(1).toFloat(), capacityFps * 0.98f)
            (ceil(boundedTarget / sourceFps).toInt() - 1).coerceIn(0, 3)
        } else {
            fixedGeneratedCeiling.coerceIn(0, 3)
        }

        var admitted = min(requested, min(safeByDeadline, safeByCapacity))

        // Presentation feedback closes the loop that native source/target ratio
        // alone cannot see. If synthetics are displacing real frames, shed them
        // immediately until source delivery recovers.
        if (presentation.sourceArrivals >= 4L && presentation.sourceInputFps > 1f) {
            val deliveryRatio = presentation.sourceFps / presentation.sourceInputFps
            if (deliveryRatio < 0.80f) {
                admitted = 0
            } else if (deliveryRatio < 0.95f) {
                admitted = (admitted - 1).coerceAtLeast(0)
            }
        }

        // Adaptive is target-seeking, not multiplier-seeking. Once measured OUT
        // is effectively at the bounded target, do not request extra synthetic
        // work merely because a higher multiplier is theoretically possible.
        if (adaptive && presentation.outputPresented >= 4L) {
            val boundedTarget = min(targetFps.coerceAtLeast(1).toFloat(), capacityFps * 0.98f)
            val deficit = boundedTarget - presentation.outputFps
            if (deficit <= max(0.5f, sourceFps * 0.10f)) {
                val observedGeneratedPerSource = if (sourceFps > 1f) {
                    ceil(presentation.generatedFps / sourceFps).toInt().coerceIn(0, 3)
                } else {
                    0
                }
                admitted = min(admitted, observedGeneratedPerSource)
            }
        }

        return admitted.coerceIn(0, 3)
    }

    companion object {
        private const val MIN_DISPLAY_PERIOD_NS = 2_000_000L
        private const val MAX_DISPLAY_PERIOD_NS = 50_000_000L
        private const val MIN_SOURCE_PERIOD_NS = 4_000_000L
        private const val MAX_SOURCE_PERIOD_NS = 250_000_000L
        private const val MIN_RESERVE_NS = 750_000L
    }
}
