"""Compile and exercise the production native LSFG pacer with a deterministic clock."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "app/src/main/cpp/lsfg"
test = r'''
#include "lsfg_pacer.hpp"
#include <cassert>
#include <chrono>
#include <cmath>
#include <iostream>
using namespace lsfg;
using Clock = std::chrono::steady_clock;
auto timestamp(double seconds) {
    return Clock::time_point{} + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(seconds));
}
int main() {
    auto near = [](double a, double b) {
        return std::abs(a - b) < 1e-9;
    };
    // Desired-presentation slots are constructed from the actual selected
    // synthetic density, not the configured maximum multiplier.
    {
        auto x2 = BuildPresentationSlots(1);
        assert(x2.generated_count == 1);
        assert(near(x2.generated[0], 0.5));
        assert(near(x2.source, 1.0));

        auto x3 = BuildPresentationSlots(2);
        assert(x3.generated_count == 2);
        assert(near(x3.generated[0], 1.0 / 3.0));
        assert(near(x3.generated[1], 2.0 / 3.0));

        auto x4 = BuildPresentationSlots(3);
        assert(x4.generated_count == 3);
        assert(near(x4.generated[0], 0.25));
        assert(near(x4.generated[1], 0.50));
        assert(near(x4.generated[2], 0.75));

        auto bounded = BuildPresentationSlots(99);
        assert(bounded.generated_count == 3);
    }

    // Fixed output supports every exposed multiplier and respects WSI capacity.
    for (unsigned multiplier = 2; multiplier <= 4; ++multiplier) {
        for (unsigned capacity = 0; capacity <= 3; ++capacity) {
            LsfgPacer pacer;
            pacer.SetConfig({multiplier, 0, 120});
            assert(pacer.PlanAt(capacity, 1, timestamp(0)).generations == 0);
            for (unsigned frame = 2; frame < 90; ++frame) {
                auto plan = pacer.PlanAt(capacity, frame, timestamp(frame / 30.0));
                assert(plan.generations == std::min(capacity, multiplier - 1));
            }
        }
    }
    // Adaptive target clamps to panel refresh, regardless of requested target.
    for (unsigned target : {120u, 240u}) {
        LsfgPacer pacer;
        pacer.SetConfig({4, target, 120});
        for (unsigned frame = 0; frame < 120; ++frame) {
            auto plan = pacer.PlanAt(3, frame + 1, timestamp(frame / 60.0));
            if (frame > 30) {
                assert(plan.generations == 1);
                auto selected = BuildPresentationSlots(plan.generations);
                assert(selected.generated_count == 1);
                assert(near(selected.generated[0], 0.5));
            }
        }
        assert(pacer.Stats().rates_settled);
        assert(pacer.Stats().source_rate > 59 && pacer.Stats().source_rate < 61);
    }
    // Acceptance targets on a 120-Hz panel: with a 30-Hz source the
    // fractional-credit scheduler converges to 60/55/45 output Hz without a
    // source governor. Only the actually selected slot is constructed.
    for (unsigned target : {60u, 55u, 45u}) {
        LsfgPacer pacer;
        pacer.SetConfig({4, target, 120});
        size_t measuredOutputs = 0;
        constexpr unsigned settleFrames = 60;
        constexpr unsigned measuredFrames = 240;
        for (unsigned frame = 0; frame < settleFrames + measuredFrames; ++frame) {
            auto plan = pacer.PlanAt(
                3, frame + 1, timestamp(static_cast<double>(frame) / 30.0));
            assert(plan.generations <= 1);
            if (plan.generations == 1) {
                const auto slots = BuildPresentationSlots(plan.generations);
                assert(slots.generated_count == 1);
                assert(near(slots.generated[0], 0.5));
            }
            if (frame >= settleFrames)
                measuredOutputs += 1 + plan.generations;
        }
        const double measuredSeconds =
            static_cast<double>(measuredFrames) / 30.0;
        const double measuredFps =
            static_cast<double>(measuredOutputs) / measuredSeconds;
        assert(std::abs(measuredFps - static_cast<double>(target)) <= 1.0);
    }

    // Native output must not crowd out a source already filling the panel.
    LsfgPacer panelFull;
    panelFull.SetConfig({4, 120, 120});
    for (unsigned frame = 0; frame < 120; ++frame) {
        auto plan = panelFull.PlanAt(3, frame + 1, timestamp(frame / 120.0));
        if (frame > 30) assert(plan.generations == 0);
    }
    // Non-integral density retains fractional credit instead of rounding up.
    LsfgPacer fractional;
    fractional.SetConfig({4, 90, 120});
    size_t generated = 0;
    for (unsigned frame = 0; frame <= 120; ++frame) {
        auto plan = fractional.PlanAt(3, frame + 1, timestamp(frame / 60.0));
        assert(plan.generations <= 1);
        generated += plan.generations;
    }
    assert(generated >= 59 && generated <= 61);
    // Resume and reset discard stale timing/credit and start warm-up again.
    assert(fractional.PlanAt(3, 122, timestamp(5)).generations == 0);
    fractional.Reset();
    assert(!fractional.Stats().rates_settled);
    assert(fractional.PlanAt(3, 123, timestamp(6)).generations == 0);
    assert(fractional.PlanAt(0, 124, timestamp(6.1)).generations == 0);
    std::cout << "native LSFG pacer: explicit slots, fixed 2x/3x/4x, adaptive selected density, capacity, fractional credit, reset passed\n";
}
'''
shared = root.parent / "lsfg-vk-android"
with tempfile.TemporaryDirectory(prefix="native-lsfg-pacer-") as directory:
    cpp = Path(directory) / "test.cpp"
    binary = Path(directory) / "test"
    cpp.write_text(test)
    subprocess.run([
        "g++", "-std=c++20", "-Wall", "-Wextra", "-Werror",
        "-I", str(root),
        "-I", str(shared / "include"),
        str(cpp),
        str(root / "lsfg_pacer.cpp"),
        str(shared / "src/adaptive_scheduler.cpp"),
        "-o", str(binary),
    ], check=True)
    subprocess.run([str(binary)], check=True)
