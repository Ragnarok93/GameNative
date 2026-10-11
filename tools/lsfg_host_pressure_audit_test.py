"""Execute actual JNI pressure setter/render forwarding with a virtual clock."""
from pathlib import Path
import subprocess
import tempfile
ROOT=Path(__file__).resolve().parents[1]
HOST=ROOT/'app/src/main/cpp/winlator'
context=(HOST/'VulkanRendererContext.cpp').read_text()
lsfg=(HOST/'VulkanRendererLsfg.cpp').read_text()
header=(HOST/'VulkanRendererContext.h').read_text()
def function(source,signature):
 start=source.index(signature);opening=source.index('{',start);depth,end=1,opening+1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
start=context.index('                vkr_lsfg_set_pressure(')
end=context.index('                vkr_lsfg_set_presentation_pressure(',start)
forward=context[start:end]
if 'const auto pressure = readFrameGenerationPressure();' in context[:start]:
 start=context.rindex('                const auto pressure = readFrameGenerationPressure();',0,start)
 forward=context[start:end]
fields=header[header.index('    std::atomic<float> framegenGpuUsagePercent_'):
              header.index('    uint64_t nativeLsfgContextEpoch_')]
extra=''
for signature in ('VulkanRendererContext::NativePressureSnapshot\nVulkanRendererContext::readFrameGenerationPressure(', 'void VulkanRendererContext::clearFrameGenerationPressure('):
 if signature in lsfg: extra+=function(lsfg,signature)
code=r'''
#include "adaptive_scheduler.hpp"
#include <chrono>
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <thread>
std::atomic<uint64_t> nowNs{2000000000ULL};
uint64_t frameGenerationPressureTimeNs() {return nowNs.load();}
struct Reading {float gpu=-1,source=-1,output=-1,p95=-1,slow=-1;int thermal=-1;};
Reading captured;
void vkr_lsfg_set_pressure(void*,float gpu,int thermal,float source,float output,float p95,float slow) {
 captured={gpu,source,output,p95,slow,thermal};
}
struct VulkanRendererContext {
''' + fields + r'''
 SourceProtectedTimeline nativeSourceTimeline_;
 SourceTimelineSample nativeSourceTimelineSample_;
 uint64_t nativeTimelineLastSourceArrivalNs_=0,nativeTimelineSourceIntervalNs_=0;
 uint64_t hostRefreshPeriodNs_=8333333ULL;
 unsigned framegenTargetRate=0,framegenMultiplier=4;
 void noteNativeSourceArrival(uint64_t);
 void* lsfg=this;
 std::atomic<float> nativeConfirmedOutputFpsForGovernor_{-1.f};
 void setFrameGenerationPressure(float,int,float,float,float,float);
''' + ('' if extra else ' void clearFrameGenerationPressure() {}\n') + r'''
 void forward() {
''' + forward + r'''
 }
};
''' + function(lsfg,'void VulkanRendererContext::setFrameGenerationPressure(') + extra + function(context,'void VulkanRendererContext::noteNativeSourceArrival(') + r'''
void require(bool okay,const char* why) {if(!okay) {std::fprintf(stderr,"FAIL: %s\n",why);std::exit(1);}}
int main() {
 VulkanRendererContext c;
 c.setFrameGenerationPressure(98,4,30,120,33,.5);
 c.forward();require(captured.gpu==98 && captured.thermal==4,"fresh collector pressure reaches Native");
 nowNs=5000000000ULL;
 c.forward();require(captured.gpu==-1 && captured.thermal==-1 && captured.source==-1 && captured.p95==-1 && captured.slow==-1,"render loops cannot refresh stopped collector GPU/thermal/source samples");
 c.setFrameGenerationPressure(0,0,0,0,0,0);
 c.forward();require(captured.gpu==0 && captured.source==0,"fresh measured zero remains distinct from missing");
 c.clearFrameGenerationPressure();c.forward();
 require(captured.gpu==-1 && captured.source==-1,"policy reset retires cached pressure tuple");
 c.setFrameGenerationPressure(NAN,7,INFINITY,INFINITY,NAN,2);
 c.forward();require(captured.gpu==-1 && captured.thermal==-1 && captured.source==-1 && captured.p95==-1 && captured.slow==-1,"invalid measurements remain unavailable");
 c.setFrameGenerationPressure(10,1,120,120,5,.1);
 c.noteNativeSourceArrival(nowNs.load());
 require(c.nativeTimelineSourceIntervalNs_>=33333330ULL && c.nativeTimelineSourceIntervalNs_<=33333336ULL,"guest120windowevents cannot seed30Hz Native source timeline as120Hz");
 c.setFrameGenerationPressure(10,1,20,60,5,.1);
 std::atomic<bool> done{false};
 std::thread publisher([&] {for(unsigned n=0;n<100000;++n) {
   if(n%2)c.setFrameGenerationPressure(10,1,20,60,5,.1);
   else c.setFrameGenerationPressure(90,5,40,120,20,.9);
 } done=true;});
 while(!done) {
  c.forward();
  const bool a=captured.gpu==10 && captured.thermal==1 && captured.source==20 && captured.p95==5 && captured.slow==.1f;
  const bool b=captured.gpu==90 && captured.thermal==5 && captured.source==40 && captured.p95==20 && captured.slow==.9f;
  require(a||b,"reader must snapshot one complete collector tuple");
 }
 publisher.join();
 std::puts("PASS: host pressure TTL, reset, invalid/zero semantics and100000 threaded tuple publications");
}
'''
with tempfile.TemporaryDirectory(prefix='lsfg-pressure-tuple-') as directory:
 cpp,binary=Path(directory)/'pressure.cpp',Path(directory)/'pressure'
 cpp.write_text(code)
 runtime=ROOT/'app/src/main/cpp/lsfg-vk-android'
 subprocess.run(['g++','-std=c++20','-pthread','-Wall','-Wextra','-Werror','-I'+str(runtime/'include'),str(cpp),str(runtime/'src/adaptive_scheduler.cpp'),'-o',str(binary)],check=True)
 subprocess.run([str(binary)],check=True)
