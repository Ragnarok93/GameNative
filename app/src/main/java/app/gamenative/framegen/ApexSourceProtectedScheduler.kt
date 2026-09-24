package app.gamenative.framegen

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Source-owned cadence planner for Apex.
 *
 * This mirrors the scheduling semantics used by lsfg-vk-android:
 * - Fixed mode requests the configured multiplier directly.
 * - Adaptive treats the requested output FPS as authoritative demand.
 * - Fractional demand is distributed over source intervals with a phase
 *   accumulator instead of rounding every interval up to an integer ratio.
 * - Source slowdown increases target-relative interpolation demand; source FPS
 *   is never used as a generation backoff signal.
 * - Display callbacks are telemetry/opportunities, not a target governor.
 *
 * The protected source deadline remains independent from generated work.
 * Generated presentation never advances or re-phases the source timeline.
 */
class ApexSourceProtectedScheduler {
    data class Diagnostics(
        val protectedSourceFps: Float,
        val sourceHealthRatio: Float,
        val sourceProtectionActive: Boolean,
        val recoveryStreak: Int,
        val wantedGeneratedFrames: Float = 0f,
        val fractionalPhase: Float = 0f,
        val measuredOpportunityFps: Float = 0f,
    )

    private var lastOpportunityNanos = 0L
    private var displayPeriodNanos = 0.0

    private var lastSourceArrivalNanos = 0L
    private var lastObservedSourceIntervalNanos = 0L
    private var sourcePeriodNanos = 0.0
    private var lastTrustedSourcePeriodNanos = 0.0
    private var pendingSourceDeadlineNanos = 0L
    private var plannedOutputPeriodNanos = 0.0

    private val recentSourcePeriodsNanos = DoubleArray(SOURCE_CADENCE_WINDOW)
    private val sourcePeriodScratch = DoubleArray(SOURCE_CADENCE_WINDOW)
    private var recentSourcePeriodCount = 0
    private var recentSourcePeriodCursor = 0

    private var adaptiveTargetFps = 0
    private var adaptiveMaxGeneratedFrames = 0
    private var adaptiveFractionalPhase = 0.0
    private var adaptiveObservedSeconds = 0.0
    private var adaptiveCostLimit = 0
    private var adaptiveUnmetDemandSinceSeconds = -1.0
    private var adaptiveWantedGeneratedFrames = 0.0
    private var runtimeCadenceEstablished = false

    private var lastAdmittedBudget = 0

