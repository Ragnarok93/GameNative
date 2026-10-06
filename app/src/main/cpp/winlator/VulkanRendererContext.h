#pragma once
#include <vulkan/vulkan.h>
#include "../lsfg/vkr_lsfg.h"
#include "adaptive_scheduler.hpp"
#include <list>
#include <vulkan/vulkan_android.h>

struct VkrLsfg;
struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkGetPastPresentationTimingGOOGLE GetPastPresentationTimingGOOGLE;
    PFN_vkGetRefreshCycleDurationGOOGLE GetRefreshCycleDurationGOOGLE;
    PFN_vkWaitForPresentKHR WaitForPresentKHR;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Scanout",__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vector>
#include <deque>
#include <unordered_map>
#include <unordered_set>
#include <array>
#include <cstddef>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>

static constexpr uint32_t BASE_FRAMES_IN_FLIGHT = 2;
static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 3;
static constexpr uint32_t MAX_BUFFERED_GPU_SUBMISSIONS = 2;
static constexpr uint64_t SMOOTH_PRESENT_STALL_NS = 8'000'000ULL;
static constexpr uint32_t SMOOTH_PRESENT_STALL_STRIKES = 2;
static constexpr uint64_t MAX_HOST_TEMPORAL_STALE_NS = 250'000'000ULL;
// Pre-composition correctness buffering must accommodate one real source plus
// the maximum Native/Legacy synthetic burst. User-facing Frame Queue controls
// GPU/WSI buffering and must not collapse this ordered handoff to one entry.
static constexpr uint32_t MIN_HOST_DELIVERY_QUEUE_CAPACITY =
    VKR_LSFG_MAX_GENERATIONS + 1;
static constexpr uint32_t MAX_HOST_DELIVERY_QUEUE_CAPACITY =
    MIN_HOST_DELIVERY_QUEUE_CAPACITY + 2;
static constexpr uint32_t MAX_HOST_PRESENT_QUEUE_DEPTH = 2;
static constexpr uint32_t MAX_NATIVE_HOST_PRESENT_QUEUE_DEPTH =
    VKR_LSFG_MAX_GENERATIONS + 1;
static constexpr uint32_t VK_MAX_COMPOSITE_TARGETS = 6;
// A generated/composited window normally rotates through only a small AHB set.
// Keep enough history for reuse without letting a long session consume the
// renderer's descriptor budget indefinitely.
static constexpr uint32_t MAX_AHB_IMPORTS_PER_WINDOW = 12;
// AHB descriptors live in their own bounded pool. Active and retired imports
// both consume this budget until a fence proves the last GPU use completed.
static constexpr uint32_t MAX_AHB_IMPORTS_TOTAL = 128;

struct AhbImportBudget {
    static bool canAllocate(std::size_t active, std::size_t retired) noexcept {
        return active <= MAX_AHB_IMPORTS_TOTAL
            && retired <= MAX_AHB_IMPORTS_TOTAL
            && active + retired < MAX_AHB_IMPORTS_TOTAL;
    }
};

struct RendererSubmissionTimeline {
    std::array<uint64_t, MAX_FRAMES_IN_FLIGHT> frameSubmissionSerial{};
    std::atomic<uint64_t> submittedSubmissionSerial{0};
    std::atomic<uint64_t> completedSubmissionSerial{0};

    uint64_t nextSubmissionSerial() const noexcept {
        return submittedSubmissionSerial.load(std::memory_order_acquire) + 1;
    }

    void submitFrame(uint32_t frameIndex, uint64_t serial) noexcept {
        if (frameIndex >= MAX_FRAMES_IN_FLIGHT) return;
        frameSubmissionSerial[frameIndex] = serial;
        submittedSubmissionSerial.store(serial, std::memory_order_release);
    }

    void completeFrame(uint32_t frameIndex) noexcept {
        if (frameIndex >= MAX_FRAMES_IN_FLIGHT) return;
        const uint64_t serial = frameSubmissionSerial[frameIndex];
        if (serial == 0) return;
        uint64_t completed =
            completedSubmissionSerial.load(std::memory_order_acquire);
        while (completed < serial
                && !completedSubmissionSerial.compare_exchange_weak(
                    completed, serial,
                    std::memory_order_release,
                    std::memory_order_acquire)) {}
        frameSubmissionSerial[frameIndex] = 0;
    }

    void completeAllFrames() noexcept {
        for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i)
            completeFrame(i);
    }
};

enum class HostDisplayConfirmationBackend : uint8_t {
    WsiAccepted = 0,
    PresentWait = 1,
    GoogleDisplayTiming = 2,
};

struct LsfgFrameProvenance {
    bool valid = false;
    bool nativeImplementation = false;
    uint64_t runtimeSessionId = 0;
    uint64_t contextEpoch = 0;
    uint64_t deliveryId = 0;
    uint64_t sourceIndex = 0;
    uint64_t batchId = 0;
    uint32_t swapchainImageIndex = 0;
    uint32_t interpolationCount = 0;
    uint8_t interpolationIndex = 0;
    uint8_t kind = 0; // 0=source, 1=generated
    uint64_t desiredPresentTimeNs = 0;
    // Host-owned Legacy cadence identity. These fields are assigned after
    // provenance transport and are not part of the guest socket ABI.
    uint64_t outputSlotIndex = 0;
    uint64_t outputSlotIntendedPresentTimeNs = 0;
    bool uniqueDelivery = false;
};

