"""Exercise production presentation/cadence/feedback code without an Android device."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / "app/src/main/cpp/winlator"
NATIVE = ROOT / "app/src/main/cpp/lsfg"
LAYER = ROOT / "app/src/main/cpp/lsfg-vk-android"

def function(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]

def run(name, code, extra=(), includes=()):
    with tempfile.TemporaryDirectory(prefix="lsfg-regression-") as directory:
        cpp, binary = Path(directory) / "test.cpp", Path(directory) / "test"
        cpp.write_text(code)
        subprocess.run(["g++", "-std=c++20", "-Wall", "-Wextra", "-Werror",
                        *includes, str(cpp), *map(str, extra), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print("PASS:", name)

host = (HOST / "VulkanRendererContext.cpp").read_text()
slot_methods = "\n".join(function(host, signature) for signature in (
    "void VulkanRendererContext::resetLegacyGeneratedOutputSlotClock(",
    "bool VulkanRendererContext::legacyGeneratedOutputSlotMissed(",
    "bool VulkanRendererContext::assignLegacyGeneratedOutputSlot(",
))
run("sparse slot 3, changing density, handoff epochs, and stale intent", r"""
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cinttypes>
constexpr int ANDROID_LOG_INFO=4, ANDROID_LOG_WARN=5;
constexpr unsigned VKR_LSFG_MAX_GENERATIONS=3;
void __android_log_print(int,const char*,const char*,...) {}
uint64_t now=1000000000;
uint64_t monotonicTimeNs() { return now; }
struct LsfgFrameProvenance {
    bool valid=true, nativeImplementation=false;
    unsigned kind=1, interpolationIndex=1, interpolationCount=1;
    uint64_t contextEpoch=1, desiredPresentTimeNs=0, sourceIndex=0;
    uint64_t outputSlotIndex=0, outputSlotIntendedPresentTimeNs=0;
};
struct VulkanRendererContext {
    uint64_t legacyGeneratedSlotContextEpoch_=0, legacyGeneratedSlotCounter_=0;
    uint64_t hostSwapchainGeneration_=1, hostPhysicalCadenceEpoch_=1;
    uint64_t hostRefreshPeriodNs_=8333333;
    void resetLegacyGeneratedOutputSlotClock(const char*);
    bool legacyGeneratedOutputSlotMissed(const LsfgFrameProvenance&,uint64_t) const;
    bool assignLegacyGeneratedOutputSlot(LsfgFrameProvenance&);
};
""" + slot_methods + r"""
int main() {
    VulkanRendererContext c;
    LsfgFrameProvenance p;
    for (unsigned batch=0; batch<600; ++batch) {
        // Third lane appears once, disappears for 14 seconds, then returns.
        now=1000000000ULL + batch*33333333ULL;
        p.sourceIndex=batch;
        for (unsigned slot=1; slot <= ((batch==0 || batch>420) ? 3u : 1u); ++slot) {
            p.interpolationIndex=slot;
            p.desiredPresentTimeNs=now+slot*6000000ULL;
            const auto intended=p.desiredPresentTimeNs;
            assert(c.assignLegacyGeneratedOutputSlot(p));
            assert(p.outputSlotIntendedPresentTimeNs==intended);
            assert(p.desiredPresentTimeNs==intended);
        }
    }
    p.contextEpoch=2; p.interpolationIndex=3; p.desiredPresentTimeNs=now+20000000;
    assert(c.assignLegacyGeneratedOutputSlot(p) && p.outputSlotIndex==1);
    p.desiredPresentTimeNs=now-14000000000ULL;
    const auto stale=p.desiredPresentTimeNs;
    assert(!c.assignLegacyGeneratedOutputSlot(p));
    assert(p.desiredPresentTimeNs==stale); // Never relabel late output as current.
}
""")

layer = (LAYER / "src/context.cpp").read_text()
packet_start = layer.index("enum class HostDisplayFeedbackStatus")
packet_end = layer.index("int hostDisplayFeedbackSocketFd", packet_start)
packets = layer[packet_start:packet_end]
poll = function(layer, "void pollHostDisplayFeedback(")
run("physical feedback filters retired revisions/epochs and distinguishes unavailable", r"""
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cerrno>
#include <cstring>
#include <deque>
#include <type_traits>
#include <sys/types.h>
constexpr int MSG_DONTWAIT=1, ANDROID_LOG_WARN=5, ANDROID_LOG_INFO=4;
constexpr uint32_t kHostDisplayFeedbackMagic=0x4c534644;
constexpr uint16_t kHostDisplayFeedbackVersion=2;
enum class HostFrameKind : uint8_t { Source, Generated };
namespace Config { struct Configuration { uint64_t transactionId=7, configurationRevision=11; }; }
void __android_log_print(int,const char*,const char*,...) {}
int ensureHostDisplayFeedbackSocket() { return 1; }
""" + packets + r"""
std::deque<HostDisplayFeedbackPacket> incoming;
ssize_t recvfrom(int,void* data,size_t size,int,void*,void*) {
    if(incoming.empty()) { errno=EAGAIN; return -1; }
    assert(size==sizeof(HostDisplayFeedbackPacket));
    std::memcpy(data,&incoming.front(),size); incoming.pop_front(); return size;
}
""" + poll + r"""
int main() {
    Config::Configuration conf;
    HostDisplayFeedbackPacket p;
    p.runtimeSessionId=3; p.contextEpoch=5; p.transactionId=7;
    p.configurationRevision=11; p.deliveryId=1;
    p.status=static_cast<uint8_t>(HostDisplayFeedbackStatus::Confirmed);
    incoming.push_back(p);
    p.configurationRevision=10; incoming.push_back(p);
    p.configurationRevision=11; p.contextEpoch=4; incoming.push_back(p);
    p.contextEpoch=5; p.kind=1; p.deliveryId=2; incoming.push_back(p);
    pollHostDisplayFeedback(3,5,conf);
    assert(hostDisplayFeedbackStats.rxTotal==2);
    assert(hostDisplayFeedbackStats.sourceConfirmedDelta==1);
    assert(hostDisplayFeedbackStats.generatedConfirmedDelta==1);
    assert(hostDisplayFeedbackStats.confirmationAvailable);
    pollHostDisplayFeedback(3,5,conf);
    assert(hostDisplayFeedbackStats.sourceConfirmedDelta==0);
    assert(hostDisplayFeedbackStats.generatedConfirmedDelta==0);
    p.status=static_cast<uint8_t>(HostDisplayFeedbackStatus::Unavailable); incoming.push_back(p);
    pollHostDisplayFeedback(3,5,conf);
    assert(!hostDisplayFeedbackStats.confirmationAvailable);
    conf.configurationRevision=12;
    p.status=1; incoming.push_back(p);
    pollHostDisplayFeedback(3,5,conf);
    assert(hostDisplayFeedbackStats.rxTotal==0);
}
""")

run("confirmed cadence measures 22 rather than 70 logical FPS and retains measured zero", r"""
#include "adaptive_scheduler.hpp"
#include <cassert>
#include <chrono>
#include <cmath>
int main() {
    LsfgOutputCadenceTracker tracker;
    tracker.configure(true,60);
    for(unsigned i=0; i<140; ++i)
        tracker.observe(std::chrono::milliseconds(100),
                        i%5==0 ? 3 : 2, 0);
    assert(tracker.snapshot().valid);
    assert(tracker.snapshot().outputFps >=20 && tracker.snapshot().outputFps<=23);
    assert(tracker.snapshot().deficitConfirmed);
    assert(!tracker.snapshot().targetSatisfiedConfirmed);
    for(unsigned i=0; i<30; ++i)
        tracker.observe(std::chrono::milliseconds(100),0,0);
    assert(tracker.snapshot().valid && tracker.snapshot().outputFps==0);
    assert(tracker.snapshot().deficitConfirmed);
    tracker.reset();
    assert(!tracker.snapshot().valid);
}
""", extra=(LAYER / "src/adaptive_scheduler.cpp",), includes=("-I",str(LAYER / "include")))

vertex = (HOST / "window.vert").read_text()
rotation = vertex[vertex.index("    uint transform"):vertex.index("    gl_Position")]
rotation = rotation.replace("uint transform = pc.surfaceTransform;", "uint32_t transform = t;")
run("all inverse surface rotations/mirrors preserve scene and cursor orientation", r"""
#include <cassert>
#include <cstdint>
#include <utility>
std::pair<float,float> inverse(float x,float y,uint32_t t) {
""" + rotation + r"""
    return {x,y};
}
int main() {
    for(uint32_t t : {1u,2u,4u,8u,16u,32u,64u,128u}) {
        for(auto point : {std::pair{-.7f,.2f},std::pair{.5f,-.3f}}) {
            auto [x,y]=inverse(point.first,point.second,t);
            if(t & 240u) x=-x;
            if(t==2 || t==32) { float previousX=x; x=-y; y=previousX; }
            else if(t==4 || t==64) { x=-x; y=-y; }
            else if(t==8 || t==128) { float previousX=x; x=y; y=-previousX; }
            assert(x==point.first && y==point.second);
        }
    }
}
""")

common = (NATIVE / "lsfg_common.cpp").read_text()
shaders = (NATIVE / "lsfg_shaders.cpp").read_text()
passes = common[common.index("LsfgPass::LsfgPass(const Device&"):
                common.index("void LsfgPass::Bind(")]
cache = "\n".join(function(shaders, sig) for sig in (
    "std::vector<uint64_t> PassKey(",
    "const LsfgShaders::PassHandles* LsfgShaders::FindPass(",
    "void LsfgShaders::CachePass(",
    "void LsfgShaders::Release()",
))
run("flow resource changes reuse pipelines and destroy shared handles exactly once", r"""
#include <cassert>
#include <cstdint>
#include <vector>
#include <map>
#include <initializer_list>
#include <utility>
using VkDevice=uint64_t; using VkShaderModule=uint64_t;
using VkDescriptorSetLayout=uint64_t; using VkPipelineLayout=uint64_t; using VkPipeline=uint64_t;
using VkDescriptorType=uint32_t;
constexpr int VK_SUCCESS=0, VK_NULL_HANDLE=0;
constexpr int VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO=1;
constexpr int VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO=2;
constexpr int VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO=3;
constexpr int VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO=4;
constexpr int VK_SHADER_STAGE_COMPUTE_BIT=32;
struct VkDescriptorSetLayoutBinding {uint32_t binding,descriptorType,descriptorCount,stageFlags;};
struct VkDescriptorSetLayoutCreateInfo {
    int sType; uint32_t bindingCount; const VkDescriptorSetLayoutBinding* pBindings;
};
struct VkPipelineLayoutCreateInfo {
    int sType; uint32_t setLayoutCount; const VkDescriptorSetLayout* pSetLayouts;
};
struct VkPipelineShaderStageCreateInfo {int sType,stage; uint64_t module; const char* pName;};
struct VkComputePipelineCreateInfo {int sType; VkPipelineShaderStageCreateInfo stage; VkPipelineLayout layout;};
struct Dispatch {
    uint64_t next=1; unsigned created=0, destroyed=0; bool fail=false;
    int CreateDescriptorSetLayout(uint64_t,const VkDescriptorSetLayoutCreateInfo*,void*,uint64_t* out) {
        *out=next++; return 0;
    }
    int CreatePipelineLayout(uint64_t,const VkPipelineLayoutCreateInfo*,void*,uint64_t* out) {
        *out=next++; return 0;
    }
    int CreateComputePipelines(uint64_t,uint64_t,unsigned,const VkComputePipelineCreateInfo*,void*,uint64_t* out) {
        if(fail) return -1;
        ++created; *out=next++; return 0;
    }
    void DestroyPipeline(uint64_t,uint64_t,void*) { ++destroyed; }
    void DestroyPipelineLayout(uint64_t,uint64_t,void*) {}
    void DestroyDescriptorSetLayout(uint64_t,uint64_t,void*) {}
    void DestroyShaderModule(uint64_t,uint64_t,void*) {}
} vkd;
struct Device { uint64_t Handle() const { return 1; } };
using LsfgBindings=std::initializer_list<std::pair<uint32_t,VkDescriptorType>>;
struct LsfgShaders {
    struct PassHandles {VkDescriptorSetLayout setLayout; VkPipelineLayout pipelineLayout;
                        VkPipeline pipeline; uint32_t descriptorCount;};
    VkDevice device=1;
    mutable std::map<std::vector<uint64_t>,PassHandles> passes;
    std::map<uint32_t,VkShaderModule> modules;
    bool valid=true;
    uint64_t Get(uint32_t shader) const { return shader; }
    const PassHandles* FindPass(uint32_t,LsfgBindings) const;
    void CachePass(uint32_t,LsfgBindings,PassHandles) const;
    void Release();
    ~LsfgShaders() { Release(); }
};
struct LsfgPass {
    VkDevice device=0; VkDescriptorSetLayout descriptor_set_layout=0;
    VkPipelineLayout pipeline_layout=0; VkPipeline pipeline=0;
    uint32_t descriptor_count=0; bool cached=false;
    LsfgPass()=default;
    LsfgPass(const Device&,const LsfgShaders&,uint32_t,LsfgBindings);
    ~LsfgPass();
    LsfgPass(LsfgPass&&) noexcept;
    LsfgPass& operator=(LsfgPass&&) noexcept;
    void Release();
};
""" + cache + passes + r"""
int main() {
    {
        Device device;
        LsfgShaders shaders;
        {
            LsfgPass first(device,shaders,280,{{1,1},{2,2}});
            LsfgPass second(device,shaders,280,{{1,1},{2,2}});
            assert(first.pipeline==second.pipeline && vkd.created==1);
            LsfgPass moved(std::move(first));
            LsfgPass assigned; assigned=std::move(second);
            assert(moved.pipeline==assigned.pipeline);
        }
        assert(vkd.destroyed==0); // Cache remains alive across graph destruction.
        {
            LsfgPass scaleChange(device,shaders,280,{{1,1},{2,2}});
            assert(vkd.created==1);
            LsfgPass distinctLayout(device,shaders,280,{{2,1},{1,2}});
            assert(vkd.created==2 && distinctLayout.pipeline!=scaleChange.pipeline);
            vkd.fail=true;
            LsfgPass failed(device,shaders,299,{{1,1}});
            assert(failed.pipeline==0 && !shaders.FindPass(299,{{1,1}}));
        }
    }
    assert(vkd.destroyed==2);
}
""")
