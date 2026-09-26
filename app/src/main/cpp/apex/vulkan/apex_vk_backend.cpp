#include "apex_vk_backend.h"

#include "apex_vk_luma_grad.h"
#include "apex_vk_inverse_search.h"
#include "apex_vk_propagate.h"
#include "apex_vk_densify.h"
#include "apex_vk_vr_setup.h"
#include "apex_vk_vr_sor.h"
#include "apex_vk_interpolate.h"
#include "apex_vk_rcas.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <vector>

namespace gamenative::apex::vk {
namespace {

constexpr const char* kBackendVersion = "gamenative-apex-vulkan-compute-v2";
constexpr VkDeviceSize kTelemetryBytes = 18u * sizeof(uint32_t);
constexpr uint64_t kDiscontinuityNanos = 250000000ULL;

VkDescriptorSetLayoutBinding binding(uint32_t slot, VkDescriptorType type) {
    VkDescriptorSetLayoutBinding out{};
    out.binding = slot;
    out.descriptorType = type;
    out.descriptorCount = 1;
    out.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    return out;
}

bool validPipelineDispatch(const Dispatch& d) {
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

bool validResourceDispatch(const Dispatch& d) {
    return d.GetPhysicalDeviceFormatProperties &&
        d.CreateImage &&
        d.DestroyImage &&
        d.AllocateMemory &&
        d.FreeMemory &&
        d.BindImageMemory &&
        d.GetImageMemoryRequirements &&
        d.CreateImageView &&
        d.DestroyImageView &&
        d.CreateBuffer &&
        d.DestroyBuffer &&
        d.BindBufferMemory &&
        d.GetBufferMemoryRequirements &&
        d.CreateDescriptorPool &&
        d.DestroyDescriptorPool &&
        d.ResetDescriptorPool &&
        d.AllocateDescriptorSets &&
        d.UpdateDescriptorSets &&
        d.CreateSampler &&
        d.DestroySampler &&
        d.CmdPipelineBarrier &&
        d.CmdCopyImage;
}

uint32_t ceilDiv(uint32_t value, uint32_t divisor) {
    return (value + divisor - 1u) / divisor;
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
        !validPipelineDispatch(context_.dispatch)) {
        diagnostics_ = "missing renderer-owned Vulkan device/queue/compute dispatch";
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

bool Backend::chooseFormats() {
    if (!context_.dispatch.GetPhysicalDeviceFormatProperties) return false;
    const VkFormatFeatureFlags required =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;

    auto supports = [&](VkFormat format) {
        VkFormatProperties props{};
        context_.dispatch.GetPhysicalDeviceFormatProperties(
            context_.physicalDevice,
            format,
            &props);
        return (props.optimalTilingFeatures & required) == required;
    };

    if (!supports(VK_FORMAT_R32_SFLOAT) ||
        !supports(VK_FORMAT_R8G8B8A8_UNORM)) {
        diagnostics_ = "required R32F/RGBA8 sampled-storage image support unavailable";
        return false;
    }

    if (supports(VK_FORMAT_R16G16B16A16_SFLOAT)) {
        motionFormat_ = VK_FORMAT_R16G16B16A16_SFLOAT;
    } else if (supports(VK_FORMAT_R32G32B32A32_SFLOAT)) {
        motionFormat_ = VK_FORMAT_R32G32B32A32_SFLOAT;
    } else {
        diagnostics_ = "no sampled-storage RGBA16F/RGBA32F motion format";
        return false;
    }
    return true;
}

uint32_t Backend::findMemoryType(
    uint32_t typeBits,
    VkMemoryPropertyFlags preferred) const {
    for (uint32_t i = 0; i < context_.memoryProperties.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) &&
            (context_.memoryProperties.memoryTypes[i].propertyFlags & preferred) == preferred) {
            return i;
        }
    }
    for (uint32_t i = 0; i < context_.memoryProperties.memoryTypeCount; ++i) {
        if (typeBits & (1u << i)) return i;
    }
    return std::numeric_limits<uint32_t>::max();
}

bool Backend::createImageResource(
    ImageResource& resource,
    uint32_t width,
    uint32_t height,
    VkFormat format,
    VkImageUsageFlags usage) {
    const auto& d = context_.dispatch;
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (d.CreateImage(context_.device, &imageInfo, nullptr, &resource.image) != VK_SUCCESS) {
        return false;
    }

    VkMemoryRequirements requirements{};
    d.GetImageMemoryRequirements(context_.device, resource.image, &requirements);
    const uint32_t memoryType = findMemoryType(
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memoryType == std::numeric_limits<uint32_t>::max()) {
        destroyImageResource(resource);
        return false;
    }

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = requirements.size;
    allocInfo.memoryTypeIndex = memoryType;
    if (d.AllocateMemory(
            context_.device,
            &allocInfo,
            nullptr,
            &resource.memory) != VK_SUCCESS ||
        d.BindImageMemory(
            context_.device,
            resource.image,
            resource.memory,
            0) != VK_SUCCESS) {
        destroyImageResource(resource);
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = resource.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (d.CreateImageView(
            context_.device,
            &viewInfo,
            nullptr,
            &resource.view) != VK_SUCCESS) {
        destroyImageResource(resource);
        return false;
    }

    resource.extent = {width, height};
    resource.format = format;
    return true;
}

void Backend::destroyImageResource(ImageResource& resource) {
    const auto& d = context_.dispatch;
    if (context_.device != VK_NULL_HANDLE) {
        if (resource.view != VK_NULL_HANDLE && d.DestroyImageView) {
            d.DestroyImageView(context_.device, resource.view, nullptr);
        }
        if (resource.image != VK_NULL_HANDLE && d.DestroyImage) {
            d.DestroyImage(context_.device, resource.image, nullptr);
        }
        if (resource.memory != VK_NULL_HANDLE && d.FreeMemory) {
            d.FreeMemory(context_.device, resource.memory, nullptr);
        }
    }
    resource = {};
}

bool Backend::createTelemetryBuffer() {
    const auto& d = context_.dispatch;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = kTelemetryBytes;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (d.CreateBuffer(
            context_.device,
            &bufferInfo,
            nullptr,
            &telemetryBuffer_) != VK_SUCCESS) {
        return false;
    }

    VkMemoryRequirements requirements{};
    d.GetBufferMemoryRequirements(
        context_.device,
        telemetryBuffer_,
        &requirements);
    const uint32_t memoryType = findMemoryType(
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memoryType == std::numeric_limits<uint32_t>::max()) {
        destroyTelemetryBuffer();
        return false;
    }

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = requirements.size;
    allocInfo.memoryTypeIndex = memoryType;
    if (d.AllocateMemory(
            context_.device,
            &allocInfo,
            nullptr,
            &telemetryMemory_) != VK_SUCCESS ||
        d.BindBufferMemory(
            context_.device,
            telemetryBuffer_,
            telemetryMemory_,
            0) != VK_SUCCESS) {
        destroyTelemetryBuffer();
        return false;
    }
    return true;
}

void Backend::destroyTelemetryBuffer() {
    const auto& d = context_.dispatch;
    if (context_.device != VK_NULL_HANDLE) {
        if (telemetryBuffer_ != VK_NULL_HANDLE && d.DestroyBuffer) {
            d.DestroyBuffer(context_.device, telemetryBuffer_, nullptr);
        }
        if (telemetryMemory_ != VK_NULL_HANDLE && d.FreeMemory) {
            d.FreeMemory(context_.device, telemetryMemory_, nullptr);
        }
    }
    telemetryBuffer_ = VK_NULL_HANDLE;
    telemetryMemory_ = VK_NULL_HANDLE;
}

bool Backend::createDescriptorPools() {
    const auto& d = context_.dispatch;
    const std::array<VkDescriptorPoolSize, 3> sizes{{
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 160},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 128},
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 64},
    }};
    for (auto& pool : descriptorPools_) {
        VkDescriptorPoolCreateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        info.maxSets = 64;
        info.poolSizeCount = static_cast<uint32_t>(sizes.size());
        info.pPoolSizes = sizes.data();
        if (d.CreateDescriptorPool(
                context_.device,
                &info,
                nullptr,
                &pool) != VK_SUCCESS) {
            destroyDescriptorPools();
            return false;
        }
    }
    return true;
}

