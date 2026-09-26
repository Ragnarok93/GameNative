package app.gamenative.framegen

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Source-clock cadence planner for Apex.
 *
 * Source production timestamps describe demand; Choreographer callbacks describe
 * presentation opportunities. Neither fixed nor adaptive generation is disabled
 * because the source cadence slows. Generated slots may still be dropped when
 * the display has no opportunity, measured pipeline cost exceeds the interval,
 * or a ready real frame reaches its presentation deadline.
 */
class ApexCadenceScheduler {
    data class Diagnostics(
        val sourceFps: Float,
        val currentSourceIntervalMs: Float,
        val requestedSyntheticCount: Int,
        val admittedSyntheticCount: Int,
        val presentationOpportunityBudget: Int,
        val pipelineCostBudget: Int,
        val wantedGeneratedFrames: Float,
        val fractionalPhase: Float,
        val measuredOpportunityFps: Float,
        val presentationCeilingFps: Float,
        val remainingSyntheticSlots: Int,
    )

    private var lastOpportunityNanos = 0L
    private var displayPeriodNanos = 0.0
    private var presentationCeilingFps = 0.0

    private var lastSourceTimestampNanos = 0L
    private var lastObservedSourceIntervalNanos = 0L
    private var sourcePeriodNanos = 0.0
    private var lastTrustedSourcePeriodNanos = 0.0
    private var pendingSourceDeadlineNanos = 0L
    private var presentationWindowDeadlineNanos = 0L
    private var plannedOutputPeriodNanos = 0.0

    private val recentSourcePeriodsNanos = DoubleArray(SOURCE_CADENCE_WINDOW)
    private val sourcePeriodScratch = DoubleArray(SOURCE_CADENCE_WINDOW)
    private var recentSourcePeriodCount = 0
    private var recentSourcePeriodCursor = 0

    private var lastAdaptiveMode: Boolean? = null
    private var lastTargetFps = -1
    private var lastFixedCeiling = -1
    private var generationPhase = 0.0
    private var wantedGeneratedFrames = 0.0
    private var requestedSyntheticCount = 0
    private var lastAdmittedBudget = 0
    private var presentationOpportunityBudget = MAX_GENERATED_FRAMES
    private var lastPipelineCostBudget = -1
    private var remainingSyntheticSlots = 0

    private var preparationCostEstimateNanos = 0.0
    private var syntheticCostPerFrameNanos = 0.0

    fun reset() {
        lastOpportunityNanos = 0L
        displayPeriodNanos = 0.0
        presentationCeilingFps = 0.0

        lastSourceTimestampNanos = 0L
        lastObservedSourceIntervalNanos = 0L
        sourcePeriodNanos = 0.0
        lastTrustedSourcePeriodNanos = 0.0
        pendingSourceDeadlineNanos = 0L
        presentationWindowDeadlineNanos = 0L
        plannedOutputPeriodNanos = 0.0
        resetSourceCadenceWindow()

        lastAdaptiveMode = null
        lastTargetFps = -1
        lastFixedCeiling = -1
        generationPhase = 0.0
        wantedGeneratedFrames = 0.0
        requestedSyntheticCount = 0
        lastAdmittedBudget = 0
        presentationOpportunityBudget = MAX_GENERATED_FRAMES
        lastPipelineCostBudget = -1
        remainingSyntheticSlots = 0

        preparationCostEstimateNanos = 0.0
        syntheticCostPerFrameNanos = 0.0
    }

    fun setPresentationCeilingFps(frameRate: Float) {
        presentationCeilingFps =
            if (frameRate.isFinite() && frameRate > 1f) {
                frameRate.toDouble()
            } else {
                0.0
            }
    }

    fun recordDisplayOpportunity(nowNanos: Long) {
        val previous = lastOpportunityNanos
        lastOpportunityNanos = nowNanos
        if (previous <= 0L) return
        val delta = nowNanos - previous
        if (delta !in MIN_DISPLAY_PERIOD_NS..MAX_DISPLAY_PERIOD_NS) return
        displayPeriodNanos =
            if (displayPeriodNanos <= 0.0) {
                delta.toDouble()
            } else {
                displayPeriodNanos * 0.80 + delta.toDouble() * 0.20
            }
    }

