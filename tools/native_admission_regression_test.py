"""Exercise actual Native admission with bounded paced source-tail overlap."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "app/src/main/cpp/winlator"
context = (HOST/"VulkanRendererContext.cpp").read_text()
header = (HOST/"VulkanRendererContext.h").read_text()

def function(source,signature):
    start = source.index(signature)
    opening = source.index("{",start)
    depth,end = 1,opening+1
    while depth:
        depth += (source[end]=="{")-(source[end]=="}")
        end += 1
    return source[start:end]

constants = "\n".join(re.search(rf"static constexpr uint32_t {name}\s*=\s*[^;]+;",header).group()
    for name in ("MIN_HOST_DELIVERY_QUEUE_CAPACITY","MAX_HOST_DELIVERY_QUEUE_CAPACITY",
                 "MAX_NATIVE_HOST_PRESENT_QUEUE_DEPTH"))
admission = function(context,"uint32_t VulkanRendererContext::nativeHostSyntheticAdmissionCapacity(")
temporal = function(context,"uint32_t VulkanRendererContext::nativeTemporalGenerationCapacity(")
presenter = function(context,"void VulkanRendererContext::hostPresenterLoop(")
tracking_start = presenter.index("            hostPresenterBusy_.store(true,")
tracking_end = presenter.index("        }\n        hostPresenterSpaceCv_",tracking_start)
tracking = presenter[tracking_start:tracking_end]
retire_start = presenter.index("            hostPresenterBusy_.store(false,")
retire_end = presenter.index("            hostPresentCompletionPending_",retire_start)
retire = presenter[retire_start:retire_end]
code = r'''
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <mutex>
#include <string>
#include <vector>
constexpr unsigned VKR_LSFG_MAX_GENERATIONS=3;
constexpr int VK_PRESENT_MODE_MAILBOX_KHR=1,VK_PRESENT_MODE_FIFO_KHR=2;
''' + constants + r'''
uint64_t nowNs=1000000000ULL;
uint64_t monotonicTimeNs() { return nowNs; }
uint64_t rollingPercentileNs(const std::vector<uint64_t>& samples,int percentile) {
    return samples.at(percentile==50 ? 0 : 1);
}
struct LsfgFrameProvenance {
    bool nativeImplementation=true,uniqueDelivery=true,valid=true;
    unsigned kind=0;
    uint64_t desiredPresentTimeNs=0;
};
struct HostDesiredPresentDecision {
    uint64_t submittedDesiredPresentTimeNs=0,provenanceDesiredPresentTimeNs=0;
    uint64_t refreshPeriodNs=8333333ULL;
    const char* fallbackReason="accepted";
};
struct PendingHostPresent {
    uint64_t enqueuedAtNs=0;
    HostDesiredPresentDecision desiredDecision{};
    std::vector<LsfgFrameProvenance> frameProvenance{{}};
    bool hasUniqueLsfgDelivery=true;
    int presentMode=VK_PRESENT_MODE_FIFO_KHR;
};
struct VulkanRendererContext {
    std::string nativeLastAdmissionReason_="none";
    bool hostAsyncPresenterActive_=true;
    std::mutex hostPresenterMutex_;
    std::deque<PendingHostPresent> pendingHostPresents_;
    std::atomic<bool> hostPresenterBusy_{false};
    uint64_t hostPresenterSourceDesiredNs_=0;
    std::atomic<float> framegenSourceFps_{30.f};
    // Real logged median0.3ms/p95~14.8ms includes presentation-engine tails.
    std::vector<uint64_t> hostPresentLatencySamplesNs_{300000ULL,14800000ULL};
    uint64_t nativeTimelineSourceIntervalNs_=33333333ULL,hostRefreshPeriodNs_=8333333ULL;
    uint64_t nativeLastAdmissionP50PresentNs_=0,nativeLastAdmissionP95PresentNs_=0;
    uint64_t nativeLastAdmissionServiceEstimateNs_=0,nativeLastAdmissionSourceIntervalNs_=0;
    uint32_t nativeTemporalGenerationCapacity() const;
    uint32_t nativeHostSyntheticAdmissionCapacity();
    void track([[maybe_unused]] const PendingHostPresent& present) {
        std::lock_guard<std::mutex> lock(hostPresenterMutex_);
''' + tracking + r'''
    }
    void retire() {
        std::lock_guard<std::mutex> lock(hostPresenterMutex_);
''' + retire + r'''
    }
    void previousSource(uint64_t age,uint64_t untilDeadline) {
        PendingHostPresent previous;previous.enqueuedAtNs=nowNs-age;
        previous.desiredDecision.provenanceDesiredPresentTimeNs=nowNs+untilDeadline;
        previous.desiredDecision.submittedDesiredPresentTimeNs=nowNs+untilDeadline;
        previous.frameProvenance.front().desiredPresentTimeNs=nowNs+untilDeadline;
        pendingHostPresents_.push_back(previous);
    }
};
''' + temporal + admission + r'''
void require(bool ok,const char* message) {
    if(!ok) { std::fprintf(stderr,"FAIL: %s\n",message); std::abort(); }
}
int main(int argc,char** argv) {
    require(argc==2,"select admission regression");const std::string test=argv[1];
    if(test=="busy") {
        VulkanRendererContext c;c.hostPresenterBusy_=true;
        c.hostPresentLatencySamplesNs_={300000ULL,650000ULL};
        require(c.nativeHostSyntheticAdmissionCapacity()==3,"cheap previous source tail retains full4x admission");
    } else if(test=="busy-paced") {
        VulkanRendererContext c;c.hostPresenterBusy_=true;
        c.hostPresenterSourceDesiredNs_=nowNs+1000000ULL;
        require(c.nativeHostSyntheticAdmissionCapacity()==3,"near-deadline active source uses residual work not full tail");
    } else if(test=="paced") {
        for(int mode: {VK_PRESENT_MODE_FIFO_KHR,VK_PRESENT_MODE_MAILBOX_KHR}) {
            VulkanRendererContext c;c.previousSource(32000000ULL,1000000ULL);
            c.pendingHostPresents_.front().presentMode=mode;
            const auto capacity=c.nativeHostSyntheticAdmissionCapacity();
            require(capacity>=2,"paced previous source retains complete3x admission");
            require(capacity==3,"paced previous source retains complete4x admission");
        }
    } else if(test=="backlog") {
        VulkanRendererContext c;
        for(unsigned i=0;i<MAX_NATIVE_HOST_PRESENT_QUEUE_DEPTH;++i)
            c.previousSource(100000000ULL,100000000ULL);
        c.hostPresenterBusy_=true;
        require(c.nativeHostSyntheticAdmissionCapacity()==0,"real full backlog remains bounded");
        VulkanRendererContext stale;stale.previousSource(100000000ULL,0);
        stale.pendingHostPresents_.front().desiredDecision={};
        stale.pendingHostPresents_.front().frameProvenance.front().desiredPresentTimeNs=0;
        require(stale.nativeHostSyntheticAdmissionCapacity()==0,"old unpaced work retains stale admission guard");
    } else if(test=="tracking") {
        VulkanRendererContext c;PendingHostPresent source;
        source.desiredDecision.submittedDesiredPresentTimeNs=nowNs+1000000ULL;
        c.track(source);
        require(c.hostPresenterBusy_ && c.hostPresenterSourceDesiredNs_==nowNs+1000000ULL,
                "presenter captures active validated source deadline under its mutex");
        c.retire();
        require(!c.hostPresenterBusy_ && c.hostPresenterSourceDesiredNs_==0,
                "presenter clears busy source deadline on completion");
        c.track(source);source.frameProvenance.front().kind=1;c.track(source);
        require(c.hostPresenterSourceDesiredNs_==0,"synthetic present clears previous source deadline");
        source.frameProvenance.front().kind=0;source.frameProvenance.front().valid=false;c.track(source);
        require(c.hostPresenterSourceDesiredNs_==0,"invalid provenance cannot supply source deadline");
        source.frameProvenance.front().valid=true;
        source.desiredDecision.submittedDesiredPresentTimeNs=0;
        source.desiredDecision.provenanceDesiredPresentTimeNs=nowNs+1000000ULL;c.track(source);
        require(c.hostPresenterSourceDesiredNs_==0,"unvalidated source intent does not bypass backlog guards");
    } else if(test=="temporal") {
        VulkanRendererContext c;c.hostAsyncPresenterActive_=false;
        require(c.nativeHostSyntheticAdmissionCapacity()==3,"30Hz to120Hz has3 synthetic slots");
        c.hostRefreshPeriodNs_=11111111ULL;
        require(c.nativeHostSyntheticAdmissionCapacity()==2,"30Hz to90Hz has2 synthetic slots");
        c.hostRefreshPeriodNs_=16666667ULL;
        require(c.nativeHostSyntheticAdmissionCapacity()==1,"30Hz to60Hz has1 synthetic slot");
        c.nativeTimelineSourceIntervalNs_=16666667ULL;
        require(c.nativeHostSyntheticAdmissionCapacity()==0,"source at refresh has no synthetic slot");
    } else { require(false,"unknown regression"); }
}
'''
tests = sys.argv[1:] or ["busy","busy-paced","paced","backlog","tracking","temporal"]
with tempfile.TemporaryDirectory(prefix="native-admission-") as directory:
    cpp,binary = Path(directory)/"test.cpp",Path(directory)/"test"
    cpp.write_text(code)
    subprocess.run(["g++","-std=c++20","-Wall","-Wextra","-Werror",str(cpp),"-o",str(binary)],check=True)
    failed = []
    for test in tests:
        result = subprocess.run([str(binary),test])
        print(f"{'PASS' if result.returncode==0 else 'FAIL'}: {test}",flush=True)
        if result.returncode != 0: failed.append(test)
    sys.exit(1 if failed else 0)