struct QueuedLsfgHostDelivery {
    AHardwareBuffer* ahb = nullptr;
    LsfgFrameProvenance provenance{};
    uint64_t enqueuedAtNs = 0;
};

struct HostDesiredPresentDecision {
    uint64_t provenanceDesiredPresentTimeNs = 0;
    uint64_t submittedDesiredPresentTimeNs = 0;
    uint64_t desiredStaleByNs = 0;
    uint64_t desiredFutureByNs = 0;
    uint64_t refreshPeriodNs = 0;
    uint64_t phaseAdvanceCycles = 0;
    uint64_t phaseAdvanceNs = 0;
    bool temporalBacklog = false;
    const char* fallbackReason = "none";
};

struct HostDisplayConfirmation {
    uint64_t hostPresentId = 0;
    uint32_t googlePresentId = 0;
    HostDisplayConfirmationBackend backend =
        HostDisplayConfirmationBackend::WsiAccepted;
    std::vector<LsfgFrameProvenance> frameProvenance;
    uint64_t provenanceDesiredPresentTimeNs = 0;
    uint64_t submittedDesiredPresentTimeNs = 0;
    uint64_t wsiDesiredPresentTimeNs = 0;
    uint64_t desiredStaleByNs = 0;
    uint64_t desiredFutureByNs = 0;
    uint64_t refreshPeriodNs = 0;
    uint64_t phaseAdvanceCycles = 0;
    uint64_t phaseAdvanceNs = 0;
    bool temporalBacklog = false;
    const char* desiredFallbackReason = "none";
    uint64_t actualPresentTimeNs = 0;
    uint64_t earliestPresentTimeNs = 0;
    uint64_t presentMarginNs = 0;
    uint64_t presentMarginRawNs = 0;
    uint64_t enqueuedAtNs = 0;
    uint64_t swapchainGeneration = 0;
    uint64_t submissionSerial = 0;
    uint64_t presentCallNs = 0;
    uint64_t submitCallNs = 0;
    uint32_t frameSlot = 0;
    uint32_t gpuOutstandingAtSubmit = 0;
};

struct PendingHostPresent {
    uint32_t frameSlot = 0;
    uint32_t imageIndex = 0;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkSemaphore waitSemaphore = VK_NULL_HANDLE;
    uint64_t hostPresentId = 0;
    uint32_t googlePresentId = 0;
    HostDisplayConfirmationBackend backend =
        HostDisplayConfirmationBackend::WsiAccepted;
    std::vector<LsfgFrameProvenance> frameProvenance;
    HostDesiredPresentDecision desiredDecision{};
    bool hasUniqueLsfgDelivery = false;
    uint64_t acquireNs = 0;
    uint64_t submitCallNs = 0;
    uint64_t submissionSerial = 0;
    uint64_t swapchainGeneration = 0;
    uint32_t gpuOutstanding = 0;
    uint64_t hostPresentEnqueueWaitNs = 0;
    uint64_t enqueuedAtNs = 0;
    uint64_t presenterQueueAgeNs = 0;
    uint32_t hostPresentQueueDepth = 0;
};

struct CompletedHostPresent {
    PendingHostPresent present{};
    VkResult result = VK_SUCCESS;
    uint64_t presentCallNs = 0;
};

struct WindowPushConstants {
    float ndcX0, ndcY0, ndcX1, ndcY1;
    int   useTexAlpha;
    int   effectId;
    float sharpness;
    float resW;
    float resH;
    int   effectMask;
    float brightness;
    float contrast;
    float gamma;
    float outW;   // on-screen quad width  in pixels (for FSR/EASU upscale ratio)
    float outH;   // on-screen quad height in pixels
};