    fun reset() {
        lastOpportunityNanos = 0L
        displayPeriodNanos = 0.0

        lastSourceArrivalNanos = 0L
        lastObservedSourceIntervalNanos = 0L
        sourcePeriodNanos = 0.0
        lastTrustedSourcePeriodNanos = 0.0
        pendingSourceDeadlineNanos = 0L
        plannedOutputPeriodNanos = 0.0
        resetSourceCadenceWindow()

        adaptiveTargetFps = 0
        adaptiveMaxGeneratedFrames = 0
        adaptiveFractionalPhase = 0.0
        adaptiveObservedSeconds = 0.0
        adaptiveCostLimit = 0
        adaptiveUnmetDemandSinceSeconds = -1.0
        adaptiveWantedGeneratedFrames = 0.0
        runtimeCadenceEstablished = false

        lastAdmittedBudget = 0
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

        if (previous <= 0L) {
            if (sourcePeriodNanos > 0.0) {
                pendingSourceDeadlineNanos = nowNanos + sourcePeriodNanos.toLong()
            }
            return
        }

        val delta = nowNanos - previous
        if (delta <= 0L) return

        if (
            lastTrustedSourcePeriodNanos > 0.0 &&
            delta.toDouble() > lastTrustedSourcePeriodNanos * SOURCE_DISCONTINUITY_RATIO
        ) {
            // Suspend/menu/loading boundaries are not synthetic catch-up debt.
            // Keep lifecycle knowledge so an explicit target change can still
            // warm-start, but clear the current cadence and fractional epoch.
            resetSourceCadenceWindow()
            lastObservedSourceIntervalNanos = 0L
            sourcePeriodNanos = 0.0
            lastTrustedSourcePeriodNanos = 0.0
            pendingSourceDeadlineNanos = 0L
            plannedOutputPeriodNanos = 0.0
            adaptiveFractionalPhase = 0.0
            adaptiveObservedSeconds = 0.0
            adaptiveUnmetDemandSinceSeconds = -1.0
            adaptiveCostLimit =
                if (adaptiveMaxGeneratedFrames > 0) 1 else 0
            adaptiveWantedGeneratedFrames = 0.0
            lastAdmittedBudget = 0
            return
        }

        if (delta !in MIN_SOURCE_PERIOD_NS..MAX_SOURCE_PERIOD_NS) {
            if (sourcePeriodNanos > 0.0) {
                pendingSourceDeadlineNanos = nowNanos + sourcePeriodNanos.toLong()
            }
            return
        }

        lastObservedSourceIntervalNanos = delta
        recentSourcePeriodsNanos[recentSourcePeriodCursor] = delta.toDouble()
        recentSourcePeriodCursor =
            (recentSourcePeriodCursor + 1) % SOURCE_CADENCE_WINDOW
        recentSourcePeriodCount =
            min(recentSourcePeriodCount + 1, SOURCE_CADENCE_WINDOW)

        val robustPeriodNanos = robustSourcePeriodNanos()
        if (robustPeriodNanos > 0.0) {
            if (sourcePeriodNanos <= 0.0) {
                sourcePeriodNanos = robustPeriodNanos
            } else {
                val previousPeriod = sourcePeriodNanos
                val boundedTarget = robustPeriodNanos.coerceIn(
                    previousPeriod * SOURCE_PERIOD_MIN_RATIO,
                    previousPeriod * SOURCE_PERIOD_MAX_RATIO,
                )
                val alpha =
                    if (boundedTarget > previousPeriod) {
                        SOURCE_SLOWDOWN_ALPHA
                    } else {
                        SOURCE_SPEEDUP_ALPHA
                    }
                sourcePeriodNanos += alpha * (boundedTarget - sourcePeriodNanos)
            }
            lastTrustedSourcePeriodNanos = sourcePeriodNanos
            runtimeCadenceEstablished = true
            pendingSourceDeadlineNanos =
                nowNanos + sourcePeriodNanos.toLong()
        }
    }

    fun onSourcePresented() {
        pendingSourceDeadlineNanos = 0L
    }

    fun measuredCapacityFps(): Float =
        if (displayPeriodNanos > 0.0) {
            (1_000_000_000.0 / displayPeriodNanos).toFloat()
        } else {
            0f
        }

    fun diagnostics(): Diagnostics {
        val smoothedSourceFps =
            if (sourcePeriodNanos > 0.0) {
                (1_000_000_000.0 / sourcePeriodNanos).toFloat()
            } else {
                0f
            }
        val measuredSourceFps =
            if (lastObservedSourceIntervalNanos > 0L) {
                (1_000_000_000.0 / lastObservedSourceIntervalNanos.toDouble()).toFloat()
            } else {
                smoothedSourceFps
            }
        val cadenceRatio =
            if (smoothedSourceFps > 1f) {
                (measuredSourceFps / smoothedSourceFps).coerceIn(0f, 1.25f)
            } else {
                1f
            }

        return Diagnostics(
            // Keep the original fields for binary/source compatibility with the
            // presenter log while changing their semantics away from a backoff
            // governor. No source-health value participates in admission.
            protectedSourceFps = smoothedSourceFps,
            sourceHealthRatio = cadenceRatio,
            sourceProtectionActive = false,
            recoveryStreak = adaptiveCostLimit,
            wantedGeneratedFrames = adaptiveWantedGeneratedFrames.toFloat(),
            fractionalPhase = adaptiveFractionalPhase.toFloat(),
            measuredOpportunityFps = measuredCapacityFps(),
        )
    }

    fun shouldPresentSourceNow(nowNanos: Long): Boolean {
        if (pendingSourceDeadlineNanos <= 0L) return false

        val slotPeriodNanos = when {
            plannedOutputPeriodNanos > 0.0 -> plannedOutputPeriodNanos
            displayPeriodNanos > 0.0 -> displayPeriodNanos
            else -> 0.0
        }
        if (slotPeriodNanos <= 0.0) return false

        val reserve =
            max(MIN_RESERVE_NS.toDouble(), slotPeriodNanos * SOURCE_RESERVE_RATIO)
                .toLong()
        return nowNanos + slotPeriodNanos.toLong() + reserve >=
            pendingSourceDeadlineNanos
    }

