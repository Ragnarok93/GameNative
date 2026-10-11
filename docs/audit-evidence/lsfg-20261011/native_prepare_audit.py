"""Extract the production prepare method; fake allocation at its platform boundary."""
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[3]
source = (repo / 'app/src/main/cpp/lsfg/vkr_lsfg.cpp').read_text()
start = source.index('bool vkr_lsfg_prepare(')
end = source.index('\nuint32_t vkr_lsfg_plan(', start)
method = source[start:end]
prefix = r'''
#include <algorithm>
#include <memory>
#include <cstdio>
#include <cstdint>
#define LSFG_LOGW(...) ((void)0)
#define LSFG_LOGI(...) ((void)0)
struct VkExtent2D { uint32_t width{}, height{}; };
using VkFormat = int;
constexpr int VK_FORMAT_UNDEFINED = 0;
bool allocationFails = false;
int buildAttempts = 0;
namespace lsfg {
struct LsfgImagePair {};
struct Device {};
struct LsfgShaders {};
struct LsfgChain {
 LsfgImagePair frames;
 bool valid = true;
 LsfgChain() = default;
 LsfgChain(Device&, LsfgShaders&, VkExtent2D, VkFormat, float, LsfgImagePair*) {
  ++buildAttempts; valid = !allocationFails;
 }
 bool Valid() { return valid; }
 LsfgImagePair& SourceFrames() { return frames; }
};
struct Pacer { void Reset() {} };
}
struct VkrLsfg {
 bool unavailable = false;
 lsfg::Device device;
 std::unique_ptr<lsfg::LsfgShaders> shaders = std::make_unique<lsfg::LsfgShaders>();
 std::unique_ptr<lsfg::LsfgChain> chain = std::make_unique<lsfg::LsfgChain>();
 VkExtent2D built_extent{1920,1080}, peak_guest_extent{1920,1080};
 VkFormat built_format = 1;
 float built_flow_scale = .8f, requested_flow_scale = 1.f;
 bool adaptive_flow = true;
 unsigned frame_count{}, plan_calls{}, warm_streak{}, flow_transition_frames{};
 bool warm{}, generated{};
 lsfg::Pacer pacer;
};
bool vkr_lsfg_needs_rebuild(VkrLsfg*,uint32_t,uint32_t,VkFormat) { return true; }
float lsfg_effective_flow_scale(VkrLsfg* r,uint32_t) { return r->requested_flow_scale; }
'''
suffix = r'''
int main() {
 VkrLsfg r;
 allocationFails = true;
 bool first = vkr_lsfg_prepare(&r,1920,1080,1);
 allocationFails = false; r.requested_flow_scale = .7f;
 bool retry = vkr_lsfg_prepare(&r,1920,1080,1);
 std::printf("first=%d allocation_healed_retry=%d unavailable=%d chain_exists=%d build_attempts=%d\n",
             first,retry,r.unavailable,!!r.chain,buildAttempts);
}
'''
with tempfile.TemporaryDirectory() as temp:
    cpp = Path(temp) / 'prepare.cpp'
    binary = Path(temp) / 'prepare'
    cpp.write_text(prefix + method + suffix)
    subprocess.run(['g++', '-std=c++20', str(cpp), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
