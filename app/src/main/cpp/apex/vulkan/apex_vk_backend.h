#pragma once

#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <string>
#include <vector>

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
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties = nullptr;

    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout CreatePipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout = nullptr;
    PFN_vkCreateShaderModule CreateShaderModule = nullptr;
    PFN_vkDestroyShaderModule DestroyShaderModule = nullptr;
    PFN_vkCreateComputePipelines CreateComputePipelines = nullptr;
    PFN_vkDestroyPipeline DestroyPipeline = nullptr;

    PFN_vkCreateImage CreateImage = nullptr;
    PFN_vkDestroyImage DestroyImage = nullptr;
    PFN_vkAllocateMemory AllocateMemory = nullptr;
    PFN_vkFreeMemory FreeMemory = nullptr;
    PFN_vkBindImageMemory BindImageMemory = nullptr;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements = nullptr;
    PFN_vkCreateImageView CreateImageView = nullptr;
    PFN_vkDestroyImageView DestroyImageView = nullptr;

    PFN_vkCreateBuffer CreateBuffer = nullptr;
    PFN_vkDestroyBuffer DestroyBuffer = nullptr;
    PFN_vkBindBufferMemory BindBufferMemory = nullptr;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements = nullptr;

    PFN_vkCreateDescriptorPool CreateDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool = nullptr;
    PFN_vkResetDescriptorPool ResetDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets = nullptr;

    PFN_vkCreateSampler CreateSampler = nullptr;
    PFN_vkDestroySampler DestroySampler = nullptr;

    PFN_vkCmdBindPipeline CmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets = nullptr;
    PFN_vkCmdPushConstants CmdPushConstants = nullptr;
    PFN_vkCmdDispatch CmdDispatch = nullptr;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyImage CmdCopyImage = nullptr;
};

struct Context {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    Dispatch dispatch{};
};

struct ImageResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkExtent2D extent{0, 0};
    VkFormat format = VK_FORMAT_UNDEFINED;
};

struct LevelResources {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t sparseWidth = 0;
    uint32_t sparseHeight = 0;
    std::array<ImageResource, 3> luma{};
    std::array<ImageResource, 3> gradient{};
    std::array<ImageResource, 2> sparseFlow{};
    ImageResource denseFlow{};
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
    bool resourcesReady() const { return resourcesReady_; }
    const std::string& diagnostics() const { return diagnostics_; }

    bool ensureResources(
        uint32_t sourceWidth,
        uint32_t sourceHeight,
        uint32_t flowShortSide = 180);
    void destroyResources();

    // Records the currently active GLES-equivalent DIS graph directly from a
    // renderer-owned source image. The caller owns queue submission and the
    // completion fence; no AHB/EGL import occurs on this path.
    bool recordSourceGraph(
        uint32_t frameSlot,
        VkCommandBuffer commandBuffer,
        VkImage sourceImage,
        VkImageView sourceView,
        uint64_t sourceTimestampNanos,
        uint32_t generatedBudget = 3);

    VkImage generatedImage(uint32_t index) const;
    VkImageView generatedImageView(uint32_t index) const;
    VkImage currentSourceImage() const;
    VkImageView currentSourceImageView() const;
    VkExtent2D generatedExtent() const { return sourceExtent_; }
    uint32_t generatedCount() const { return generatedCount_; }
    uint64_t generatedSourceTimestampNanos() const {
        return generatedSourceTimestampNanos_;
    }

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

    struct ImageBinding {
        uint32_t binding = 0;
        VkDescriptorType type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        VkImageView view = VK_NULL_HANDLE;
        VkSampler sampler = VK_NULL_HANDLE;
    };

    static constexpr size_t kStageCount = static_cast<size_t>(Stage::Count);
    static constexpr uint32_t kHistorySlots = 3;
    static constexpr uint32_t kPyramidLevels = 4;
    static constexpr uint32_t kGeneratedFrames = 3;
    static constexpr uint32_t kFramePools = 3;