    @Suppress("UNUSED_PARAMETER")
    fun generationBudget(
        adaptive: Boolean,
        fixedGeneratedCeiling: Int,
        targetFps: Int,
        presentation: ApexPresentationTelemetry.Snapshot,
    ): Int {
        val fixedCeiling = fixedGeneratedCeiling.coerceIn(0, MAX_GENERATED_FRAMES)

        if (!adaptive) {
            adaptiveWantedGeneratedFrames = 0.0
            adaptiveFractionalPhase = 0.0
            lastAdmittedBudget = fixedCeiling
            plannedOutputPeriodNanos =
                if (sourcePeriodNanos > 0.0) {
                    sourcePeriodNanos / (fixedCeiling + 1).coerceAtLeast(1)
                } else {
                    displayPeriodNanos
                }
            return lastAdmittedBudget
        }

        configureAdaptive(
            targetFps = targetFps.coerceAtLeast(0),
            maxGeneratedFrames = MAX_GENERATED_FRAMES,
        )

        if (
            adaptiveTargetFps <= 0 ||
            adaptiveMaxGeneratedFrames <= 0 ||
            sourcePeriodNanos <= 0.0
        ) {
            lastAdmittedBudget = 0
            plannedOutputPeriodNanos = 0.0
            return 0
        }

        val sourceIntervalSeconds = when {
            lastObservedSourceIntervalNanos > 0L ->
                lastObservedSourceIntervalNanos / NANOS_PER_SECOND
            else -> sourcePeriodNanos / NANOS_PER_SECOND
        }
        if (sourceIntervalSeconds <= 0.0 || !sourceIntervalSeconds.isFinite()) {
            lastAdmittedBudget = 0
            return 0
        }

        val smoothedSourceIntervalSeconds = sourcePeriodNanos / NANOS_PER_SECOND
        adaptiveWantedGeneratedFrames = (
            adaptiveTargetFps * smoothedSourceIntervalSeconds - 1.0
        ).coerceIn(0.0, adaptiveMaxGeneratedFrames.toDouble())

        adaptiveObservedSeconds += sourceIntervalSeconds
        updateAdaptiveCostLimit(adaptiveWantedGeneratedFrames)

        // Port the LSFG time-domain error diffuser: every source interval
        // contributes elapsed target-output demand, consumes one source slot,
        // and carries only the fractional remainder. Whole opportunities that
        // cannot be used are consumed now rather than becoming catch-up debt.
        val opportunityIntervalSeconds = min(
            sourceIntervalSeconds,
            smoothedSourceIntervalSeconds * OPPORTUNITY_INTERVAL_MAX_RATIO,
        )
        val intervalOutputDemand =
            adaptiveTargetFps.toDouble() * opportunityIntervalSeconds
        adaptiveFractionalPhase = max(
            0.0,
            adaptiveFractionalPhase + intervalOutputDemand - 1.0,
        )

        val wholeOpportunities = floor(
            adaptiveFractionalPhase + INTEGER_SNAP_EPSILON,
        ).toInt().coerceAtLeast(0)
        adaptiveFractionalPhase = (
            adaptiveFractionalPhase - wholeOpportunities.toDouble()
        ).coerceIn(0.0, 0.999999)

        lastAdmittedBudget = min(
            wholeOpportunities,
            min(adaptiveCostLimit, adaptiveMaxGeneratedFrames),
        ).coerceIn(0, MAX_GENERATED_FRAMES)

        plannedOutputPeriodNanos =
            NANOS_PER_SECOND / adaptiveTargetFps.toDouble()
        return lastAdmittedBudget
    }

