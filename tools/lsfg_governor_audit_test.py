"""Execute Native governor/configuration code with deterministic source and WSI evidence.

Only Vulkan allocation and Flow decisions are substituted; observation construction,
cap changes, reset and configuration publication are the production implementations.
"""
from pathlib import Path
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[1]
native = (ROOT / 'app/src/main/cpp/lsfg/vkr_lsfg.cpp').read_text()
header = (ROOT / 'app/src/main/cpp/lsfg/vkr_lsfg.h').read_text()
def function(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    end, depth = opening + 1, 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]
pressure = header[header.index('typedef struct VkrLsfgPresentationPressure {'):header.index('} VkrLsfgPresentationPressure;') + len('} VkrLsfgPresentationPressure;')]
plan = function(native, 'uint32_t vkr_lsfg_plan(')
plan = plan[:plan.index('    lsfg->warm =')] + '\nreturn static_cast<uint32_t>(lsfg->plan.generations);\n}'
structure = function(native, 'struct VkrLsfg {').replace('AdaptiveFlowController flow_controller', 'FakeFlowController flow_controller') + ';'
code = r'''
#include <algorithm>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include "adaptive_flow_controller.hpp"
constexpr unsigned VKR_LSFG_MAX_GENERATIONS=3,VKR_LSFG_FLOW_ADAPTIVE=1,VKR_LSFG_FLOW_FIXED=0;
constexpr unsigned VKR_LSFG_FLOW_PRESET_QUALITY=0,VKR_LSFG_FLOW_PRESET_BALANCED=1,VKR_LSFG_FLOW_PRESET_LOW=2,VKR_LSFG_FLOW_PRESET_AUTO=3;
constexpr float LSFG_FLOW_SCALE_MIN=.25f,LSFG_FLOW_SCALE_MAX=1.f;
struct VkExtent2D { unsigned width{},height{}; };
using VkFormat = int; constexpr int VK_FORMAT_UNDEFINED=0;
void log(const char*,...) {}
#define LSFG_FLOW_LOG(...) log(__VA_ARGS__)
namespace lsfg {
struct Device {};
struct LsfgShaders {};
struct LsfgChain { void ResetHistory() {} };
struct LsfgPacerConfig { unsigned multiplier=4,target_rate=120; float refresh_rate=120; };
struct LsfgPlan { size_t generations=3; bool warm=true; };
struct LsfgPacerStats { float target_rate=120,source_rate=30,refresh_rate=120,last_elapsed=1.f/30; bool rates_settled=true; uint64_t last_drawn=1; };
struct LsfgPacer {
    LsfgPacerConfig config{}; LsfgPacerStats stats{};
    LsfgPacerConfig Config() const { return config; }
    void SetConfig(LsfgPacerConfig value) { config=value; stats.target_rate=value.target_rate; stats.refresh_rate=value.refresh_rate; }
    LsfgPlan Plan(size_t cap,uint64_t) { return {std::min<size_t>(cap,config.multiplier-1),true}; }
    LsfgPacerStats Stats() const { return stats; }
    void Reset() {}
};
}
struct FakeFlowController {
    float scale=1.f; AdaptiveFlowObservation last{}; AdaptiveFlowTelemetry info{};
    void configure(bool,AdaptiveFlowPreset) {}
    void seedCurrentScale(float value) { scale=value; }
    float currentScale() const { return scale; }
    float observe(const AdaptiveFlowObservation& value) { last=value; return scale; }
    const AdaptiveFlowTelemetry& telemetry() const { return info; }
    void reset() { last={}; }
};
''' + pressure + '\n' + function(native, 'AdaptiveFlowPreset ParseFlowPreset(') + '\n' + structure + '\n' + '\n'.join(function(native,s) for s in ['void vkr_lsfg_configure(', 'void vkr_lsfg_set_pressure(', 'void vkr_lsfg_set_refresh_rate(', 'void vkr_lsfg_reset(']) + '\n' + plan + r'''
void require(bool ok,const char* text) { if(!ok) { std::fprintf(stderr,"FAIL: %s\n",text); std::exit(1); } }
void fresh(VkrLsfg& r,float gpu=61,float output=120,int thermal=0) { vkr_lsfg_set_pressure(&r,gpu,thermal,r.pacer.stats.source_rate,output,1000.f/r.pacer.stats.source_rate,.2f); }
void tick(VkrLsfg& r,int count) { for(int i=0;i<count;++i) { fresh(r,r.pressure.gpu_valid ? r.pressure.gpu_usage_percent : -1,r.pressure.output_valid ? r.pressure.output_fps : -1,r.pressure.thermal_valid ? r.pressure.thermal_status : -1); vkr_lsfg_plan(&r,3,1); } }
int main(int argc,char** argv) {
    require(argc==2,"select case"); const std::string test=argv[1]; VkrLsfg r;
    r.flow_controller.info.minimumScale=.5f; r.last_generations=3; r.active_flow_scale=1; r.flow_controller.scale=1;
    fresh(r);
    if(test=="fixed-flow-relief") {
        r.presentation_pressure.confirmation_available=true; r.presentation_pressure.pressure_active=true;
        tick(r,22); require(r.adaptive_generation_cap==2,"Adaptive FG relieves WSI pressure under Fixed Flow");
        require(r.active_flow_scale==1,"Fixed Flow remains authoritative");
    } else if(test=="fixed-compute-relief") {
        fresh(r,98,60); tick(r,22);
        require(r.adaptive_generation_cap==2 && r.active_flow_scale==1,"Fixed Flow preserves quality while Adaptive FG relieves severe compute deficit");
    } else if(test=="above-floor-relief") {
        r.adaptive_flow=true; r.presentation_pressure.confirmation_available=true; r.presentation_pressure.pressure_active=true;
        tick(r,22); require(r.adaptive_generation_cap==2,"WSI actuator must not wait for optical Flow floor");
    } else if(test=="source-domain") {
        r.adaptive_flow=true; vkr_lsfg_plan(&r,3,1);
        require(!r.flow_controller.last.outputDeficit,"30 source / 120 physical output is healthy despite source p95 33ms");
    } else if(test=="unique-source-domain") {
        r.adaptive_flow=true; r.pressure.source_fps=60;
        vkr_lsfg_plan(&r,3,1);
        require(r.flow_controller.last.sourceFps==30,"raw guest upload pressure cannot override unique composition cadence");
        r.pacer.stats.source_rate=0; r.pressure.source_fps=60;
        vkr_lsfg_plan(&r,3,1);
        require(r.flow_controller.last.sourceFps==0,"paused composition cadence cannot inherit raw upload headroom");
    } else if(test=="unknown-output") {
        r.adaptive_flow=true; fresh(r,98,-1); r.active_flow_scale=.5f; r.flow_controller.scale=.5f;
        tick(r,40); require(r.adaptive_generation_cap==3,"unknown physical output cannot invent GPU/output deficit");
    } else if(test=="no-source-progress") {
        r.adaptive_flow=true; r.active_flow_scale=.5f; r.flow_controller.scale=.5f; r.pacer.stats.last_drawn=0; r.presentation_pressure.confirmation_available=true; r.presentation_pressure.pressure_active=true;
        tick(r,100); require(r.adaptive_generation_cap==3,"unchanged source loops cannot advance pressure timer");
        require(!r.flow_controller.last.valid,"no source progress invalidates Flow observation");
    } else if(test=="long-source-gap") {
        r.adaptive_flow=true; r.active_flow_scale=.5f; r.flow_controller.scale=.5f; r.pacer.stats.last_elapsed=.8f; r.presentation_pressure.confirmation_available=true; r.presentation_pressure.pressure_active=true;
        tick(r,20); require(r.adaptive_generation_cap==3,"resume gaps cannot qualify sustained pressure");
    } else if(test=="fixed-flow-recovery") {
        r.adaptive_generation_cap=0; tick(r,240); require(r.adaptive_generation_cap==3,"Fixed Flow permits Adaptive density recovery");
    } else if(test=="unknown-gpu-delivery-recovery") {
        r.presentation_pressure.confirmation_available=true; r.presentation_pressure.pressure_active=true;
        fresh(r,-1,30); tick(r,100); require(r.adaptive_generation_cap==0,"delivery failure backs off despite missing GPU sensor");
        r.presentation_pressure.pressure_active=false; r.presentation_pressure.source_delivery_healthy=true;
        r.presentation_pressure.generated_delivery_efficiency=1;
        tick(r,240); require(r.adaptive_generation_cap==3,"fresh confirmed source delivery allows bounded GPU-unknown recovery probes");
    } else if(test=="present-wait-recovery") {
        for(float gpu: {61.f,-1.f}) {
            VkrLsfg presentWait; presentWait.adaptive_generation_cap=0;
            presentWait.presentation_pressure.confirmation_available=true;
            presentWait.presentation_pressure.source_delivery_healthy=true;
            presentWait.presentation_pressure.generated_delivery_efficiency=1;
            fresh(presentWait,gpu,-1); tick(presentWait,240);
            require(presentWait.adaptive_generation_cap==3,"fresh PresentWait delivery truth allows bounded density recovery while actual output FPS stays unknown");
            require(!presentWait.pressure.output_valid,"PresentWait does not invent scanout timing");
        }
    } else if(test=="all-external-pressure-unavailable") {
        r.adaptive_generation_cap=0;
        r.presentation_pressure.confirmation_available=true;
        r.presentation_pressure.source_delivery_healthy=true;
        r.presentation_pressure.generated_delivery_efficiency=1;
        for(int i=0;i<240;++i) {
            vkr_lsfg_set_pressure(&r,-1,-1,-1,-1,-1,-1);
            vkr_lsfg_plan(&r,3,1);
        }
        require(r.adaptive_generation_cap==3,"actual unique source progress and fresh confirmed delivery allow bounded probes when all external metrics are absent");
        require(!r.pressure.sample_valid,"recovery does not manufacture external measurements");
    } else if(test=="flow-edit-evidence") {
        r.density_pressure_seconds=.59; r.density_recovery_seconds=1.99; r.synthetic_drop_pressure=true;
        vkr_lsfg_configure(&r,4,120,.75f,0,0,120,7);
        require(r.density_pressure_seconds==0 && r.density_recovery_seconds==0 && !r.synthetic_drop_pressure,"Flow edit rejects partial old-quality density evidence");
    } else if(test=="flow-transition-evidence") {
        r.adaptive_flow=true; r.active_flow_scale=.5f; r.flow_controller.scale=.5f; r.flow_transition_frames=3;
        r.pacer.stats.source_rate=10; r.pacer.stats.last_elapsed=.1f;
        r.presentation_pressure.confirmation_available=true; r.presentation_pressure.pressure_active=true;
        tick(r,4); require(r.density_pressure_seconds<.11,"Flow graph warm-up does not count as sustained density pressure");
    } else if(test=="unknown-gpu-unproven-recovery") {
        r.adaptive_generation_cap=0; fresh(r,-1,-1); tick(r,240);
        require(r.adaptive_generation_cap==0,"GPU-unknown recovery requires actual source and physical cadence proof");
    } else if(test=="compute-origin-recovery") {
        fresh(r,98,60); tick(r,22); require(r.adaptive_generation_cap==2,"compute origin backoff established");
        r.presentation_pressure.confirmation_available=true; r.presentation_pressure.source_delivery_healthy=true;
        r.presentation_pressure.generated_delivery_efficiency=1; fresh(r,-1,120); tick(r,240);
        require(r.adaptive_generation_cap==2,"missing GPU sensor cannot reverse compute-origin relief without headroom evidence");
        fresh(r,61,120); tick(r,70); require(r.adaptive_generation_cap==3,"measured GPU headroom releases compute-origin cap");
    } else if(test=="thermal-recovery") {
        r.adaptive_flow=true; r.adaptive_generation_cap=0; fresh(r,61,30,4); tick(r,240);
        require(r.adaptive_generation_cap==0,"severe thermal pressure blocks density recovery");
    } else if(test=="cap-preserved") {
        r.adaptive_generation_cap=1; vkr_lsfg_configure(&r,4,120,.75f,0,0,120,7);
        require(r.adaptive_generation_cap==1,"Fixed Flow settings must preserve Adaptive cap");
        require(r.active_flow_scale==.75f && r.flow_transition_frames==0,"Fixed Flow publication applies only chosen quality");
    } else if(test=="workload-edit") {
        r.adaptive_flow=true; r.flow_controller.scale=.625f; r.active_flow_scale=.625f; r.requested_flow_scale=1;
        r.adaptive_generation_cap=1; r.density_pressure_seconds=.59; r.density_recovery_seconds=1.99; r.synthetic_drop_pressure=true;
        vkr_lsfg_configure(&r,4,90,1,1,0,120,7);
        require(r.active_flow_scale==.625f && r.adaptive_generation_cap==1,"workload edit preserves settled quality and cap");
        require(r.density_pressure_seconds==0 && r.density_recovery_seconds==0 && !r.synthetic_drop_pressure,"workload edit clears incompatible partial evidence");
        require(r.density_transition_frames==3,"workload edit starts settle interval");
    } else if(test=="refresh-edit") {
        r.adaptive_flow=true; r.density_pressure_seconds=.59; r.synthetic_drop_pressure=true;
        vkr_lsfg_set_refresh_rate(&r,60);
        require(r.density_pressure_seconds==0 && !r.synthetic_drop_pressure,"refresh changes clear old cadence evidence");
    } else if(test=="measured-zero") {
        vkr_lsfg_set_pressure(&r,-1,-1,0,0,0,0);
        require(r.pressure.source_valid && r.pressure.frame_time_valid && r.pressure.output_valid,"measured zero stays distinct from unknown");
    } else if(test=="reset-evidence") {
        r.flow_transition_frames=3; r.density_transition_frames=3; vkr_lsfg_reset(&r);
        require(!r.pressure.sample_valid && r.flow_transition_frames==0 && r.density_transition_frames==0,"new runtime cannot reuse old pressure or transition evidence");
    } else if(test=="cadence-provenance") {
        r.adaptive_flow=true; vkr_lsfg_plan(&r,3,1);
        require(r.flow_controller.last.generatedCadenceSample && !r.flow_controller.last.generatedWorkSample,"real Native generated cadence must not imply invented GPU timing");
    } else if(test=="fixed-clear-drop") {
        r.pacer.config.target_rate=0; r.pacer.stats.target_rate=0; r.synthetic_drop_pressure=true;
        vkr_lsfg_plan(&r,3,1); require(!r.synthetic_drop_pressure,"Fixed FG + Fixed Flow cannot retain old synthetic pressure");
    } else if(test=="stationary-failed-delivery-probe" || test=="stationary-failed-admission-probe"
            || test=="jitter-failed-density-probe" || test=="stationary-limited-density-probe") {
        const unsigned baseline=test=="stationary-limited-density-probe" ? 1 : 0;
        r.adaptive_generation_cap=baseline;
        r.presentation_pressure.confirmation_available=true;
        r.presentation_pressure.source_delivery_healthy=true;
        unsigned attempts=0,failedProbes=0;
        // The source/GPU/policy operating point stays unchanged for five
        // minutes. Source-only is healthy; adding even one generation fails.
        // A failed probe is evidence against retrying the same actuator there.
        for(int i=0;i<9000;++i) {
            const auto before=r.adaptive_generation_cap;
            const bool generates=before>baseline;
            r.presentation_pressure.pressure_active=generates && test!="stationary-failed-admission-probe";
            r.presentation_pressure.generated_delivery_efficiency=generates ? .2f : 1.f;
            r.synthetic_drop_pressure=generates && test=="stationary-failed-admission-probe";
            const bool jitter=test=="jitter-failed-density-probe";
            r.pacer.stats.source_rate=jitter ? ((i%2) ? 30.1f : 29.9f) : 30.f;
            r.pacer.stats.last_elapsed=1.f/r.pacer.stats.source_rate;
            fresh(r,jitter ? ((i%2) ? 60.5f : 59.5f) : 60.f,30*(baseline+1));
            const auto selected=vkr_lsfg_plan(&r,3,1);
            r.last_generations=test=="stationary-failed-admission-probe" ? 0 : selected;
            attempts+=before==baseline && r.adaptive_generation_cap>baseline;
            failedProbes+=before>baseline && r.adaptive_generation_cap==baseline;
        }
        std::printf("stationary300s attempts=%u failed_probes=%u cap=%u\n",attempts,failedProbes,r.adaptive_generation_cap);
        require(attempts==1 && failedProbes==1 && r.adaptive_generation_cap==baseline,
                "failed density recovery probe must hold at unchanged source/GPU/delivery point");
    } else if(test=="changed-point-density-recovery") {
        r.adaptive_generation_cap=0;
        r.presentation_pressure.confirmation_available=true;
        r.presentation_pressure.source_delivery_healthy=true;
        // Establish a failed generation probe at the original operating point.
        for(int i=0;i<300;++i) {
            const bool generated=r.adaptive_generation_cap>0;
            r.presentation_pressure.pressure_active=generated;
            r.presentation_pressure.generated_delivery_efficiency=generated ? .2f : 1.f;
            fresh(r,60,30);
            r.last_generations=vkr_lsfg_plan(&r,3,1);
        }
        // A sustained lower source workload and lower GPU pressure is material
        // new evidence. At this point new generation probes actually succeed.
        r.pacer.stats.source_rate=20;
        r.pacer.stats.last_elapsed=.05f;
        r.presentation_pressure.pressure_active=false;
        r.presentation_pressure.generated_delivery_efficiency=1;
        for(int i=0;i<600;++i) {
            fresh(r,45,80);
            r.last_generations=vkr_lsfg_plan(&r,3,1);
        }
        require(r.adaptive_generation_cap==3,"sustained material workload/headroom change releases failed density probe hold");
    } else if(test=="soak") {
        uint64_t observations=0;
        for(unsigned multiplier: {2u,3u,4u}) for(unsigned target: {90u,120u})
        for(bool adaptiveFlow: {false,true}) for(float source: {10.f,20.f,30.f,60.f,90.f,120.f}) {
            VkrLsfg stream; stream.adaptive_flow=adaptiveFlow;
            stream.pacer.config.multiplier=multiplier; stream.pacer.config.target_rate=target;
            stream.pacer.stats.target_rate=target; stream.pacer.stats.source_rate=source;
            stream.pacer.stats.last_elapsed=1.f/source; stream.last_generations=multiplier-1;
            for(int cycle=0;cycle<40;++cycle) {
                stream.presentation_pressure.confirmation_available=true;
                stream.presentation_pressure.pressure_active=true;
                for(int i=0;i<int(source*6);++i) {
                    fresh(stream,85); const auto plan=vkr_lsfg_plan(&stream,3,1); ++observations;
                    require(plan<=stream.adaptive_generation_cap && plan<=multiplier-1,"backoff retains bounded multiplier/cap authority");
                }
                require(stream.adaptive_generation_cap==0,"sustained delivery failure safely reaches source-only cap");
                stream.presentation_pressure.pressure_active=false;
                stream.presentation_pressure.source_delivery_healthy=true;
                stream.presentation_pressure.generated_delivery_efficiency=1;
                // Recovery has a measurable operating-point change: GPU
                // demand falls85->61, rather than asserting unobservable
                // generated health while the stream is source-only.
                for(int i=0;i<int(source*12);++i) {
                    fresh(stream,61); const auto plan=vkr_lsfg_plan(&stream,3,1); ++observations;
                    require(plan<=stream.adaptive_generation_cap && plan<=multiplier-1,"recovery retains bounded multiplier/cap authority");
                }
                require(stream.adaptive_generation_cap==3,"healthy delivery and headroom consistently restore generation");
            }
        }
        std::printf("72 operating points x 40 cycles: %llu production observations\n",(unsigned long long)observations);
    } else require(false,"unknown case");
}
'''
# API additions are referenced only by assertions after their production field exists.
if 'density_transition_frames' not in native:
    structure_marker = 'uint32_t flow_transition_frames{};'
    code = code.replace(structure_marker, structure_marker + '\nuint32_t density_transition_frames{};')