    fun recordSourceFrame(sourceTimestampNanos: Long) {
        if (sourceTimestampNanos <= 0L) return
        val previous = lastSourceTimestampNanos
        lastSourceTimestampNanos = sourceTimestampNanos
        if (previous <= 0L) return

        val delta = sourceTimestampNanos - previous
        if (delta <= 0L) return

        if (
            lastTrustedSourcePeriodNanos > 0.0 &&
            delta.toDouble() > lastTrustedSourcePeriodNanos * SOURCE_DISCONTINUITY_RATIO
        ) {
            resetSourceCadenceWindow()
            lastObservedSourceIntervalNanos = 0L
            sourcePeriodNanos = 0.0
            lastTrustedSourcePeriodNanos = 0.0
            pendingSourceDeadlineNanos = 0L
            plannedOutputPeriodNanos = 0.0
            generationPhase = 0.0
            wantedGeneratedFrames = 0.0
            requestedSyntheticCount = 0
            lastAdmittedBudget = 0
            return
        }

        if (delta !in MIN_SOURCE_PERIOD_NS..MAX_SOURCE_PERIOD_NS) {
            if (sourcePeriodNanos > 0.0) {
                pendingSourceDeadlineNanos =
                    sourceTimestampNanos + sourcePeriodNanos.toLong()
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
            pendingSourceDeadlineNanos =
                sourceTimestampNanos + sourcePeriodNanos.toLong()
        }
    }

    fun onSourcePresented() {
        pendingSourceDeadlineNanos = 0L
        presentationWindowDeadlineNanos = 0L
        remainingSyntheticSlots = 0
    }

    fun onGeneratedPresented() {
        markGeneratedPresented(0L)
    }

    fun onGeneratedPresented(nowNanos: Long) {
        markGeneratedPresented(nowNanos)
    }

    private fun markGeneratedPresented(nowNanos: Long) {
        if (
            nowNanos > 0L &&
            presentationWindowDeadlineNanos <= 0L &&
            remainingSyntheticSlots > 0
        ) {
            val cadence =
                sourcePeriodNanos.takeIf { it > 0.0 }
                    ?: lastObservedSourceIntervalNanos.toDouble().takeIf { it > 0.0 }
            if (cadence != null) {
                val producerDeadline =
                    pendingSourceDeadlineNanos.toDouble().coerceAtLeast(0.0)
                presentationWindowDeadlineNanos =
                    max(
                        producerDeadline,
                        nowNanos.toDouble() + cadence,
                    ).toLong()
            }
        }
        if (remainingSyntheticSlots > 0) {
            remainingSyntheticSlots--
        }
    }

    fun recordNativeCost(
        preparationCostNanos: Long,
        pipelineCostNanos: Long,
        generatedFrames: Int,
    ) {
        if (preparationCostNanos > 0L) {
            preparationCostEstimateNanos =
                ewma(
                    preparationCostEstimateNanos,
                    preparationCostNanos.toDouble(),
                    COST_EWMA_ALPHA,
                )
        }
        if (pipelineCostNanos > 0L && generatedFrames > 0) {
            val perFrame =
                pipelineCostNanos.toDouble() / generatedFrames.coerceAtLeast(1).toDouble()
            syntheticCostPerFrameNanos =
                ewma(
                    syntheticCostPerFrameNanos,
                    perFrame,
                    COST_EWMA_ALPHA,
                )
        }
    }

    fun measuredCapacityFps(): Float =
        if (displayPeriodNanos > 0.0) {
            (NANOS_PER_SECOND / displayPeriodNanos).toFloat()
        } else {
            0f
        }

    fun diagnostics(): Diagnostics {
        val sourceFps =
            if (sourcePeriodNanos > 0.0) {
                NANOS_PER_SECOND / sourcePeriodNanos
            } else {
                0.0
            }
        val currentInterval =
            if (lastObservedSourceIntervalNanos > 0L) {
                lastObservedSourceIntervalNanos.toDouble()
            } else {
                sourcePeriodNanos
            }
        return Diagnostics(
            sourceFps = sourceFps.toFloat(),
            currentSourceIntervalMs = (currentInterval / 1_000_000.0).toFloat(),
            requestedSyntheticCount = requestedSyntheticCount,
            admittedSyntheticCount = lastAdmittedBudget,
            presentationOpportunityBudget = presentationOpportunityBudget,
            pipelineCostBudget = lastPipelineCostBudget,
            wantedGeneratedFrames = wantedGeneratedFrames.toFloat(),
            fractionalPhase = generationPhase.toFloat(),
            measuredOpportunityFps = measuredCapacityFps(),
            presentationCeilingFps = presentationCeilingFps.toFloat(),
            remainingSyntheticSlots = remainingSyntheticSlots,
        )
    }

    fun shouldPresentSourceNow(nowNanos: Long): Boolean {
        if (
            pendingSourceDeadlineNanos <= 0L ||
            remainingSyntheticSlots <= 0
        ) {
            return false
        }
        val slot = effectiveSlotPeriodNanos()
        if (slot <= 0.0) return false

        // Preempt only when finishing the synthetic prefix would actually push
        // the buffered real frame beyond its producer-cadence deadline.
        val deadlineNanos =
            presentationWindowDeadlineNanos.takeIf { it > 0L }
                ?: pendingSourceDeadlineNanos
        val completionNanos =
            nowNanos.toDouble() + slot * remainingSyntheticSlots.toDouble()
        return completionNanos > deadlineNanos.toDouble()
    }

    fun shouldPreemptForQueuedSource(
        nowNanos: Long,
        queuedSourceTimestampNanos: Long,
    ): Boolean {
        if (
            queuedSourceTimestampNanos <= 0L ||
            nowNanos < queuedSourceTimestampNanos ||
            remainingSyntheticSlots <= 0
        ) {
            return false
        }
        val slot = effectiveSlotPeriodNanos()
        if (slot <= 0.0) return false

        val cadence =
            sourcePeriodNanos.takeIf { it > 0.0 }
                ?: lastObservedSourceIntervalNanos.toDouble().takeIf { it > 0.0 }
                ?: return false
        val queueAllowance = max(
            slot * QUEUED_SOURCE_SLOT_ALLOWANCE,
            cadence * QUEUED_SOURCE_CADENCE_ALLOWANCE,
        )
        val queuedDeadline =
            queuedSourceTimestampNanos.toDouble() + queueAllowance
        val completionNanos =
            nowNanos.toDouble() + slot * remainingSyntheticSlots.toDouble()
        return completionNanos > queuedDeadline
    }

    fun generationBudget(
        adaptive: Boolean,
        fixedGeneratedCeiling: Int,
        targetFps: Int,
    ): Int {
        val fixedCeiling =
            fixedGeneratedCeiling.coerceIn(0, MAX_GENERATED_FRAMES)
        val boundedTargetFps = targetFps.coerceAtLeast(0)
        val configChanged =
            lastAdaptiveMode != adaptive ||
                lastTargetFps != boundedTargetFps ||
                lastFixedCeiling != fixedCeiling
        if (configChanged) {
            generationPhase = 0.0
            lastAdaptiveMode = adaptive
            lastTargetFps = boundedTargetFps
            lastFixedCeiling = fixedCeiling
        }

        if (
            sourcePeriodNanos <= 0.0 ||
            fixedCeiling == 0 ||
            (adaptive && boundedTargetFps <= 0)
        ) {
            wantedGeneratedFrames = 0.0
            requestedSyntheticCount = 0
            lastAdmittedBudget = 0
            remainingSyntheticSlots = 0
            presentationWindowDeadlineNanos = 0L
            plannedOutputPeriodNanos = 0.0
            return 0
        }

        val currentIntervalNanos =
            lastObservedSourceIntervalNanos.toDouble().takeIf { it > 0.0 }
                ?: sourcePeriodNanos
        val boundedOpportunityIntervalNanos = min(
            currentIntervalNanos,
            sourcePeriodNanos * OPPORTUNITY_INTERVAL_MAX_RATIO,
        )

        val sourceDemand =
            if (adaptive) {
                (
                    boundedTargetFps.toDouble() *
                        (boundedOpportunityIntervalNanos / NANOS_PER_SECOND) -
                        1.0
                    ).coerceIn(0.0, MAX_GENERATED_FRAMES.toDouble())
            } else {
                fixedCeiling.toDouble()
            }

        wantedGeneratedFrames =
            if (adaptive) {
                (
                    boundedTargetFps.toDouble() *
                        (sourcePeriodNanos / NANOS_PER_SECOND) -
                        1.0
                    ).coerceIn(0.0, MAX_GENERATED_FRAMES.toDouble())
            } else {
                fixedCeiling.toDouble()
            }
        requestedSyntheticCount =
            ceil(wantedGeneratedFrames - INTEGER_SNAP_EPSILON)
                .toInt()
                .coerceIn(0, MAX_GENERATED_FRAMES)

        val presentationCapacity =
            computePresentationCapacityPerSource()
        presentationOpportunityBudget =
            ceil(presentationCapacity - INTEGER_SNAP_EPSILON)
                .toInt()
                .coerceIn(0, MAX_GENERATED_FRAMES)

        val desiredWhole =
            advanceGenerationPhase(min(sourceDemand, presentationCapacity))

        lastPipelineCostBudget = computeCostBudget()
        val admitted =
            if (lastPipelineCostBudget >= 0) {
                min(desiredWhole, lastPipelineCostBudget)
            } else {
                desiredWhole
            }
        lastAdmittedBudget =
            admitted.coerceIn(0, MAX_GENERATED_FRAMES)
        remainingSyntheticSlots = lastAdmittedBudget
        presentationWindowDeadlineNanos = 0L

        plannedOutputPeriodNanos =
            if (adaptive) {
                NANOS_PER_SECOND / boundedTargetFps.toDouble()
            } else {
                sourcePeriodNanos / (fixedCeiling + 1).coerceAtLeast(1)
            }
        return lastAdmittedBudget
    }

    private fun advanceGenerationPhase(wantedPerSource: Double): Int {
        if (!(wantedPerSource > 0.0)) return 0
        generationPhase = max(
            0.0,
            generationPhase +
                wantedPerSource.coerceIn(
                    0.0,
                    MAX_GENERATED_FRAMES.toDouble(),
                ),
        )
        val whole =
            floor(generationPhase + INTEGER_SNAP_EPSILON)
                .toInt()
                .coerceAtLeast(0)
        generationPhase =
            (generationPhase - whole.toDouble()).coerceIn(0.0, 0.999999)
        return whole.coerceAtMost(MAX_GENERATED_FRAMES)
    }

    private fun computePresentationCapacityPerSource(): Double {
        val capacityFps =
            presentationCeilingFps.takeIf { it > 1.0 }
                ?: measuredCapacityFps().toDouble().takeIf { it > 1.0 }
                ?: return MAX_GENERATED_FRAMES.toDouble()
        val opportunitiesPerSource =
            capacityFps * sourcePeriodNanos / NANOS_PER_SECOND
        return (opportunitiesPerSource - 1.0)
            .coerceIn(0.0, MAX_GENERATED_FRAMES.toDouble())
    }

    /**
     * Returns -1 until native submission-cost telemetry is available.
     * This is a capacity bound only; it never measures or waits for GPU
     * completion and never derives a backoff decision from source FPS.
     */
    private fun computeCostBudget(): Int {
        if (syntheticCostPerFrameNanos <= 0.0) return -1
        val usable = max(
            0.0,
            sourcePeriodNanos * SYNTHETIC_BUDGET_RATIO -
                preparationCostEstimateNanos,
        )
        return floor(usable / syntheticCostPerFrameNanos)
            .toInt()
            .coerceIn(0, MAX_GENERATED_FRAMES)
    }

    private fun effectiveSlotPeriodNanos(): Double = when {
        // Ready synthetics cost a physical presentation slot. Short-lived
        // callback stalls are pressure evidence, not a new display ceiling.
        presentationCeilingFps > 1.0 ->
            NANOS_PER_SECOND / presentationCeilingFps
        displayPeriodNanos > 0.0 -> displayPeriodNanos
        plannedOutputPeriodNanos > 0.0 -> plannedOutputPeriodNanos
        sourcePeriodNanos > 0.0 -> sourcePeriodNanos
        else -> 0.0
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

    private fun ewma(previous: Double, sample: Double, alpha: Double): Double =
        if (previous <= 0.0) {
            sample
        } else {
            previous * (1.0 - alpha) + sample * alpha
        }

    companion object {
        private const val MAX_GENERATED_FRAMES = 3
        private const val MIN_DISPLAY_PERIOD_NS = 2_000_000L
        private const val MAX_DISPLAY_PERIOD_NS = 50_000_000L
        private const val MIN_SOURCE_PERIOD_NS = 4_000_000L
        private const val MAX_SOURCE_PERIOD_NS = 250_000_000L
        private const val SOURCE_CADENCE_WINDOW = 9
        private const val SOURCE_DISCONTINUITY_RATIO = 8.0
        private const val SOURCE_PERIOD_MIN_RATIO = 0.75
        private const val SOURCE_PERIOD_MAX_RATIO = 1.30
        private const val SOURCE_SLOWDOWN_ALPHA = 0.45
        private const val SOURCE_SPEEDUP_ALPHA = 0.30
        private const val QUEUED_SOURCE_SLOT_ALLOWANCE = 1.5
        private const val QUEUED_SOURCE_CADENCE_ALLOWANCE = 0.75
        private const val OPPORTUNITY_INTERVAL_MAX_RATIO = 1.50
        private const val SYNTHETIC_BUDGET_RATIO = 0.80
        private const val COST_EWMA_ALPHA = 0.25
        private const val INTEGER_SNAP_EPSILON = 1e-6
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