class VulkanRendererContext {
public:
    int64_t enableXrTarget();
    void disableXrTarget();
    int64_t xrTargetExtentPacked();
    VulkanRendererContext(
        ANativeWindow* window,
        int cWidth,
        int cHeight,
        void* adrenotoolsHandle = nullptr,
        std::string provenanceSocketPath = {});
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    void updateWindowContent(int64_t id, void* pixels, short w, short h, short stride, int x, int y);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int x, int y);
    void updateCursorImage(void* pixels, short w, short h, short hotX, short hotY);
    void setCursorVisible(bool visible);
    void setRenderList(const int64_t* ids, const int* xs, const int* ys, int count);
    void removeWindow(int64_t id);
    void clearBackbuffer() {}
    void beginBatch() {}
    void endBatch() {}
    void initScanout();
    void destroyScanout();
    void applyScanoutBuffer();
    void initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin);
    void scanoutSetDst(int x, int y, int w, int h);
    void scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd = -1);
    void scanoutSetCursorImage(void* pixels, short w, short h, short stride);
    void scanoutSetCursorPos(short x, short y, short hotX, short hotY);
    std::atomic<bool> scanoutActive{false};
    std::atomic<bool> gameFrameDelivered{false};
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);
    bool hasPresentationSurface() const;

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    std::atomic<bool> scanoutBlackFrameDone{false};
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void armFrameGeneration();
    void setFrameGenerationEnabled(bool enabled);
    void beginLsfgBackendTransition(uint64_t transactionId, uint64_t revision);
    void commitLsfgBackendTransitionPolicy(uint64_t transactionId);
    void completeLsfgBackendTransition(uint64_t transactionId, const char* reason);
    bool isFrameGenerationSupported() const;
    void setFrameGenerationShaders(const std::string& cachePath);
    void setFrameGenerationRefreshRate(float hz);
    void setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct,
                                int flowMode, int flowPreset, uint64_t configRevision);
    void setFrameGenerationPressure(float gpuUsagePercent, int thermalStatus,
                                    float sourceFps, float outputFps,
                                    float frameTimeP95Ms, float slowFrameRatio);
    uint64_t getGeneratedFrameCount() const;
    uint64_t getGeneratedPresentedFrameCount() const;
    uint64_t getPresentedFrameCount() const;
    uint64_t getDisplayConfirmedFrameCount() const;
    uint64_t getGeneratedDisplayConfirmedFrameCount() const;
    uint64_t getSourceDisplayConfirmedFrameCount() const;
    bool isDisplayConfirmationAvailable() const;
    uint64_t getRealFrameCount() const;
    uint64_t getSourceFrameCount() const;
    void setSourceFrameCount(uint64_t count);

    void setFilterMode(int mode);
    void setSwapRB(bool enabled);
    void setEffect(int effectId, float sharpness, int effectMask, float brightness, float contrast, float gamma);
    void setPresentMode(VkPresentModeKHR mode);
    void setLsfgFrameQueue(bool enabled, uint32_t target);
    std::vector<int> getSupportedPresentModes() const;

