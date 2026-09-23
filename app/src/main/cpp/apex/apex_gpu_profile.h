#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace gamenative::apex {

enum class GpuProfile : int32_t {
    Unsupported = 0,
    Adreno6xxPlus = 1,
    XclipseCompatibility = 2,
    PortableFallback = 3,
};

enum class MotionStorage : int32_t {
    Rgba8 = 0,
    Rgba16f = 1,
};

struct GpuCapabilities {
    int32_t glesMajor{0};
    int32_t glesMinor{0};
    int32_t maxComputeInvocations{0};
    int32_t maxComputeSharedMemoryBytes{0};
    bool rgba8ImageStore{false};
    bool rgba16fImageStore{false};
    bool textureFetchBarrier{false};
};

struct GpuProfileDecision {
    GpuProfile profile{GpuProfile::Unsupported};
    MotionStorage motionStorage{MotionStorage::Rgba8};
    bool allowFullDisHierarchy{false};
    int32_t preferredWorkgroupInvocations{64};
};

GpuProfileDecision selectGpuProfile(
    std::string_view vendor,
    std::string_view renderer,
    const GpuCapabilities& capabilities);

const char* gpuProfileName(GpuProfile profile);

} // namespace gamenative::apex