if 'generatedCadenceSample' not in (ROOT/'app/src/main/cpp/lsfg-vk-android/include/adaptive_flow_controller.hpp').read_text():
    code = code.replace('r.flow_controller.last.generatedCadenceSample', 'false')
cases = sys.argv[1:] or ['fixed-flow-relief','fixed-compute-relief','above-floor-relief','source-domain','unique-source-domain','unknown-output','no-source-progress','long-source-gap','fixed-flow-recovery','unknown-gpu-delivery-recovery','present-wait-recovery','all-external-pressure-unavailable','flow-edit-evidence','flow-transition-evidence','unknown-gpu-unproven-recovery','compute-origin-recovery','thermal-recovery','cap-preserved','workload-edit','refresh-edit','measured-zero','reset-evidence','cadence-provenance','fixed-clear-drop','stationary-failed-delivery-probe','stationary-failed-admission-probe','jitter-failed-density-probe','stationary-limited-density-probe','changed-point-density-recovery','soak']
with tempfile.TemporaryDirectory(prefix='native-governor-audit-') as directory:
    cpp,binary=Path(directory)/'test.cpp',Path(directory)/'test'
    cpp.write_text(code)
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Werror','-I',str(ROOT/'app/src/main/cpp/lsfg-vk-android/include'),str(cpp),str(ROOT/'app/src/main/cpp/lsfg-vk-android/src/adaptive_flow_controller.cpp'),'-o',str(binary)],check=True)
    failed=[]
    for case in cases:
        result=subprocess.run([str(binary),case]); print(f"{'PASS' if result.returncode==0 else 'FAIL'}: {case}",flush=True)
        if result.returncode: failed.append(case)
    if failed:
        sys.exit(1)

