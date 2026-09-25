package app.gamenative.framegen

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Source-owned admission controller for Apex.
 *
 * The real/source timeline is authoritative. Target FPS and Fixed multipliers
 * describe desired synthetic output, but every generated frame is optional.
 * A clean baseline is learned only from source-only intervals. Generated work
 * that stretches that baseline triggers source-only recovery; recovery probes
 * one synthetic level at a time and never accumulates catch-up debt.
 */
class ApexSourceProtectedScheduler {
    enum class ProtectionState(val wireName: String) {
        BASELINE_LEARNING("baseline-learning"),
        NORMAL("normal"),
        PROTECTED_SOURCE_ONLY("protected-source-only"),
        GENERATION_PROBE("generation-probe"),
    }

    enum class BackoffReason(val wireName: String) {
        NONE("none"),
        BASELINE_LEARNING("baseline-learning"),
        SOURCE_DEGRADATION("source-degradation"),
        PRESENTATION_CAPACITY("presentation-capacity"),
        PIPELINE_COST("pipeline-cost"),
        RECOVERY_HOLD("recovery-hold"),
    }

    data class Diagnostics(
        val cleanSourceBaselineIntervalMs: Float,
        val cleanSourceBaselineFps: Float,
        val currentSourceIntervalMs: Float,
        val baselineRatio: Float,
        val requestedSyntheticCount: Int,
        val admittedSyntheticCount: Int,
        val sourceProtectionState: String,
        val sourceProtectionActive: Boolean,
        val backoffReason: String,
        val recoveryStreak: Int,
        val preparationCostEstimateMs: Float,
        val pipelineCostEstimateMs: Float,
        val presentationOpportunityBudget: Int,
        val generationProbeLevel: Int,
        val wantedGeneratedFrames: Float,
        val fractionalPhase: Float,
        val measuredOpportunityFps: Float,
        // Compatibility names retained for existing log/HUD consumers.
        val protectedSourceFps: Float,
        val sourceHealthRatio: Float,
    )

    private var lastOpportunityNanos = 0L
    private var displayPeriodNanos = 0.0

    private var lastSourceArrivalNanos = 0L
    private var currentSourceIntervalNanos = 0L
    private var pendingSourceDeadlineNanos = 0L
    private var plannedOutputPeriodNanos = 0.0

    private val cleanSourcePeriodsNanos = DoubleArray(CLEAN_BASELINE_WINDOW)
    private val cleanSourceScratch = DoubleArray(CLEAN_BASELINE_WINDOW)
    private var cleanSourceCount = 0
    private var cleanSourceCursor = 0
    private var cleanSourceBaselineNanos = 0.0

    private var protectionState = ProtectionState.BASELINE_LEARNING
    private var backoffReason = BackoffReason.BASELINE_LEARNING
    private var recoveryStreak = 0
    private var probeHealthySamples = 0
    private var normalHealthyGeneratedSamples = 0
    private var degradedCleanCandidateNanos = 0.0
    private var degradedCleanCandidateSamples = 0

    private var admissionLimit = 1
    private var lastAdmittedBudget = 0
    private var requestedSyntheticCount = 0
    private var presentationOpportunityBudget = MAX_GENERATED_FRAMES

    private var adaptiveFractionalPhase = 0.0
    private var adaptiveWantedGeneratedFrames = 0.0
    private var lastAdaptiveMode: Boolean? = null
    private var lastTargetFps = -1
    private var lastFixedCeiling = -1

    private var preparationCostEstimateNanos = 0.0
    private var syntheticCostPerFrameNanos = 0.0

