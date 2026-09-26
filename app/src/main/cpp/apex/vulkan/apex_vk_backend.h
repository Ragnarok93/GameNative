#pragma once

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

namespace gamenative::apex::vk {

enum class Stage : uint8_t {
    LumaGrad = 0,
    InverseSearch,
    Propagate,
    Densify,
    VrSetup,
    VrSor,
    Interpolate,
    Rcas,
    Count,
};

struct Dispatch {
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout CreatePipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout = nullptr;
    PFN_vkCreateShaderModule CreateShaderModule = nullptr;
    PFN_vkDestroyShaderModule DestroyShaderModule = nullptr;
    PFN_vkCreateComputePipelines CreateComputePipelines = nullptr;
    PFN_vkDestroyPipeline DestroyPipeline = nullptr;
    PFN_vkCmdBindPipeline CmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets = nullptr;
    PFN_vkCmdPushConstants CmdPushConstants = nullptr;
    PFN_vkCmdDispatch CmdDispatch = nullptr;
};

struct Context {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    Dispatch dispatch{};
};

class Backend {
public:
    Backend() = default;
    ~Backend();

    Backend(const Backend&) = delete;
    Backend& operator=(const Backend&) = delete;

    bool initialize(const Context& context);
    void destroy();

    bool healthy() const { return healthy_; }
    const std::string& diagnostics() const { return diagnostics_; }

    VkDescriptorSetLayout descriptorSetLayout(Stage stage) const;
    VkPipelineLayout pipelineLayout(Stage stage) const;

    void record(
        Stage stage,
        VkCommandBuffer commandBuffer,
        VkDescriptorSet descriptorSet,
        uint32_t groupX,
        uint32_t groupY,
        uint32_t groupZ,
        const void* pushConstants = nullptr,
        uint32_t pushConstantBytes = 0) const;

private:
    struct StageState {
        VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
        VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
        VkPipeline pipeline = VK_NULL_HANDLE;
        uint32_t pushConstantBytes = 0;
    };

    bool createStage(
        Stage stage,
        const char* name,
        const uint32_t* spirv,
        size_t spirvBytes,
        const VkDescriptorSetLayoutBinding* bindings,
        uint32_t bindingCount,
        uint32_t pushConstantBytes);

    static constexpr size_t kStageCount = static_cast<size_t>(Stage::Count);

    Context context_{};
    std::array<StageState, kStageCount> stages_{};
    bool healthy_ = false;
    std::string diagnostics_;
};

} // namespace gamenative::apex::vk
