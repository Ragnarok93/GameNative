#include "apex_vk_backend.h"

#include "apex_vk_luma_grad.h"
#include "apex_vk_inverse_search.h"
#include "apex_vk_propagate.h"
#include "apex_vk_densify.h"
#include "apex_vk_vr_setup.h"
#include "apex_vk_vr_sor.h"
#include "apex_vk_interpolate.h"
#include "apex_vk_rcas.h"

#include <array>

namespace gamenative::apex::vk {
namespace {

constexpr const char* kBackendVersion = "gamenative-apex-vulkan-compute-v1";

VkDescriptorSetLayoutBinding binding(uint32_t slot, VkDescriptorType type) {
    VkDescriptorSetLayoutBinding out{};
    out.binding = slot;
    out.descriptorType = type;
    out.descriptorCount = 1;
    out.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    return out;
}

bool validDispatch(const Dispatch& d) {
    return d.CreateDescriptorSetLayout &&
        d.DestroyDescriptorSetLayout &&
        d.CreatePipelineLayout &&
        d.DestroyPipelineLayout &&
        d.CreateShaderModule &&
        d.DestroyShaderModule &&
        d.CreateComputePipelines &&
        d.DestroyPipeline &&
        d.CmdBindPipeline &&
        d.CmdBindDescriptorSets &&
        d.CmdPushConstants &&
        d.CmdDispatch;
}

} // namespace

Backend::~Backend() {
    destroy();
}

bool Backend::initialize(const Context& context) {
    destroy();
    context_ = context;

    if (context_.physicalDevice == VK_NULL_HANDLE ||
        context_.device == VK_NULL_HANDLE ||
        context_.queue == VK_NULL_HANDLE ||
        !validDispatch(context_.dispatch)) {
        diagnostics_ = "missing renderer-owned Vulkan device/queue/dispatch";
        return false;
    }

    const std::array<VkDescriptorSetLayoutBinding, 4> lumaBindings{
        binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
    };
    const std::array<VkDescriptorSetLayoutBinding, 6> searchBindings{
        binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
    };
    const std::array<VkDescriptorSetLayoutBinding, 5> propagateBindings{
        binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
    };
    const auto densifyBindings = propagateBindings;
    const std::array<VkDescriptorSetLayoutBinding, 6> vrSetupBindings{
        binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(5, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
    };
    const std::array<VkDescriptorSetLayoutBinding, 4> vrSorBindings{
        binding(0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
    };
    const std::array<VkDescriptorSetLayoutBinding, 5> interpolateBindings{
        binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
        binding(5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
    };
    const std::array<VkDescriptorSetLayoutBinding, 2> rcasBindings{
        binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
        binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
    };

    struct StageCreate {
        Stage stage;
        const char* name;
        const uint32_t* code;
        size_t bytes;
        const VkDescriptorSetLayoutBinding* bindings;
        uint32_t bindingCount;
        uint32_t pushBytes;
    };
    const std::array<StageCreate, kStageCount> creates{{
        {Stage::LumaGrad, "luma_grad", apex_vk_luma_grad_code, sizeof(apex_vk_luma_grad_code), lumaBindings.data(), static_cast<uint32_t>(lumaBindings.size()), 8},
        {Stage::InverseSearch, "inverse_search", apex_vk_inverse_search_code, sizeof(apex_vk_inverse_search_code), searchBindings.data(), static_cast<uint32_t>(searchBindings.size()), 12},
        {Stage::Propagate, "propagate", apex_vk_propagate_code, sizeof(apex_vk_propagate_code), propagateBindings.data(), static_cast<uint32_t>(propagateBindings.size()), 12},
        {Stage::Densify, "densify", apex_vk_densify_code, sizeof(apex_vk_densify_code), densifyBindings.data(), static_cast<uint32_t>(densifyBindings.size()), 8},
        {Stage::VrSetup, "vr_setup", apex_vk_vr_setup_code, sizeof(apex_vk_vr_setup_code), vrSetupBindings.data(), static_cast<uint32_t>(vrSetupBindings.size()), 0},
        {Stage::VrSor, "vr_sor", apex_vk_vr_sor_code, sizeof(apex_vk_vr_sor_code), vrSorBindings.data(), static_cast<uint32_t>(vrSorBindings.size()), 8},
        {Stage::Interpolate, "interpolate", apex_vk_interpolate_code, sizeof(apex_vk_interpolate_code), interpolateBindings.data(), static_cast<uint32_t>(interpolateBindings.size()), 20},
        {Stage::Rcas, "rcas", apex_vk_rcas_code, sizeof(apex_vk_rcas_code), rcasBindings.data(), static_cast<uint32_t>(rcasBindings.size()), 4},
    }};

    for (const auto& create : creates) {
        if (!createStage(
                create.stage,
                create.name,
                create.code,
                create.bytes,
                create.bindings,
                create.bindingCount,
                create.pushBytes)) {
            destroy();
            return false;
        }
    }

    healthy_ = true;
    diagnostics_ = std::string(kBackendVersion) +
        " ready: 8/8 compute pipelines on renderer-owned VkDevice";
    return true;
}

bool Backend::createStage(
    Stage stage,
    const char* name,
    const uint32_t* spirv,
    size_t spirvBytes,
    const VkDescriptorSetLayoutBinding* bindings,
    uint32_t bindingCount,
    uint32_t pushConstantBytes) {
    const size_t index = static_cast<size_t>(stage);
    if (index >= stages_.size() || !spirv || spirvBytes == 0) return false;
    auto& state = stages_[index];
    const auto& d = context_.dispatch;

    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = bindingCount;
    descriptorInfo.pBindings = bindings;
    if (d.CreateDescriptorSetLayout(
            context_.device,
            &descriptorInfo,
            nullptr,
            &state.descriptorSetLayout) != VK_SUCCESS) {
        diagnostics_ = std::string(name) + ": descriptor-set layout creation failed";
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0;
    pushRange.size = pushConstantBytes;

    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &state.descriptorSetLayout;
    if (pushConstantBytes > 0) {
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
    }
    if (d.CreatePipelineLayout(
            context_.device,
            &layoutInfo,
            nullptr,
            &state.pipelineLayout) != VK_SUCCESS) {
        diagnostics_ = std::string(name) + ": pipeline layout creation failed";
        return false;
    }

    VkShaderModuleCreateInfo moduleInfo{};
    moduleInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    moduleInfo.codeSize = spirvBytes;
    moduleInfo.pCode = spirv;
    VkShaderModule module = VK_NULL_HANDLE;
    if (d.CreateShaderModule(
            context_.device,
            &moduleInfo,
            nullptr,
            &module) != VK_SUCCESS) {
        diagnostics_ = std::string(name) + ": shader module creation failed";
        return false;
    }

    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = module;
    stageInfo.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = state.pipelineLayout;
    const VkResult result = d.CreateComputePipelines(
        context_.device,
        VK_NULL_HANDLE,
        1,
        &pipelineInfo,
        nullptr,
        &state.pipeline);
    d.DestroyShaderModule(context_.device, module, nullptr);
    if (result != VK_SUCCESS) {
        diagnostics_ = std::string(name) + ": compute pipeline creation failed";
        return false;
    }

    state.pushConstantBytes = pushConstantBytes;
    return true;
}

void Backend::destroy() {
    if (context_.device != VK_NULL_HANDLE) {
        const auto& d = context_.dispatch;
        for (auto& stage : stages_) {
            if (stage.pipeline != VK_NULL_HANDLE && d.DestroyPipeline) {
                d.DestroyPipeline(context_.device, stage.pipeline, nullptr);
            }
            if (stage.pipelineLayout != VK_NULL_HANDLE && d.DestroyPipelineLayout) {
                d.DestroyPipelineLayout(context_.device, stage.pipelineLayout, nullptr);
            }
            if (stage.descriptorSetLayout != VK_NULL_HANDLE && d.DestroyDescriptorSetLayout) {
                d.DestroyDescriptorSetLayout(
                    context_.device,
                    stage.descriptorSetLayout,
                    nullptr);
            }
            stage = {};
        }
    } else {
        for (auto& stage : stages_) stage = {};
    }
    healthy_ = false;
    context_ = {};
}

VkDescriptorSetLayout Backend::descriptorSetLayout(Stage stage) const {
    const size_t index = static_cast<size_t>(stage);
    return index < stages_.size()
        ? stages_[index].descriptorSetLayout
        : VK_NULL_HANDLE;
}

VkPipelineLayout Backend::pipelineLayout(Stage stage) const {
    const size_t index = static_cast<size_t>(stage);
    return index < stages_.size()
        ? stages_[index].pipelineLayout
        : VK_NULL_HANDLE;
}

void Backend::record(
    Stage stage,
    VkCommandBuffer commandBuffer,
    VkDescriptorSet descriptorSet,
    uint32_t groupX,
    uint32_t groupY,
    uint32_t groupZ,
    const void* pushConstants,
    uint32_t pushConstantBytes) const {
    if (!healthy_ ||
        commandBuffer == VK_NULL_HANDLE ||
        descriptorSet == VK_NULL_HANDLE ||
        groupX == 0 ||
        groupY == 0 ||
        groupZ == 0) {
        return;
    }

    const size_t index = static_cast<size_t>(stage);
    if (index >= stages_.size()) return;
    const auto& state = stages_[index];
    const auto& d = context_.dispatch;
    if (state.pipeline == VK_NULL_HANDLE ||
        state.pipelineLayout == VK_NULL_HANDLE) {
        return;
    }

    d.CmdBindPipeline(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state.pipeline);
    d.CmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state.pipelineLayout,
        0,
        1,
        &descriptorSet,
        0,
        nullptr);

    if (state.pushConstantBytes > 0) {
        if (!pushConstants || pushConstantBytes != state.pushConstantBytes) return;
        d.CmdPushConstants(
            commandBuffer,
            state.pipelineLayout,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            pushConstantBytes,
            pushConstants);
    }

    d.CmdDispatch(commandBuffer, groupX, groupY, groupZ);
}

} // namespace gamenative::apex::vk
