"""Compile production host policy paths against deterministic Vulkan/thread stubs."""
from pathlib import Path
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "app/src/main/cpp/winlator"
context = (HOST / "VulkanRendererContext.cpp").read_text()
lsfg = (HOST / "VulkanRendererLsfg.cpp").read_text()


def function(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def run(name, code):
    with tempfile.TemporaryDirectory(prefix="lsfg-stability-") as directory:
        cpp, binary = Path(directory) / "test.cpp", Path(directory) / "test"
        cpp.write_text(code)
        subprocess.run(["g++", "-std=c++20", "-pthread", "-Wall", "-Wextra", "-Werror",
                        str(cpp), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print("PASS:", name, flush=True)


if len(sys.argv) == 1 or sys.argv[1] == "confirmation":
    selection_start = context.index("        HostDisplayConfirmationBackend confirmationBackend =")
    selection_end = context.index("        const uint32_t gpuOutstanding", selection_start)
    selection = context[selection_start:selection_end]
    selector = ""
    if "selectHostDisplayConfirmationBackend()" in selection:
        selector = function(context, "HostDisplayConfirmationBackend VulkanRendererContext::selectHostDisplayConfirmationBackend()")
    run("Mailbox replacement waits are unavailable physical evidence; FIFO and GOOGLE retain proof", r'''
#include <cassert>
#include <shared_mutex>
using VkPresentModeKHR=int;
constexpr int VK_PRESENT_MODE_FIFO_KHR=2, VK_PRESENT_MODE_MAILBOX_KHR=1;
enum class HostDisplayConfirmationBackend { WsiAccepted, PresentWait, GoogleDisplayTiming };
struct VulkanRendererContext {
    bool hostGoogleDisplayTimingEnabled=false, hostPresentWaitEnabled=true;
    int activePresentMode=VK_PRESENT_MODE_MAILBOX_KHR;
    mutable std::shared_mutex frameMutex;
    struct { bool WaitForPresentKHR=true, GetPastPresentationTimingGOOGLE=true; } vk_;
    bool isDisplayConfirmationAvailable() const;
    HostDisplayConfirmationBackend selectHostDisplayConfirmationBackend() const;
    HostDisplayConfirmationBackend selected() const {
''' + selection + r'''
        return confirmationBackend;
    }
};
''' + selector + function(lsfg, "bool VulkanRendererContext::isDisplayConfirmationAvailable()") + r'''
int main() {
    VulkanRendererContext c;
    // Three replaced Mailbox IDs can all have successful present waits.
    // None of those retirements identifies which image reached the display.
    for (int id=10; id<=12; ++id) {
        assert(!c.isDisplayConfirmationAvailable());
        assert(c.selected()==HostDisplayConfirmationBackend::WsiAccepted);
    }
    c.activePresentMode=VK_PRESENT_MODE_FIFO_KHR;
    assert(c.isDisplayConfirmationAvailable());
    assert(c.selected()==HostDisplayConfirmationBackend::PresentWait);
    c.activePresentMode=VK_PRESENT_MODE_MAILBOX_KHR;
    c.hostGoogleDisplayTimingEnabled=true;
    assert(c.isDisplayConfirmationAvailable());
    assert(c.selected()==HostDisplayConfirmationBackend::GoogleDisplayTiming);
    c.vk_.GetPastPresentationTimingGOOGLE=false;
    assert(!c.isDisplayConfirmationAvailable());
}
''')

if len(sys.argv) == 1 or sys.argv[1] == "dispatch":
    presenter = function(context, "void VulkanRendererContext::hostPresenterLoop()")
    start = presenter.index("        const auto pacedIt =")
    end = presenter.index("        const auto generatedIt =", start)
    pacing = presenter[start:end]
    helper = ""
    if "mailboxDispatchDelayNs(" in pacing:
        helper = function(context, "uint64_t mailboxDispatchDelayNs(")
    run("Mailbox pacing bounds raw intent and skips rejected timestamps", r'''
#include <algorithm>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cinttypes>
#include <cstring>
#include <vector>
constexpr int VK_PRESENT_MODE_MAILBOX_KHR=1, ANDROID_LOG_DEBUG=3;
constexpr uint64_t kMaxHostFutureDesiredPresentNs=250000000ULL;
uint64_t slept=0;
namespace std::this_thread { void sleep_for(std::chrono::nanoseconds delay) { slept+=delay.count(); } }
void __android_log_print(int,const char*,const char*,...) {}
uint64_t monotonicTimeNs() { return 1000000000ULL+slept; }
struct LsfgFrameProvenance { bool uniqueDelivery=true, nativeImplementation=true; uint64_t sourceIndex=1; int kind=1; };
struct HostDesiredPresentDecision {
    uint64_t submittedDesiredPresentTimeNs=0, provenanceDesiredPresentTimeNs=0, refreshPeriodNs=16666667;
    const char* fallbackReason="none";
};
struct Present {
    std::vector<LsfgFrameProvenance> frameProvenance{{}};
    HostDesiredPresentDecision desiredDecision;
    int presentMode=VK_PRESENT_MODE_MAILBOX_KHR;
    uint64_t mailboxDispatchDelayNs=0, swapchainGeneration=1;
};
''' + helper + r'''
uint64_t pace(uint64_t desired, const char* reason, bool submitted=false) {
    Present present;
    present.desiredDecision.provenanceDesiredPresentTimeNs=desired;
    present.desiredDecision.fallbackReason=reason;
    if(submitted) present.desiredDecision.submittedDesiredPresentTimeNs=desired;
    uint64_t presenterNowNs=1000000000ULL;
    slept=0;
''' + pacing + r'''
    return slept;
}
int main() {
    assert(pace(10000000000ULL,"too-far-future")==0);
    assert(pace(UINT64_MAX,"google-display-timing-unavailable")==0);
    assert(pace(1100000000ULL,"mixed-temporal-intent")==0);
    assert(pace(900000000ULL,"google-display-timing-unavailable")==0);
    assert(pace(1100000000ULL,"google-display-timing-unavailable")==96000000ULL);
    assert(pace(1100000000ULL,"accepted",true)==96000000ULL);
}
''')

if len(sys.argv) == 1 or sys.argv[1] == "locking":
    run("completion-only wakes exclude concurrent settings transactions", r'''
#include <atomic>
#include <cassert>
#include <condition_variable>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <vector>
constexpr int VK_NULL_HANDLE=0;
struct VulkanRendererContext {
    bool isRunning=true, protectedCompletion=false;
    std::atomic<bool> hostPresentCompletionPending_{true}, surfaceDetached{true};
    std::atomic<bool> needsRender{false}, fbResized{false}, cursorMoved{false};
    std::mutex dirtyMutex; std::shared_mutex frameMutex; std::condition_variable dirtyCV;
    int swapchain=1; std::vector<int> cmdBufs{1};
    void processHostPresentCompletions() {
        // A settings writer runs on another thread, as it does in the app.
        std::thread contender([this] {
            const bool acquired=frameMutex.try_lock();
            protectedCompletion=!acquired;
            if(acquired) frameMutex.unlock();
        });
        contender.join();
        isRunning=false;
    }
    void renderFrame() {}
    void renderLoop();
};
''' + function(context, "void VulkanRendererContext::renderLoop()") + r'''
int main() { VulkanRendererContext c; c.renderLoop(); assert(c.protectedCompletion); }
''')

if len(sys.argv) == 1 or sys.argv[1] == "flow":
    retirement = function(lsfg, "void VulkanRendererContext::waitNativeResources(")
    # Keep the baseline implementation executable before the optional drain
    # parameter exists, so RED demonstrates its unconditional host drain.
    retirement = retirement.replace("waitNativeResources()",
                                    "waitNativeResources([[maybe_unused]] bool drainPresenter)")
    gate_start = context.index("        const bool flowOnly = chainRebuild")
    gate_end = context.index("        if (!createCompositeTargets", gate_start)
    gate = context[gate_start:gate_end]
    run("Flow-only retirement waits GPU users without blocking on host presentation", r'''
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <vector>
constexpr int VK_NULL_HANDLE=0, VK_TRUE=1, VK_SUCCESS=0;
#define RLOG(...) ((void)0)
void vkr_lsfg_forget_targets(void*) {}
struct VulkanRendererContext {
    int device=1, drained=0, completed=0;
    std::vector<int> inFlightFences{1,2};
    std::atomic<uint64_t> nativeHostWaitNsTotal_{0}, nativeHostWaitSamples_{0};
    struct { int WaitForFences(int,int,const int*,int,uint64_t) {return VK_SUCCESS;} } vk_;
    void drainHostPresenter(const char*) { ++drained; }
    void completeObservedFence(int) { ++completed; }
    void waitNativeResources(bool drainPresenter=true);
    void applyRebuild(bool chainRebuild, bool compositeRebuild, const char* nativeRebuildReason) {
        const bool rebuild=chainRebuild || compositeRebuild;
        void* lsfg=nullptr;
''' + gate + r'''
    }
};
''' + retirement + r'''
int main() {
    VulkanRendererContext c;
    c.waitNativeResources(false);
    assert(c.drained==0 && c.completed==2);
    c.waitNativeResources();
    assert(c.drained==1 && c.completed==4);
    c.applyRebuild(true,false,"flow-scale-change");
    assert(c.drained==1 && c.completed==6);
    c.applyRebuild(true,true,"flow-scale-change");
    assert(c.drained==2 && c.completed==8);
    c.applyRebuild(true,false,"resolution-change");
    assert(c.drained==3 && c.completed==10);
    c.applyRebuild(false,true,"initial-create");
    assert(c.drained==4 && c.completed==12);
    c.applyRebuild(false,false,"none");
    assert(c.drained==4 && c.completed==12);
}
''')

if len(sys.argv) == 1 or sys.argv[1] == "target":
    native = (HOST.parent / "lsfg/vkr_lsfg.cpp").read_text()
    start = native.index("        const double requested_output_target =") if "const double requested_output_target" in native else native.index("        const double output_target =")
    end = native.index("        const bool output_valid =", start)
    target = native[start:end]
    run("Flow feedback compares physical cadence with a display-reachable target", r'''
#include <algorithm>
#include <cassert>
struct Stats {double target_rate=0, source_rate=30, refresh_rate=60;};
struct Runtime { struct { unsigned multiplier=4; } config; struct Pacer {
    auto Config() const { return Runtime{}.config; }
} pacer; };
double targetFps(Stats stats) {
    Runtime runtime; auto* lsfg=&runtime;
''' + target + r'''
    return output_target;
}
int main() {
    assert(targetFps({0,30,60})==60);
    assert(targetFps({240,30,120})==120);
    assert(targetFps({55,30,120})==55);
    assert(targetFps({0,30,0})==120);
}
''')