# Run the same production Native configuration/observation/density functions
# with the real fractional scheduler, pacer and Flow controller. Only Vulkan
# allocation is absent; a clock adapter calls the pacer's public PlanAt seam.
start=code.index('namespace lsfg {')
end=code.index(pressure, start)
# Replace both deterministic doubles preceding the extracted Native type.
integration=code[:start]+r'''
#include "lsfg_pacer.hpp"
namespace lsfg { struct Device {}; struct LsfgShaders {}; struct LsfgChain { void ResetHistory() {} }; }
std::chrono::steady_clock::time_point simulationNow{};
struct ClockedPacer : lsfg::LsfgPacer {
    lsfg::LsfgPlan Plan(size_t capacity,uint64_t source) { return PlanAt(capacity,source,simulationNow); }
};
struct FakeFlowController : AdaptiveFlowController {
    AdaptiveFlowObservation last{};
    float observe(const AdaptiveFlowObservation& observation) { last=observation; return AdaptiveFlowController::observe(observation); }
};
'''+code[end:code.index('void require(bool ok,')]
integration=integration.replace('lsfg::LsfgPacer pacer;', 'ClockedPacer pacer;')+r'''
void require(bool ok,const char* text) { if(!ok) { std::fprintf(stderr,"FAIL integration: %s\n",text); std::exit(1); } }
uint64_t observations=0;
void drive(VkrLsfg& stream,unsigned count,double sourceFps,float gpu,float output,bool deliveryPressure) {
    for(unsigned i=0;i<count;++i) {
        simulationNow+=std::chrono::duration_cast<std::chrono::steady_clock::duration>(std::chrono::duration<double>(1.0/sourceFps));
        stream.presentation_pressure.confirmation_available=true;
        stream.presentation_pressure.pressure_active=deliveryPressure;
        stream.presentation_pressure.source_delivery_healthy=true;
        stream.presentation_pressure.generated_delivery_efficiency=deliveryPressure ? .2f : 1.f;
        vkr_lsfg_set_pressure(&stream,gpu,0,sourceFps,output,1000/sourceFps,0);
        const auto generated=vkr_lsfg_plan(&stream,3,++stream.frame_count); ++observations;
        stream.last_generations=generated;
        require(generated<=3,"all Native plans remain within capacity");
        if(stream.pacer.Config().target_rate>0) require(generated<=stream.adaptive_generation_cap,"density cap bounds actual fractional scheduler selection");
        else require(generated<=stream.pacer.Config().multiplier-1,"Fixed multiplier bounds actual scheduler selection");
        require(std::isfinite(stream.active_flow_scale) && stream.active_flow_scale>=.25f && stream.active_flow_scale<=1,"Flow remains finite within setting bounds");
    }
}
int main() {
    for(unsigned multiplier: {2u,3u,4u}) for(bool flow: {false,true}) {
        VkrLsfg fixed; vkr_lsfg_configure(&fixed,multiplier,0,.8f,flow ? 1 : 0,0,120,1);
        drive(fixed,180,30,61,30*multiplier,false);
        require(fixed.plan.generations==multiplier-1,"actual Fixed 2x/3x/4x generation survives healthy source cadence");
        drive(fixed,120,60,61,std::min(120u,60*multiplier),false);
        require(fixed.plan.generations==multiplier-1,"Fixed generation remains authoritative across source acceleration");
        vkr_lsfg_configure(&fixed,multiplier,0,.7f,flow ? 1 : 0,1,120,2);
        drive(fixed,120,30,61,30*multiplier,false);
        require(fixed.plan.generations==multiplier-1,"Flow settings transition does not collapse Fixed generation");
        for(float gpu: {61.f,-1.f}) {
            VkrLsfg adaptive; vkr_lsfg_configure(&adaptive,multiplier,120,.8f,flow ? 1 : 0,0,120,1);
            drive(adaptive,180,30,gpu,120,false);
            require(adaptive.plan.generations==3 && adaptive.adaptive_generation_cap==3,"actual Adaptive scheduler delivers 4x at 30/120");
            const float quality=adaptive.active_flow_scale;
            drive(adaptive,180,30,gpu,30,true);
            require(adaptive.adaptive_generation_cap==0,"actual scheduler/controller interaction safely reaches source-only under persistent WSI loss");
            require(adaptive.active_flow_scale>=quality-.001f,"downstream loss alone does not lower Flow quality");
            // Source-only cannot observe a healed generated path. Establish a
            // sustained material source30->20 workload improvement first.
            drive(adaptive,420,20,gpu<0 ? -1.f : 45.f,80,false);
            require(adaptive.plan.generations==3 && adaptive.adaptive_generation_cap==3,"real fractional scheduler recovers full generation with and without GPU sensor");
            vkr_lsfg_configure(&adaptive,multiplier,90,.8f,flow ? 1 : 0,0,120,2);
            drive(adaptive,180,30,gpu,90,false);
            require(adaptive.plan.generations==2,"target change converges to real 3x density");
            drive(adaptive,240,60,gpu,90,false);
            require(adaptive.pacer.Stats().source_rate>59 && adaptive.pacer.Stats().source_rate<61,"source acceleration converges without output/source units alias");
            vkr_lsfg_set_refresh_rate(&adaptive,60);
            drive(adaptive,180,30,gpu,60,false);
            require(adaptive.plan.generations==1,"panel transition converges to real 2x physical target");
            simulationNow+=std::chrono::seconds(2);
            drive(adaptive,1,30,gpu,60,false);
            require(!adaptive.flow_controller.last.valid,"source resume gap does not authorize Flow decisions");
            drive(adaptive,120,30,gpu,60,false);
            require(adaptive.plan.generations==1,"source resume reestablishes predictable density");
        }
    }
    std::printf("PASS: real Native pacer + shared scheduler + Adaptive Flow interaction: %llu source cycles\n",(unsigned long long)observations);
}
'''
with tempfile.TemporaryDirectory(prefix='native-governor-integration-') as directory:
    cpp,binary=Path(directory)/'test.cpp',Path(directory)/'test'
    cpp.write_text(integration)
    shared=ROOT/'app/src/main/cpp/lsfg-vk-android'
    native_dir=ROOT/'app/src/main/cpp/lsfg'
    subprocess.run(['g++','-std=c++20','-Wall','-Wextra','-Werror','-I',str(shared/'include'),'-I',str(native_dir),str(cpp),str(native_dir/'lsfg_pacer.cpp'),str(shared/'src/adaptive_scheduler.cpp'),str(shared/'src/adaptive_flow_controller.cpp'),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
