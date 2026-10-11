"""Behavioral virtual-clock audit of the actual host slot validator.

This checks intent preservation and simulated display slots, not a physical panel.
"""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text()
def function(signature):
    start = SOURCE.index(signature)
    opening = SOURCE.index('{', start)
    while ';' in SOURCE[start:opening]:
        start = SOURCE.index(signature, start + len(signature))
        opening = SOURCE.index('{', start)
    depth, end = 1, opening + 1
    while depth:
        depth += (SOURCE[end] == '{') - (SOURCE[end] == '}')
        end += 1
    return SOURCE[start:end]
code = r'''
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>
uint64_t nowNs=1000000000ULL;
uint64_t monotonicTimeNs() { return nowNs; }
constexpr uint64_t MAX_HOST_TEMPORAL_STALE_NS=1000000000ULL;
constexpr uint64_t kMaxHostFutureDesiredPresentNs=1000000000ULL;
struct LsfgFrameProvenance {
 bool uniqueDelivery=true,nativeImplementation=true;
 unsigned kind=1;
 uint64_t desiredPresentTimeNs=0,outputSlotIntendedPresentTimeNs=0;
};
struct HostDesiredPresentDecision {
 uint64_t provenanceDesiredPresentTimeNs=0,refreshPeriodNs=0,desiredStaleByNs=0;
 uint64_t submittedDesiredPresentTimeNs=0,desiredFutureByNs=0,phaseAdvanceCycles=0,phaseAdvanceNs=0;
 bool temporalBacklog=false;
 const char* fallbackReason="none";
};
struct VulkanRendererContext {
 uint64_t hostRefreshPeriodNs_=8333333ULL,lastAcceptedDesiredPresentTimeNs_=0;
 uint64_t hostTemporalBacklogTotal_=0,hostPhaseRescheduledTotal_=0;
 bool hostGoogleDisplayTimingEnabled=true;
 HostDesiredPresentDecision validatedHostDesiredPresentTime(const std::vector<LsfgFrameProvenance>&);
};
''' + function('uint64_t advanceDesiredPresentPhase(') + function('HostDesiredPresentDecision VulkanRendererContext::validatedHostDesiredPresentTime(') + r'''
void require(bool okay,const char* why) { if(!okay) { std::fprintf(stderr,"FAIL: %s\n",why); std::exit(1); } }
int main() {
 uint64_t healthy=0,stalled=0;
 for(unsigned multiplier: {2U,3U,4U}) for(unsigned mode: {0U,1U}) {
   VulkanRendererContext c;
   for(unsigned cycle=0;cycle<2400;++cycle) {
     const uint64_t base=1000000000ULL+uint64_t(cycle)*multiplier*c.hostRefreshPeriodNs_;
     nowNs=base;
     for(unsigned slot=1;slot<multiplier;++slot) {
       LsfgFrameProvenance p;p.desiredPresentTimeNs=base+slot*c.hostRefreshPeriodNs_;
       auto d=c.validatedHostDesiredPresentTime({p});
       require(d.submittedDesiredPresentTimeNs==p.desiredPresentTimeNs,"healthy generated slot keeps source phase");
       ++healthy;
     }
     LsfgFrameProvenance source;source.kind=0;source.desiredPresentTimeNs=base+multiplier*c.hostRefreshPeriodNs_;
     require(c.validatedHostDesiredPresentTime({source}).submittedDesiredPresentTimeNs==source.desiredPresentTimeNs,"source deadline follows generated batch");
   }
   (void)mode; // Both host present modes use this same validation policy.
 }
 for(unsigned multiplier: {3U,4U}) for(unsigned cycle=0;cycle<2000;++cycle) {
   VulkanRendererContext c;
   uint64_t base=1000000000ULL+uint64_t(cycle)*multiplier*c.hostRefreshPeriodNs_;
   nowNs=base+c.hostRefreshPeriodNs_;
   LsfgFrameProvenance late;late.desiredPresentTimeNs=base+c.hostRefreshPeriodNs_;
   auto missed=c.validatedHostDesiredPresentTime({late});
   require(missed.submittedDesiredPresentTimeNs==0,"missed Native slot must not be retimed into later source interval");
   require(missed.provenanceDesiredPresentTimeNs==late.desiredPresentTimeNs,"missed slot retains original intent for accounting");
   require(c.lastAcceptedDesiredPresentTimeNs_==0,"missed slot cannot accrue phase debt");
   LsfgFrameProvenance survivor;survivor.desiredPresentTimeNs=base+2*c.hostRefreshPeriodNs_;
   require(c.validatedHostDesiredPresentTime({survivor}).submittedDesiredPresentTimeNs==survivor.desiredPresentTimeNs,"later surviving slot retains phase after stall");
   ++stalled;
 }
 std::printf("PASS: %llu healthy slots and %llu stalled batches\n",(unsigned long long)healthy,(unsigned long long)stalled);
}
'''
with tempfile.TemporaryDirectory(prefix='lsfg-cadence-') as directory:
    cpp, binary = Path(directory)/'audit.cpp', Path(directory)/'audit'
    cpp.write_text(code)
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Werror',str(cpp),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