void Backend::destroyDescriptorPools() {
    const auto& d = context_.dispatch;
    if (context_.device != VK_NULL_HANDLE && d.DestroyDescriptorPool) {
        for (auto& pool : descriptorPools_) {
            if (pool != VK_NULL_HANDLE) {
                d.DestroyDescriptorPool(context_.device, pool, nullptr);
                pool = VK_NULL_HANDLE;
            }
        }
    } else {
        descriptorPools_.fill(VK_NULL_HANDLE);
    }
}

bool Backend::ensureResources(
    uint32_t sourceWidth,
    uint32_t sourceHeight,
    uint32_t flowShortSide) {
    if (!healthy_ ||
        !validResourceDispatch(context_.dispatch) ||
        sourceWidth == 0 ||
        sourceHeight == 0) {
        diagnostics_ = "Vulkan DIS resource dispatch or extent unavailable";
        return false;
    }

    const uint32_t minorSide = std::max(
        1u,
        std::min(sourceWidth, sourceHeight));
    const uint32_t requestedShortSide =
        std::max(64u, flowShortSide == 0 ? 180u : flowShortSide);
    const float flowScale =
        static_cast<float>(requestedShortSide) /
        static_cast<float>(minorSide);
    const uint32_t flowWidth = std::max(
        64u,
        static_cast<uint32_t>(
            std::floor(static_cast<float>(sourceWidth) * flowScale + 0.5f)));
    const uint32_t flowHeight = std::max(
        64u,
        static_cast<uint32_t>(
            std::floor(static_cast<float>(sourceHeight) * flowScale + 0.5f)));

    if (resourcesReady_ &&
        sourceExtent_.width == sourceWidth &&
        sourceExtent_.height == sourceHeight &&
        flowExtent_.width == flowWidth &&
        flowExtent_.height == flowHeight) {
        return true;
    }

    destroyResources();
    if (!chooseFormats()) return false;

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod = 0.0f;
    if (context_.dispatch.CreateSampler(
            context_.device,
            &samplerInfo,
            nullptr,
            &sampler_) != VK_SUCCESS) {
        diagnostics_ = "Apex Vulkan sampler allocation failed";
        destroyResources();
        return false;
    }

    const VkImageUsageFlags historyUsage =
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    const VkImageUsageFlags storageUsage =
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_STORAGE_BIT;

    for (auto& history : colorHistory_) {
        if (!createImageResource(
                history,
                sourceWidth,
                sourceHeight,
                VK_FORMAT_R8G8B8A8_UNORM,
                historyUsage)) {
            diagnostics_ = "Apex Vulkan color history allocation failed";
            destroyResources();
            return false;
        }
    }

    for (auto& generated : generated_) {
        if (!createImageResource(
                generated,
                sourceWidth,
                sourceHeight,
                VK_FORMAT_R8G8B8A8_UNORM,
                storageUsage | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
            diagnostics_ = "Apex Vulkan generated image allocation failed";
            destroyResources();
            return false;
        }
    }

    for (uint32_t level = 0; level < kPyramidLevels; ++level) {
        auto& resources = levels_[level];
        resources.width = std::max(1u, flowWidth >> level);
        resources.height = std::max(1u, flowHeight >> level);
        resources.sparseWidth =
            resources.width > 8u ? 1u + (resources.width - 8u) / 3u : 1u;
        resources.sparseHeight =
            resources.height > 8u ? 1u + (resources.height - 8u) / 3u : 1u;

        for (uint32_t slot = 0; slot < kHistorySlots; ++slot) {
            if (!createImageResource(
                    resources.luma[slot],
                    resources.width,
                    resources.height,
                    VK_FORMAT_R32_SFLOAT,
                    storageUsage) ||
                !createImageResource(
                    resources.gradient[slot],
                    resources.width,
                    resources.height,
                    motionFormat_,
                    storageUsage)) {
                diagnostics_ = "Apex Vulkan pyramid allocation failed";
                destroyResources();
                return false;
            }
        }

        for (auto& sparse : resources.sparseFlow) {
            if (!createImageResource(
                    sparse,
                    resources.sparseWidth,
                    resources.sparseHeight,
                    motionFormat_,
                    storageUsage)) {
                diagnostics_ = "Apex Vulkan sparse-flow allocation failed";
                destroyResources();
                return false;
            }
        }

        if (!createImageResource(
                resources.denseFlow,
                resources.width,
                resources.height,
                motionFormat_,
                storageUsage)) {
            diagnostics_ = "Apex Vulkan dense-flow allocation failed";
            destroyResources();
            return false;
        }
    }

    if (!createTelemetryBuffer() || !createDescriptorPools()) {
        diagnostics_ = "Apex Vulkan descriptor/telemetry allocation failed";
        destroyResources();
        return false;
    }

    sourceExtent_ = {sourceWidth, sourceHeight};
    flowExtent_ = {flowWidth, flowHeight};
    layoutsInitialized_ = false;
    resourcesReady_ = true;
    currentHistorySlot_ = kHistorySlots - 1;
    previousHistorySlot_ = kHistorySlots - 1;
    sourceFrames_ = 0;
    generatedCount_ = 0;
    lastSourceTimestampNanos_ = 0;
    generatedSourceTimestampNanos_ = 0;

    diagnostics_ = std::string(kBackendVersion) +
        " resources ready: source=" + std::to_string(sourceWidth) + "x" +
        std::to_string(sourceHeight) + " flow=" +
        std::to_string(flowWidth) + "x" + std::to_string(flowHeight) +
        " motion=" +
        (motionFormat_ == VK_FORMAT_R16G16B16A16_SFLOAT ? "rgba16f" : "rgba32f");
    return true;
}

void Backend::destroyResources() {
    destroyDescriptorPools();
    destroyTelemetryBuffer();

    if (context_.device != VK_NULL_HANDLE &&
        sampler_ != VK_NULL_HANDLE &&
        context_.dispatch.DestroySampler) {
        context_.dispatch.DestroySampler(context_.device, sampler_, nullptr);
    }
    sampler_ = VK_NULL_HANDLE;

    for (auto& generated : generated_) destroyImageResource(generated);
    for (auto& history : colorHistory_) destroyImageResource(history);
    for (auto& level : levels_) {
        for (auto& image : level.luma) destroyImageResource(image);
        for (auto& image : level.gradient) destroyImageResource(image);
        for (auto& image : level.sparseFlow) destroyImageResource(image);
        destroyImageResource(level.denseFlow);
        level.width = 0;
        level.height = 0;
        level.sparseWidth = 0;
        level.sparseHeight = 0;
    }

    resourcesReady_ = false;
    layoutsInitialized_ = false;
    motionFormat_ = VK_FORMAT_UNDEFINED;
    sourceExtent_ = {0, 0};
    flowExtent_ = {0, 0};
    currentHistorySlot_ = kHistorySlots - 1;
    previousHistorySlot_ = kHistorySlots - 1;
    sourceFrames_ = 0;
    generatedCount_ = 0;
    lastSourceTimestampNanos_ = 0;
    generatedSourceTimestampNanos_ = 0;
}

VkDescriptorSet Backend::allocateAndWriteSet(
    uint32_t frameSlot,
    Stage stage,
    std::initializer_list<ImageBinding> images,
    bool bindTelemetry) {
    if (frameSlot >= kFramePools || descriptorPools_[frameSlot] == VK_NULL_HANDLE) {
        return VK_NULL_HANDLE;
    }

    const VkDescriptorSetLayout layout = descriptorSetLayout(stage);
    if (layout == VK_NULL_HANDLE) return VK_NULL_HANDLE;

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPools_[frameSlot];
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &layout;

    VkDescriptorSet set = VK_NULL_HANDLE;
    if (context_.dispatch.AllocateDescriptorSets(
            context_.device,
            &allocInfo,
            &set) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }

    std::vector<VkDescriptorImageInfo> imageInfos;
    std::vector<VkWriteDescriptorSet> writes;
    imageInfos.reserve(images.size());
    writes.reserve(images.size() + (bindTelemetry ? 1u : 0u));

    for (const auto& image : images) {
        VkDescriptorImageInfo info{};
        info.sampler =
            image.type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                ? image.sampler
                : VK_NULL_HANDLE;
        info.imageView = image.view;
        info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        imageInfos.push_back(info);

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = set;
        write.dstBinding = image.binding;
        write.descriptorCount = 1;
        write.descriptorType = image.type;
        write.pImageInfo = &imageInfos.back();
        writes.push_back(write);
    }

    VkDescriptorBufferInfo telemetryInfo{};
    if (bindTelemetry) {
        telemetryInfo.buffer = telemetryBuffer_;
        telemetryInfo.offset = 0;
        telemetryInfo.range = kTelemetryBytes;

        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = set;
        write.dstBinding = 5;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &telemetryInfo;
        writes.push_back(write);
    }

    context_.dispatch.UpdateDescriptorSets(
        context_.device,
        static_cast<uint32_t>(writes.size()),
        writes.data(),
        0,
        nullptr);
    return set;
}

void Backend::barrierImageVector(
    VkCommandBuffer commandBuffer,
    const std::vector<VkImage>& images,
    VkAccessFlags srcAccess,
    VkAccessFlags dstAccess,
    VkPipelineStageFlags srcStage,
    VkPipelineStageFlags dstStage) const {
    if (images.empty()) return;
    std::vector<VkImageMemoryBarrier> barriers;
    barriers.reserve(images.size());
    for (VkImage image : images) {
        if (image == VK_NULL_HANDLE) continue;
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = srcAccess;
        barrier.dstAccessMask = dstAccess;
        barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        barriers.push_back(barrier);
    }
    if (barriers.empty()) return;
    context_.dispatch.CmdPipelineBarrier(
        commandBuffer,
        srcStage,
        dstStage,
        0,
        0,
        nullptr,
        0,
        nullptr,
        static_cast<uint32_t>(barriers.size()),
        barriers.data());
}

void Backend::barrierImages(
    VkCommandBuffer commandBuffer,
    std::initializer_list<VkImage> images,
    VkAccessFlags srcAccess,
    VkAccessFlags dstAccess,
    VkPipelineStageFlags srcStage,
    VkPipelineStageFlags dstStage) const {
    barrierImageVector(
        commandBuffer,
        std::vector<VkImage>(images),
        srcAccess,
        dstAccess,
        srcStage,
        dstStage);
}

void Backend::initializeResourceLayouts(VkCommandBuffer commandBuffer) {
    if (layoutsInitialized_) return;

    std::vector<VkImage> images;
    images.reserve(48);
    for (const auto& history : colorHistory_) images.push_back(history.image);
    for (const auto& generated : generated_) images.push_back(generated.image);
    for (const auto& level : levels_) {
        for (const auto& image : level.luma) images.push_back(image.image);
        for (const auto& image : level.gradient) images.push_back(image.image);
        for (const auto& image : level.sparseFlow) images.push_back(image.image);
        images.push_back(level.denseFlow.image);
    }

    std::vector<VkImageMemoryBarrier> barriers;
    barriers.reserve(images.size());
    for (VkImage image : images) {
        if (image == VK_NULL_HANDLE) continue;
        VkImageMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask =
            VK_ACCESS_SHADER_READ_BIT |
            VK_ACCESS_SHADER_WRITE_BIT |
            VK_ACCESS_TRANSFER_READ_BIT |
            VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image;
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        barriers.push_back(barrier);
    }

    context_.dispatch.CmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        static_cast<uint32_t>(barriers.size()),
        barriers.data());
    layoutsInitialized_ = true;
}

