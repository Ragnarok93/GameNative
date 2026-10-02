#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <vulkan/vulkan_android.h>
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
    uint64_t runtimeSessionId = 0;
    uint64_t contextEpoch = 0;
    uint64_t deliveryId = 0;
    uint64_t sourceIndex = 0;
    uint64_t batchId = 0;
    uint32_t swapchainImageIndex = 0;
    uint32_t interpolationCount = 0;
    uint8_t interpolationIndex = 0;
    uint8_t kind = 0; // 0=source, 1=generated
};

struct HostDisplayConfirmation {
    uint64_t hostPresentId = 0;
    uint32_t googlePresentId = 0;
    HostDisplayConfirmationBackend backend =
        HostDisplayConfirmationBackend::WsiAccepted;
    std::vector<LsfgFrameProvenance> frameProvenance;
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
    uint64_t acquireNs = 0;
    uint32_t gpuOutstanding = 0;
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
    uint32_t graphicsQueueFamilyIndex = 0;
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
    std::deque<HostDisplayConfirmation> pendingHostDisplayConfirmations;

    // Frame Queue is retirement-aware final-compositor buffering. Presentation
    // stays on the render thread and WSI remains the natural pacing boundary.
    std::atomic<bool> lsfgFrameQueueEnabled_{false};
    std::atomic<uint32_t> lsfgFrameQueueTarget_{0};
    std::mutex graphicsQueueMutex_;
    std::atomic<uint64_t> frameQueuePresentedTotal_{0};
    std::atomic<uint64_t> frameQueueRetirementWaitTotal_{0};
    std::atomic<uint64_t> frameQueueRetirementWaitNsTotal_{0};
    std::atomic<uint64_t> frameQueueAcquireNsTotal_{0};
    std::atomic<uint64_t> frameQueuePresentNsTotal_{0};
    std::atomic<uint64_t> frameQueuePresentSamples_{0};
    std::atomic<uint32_t> frameQueueMaxGpuOutstanding_{0};
    std::atomic<uint64_t> frameQueuePresentRetirementWaitTotal_{0};
    std::atomic<uint64_t> frameQueuePresentRetirementWaitNsTotal_{0};
    std::deque<uint64_t> frameQueuePendingPresentIds_;

    int lsfgProvenanceSocket = -1;
    std::string lsfgProvenanceSocketPath;
    uint64_t provenanceRxTotal_ = 0;
    uint64_t provenanceMatchTotal_ = 0;
    uint64_t provenanceMissTotal_ = 0;
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

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
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
    std::shared_mutex frameMutex;

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
    void retireFrameQueuePresentSemaphores();
    void destroyRetiredFrameQueuePresentSemaphores();
    void cleanupSwapchain();

    void initLsfgProvenanceSocket();
    void closeLsfgProvenanceSocket();
    void drainLsfgProvenance();
    void bindLsfgProvenance(AHardwareBuffer* ahb, WinTex& texture);
    uint64_t ahbIdentity(AHardwareBuffer* ahb) const;
    void pollHostDisplayConfirmations();
    void recordHostPresent(
        uint64_t hostPresentId,
        uint32_t googlePresentId,
        HostDisplayConfirmationBackend backend,
        const std::vector<LsfgFrameProvenance>& frameProvenance);
    void emitHostDisplayConfirmation(
        const HostDisplayConfirmation& confirmation,
        bool confirmed,
        bool unknown,
        const char* reason);
    void flushHostDisplayConfirmationsUnknown(const char* reason);
    uint32_t effectiveFrameQueueTarget() const;
    uint32_t activeFrameSlotCount() const;
    uint32_t countOutstandingFrameSubmissions(bool observeCompleted);
    void enforceFrameQueueSubmissionBudget(uint32_t target);
    void enforceFrameQueuePresentationBudget(uint32_t target);
    void drainFrameQueuePresentations(const char* reason);
    void drainFrameQueueSubmissions(const char* reason);
    VkResult presentHostFrame(const PendingHostPresent& present);

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
        short curW, short curH, bool curVis);
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
