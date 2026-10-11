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