bool Backend::recordLumaGrad(
    uint32_t frameSlot,
    VkCommandBuffer commandBuffer,
    uint32_t level,
    VkImageView inputView,
    uint32_t historySlot) {
    auto& resources = levels_[level];
    VkDescriptorSet set = allocateAndWriteSet(
        frameSlot,
        Stage::LumaGrad,
        {
            {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, inputView, sampler_},
            {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, resources.luma[historySlot].view, VK_NULL_HANDLE},
            {2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, resources.gradient[historySlot].view, VK_NULL_HANDLE},
        },
        true);
    if (set == VK_NULL_HANDLE) return false;

    struct Push {
        int32_t isColor;
        int32_t collectTelemetry;
    } push{level == 0 ? 1 : 0, 0};

    record(
        Stage::LumaGrad,
        commandBuffer,
        set,
        ceilDiv(resources.width, 16),
        ceilDiv(resources.height, 16),
        1,
        &push,
        sizeof(push));
    barrierImages(
        commandBuffer,
        {resources.luma[historySlot].image, resources.gradient[historySlot].image},
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    return true;
}

bool Backend::recordInverseSearch(
    uint32_t frameSlot,
    VkCommandBuffer commandBuffer,
    uint32_t level,
    uint32_t previousHistorySlot,
    uint32_t currentHistorySlot,
    VkImageView coarseFlowView) {
    auto& resources = levels_[level];
    VkDescriptorSet set = allocateAndWriteSet(
        frameSlot,
        Stage::InverseSearch,
        {
            {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.luma[previousHistorySlot].view, sampler_},
            {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.luma[currentHistorySlot].view, sampler_},
            {2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.gradient[previousHistorySlot].view, sampler_},
            {3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, coarseFlowView, sampler_},
            {4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, resources.sparseFlow[0].view, VK_NULL_HANDLE},
        },
        true);
    if (set == VK_NULL_HANDLE) return false;

    struct Push {
        int32_t level;
        int32_t coarseLevel;
        int32_t collectTelemetry;
    } push{
        static_cast<int32_t>(level),
        static_cast<int32_t>(kPyramidLevels - 1),
        0,
    };

    record(
        Stage::InverseSearch,
        commandBuffer,
        set,
        ceilDiv(resources.sparseWidth, 8),
        ceilDiv(resources.sparseHeight, 8),
        1,
        &push,
        sizeof(push));
    barrierImages(
        commandBuffer,
        {resources.sparseFlow[0].image},
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    return true;
}

bool Backend::recordPropagation(
    uint32_t frameSlot,
    VkCommandBuffer commandBuffer,
    uint32_t level,
    uint32_t historySlot,
    uint32_t inputIndex,
    uint32_t outputIndex,
    int distance) {
    (void)historySlot;
    auto& resources = levels_[level];
    VkDescriptorSet set = allocateAndWriteSet(
        frameSlot,
        Stage::Propagate,
        {
            {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.luma[previousHistorySlot_].view, sampler_},
            {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.luma[currentHistorySlot_].view, sampler_},
            {2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.sparseFlow[inputIndex].view, sampler_},
            {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, resources.sparseFlow[outputIndex].view, VK_NULL_HANDLE},
        },
        true);
    if (set == VK_NULL_HANDLE) return false;

    struct Push {
        int32_t distance;
        int32_t level;
        int32_t collectTelemetry;
    } push{
        static_cast<int32_t>(distance),
        static_cast<int32_t>(level),
        0,
    };

    record(
        Stage::Propagate,
        commandBuffer,
        set,
        ceilDiv(resources.sparseWidth, 8),
        ceilDiv(resources.sparseHeight, 8),
        1,
        &push,
        sizeof(push));
    barrierImages(
        commandBuffer,
        {resources.sparseFlow[outputIndex].image},
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    return true;
}

bool Backend::recordDensify(
    uint32_t frameSlot,
    VkCommandBuffer commandBuffer,
    uint32_t level,
    uint32_t previousHistorySlot,
    uint32_t currentHistorySlot) {
    auto& resources = levels_[level];
    VkDescriptorSet set = allocateAndWriteSet(
        frameSlot,
        Stage::Densify,
        {
            {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.sparseFlow[0].view, sampler_},
            {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.luma[previousHistorySlot].view, sampler_},
            {2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, resources.luma[currentHistorySlot].view, sampler_},
            {3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, resources.denseFlow.view, VK_NULL_HANDLE},
        },
        true);
    if (set == VK_NULL_HANDLE) return false;

    struct Push {
        int32_t level;
        int32_t collectTelemetry;
    } push{static_cast<int32_t>(level), 0};

    record(
        Stage::Densify,
        commandBuffer,
        set,
        ceilDiv(resources.width, 8),
        ceilDiv(resources.height, 8),
        1,
        &push,
        sizeof(push));
    barrierImages(
        commandBuffer,
        {resources.denseFlow.image},
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    return true;
}

bool Backend::recordInterpolation(
    uint32_t frameSlot,
    VkCommandBuffer commandBuffer,
    uint32_t previousHistorySlot,
    uint32_t currentHistorySlot,
    uint32_t generatedIndex,
    float t) {
    if (generatedIndex >= generated_.size()) return false;

    VkDescriptorSet set = allocateAndWriteSet(
        frameSlot,
        Stage::Interpolate,
        {
            {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, colorHistory_[previousHistorySlot].view, sampler_},
            {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, colorHistory_[currentHistorySlot].view, sampler_},
            {2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, levels_[0].denseFlow.view, sampler_},
            {4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, generated_[generatedIndex].view, VK_NULL_HANDLE},
        },
        true);
    if (set == VK_NULL_HANDLE) return false;

    struct Push {
        float t;
        float liquidFeel;
        float shutterGain;
        float edgeGuard;
        int32_t collectTelemetry;
    } push{t, 0.5f, 0.0f, 0.5f, 0};

    record(
        Stage::Interpolate,
        commandBuffer,
        set,
        ceilDiv(sourceExtent_.width, 16),
        ceilDiv(sourceExtent_.height, 8),
        1,
        &push,
        sizeof(push));
    return true;
}

bool Backend::recordSourceGraph(
    uint32_t frameSlot,
    VkCommandBuffer commandBuffer,
    VkImage sourceImage,
    VkImageView sourceView,
    uint64_t sourceTimestampNanos) {
    if (!resourcesReady_ ||
        frameSlot >= kFramePools ||
        commandBuffer == VK_NULL_HANDLE ||
        sourceImage == VK_NULL_HANDLE ||
        sourceView == VK_NULL_HANDLE ||
        sourceTimestampNanos == 0) {
        return false;
    }

    if (context_.dispatch.ResetDescriptorPool(
            context_.device,
            descriptorPools_[frameSlot],
            0) != VK_SUCCESS) {
        diagnostics_ = "Apex Vulkan descriptor pool reset failed";
        return false;
    }

    if (lastSourceTimestampNanos_ != 0 &&
        sourceTimestampNanos > lastSourceTimestampNanos_ &&
        sourceTimestampNanos - lastSourceTimestampNanos_ > kDiscontinuityNanos) {
        sourceFrames_ = 0;
        currentHistorySlot_ = kHistorySlots - 1;
        previousHistorySlot_ = kHistorySlots - 1;
    }
    lastSourceTimestampNanos_ = sourceTimestampNanos;
    generatedCount_ = 0;

    previousHistorySlot_ = currentHistorySlot_;
    currentHistorySlot_ = (currentHistorySlot_ + 1u) % kHistorySlots;

    initializeResourceLayouts(commandBuffer);

    barrierImages(
        commandBuffer,
        {sourceImage},
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT);
    barrierImages(
        commandBuffer,
        {colorHistory_[currentHistorySlot_].image},
        VK_ACCESS_SHADER_READ_BIT,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy copy{};
    copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.extent = {sourceExtent_.width, sourceExtent_.height, 1};
    context_.dispatch.CmdCopyImage(
        commandBuffer,
        sourceImage,
        VK_IMAGE_LAYOUT_GENERAL,
        colorHistory_[currentHistorySlot_].image,
        VK_IMAGE_LAYOUT_GENERAL,
        1,
        &copy);

    barrierImages(
        commandBuffer,
        {sourceImage},
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    barrierImages(
        commandBuffer,
        {colorHistory_[currentHistorySlot_].image},
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkImageView pyramidInput = sourceView;
    for (uint32_t level = 0; level < kPyramidLevels; ++level) {
        if (!recordLumaGrad(
                frameSlot,
                commandBuffer,
                level,
                pyramidInput,
                currentHistorySlot_)) {
            diagnostics_ = "Apex Vulkan luma/gradient dispatch failed";
            return false;
        }
        pyramidInput = levels_[level].luma[currentHistorySlot_].view;
    }

    ++sourceFrames_;
    if (sourceFrames_ < 2) {
        diagnostics_ = std::string(kBackendVersion) +
            " history primed on renderer-owned source image";
        return true;
    }

    for (int level = static_cast<int>(kPyramidLevels) - 1; level >= 0; --level) {
        const VkImageView coarseFlowView =
            level == static_cast<int>(kPyramidLevels) - 1
                ? levels_[level].luma[previousHistorySlot_].view
                : levels_[level + 1].denseFlow.view;

        if (!recordInverseSearch(
                frameSlot,
                commandBuffer,
                static_cast<uint32_t>(level),
                previousHistorySlot_,
                currentHistorySlot_,
                coarseFlowView)) {
            diagnostics_ = "Apex Vulkan inverse-search dispatch failed";
            return false;
        }

        if (!recordPropagation(
                frameSlot,
                commandBuffer,
                static_cast<uint32_t>(level),
                currentHistorySlot_,
                0,
                1,
                1) ||
            !recordPropagation(
                frameSlot,
                commandBuffer,
                static_cast<uint32_t>(level),
                currentHistorySlot_,
                1,
                0,
                2)) {
            diagnostics_ = "Apex Vulkan propagation dispatch failed";
            return false;
        }

        if (level >= 2) {
            if (!recordPropagation(
                    frameSlot,
                    commandBuffer,
                    static_cast<uint32_t>(level),
                    currentHistorySlot_,
                    0,
                    1,
                    4) ||
                !recordPropagation(
                    frameSlot,
                    commandBuffer,
                    static_cast<uint32_t>(level),
                    currentHistorySlot_,
                    1,
                    0,
                    1)) {
                diagnostics_ = "Apex Vulkan coarse propagation dispatch failed";
                return false;
            }
        }

        if (!recordDensify(
                frameSlot,
                commandBuffer,
                static_cast<uint32_t>(level),
                previousHistorySlot_,
                currentHistorySlot_)) {
            diagnostics_ = "Apex Vulkan densification dispatch failed";
            return false;
        }
    }

    for (uint32_t generatedIndex = 0;
         generatedIndex < kGeneratedFrames;
         ++generatedIndex) {
        const float t =
            static_cast<float>(generatedIndex + 1u) /
            static_cast<float>(kGeneratedFrames + 1u);
        if (!recordInterpolation(
                frameSlot,
                commandBuffer,
                previousHistorySlot_,
                currentHistorySlot_,
                generatedIndex,
                t)) {
            diagnostics_ = "Apex Vulkan interpolation dispatch failed";
            return false;
        }
    }

    std::vector<VkImage> generatedImages;
    generatedImages.reserve(generated_.size());
    for (const auto& generated : generated_) {
        generatedImages.push_back(generated.image);
    }
    barrierImageVector(
        commandBuffer,
        generatedImages,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT);

    generatedCount_ = kGeneratedFrames;
    generatedSourceTimestampNanos_ = sourceTimestampNanos;
    diagnostics_ = std::string(kBackendVersion) +
        " recorded: 4 pyramid + 4 search + 12 propagation + 4 densify + 3 interpolation dispatches";
    return true;
}

VkImage Backend::generatedImage(uint32_t index) const {
    return index < generated_.size()
        ? generated_[index].image
        : VK_NULL_HANDLE;
}

VkImageView Backend::generatedImageView(uint32_t index) const {
    return index < generated_.size()
        ? generated_[index].view
        : VK_NULL_HANDLE;
}

void Backend::destroy() {
    destroyResources();
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

    if (state.pushConstantBytes > 0 &&
        (!pushConstants || pushConstantBytes != state.pushConstantBytes)) {
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
