#include "../apex_gpu_profile.h"

#include <cassert>
#include <iostream>

using gamenative::apex::GpuCapabilities;
using gamenative::apex::GpuProfile;
using gamenative::apex::MotionStorage;
using gamenative::apex::selectGpuProfile;

static GpuCapabilities fullCaps() {
    return {
        .glesMajor = 3,
        .glesMinor = 2,
        .maxComputeInvocations = 256,
        .maxComputeSharedMemoryBytes = 32768,
        .rgba8ImageStore = true,
        .rgba16fImageStore = true,
        .textureFetchBarrier = true,
    };
}

int main() {
    {
        const auto d = selectGpuProfile("Qualcomm", "Adreno (TM) 650", fullCaps());
        assert(d.profile == GpuProfile::Adreno6xxPlus);
        assert(d.motionStorage == MotionStorage::Rgba16f);
        assert(d.allowFullDisHierarchy);
        assert(d.preferredWorkgroupInvocations == 128);
    }
    {
        auto caps = fullCaps();
        caps.rgba16fImageStore = false;
        const auto d = selectGpuProfile("Qualcomm", "Adreno (TM) 650", caps);
        assert(d.profile == GpuProfile::PortableFallback);
        assert(d.motionStorage == MotionStorage::Rgba8);
        assert(!d.allowFullDisHierarchy);
    }
    {
        const auto d = selectGpuProfile("Samsung", "Xclipse 940", fullCaps());
        assert(d.profile == GpuProfile::XclipseCompatibility);
        assert(d.motionStorage == MotionStorage::Rgba8);
        assert(!d.allowFullDisHierarchy);
        assert(d.preferredWorkgroupInvocations == 64);
    }
    {
        auto caps = fullCaps();
        caps.glesMinor = 0;
        const auto d = selectGpuProfile("Qualcomm", "Adreno (TM) 650", caps);
        assert(d.profile == GpuProfile::Unsupported);
    }
    {
        const auto d = selectGpuProfile("ARM", "Mali-G715", fullCaps());
        assert(d.profile == GpuProfile::PortableFallback);
    }

    std::cout << "Apex GPU profile tests passed\n";
    return 0;
}
