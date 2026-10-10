"""Test real Native host evidence, Flow observations and density decisions."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "app/src/main/cpp/winlator"
context = (HOST / "VulkanRendererContext.cpp").read_text()
native = (ROOT / "app/src/main/cpp/lsfg/vkr_lsfg.cpp").read_text()
header = (ROOT / "app/src/main/cpp/lsfg/vkr_lsfg.h").read_text()

def function(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]

pressure_type = header[header.index("typedef struct VkrLsfgPresentationPressure {"):
                       header.index("} VkrLsfgPresentationPressure;")
                       + len("} VkrLsfgPresentationPressure;")]
host_pressure = function(context, "void VulkanRendererContext::updateNativePresentationPressure(")
plan = function(native, "uint32_t vkr_lsfg_plan(")
start = plan.index("        const auto& flow = lsfg->flow_controller.telemetry();")
end = plan.index("        lsfg->synthetic_drop_pressure = false;", start)
density = plan[start:end + len("        lsfg->synthetic_drop_pressure = false;")]
start = plan.index("        const double elapsed_seconds =")
end = plan.index("        const float previous_scale =", start)
observation = plan[start:end]

code = r'''
#include <algorithm>
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <string>
#include "adaptive_flow_controller.hpp"
constexpr int ANDROID_LOG_INFO=4,VKR_LSFG_FLOW_ADAPTIVE=1;
constexpr unsigned VKR_LSFG_MAX_GENERATIONS=3;
enum class HostDisplayConfirmationBackend { WsiAccepted,GoogleDisplayTiming };
void __android_log_print(int,const char*,const char*,...) {}
#define LSFG_FLOW_LOG(...) ((void)0)
template<class T> void trimEvidence(T& events,uint64_t cutoff) {
    while(!events.empty() && events.front()<cutoff) events.pop_front();
}
uint64_t rollingPercentileNs(const std::deque<uint64_t>&,int) { return 0; }
''' + pressure_type + r'''
struct VulkanRendererContext {
    std::deque<uint64_t> nativeSourceWsiEventNs_,nativeGeneratedWsiEventNs_;
    std::deque<uint64_t> nativeSourceConfirmedEventNs_,nativeGeneratedConfirmedEventNs_;
    std::deque<uint64_t> nativeSourceUnobservedEventNs_,nativeGeneratedUnobservedEventNs_;
    std::deque<uint64_t> nativeGeneratedRejectedEventNs_,hostPresentLatencySamplesNs_;
    uint64_t nativePresentationEvidenceStartNs_=1000000000ULL,framegenConfigRevision=1;
    uint64_t hostRefreshPeriodNs_=8333333ULL;
    unsigned framegenTargetRate=120,framegenMultiplier=4,framegenFlowMode=1,framegenFlowPreset=3;
    float framegenFlowScale=.5f;
    uint32_t nativePresentationPressureStrikes_=0,nativePresentationRecoveryStrikes_=0;
    uint32_t hostSuboptimalConsecutive_=0;
    bool nativePresentationPressureActive_=false;
    std::atomic<bool> lsfgFrameQueueEnabled_{false};
    std::atomic<uint32_t> lsfgFrameQueueTarget_{2};
    double nativeSourceConfirmedFps_=0,nativeGeneratedConfirmedFps_=0;
    VkrLsfgPresentationPressure nativePresentationPressure_{};
    HostDisplayConfirmationBackend selectHostDisplayConfirmationBackend() const {
        return HostDisplayConfirmationBackend::GoogleDisplayTiming;
    }
    void updateNativePresentationPressure(uint64_t);
    void evidence(unsigned source,unsigned generated,unsigned sourceLost=0,unsigned generatedLost=0,unsigned rejected=0) {
        nativeSourceConfirmedEventNs_.assign(source,2000000000ULL);
        nativeGeneratedConfirmedEventNs_.assign(generated,2000000000ULL);
        nativeSourceUnobservedEventNs_.assign(sourceLost,2000000000ULL);
        nativeGeneratedUnobservedEventNs_.assign(generatedLost,2000000000ULL);
        nativeGeneratedRejectedEventNs_.assign(rejected,2000000000ULL);
        nativeGeneratedWsiEventNs_.assign(generated+generatedLost,2000000000ULL);
        for(int i=0;i<8;++i) updateNativePresentationPressure(3000000000ULL);
    }
};
''' + host_pressure + r'''
struct Runtime {
    struct {
        float gpu_usage_percent=61.f,source_fps=30.f,output_fps=60.f;
        float slow_frame_ratio=0.f,frame_time_p95_ms=8.333f;
        int thermal_status=0;
        bool output_valid=true,source_valid=true,slow_ratio_valid=true,frame_time_valid=true;
    } pressure;
    struct FlowController {
        struct Telemetry { float minimumScale=.5f; };
        Telemetry telemetry() const { return {}; }
    } flow_controller;
    struct { size_t generations=3; } plan;
    VkrLsfgPresentationPressure presentation_pressure{true,false,false,true,1.f,1.f,0.f,.5f};
    float active_flow_scale=.5f;
    bool synthetic_drop_pressure=false;
    unsigned adaptive_generation_cap=1,flow_transition_frames=0;
    double density_pressure_seconds=0,density_recovery_seconds=0;
    struct Pacer {
        struct Configuration { unsigned multiplier=4; };
        Configuration Config() const { return {}; }
    } pacer;
};
AdaptiveFlowObservation observe(Runtime* lsfg,float requested_target=120.f) {
    struct {
        float target_rate=120.f,source_rate=30.f,refresh_rate=120.f,last_elapsed=.033333f;
        bool rates_settled=true;
    } stats;
    stats.target_rate=requested_target;
    const bool pressure_fresh=true,global_pressure_valid=true,thermal_pressure_valid=true;
''' + observation + r'''
    return observation;
}
void tick(Runtime* lsfg,bool output_satisfied=false) {
    struct { float target_rate=120.f; } stats;
    const bool global_pressure_valid=true,output_deficit=!output_satisfied;
    [[maybe_unused]] const bool output_valid=true;
    const double elapsed_seconds=.10;
''' + density + r'''
}
void require(bool ok,const char* message) {
    if(!ok) { std::fprintf(stderr,"FAIL: %s\n",message); std::abort(); }
}
int main(int argc,char** argv) {
    require(argc==2,"select regression");
    const std::string test=argv[1];
    if(test=="pressure") {
        for(unsigned target: {90u,120u}) {
            VulkanRendererContext host;host.framegenTargetRate=target;host.evidence(60,60);
            require(host.nativePresentationPressure_.output_target_deficit_ratio>.30f,"target deficit remains telemetry");
            require(!host.nativePresentationPressure_.pressure_active,"healthy lower density is not delivery pressure");
            host.evidence(55,48,5,2);
            require(!host.nativePresentationPressure_.pressure_active,"0.96 generated/0.92 source delivery cannot collapse density");
        }
    } else if(test=="refresh") {
        for(unsigned target: {0u,120u}) {
            VulkanRendererContext host;host.hostRefreshPeriodNs_=16666667ULL;
            host.framegenTargetRate=target;host.framegenMultiplier=4;host.evidence(60,60);
            require(host.nativePresentationPressure_.output_target_deficit_ratio<.001f,"physical target clamps to refresh");
            require(!host.nativePresentationPressure_.pressure_active,"panel-rate delivery is healthy");
        }
    } else if(test=="flow") {
        for(unsigned cap=0;cap<=3;++cap) {
            Runtime runtime;runtime.adaptive_generation_cap=cap;runtime.pressure.output_fps=30.f*(cap+1);
            const auto o=observe(&runtime);
            require(o.generationCount==cap && o.scheduledGenerationDensity==cap,"Flow observes actual capped density");
            require(std::fabs(o.outputTargetFps-runtime.pressure.output_fps)<.001,"Flow target follows reachable density");
            require(o.outputTargetSatisfied && !o.outputDeficit,"healthy capped output has no false Flow deficit");
        }
        Runtime fixed;fixed.adaptive_generation_cap=1;fixed.pressure.output_fps=120.f;
        const auto o=observe(&fixed,0.f);
        require(o.generationCount==3 && o.scheduledGenerationDensity==3,"Fixed generation remains authoritative");
        require(o.outputTargetFps==120.f,"Fixed target remains authoritative");
    } else if(test=="recovery") {
        Runtime runtime;for(int i=0;i<45;++i) tick(&runtime);
        require(runtime.adaptive_generation_cap==3,"healthy cap must recover before satisfying full target");
    } else if(test=="probe") {
        Runtime runtime;runtime.adaptive_generation_cap=0;
        for(int i=0;i<35;++i) tick(&runtime);
        require(runtime.adaptive_generation_cap>=1,"source-only stream probes generation");
        VulkanRendererContext host;host.evidence(60,60);runtime.presentation_pressure=host.nativePresentationPressure_;
        for(int i=0;i<45;++i) tick(&runtime);
        require(runtime.adaptive_generation_cap==3,"successful probe grows instead of collapsing");
    } else if(test=="preset") {
        for(unsigned cap: {0u,1u}) {
            Runtime runtime;runtime.adaptive_generation_cap=cap;runtime.active_flow_scale=1.f;
            // A Flow preset edit can raise quality while preserving an older
            // reduced cap. Healthy delivery must permit density recovery there.
            for(int i=0;i<85;++i) tick(&runtime);
            require(runtime.adaptive_generation_cap==3,"Flow preset edit cannot pin healthy reduced cap above floor");
        }
    } else if(test=="loss") {
        VulkanRendererContext host;host.evidence(60,8,0,32);
        require(host.nativePresentationPressure_.pressure_active,"real generated loss retains pressure");
        Runtime runtime;runtime.adaptive_generation_cap=3;runtime.presentation_pressure=host.nativePresentationPressure_;
        for(int i=0;i<8;++i) tick(&runtime);
        require(runtime.adaptive_generation_cap==2,"real delivery loss retains backoff");
        VulkanRendererContext rejected;rejected.evidence(60,60,0,0,2);
        require(rejected.nativePresentationPressure_.pressure_active,"admission rejection retains pressure");
        Runtime gpu;gpu.adaptive_generation_cap=3;gpu.pressure.gpu_usage_percent=98.f;
        for(int i=0;i<8;++i) tick(&gpu);
        require(gpu.adaptive_generation_cap==2,"severe GPU deficit retains backoff");
        gpu.pressure.gpu_usage_percent=95.f;for(int i=0;i<35;++i) tick(&gpu);
        require(gpu.adaptive_generation_cap==2,"recovery requires GPU headroom");
    } else { require(false,"unknown regression"); }
}
'''

tests = sys.argv[1:] or ["pressure", "refresh", "flow", "recovery", "probe", "preset", "loss"]
with tempfile.TemporaryDirectory(prefix="native-density-") as directory:
    cpp,binary = Path(directory)/"test.cpp",Path(directory)/"test"
    cpp.write_text(code)
    subprocess.run(["g++","-std=c++20","-Wall","-Wextra","-Werror","-I",
                    str(ROOT/"app/src/main/cpp/lsfg-vk-android/include"),str(cpp),"-o",str(binary)],check=True)
    failed = []
    for test in tests:
        result = subprocess.run([str(binary),test])
        print(f"{'PASS' if result.returncode == 0 else 'FAIL'}: {test}",flush=True)
        if result.returncode != 0: failed.append(test)
    sys.exit(1 if failed else 0)
