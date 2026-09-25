// GameNative Apex native backend
// Upstream algorithm base: GunaCharanTeja/WinlatorMali@d3339806904fc5da0d8db64f4c8e5d77648975d9
// Imported under the upstream MIT license; see LICENSE.upstream in this directory.

#include "apex_engine.h"

namespace apex {

void ApexEngine::onFrameCaptured(int64_t nowNanos, bool isActualNewFrame) {
    if (!mActive.load(std::memory_order_relaxed) || !isActualNewFrame) return;

    const int64_t lastTime = mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if (lastTime > 0) {
        const float delta = static_cast<float>(nowNanos - lastTime);
        if (delta > 1000000.0f && delta < 500000000.0f) {
            mDeltaHistory[mHistoryIdx] = delta;
            mHistoryIdx = (mHistoryIdx + 1) % mDeltaHistory.size();

            std::copy(mDeltaHistory.begin(), mDeltaHistory.end(), mSortedHistory.begin());
            std::sort(mSortedHistory.begin(), mSortedHistory.end());
            const float medianDelta = mSortedHistory[mSortedHistory.size() / 2];
            if (mTypicalDeltaNanos <= 1000000.0f) {
                mTypicalDeltaNanos = medianDelta;
            } else {
                mTypicalDeltaNanos += (medianDelta - mTypicalDeltaNanos) * 0.15f;
            }
        }
    }
    mLastRealFrameTimeNanos.store(nowNanos, std::memory_order_release);

    // ApexCadenceScheduler is the single admission owner. This receives the
    // producer timestamp carried through the AHB ring, not presenter dequeue
    // time. Native pacing retains it for diagnostics and legacy callers only.
    const bool adaptive = mAdaptiveFrameGeneration.load(std::memory_order_acquire);
    int requested = 1;
    if (!adaptive) {
        requested = std::clamp(
            mFixedMultiplier.load(std::memory_order_acquire) - 1,
            1,
            3);
    } else if (mTypicalDeltaNanos > 1000000.0f) {
        const float sourceFps = 1000000000.0f / mTypicalDeltaNanos;
        const float target = static_cast<float>(
            std::max(0, mTargetFPS.load(std::memory_order_acquire)));
        requested = target > 0.0f
            ? std::clamp(
                static_cast<int>(std::ceil(target / std::max(1.0f, sourceFps))) - 1,
                0,
                3)
            : 0;
    }
    mPlannedGen.store(requested, std::memory_order_release);
    mAutoMultiplier.store(requested + 1, std::memory_order_release);
    mAutoMultiplierVal.store(static_cast<float>(requested + 1), std::memory_order_release);
}

float ApexEngine::getInterpolationFactor(int64_t nowNanos) {
    if (!mActive.load(std::memory_order_relaxed)) return 0.5f;
    if (mRealFramesCaptured.load(std::memory_order_relaxed) < 2) return 0.5f;

    const int64_t lastRealTime =
        mLastRealFrameTimeNanos.load(std::memory_order_acquire);
    if (lastRealTime > 0 && mTypicalDeltaNanos > 1000000.0f) {
        const float elapsedNanos = static_cast<float>(nowNanos - lastRealTime);
        const float phase = elapsedNanos / mTypicalDeltaNanos;
        return std::clamp(phase, 0.10f, 0.90f);
    }

    const int framesSince = mFramesSinceReal.load(std::memory_order_acquire);
    const int mult = std::max(2, mPlannedGen.load(std::memory_order_acquire) + 1);
    const float factor =
        static_cast<float>(framesSince + 1) / static_cast<float>(mult);
    return std::clamp(factor, 0.10f, 0.90f);
}

} // namespace apex
