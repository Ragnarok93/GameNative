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
    private var adaptiveExtraBudget = 0
    private var adaptiveDeficitStreak = 0
    private var adaptiveSatisfiedStreak = 0

    fun reset() {
        lastOpportunityNanos = 0L
        displayPeriodNanos = 0.0
        lastSourceArrivalNanos = 0L
        sourcePeriodNanos = 0.0
        pendingSourceDeadlineNanos = 0L
        adaptiveExtraBudget = 0
        adaptiveDeficitStreak = 0
        adaptiveSatisfiedStreak = 0
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

        val baseRequested = if (adaptive) {
            val boundedTarget = min(targetFps.coerceAtLeast(1).toFloat(), capacityFps * 0.98f)
            (ceil(boundedTarget / sourceFps).toInt() - 1).coerceIn(0, 3)
        } else {
            fixedGeneratedCeiling.coerceIn(0, 3)
        }

        // One source may legitimately be buffered behind synthetics. Account for
        // that single in-flight real frame when judging source-delivery health so
        // normal interpolation latency is not misclassified as source loss.
        val countDeliveryRatio = if (presentation.sourceArrivals > 0L) {
            (min(
                presentation.sourceArrivals,
                presentation.sourcePresented + 1L,
            ).toFloat() / presentation.sourceArrivals.toFloat())
        } else {
            1f
        }
        val cadenceDeliveryRatio = if (presentation.sourceInputFps > 1f) {
            presentation.sourceFps / presentation.sourceInputFps
        } else {
            1f
        }
        val deliveryRatio = max(countDeliveryRatio, cadenceDeliveryRatio)

        var requested = baseRequested
        if (adaptive) {
            if (presentation.outputPresented >= 4L) {
                val boundedTarget =
                    min(targetFps.coerceAtLeast(1).toFloat(), capacityFps * 0.98f)
                val tolerance = max(0.75f, sourceFps * 0.08f)
                val deficit = boundedTarget - presentation.outputFps

                when {
                    deliveryRatio < 0.95f -> {
                        adaptiveExtraBudget = 0
                        adaptiveDeficitStreak = 0
                        adaptiveSatisfiedStreak = 0
                    }
                    deficit > tolerance &&
                        baseRequested + adaptiveExtraBudget < 3 -> {
                        adaptiveDeficitStreak++
                        adaptiveSatisfiedStreak = 0
                        if (adaptiveDeficitStreak >= 3) {
                            adaptiveExtraBudget =
                                (adaptiveExtraBudget + 1).coerceAtMost(3 - baseRequested)
                            adaptiveDeficitStreak = 0
                        }
                    }
                    deficit <= tolerance -> {
                        adaptiveSatisfiedStreak++
                        adaptiveDeficitStreak = 0
                        if (adaptiveSatisfiedStreak >= 2 && adaptiveExtraBudget > 0) {
                            adaptiveExtraBudget--
                            adaptiveSatisfiedStreak = 0
                        }
                    }
                    else -> {
                        adaptiveDeficitStreak = 0
                        adaptiveSatisfiedStreak = 0
                    }
                }
            }
            requested = (baseRequested + adaptiveExtraBudget).coerceIn(0, 3)
        } else {
            adaptiveExtraBudget = 0
            adaptiveDeficitStreak = 0
            adaptiveSatisfiedStreak = 0
        }

        var admitted = min(requested, min(safeByDeadline, safeByCapacity))

        // Synthetic work is always subordinate to source delivery. Fixed mode
        // and Adaptive share this invariant.
        if (presentation.sourceArrivals >= 4L) {
            if (deliveryRatio < 0.80f) {
                admitted = 0
                adaptiveExtraBudget = 0
            } else if (deliveryRatio < 0.95f) {
                admitted = (admitted - 1).coerceAtLeast(0)
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