    private fun configureAdaptive(
        targetFps: Int,
        maxGeneratedFrames: Int,
    ) {
        val boundedMax = maxGeneratedFrames.coerceIn(0, MAX_GENERATED_FRAMES)
        if (
            adaptiveTargetFps == targetFps &&
            adaptiveMaxGeneratedFrames == boundedMax
        ) {
            return
        }

        val hadActiveConfig =
            adaptiveTargetFps > 0 && adaptiveMaxGeneratedFrames > 0
        val canWarmStart =
            hadActiveConfig &&
                runtimeCadenceEstablished &&
                sourcePeriodNanos > 0.0

        adaptiveTargetFps = targetFps
        adaptiveMaxGeneratedFrames = boundedMax
        adaptiveFractionalPhase = 0.0
        adaptiveObservedSeconds = 0.0
        adaptiveUnmetDemandSinceSeconds = -1.0

        if (boundedMax <= 0 || targetFps <= 0) {
            adaptiveCostLimit = 0
            adaptiveWantedGeneratedFrames = 0.0
            return
        }

        adaptiveWantedGeneratedFrames =
            if (sourcePeriodNanos > 0.0) {
                (
                    targetFps * (sourcePeriodNanos / NANOS_PER_SECOND) - 1.0
                ).coerceIn(0.0, boundedMax.toDouble())
            } else {
                0.0
            }

        adaptiveCostLimit =
            if (canWarmStart) {
                ceil(adaptiveWantedGeneratedFrames - INTEGER_SNAP_EPSILON)
                    .toInt()
                    .coerceIn(1, boundedMax)
            } else {
                1
            }
    }

    private fun updateAdaptiveCostLimit(wantedGeneratedFrames: Double) {
        if (adaptiveMaxGeneratedFrames <= 0) {
            adaptiveCostLimit = 0
            adaptiveUnmetDemandSinceSeconds = -1.0
            return
        }

        if (adaptiveCostLimit <= 0) {
            adaptiveCostLimit = 1
        }
        adaptiveCostLimit =
            adaptiveCostLimit.coerceAtMost(adaptiveMaxGeneratedFrames)

        if (
            adaptiveCostLimit >= adaptiveMaxGeneratedFrames ||
            wantedGeneratedFrames <= adaptiveCostLimit.toDouble() + 0.001
        ) {
            adaptiveUnmetDemandSinceSeconds = -1.0
            return
        }

        if (adaptiveUnmetDemandSinceSeconds < 0.0) {
            adaptiveUnmetDemandSinceSeconds = adaptiveObservedSeconds
            return
        }

        if (
            adaptiveObservedSeconds - adaptiveUnmetDemandSinceSeconds <
            SUSTAINED_DEMAND_SECONDS
        ) {
            return
        }

        adaptiveCostLimit =
            (adaptiveCostLimit + 1).coerceAtMost(adaptiveMaxGeneratedFrames)
        adaptiveUnmetDemandSinceSeconds = -1.0
    }

    private fun robustSourcePeriodNanos(): Double {
        if (recentSourcePeriodCount <= 0) return 0.0

        for (index in 0 until recentSourcePeriodCount) {
            sourcePeriodScratch[index] = recentSourcePeriodsNanos[index]
        }
        java.util.Arrays.sort(
            sourcePeriodScratch,
            0,
            recentSourcePeriodCount,
        )

        var begin = 0
        var end = recentSourcePeriodCount
        if (recentSourcePeriodCount >= 7) {
            begin = 1
            end -= 1
        }

        var sum = 0.0
        for (index in begin until end) {
            sum += sourcePeriodScratch[index]
        }
        return sum / (end - begin).coerceAtLeast(1)
    }

    private fun resetSourceCadenceWindow() {
        java.util.Arrays.fill(recentSourcePeriodsNanos, 0.0)
        java.util.Arrays.fill(sourcePeriodScratch, 0.0)
        recentSourcePeriodCount = 0
        recentSourcePeriodCursor = 0
    }

    companion object {
        private const val MAX_GENERATED_FRAMES = 3

        private const val MIN_DISPLAY_PERIOD_NS = 2_000_000L
        private const val MAX_DISPLAY_PERIOD_NS = 50_000_000L
        private const val MIN_SOURCE_PERIOD_NS = 4_000_000L
        private const val MAX_SOURCE_PERIOD_NS = 250_000_000L
        private const val MIN_RESERVE_NS = 750_000L
        private const val SOURCE_RESERVE_RATIO = 0.15

        private const val SOURCE_CADENCE_WINDOW = 9
        private const val SOURCE_DISCONTINUITY_RATIO = 8.0
        private const val SOURCE_PERIOD_MIN_RATIO = 0.75
        private const val SOURCE_PERIOD_MAX_RATIO = 1.30
        private const val SOURCE_SLOWDOWN_ALPHA = 0.45
        private const val SOURCE_SPEEDUP_ALPHA = 0.30

        private const val OPPORTUNITY_INTERVAL_MAX_RATIO = 1.50
        private const val SUSTAINED_DEMAND_SECONDS = 0.600
        private const val INTEGER_SNAP_EPSILON = 1e-6
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