# Exercise actual evidence accumulation, physical accounting and public readout.
header = (ROOT / 'app/src/main/cpp/winlator/VulkanRendererContext.h').read_text()
fields = header[header.index('    std::deque<uint64_t> nativeSourceWsiEventNs_;'):
                header.index('\n    bool nativeSwapchainTransferSupported_')]
physical_fields = header[header.index('    uint64_t uniquePhysicalPresent_ = 0;'):
                         header.index('    uint64_t lastAcceptedDesiredPresentTimeNs_')]
emit = function('void VulkanRendererContext::emitHostDisplayConfirmation(')
emit = emit[:emit.index('        std::vector<uint64_t> sortedCadenceErrors')] + '''
        (void)uniquePhysicalIntervalNs;
    }
    (void)presentMarginValid; (void)confirmationAgeNs; (void)reason;
}
'''
pressure_code = r'''
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <deque>
#include <string>
#include <shared_mutex>
#include <unordered_set>
#include <vector>
#define __android_log_print(...) ((void)0)
uint64_t nowNs=2000000000ULL;
uint64_t monotonicTimeNs() {return nowNs;}
constexpr uint64_t kMaxSanePresentMarginNs=1000000000ULL;
enum class HostDisplayConfirmationBackend { WsiAccepted, GoogleDisplayTiming, PresentWait };
struct LsfgFrameProvenance {
 bool valid=true,nativeImplementation=true,uniqueDelivery=true;
 unsigned kind=1;
 uint64_t transactionId=1,runtimeSessionId=1,contextEpoch=1,configurationRevision=1;
 uint64_t deliveryId=1,desiredPresentTimeNs=0;
};
struct HostDisplayConfirmation {
 std::vector<LsfgFrameProvenance> frameProvenance{{}};
 HostDisplayConfirmationBackend backend=HostDisplayConfirmationBackend::GoogleDisplayTiming;
 uint64_t actualPresentTimeNs=0,enqueuedAtNs=0,presentMarginRawNs=0;
 uint64_t swapchainGeneration=1,hostPresentId=1;
 uint64_t provenanceDesiredPresentTimeNs=0,submittedDesiredPresentTimeNs=0;
};
struct VkrLsfgPresentationPressure {
 bool confirmation_available=false,pressure_active=false,wsi_recovery_active=false,source_delivery_healthy=false;
 float generated_delivery_efficiency=0,source_delivery_efficiency=0,confirmation_timeout_rate=0,output_target_deficit_ratio=0;
};
''' + function('uint64_t rollingPercentileNs(') + function('void insertPresentationEvidence(') + function('double timedCadenceFps(') + function('void recordTimedPhysicalCadence(') + function('void trimEvidence(') + r'''
struct VulkanRendererContext {
''' + fields + physical_fields + r'''
 std::atomic<float> nativeConfirmedOutputFpsForGovernor_{-1.f};
 std::atomic<uint64_t> nativeConfirmedOutputSampleNs_{0},nativeGeneratedLateDisplayTotal_{0};
 std::atomic<uint64_t> nativeGenerationOpportunityCount_{0};
 std::atomic<uint64_t> hostUniqueDisplayConfirmedEpoch_{0},nativeGeneratedDisplayConfirmed_{0};
 std::atomic<uint64_t> nativeGeneratedDisplayConfirmedEpoch_{0},nativeSourceDisplayConfirmed_{0},nativeSourceDisplayConfirmedEpoch_{0};
 std::deque<uint64_t> hostPresentLatencySamplesNs_,physicalCadenceErrorsNs_,scheduledCadenceErrorsNs_;
 uint64_t hostRefreshPeriodNs_=8333333ULL,hostSwapchainGeneration_=1;
 uint64_t nativeRuntimeSessionId_=1,nativeLsfgContextEpoch_=1,lsfgBackendTransitionId_=1;
 uint64_t nativeFeedbackMinConfigRevision_=1,framegenConfigRevision=1;
 uint64_t generatedPhysicalUnknown_=0,sourcePhysicalUnknown_=0;
 unsigned framegenTargetRate=120,framegenMultiplier=4,hostSuboptimalConsecutive_=0;
 double nativeSourceConfirmedFps_=0,nativeGeneratedConfirmedFps_=0,nativeOutputConfirmedFps_=0;
 mutable std::shared_mutex frameMutex;
 bool supported=true;
 bool isFrameGenerationSupported() const {return supported;}
 HostDisplayConfirmationBackend selectHostDisplayConfirmationBackend() const { return HostDisplayConfirmationBackend::GoogleDisplayTiming; }
 void resetNativePresentationEvidence();
 bool nativeFeedbackIdentityCurrent(const LsfgFrameProvenance&) const;
 void recordNativePresentationEvidence(bool,bool,bool,bool,bool,uint64_t,bool timed=false);
 void updateNativePresentationPressure(uint64_t);
 void emitHostDisplayConfirmation(const HostDisplayConfirmation&,bool,bool,const char*);
 float getNativeConfirmedOutputFps() const;
 uint64_t getGenerationOpportunityCount() const;
};
''' + function('void VulkanRendererContext::resetNativePresentationEvidence(') + function('bool VulkanRendererContext::nativeFeedbackIdentityCurrent(') + function('void VulkanRendererContext::recordNativePresentationEvidence(') + function('void VulkanRendererContext::updateNativePresentationPressure(') + emit + function('float VulkanRendererContext::getNativeConfirmedOutputFps(') + function('uint64_t VulkanRendererContext::getGenerationOpportunityCount(') + r'''
void require(bool okay,const char* why) {if(!okay) {std::fprintf(stderr,"FAIL: %s\n",why);std::exit(1);}}
int main() {
 VulkanRendererContext c;
 require(c.getNativeConfirmedOutputFps()==-1,"fresh domain has unavailable FPS");
 HostDisplayConfirmation p;p.frameProvenance[0].kind=0;
 c.emitHostDisplayConfirmation(p,true,false,"present-wait");
 require(c.uniquePhysicalPresent_==1 && c.timedUniquePhysicalPresent_==0,"untimed confirmation proves delivery but not cadence");
 require(c.getNativeConfirmedOutputFps()==-1,"receipt time must not fabricate physical FPS");
 c.resetNativePresentationEvidence();
 // Delayed batched receipts retain their actual display timestamps.
 for(unsigned slot=0;slot<120;++slot) {
   p.frameProvenance[0].kind=slot%4==3 ? 0 : 1;
   p.frameProvenance[0].deliveryId=2+slot;
   p.actualPresentTimeNs=1000000000ULL+uint64_t(slot)*8333333ULL;
   p.frameProvenance[0].desiredPresentTimeNs=p.actualPresentTimeNs;
   c.emitHostDisplayConfirmation(p,true,false,"actual-present-time");
 }
 require(std::abs(c.getNativeConfirmedOutputFps()-120.f)<.001,"delayed receipts report actual120Hz rather than poll burst rate");
 require(std::abs(timedCadenceFps(c.timedUniquePhysicalPresent_,c.firstUniquePhysicalPresentNs_,c.lastUniquePhysicalPresentNs_)-120)<.001,"physical FPS counts N-1 intervals");
 require(std::abs(c.nativeSourceConfirmedFps_-30)<.001,"source timed cadence is independent of generated delivery counts");
 const uint64_t unique=c.uniquePhysicalPresent_;
 c.emitHostDisplayConfirmation(p,true,false,"duplicate");
 require(c.uniquePhysicalPresent_==unique,"duplicate terminal verdict cannot inflate delivery or FPS");
 p.frameProvenance[0].deliveryId=900;p.actualPresentTimeNs=nowNs+1;
 c.emitHostDisplayConfirmation(p,true,false,"bad-future-clock");
 require(c.uniquePhysicalPresent_==unique,"future scanout timestamp cannot prove physical delivery");
 p.frameProvenance[0].deliveryId=901;p.actualPresentTimeNs=1900000000ULL;
 p.frameProvenance[0].runtimeSessionId=2;c.emitHostDisplayConfirmation(p,true,false,"retired-session");
 require(c.uniquePhysicalPresent_==unique,"retired runtime session cannot steer current cadence");
 p.frameProvenance[0].runtimeSessionId=1;
 p.frameProvenance[0].deliveryId=902;p.swapchainGeneration=2;
 c.emitHostDisplayConfirmation(p,true,false,"retired-swapchain");
 require(c.uniquePhysicalPresent_==unique,"retired swapchain cannot steer current cadence");
 // Ordered insertion tolerates driver timing arrays delivered out of order.
 std::deque<uint64_t> ordered;
 for(unsigned n=0;n<10000;++n) insertPresentationEvidence(ordered,10000-n);
 require(ordered.size()==1024 && std::is_sorted(ordered.begin(),ordered.end()),"out-of-order evidence remains chronological and bounded");
 nowNs=5000000000ULL;
 require(c.getNativeConfirmedOutputFps()==0,"known timed domain silence is measured zero");
 c.resetNativePresentationEvidence();
 require(c.getNativeConfirmedOutputFps()==-1,"scope reset invalidates timed domain");
 c.nativeGenerationOpportunityCount_=17;
 require(c.getGenerationOpportunityCount()==17,"opportunities survive evidence reset");
 std::puts("PASS: physical cadence, delayed receipts, untimed truth, scoped dedup, invalid clock, silence and reset");
}
'''
with tempfile.TemporaryDirectory(prefix='lsfg-evidence-') as directory:
    cpp, binary = Path(directory)/'audit.cpp', Path(directory)/'audit'
    cpp.write_text(pressure_code)
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Werror',str(cpp),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)

