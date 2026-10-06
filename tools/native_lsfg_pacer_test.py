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
#include <iostream>
using namespace lsfg;
using Clock = std::chrono::steady_clock;
auto timestamp(double seconds) {
    return Clock::time_point{} + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(seconds));
}
int main() {
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
            if (frame > 30) assert(plan.generations == 1);
        }
        assert(pacer.Stats().rates_settled);
        assert(pacer.Stats().source_rate > 59 && pacer.Stats().source_rate < 61);
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
    std::cout << "native LSFG pacer: fixed 2x/3x/4x, capacity, adaptive panel limit, fractional credit, reset passed\n";
}
'''
with tempfile.TemporaryDirectory(prefix="native-lsfg-pacer-") as directory:
    cpp = Path(directory) / "test.cpp"
    binary = Path(directory) / "test"
    cpp.write_text(test)
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-I", str(root), str(cpp), str(root / "lsfg_pacer.cpp"), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
