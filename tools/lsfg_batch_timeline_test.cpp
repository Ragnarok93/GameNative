#include "presentation_timeline.hpp"
#include <cassert>
#include <chrono>
#include <limits>

int main() {
    SourceProtectedTimeline source;
    SourcePresentationTimeline output;
    constexpr uint64_t interval = 45000000, lead = 16666667;
    uint64_t previousEnd = 0;
    for (unsigned batch = 0; batch < 600; ++batch) {
        const uint64_t arrival = 1000000000ULL + batch * interval;
        const auto raw = source.observe(arrival, std::chrono::nanoseconds(interval));
        const auto scheduled = output.schedule(raw, interval + lead);
        const unsigned count = batch % 3 + 1;
        const auto first = source.syntheticDesiredTimeNs(scheduled, 1.0 / (count + 1));
        // Reproduces the recorded completion wait: raw first slots have expired.
        const auto ready = arrival + 38000000;
        assert(first > ready + 5000000);
        assert(scheduled.previousSourceDesiredTimeNs >= arrival + interval);
        assert(scheduled.sourceDesiredTimeNs <= arrival + 2 * interval + lead);
        assert(scheduled.sourceDesiredTimeNs - scheduled.previousSourceDesiredTimeNs == interval);
        assert(raw.previousSourceDesiredTimeNs == arrival); // Admission clock unchanged.
        if (batch) assert(scheduled.previousSourceDesiredTimeNs >= previousEnd);
        previousEnd = scheduled.sourceDesiredTimeNs;
    }
    // Sustained cadence changes and alternating jitter must not grow a queue.
    source.reset(); output.reset(); previousEnd = 0;
    uint64_t arrival = 1000000000ULL;
    for (unsigned batch = 0; batch < 3000; ++batch) {
        const uint64_t step = batch < 1000 ? 45000000 :
            batch < 2000 ? 16000000 : (batch % 2 ? 25000000 : 35000000);
        arrival += step;
        const auto sample = source.observe(arrival, std::chrono::nanoseconds(step));
        const auto scheduled = output.schedule(sample, sample.intervalNs + lead);
        assert(scheduled.sourceDesiredTimeNs > previousEnd);
        assert(scheduled.previousSourceDesiredTimeNs >= previousEnd);
        assert(scheduled.sourceDesiredTimeNs <= arrival + 4 * sample.intervalNs + lead);
        previousEnd = scheduled.sourceDesiredTimeNs;
    }
    output.reset();
    SourceTimelineSample raw{.intervalNs=10000000, .previousSourceDesiredTimeNs=100,
        .sourceDesiredTimeNs=10000100, .valid=true};
    assert(output.schedule(raw, 0).sourceDesiredTimeNs == raw.sourceDesiredTimeNs);
    output.reset();
    raw.previousSourceDesiredTimeNs = std::numeric_limits<uint64_t>::max() - 10;
    raw.sourceDesiredTimeNs = std::numeric_limits<uint64_t>::max();
    assert(!output.schedule(raw, 100).valid); // Never wrap deadlines.
}
