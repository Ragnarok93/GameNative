// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "lsfg_pacer.hpp"

#include <algorithm>
#include <cmath>

namespace lsfg {

namespace {

using Clock = std::chrono::steady_clock;

constexpr float INTERVAL_SMOOTHING = 0.25f;
constexpr float SOURCE_SMOOTHING = 0.15f;
constexpr float SOURCE_STALE_SECONDS = 0.5f;
constexpr float DISCONTINUITY_SECONDS = 0.25f;
constexpr float HEADROOM_EPSILON = 0.02f;
constexpr float SOURCE_ACCUM_FLOOR = 0.01f;
constexpr uint32_t MIN_RATE_SAMPLES = 12;

}

void LsfgPacer::SetConfig(const LsfgPacerConfig& config_) {
    config = config_;
    uint32_t adaptiveTarget = config.target_rate;
    if (adaptiveTarget != 0 && config.refresh_rate > 1.0f) {
        adaptiveTarget = std::min<uint32_t>(
            adaptiveTarget,
            static_cast<uint32_t>(std::llround(config.refresh_rate)));
    }
    adaptive_scheduler.configure(
        adaptiveTarget,
        config.target_rate != 0 ? LSFG_MAX_MULTIPLIER - 1 : 0);
    adaptive_scheduler.setGenerationFirst(false);
}

LsfgPresentationSlots BuildPresentationSlots(size_t generations) {
    LsfgPresentationSlots slots{};
    slots.generated_count = std::min(generations, LSFG_MAX_MULTIPLIER - 1);
    const double denominator = static_cast<double>(slots.generated_count + 1);
    for (size_t i = 0; i < slots.generated_count; ++i)
        slots.generated[i] = static_cast<double>(i + 1) / denominator;
    return slots;
}

size_t LsfgPacer::MaxGenerations() const {
    if (config.multiplier < 2) return 0;
    if (config.target_rate != 0) return LSFG_MAX_MULTIPLIER - 1;
    return std::min<size_t>(config.multiplier, LSFG_MAX_MULTIPLIER) - 1;
}

void LsfgPacer::TrackSourceRate(Clock::time_point now, uint64_t source_frames) {
    if (!last_source_sample) {
        last_source_sample = now;
        last_source_frames = source_frames;
        return;
    }

    const float elapsed = std::chrono::duration<float>(now - *last_source_sample).count();
    if (elapsed <= 0.0f) {
        return;
    }

    last_source_sample = now;
    const uint64_t drawn =
        source_frames > last_source_frames ? source_frames - last_source_frames : 0;
    last_source_frames = source_frames;

    if (elapsed > SOURCE_STALE_SECONDS) {
        source_frame_accum = 0.0f;
        source_time_accum = 0.0f;
        source_interval = 0.0f;
        source_samples = 0;
        return;
    }

    last_drawn = drawn;
    last_elapsed = elapsed;
    source_frame_accum += (static_cast<float>(drawn) - source_frame_accum) * SOURCE_SMOOTHING;
    source_time_accum += (elapsed - source_time_accum) * SOURCE_SMOOTHING;
    source_interval =
        source_frame_accum > SOURCE_ACCUM_FLOOR ? source_time_accum / source_frame_accum : 0.0f;
    if (source_samples < MIN_RATE_SAMPLES) ++source_samples;
}

void LsfgPacer::TrackLoopRate(float interval_seconds) {
    loop_interval = loop_interval > 0.0f
                        ? loop_interval + (interval_seconds - loop_interval) * INTERVAL_SMOOTHING
                        : interval_seconds;
    if (loop_samples < MIN_RATE_SAMPLES) ++loop_samples;
}

bool LsfgPacer::RatesSettled() const {
    return source_samples >= MIN_RATE_SAMPLES && loop_samples >= MIN_RATE_SAMPLES;
}

size_t LsfgPacer::HeadroomLimit() const {
    if (config.refresh_rate <= 0.0f || source_interval <= 0.0f ||
        source_samples < MIN_RATE_SAMPLES) {
        return LSFG_MAX_MULTIPLIER - 1;
    }

    const float budget = std::ceil(config.refresh_rate * source_interval - HEADROOM_EPSILON);
    return budget < 2.0f ? 0 : static_cast<size_t>(budget) - 1;
}

LsfgPlan LsfgPacer::Plan(size_t capacity, uint64_t source_frames) {
    return PlanAt(capacity, source_frames, Clock::now());
}

LsfgPlan LsfgPacer::PlanAt(size_t capacity, uint64_t source_frames, Clock::time_point now) {
    TrackSourceRate(now, source_frames);
    if (!last_frame) {
        last_frame = now;
        return {};
    }

    const float interval_seconds =
        std::chrono::duration<float>(now - *last_frame).count();
    last_frame = now;
    if (interval_seconds <= 0.0f)
        return {};

    TrackLoopRate(interval_seconds);
    const size_t ceiling = std::min(capacity, MaxGenerations());

    if (config.target_rate == 0) {
        if (interval_seconds > DISCONTINUITY_SECONDS) {
            limit = 0;
            return LsfgPlan{0, true};
        }
        // Fixed 2x/3x/4x remains authoritative exactly as in Legacy.
        limit = ceiling;
        return LsfgPlan{limit, true};
    }

    const auto sourceInterval =
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::duration<float>(interval_seconds));
    // Adaptive target density is owned by the exact same scheduler as Legacy.
    // Native applies only hard backend capacity after the policy decision.
    const size_t scheduled = adaptive_scheduler.plan(sourceInterval);
    limit = std::min(scheduled, ceiling);
    return LsfgPlan{limit, true};
}

LsfgPacerStats LsfgPacer::Stats() const {
    LsfgPacerStats stats;
    stats.source_rate = source_interval > 0.0f ? 1.0f / source_interval : 0.0f;
    stats.loop_rate = loop_interval > 0.0f ? 1.0f / loop_interval : 0.0f;
    stats.refresh_rate = config.refresh_rate;
    stats.target_rate = static_cast<float>(adaptive_scheduler.targetFps());
    stats.slots = config.refresh_rate * source_interval;
    stats.limit = limit;
    stats.rates_settled = RatesSettled();
    stats.last_drawn = last_drawn;
    stats.last_elapsed = last_elapsed;
    stats.source_frames = last_source_frames;
    return stats;
}

void LsfgPacer::Reset() {
    last_frame.reset();
    last_source_sample.reset();
    last_source_frames = 0;
    source_interval = 0.0f;
    source_frame_accum = 0.0f;
    source_time_accum = 0.0f;
    loop_interval = 0.0f;
    source_samples = 0;
    loop_samples = 0;
    last_drawn = 0;
    last_elapsed = 0.0f;
    adaptive_scheduler.reset();
    limit = 0;
}

}
