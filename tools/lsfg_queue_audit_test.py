"""Run renderer queue policy, fence retirement and presenter paths with fake Vulkan.

Only the external Vulkan boundary is simulated; queue/thread/timeline code comes
from the production source. This cannot establish scanout or device latency.
"""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / 'app/src/main/cpp/winlator'
source = (HOST / 'VulkanRendererContext.cpp').read_text()
header = (HOST / 'VulkanRendererContext.h').read_text()


def function(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


names = ['BASE_FRAMES_IN_FLIGHT', 'MAX_FRAMES_IN_FLIGHT',
         'MAX_BUFFERED_GPU_SUBMISSIONS', 'SMOOTH_PRESENT_STALL_NS',
         'SMOOTH_PRESENT_STALL_STRIKES', 'MIN_HOST_DELIVERY_QUEUE_CAPACITY',
         'MAX_HOST_DELIVERY_QUEUE_CAPACITY', 'MAX_HOST_PRESENT_QUEUE_DEPTH',
         'MAX_NATIVE_HOST_PRESENT_QUEUE_DEPTH']
constants = '\n'.join(re.search(rf'static constexpr \w+ {name}\s*=\s*[^;]+;', header).group()
                      for name in names)
timeline = function(header, 'struct RendererSubmissionTimeline') + ';'
methods = ['uint32_t VulkanRendererContext::effectiveFrameQueueTarget()',
           'uint32_t VulkanRendererContext::hostDeliveryQueueCapacity()',
           'uint32_t VulkanRendererContext::activeFrameSlotCount()',
           'uint32_t VulkanRendererContext::countOutstandingFrameSubmissions(',
           'void VulkanRendererContext::enforceFrameQueueSubmissionBudget(',
           'void VulkanRendererContext::updateSmoothQueuePressure(',
           'void VulkanRendererContext::drainFrameQueueSubmissions(',
           'void VulkanRendererContext::createFrameQueuePresentSemaphores()',
           'void VulkanRendererContext::retireFrameQueuePresentSemaphores()',
           'void VulkanRendererContext::destroyRetiredFrameQueuePresentSemaphores()',
           'VkResult VulkanRendererContext::enqueueHostPresent(',
           'void VulkanRendererContext::hostPresenterLoop()',
           'void VulkanRendererContext::processHostPresentCompletions()',
           'void VulkanRendererContext::drainHostPresenter(',
           'void VulkanRendererContext::setLsfgFrameQueue(']
code = r'''
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cinttypes>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <shared_mutex>
#include <stdexcept>
#include <thread>
#include <vector>
#include <cstdio>
using VkResult=int; using VkSemaphore=uint64_t;
constexpr unsigned VKR_LSFG_MAX_GENERATIONS=3;
constexpr int VK_SUCCESS=0,VK_NOT_READY=1,VK_TRUE=1,VK_NULL_HANDLE=0;
constexpr int VK_PRESENT_MODE_FIFO_KHR=2,VK_PRESENT_MODE_MAILBOX_KHR=1;
constexpr int ANDROID_LOG_DEBUG=0,ANDROID_LOG_INFO=1,ANDROID_LOG_WARN=2;
constexpr int VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO=1;
void __android_log_print(int,const char*,const char*,...) {}
struct VkSemaphoreCreateInfo { int sType=0; };
uint64_t monotonicTimeNs() { return 1000000000ULL; }
struct LsfgFrameProvenance {
    bool valid=true,nativeImplementation=true,uniqueDelivery=true;
    uint32_t kind=0; uint64_t sourceIndex=0;
};
struct HostDesiredPresentDecision {
    uint64_t submittedDesiredPresentTimeNs=0,provenanceDesiredPresentTimeNs=0,refreshPeriodNs=0;
};
uint64_t mailboxDispatchDelayNs(const HostDesiredPresentDecision&,uint64_t) { return 0; }
struct PendingHostPresent {
    uint64_t id=0,enqueuedAtNs=0,hostPresentEnqueueWaitNs=0,presenterQueueAgeNs=0;
    uint64_t mailboxDispatchDelayNs=0,swapchainGeneration=0;
    uint32_t hostPresentQueueDepth=0; int presentMode=VK_PRESENT_MODE_FIFO_KHR;
    HostDesiredPresentDecision desiredDecision{};
    std::vector<LsfgFrameProvenance> frameProvenance{{}};
};
struct CompletedHostPresent { uint64_t id=0; };
struct VkrLsfg { bool rejected=true; unsigned evidenceResets=0; };
void vkr_lsfg_reset_density_evidence(VkrLsfg* runtime) {
    if(runtime) { runtime->rejected=false; ++runtime->evidenceResets; }
}
''' + constants + '\n' + timeline + r'''
struct VulkanRendererContext {
    VkrLsfg* lsfg=nullptr;
    bool nativeQueuePolicyResetPending_=false;
    unsigned pressureClears=0,presentationEvidenceResets=0;
    void clearFrameGenerationPressure() { ++pressureClears; }
    void resetNativePresentationEvidence() { ++presentationEvidenceResets; }
    std::atomic<bool> lsfgFrameQueueEnabled_{true};
    std::atomic<uint32_t> lsfgFrameQueueTarget_{2};
    mutable std::atomic<bool> frameQueueSmoothFifoFallback_{false};
    std::atomic<bool> frameQueueSmoothRuntimeSuppressed_{false};
    std::atomic<uint32_t> frameQueueSmoothPressureStrikes_{0},frameQueueMaxGpuOutstanding_{0};
    std::atomic<uint64_t> frameQueueRetirementWaitTotal_{0},frameQueueRetirementWaitNsTotal_{0};
    std::atomic<uint64_t> frameQueueConfigRequestSerial_{0},frameQueueTelemetryEpoch_{0},frameQueuePresentedTotal_{0};
    RendererSubmissionTimeline submissionTimeline;
    std::array<int,MAX_FRAMES_IN_FLIGHT> inFlightFences{0,1,2};
    std::array<bool,MAX_FRAMES_IN_FLIGHT> fenceReady{};
    std::vector<int> waitedFences;
    int device=1,activePresentMode=VK_PRESENT_MODE_MAILBOX_KHR;
    std::shared_mutex frameMutex; std::mutex renderMutex;
    unsigned currentFrame=0,dropped=0;
    unsigned graphicsQueueFamilyIndex=0,graphicsQueueFamilyQueueCount=2,presentCapableQueueFamilyCount=1;
    bool alternatePresentQueueFamilyAvailable=false,hostSplitPresentQueueActive_=true;
    unsigned hostPresentQueueIndex_=1;
    std::vector<int> swapchainImages{0,1,2,3,4,5};
    std::vector<VkSemaphore> frameQueuePresentSems_,retiredFrameQueuePresentSems_,destroyed;
    uint64_t semaphoreNext=100; int createFailureAfter=-1;
    struct VkFake {
        VulkanRendererContext* owner;
        std::function<VkResult(int,int)> GetFenceStatus=[this](int,int f) { return owner->fenceReady.at(f)?VK_SUCCESS:VK_NOT_READY; };
        VkResult WaitForFences(int,unsigned,const int* f,int,uint64_t) {
            owner->waitedFences.push_back(*f);owner->fenceReady.at(*f)=true;return VK_SUCCESS;
        }
        VkResult CreateSemaphore(int,const VkSemaphoreCreateInfo*,void*,VkSemaphore* sem) {
            if(owner->createFailureAfter==0)return VK_NOT_READY;
            if(owner->createFailureAfter>0)--owner->createFailureAfter;
            *sem=owner->semaphoreNext++;return VK_SUCCESS;
        }
        void DestroySemaphore(int,VkSemaphore sem,void*) {owner->destroyed.push_back(sem);}
    } vk_{this};
    bool hostAsyncPresenterActive_=true;
    std::atomic<bool> hostPresenterRunning_{false},hostPresenterBusy_{false},hostPresentCompletionPending_{false};
    uint64_t hostPresenterSourceDesiredNs_=0;
    std::atomic<uint64_t> hostPresentEnqueueWaitNsTotal_{0},hostPresentEnqueueWaitCount_{0};
    std::atomic<uint64_t> nativeHostWaitNsTotal_{0},nativeHostWaitSamples_{0};
    std::atomic<uint32_t> hostPresentQueueHighWater_{0};
    std::atomic<uint64_t> nativeGeneratedStale_{0},nativeGeneratedSuperseded_{0},framegenSourceFrames{0};
    std::thread hostPresenterThread_;
    std::mutex hostPresenterMutex_,deliveredMutex;
    std::condition_variable hostPresenterCv_,hostPresenterSpaceCv_,hostPresenterDrainCv_,dirtyCV;
    std::deque<PendingHostPresent> pendingHostPresents_;
    std::deque<CompletedHostPresent> completedHostPresents_;
    std::vector<uint64_t> delivered,finalized;
    void completeObservedFence(int f) { submissionTimeline.completeFrame(f); }
    void resetFrameQueueTelemetry() {++frameQueueTelemetryEpoch_;frameQueueMaxGpuOutstanding_=0;}
    void dropQueuedLsfgHostDeliveries(const char*) {++dropped;}
    CompletedHostPresent executeHostPresent(PendingHostPresent present) {
        std::lock_guard<std::mutex> lock(deliveredMutex);delivered.push_back(present.id);
        return {present.id};
    }
    VkResult presentHostFrame(PendingHostPresent p) {finalizeHostPresent(executeHostPresent(std::move(p)));return VK_SUCCESS;}
    void finalizeHostPresent(CompletedHostPresent p) {finalized.push_back(p.id);}
    uint32_t effectiveFrameQueueTarget() const;
    uint32_t hostDeliveryQueueCapacity() const;
    uint32_t activeFrameSlotCount() const;
    uint32_t countOutstandingFrameSubmissions(bool);
    void enforceFrameQueueSubmissionBudget(uint32_t);
    void updateSmoothQueuePressure(uint64_t);
    void drainFrameQueueSubmissions(const char*);
    void createFrameQueuePresentSemaphores();
    void retireFrameQueuePresentSemaphores();
    void destroyRetiredFrameQueuePresentSemaphores();
    VkResult enqueueHostPresent(PendingHostPresent);
    void hostPresenterLoop();
    void processHostPresentCompletions();
    void drainHostPresenter(const char*);
    void setLsfgFrameQueue(bool,uint32_t);
};
''' + '\n'.join(function(source, signature) for signature in methods) + r'''

void queuePolicy() {
    unsigned trials=0;
    for(int mode:{VK_PRESENT_MODE_FIFO_KHR,VK_PRESENT_MODE_MAILBOX_KHR}) {
        for(bool enabled:{false,true})for(unsigned depth=0;depth<3;++depth) {
            VulkanRendererContext c;c.activePresentMode=mode;c.setLsfgFrameQueue(enabled,depth);
            const unsigned target=!enabled?0:depth==2&&mode==VK_PRESENT_MODE_FIFO_KHR?1:depth;
            assert(c.effectiveFrameQueueTarget()==target);
            assert(c.hostDeliveryQueueCapacity()==4+(enabled?depth:0));
            assert(c.activeFrameSlotCount()==(enabled?target+1:BASE_FRAMES_IN_FLIGHT));
            // Full 2x/3x/4x bursts and adaptive collapse/recovery cannot change
            // retention policy or shrink the next source's protected capacity.
            for(unsigned cycle=0;cycle<2000;++cycle)for(unsigned generated:{1,2,3,0,3,1}) {
                assert(c.hostDeliveryQueueCapacity()>=generated+1);
                assert(c.effectiveFrameQueueTarget()==target);++trials;
            }
        }
    }
    std::printf("PASS: policy matrix (%u burst-retention observations)\n",trials);
}
void nativeQueueEvidenceBoundary() {
    VulkanRendererContext c;VkrLsfg runtime;c.lsfg=&runtime;
    c.setLsfgFrameQueue(true,2);
    assert(runtime.rejected && runtime.evidenceResets==0);
    assert(c.pressureClears==0 && c.presentationEvidenceResets==0);
    assert(!c.nativeQueuePolicyResetPending_);
    c.setLsfgFrameQueue(true,1);
    assert(!runtime.rejected && runtime.evidenceResets==1);
    assert(c.pressureClears==1 && c.presentationEvidenceResets==1);
    assert(c.nativeQueuePolicyResetPending_);
    runtime.rejected=true;c.nativeQueuePolicyResetPending_=false;
    c.setLsfgFrameQueue(true,1);
    assert(runtime.rejected && runtime.evidenceResets==1);
    assert(c.pressureClears==1 && c.presentationEvidenceResets==1);
    assert(!c.nativeQueuePolicyResetPending_);
    c.setLsfgFrameQueue(false,1);
    assert(!runtime.rejected && runtime.evidenceResets==2);
    assert(c.pressureClears==2 && c.presentationEvidenceResets==2);
    assert(c.nativeQueuePolicyResetPending_);
    std::puts("PASS: real queue policy changes reset Native density evidence; identical requests preserve hold");
}
void pressureRecovery() {
    VulkanRendererContext c;
    c.updateSmoothQueuePressure(SMOOTH_PRESENT_STALL_NS);assert(c.effectiveFrameQueueTarget()==2);
    c.updateSmoothQueuePressure(0);c.updateSmoothQueuePressure(SMOOTH_PRESENT_STALL_NS);
    assert(c.effectiveFrameQueueTarget()==2); // isolated stalls do not accumulate
    c.updateSmoothQueuePressure(SMOOTH_PRESENT_STALL_NS);assert(c.effectiveFrameQueueTarget()==1);
    c.setLsfgFrameQueue(true,2); // repeated settings publication cannot undo fallback
    for(unsigned i=0;i<10000;++i)c.updateSmoothQueuePressure(0);
    assert(c.effectiveFrameQueueTarget()==1);
    c.setLsfgFrameQueue(true,1);c.setLsfgFrameQueue(true,2);
    assert(c.effectiveFrameQueueTarget()==2);
    c.activePresentMode=VK_PRESENT_MODE_FIFO_KHR;assert(c.effectiveFrameQueueTarget()==1);
    std::puts("PASS: pressure qualification, fallback persistence and explicit recovery");
}
void gpuBudget() {
    unsigned submissions=0;
    for(int mode:{VK_PRESENT_MODE_FIFO_KHR,VK_PRESENT_MODE_MAILBOX_KHR})for(bool enabled:{false,true})for(unsigned depth=0;depth<3;++depth) {
        VulkanRendererContext c;c.activePresentMode=mode;c.setLsfgFrameQueue(enabled,depth);
        for(unsigned step=0;step<20000;++step) {
            const unsigned slot=step%c.activeFrameSlotCount();
            // Real renderer waits for a reused frame slot before recording it;
            // model only that Vulkan boundary. Budget method and timeline are real.
            if(c.submissionTimeline.frameSubmissionSerial[slot]) {
                c.vk_.WaitForFences(c.device,1,&c.inFlightFences[slot],VK_TRUE,UINT64_MAX);
                c.completeObservedFence(slot);
            }
            c.enforceFrameQueueSubmissionBudget(c.effectiveFrameQueueTarget());
            c.fenceReady[slot]=false;
            c.submissionTimeline.submitFrame(slot,c.submissionTimeline.nextSubmissionSerial());
            assert(c.countOutstandingFrameSubmissions(false)<=MAX_BUFFERED_GPU_SUBMISSIONS);
            ++submissions;
        }
        c.drainFrameQueueSubmissions("audit-transition");
        assert(c.countOutstandingFrameSubmissions(false)==0);
        assert(c.submissionTimeline.completedSubmissionSerial==c.submissionTimeline.submittedSubmissionSerial);
    }
    VulkanRendererContext c;c.submissionTimeline.submitFrame(0,90);c.submissionTimeline.submitFrame(1,80);
    c.enforceFrameQueueSubmissionBudget(2);assert(c.waitedFences.front()==1);
    c.fenceReady[0]=true;assert(c.countOutstandingFrameSubmissions(true)==0);
    std::printf("PASS: GPU bound, oldest retirement and transition drain (%u submissions; fake fences)\n",submissions);
}
void semaphoreLifetime() {
    VulkanRendererContext c;c.createFrameQueuePresentSemaphores();
    assert(c.frameQueuePresentSems_.size()==c.swapchainImages.size());
    const auto original=c.frameQueuePresentSems_;
    c.createFrameQueuePresentSemaphores();assert(c.frameQueuePresentSems_==original);
    // GPU frame completion cannot destroy a semaphore still owned by WSI.
    c.submissionTimeline.submitFrame(0,1);c.completeObservedFence(0);
    assert(c.destroyed.empty());
    c.retireFrameQueuePresentSemaphores();assert(c.destroyed.empty());
    assert(c.retiredFrameQueuePresentSems_==original);
    c.createFrameQueuePresentSemaphores();assert(c.frameQueuePresentSems_!=original);
    c.destroyRetiredFrameQueuePresentSemaphores();assert(c.destroyed==original);
    c.destroyRetiredFrameQueuePresentSemaphores();assert(c.destroyed==original);
    VulkanRendererContext failure;failure.createFailureAfter=3;
    bool threw=false;try{failure.createFrameQueuePresentSemaphores();}catch(const std::runtime_error&){threw=true;}
    assert(threw&&failure.destroyed.size()==3&&failure.frameQueuePresentSems_.empty());
    std::puts("PASS: per-image semaphore lifetime, retirement and partial-allocation cleanup");
}
void presenterOrder() {
    unsigned total=0;
    for(bool native:{false,true})for(int mode:{VK_PRESENT_MODE_FIFO_KHR,VK_PRESENT_MODE_MAILBOX_KHR})
        for(bool enabled:{false,true})for(unsigned depth=0;depth<3;++depth) {
        if(!enabled&&depth!=0)continue;
        VulkanRendererContext c;c.activePresentMode=mode;c.setLsfgFrameQueue(enabled,depth);
        c.hostPresenterRunning_=true;
        c.hostPresenterThread_=std::thread(&VulkanRendererContext::hostPresenterLoop,&c);
        uint64_t id=0;
        // Exercise full 2/3/4x bursts, zero-demand collapse and recovery on the
        // production enqueue/worker path, separately from GPU budget policy.
        for(unsigned cycle=0;cycle<300;++cycle)for(unsigned generated:{1,2,3,0,3,1}) {
            for(unsigned kind=0;kind<=generated;++kind) {
                PendingHostPresent p;p.id=++id;p.presentMode=mode;
                p.frameProvenance[0].nativeImplementation=native;
                p.frameProvenance[0].kind=kind==0?0:1;
                assert(c.enqueueHostPresent(std::move(p))==VK_SUCCESS);
            }
            if(cycle%13==0)c.processHostPresentCompletions();
        }
        c.drainHostPresenter("audit-transition");
        assert(c.delivered.size()==id&&c.finalized.size()==id);
        for(unsigned i=0;i<id;++i)assert(c.delivered[i]==i+1&&c.finalized[i]==i+1);
        const auto limit=native?MAX_NATIVE_HOST_PRESENT_QUEUE_DEPTH:MAX_HOST_PRESENT_QUEUE_DEPTH;
        assert(c.hostPresentQueueHighWater_<=limit);
        // Paused source does not create extra delivery or leave the worker busy.
        assert(c.pendingHostPresents_.empty()&&!c.hostPresenterBusy_);
        c.hostPresenterRunning_=false;c.hostPresenterCv_.notify_all();c.hostPresenterThread_.join();
        PendingHostPresent p;p.id=id+1;p.frameProvenance[0].nativeImplementation=native;
        c.enqueueHostPresent(std::move(p)); // stopped worker executes synchronously
        assert(c.finalized.back()==id+1&&c.delivered.back()==id+1);total+=id+1;
    }
    std::printf("PASS: ordered once-only Off/0/1/2-depth Native/Legacy worker delivery, drain and stop fallback (%u deliveries)\n",total);
}
int main(){nativeQueueEvidenceBoundary();queuePolicy();pressureRecovery();gpuBudget();semaphoreLifetime();presenterOrder();}
'''
with tempfile.TemporaryDirectory(prefix='lsfg-queue-audit-') as directory:
    cpp, binary = Path(directory) / 'audit.cpp', Path(directory) / 'audit'
    cpp.write_text(code)
    subprocess.run(['g++', '-std=c++20', '-pthread', '-Wall', '-Wextra', '-Werror',
                    str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=30)
