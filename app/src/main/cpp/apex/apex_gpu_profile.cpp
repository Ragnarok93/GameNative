#include "apex_gpu_profile.h"

#include <algorithm>
#include <cctype>

namespace gamenative::apex {
namespace {

std::string lower(std::string_view input) {
    std::string value(input);
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

bool hasGles31(const GpuCapabilities& capabilities) {
    return capabilities.glesMajor > 3 ||
        (capabilities.glesMajor == 3 && capabilities.glesMinor >= 1);
}

int parseAdrenoModel(std::string_view renderer) {
    const std::string value = lower(renderer);
    const size_t marker = value.find("adreno");
    if (marker == std::string::npos) return 0;

    size_t pos = marker + 6;
    while (pos < value.size() && !std::isdigit(static_cast<unsigned char>(value[pos]))) ++pos;

    int model = 0;
    int digits = 0;
    while (pos < value.size() && std::isdigit(static_cast<unsigned char>(value[pos])) && digits < 4) {
        model = model * 10 + (value[pos] - '0');
        ++pos;
        ++digits;
    }
    return digits >= 3 ? model : 0;
}

bool isXclipse(std::string_view vendor, std::string_view renderer) {
    const std::string v = lower(vendor);
    const std::string r = lower(renderer);
    return r.find("xclipse") != std::string::npos ||
        (v.find("samsung") != std::string::npos && r.find("amd") != std::string::npos);
}

} // namespace

GpuProfileDecision selectGpuProfile(
    std::string_view vendor,
    std::string_view renderer,
    const GpuCapabilities& capabilities) {
    if (!hasGles31(capabilities) ||
        !capabilities.rgba8ImageStore ||
        !capabilities.textureFetchBarrier ||
        capabilities.maxComputeInvocations < 64) {
        return {};
    }

    if (isXclipse(vendor, renderer)) {
        return {
            .profile = GpuProfile::XclipseCompatibility,
            .motionStorage = MotionStorage::Rgba8,
            .allowFullDisHierarchy = false,
            .preferredWorkgroupInvocations = 64,
        };
    }

    const int adrenoModel = parseAdrenoModel(renderer);
    if (adrenoModel >= 600 &&
        capabilities.rgba16fImageStore &&
        capabilities.maxComputeInvocations >= 128 &&
        capabilities.maxComputeSharedMemoryBytes >= 16384) {
        return {
            .profile = GpuProfile::Adreno6xxPlus,
            .motionStorage = MotionStorage::Rgba16f,
            .allowFullDisHierarchy = true,
            .preferredWorkgroupInvocations = 128,
        };
    }

    return {
        .profile = GpuProfile::PortableFallback,
        .motionStorage = MotionStorage::Rgba8,
        .allowFullDisHierarchy = false,
        .preferredWorkgroupInvocations = 64,
    };
}

const char* gpuProfileName(GpuProfile profile) {
    switch (profile) {
        case GpuProfile::Adreno6xxPlus: return "adreno-6xx-plus";
        case GpuProfile::XclipseCompatibility: return "xclipse-compatibility";
        case GpuProfile::PortableFallback: return "portable-fallback";
        case GpuProfile::Unsupported:
        default: return "unsupported";
    }
}

} // namespace gamenative::apex