private:
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        VkBuffer             stg            = VK_NULL_HANDLE;
        VkDeviceMemory       stgMem         = VK_NULL_HANDLE;
        void*                mapped         = nullptr;
        VkDeviceSize         cap            = 0;
        int                  w              = 0;
        int                  h              = 0;
        bool                 dirty          = false;
        bool                 isAHB          = false;
        bool                 needsTransition = false;
        AHardwareBuffer*     ahb            = nullptr;
        VkDescriptorPool     descriptorPool = VK_NULL_HANDLE;
        uint64_t             lastUseSubmissionSerial = 0;
        LsfgFrameProvenance  frameProvenance{};
    };

    struct RetiredAhbImport {
        AHardwareBuffer* ahb = nullptr;
        WinTex texture{};
        uint64_t lastUseSubmissionSerial = 0;
        uint64_t retireAfterSubmissionSerial = 0;
    };

    struct RetiredWindowTexture {
        WinTex texture{};
        uint64_t lastUseSubmissionSerial = 0;
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        int64_t         ownerId        = 0;
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        VkBuffer        upload         = VK_NULL_HANDLE;
        AHardwareBuffer* ahb           = nullptr;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        bool            isAHB          = false;
        LsfgFrameProvenance frameProvenance{};
    };

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    void* adrenotoolsHandle = nullptr;
    int filterMode = 0;
    bool swapRB = false;
    int activeEffectId = 0;
    float activeSharpness = 1.0f;
    int activeEffectMask = 0;
    float activeBrightness = 0.0f;
    float activeContrast = 0.0f;
    float activeGamma = 1.0f;
    float maxAnisotropy           = 1.0f;
    bool  cubicSupported          = false;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    // Updated only after a replacement swapchain is successfully created.
    VkPresentModeKHR activePresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    uint32_t graphicsQueueFamilyQueueCount = 1;
    uint32_t presentCapableQueueFamilyCount = 0;
    bool alternatePresentQueueFamilyAvailable = false;
    bool hostSplitPresentQueueEnabled_ = true;
    bool hostSplitPresentQueueActive_ = false;
    uint32_t hostPresentQueueIndex_ = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    // Host-output confirmation remains telemetry-only. It never changes
    // render pacing or waits the render thread.
    bool hostGoogleDisplayTimingEnabled = false;
    bool hostPresentWaitEnabled = false;
    uint64_t hostPresentId_ = 1;
    uint32_t hostGooglePresentId_ = 1;
    uint64_t hostWsiAccepted_ = 0;
    uint64_t hostDisplayConfirmed_ = 0;
    uint64_t hostDisplayUnknown_ = 0;
    uint64_t repeatedContentPresent_ = 0;
    uint64_t uniquePhysicalPresent_ = 0;
    uint64_t sourceUniquePhysicalPresent_ = 0;
    uint64_t generatedUniquePhysicalPresent_ = 0;
    uint64_t firstUniquePhysicalPresentNs_ = 0;
    uint64_t lastUniquePhysicalPresentNs_ = 0;
    uint64_t lastAcceptedDesiredPresentTimeNs_ = 0;
    uint64_t hostRefreshPeriodNs_ = 0;
    uint64_t sourceUniqueWsiAccepted_ = 0;
    uint64_t generatedUniqueWsiAccepted_ = 0;
    uint64_t sourcePhysicalUnknown_ = 0;
    uint64_t generatedPhysicalUnknown_ = 0;
    uint64_t hostPhaseRescheduledTotal_ = 0;
    uint64_t hostTemporalBacklogTotal_ = 0;
    uint64_t hostRefreshCycleQueryFailureTotal_ = 0;
    uint64_t hostSwapchainGeneration_ = 0;
    uint64_t lsfgBackendTransitionId_ = 0;
    uint64_t lsfgBackendTransitionRevision_ = 0;
    uint64_t lsfgBackendTransitionStartGeneration_ = 0;
    uint32_t lsfgBackendTransitionRecreationAttempts_ = 0;
    uint32_t lsfgBackendTransitionRecreationCount_ = 0;
    bool lsfgBackendTransitionFirstRecreationFailed_ = false;
    bool lsfgBackendTransitionRebuildPending_ = false;
    bool lsfgBackendTransitionPolicyDirty_ = false;
    bool lsfgBackendTransitionPolicyCommitted_ = false;
    uint64_t hostPhysicalCadenceEpoch_ = 0;
    uint64_t legacyGeneratedSlotContextEpoch_ = 0;
    std::array<uint64_t, VKR_LSFG_MAX_GENERATIONS>
        legacyGeneratedSlotLastRawDesiredNs_{};
    std::array<uint64_t, VKR_LSFG_MAX_GENERATIONS>
        legacyGeneratedSlotPeriodNs_{};
    std::array<uint64_t, VKR_LSFG_MAX_GENERATIONS>
        legacyGeneratedSlotNextNs_{};
    std::array<uint64_t, VKR_LSFG_MAX_GENERATIONS>
        legacyGeneratedSlotIndex_{};
    uint64_t hostConfirmationPendingHighWater_ = 0;
    uint64_t hostConfirmationExpiredTotal_ = 0;
    uint64_t hostConfirmationOverflowTotal_ = 0;
    uint64_t hostDisplayTimingQueryFailureTotal_ = 0;
    uint64_t hostInvalidPresentMarginTotal_ = 0;
    uint64_t hostSuboptimalTotal_ = 0;
    uint32_t hostSuboptimalConsecutive_ = 0;
    bool hostSuboptimalActive_ = false;
    uint64_t hostSuboptimalStartNs_ = 0;
    uint64_t hostSuboptimalStartGeneration_ = 0;
    uint64_t hostSuboptimalEpisodeCount_ = 0;
    uint64_t hostSuboptimalLastRequeryNs_ = 0;
    uint64_t hostSuboptimalLastAuditGeneration_ = UINT64_MAX;
    uint64_t hostSuboptimalLastRecreateNs_ = 0;
    uint64_t hostSuboptimalRecreateGeneration_ = 0;
    VkColorSpaceKHR swapchainColorSpace_ = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    VkImageUsageFlags swapchainImageUsage_ = 0;
    VkSurfaceTransformFlagBitsKHR swapchainPreTransform_ =
        VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    VkCompositeAlphaFlagBitsKHR swapchainCompositeAlpha_ =
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    uint32_t swapchainRequestedImageCount_ = 0;
    std::deque<uint8_t> hostSuboptimalWindow_;
    std::deque<uint64_t> hostPresentLatencySamplesNs_;
    std::deque<uint64_t> hostPresenterQueueAgeSamplesNs_;
    std::deque<uint64_t> physicalCadenceErrorsNs_;
    std::deque<uint64_t> scheduledCadenceErrorsNs_;
    std::unordered_set<uint64_t> consumedLsfgDeliveries_;
    std::deque<HostDisplayConfirmation> pendingHostDisplayConfirmations;

    // Ordered LSFG deliveries are retained before host composition when Frame
    // Queue is enabled. Queue references participate in the existing AHB import
    // refcount so imported images cannot retire before their snapshot is consumed.
    std::unordered_map<int64_t, std::deque<QueuedLsfgHostDelivery>>
        pendingLsfgHostDeliveries_;
    std::unordered_set<uint64_t> hostSnapshottedLsfgDeliveries_;
    uint64_t hostDeliveryQueueContextEpoch_ = 0;
    std::atomic<uint32_t> pendingLsfgHostDeliveryCount_{0};
    std::atomic<uint64_t> hostDeliveryPendingHighWater_{0};
    std::atomic<uint64_t> sourceDeliveryReceived_{0};
    std::atomic<uint64_t> generatedDeliveryReceived_{0};
    std::atomic<uint64_t> sourceSnapshotCreated_{0};
    std::atomic<uint64_t> generatedSnapshotCreated_{0};
    std::atomic<uint64_t> sourceCoalescedDrop_{0};
    std::atomic<uint64_t> generatedCoalescedDrop_{0};
    std::atomic<uint64_t> sourceBacklogDrop_{0};
    std::atomic<uint64_t> generatedBacklogDrop_{0};
    std::atomic<uint64_t> sourceStaleDrop_{0};
    std::atomic<uint64_t> generatedStaleDrop_{0};
    std::atomic<uint64_t> sourceAhbReuseDrop_{0};
    std::atomic<uint64_t> generatedAhbReuseDrop_{0};

    // Frame Queue is retirement-aware final-compositor buffering. Presentation
    // stays on the render thread and WSI remains the natural pacing boundary.
    std::atomic<bool> lsfgFrameQueueEnabled_{false};
    std::atomic<uint32_t> lsfgFrameQueueTarget_{0};
    std::mutex graphicsQueueMutex_;
    std::mutex presentQueueMutex_;

    std::mutex hostPresenterMutex_;
    std::condition_variable hostPresenterCv_;
    std::condition_variable hostPresenterSpaceCv_;
    std::condition_variable hostPresenterDrainCv_;
    std::deque<PendingHostPresent> pendingHostPresents_;
    std::deque<CompletedHostPresent> completedHostPresents_;
    std::thread hostPresenterThread_;
    std::atomic<bool> hostPresenterRunning_{false};
    std::atomic<bool> hostPresenterBusy_{false};
    std::atomic<bool> hostPresentCompletionPending_{false};
    bool hostAsyncPresenterEnabled_ = false;
    bool hostAsyncPresenterActive_ = false;
    std::atomic<uint64_t> hostPresentEnqueueWaitNsTotal_{0};
    std::atomic<uint64_t> hostPresentEnqueueWaitCount_{0};
    std::atomic<uint32_t> hostPresentQueueHighWater_{0};
    // Telemetry-only serial for the queue that owns vkQueuePresentKHR. This
    // must never be used for pacing or synchronization decisions.
    std::atomic<uint64_t> presentQueuePresentSerial_{0};

    std::atomic<uint64_t> frameQueuePresentedTotal_{0};
    std::atomic<uint64_t> frameQueueRetirementWaitTotal_{0};
    std::atomic<uint64_t> frameQueueRetirementWaitNsTotal_{0};
    std::atomic<uint64_t> frameQueueAcquireNsTotal_{0};
    std::atomic<uint64_t> frameQueuePresentNsTotal_{0};
    std::atomic<uint64_t> frameQueuePresentSamples_{0};
    std::atomic<uint32_t> frameQueueMaxGpuOutstanding_{0};
    std::atomic<bool> frameQueueSmoothRuntimeSuppressed_{false};
    std::atomic<uint32_t> frameQueueSmoothPressureStrikes_{0};
    mutable std::atomic<bool> frameQueueSmoothFifoFallback_{false};
    std::atomic<uint64_t> frameQueueTelemetryEpoch_{0};
    std::atomic<uint64_t> frameQueueConfigRequestSerial_{0};

    int lsfgProvenanceSocket = -1;
    std::string lsfgProvenanceSocketPath;
    uint64_t provenanceRxTotal_ = 0;
    uint64_t provenanceMatchTotal_ = 0;
    uint64_t provenanceMissTotal_ = 0;
    uint64_t provenanceSupersededTotal_ = 0;
    uint64_t activeProvenanceContextEpoch_ = 0;
    uint64_t provenanceSocketOwnerGeneration_ = 0;
    bool provenanceFirstPacketLogged_ = false;
    std::deque<LsfgFrameProvenance> pendingLsfgProvenance;
    std::unordered_map<uint32_t, uint64_t> lsfgSwapchainImageAhbs;

    std::unordered_map<int64_t, WinTex>         texMap;

    std::unordered_map<AHardwareBuffer*, WinTex>              ahbImportCache;
    std::unordered_map<int64_t, std::vector<AHardwareBuffer*>> windowAhbs;
    std::unordered_map<AHardwareBuffer*, uint32_t>             ahbWindowRefCounts;
    std::vector<RetiredAhbImport>                              retiredAhbImports;
    std::vector<RetiredWindowTexture>                          retiredWindowTextures;
    RendererSubmissionTimeline                                 submissionTimeline{};
    std::atomic<uint64_t>                                      renderSubmissionSerial{0};

    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    std::vector<VkImageMemoryBarrier>  framePreUpload;
    std::vector<VkImageMemoryBarrier>  framePostUpload;

    void*  scanoutGameSC      = nullptr;
    void*  scanoutCursorSC    = nullptr;
    void*  scanoutCursorBuf   = nullptr;
    int32_t scanoutCursorBufW = 0;
    int32_t scanoutCursorBufH = 0;

    void*  scanoutTx          = nullptr;
    void*  scanoutGameTx      = nullptr;

    ARect  scanoutLastSrc{}, scanoutLastDst{};
    bool   scanoutGeoDirty    = true;
    bool   scanoutVisShown    = false;
    bool   scanoutApiLoaded   = false;
    void*  fnSCCreateFromWin  = nullptr;
    void*  fnSCRelease        = nullptr;
    void*  fnSTCreate         = nullptr;
    void*  fnSTDelete         = nullptr;
    void*  fnSTApply          = nullptr;
    void*  fnSTSetBuffer      = nullptr;
    void*  fnSTSetZOrder      = nullptr;
    void*  fnSTSetVisibility  = nullptr;
    void*  fnSTSetGeometry    = nullptr;
    void*  fnSTSetBackPressure = nullptr;
    bool   loadScanoutApi();

    int32_t scanoutDstX=0, scanoutDstY=0, scanoutDstW=0, scanoutDstH=0;

    int32_t lastDstX=0, lastDstY=0, lastDstW=0, lastDstH=0;
    bool    gameScVisible      = false;

    struct ScanoutPending { AHardwareBuffer* ahb=nullptr; int x=0,y=0,w=0,h=0; int fenceFd=-1; };
    std::mutex        scanoutMutex;
    ScanoutPending    scanoutPending{};
    std::atomic<bool> scanoutPendingDirty{false};

    short  pendingCursorX=0, pendingCursorY=0, pendingCursorHotX=0, pendingCursorHotY=0;
    bool   cursorPosDirty=false;
    bool   cursorImageDirty=false;

    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    VkBuffer         cursorStg  = VK_NULL_HANDLE;
    VkDeviceMemory   cursorStgM = VK_NULL_HANDLE;
    void*            cursorStgP = nullptr;
    VkDeviceSize     cursorStgC = 0;
    VkDeviceSize     cursorUploadSize = 0;

    VkInstance       instance;
    VkSurfaceKHR     surface;
    VkPhysicalDevice physicalDevice;
    VkDevice         device;
    VkQueue          graphicsQueue;
    VkQueue          presentQueue = VK_NULL_HANDLE;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;
    VkExtent2D       swapchainExt;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;

    // XR offscreen scene target: when active, renderFrame renders the composited scene into
    // this AHB-backed image (no surface acquire/present) so the immersive session samples it
    // directly. Enabled/disabled from JNI; resources recreated on resize.
    AHardwareBuffer*  xrAhb   = nullptr;
    VkImage           xrImg   = VK_NULL_HANDLE;
    VkDeviceMemory    xrMem   = VK_NULL_HANDLE;
    VkImageView       xrView  = VK_NULL_HANDLE;
    VkFramebuffer     xrFb    = VK_NULL_HANDLE;
    VkRenderPass      xrRp    = VK_NULL_HANDLE;
    VkExtent2D        xrExt   {0,0};
    std::atomic<bool> xrTargetActive{false};
    bool createXrTargetResources(uint32_t w, uint32_t h);
    void destroyXrTargetResources();

    struct VkCompositeTarget {
        VkImage image = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        uint32_t width = 0;
        uint32_t height = 0;
    };

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkRenderPass          compositePass = VK_NULL_HANDLE;
    std::array<VkCompositeTarget, VK_MAX_COMPOSITE_TARGETS> composite{};
    uint32_t compositeCount = 0;
    bool compositeBuilt = false;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline    = VK_NULL_HANDLE;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkSemaphore> renderDoneSems;
    // Frame-queue present semaphores are indexed by swapchain image.
    // Reacquiring an image proves WSI consumed its previous wait.
    std::vector<VkSemaphore> frameQueuePresentSems_;
    std::vector<VkSemaphore> retiredFrameQueuePresentSems_;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    // Allocate native state only after explicit backend selection. Legacy and
    // source-only launches retain the ordinary compositor resource topology.
    VkrLsfg* lsfg = nullptr;
    std::string lsfgCachePath;
    std::atomic<bool> framegenArmed{false};
    std::atomic<bool> framegenSupported{false};
    std::atomic<bool> nativeLsfgReady_{false};
    std::atomic<bool> nativePresentationSurfaceReady_{false};
    bool nativeVulkanDispatchLoaded_ = false;
    std::atomic<bool> framegenRequested{false};
    bool framegenArmWarned = false;
    uint32_t framegenMultiplier = 2;
    uint32_t framegenTargetRate = 0;
    float framegenFlowScale = 0.7f;
    uint32_t framegenFlowMode = VKR_LSFG_FLOW_FIXED;
    uint32_t framegenFlowPreset = VKR_LSFG_FLOW_PRESET_QUALITY;
    uint64_t framegenConfigRevision = 0;
    float framegenRefreshRate = 60.0f;
    std::atomic<float> framegenGpuUsagePercent_{-1.0f};
    std::atomic<int> framegenThermalStatus_{-1};
    std::atomic<float> framegenSourceFps_{0.0f};
    std::atomic<float> framegenOutputFps_{0.0f};
    std::atomic<float> framegenFrameTimeP95Ms_{0.0f};
    std::atomic<float> framegenSlowFrameRatio_{0.0f};
    uint64_t nativeLsfgContextEpoch_ = 0;
    uint64_t nativeLastContextReuseRevision_ = UINT64_MAX;
    std::atomic<uint64_t> framegenSourceFrames{0};
    std::atomic<uint64_t> framegenRealFrames{0};
    std::atomic<uint64_t> framegenMadeFrames{0};
    std::atomic<uint64_t> nativeGeneratedPresentedFrames_{0};
    std::atomic<uint64_t> presentedFrames{0};
    std::atomic<uint64_t> nativeSourceReceived_{0};
    std::atomic<uint64_t> nativeSourceWsiSubmitted_{0};
    std::atomic<uint64_t> nativeSourceWsiAccepted_{0};
    std::atomic<uint64_t> nativeSourceDisplayConfirmed_{0};
    std::atomic<uint64_t> nativeGeneratedRequested_{0};
    std::atomic<uint64_t> nativeGeneratedAdmitted_{0};
    std::atomic<uint64_t> nativeGeneratedDispatched_{0};
    std::atomic<uint64_t> nativeGeneratedCompleted_{0};
    std::atomic<uint64_t> nativeGeneratedWsiSubmitted_{0};
    std::atomic<uint64_t> nativeGeneratedWsiAccepted_{0};
    std::atomic<uint64_t> nativeGeneratedDisplayConfirmed_{0};
    std::atomic<uint64_t> nativeGeneratedDisplayConfirmedEpoch_{0};
    std::atomic<uint64_t> nativeSourceDisplayConfirmedEpoch_{0};
    std::atomic<uint64_t> nativeGeneratedDroppedBefore_{0};
    std::atomic<uint64_t> nativeGeneratedDroppedAfter_{0};
    std::atomic<uint64_t> nativeGeneratedSuperseded_{0};
    std::atomic<uint64_t> nativeGeneratedStale_{0};
    std::atomic<uint64_t> nativeGeneratedDeadlineRejected_{0};
    std::atomic<uint64_t> nativeGeneratedWsiRejected_{0};
    std::atomic<uint64_t> nativeGeneratedBacklogRejected_{0};
    std::array<uint32_t, MAX_FRAMES_IN_FLIGHT> nativeGeneratedSubmittedByFrame_{};
    std::array<uint64_t, MAX_FRAMES_IN_FLIGHT> nativeSubmissionStartedNs_{};
    std::atomic<uint64_t> nativeGpuCompletionLatencyNsTotal_{0};
    std::atomic<uint64_t> nativeGpuCompletionSamples_{0};
    std::atomic<uint64_t> nativeHostWaitNsTotal_{0};
    std::atomic<uint64_t> nativeHostWaitSamples_{0};
    uint64_t nativePresentRateSampleNs_ = 0;
    uint64_t nativePresentRateSourceAccepted_ = 0;
    uint64_t nativePresentRateGeneratedAccepted_ = 0;
    uint64_t nativePresentRateSourceConfirmed_ = 0;
    uint64_t nativePresentRateGeneratedConfirmed_ = 0;
    double nativeSourceWsiFps_ = 0.0;
    double nativeGeneratedWsiFps_ = 0.0;
    double nativeOutputWsiFps_ = 0.0;
    double nativeOutputConfirmedFps_ = 0.0;
    double nativeSourceConfirmedFps_ = 0.0;
    double nativeGeneratedConfirmedFps_ = 0.0;

    struct NativePresentationSchedule {
        std::array<uint64_t, VKR_LSFG_MAX_GENERATIONS> generatedDesiredNs{};
        uint32_t generatedCount = 0;
        uint64_t sourceDesiredNs = 0;
        uint64_t sourceIntervalNs = 0;
        uint64_t refreshPeriodNs = 0;
        uint32_t sourceRefreshCycles = 0;
        uint64_t phaseAdvanceCycles = 0;
    };
    SourceProtectedTimeline nativeSourceTimeline_{};
    SourceTimelineSample nativeSourceTimelineSample_{};
    uint64_t nativeTimelineLastSourceArrivalNs_ = 0;
    uint64_t nativeTimelineSourceIntervalNs_ = 0;
    uint64_t nativeTimelineGeneration_ = 0;
    std::string nativeLastAdmissionReason_{"none"};
    uint64_t nativeLastAdmissionP50PresentNs_ = 0;
    uint64_t nativeLastAdmissionP95PresentNs_ = 0;
    uint64_t nativeLastAdmissionServiceEstimateNs_ = 0;
    uint64_t nativeLastAdmissionSourceIntervalNs_ = 0;
    std::deque<uint64_t> nativeSourceWsiEventNs_;
    std::deque<uint64_t> nativeGeneratedWsiEventNs_;
    std::deque<uint64_t> nativeSourceConfirmedEventNs_;
    std::deque<uint64_t> nativeGeneratedConfirmedEventNs_;
    std::deque<uint64_t> nativeSourceUnobservedEventNs_;
    std::deque<uint64_t> nativeGeneratedUnobservedEventNs_;
    std::deque<uint64_t> nativeGeneratedRejectedEventNs_;
    uint64_t nativePresentationEvidenceStartNs_ = 0;
    uint32_t nativePresentationPressureStrikes_ = 0;
    uint32_t nativePresentationRecoveryStrikes_ = 0;
    bool nativePresentationPressureActive_ = false;
    VkrLsfgPresentationPressure nativePresentationPressure_{};

    bool nativeSwapchainTransferSupported_ = false;
    bool nativeComputeSupported_ = false;
    uint32_t nativeMinImageCount_ = 0;
    uint64_t nativeLastSourceFrame_ = 0;
    uint64_t nativeDeliveryId_ = 0;
    uint64_t nativeRuntimeSessionId_ = 0;
    void waitNativeResources();
    void recoverNativeAcquiredFrame();
    std::array<std::array<VkSemaphore, 3>, MAX_FRAMES_IN_FLIGHT>
        nativeExtraAcquireSems_{};

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;
    VkDescriptorPool ahbTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    mutable std::shared_mutex frameMutex;

    void createInstance();
    void createSurface();
    void pickPhysicalDevice();
    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out);
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createWinTexPool();
    void createAhbTexPool();
    void createCursorPipeline();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void createFrameQueuePresentSemaphores();
    bool ensureNativeExtraAcquireSemaphores();
    void retireFrameQueuePresentSemaphores();
    void destroyRetiredFrameQueuePresentSemaphores();
    void cleanupSwapchain();

    void createCompositePass();
    void destroyOneComposite(VkCompositeTarget& target);
    void destroyCompositeTargets();
    bool createOneComposite(VkCompositeTarget& target, uint32_t width, uint32_t height);
    bool createCompositeTargets(uint32_t width, uint32_t height, uint32_t count);
    void destroyLsfg();
    void createLsfg();
    uint32_t framegenExtraImages() const;
    bool compositeFormatSupported();
    void blitCompositeToSwapchain(
        VkCommandBuffer cmd, const VkCompositeTarget& source, VkImage destination);
    void initLsfgProvenanceSocket();
    void closeLsfgProvenanceSocket();
    void drainLsfgProvenance();
    void bindLsfgProvenance(AHardwareBuffer* ahb, WinTex& texture);
    uint64_t ahbIdentity(AHardwareBuffer* ahb) const;
    void pollHostDisplayConfirmations();
    std::vector<LsfgFrameProvenance> classifyHostPresentProvenance(
        const std::vector<DrawEntry>& draws) const;
    HostDesiredPresentDecision validatedHostDesiredPresentTime(
        const std::vector<LsfgFrameProvenance>& provenance);
    void recordHostPresent(
        const PendingHostPresent& present,
        uint64_t presentCallNs);
    void resetHostPhysicalCadenceTelemetry(const char* reason);
    void emitHostDisplayConfirmation(
        const HostDisplayConfirmation& confirmation,
        bool confirmed,
        bool unknown,
        const char* reason);
    void flushHostDisplayConfirmationsUnknown(const char* reason);
    uint32_t effectiveFrameQueueTarget() const;
    uint32_t hostDeliveryQueueCapacity() const;
    void resetLegacyGeneratedOutputSlotClock(const char* reason);
    bool assignLegacyGeneratedOutputSlot(LsfgFrameProvenance& provenance);
    bool legacyGeneratedOutputSlotMissed(
        const LsfgFrameProvenance& provenance, uint64_t nowNs) const;
    bool pruneMissedLegacyGeneratedOutputSlots(uint64_t nowNs);
    bool isLsfgHostDeliveryStale(const LsfgFrameProvenance& provenance) const;
    bool enqueueLsfgHostDelivery(
        int64_t ownerId, AHardwareBuffer* ahb, WinTex& source);
    bool selectQueuedLsfgHostDelivery(
        const RenderEntry& renderEntry, DrawEntry& draw);
    void consumeQueuedLsfgHostDeliveries(
        const std::vector<DrawEntry>& draws);
    void dropQueuedLsfgHostDeliveries(const char* reason);
    void dropQueuedLsfgHostDeliveriesForWindow(
        int64_t ownerId, const char* reason);
    void emitHostDeliveryAccounting(
        const char* reason,
        const LsfgFrameProvenance* provenance = nullptr);
    void recordHostSnapshotCreated(
        const LsfgFrameProvenance& provenance);
    void resetFrameQueueTelemetry();
    uint32_t activeFrameSlotCount() const;
    uint32_t countOutstandingFrameSubmissions(bool observeCompleted);
    void enforceFrameQueueSubmissionBudget(uint32_t target);
    void updateSmoothQueuePressure(uint64_t presentNs);
    void drainFrameQueueSubmissions(const char* reason);
    CompletedHostPresent executeHostPresent(PendingHostPresent present);
    void finalizeHostPresent(CompletedHostPresent&& completed);
    VkResult presentHostFrame(const PendingHostPresent& present);
    VkResult enqueueHostPresent(PendingHostPresent present);
    void hostPresenterLoop();
    void processHostPresentCompletions();
    void drainHostPresenter(const char* reason);
    uint32_t nativeHostSyntheticAdmissionCapacity();
    void noteNativeSourceArrival(uint64_t nowNs);
    NativePresentationSchedule buildNativePresentationSchedule(
        uint32_t generations, uint64_t sourceIndex);
    void resetNativePresentationTimeline(const char* reason);
    uint32_t nativeTemporalGenerationCapacity() const;
    void recordNativePresentationEvidence(
        bool generated, bool confirmed, bool unobserved, bool wsiAccepted,
        bool wsiRejected, uint64_t nowNs);
    void updateNativePresentationPressure(uint64_t nowNs);
    void requestLsfgSwapchainRebuild(const char* reason);
    void observeHostPresentResult(VkResult result);
    void emitNativeLsfgPipelineTelemetry(const char* reason);

    bool  createWinTexResources(WinTex& wt, int w, int h);
    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  retireAhbImport(AHardwareBuffer* ahb);
    void  releaseWindowAhbReference(AHardwareBuffer* ahb);
    void  releaseWindowAhbImports(int64_t id);
    void  evictWindowAhbImports(int64_t id, AHardwareBuffer* keepAhb);
    void  reclaimRetiredAhbImports();
    void  reclaimRetiredWindowTextures();
    void  markDrawResourcesSubmitted(uint64_t submissionSerial);
    void  completeObservedFence(VkFence fence);
    void  cleanupAllAHBCache();
    void  flushDeleteQueue();
    void  destroyWinTex(WinTex& wt);
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(VkDeviceSize sz);

    void recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& preUpload,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, bool curVis, bool keepOpen = false,
        const VkCompositeTarget* target = nullptr);
    void renderLoop();
    void renderFrame();

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkCommandBuffer beginOneTime();
    void            endOneTime(VkCommandBuffer cmd);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);
};