    fun reset() {
        lastOpportunityNanos = 0L
        displayPeriodNanos = 0.0
        lastSourceArrivalNanos = 0L
        currentSourceIntervalNanos = 0L
        pendingSourceDeadlineNanos = 0L
        plannedOutputPeriodNanos = 0.0

        java.util.Arrays.fill(cleanSourcePeriodsNanos, 0.0)
        java.util.Arrays.fill(cleanSourceScratch, 0.0)
        cleanSourceCount = 0
        cleanSourceCursor = 0
        cleanSourceBaselineNanos = 0.0

        protectionState = ProtectionState.BASELINE_LEARNING
        backoffReason = BackoffReason.BASELINE_LEARNING
        recoveryStreak = 0
        probeHealthySamples = 0
        normalHealthyGeneratedSamples = 0
        degradedCleanCandidateNanos = 0.0
        degradedCleanCandidateSamples = 0

        admissionLimit = 1
        lastAdmittedBudget = 0
        requestedSyntheticCount = 0
        presentationOpportunityBudget = MAX_GENERATED_FRAMES

        adaptiveFractionalPhase = 0.0
        adaptiveWantedGeneratedFrames = 0.0
        lastAdaptiveMode = null
        lastTargetFps = -1
        lastFixedCeiling = -1

        preparationCostEstimateNanos = 0.0
        syntheticCostPerFrameNanos = 0.0
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

    fun recordSourceArrival(nowNanos: Long) {
        val previous = lastSourceArrivalNanos
        lastSourceArrivalNanos = nowNanos
        if (previous <= 0L) return

        val delta = nowNanos - previous
        if (delta !in MIN_SOURCE_PERIOD_NS..MAX_SOURCE_PERIOD_NS) {
            currentSourceIntervalNanos = 0L
            pendingSourceDeadlineNanos = 0L
            lastAdmittedBudget = 0
            adaptiveFractionalPhase = 0.0
            return
        }
        if (
            cleanSourceBaselineNanos > 0.0 &&
            delta.toDouble() > cleanSourceBaselineNanos * SOURCE_DISCONTINUITY_RATIO
        ) {
            currentSourceIntervalNanos = 0L
            pendingSourceDeadlineNanos = 0L
            lastAdmittedBudget = 0
            adaptiveFractionalPhase = 0.0
            return
        }

        currentSourceIntervalNanos = delta
        val baselineBefore = cleanSourceBaselineNanos
        val ratioBefore =
            if (baselineBefore > 0.0) {
                (baselineBefore / delta.toDouble()).coerceIn(0.0, 1.5)
            } else {
                1.0
            }

        if (lastAdmittedBudget == 0) {
            observeCleanSource(delta.toDouble(), ratioBefore)
        } else if (baselineBefore > 0.0) {
            observeGeneratedInterval(ratioBefore)
        }

        val deadlinePeriod = cleanSourceBaselineNanos.takeIf { it > 0.0 }
            ?: delta.toDouble()
        pendingSourceDeadlineNanos = nowNanos + deadlinePeriod.toLong()
    }

    private fun observeCleanSource(intervalNanos: Double, baselineRatio: Double) {
        if (cleanSourceBaselineNanos <= 0.0) {
            addCleanBaselineSample(intervalNanos)
            if (cleanSourceCount >= BASELINE_MIN_SAMPLES) {
                cleanSourceBaselineNanos = robustCleanBaseline()
                protectionState = ProtectionState.GENERATION_PROBE
                backoffReason = BackoffReason.NONE
                admissionLimit = 1
                recoveryStreak = 0
            }
            return
        }

        when (protectionState) {
            ProtectionState.PROTECTED_SOURCE_ONLY -> {
                if (baselineRatio >= RECOVERY_RATIO) {
                    recoveryStreak++
                    degradedCleanCandidateSamples = 0
                    degradedCleanCandidateNanos = 0.0
                    if (recoveryStreak >= RECOVERY_SAMPLES) {
                        protectionState = ProtectionState.GENERATION_PROBE
                        backoffReason = BackoffReason.NONE
                        admissionLimit = 1
                        recoveryStreak = 0
                        probeHealthySamples = 0
                    }
                } else {
                    recoveryStreak = 0
                    // A genuine workload/scene change may establish a new clean
                    // baseline, but only after sustained source-only evidence.
                    if (degradedCleanCandidateNanos <= 0.0) {
                        degradedCleanCandidateNanos = intervalNanos
                        degradedCleanCandidateSamples = 1
                    } else {
                        val similarity = min(
                            degradedCleanCandidateNanos,
                            intervalNanos,
                        ) / max(degradedCleanCandidateNanos, intervalNanos)
                        if (similarity >= REBASE_STABILITY_RATIO) {
                            degradedCleanCandidateNanos =
                                degradedCleanCandidateNanos * 0.85 + intervalNanos * 0.15
                            degradedCleanCandidateSamples++
                        } else {
                            degradedCleanCandidateNanos = intervalNanos
                            degradedCleanCandidateSamples = 1
                        }
                    }
                    if (degradedCleanCandidateSamples >= CLEAN_REBASE_SAMPLES) {
                        cleanSourceBaselineNanos = degradedCleanCandidateNanos
                        resetCleanBaselineWindow()
                        repeat(BASELINE_MIN_SAMPLES) {
                            addCleanBaselineSample(cleanSourceBaselineNanos)
                        }
                        protectionState = ProtectionState.GENERATION_PROBE
                        backoffReason = BackoffReason.NONE
                        admissionLimit = 1
                        degradedCleanCandidateSamples = 0
                        degradedCleanCandidateNanos = 0.0
                    }
                }
            }

            ProtectionState.BASELINE_LEARNING -> {
                addCleanBaselineSample(intervalNanos)
                if (cleanSourceCount >= BASELINE_MIN_SAMPLES) {
                    cleanSourceBaselineNanos = robustCleanBaseline()
                    protectionState = ProtectionState.GENERATION_PROBE
                    backoffReason = BackoffReason.NONE
                    admissionLimit = 1
                }
            }

            ProtectionState.GENERATION_PROBE,
            ProtectionState.NORMAL -> {
                if (baselineRatio in CLEAN_TRACK_MIN_RATIO..CLEAN_TRACK_MAX_RATIO) {
                    cleanSourceBaselineNanos =
                        cleanSourceBaselineNanos * BASELINE_KEEP_ALPHA +
                            intervalNanos * (1.0 - BASELINE_KEEP_ALPHA)
                }
            }
        }
    }

    private fun observeGeneratedInterval(baselineRatio: Double) {
        if (baselineRatio < SOURCE_PROTECTION_RATIO) {
            enterProtection(BackoffReason.SOURCE_DEGRADATION)
            return
        }

        when (protectionState) {
            ProtectionState.GENERATION_PROBE -> {
                if (baselineRatio >= PROBE_HEALTHY_RATIO) {
                    probeHealthySamples++
                    if (probeHealthySamples >= PROBE_SUCCESS_SAMPLES) {
                        protectionState = ProtectionState.NORMAL
                        backoffReason = BackoffReason.NONE
                        probeHealthySamples = 0
                        normalHealthyGeneratedSamples = 0
                    }
                } else {
                    enterProtection(BackoffReason.SOURCE_DEGRADATION)
                }
            }

            ProtectionState.NORMAL -> {
                if (baselineRatio >= PROBE_HEALTHY_RATIO) {
                    normalHealthyGeneratedSamples++
                    if (normalHealthyGeneratedSamples >= RAISE_SAMPLES) {
                        admissionLimit =
                            (admissionLimit + 1).coerceAtMost(MAX_GENERATED_FRAMES)
                        normalHealthyGeneratedSamples = 0
                    }
                } else {
                    normalHealthyGeneratedSamples = 0
                }
            }

            ProtectionState.PROTECTED_SOURCE_ONLY,
            ProtectionState.BASELINE_LEARNING -> Unit
        }
    }

    private fun enterProtection(reason: BackoffReason) {
        protectionState = ProtectionState.PROTECTED_SOURCE_ONLY
        backoffReason = reason
        admissionLimit = 0
        recoveryStreak = 0
        probeHealthySamples = 0
        normalHealthyGeneratedSamples = 0
        adaptiveFractionalPhase = 0.0
        degradedCleanCandidateNanos = 0.0
        degradedCleanCandidateSamples = 0
    }

    fun onSourcePresented() {
        pendingSourceDeadlineNanos = 0L
    }

    fun recordNativeCost(
        preparationCostNanos: Long,
        pipelineCostNanos: Long,
        generatedFrames: Int,
    ) {
        if (preparationCostNanos > 0L) {
            preparationCostEstimateNanos =
                ewma(preparationCostEstimateNanos, preparationCostNanos.toDouble(), COST_EWMA_ALPHA)
        }
        if (pipelineCostNanos > 0L && generatedFrames > 0) {
            val perFrame =
                pipelineCostNanos.toDouble() / generatedFrames.coerceAtLeast(1).toDouble()
            syntheticCostPerFrameNanos =
                ewma(syntheticCostPerFrameNanos, perFrame, COST_EWMA_ALPHA)
        }
    }

    fun measuredCapacityFps(): Float =
        if (displayPeriodNanos > 0.0) {
            (NANOS_PER_SECOND / displayPeriodNanos).toFloat()
        } else {
            0f
        }

    fun diagnostics(): Diagnostics {
        val currentInterval =
            currentSourceIntervalNanos.takeIf { it > 0L }?.toDouble() ?: cleanSourceBaselineNanos
        val baselineRatio =
            if (cleanSourceBaselineNanos > 0.0 && currentInterval > 0.0) {
                (cleanSourceBaselineNanos / currentInterval).coerceIn(0.0, 1.5)
            } else {
                1.0
            }
        val baselineFps =
            if (cleanSourceBaselineNanos > 0.0) {
                NANOS_PER_SECOND / cleanSourceBaselineNanos
            } else {
                0.0
            }
        return Diagnostics(
            cleanSourceBaselineIntervalMs = (cleanSourceBaselineNanos / 1_000_000.0).toFloat(),
            cleanSourceBaselineFps = baselineFps.toFloat(),
            currentSourceIntervalMs = (currentInterval / 1_000_000.0).toFloat(),
            baselineRatio = baselineRatio.toFloat(),
            requestedSyntheticCount = requestedSyntheticCount,
            admittedSyntheticCount = lastAdmittedBudget,
            sourceProtectionState = protectionState.wireName,
            sourceProtectionActive = protectionState == ProtectionState.PROTECTED_SOURCE_ONLY,
            backoffReason = backoffReason.wireName,
            recoveryStreak = recoveryStreak,
            preparationCostEstimateMs = (preparationCostEstimateNanos / 1_000_000.0).toFloat(),
            pipelineCostEstimateMs = (syntheticCostPerFrameNanos / 1_000_000.0).toFloat(),
            presentationOpportunityBudget = presentationOpportunityBudget,
            generationProbeLevel =
                if (protectionState == ProtectionState.GENERATION_PROBE) 1 else 0,
            wantedGeneratedFrames = adaptiveWantedGeneratedFrames.toFloat(),
            fractionalPhase = adaptiveFractionalPhase.toFloat(),
            measuredOpportunityFps = measuredCapacityFps(),
            protectedSourceFps = baselineFps.toFloat(),
            sourceHealthRatio = baselineRatio.toFloat(),
        )
    }

    fun shouldPresentSourceNow(nowNanos: Long): Boolean {
        if (pendingSourceDeadlineNanos <= 0L) return false
        val slot = effectiveSlotPeriodNanos()
        if (slot <= 0.0) return false
        val reserve = max(MIN_RESERVE_NS.toDouble(), slot * SOURCE_RESERVE_RATIO)
        return nowNanos + slot.toLong() + reserve.toLong() >= pendingSourceDeadlineNanos
    }

    /**
     * A newer real source is allowed to terminate the remaining synthetic prefix
     * only when another generated slot would materially delay that real source.
     */
    fun shouldPreemptForQueuedSource(
        nowNanos: Long,
        queuedSourceArrivalNanos: Long,
    ): Boolean {
        if (queuedSourceArrivalNanos <= 0L || nowNanos < queuedSourceArrivalNanos) return false
        if (protectionState == ProtectionState.PROTECTED_SOURCE_ONLY) return true
        val slot = effectiveSlotPeriodNanos()
        if (slot <= 0.0) return false

        val baseline = cleanSourceBaselineNanos.takeIf { it > 0.0 }
            ?: currentSourceIntervalNanos.toDouble().takeIf { it > 0.0 }
            ?: return false
        val queueAllowance = max(slot * QUEUED_SOURCE_SLOT_ALLOWANCE, baseline * QUEUED_SOURCE_BASELINE_ALLOWANCE)
        val queuedDeadline = queuedSourceArrivalNanos + queueAllowance.toLong()
        val reserve = max(MIN_RESERVE_NS.toDouble(), slot * SOURCE_RESERVE_RATIO)
        return nowNanos + slot.toLong() + reserve.toLong() >= queuedDeadline
    }

    fun generationBudget(
        adaptive: Boolean,
        fixedGeneratedCeiling: Int,
        targetFps: Int,
        presentation: ApexPresentationTelemetry.Snapshot,
    ): Int {
        val fixedCeiling = fixedGeneratedCeiling.coerceIn(0, MAX_GENERATED_FRAMES)
        val configChanged =
            lastAdaptiveMode != adaptive ||
                lastTargetFps != targetFps ||
                lastFixedCeiling != fixedCeiling
        if (configChanged) {
            adaptiveFractionalPhase = 0.0
            lastAdaptiveMode = adaptive
            lastTargetFps = targetFps
            lastFixedCeiling = fixedCeiling
        }

        val baseline = cleanSourceBaselineNanos
        if (baseline <= 0.0) {
            requestedSyntheticCount = if (adaptive) MAX_GENERATED_FRAMES else fixedCeiling
            adaptiveWantedGeneratedFrames = 0.0
            lastAdmittedBudget = 0
            plannedOutputPeriodNanos = 0.0
            backoffReason = BackoffReason.BASELINE_LEARNING
            return 0
        }

        val currentInterval =
            currentSourceIntervalNanos.takeIf { it > 0L }?.toDouble() ?: baseline
        val desiredWhole = if (adaptive) {
            adaptiveWantedGeneratedFrames =
                (targetFps.coerceAtLeast(0) * (currentInterval / NANOS_PER_SECOND) - 1.0)
                    .coerceIn(0.0, MAX_GENERATED_FRAMES.toDouble())
            advanceAdaptivePhase(
                targetFps = targetFps.coerceAtLeast(0),
                currentIntervalNanos = currentInterval,
                baselineIntervalNanos = baseline,
            )
        } else {
            adaptiveWantedGeneratedFrames = 0.0
            adaptiveFractionalPhase = 0.0
            fixedCeiling
        }
        requestedSyntheticCount =
            if (adaptive) {
                kotlin.math.ceil(adaptiveWantedGeneratedFrames - INTEGER_SNAP_EPSILON)
                    .toInt()
                    .coerceIn(0, MAX_GENERATED_FRAMES)
            } else {
                fixedCeiling
            }

        if (
            fixedCeiling == 0 ||
            (adaptive && targetFps <= 0) ||
            protectionState == ProtectionState.PROTECTED_SOURCE_ONLY
        ) {
            if (protectionState == ProtectionState.PROTECTED_SOURCE_ONLY) {
                backoffReason =
                    if (backoffReason == BackoffReason.NONE) {
                        BackoffReason.RECOVERY_HOLD
                    } else {
                        backoffReason
                    }
            }
            adaptiveFractionalPhase = 0.0
            lastAdmittedBudget = 0
            plannedOutputPeriodNanos = 0.0
            return 0
        }

        presentationOpportunityBudget =
            computePresentationBudget(presentation, baseline)

        val costBudget = computeCostBudget(baseline)
        var limit = min(admissionLimit.coerceAtLeast(1), presentationOpportunityBudget)
        if (costBudget >= 0) limit = min(limit, costBudget)
        if (protectionState == ProtectionState.GENERATION_PROBE) {
            limit = min(limit, 1)
        }

        val desiredBudget =
            if (adaptive) desiredWhole else fixedCeiling
        val admitted = min(desiredBudget, limit)
            .coerceIn(0, MAX_GENERATED_FRAMES)

        backoffReason = when {
            admitted < desiredBudget && costBudget >= 0 && costBudget <= presentationOpportunityBudget &&
                costBudget < desiredBudget -> BackoffReason.PIPELINE_COST
            admitted < desiredBudget && presentationOpportunityBudget < desiredBudget ->
                BackoffReason.PRESENTATION_CAPACITY
            else -> BackoffReason.NONE
        }

        lastAdmittedBudget = admitted
        plannedOutputPeriodNanos =
            if (admitted > 0) baseline / (admitted + 1).toDouble()
            else baseline
        return admitted
    }

    private fun advanceAdaptivePhase(
        targetFps: Int,
        currentIntervalNanos: Double,
        baselineIntervalNanos: Double,
    ): Int {
        if (targetFps <= 0) {
            adaptiveFractionalPhase = 0.0
            return 0
        }
        val opportunityInterval = min(
            currentIntervalNanos,
            baselineIntervalNanos * OPPORTUNITY_INTERVAL_MAX_RATIO,
        )
        adaptiveFractionalPhase = max(
            0.0,
            adaptiveFractionalPhase +
                targetFps.toDouble() * (opportunityInterval / NANOS_PER_SECOND) - 1.0,
        )
        val whole = floor(adaptiveFractionalPhase + INTEGER_SNAP_EPSILON)
            .toInt()
            .coerceAtLeast(0)
        adaptiveFractionalPhase =
            (adaptiveFractionalPhase - whole.toDouble()).coerceIn(0.0, 0.999999)
        return whole.coerceAtMost(MAX_GENERATED_FRAMES)
    }

    private fun computePresentationBudget(
        presentation: ApexPresentationTelemetry.Snapshot,
        baselineIntervalNanos: Double,
    ): Int {
        val baselineFps = NANOS_PER_SECOND / baselineIntervalNanos
        val measured =
            presentation.opportunityFps.takeIf { it > 1f }?.toDouble()
                ?: measuredCapacityFps().toDouble().takeIf { it > 1.0 }
                ?: return MAX_GENERATED_FRAMES
        return (floor(measured / baselineFps).toInt() - 1)
            .coerceIn(0, MAX_GENERATED_FRAMES)
    }

    /**
     * Returns -1 when no native cost estimate exists yet. The estimate is
     * submission-cost telemetry only; it never blocks to measure GPU completion.
     */
    private fun computeCostBudget(baselineIntervalNanos: Double): Int {
        if (syntheticCostPerFrameNanos <= 0.0) return -1
        val usable = max(
            0.0,
            baselineIntervalNanos * SYNTHETIC_BUDGET_RATIO - preparationCostEstimateNanos,
        )
        return floor(usable / syntheticCostPerFrameNanos)
            .toInt()
            .coerceIn(0, MAX_GENERATED_FRAMES)
    }

    private fun effectiveSlotPeriodNanos(): Double = when {
        plannedOutputPeriodNanos > 0.0 -> plannedOutputPeriodNanos
        displayPeriodNanos > 0.0 -> displayPeriodNanos
        cleanSourceBaselineNanos > 0.0 -> cleanSourceBaselineNanos
        else -> 0.0
    }

    private fun addCleanBaselineSample(intervalNanos: Double) {
        cleanSourcePeriodsNanos[cleanSourceCursor] = intervalNanos
        cleanSourceCursor = (cleanSourceCursor + 1) % CLEAN_BASELINE_WINDOW
        cleanSourceCount = min(cleanSourceCount + 1, CLEAN_BASELINE_WINDOW)
    }

    private fun robustCleanBaseline(): Double {
        if (cleanSourceCount <= 0) return 0.0
        for (i in 0 until cleanSourceCount) {
            cleanSourceScratch[i] = cleanSourcePeriodsNanos[i]
        }
        java.util.Arrays.sort(cleanSourceScratch, 0, cleanSourceCount)
        var begin = 0
        var end = cleanSourceCount
        if (cleanSourceCount >= 7) {
            begin = 1
            end -= 1
        }
        var sum = 0.0
        for (i in begin until end) sum += cleanSourceScratch[i]
        return sum / (end - begin).coerceAtLeast(1)
    }

    private fun resetCleanBaselineWindow() {
        java.util.Arrays.fill(cleanSourcePeriodsNanos, 0.0)
        java.util.Arrays.fill(cleanSourceScratch, 0.0)
        cleanSourceCount = 0
        cleanSourceCursor = 0
    }

    private fun ewma(previous: Double, sample: Double, alpha: Double): Double =
        if (previous <= 0.0) sample else previous * (1.0 - alpha) + sample * alpha

    companion object {
        private const val MAX_GENERATED_FRAMES = 3
        private const val CLEAN_BASELINE_WINDOW = 12
        private const val BASELINE_MIN_SAMPLES = 5

        private const val MIN_DISPLAY_PERIOD_NS = 2_000_000L
        private const val MAX_DISPLAY_PERIOD_NS = 50_000_000L
        private const val MIN_SOURCE_PERIOD_NS = 4_000_000L
        private const val MAX_SOURCE_PERIOD_NS = 250_000_000L
        private const val SOURCE_DISCONTINUITY_RATIO = 8.0

        private const val SOURCE_PROTECTION_RATIO = 0.90
        private const val RECOVERY_RATIO = 0.95
        private const val PROBE_HEALTHY_RATIO = 0.94
        private const val CLEAN_TRACK_MIN_RATIO = 0.94
        private const val CLEAN_TRACK_MAX_RATIO = 1.06
        private const val REBASE_STABILITY_RATIO = 0.96
        private const val BASELINE_KEEP_ALPHA = 0.96

        private const val RECOVERY_SAMPLES = 6
        private const val PROBE_SUCCESS_SAMPLES = 4
        private const val RAISE_SAMPLES = 6
        private const val CLEAN_REBASE_SAMPLES = 24

        private const val MIN_RESERVE_NS = 750_000L
        private const val SOURCE_RESERVE_RATIO = 0.15
        private const val QUEUED_SOURCE_SLOT_ALLOWANCE = 1.5
        private const val QUEUED_SOURCE_BASELINE_ALLOWANCE = 0.25
        private const val OPPORTUNITY_INTERVAL_MAX_RATIO = 1.50
        private const val SYNTHETIC_BUDGET_RATIO = 0.80
        private const val COST_EWMA_ALPHA = 0.25
        private const val INTEGER_SNAP_EPSILON = 1e-6
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