# Execute the actual teardown method with a live rendering reader and a blocked
# presenter worker. The mocks enforce Vulkan destruction ordering.
detach_code = r'''
#include <atomic>
#include <cassert>
#include <cstdint>
#include <future>
#include <mutex>
#include <shared_mutex>
#include <thread>
#define RLOG(...) ((void)0)
constexpr int VK_NULL_HANDLE=0;
void ANativeWindow_release(void*) {}
struct VulkanRendererContext {
 std::atomic<bool> surfaceDetached{false},nativePresentationSurfaceReady_{true};
 std::atomic<uint64_t> nativeGeneratedPresentedFrames_{8},presentedFrames{12};
 std::atomic<uint64_t> nativeGeneratedDisplayConfirmedEpoch_{8},nativeSourceDisplayConfirmedEpoch_{4};
 std::shared_mutex frameMutex;
 std::atomic<bool> reset{false},drainStarted{false},presenterFinished{false};
 int device=1,surface=1,instance=1; void* window=nullptr;
 struct CV {void notify_all() {}} dirtyCV;
 struct Dispatch {
   VulkanRendererContext* owner;
   void DeviceWaitIdle(int) {assert(owner->presenterFinished && owner->reset);}
   void DestroySurfaceKHR(int,int,void*) {assert(owner->presenterFinished);}
 } vk_{this};
 struct Timeline {void completeAllFrames() {}} submissionTimeline;
 void drainHostPresenter(const char*) {
   assert(!reset);drainStarted=true;
   while(!presenterFinished) std::this_thread::yield();
 }
 void resetNativePresentationTimeline(const char*) {assert(presenterFinished);reset=true;}
 void cleanupSwapchain() {assert(reset && presenterFinished);}
 void destroyRetiredFrameQueuePresentSemaphores() {assert(presenterFinished);}
 void detachSurface();
};
''' + function('void VulkanRendererContext::detachSurface(') + r'''
int main() {
 VulkanRendererContext c;
 std::shared_lock<std::shared_mutex> renderReader(c.frameMutex);
 auto worker=std::async(std::launch::async,[&] {
   while(!c.drainStarted) std::this_thread::yield();
   c.presenterFinished=true;
 });
 auto detach=std::async(std::launch::async,[&] {c.detachSurface();});
 while(!c.surfaceDetached) std::this_thread::yield();
 assert(!c.reset && !c.drainStarted);
 assert(c.nativeGeneratedPresentedFrames_==8);
 renderReader.unlock();
 detach.get();worker.get();
 assert(c.reset && c.presenterFinished && !c.nativePresentationSurfaceReady_);
 assert(c.nativeGeneratedPresentedFrames_==0 && c.surface==0);
}
'''
with tempfile.TemporaryDirectory(prefix='lsfg-detach-') as directory:
    cpp,binary = Path(directory)/'detach.cpp',Path(directory)/'detach'
    cpp.write_text(detach_code)
    subprocess.run(['g++','-std=c++20','-pthread','-Wall','-Wextra','-Werror',str(cpp),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True,timeout=10)
print('PASS: live-reader teardown drains presenter before evidence reset and device idle')
plan = SOURCE.index('nativeRequestedGenerations = nativeGenerations;')
count = SOURCE.index('nativeGenerationOpportunityCount_.fetch_add(', plan)
admission = SOURCE.index('nativeHostSyntheticAdmissionCapacity();', plan)
assert plan < count < admission, 'opportunity accounting must precede host admission'
print('PASS: requested plan opportunities are captured before admission truncation')
