// GameNative Apex native backend
// Upstream algorithm base: GunaCharanTeja/WinlatorMali@d3339806904fc5da0d8db64f4c8e5d77648975d9
// Imported under the upstream MIT license; see LICENSE.upstream in this directory.

#include "apex_engine.h"

namespace apex {


void ApexEngine::onFrameCaptured(int64_t nowNanos, bool isActualNewFrame) {
    if (!mActive.load(std::memory_order_relaxed)) return;
    if (!isActualNewFrame) return; // WinNative DIS only tracks real source frames!

    int64_t lastTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if (lastTime > 0) {
        float delta = static_cast<float>(nowNanos - lastTime);

        // Outlier rejection (>500ms hitch/loading filter)
        if (delta > 1000000.0f && delta < 500000000.0f) {
            mDeltaHistory[mHistoryIdx] = delta;
            mHistoryIdx = (mHistoryIdx + 1) % mDeltaHistory.size();

            std::copy(mDeltaHistory.begin(), mDeltaHistory.end(), mSortedHistory.begin());
            std::sort(mSortedHistory.begin(), mSortedHistory.end());
            float medianDelta = mSortedHistory[mSortedHistory.size() / 2];

            // WinNative DIS EWMA rate tracking (15% smoothing for rapid convergence without lag)
            if (mTypicalDeltaNanos <= 1000000.0f) {
                mTypicalDeltaNanos = medianDelta;
            } else {
                mTypicalDeltaNanos += (medianDelta - mTypicalDeltaNanos) * 0.15f;
            }
        }
    }

    mLastRealFrameTimeNanos.store(nowNanos, std::memory_order_release);

    // --- Auto-Multiplier Planning: Proactively reach and maintain Target FPS ---
    int target = mTargetFPS.load(std::memory_order_acquire);
    float desiredFps = (target > 0) ? static_cast<float>(target) : 60.0f;

    if (mSmoothedDesired < 1.0f) mSmoothedDesired = desiredFps;
    mSmoothedDesired += (desiredFps - mSmoothedDesired) * 0.25f;

    float sourceFps = (mTypicalDeltaNanos > 1000000.0f) ? (1000000000.0f / mTypicalDeltaNanos) : 30.0f;
    float ratio = mSmoothedDesired / std::max(1.0f, sourceFps);

    int currentGen = mPlannedGen;
    int proposedGen = 0;
    const bool adaptive = mAdaptiveFrameGeneration.load(std::memory_order_acquire);

    if (!adaptive) {
        // Fixed mode still treats the selected multiplier as a ceiling: the
        // existing cost-limit logic below may reduce generated work to protect
        // the real/source timeline under sustained saturation.
        proposedGen = std::clamp(mFixedMultiplier.load(std::memory_order_acquire) - 1, 1, 3);
    } else if (target > 0 && sourceFps >= static_cast<float>(target) - 0.5f) {
        proposedGen = 0;
    } else {
        // Adaptive mode preserves the mature WinNative target-driven governor.
        if (ratio <= 2.20f) {
            proposedGen = 1;
        } else {
            int requiredMultiplier = static_cast<int>(std::ceil(ratio - 0.20f));
            proposedGen = std::clamp(requiredMultiplier - 1, 1, 3);
        }
    }

    // Reset cost limit hold after expiration (5.0s cooldown)
    if (nowNanos >= mHoldUntilNanos && mCostLimit < 3) {
        mCostLimit = 3;
    }

    // Clamp proposedGen to current cost limit
    if (proposedGen > mCostLimit) {
        proposedGen = mCostLimit;
    }

    // GPU Saturation Detection:
    // If generating >1 frame (3x/4x) and game frame time grows >15% vs pre-raise baseline,
    // the GPU is choked by frame generation. Throttle back to protect source game FPS!
    if (currentGen > 1 && mDeltaAtRaise > 0.0f && mTypicalDeltaNanos > mDeltaAtRaise * 1.15f) {
        if (mDropSinceNanos == 0) {
            mDropSinceNanos = nowNanos;
        } else if (nowNanos - mDropSinceNanos >= 1000000000LL) { // 1.0s of saturation
            mCostLimit = std::max(1, currentGen - 1);
            mHoldUntilNanos = nowNanos + 5000000000LL; // 5.0s hold limit
            proposedGen = mCostLimit;
            mDropSinceNanos = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
            if (mLoggingEnabled.load(std::memory_order_relaxed)) {
                APEX_LOGI("ApexDIS GPU Saturation detected! Throttling multiplier back to %dx to protect game FPS",
                          mCostLimit + 1);
            }
        }
    } else {
        mDropSinceNanos = 0;
    }

    // Debounced step UP (12 frames ~ 350ms) to prevent hitch-induced 4x spikes
    if (proposedGen > currentGen) {
        mGenHighStreak++;
        mGenLowStreak = 0;
        if (mGenHighStreak >= 12) {
            mPlannedGen = proposedGen;
            mGenHighStreak = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
            mLastCostChangeNanos = nowNanos;
            if (mLoggingEnabled.load(std::memory_order_relaxed)) {
                APEX_LOGI("ApexDIS Multiplier stepped UP to %dx (mode=%s Source: %.1f FPS, Target: %d FPS)",
                          mPlannedGen + 1, adaptive ? "adaptive" : "fixed", sourceFps, target);
            }
        }
    } else if (proposedGen < currentGen) {
        mGenLowStreak++;
        mGenHighStreak = 0;
        if (mGenLowStreak >= 6) { // Prompt step DOWN (6 frames ~ 180ms)
            mPlannedGen = proposedGen;
            mGenLowStreak = 0;
            mDeltaAtRaise = mTypicalDeltaNanos;
            mLastCostChangeNanos = nowNanos;
            if (mLoggingEnabled.load(std::memory_order_relaxed)) {
                APEX_LOGI("ApexDIS Multiplier stepped DOWN to %dx (mode=%s Source: %.1f FPS, Target: %d FPS)",
                          mPlannedGen + 1, adaptive ? "adaptive" : "fixed", sourceFps, target);
            }
        }
    } else {
        mGenHighStreak = 0;
        mGenLowStreak = 0;
    }

    // Direct and responsive multiplier tracking
    float multiplier = (mPlannedGen > 0) ? static_cast<float>(mPlannedGen + 1) : 1.0f;
    float currentMult = mAutoMultiplierVal.load(std::memory_order_acquire);
    float nextMult = currentMult + (multiplier - currentMult) * 0.50f;
    mAutoMultiplierVal.store(nextMult, std::memory_order_release);
    mAutoMultiplier.store(static_cast<int>(std::round(multiplier)), std::memory_order_release);
}

float ApexEngine::getInterpolationFactor(int64_t nowNanos) {
    if (!mActive.load(std::memory_order_relaxed)) return 0.5f;
    if (mRealFramesCaptured.load(std::memory_order_relaxed) < 2) return 0.5f;

    int64_t lastRealTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if (lastRealTime > 0 && mTypicalDeltaNanos > 1000000.0f) {
        float elapsedNanos = static_cast<float>(nowNanos - lastRealTime);
        float phase = elapsedNanos / mTypicalDeltaNanos;
        return std::clamp(phase, 0.10f, 0.90f);
    }

    int framesSince = mFramesSinceReal.load(std::memory_order_acquire);
    int mult = std::max(2, mPlannedGen + 1);
    float factor = static_cast<float>(framesSince + 1) / static_cast<float>(mult);
    return std::clamp(factor, 0.10f, 0.90f);
}

} // namespace apex