    bool createStage(
        Stage stage,
        const char* name,
        const uint32_t* spirv,
        size_t spirvBytes,
        const VkDescriptorSetLayoutBinding* bindings,
        uint32_t bindingCount,
        uint32_t pushConstantBytes);

    bool chooseFormats();
    bool createImageResource(
        ImageResource& resource,
        uint32_t width,
        uint32_t height,
        VkFormat format,
        VkImageUsageFlags usage);
    void destroyImageResource(ImageResource& resource);
    bool createTelemetryBuffer();
    void destroyTelemetryBuffer();
    bool createDescriptorPools();
    void destroyDescriptorPools();
    uint32_t findMemoryType(
        uint32_t typeBits,
        VkMemoryPropertyFlags preferred) const;

    VkDescriptorSet allocateAndWriteSet(
        uint32_t frameSlot,
        Stage stage,
        std::initializer_list<ImageBinding> images,
        bool bindTelemetry);

    void initializeResourceLayouts(VkCommandBuffer commandBuffer);
    void barrierImages(
        VkCommandBuffer commandBuffer,
        std::initializer_list<VkImage> images,
        VkAccessFlags srcAccess,
        VkAccessFlags dstAccess,
        VkPipelineStageFlags srcStage,
        VkPipelineStageFlags dstStage) const;
    void barrierImageVector(
        VkCommandBuffer commandBuffer,
        const std::vector<VkImage>& images,
        VkAccessFlags srcAccess,
        VkAccessFlags dstAccess,
        VkPipelineStageFlags srcStage,
        VkPipelineStageFlags dstStage) const;

    bool recordLumaGrad(
        uint32_t frameSlot,
        VkCommandBuffer commandBuffer,
        uint32_t level,
        VkImageView inputView,
        uint32_t historySlot);
    bool recordInverseSearch(
        uint32_t frameSlot,
        VkCommandBuffer commandBuffer,
        uint32_t level,
        uint32_t previousHistorySlot,
        uint32_t currentHistorySlot,
        VkImageView coarseFlowView);
    bool recordPropagation(
        uint32_t frameSlot,
        VkCommandBuffer commandBuffer,
        uint32_t level,
        uint32_t historySlot,
        uint32_t inputIndex,
        uint32_t outputIndex,
        int distance);
    bool recordDensify(
        uint32_t frameSlot,
        VkCommandBuffer commandBuffer,
        uint32_t level,
        uint32_t previousHistorySlot,
        uint32_t currentHistorySlot);
    bool recordInterpolation(
        uint32_t frameSlot,
        VkCommandBuffer commandBuffer,
        uint32_t previousHistorySlot,
        uint32_t currentHistorySlot,
        uint32_t generatedIndex,
        float t);

    Context context_{};
    std::array<StageState, kStageCount> stages_{};

    std::array<ImageResource, kHistorySlots> colorHistory_{};
    std::array<LevelResources, kPyramidLevels> levels_{};
    std::array<ImageResource, kGeneratedFrames> generated_{};
    std::array<VkDescriptorPool, kFramePools> descriptorPools_{};
    VkSampler sampler_ = VK_NULL_HANDLE;
    VkBuffer telemetryBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory telemetryMemory_ = VK_NULL_HANDLE;

    VkFormat motionFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D sourceExtent_{0, 0};
    VkExtent2D flowExtent_{0, 0};
    bool healthy_ = false;
    bool resourcesReady_ = false;
    bool layoutsInitialized_ = false;

    uint32_t currentHistorySlot_ = kHistorySlots - 1;
    uint32_t previousHistorySlot_ = kHistorySlots - 1;
    uint32_t sourceFrames_ = 0;
    uint32_t generatedCount_ = 0;
    uint64_t lastSourceTimestampNanos_ = 0;
    uint64_t generatedSourceTimestampNanos_ = 0;

    std::string diagnostics_;
};

} // namespace gamenative::apex::vk
