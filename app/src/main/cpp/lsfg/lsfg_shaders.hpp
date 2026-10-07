// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <cstdint>
#include <map>
#include <string>
#include "lsfg_common.hpp"

namespace lsfg {

class Device;

class LsfgShaders {
public:
    LsfgShaders() = default;
    LsfgShaders(const Device& device, const std::string& cache_path);
    ~LsfgShaders();

    LsfgShaders(const LsfgShaders&) = delete;
    LsfgShaders& operator=(const LsfgShaders&) = delete;

    [[nodiscard]] bool IsValid() const {
        return valid;
    }

    [[nodiscard]] VkShaderModule Get(uint32_t shader_id) const;

    struct PassHandles {
        VkDescriptorSetLayout setLayout;
        VkPipelineLayout pipelineLayout;
        VkPipeline pipeline;
        uint32_t descriptorCount;
    };
    // Pipelines depend on shader + descriptor layout, never Flow Scale.
    // The shader owner outlives all chains and owns these shared handles.
    [[nodiscard]] const PassHandles* FindPass(uint32_t shader, LsfgBindings bindings) const;
    void CachePass(uint32_t shader, LsfgBindings bindings, PassHandles handles) const;

private:
    void Release();

    VkDevice device{VK_NULL_HANDLE};
    std::map<uint32_t, VkShaderModule> modules;
    mutable std::map<std::vector<uint64_t>, PassHandles> passes;
    bool valid{};
};

}