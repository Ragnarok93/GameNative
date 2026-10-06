"""Run the production confirmation poll against a mock Vulkan timing driver.

Usage: python3 tools/host_display_timing_test.py [VulkanRendererContext.cpp]
No Android SDK or Vulkan headers are required.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

source = Path(sys.argv[1]) if len(sys.argv) > 1 else (
    Path(__file__).resolve().parents[1]
    / "app/src/main/cpp/winlator/VulkanRendererContext.cpp"
)
text = source.read_text()
start = text.index("void VulkanRendererContext::pollHostDisplayConfirmations()")
end = text.index("void VulkanRendererContext::flushHostDisplayConfirmationsUnknown", start)
production = text[start:end]
stub = r"""
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cinttypes>
#include <deque>
#include <vector>
using VkResult = int;
constexpr int VK_SUCCESS=0, VK_INCOMPLETE=5, VK_TIMEOUT=2, VK_NOT_READY=1;
constexpr int VK_NULL_HANDLE=0, ANDROID_LOG_WARN=5, ANDROID_LOG_DEBUG=3;
constexpr uint64_t kMaxSanePresentMarginNs=1000000000ULL;
constexpr uint64_t kMaxHostConfirmationAgeNs=1000000000ULL;
void __android_log_print(int, const char*, const char*, ...) {}
uint64_t monotonicTimeNs() { return 100; }
enum class HostDisplayConfirmationBackend { GoogleDisplayTiming, PresentWait };
struct VkPastPresentationTimingGOOGLE {
    uint32_t presentID;
    uint64_t desiredPresentTime, actualPresentTime, earliestPresentTime, presentMargin;
};
struct HostDisplayConfirmation {
    HostDisplayConfirmationBackend backend=HostDisplayConfirmationBackend::GoogleDisplayTiming;
    uint32_t googlePresentId=1;
    uint64_t swapchainGeneration=1, hostPresentId=1, enqueuedAtNs=100;
    uint64_t actualPresentTimeNs=0, wsiDesiredPresentTimeNs=0, earliestPresentTimeNs=0;
    uint64_t presentMarginRawNs=0, presentMarginNs=0;
};
int countResult=VK_SUCCESS, dataResult=VK_SUCCESS, driverCalls=0;
uint32_t advertisedCount=1;
VkResult queryTiming(int, int, uint32_t* count, VkPastPresentationTimingGOOGLE* data) {
    ++driverCalls;
    if (!data) { *count=advertisedCount; return countResult; }
    assert(*count==advertisedCount);
    data[0]={1, 110, 120, 120, 0};
    *count=1;
    return dataResult;
}
struct Dispatch {
    decltype(&queryTiming) GetPastPresentationTimingGOOGLE=queryTiming;
    VkResult (*WaitForPresentKHR)(int,int,uint64_t,uint64_t)=nullptr;
};
struct VulkanRendererContext {
    bool hostGoogleDisplayTimingEnabled=true, hostPresentWaitEnabled=false;
    int device=1, swapchain=1;
    Dispatch vk_;
    std::deque<HostDisplayConfirmation> pendingHostDisplayConfirmations;
    uint64_t hostSwapchainGeneration_=1, hostInvalidPresentMarginTotal_=0;
    uint64_t hostDisplayConfirmed_=0, hostDisplayUnknown_=0;
    uint64_t hostDisplayTimingQueryFailureTotal_=0, hostConfirmationExpiredTotal_=0;
    int emitted=0;
    void emitHostDisplayConfirmation(const HostDisplayConfirmation& c,
                                    bool confirmed, bool unknown, const char*) {
        assert(c.actualPresentTimeNs==120);
        assert(confirmed && !unknown);
        ++emitted;
    }
    void pollHostDisplayConfirmations();
};
"""
cases = r"""
int main() {
    for (int result : {VK_SUCCESS, VK_INCOMPLETE}) {
        countResult=VK_SUCCESS; dataResult=result; advertisedCount=1; driverCalls=0;
        VulkanRendererContext c;
        c.pendingHostDisplayConfirmations.push_back({});
        c.pollHostDisplayConfirmations();
        assert(c.emitted==1 && c.hostDisplayConfirmed_==1);
        assert(c.hostDisplayTimingQueryFailureTotal_==0);
        assert(c.pendingHostDisplayConfirmations.empty() && driverCalls==2);
    }
    // Drivers may also return a usable count with VK_INCOMPLETE.
    countResult=VK_INCOMPLETE; dataResult=VK_SUCCESS;
    VulkanRendererContext partialCount;
    partialCount.pendingHostDisplayConfirmations.push_back({});
    partialCount.pollHostDisplayConfirmations();
    assert(partialCount.emitted==1 && partialCount.hostDisplayTimingQueryFailureTotal_==0);
    // Empty history must avoid a data query.
    countResult=VK_SUCCESS; advertisedCount=0; driverCalls=0;
    VulkanRendererContext empty;
    empty.pendingHostDisplayConfirmations.push_back({});
    empty.pollHostDisplayConfirmations();
    assert(driverCalls==1 && empty.emitted==0 && empty.pendingHostDisplayConfirmations.size()==1);
    for (bool failCount : {false, true}) {
        advertisedCount=1; countResult=failCount ? -4 : VK_SUCCESS; dataResult=-4;
        VulkanRendererContext c;
        c.pendingHostDisplayConfirmations.push_back({});
        c.pollHostDisplayConfirmations();
        assert(c.hostDisplayTimingQueryFailureTotal_==1 && c.emitted==0);
        assert(c.pendingHostDisplayConfirmations.size()==1);
    }
}
"""
with tempfile.TemporaryDirectory() as directory:
    cpp = Path(directory) / "test.cpp"
    binary = Path(directory) / "test"
    cpp.write_text(stub + production + cases)
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    str(cpp), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print("PASS: success, partial data, partial count, empty history, count/data errors")

# Compile the actual metric stream expression and check its machine-readable output.
metric_source = Path(sys.argv[2]) if len(sys.argv) > 2 else (
    source.parent.parent / "lsfg-vk-android/src/context.cpp"
)
metric_line = next(line for line in metric_source.read_text().splitlines()
                   if '<< " source_deadline_error_avg_ms="' in line)
with tempfile.TemporaryDirectory() as directory:
    cpp = Path(directory) / "metric.cpp"
    binary = Path(directory) / "metric"
    cpp.write_text('#include <iostream>\nint main() { double sourceDeadlineErrorAvgMs=2.5; '
                   + 'std::cout ' + metric_line.strip() + '; }')
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    str(cpp), "-o", str(binary)], check=True)
    output = subprocess.check_output([str(binary)], text=True)
    assert output.split() == ["source_deadline_error_avg_ms=2.5"], output
print("PASS: source deadline average has one key with a numeric value")
