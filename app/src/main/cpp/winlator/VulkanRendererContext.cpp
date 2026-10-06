#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#include "VulkanRendererContext.h"
#include <stdexcept>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <inttypes.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <cerrno>
#include <cstddef>
#include <chrono>
#include <unordered_set>
#include <time.h>
#include "window_vert.h"
#include "window_frag.h"
#include "../lsfg/vkr_lsfg.h"

extern "C" __attribute__((used, visibility("default")))
const char gamenative_vulkan_renderer_build_marker[] =
    "gamenative-host-display-confirmation-v3-split-present-worker";

namespace {
constexpr char LSFG_PROVENANCE_SOCKET[] = "gamenative-lsfg-provenance-v1";
constexpr uint32_t kLsfgFrameProvenanceMagic = 0x4c534650U; // "LSFP"
constexpr uint16_t kLsfgFrameProvenanceVersion = 2;
constexpr std::size_t kMaxPendingLsfgProvenance = 512;
constexpr std::size_t kMaxPendingHostConfirmations = 256;
constexpr uint64_t kMaxHostConfirmationAgeNs = 1000000000ULL;
constexpr uint64_t kMaxSanePresentMarginNs = 1000000000ULL;
constexpr int kLsfgProvenanceReceiveBufferBytes = 1024 * 1024;
constexpr char LSFG_DISPLAY_FEEDBACK_SOCKET[] =
    "gamenative-lsfg-display-feedback-v1";
constexpr uint32_t kHostDisplayFeedbackMagic = 0x4c534644U; // "LSFD"
constexpr uint16_t kHostDisplayFeedbackVersion = 1;

std::mutex gLsfgProvenanceSocketOwnerMutex;
int gLsfgProvenanceSocketFd = -1;
VulkanRendererContext* gLsfgProvenanceSocketOwner = nullptr;
std::vector<VulkanRendererContext*> gLsfgProvenanceSocketContexts;
uint64_t gLsfgProvenanceSocketOwnerGeneration = 0;

struct LsfgFrameProvenancePacket {
    uint32_t magic;
    uint16_t version;
    uint8_t kind;
    uint8_t interpolationIndex;
    uint32_t interpolationCount;
    uint32_t swapchainImageIndex;
    uint64_t runtimeSessionId;
    uint64_t contextEpoch;
    uint64_t deliveryId;
    uint64_t sourceIndex;
    uint64_t batchId;
    uint64_t desiredPresentTimeNs;
};
static_assert(sizeof(LsfgFrameProvenancePacket) == 64);

const char* hostDisplayBackendName(HostDisplayConfirmationBackend backend) {
    switch (backend) {
        case HostDisplayConfirmationBackend::GoogleDisplayTiming:
            return "google-display-timing";
        case HostDisplayConfirmationBackend::PresentWait:
            return "khr-present-wait";
        case HostDisplayConfirmationBackend::WsiAccepted:
            return "wsi-accepted";
    }
    return "wsi-accepted";
}

const char* provenanceKindName(uint8_t kind) {
    return kind == 1 ? "generated" : "source";
}

enum class HostDisplayFeedbackStatus : uint8_t {
    Unknown = 0,
    Confirmed = 1,
};

struct HostDisplayFeedbackPacket {
    uint32_t magic{kHostDisplayFeedbackMagic};
    uint16_t version{kHostDisplayFeedbackVersion};
    uint8_t status{0};
    uint8_t kind{0};
    uint64_t runtimeSessionId{0};
    uint64_t contextEpoch{0};
    uint64_t deliveryId{0};
    uint64_t actualPresentTimeNs{0};
    uint64_t provenanceDesiredPresentTimeNs{0};
    uint64_t submittedDesiredPresentTimeNs{0};
    uint64_t swapchainGeneration{0};
};
static_assert(sizeof(HostDisplayFeedbackPacket) == 64);

uint64_t monotonicTimeNs() noexcept {
    timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL
        + static_cast<uint64_t>(ts.tv_nsec);
}

uint64_t advanceDesiredPresentPhase(
        uint64_t desiredPresentTimeNs,
        uint64_t earliestAllowedNs,
        uint64_t refreshPeriodNs,
        uint64_t& phaseAdvanceCycles) noexcept {
    phaseAdvanceCycles = 0;
    if (refreshPeriodNs == 0 || desiredPresentTimeNs >= earliestAllowedNs)
        return desiredPresentTimeNs;
    const uint64_t deltaNs = earliestAllowedNs - desiredPresentTimeNs;
    uint64_t cycles = deltaNs / refreshPeriodNs;
    if (deltaNs % refreshPeriodNs != 0)
        ++cycles;
    if (cycles == 0)
        cycles = 1;
    if (cycles > (UINT64_MAX - desiredPresentTimeNs) / refreshPeriodNs)
        return 0;
    phaseAdvanceCycles = cycles;
    return desiredPresentTimeNs + cycles * refreshPeriodNs;
}

void publishLsfgHostDisplayFeedback(
        const HostDisplayConfirmation& confirmation,
        const LsfgFrameProvenance& provenance,
        bool confirmed) noexcept {
    if (!provenance.uniqueDelivery || provenance.deliveryId == 0)
        return;

    static const int socketFd = []() noexcept {
        return ::socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    }();
    if (socketFd < 0)
        return;

    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    constexpr std::size_t socketNameLength =
        sizeof(LSFG_DISPLAY_FEEDBACK_SOCKET) - 1;
    static_assert(socketNameLength + 1 <= sizeof(address.sun_path));
    std::memcpy(
        address.sun_path + 1,
        LSFG_DISPLAY_FEEDBACK_SOCKET,
        socketNameLength);
    const socklen_t addressLength = static_cast<socklen_t>(
        offsetof(sockaddr_un, sun_path) + 1 + socketNameLength);

    HostDisplayFeedbackPacket packet{};
    packet.status = static_cast<uint8_t>(
        confirmed ? HostDisplayFeedbackStatus::Confirmed
                  : HostDisplayFeedbackStatus::Unknown);
    packet.kind = provenance.kind;
    packet.runtimeSessionId = provenance.runtimeSessionId;
    packet.contextEpoch = provenance.contextEpoch;
    packet.deliveryId = provenance.deliveryId;
    packet.actualPresentTimeNs = confirmation.actualPresentTimeNs;
    packet.provenanceDesiredPresentTimeNs =
        confirmation.provenanceDesiredPresentTimeNs;
    packet.submittedDesiredPresentTimeNs =
        confirmation.submittedDesiredPresentTimeNs;
    packet.swapchainGeneration = confirmation.swapchainGeneration;

    const ssize_t sent = ::sendto(
        socketFd, &packet, sizeof(packet), MSG_DONTWAIT,
        reinterpret_cast<const sockaddr*>(&address), addressLength);
    static std::atomic<bool> successLogged{false};
    static std::atomic<unsigned> failureLogs{0};
    if (sent == static_cast<ssize_t>(sizeof(packet))) {
        if (!successLogged.exchange(true, std::memory_order_relaxed)) {
            __android_log_print(
                ANDROID_LOG_INFO, "LSFG_HOST_FEEDBACK",
                "host-feedback-send-ok socket=%s",
                LSFG_DISPLAY_FEEDBACK_SOCKET);
        }
    } else if (failureLogs.fetch_add(1, std::memory_order_relaxed) < 5) {
        __android_log_print(
            ANDROID_LOG_WARN, "LSFG_HOST_FEEDBACK",
            "host-feedback-send-failed errno=%d delivery_id=%" PRIu64,
            errno, provenance.deliveryId);
    }
}

} // namespace

VulkanRendererContext::VulkanRendererContext(
        ANativeWindow* win,
        int cW,
        int cH,
        void* aHandle,
        std::string provenanceSocketPath)
    : window(win), surfaceWidth(cW), surfaceHeight(cH), containerWidth(cW), containerHeight(cH),
      adrenotoolsHandle(aHandle),
      lsfgProvenanceSocketPath(std::move(provenanceSocketPath))
{
    createInstance(); createSurface(); pickPhysicalDevice(); createLogicalDevice();
    createSwapchain(); createRenderPass(); createDSLayout();
    createPipeline(true, pipeline);
    createFramebuffers(); createCmdPool(); createSampler();
    createWinTexPool(); createAhbTexPool(); createCursorDS(); createCmdBufs(); createSyncObjects();
    initLsfgProvenanceSocket();
    if (hostAsyncPresenterActive_) {
        try {
            hostPresenterRunning_.store(true, std::memory_order_release);
            hostPresenterThread_ =
                std::thread(&VulkanRendererContext::hostPresenterLoop, this);
            __android_log_print(
                ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
                "event=present-worker-start host_present_worker=1 "
                "present_queue_split=1 present_queue_index=%u max_queue_depth=%u",
                hostPresentQueueIndex_, MAX_HOST_PRESENT_QUEUE_DEPTH);
        } catch (...) {
            hostPresenterRunning_.store(false, std::memory_order_release);
            hostAsyncPresenterActive_ = false;
            __android_log_print(
                ANDROID_LOG_WARN, "LSFG_FRAME_QUEUE",
                "event=present-worker-fallback reason=thread-start-failed host_present_worker=0");
        }
    }
    isRunning = true;
    renderThread = std::thread(&VulkanRendererContext::renderLoop, this);
}

VulkanRendererContext::~VulkanRendererContext() {
    isRunning = false; dirtyCV.notify_all();
    if (renderThread.joinable()) renderThread.join();
    drainHostPresenter("renderer-destroy");
    if (hostPresenterThread_.joinable()) {
        hostPresenterRunning_.store(false, std::memory_order_release);
        hostPresenterCv_.notify_all();
        hostPresenterSpaceCv_.notify_all();
        hostPresenterThread_.join();
    }
    processHostPresentCompletions();
    std::lock_guard<std::mutex> lk(renderMutex);
    vk_.DeviceWaitIdle(device);
    submissionTimeline.completeAllFrames();
    flushHostDisplayConfirmationsUnknown("renderer-destroy");
    destroyLsfg();
    destroyCompositeTargets();
    if (compositePass != VK_NULL_HANDLE) {
        vk_.DestroyRenderPass(device, compositePass, nullptr);
        compositePass = VK_NULL_HANDLE;
    }
    closeLsfgProvenanceSocket();

    for (auto& [id, wt] : texMap) {
        if (wt.isAHB) wt = {};
        else destroyWinTex(wt);
    }
    texMap.clear();
    dropQueuedLsfgHostDeliveries("renderer-destroy");
    cleanupAllAHBCache();

    for (auto& retired : retiredWindowTextures) {
        auto& wt = retired.texture;
        VkDescriptorPool pool =
            wt.descriptorPool != VK_NULL_HANDLE ? wt.descriptorPool : winTexPool;
        if (wt.ds   != VK_NULL_HANDLE) vk_.FreeDescriptorSets(device, pool, 1, &wt.ds);
        if (wt.view != VK_NULL_HANDLE) vk_.DestroyImageView(device, wt.view, nullptr);
        if (wt.img  != VK_NULL_HANDLE) vk_.DestroyImage(device, wt.img, nullptr);
        if (wt.mem  != VK_NULL_HANDLE) vk_.FreeMemory(device, wt.mem, nullptr);
        if (wt.stg  != VK_NULL_HANDLE) {
            vk_.DestroyBuffer(device, wt.stg, nullptr);
            vk_.FreeMemory(device, wt.stgMem, nullptr);
        }
    }
    retiredWindowTextures.clear();

    destroyXrTargetResources();
    cleanupSwapchain(); cleanupCursorTex();
    destroyRetiredFrameQueuePresentSemaphores();

    vk_.DestroySampler(device, sampler, nullptr);
    if (ahbTexPool != VK_NULL_HANDLE)
        vk_.DestroyDescriptorPool(device, ahbTexPool, nullptr);
    vk_.DestroyDescriptorPool(device, winTexPool, nullptr);
    vk_.DestroyPipeline(device, pipeline, nullptr);
    vk_.DestroyPipelineLayout(device, pipeLayout, nullptr);
    vk_.DestroyDescriptorSetLayout(device, dsLayout, nullptr);
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        vk_.DestroySemaphore(device, renderDoneSems[i], nullptr);
        vk_.DestroySemaphore(device, imgAvailSems[i], nullptr);
        for (auto semaphore : nativeExtraAcquireSems_[i]) {
            if (semaphore != VK_NULL_HANDLE)
                vk_.DestroySemaphore(device, semaphore, nullptr);
        }
        vk_.DestroyFence(device, inFlightFences[i], nullptr);
    }
    vk_.DestroyCommandPool(device, cmdPool, nullptr);
    vk_.DestroyRenderPass(device, renderPass, nullptr);
    if (nativeVulkanDispatchLoaded_) {
        vkd_unload();
        nativeVulkanDispatchLoaded_ = false;
    }
    vk_.DestroyDevice(device, nullptr);
    vk_.DestroySurfaceKHR(instance, surface, nullptr);
    vk_.DestroyInstance(instance, nullptr);
    if (adrenotoolsHandle) { dlclose(adrenotoolsHandle); adrenotoolsHandle = nullptr; }
}

void VulkanRendererContext::loadInstanceDispatch() {
    auto i = [&](const char* name) { return gipa ? gipa(instance, name) : nullptr; };
#define LOAD_I2(fn) vk_.fn = (PFN_vk##fn)i("vk"#fn)
    LOAD_I2(DestroyInstance);
    LOAD_I2(EnumeratePhysicalDevices);
    LOAD_I2(GetPhysicalDeviceProperties);
    LOAD_I2(GetPhysicalDeviceFeatures2);
    LOAD_I2(GetPhysicalDeviceMemoryProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    LOAD_I2(GetPhysicalDeviceSurfaceFormatsKHR);
    LOAD_I2(GetPhysicalDeviceSurfacePresentModesKHR);
    LOAD_I2(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceSupportKHR);
    LOAD_I2(CreateDevice);
    LOAD_I2(DestroySurfaceKHR);
    LOAD_I2(CreateAndroidSurfaceKHR);
    LOAD_I2(GetDeviceProcAddr);
}

void VulkanRendererContext::loadDeviceDispatch() {
    auto d = [&](const char* name) -> PFN_vkVoidFunction {
        return vk_.GetDeviceProcAddr ? vk_.GetDeviceProcAddr(device, name) : nullptr;
    };
#define LOAD_D2(fn) vk_.fn = (PFN_vk##fn)d("vk"#fn)
    LOAD_D2(DestroyDevice);
    LOAD_D2(GetDeviceQueue);
    LOAD_D2(DeviceWaitIdle);
    LOAD_D2(CreateSwapchainKHR);
    LOAD_D2(DestroySwapchainKHR);
    LOAD_D2(GetSwapchainImagesKHR);
    LOAD_D2(AcquireNextImageKHR);
    LOAD_D2(QueuePresentKHR);
    LOAD_D2(GetPastPresentationTimingGOOGLE);
    LOAD_D2(GetRefreshCycleDurationGOOGLE);
    LOAD_D2(WaitForPresentKHR);
    LOAD_D2(QueueSubmit);
    LOAD_D2(CreateRenderPass);
    LOAD_D2(DestroyRenderPass);
    LOAD_D2(CreateFramebuffer);
    LOAD_D2(DestroyFramebuffer);
    LOAD_D2(CreateImageView);
    LOAD_D2(DestroyImageView);
    LOAD_D2(CreateImage);
    LOAD_D2(DestroyImage);
    LOAD_D2(CreateBuffer);
    LOAD_D2(DestroyBuffer);
    LOAD_D2(AllocateMemory);
    LOAD_D2(FreeMemory);
    LOAD_D2(MapMemory);
    LOAD_D2(FlushMappedMemoryRanges);
    LOAD_D2(BindBufferMemory);
    LOAD_D2(BindImageMemory);
    LOAD_D2(GetBufferMemoryRequirements);
    LOAD_D2(GetImageMemoryRequirements);
    LOAD_D2(CreateDescriptorSetLayout);
    LOAD_D2(DestroyDescriptorSetLayout);
    LOAD_D2(CreateDescriptorPool);
    LOAD_D2(DestroyDescriptorPool);
    LOAD_D2(AllocateDescriptorSets);
    LOAD_D2(FreeDescriptorSets);
    LOAD_D2(UpdateDescriptorSets);
    LOAD_D2(CreatePipelineLayout);
    LOAD_D2(DestroyPipelineLayout);
    LOAD_D2(CreateShaderModule);
    LOAD_D2(DestroyShaderModule);
    LOAD_D2(CreateGraphicsPipelines);
    LOAD_D2(DestroyPipeline);
    LOAD_D2(CreateCommandPool);
    LOAD_D2(DestroyCommandPool);
    LOAD_D2(AllocateCommandBuffers);
    LOAD_D2(FreeCommandBuffers);
    LOAD_D2(BeginCommandBuffer);
    LOAD_D2(EndCommandBuffer);
    LOAD_D2(ResetCommandBuffer);
    LOAD_D2(CmdBeginRenderPass);
    LOAD_D2(CmdEndRenderPass);
    LOAD_D2(CmdBindPipeline);
    LOAD_D2(CmdBindDescriptorSets);
    LOAD_D2(CmdDraw);
    LOAD_D2(CmdPushConstants);
    LOAD_D2(CmdSetViewport);
    LOAD_D2(CmdSetScissor);
    LOAD_D2(CmdPipelineBarrier);
    LOAD_D2(CmdCopyImage);
    LOAD_D2(CmdCopyBufferToImage);
    LOAD_D2(CreateSampler);
    LOAD_D2(DestroySampler);
    LOAD_D2(CreateSemaphore);
    LOAD_D2(DestroySemaphore);
    LOAD_D2(CreateFence);
    LOAD_D2(DestroyFence);
    LOAD_D2(WaitForFences);
    LOAD_D2(ResetFences);
    LOAD_D2(GetFenceStatus);

    vk_.GetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)d("vkGetAndroidHardwareBufferPropertiesANDROID");
}

void VulkanRendererContext::createInstance() {
    RLOG("createInstance: adrenotoolsHandle=%p (custom driver %s)",
        adrenotoolsHandle, adrenotoolsHandle?"ACTIVE":"NOT SET - using stock driver");

    if (adrenotoolsHandle) {
        gipa = (PFN_vkGetInstanceProcAddr)dlsym(adrenotoolsHandle, "vkGetInstanceProcAddr");
    }
    if (!gipa) {
        void* loaderLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
        if (loaderLib)
            gipa = (PFN_vkGetInstanceProcAddr)dlsym(loaderLib, "vkGetInstanceProcAddr");
    }

    vk_.CreateInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    VkApplicationInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;
    ai.pApplicationName="Winlator"; ai.apiVersion=VK_API_VERSION_1_3;
    const char* ext[]={"VK_KHR_surface","VK_KHR_android_surface"};
    VkInstanceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo=&ai; ci.enabledExtensionCount=2; ci.ppEnabledExtensionNames=ext;
    if (vk_.CreateInstance(&ci,nullptr,&instance)!=VK_SUCCESS) throw std::runtime_error("instance");

    loadInstanceDispatch();
}

void VulkanRendererContext::createSurface() {
    VkAndroidSurfaceCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window=window;
    if (vk_.CreateAndroidSurfaceKHR(instance,&ci,nullptr,&surface)!=VK_SUCCESS) throw std::runtime_error("surface");
}

void VulkanRendererContext::pickPhysicalDevice() {
    uint32_t n=0; vk_.EnumeratePhysicalDevices(instance,&n,nullptr);
    std::vector<VkPhysicalDevice> devs(n); vk_.EnumeratePhysicalDevices(instance,&n,devs.data());
    physicalDevice = VK_NULL_HANDLE;
    graphicsQueueFamilyIndex = 0;
    graphicsQueueFamilyQueueCount = 1;
    presentCapableQueueFamilyCount = 0;
    alternatePresentQueueFamilyAvailable = false;
    for (auto d : devs) {
        uint32_t qCount = 0;
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, qProps.data());

        int32_t selectedGraphicsPresentFamily = -1;
        uint32_t presentFamilyCount = 0;
        for (uint32_t i = 0; i < qCount; i++) {
            VkBool32 present = VK_FALSE;
            vk_.GetPhysicalDeviceSurfaceSupportKHR(d, i, surface, &present);
            if (present) ++presentFamilyCount;
            if (selectedGraphicsPresentFamily < 0
                    && (qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)
                    && present) {
                selectedGraphicsPresentFamily = static_cast<int32_t>(i);
            }
        }
        if (selectedGraphicsPresentFamily >= 0) {
            physicalDevice = d;
            graphicsQueueFamilyIndex =
                static_cast<uint32_t>(selectedGraphicsPresentFamily);
            graphicsQueueFamilyQueueCount =
                qProps[graphicsQueueFamilyIndex].queueCount;
            presentCapableQueueFamilyCount = presentFamilyCount;
            alternatePresentQueueFamilyAvailable = presentFamilyCount > 1;
            return;
        }
    }
    if (n > 0) physicalDevice = devs[0];
}

void VulkanRendererContext::createLogicalDevice() {
    const char* splitQueueEnv = std::getenv("GAMENATIVE_LSFG_SPLIT_PRESENT_QUEUE");
    hostSplitPresentQueueEnabled_ =
        splitQueueEnv == nullptr || std::strcmp(splitQueueEnv, "0") != 0;
    const bool splitPresentQueueCapable = graphicsQueueFamilyQueueCount >= 2;
    uint32_t requestedHostQueueCount =
        hostSplitPresentQueueEnabled_ && splitPresentQueueCapable ? 2U : 1U;
    std::array<float, 2> p{1.f, 1.f};
    VkDeviceQueueCreateInfo qi{}; qi.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qi.queueFamilyIndex=graphicsQueueFamilyIndex; qi.queueCount=requestedHostQueueCount; qi.pQueuePriorities=p.data();

    bool googleDisplayTimingExtension = false;
    bool presentIdExtension = false;
    bool presentWaitExtension = false;
    PFN_vkEnumerateDeviceExtensionProperties enumDevExts =
        (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    { uint32_t n=0; if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,nullptr);
      std::vector<VkExtensionProperties> av(n);
      if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,av.data());
      for (auto& e:av) {
          if (strcmp(e.extensionName,"VK_EXT_filter_cubic")==0
           || strcmp(e.extensionName,"VK_IMG_filter_cubic")==0) cubicSupported=true;
          if (strcmp(e.extensionName, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)==0)
              googleDisplayTimingExtension = true;
          if (strcmp(e.extensionName, VK_KHR_PRESENT_ID_EXTENSION_NAME)==0)
              presentIdExtension = true;
          if (strcmp(e.extensionName, VK_KHR_PRESENT_WAIT_EXTENSION_NAME)==0)
              presentWaitExtension = true;
      } }

    VkPhysicalDevicePresentIdFeaturesKHR presentIdFeatures{};
    presentIdFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;
    VkPhysicalDevicePresentWaitFeaturesKHR presentWaitFeatures{};
    presentWaitFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR;
    VkPhysicalDeviceFeatures2 features2{};
    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    void* featureChain = nullptr;
    if (presentIdExtension) {
        presentIdFeatures.pNext = featureChain;
        featureChain = &presentIdFeatures;
    }
    if (presentWaitExtension) {
        presentWaitFeatures.pNext = featureChain;
        featureChain = &presentWaitFeatures;
    }
    features2.pNext = featureChain;
    if (featureChain && vk_.GetPhysicalDeviceFeatures2)
        vk_.GetPhysicalDeviceFeatures2(physicalDevice, &features2);

    hostGoogleDisplayTimingEnabled = googleDisplayTimingExtension;
    hostPresentWaitEnabled =
        presentIdExtension && presentWaitExtension
        && presentIdFeatures.presentId == VK_TRUE
        && presentWaitFeatures.presentWait == VK_TRUE;

    std::vector<const char*> extList = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    };
    if (cubicSupported) extList.push_back("VK_EXT_filter_cubic");
    if (hostGoogleDisplayTimingEnabled)
        extList.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
    if (hostPresentWaitEnabled) {
        extList.push_back(VK_KHR_PRESENT_ID_EXTENSION_NAME);
        extList.push_back(VK_KHR_PRESENT_WAIT_EXTENSION_NAME);
    }

    VkPhysicalDevicePresentIdFeaturesKHR enabledPresentId{};
    enabledPresentId.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;
    enabledPresentId.presentId = hostPresentWaitEnabled ? VK_TRUE : VK_FALSE;
    VkPhysicalDevicePresentWaitFeaturesKHR enabledPresentWait{};
    enabledPresentWait.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR;
    enabledPresentWait.presentWait = hostPresentWaitEnabled ? VK_TRUE : VK_FALSE;
    if (hostPresentWaitEnabled)
        enabledPresentWait.pNext = &enabledPresentId;

    VkDeviceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.pQueueCreateInfos=&qi; ci.queueCreateInfoCount=1;
    ci.enabledExtensionCount=(uint32_t)extList.size(); ci.ppEnabledExtensionNames=extList.data();
    ci.pNext = hostPresentWaitEnabled ? &enabledPresentWait : nullptr;
    VkResult deviceCreateResult =
        vk_.CreateDevice(physicalDevice,&ci,nullptr,&device);
    if (deviceCreateResult != VK_SUCCESS && requestedHostQueueCount > 1) {
        __android_log_print(
            ANDROID_LOG_WARN, "LSFG_FRAME_QUEUE",
            "event=split-present-queue-fallback reason=device-create-failed result=%d "
            "requested_device_queues=%u fallback_device_queues=1",
            static_cast<int>(deviceCreateResult),
            requestedHostQueueCount);
        requestedHostQueueCount = 1;
        qi.queueCount = 1;
        deviceCreateResult =
            vk_.CreateDevice(physicalDevice,&ci,nullptr,&device);
    }
    if (deviceCreateResult != VK_SUCCESS) throw std::runtime_error("device");
    vk_.GetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
    loadDeviceDispatch();
    if (!vk_.GetPastPresentationTimingGOOGLE)
        hostGoogleDisplayTimingEnabled = false;
    if (!vk_.WaitForPresentKHR)
        hostPresentWaitEnabled = false;
    vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);
    presentQueue = graphicsQueue;
    hostSplitPresentQueueActive_ = false;
    hostPresentQueueIndex_ = 0;
    if (requestedHostQueueCount > 1) {
        presentQueue = VK_NULL_HANDLE;
        vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,1,&presentQueue);
        if (presentQueue != VK_NULL_HANDLE) {
            hostSplitPresentQueueActive_ = true;
            hostPresentQueueIndex_ = 1;
        } else {
            presentQueue = graphicsQueue;
            __android_log_print(
                ANDROID_LOG_WARN, "LSFG_FRAME_QUEUE",
                "event=split-present-queue-fallback reason=queue-handle-unavailable "
                "requested_device_queues=%u fallback_present_queue_index=0",
                requestedHostQueueCount);
        }
    }

    const char* asyncPresenterEnv = std::getenv("GAMENATIVE_LSFG_ASYNC_PRESENT");
    hostAsyncPresenterEnabled_ =
        asyncPresenterEnv == nullptr || std::strcmp(asyncPresenterEnv, "0") != 0;
    hostAsyncPresenterActive_ =
        hostAsyncPresenterEnabled_ && hostSplitPresentQueueActive_;

    vk_.GetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);
    maxAnisotropy = props.limits.maxSamplerAnisotropy;
    __android_log_print(ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
        "capability google_display_timing=%d refresh_cycle_query=%d present_wait=%d",
        hostGoogleDisplayTimingEnabled ? 1 : 0,
        vk_.GetRefreshCycleDurationGOOGLE ? 1 : 0,
        hostPresentWaitEnabled ? 1 : 0);
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=queue-capability graphics_family=%u family_queue_count=%u "
        "requested_device_queues=%u present_capable_families=%u "
        "alternate_present_family=%d second_same_family_queue_available=%d "
        "split_present_queue_enabled=%d split_present_queue_active=%d "
        "present_queue_index=%u async_presenter_enabled=%d async_presenter_active=%d",
        graphicsQueueFamilyIndex,
        graphicsQueueFamilyQueueCount,
        requestedHostQueueCount,
        presentCapableQueueFamilyCount,
        alternatePresentQueueFamilyAvailable ? 1 : 0,
        graphicsQueueFamilyQueueCount > 1 ? 1 : 0,
        hostSplitPresentQueueEnabled_ ? 1 : 0,
        hostSplitPresentQueueActive_ ? 1 : 0,
        hostPresentQueueIndex_,
        hostAsyncPresenterEnabled_ ? 1 : 0,
        hostAsyncPresenterActive_ ? 1 : 0);
}

void VulkanRendererContext::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice,surface,&caps);
    swapchainExt=(caps.currentExtent.width!=0xFFFFFFFF)?caps.currentExtent:VkExtent2D{(uint32_t)surfaceWidth,(uint32_t)surfaceHeight};
    uint32_t fmtN=0; vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtN); vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,fmts.data());
    swapchainFmt = VK_FORMAT_R8G8B8A8_UNORM;
    uint32_t imgCount=caps.minImageCount+1;
    if (caps.maxImageCount>0&&imgCount>caps.maxImageCount) imgCount=caps.maxImageCount;

    uint32_t pmCount=0;
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,nullptr);
    availablePresentModes.resize(pmCount);
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,availablePresentModes.data());
    VkPresentModeKHR presentMode=VK_PRESENT_MODE_FIFO_KHR;
    bool requestedModeSupported = false;
    for (auto pm:availablePresentModes) {
        if(pm==requestedPresentMode){
            presentMode=pm;
            requestedModeSupported = true;
            break;
        }
    }
    if(verboseLog){
        std::string pmList;
        for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
        RLOG("createSwapchain: %dx%d fmt=%d supportedPresentModes=[%s] chosen=%d req=%d",
            swapchainExt.width,swapchainExt.height,(int)swapchainFmt,pmList.c_str(),(int)presentMode,(int)requestedPresentMode);
    }

    VkSurfaceTransformFlagBitsKHR pre=
        (caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)?
        VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:caps.currentTransform;

    VkCompositeAlphaFlagBitsKHR compositeAlpha=
        (caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

    VkSwapchainKHR oldSwapchain=swapchain;
    VkSwapchainCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface=surface; ci.minImageCount=imgCount; ci.imageFormat=swapchainFmt;
    ci.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR; ci.imageExtent=swapchainExt;
    ci.imageArrayLayers=1; ci.imageUsage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    nativeSwapchainTransferSupported_ =
        (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0 &&
        (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0;
    if (nativeSwapchainTransferSupported_)
        ci.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=pre;
    ci.compositeAlpha=compositeAlpha; ci.presentMode=presentMode; ci.clipped=VK_TRUE;
    ci.oldSwapchain=oldSwapchain;
    if (vk_.CreateSwapchainKHR(device,&ci,nullptr,&swapchain)!=VK_SUCCESS) throw std::runtime_error("swapchain");
    if (oldSwapchain != VK_NULL_HANDLE) {
        flushHostDisplayConfirmationsUnknown("swapchain-recreated");
        resetHostPhysicalCadenceTelemetry("swapchain-recreated");
    }
    ++hostSwapchainGeneration_;
    lastAcceptedDesiredPresentTimeNs_ = 0;
    hostRefreshPeriodNs_ = 0;
    VkResult refreshCycleResult = VK_ERROR_EXTENSION_NOT_PRESENT;
    if (hostGoogleDisplayTimingEnabled && vk_.GetRefreshCycleDurationGOOGLE) {
        VkRefreshCycleDurationGOOGLE refreshCycle{};
        refreshCycleResult = vk_.GetRefreshCycleDurationGOOGLE(
            device, swapchain, &refreshCycle);
        constexpr uint64_t kMinSaneRefreshPeriodNs = 1000000ULL;
        constexpr uint64_t kMaxSaneRefreshPeriodNs = 100000000ULL;
        if (refreshCycleResult == VK_SUCCESS
                && refreshCycle.refreshDuration >= kMinSaneRefreshPeriodNs
                && refreshCycle.refreshDuration <= kMaxSaneRefreshPeriodNs) {
            hostRefreshPeriodNs_ = refreshCycle.refreshDuration;
        } else {
            ++hostRefreshCycleQueryFailureTotal_;
        }
    }
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
        "event=refresh-cycle swapchain_generation=%" PRIu64
        " refresh_period_ns=%" PRIu64 " refresh_hz=%.3f query_result=%d",
        hostSwapchainGeneration_,
        hostRefreshPeriodNs_,
        hostRefreshPeriodNs_ != 0
            ? 1000000000.0 / static_cast<double>(hostRefreshPeriodNs_) : 0.0,
        static_cast<int>(refreshCycleResult));
    RLOG("swapchain created: %dx%d format=%d presentMode=%d compositeAlpha=%d imgCount=%u",
        swapchainExt.width,swapchainExt.height,(int)swapchainFmt,(int)presentMode,(int)compositeAlpha,imgCount);
    const VkPresentModeKHR previousActivePresentMode = activePresentMode;
    activePresentMode = presentMode;
    if (previousActivePresentMode != activePresentMode) {
        frameQueueSmoothRuntimeSuppressed_.store(false, std::memory_order_release);
        frameQueueSmoothPressureStrikes_.store(0, std::memory_order_relaxed);
        frameQueueSmoothFifoFallback_.store(false, std::memory_order_release);
        resetFrameQueueTelemetry();
        __android_log_print(ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
            "event=active-present-mode-transition requested_present_mode=%d active_present_mode=%d "
            "requested_supported=%d swapchain_generation=%llu",
            static_cast<int>(requestedPresentMode), static_cast<int>(activePresentMode),
            requestedModeSupported ? 1 : 0,
            static_cast<unsigned long long>(hostSwapchainGeneration_));
    }
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=swapchain-present-mode-activated requested_present_mode=%d active_present_mode=%d "
        "requested_supported=%d fallback_to_fifo=%d swapchain_generation=%llu",
        static_cast<int>(requestedPresentMode),
        static_cast<int>(activePresentMode),
        requestedModeSupported ? 1 : 0,
        (!requestedModeSupported && requestedPresentMode != VK_PRESENT_MODE_FIFO_KHR) ? 1 : 0,
        static_cast<unsigned long long>(hostSwapchainGeneration_));
    if (oldSwapchain!=VK_NULL_HANDLE) vk_.DestroySwapchainKHR(device,oldSwapchain,nullptr);
    vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,nullptr);
    swapchainImages.resize(imgCount); vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,swapchainImages.data());
    swapchainViews.resize(imgCount);
    for (size_t i=0;i<imgCount;i++) {
        VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image=swapchainImages[i]; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=swapchainFmt;
        vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        VkComponentMapping mapping{};
        mapping.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components = mapping;
        if (vk_.CreateImageView(device,&vi,nullptr,&swapchainViews[i])!=VK_SUCCESS) throw std::runtime_error("imgview");
    }
}

void VulkanRendererContext::createRenderPass() {
    VkAttachmentDescription att{}; att.format=swapchainFmt; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;
    VkSubpassDependency dep{}; dep.srcSubpass=VK_SUBPASS_EXTERNAL; dep.dstSubpass=0;
    dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dep.srcAccessMask=0;
    dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount=1; ci.pAttachments=&att; ci.subpassCount=1; ci.pSubpasses=&sub;
    ci.dependencyCount=1; ci.pDependencies=&dep;
    if (vk_.CreateRenderPass(device,&ci,nullptr,&renderPass)!=VK_SUCCESS) throw std::runtime_error("renderpass");
}

void VulkanRendererContext::createDSLayout() {
    VkDescriptorSetLayoutBinding b{}; b.binding=0; b.descriptorCount=1;
    b.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount=1; ci.pBindings=&b;
    if (vk_.CreateDescriptorSetLayout(device,&ci,nullptr,&dsLayout)!=VK_SUCCESS) throw std::runtime_error("dslayout");
}
 

VkShaderModule VulkanRendererContext::makeShader(const uint32_t* code, size_t sz) {
    VkShaderModuleCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize=sz; ci.pCode=code; VkShaderModule m;
    if (vk_.CreateShaderModule(device,&ci,nullptr,&m)!=VK_SUCCESS) throw std::runtime_error("shader");
    return m;
}

void VulkanRendererContext::createPipeline(bool blend, VkPipeline& out) {
    if (pipeLayout==VK_NULL_HANDLE) {
        VkPushConstantRange pc{}; pc.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;
        pc.size=sizeof(WindowPushConstants);
        VkPipelineLayoutCreateInfo li{}; li.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        li.setLayoutCount=1; li.pSetLayouts=&dsLayout; li.pushConstantRangeCount=1; li.pPushConstantRanges=&pc;
        if (vk_.CreatePipelineLayout(device,&li,nullptr,&pipeLayout)!=VK_SUCCESS) throw std::runtime_error("pipelayout");
    }
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_frag_code,sizeof(window_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=blend?VK_TRUE:VK_FALSE;
    if (blend){ba.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;ba.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;ba.colorBlendOp=VK_BLEND_OP_ADD;ba.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;ba.dstAlphaBlendFactor=VK_BLEND_FACTOR_ZERO;ba.alphaBlendOp=VK_BLEND_OP_ADD;}
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&out)!=VK_SUCCESS) throw std::runtime_error("pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
}


void VulkanRendererContext::createCursorPipeline() {  }
void VulkanRendererContext::createFramebuffers() {
    swapchainFBs.resize(swapchainViews.size());
    for (size_t i=0;i<swapchainViews.size();i++) {
        VkImageView att[]={swapchainViews[i]};
        VkFramebufferCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass=renderPass; fi.attachmentCount=1; fi.pAttachments=att;
        fi.width=swapchainExt.width; fi.height=swapchainExt.height; fi.layers=1;
        if (vk_.CreateFramebuffer(device,&fi,nullptr,&swapchainFBs[i])!=VK_SUCCESS) throw std::runtime_error("fb");
    }
}

void VulkanRendererContext::createCmdPool() {
    VkCommandPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; ci.queueFamilyIndex=graphicsQueueFamilyIndex;
    if (vk_.CreateCommandPool(device,&ci,nullptr,&cmdPool)!=VK_SUCCESS) throw std::runtime_error("cmdpool");
}

void VulkanRendererContext::createSampler() {
    bool useCubic = (filterMode == 2) && cubicSupported;
    VkFilter filter = (filterMode == 1) ? VK_FILTER_NEAREST
                    : (useCubic)         ? VK_FILTER_CUBIC_EXT
                    :                      VK_FILTER_LINEAR;
    RLOG("createSampler: filter=%s (filterMode=%d, cubicSupported=%d)",
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR_FALLBACK"):filterMode==1?"NEAREST":"LINEAR",
        filterMode, (int)cubicSupported);
    VkSamplerCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    ci.magFilter=filter; ci.minFilter=filter;
    ci.addressModeU=ci.addressModeV=ci.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    ci.minLod=0.f; ci.maxLod=0.f;
    if (vk_.CreateSampler(device,&ci,nullptr,&sampler)!=VK_SUCCESS) throw std::runtime_error("sampler");
}

void VulkanRendererContext::createWinTexPool() {

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 129};
    VkDescriptorPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.poolSizeCount=1; ci.pPoolSizes=&ps; ci.maxSets=129;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&winTexPool)!=VK_SUCCESS) throw std::runtime_error("wintexpool");
}

void VulkanRendererContext::createAhbTexPool() {
    VkDescriptorPoolSize ps{
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, MAX_AHB_IMPORTS_TOTAL};
    VkDescriptorPoolCreateInfo ci{};
    ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.poolSizeCount=1;
    ci.pPoolSizes=&ps;
    ci.maxSets=MAX_AHB_IMPORTS_TOTAL;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&ahbTexPool)!=VK_SUCCESS)
        throw std::runtime_error("ahbtexpool");
}


void VulkanRendererContext::createCursorDS() {
    VkDescriptorSetAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool=winTexPool; ai.descriptorSetCount=1; ai.pSetLayouts=&dsLayout;
    vk_.AllocateDescriptorSets(device,&ai,&cursorDS);
}

void VulkanRendererContext::createCmdBufs() {
    cmdBufs.resize(MAX_FRAMES_IN_FLIGHT);
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool=cmdPool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=MAX_FRAMES_IN_FLIGHT;
    if (vk_.AllocateCommandBuffers(device,&ai,cmdBufs.data())!=VK_SUCCESS) throw std::runtime_error("cmdbuf");
}

void VulkanRendererContext::createSyncObjects() {
    imgAvailSems.resize(MAX_FRAMES_IN_FLIGHT); renderDoneSems.resize(MAX_FRAMES_IN_FLIGHT); inFlightFences.resize(MAX_FRAMES_IN_FLIGHT);
    VkSemaphoreCreateInfo si{}; si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
    for (uint32_t i=0;i<MAX_FRAMES_IN_FLIGHT;i++) {
        if (vk_.CreateSemaphore(device,&si,nullptr,&imgAvailSems[i])!=VK_SUCCESS||
            vk_.CreateSemaphore(device,&si,nullptr,&renderDoneSems[i])!=VK_SUCCESS||
            vk_.CreateFence(device,&fi,nullptr,&inFlightFences[i])!=VK_SUCCESS) throw std::runtime_error("sync");
    }
    createFrameQueuePresentSemaphores();
}

bool VulkanRendererContext::ensureNativeExtraAcquireSemaphores() {
    if (!device || !vk_.CreateSemaphore) return false;
    VkSemaphoreCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (uint32_t frame = 0; frame < MAX_FRAMES_IN_FLIGHT; ++frame) {
        for (auto& semaphore : nativeExtraAcquireSems_[frame]) {
            if (semaphore != VK_NULL_HANDLE) continue;
            if (vk_.CreateSemaphore(device, &info, nullptr, &semaphore) != VK_SUCCESS) {
                RLOG_E("Native LSFG acquire semaphore creation failed frame=%u", frame);
                return false;
            }
        }
    }
    return true;
}

void VulkanRendererContext::createFrameQueuePresentSemaphores() {
    if (!frameQueuePresentSems_.empty() || swapchainImages.empty())
        return;
    VkSemaphoreCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    frameQueuePresentSems_.resize(swapchainImages.size(), VK_NULL_HANDLE);
    for (auto& semaphore : frameQueuePresentSems_) {
        if (vk_.CreateSemaphore(device, &info, nullptr, &semaphore) != VK_SUCCESS) {
            for (auto created : frameQueuePresentSems_) {
                if (created != VK_NULL_HANDLE)
                    vk_.DestroySemaphore(device, created, nullptr);
            }
            frameQueuePresentSems_.clear();
            throw std::runtime_error("frame-queue-present-sync");
        }
    }
}

void VulkanRendererContext::retireFrameQueuePresentSemaphores() {
    for (auto semaphore : frameQueuePresentSems_) {
        if (semaphore != VK_NULL_HANDLE)
            retiredFrameQueuePresentSems_.push_back(semaphore);
    }
    frameQueuePresentSems_.clear();
}

void VulkanRendererContext::destroyRetiredFrameQueuePresentSemaphores() {
    for (auto semaphore : retiredFrameQueuePresentSems_) {
        if (semaphore != VK_NULL_HANDLE)
            vk_.DestroySemaphore(device, semaphore, nullptr);
    }
    retiredFrameQueuePresentSems_.clear();
}

uint32_t VulkanRendererContext::effectiveFrameQueueTarget() const {
    if (!lsfgFrameQueueEnabled_.load(std::memory_order_acquire)) {
        frameQueueSmoothFifoFallback_.store(false, std::memory_order_release);
        return 0;
    }

    const uint32_t requested =
        std::min<uint32_t>(2, lsfgFrameQueueTarget_.load(std::memory_order_acquire));
    const bool fifoPresent = activePresentMode == VK_PRESENT_MODE_FIFO_KHR;
    frameQueueSmoothFifoFallback_.store(
        requested == 2 && fifoPresent, std::memory_order_release);

    if (requested == 2 && fifoPresent)
        return 1;
    if (requested == 2
            && frameQueueSmoothRuntimeSuppressed_.load(std::memory_order_acquire))
        return 1;

    return requested;
}


uint32_t VulkanRendererContext::hostDeliveryQueueCapacity() const {
    if (!lsfgFrameQueueEnabled_.load(std::memory_order_acquire))
        return MIN_HOST_DELIVERY_QUEUE_CAPACITY;

    // Pre-composition delivery retention is a correctness boundary, not a GPU
    // submission-depth control. Keep the requested delivery depth even when
    // Smooth falls back to Balanced WSI/GPU pacing after a present stall.
    const uint32_t requestedTarget = std::min<uint32_t>(
        2, lsfgFrameQueueTarget_.load(std::memory_order_acquire));
    return std::min<uint32_t>(
        MAX_HOST_DELIVERY_QUEUE_CAPACITY,
        requestedTarget + MIN_HOST_DELIVERY_QUEUE_CAPACITY);
}

bool VulkanRendererContext::isLsfgHostDeliveryStale(
        const LsfgFrameProvenance& provenance) const {
    if (!provenance.valid || provenance.deliveryId == 0)
        return false;
    if (hostDeliveryQueueContextEpoch_ != 0
            && provenance.contextEpoch != 0
            && provenance.contextEpoch != hostDeliveryQueueContextEpoch_) {
        return true;
    }
    if (provenance.desiredPresentTimeNs == 0)
        return false;
    const uint64_t nowNs = monotonicTimeNs();
    return nowNs != 0
        && nowNs > provenance.desiredPresentTimeNs
        && nowNs - provenance.desiredPresentTimeNs
            > MAX_HOST_TEMPORAL_STALE_NS;
}

void VulkanRendererContext::emitHostDeliveryAccounting(
        const char* reason,
        const LsfgFrameProvenance* provenance) {
    const uint64_t sourceReceived =
        sourceDeliveryReceived_.load(std::memory_order_relaxed);
    const uint64_t generatedReceived =
        generatedDeliveryReceived_.load(std::memory_order_relaxed);
    const uint64_t sourceSnapshots =
        sourceSnapshotCreated_.load(std::memory_order_relaxed);
    const uint64_t generatedSnapshots =
        generatedSnapshotCreated_.load(std::memory_order_relaxed);
    const uint64_t sourceCoalesced =
        sourceCoalescedDrop_.load(std::memory_order_relaxed);
    const uint64_t generatedCoalesced =
        generatedCoalescedDrop_.load(std::memory_order_relaxed);
    const uint64_t sourceBacklog =
        sourceBacklogDrop_.load(std::memory_order_relaxed);
    const uint64_t generatedBacklog =
        generatedBacklogDrop_.load(std::memory_order_relaxed);
    const uint64_t sourceStale =
        sourceStaleDrop_.load(std::memory_order_relaxed);
    const uint64_t generatedStale =
        generatedStaleDrop_.load(std::memory_order_relaxed);
    const uint64_t sourceReuse =
        sourceAhbReuseDrop_.load(std::memory_order_relaxed);
    const uint64_t generatedReuse =
        generatedAhbReuseDrop_.load(std::memory_order_relaxed);
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_HOST_DELIVERY",
        "event=delivery-accounting reason=%s delivery_id=%" PRIu64
        " context_epoch=%" PRIu64 " kind=%s pending=%u pending_high_water=%" PRIu64
        " host_received=%" PRIu64 " host_snapshot_created=%" PRIu64
        " host_coalesced_drop=%" PRIu64 " host_backlog_drop=%" PRIu64
        " host_stale_drop=%" PRIu64 " host_ahb_reuse_drop=%" PRIu64
        " source_received=%" PRIu64 " generated_received=%" PRIu64
        " source_snapshot_created=%" PRIu64 " generated_snapshot_created=%" PRIu64
        " source_coalesced_drop=%" PRIu64 " generated_coalesced_drop=%" PRIu64
        " source_backlog_drop=%" PRIu64 " generated_backlog_drop=%" PRIu64
        " source_stale_drop=%" PRIu64 " generated_stale_drop=%" PRIu64
        " source_ahb_reuse_drop=%" PRIu64 " generated_ahb_reuse_drop=%" PRIu64
        " delivery_handoff=%s delivery_queue_capacity=%u effective_gpu_target=%u"
        " graphics_family=%u family_queue_count=%u present_capable_families=%u"
        " alternate_present_family=%d second_same_family_queue_available=%d"
        " present_queue_split=%d present_queue_index=%u",
        reason ? reason : "none",
        provenance ? provenance->deliveryId : 0,
        provenance ? provenance->contextEpoch : 0,
        provenance ? provenanceKindName(provenance->kind) : "none",
        pendingLsfgHostDeliveryCount_.load(std::memory_order_relaxed),
        hostDeliveryPendingHighWater_.load(std::memory_order_relaxed),
        sourceReceived + generatedReceived,
        sourceSnapshots + generatedSnapshots,
        sourceCoalesced + generatedCoalesced,
        sourceBacklog + generatedBacklog,
        sourceStale + generatedStale,
        sourceReuse + generatedReuse,
        sourceReceived,
        generatedReceived,
        sourceSnapshots,
        generatedSnapshots,
        sourceCoalesced,
        generatedCoalesced,
        sourceBacklog,
        generatedBacklog,
        sourceStale,
        generatedStale,
        sourceReuse,
        generatedReuse,
        lsfgFrameQueueEnabled_.load(std::memory_order_acquire)
            ? "ordered-buffered" : "ordered-minimum",
        hostDeliveryQueueCapacity(),
        lsfgFrameQueueEnabled_.load(std::memory_order_acquire)
            ? effectiveFrameQueueTarget() : 0U,
        graphicsQueueFamilyIndex,
        graphicsQueueFamilyQueueCount,
        presentCapableQueueFamilyCount,
        alternatePresentQueueFamilyAvailable ? 1 : 0,
        graphicsQueueFamilyQueueCount > 1 ? 1 : 0,
        hostSplitPresentQueueActive_ ? 1 : 0,
        hostPresentQueueIndex_);
}

void VulkanRendererContext::dropQueuedLsfgHostDeliveriesForWindow(
        int64_t ownerId, const char* reason) {
    auto qit = pendingLsfgHostDeliveries_.find(ownerId);
    if (qit == pendingLsfgHostDeliveries_.end())
        return;
    auto& queue = qit->second;
    while (!queue.empty()) {
        QueuedLsfgHostDelivery dropped = queue.front();
        queue.pop_front();
        if (reason && strcmp(reason, "provenance-epoch-reset") == 0) {
            if (dropped.provenance.kind == 1)
                generatedStaleDrop_.fetch_add(1, std::memory_order_relaxed);
            else
                sourceStaleDrop_.fetch_add(1, std::memory_order_relaxed);
        } else {
            if (dropped.provenance.kind == 1)
                generatedBacklogDrop_.fetch_add(1, std::memory_order_relaxed);
            else
                sourceBacklogDrop_.fetch_add(1, std::memory_order_relaxed);
        }
        pendingLsfgHostDeliveryCount_.fetch_sub(1, std::memory_order_relaxed);
        releaseWindowAhbReference(dropped.ahb);
        emitHostDeliveryAccounting(reason, &dropped.provenance);
    }
    pendingLsfgHostDeliveries_.erase(qit);
}

void VulkanRendererContext::dropQueuedLsfgHostDeliveries(const char* reason) {
    std::vector<int64_t> owners;
    owners.reserve(pendingLsfgHostDeliveries_.size());
    for (const auto& [ownerId, queue] : pendingLsfgHostDeliveries_) {
        if (!queue.empty())
            owners.push_back(ownerId);
    }
    for (int64_t ownerId : owners)
        dropQueuedLsfgHostDeliveriesForWindow(ownerId, reason);
}

bool VulkanRendererContext::enqueueLsfgHostDelivery(
        int64_t ownerId, AHardwareBuffer* ahb, WinTex& source) {
    const LsfgFrameProvenance provenance = source.frameProvenance;
    if (!provenance.valid || provenance.deliveryId == 0)
        return true;

    if (provenance.kind == 1)
        generatedDeliveryReceived_.fetch_add(1, std::memory_order_relaxed);
    else
        sourceDeliveryReceived_.fetch_add(1, std::memory_order_relaxed);

    if (provenance.contextEpoch != 0
            && hostDeliveryQueueContextEpoch_ != 0
            && provenance.contextEpoch != hostDeliveryQueueContextEpoch_) {
        dropQueuedLsfgHostDeliveries("provenance-epoch-reset");
        hostSnapshottedLsfgDeliveries_.clear();
    }
    if (provenance.contextEpoch != 0)
        hostDeliveryQueueContextEpoch_ = provenance.contextEpoch;

    if (isLsfgHostDeliveryStale(provenance)) {
        if (provenance.kind == 1)
            generatedStaleDrop_.fetch_add(1, std::memory_order_relaxed);
        else
            sourceStaleDrop_.fetch_add(1, std::memory_order_relaxed);
        emitHostDeliveryAccounting("stale-before-host-snapshot", &provenance);
        source.frameProvenance = {};
        return false;
    }

    const uint32_t capacity = hostDeliveryQueueCapacity();
    if (capacity == 0) {
        const uint64_t received =
            sourceDeliveryReceived_.load(std::memory_order_relaxed)
            + generatedDeliveryReceived_.load(std::memory_order_relaxed);
        if (received == 1 || received % 120 == 0)
            emitHostDeliveryAccounting("periodic", &provenance);
        return true;
    }

    auto& queue = pendingLsfgHostDeliveries_[ownerId];

    // If an AHB is reused before an older queued delivery using it was
    // snapshotted, that older content is no longer immutable. Drop it rather
    // than presenting newer pixels with older provenance.
    for (auto it = queue.begin(); it != queue.end();) {
        if (it->ahb != ahb || it->provenance.deliveryId == provenance.deliveryId) {
            ++it;
            continue;
        }
        QueuedLsfgHostDelivery dropped = *it;
        it = queue.erase(it);
        if (dropped.provenance.kind == 1) {
            generatedCoalescedDrop_.fetch_add(1, std::memory_order_relaxed);
            generatedAhbReuseDrop_.fetch_add(1, std::memory_order_relaxed);
        } else {
            sourceCoalescedDrop_.fetch_add(1, std::memory_order_relaxed);
            sourceAhbReuseDrop_.fetch_add(1, std::memory_order_relaxed);
        }
        pendingLsfgHostDeliveryCount_.fetch_sub(1, std::memory_order_relaxed);
        releaseWindowAhbReference(dropped.ahb);
        emitHostDeliveryAccounting("ahb-reused-before-snapshot", &dropped.provenance);
    }

    const auto duplicate = std::find_if(
        queue.begin(), queue.end(),
        [&](const QueuedLsfgHostDelivery& queued) {
            return queued.provenance.deliveryId == provenance.deliveryId;
        });
    if (duplicate != queue.end())
        return true;

    while (queue.size() >= capacity) {
        auto dropIt = std::find_if(
            queue.begin(), queue.end(),
            [](const QueuedLsfgHostDelivery& queued) {
                return queued.provenance.kind == 1;
            });
        if (dropIt == queue.end() && provenance.kind == 1) {
            generatedBacklogDrop_.fetch_add(1, std::memory_order_relaxed);
            emitHostDeliveryAccounting("generated-backlog-protect-source", &provenance);
            source.frameProvenance = {};
            return false;
        }
        if (dropIt == queue.end())
            dropIt = queue.begin();

        QueuedLsfgHostDelivery dropped = *dropIt;
        queue.erase(dropIt);
        if (dropped.provenance.kind == 1)
            generatedBacklogDrop_.fetch_add(1, std::memory_order_relaxed);
        else
            sourceBacklogDrop_.fetch_add(1, std::memory_order_relaxed);
        pendingLsfgHostDeliveryCount_.fetch_sub(1, std::memory_order_relaxed);
        releaseWindowAhbReference(dropped.ahb);
        emitHostDeliveryAccounting("host-delivery-queue-full", &dropped.provenance);
    }

    ++ahbWindowRefCounts[ahb];
    queue.push_back(QueuedLsfgHostDelivery{
        .ahb = ahb,
        .provenance = provenance,
        .enqueuedAtNs = monotonicTimeNs(),
    });
    const uint32_t pending =
        pendingLsfgHostDeliveryCount_.fetch_add(1, std::memory_order_relaxed) + 1U;
    uint64_t highWater =
        hostDeliveryPendingHighWater_.load(std::memory_order_relaxed);
    while (highWater < pending
            && !hostDeliveryPendingHighWater_.compare_exchange_weak(
                highWater, pending, std::memory_order_relaxed)) {}

    const uint64_t received =
        sourceDeliveryReceived_.load(std::memory_order_relaxed)
        + generatedDeliveryReceived_.load(std::memory_order_relaxed);
    if (received == 1 || received % 120 == 0)
        emitHostDeliveryAccounting("periodic", &provenance);
    return true;
}

bool VulkanRendererContext::selectQueuedLsfgHostDelivery(
        const RenderEntry& renderEntry, DrawEntry& draw) {
    // Ordered LSFG handoff remains active even with user-facing Frame Queue
    // Off. Off disables extra GPU/WSI buffering, not delivery correctness.
    auto qit = pendingLsfgHostDeliveries_.find(renderEntry.id);
    if (qit == pendingLsfgHostDeliveries_.end())
        return false;
    auto& queue = qit->second;
    while (!queue.empty()) {
        const QueuedLsfgHostDelivery queued = queue.front();
        const bool wrongEpoch =
            hostDeliveryQueueContextEpoch_ != 0
            && queued.provenance.contextEpoch != 0
            && queued.provenance.contextEpoch != hostDeliveryQueueContextEpoch_;
        if (wrongEpoch || isLsfgHostDeliveryStale(queued.provenance)) {
            queue.pop_front();
            if (queued.provenance.kind == 1)
                generatedStaleDrop_.fetch_add(1, std::memory_order_relaxed);
            else
                sourceStaleDrop_.fetch_add(1, std::memory_order_relaxed);
            pendingLsfgHostDeliveryCount_.fetch_sub(1, std::memory_order_relaxed);
            releaseWindowAhbReference(queued.ahb);
            emitHostDeliveryAccounting(
                wrongEpoch ? "provenance-epoch-reset" : "queued-delivery-stale",
                &queued.provenance);
            continue;
        }

        auto imported = ahbImportCache.find(queued.ahb);
        if (imported == ahbImportCache.end()) {
            queue.pop_front();
            if (queued.provenance.kind == 1)
                generatedBacklogDrop_.fetch_add(1, std::memory_order_relaxed);
            else
                sourceBacklogDrop_.fetch_add(1, std::memory_order_relaxed);
            pendingLsfgHostDeliveryCount_.fetch_sub(1, std::memory_order_relaxed);
            releaseWindowAhbReference(queued.ahb);
            emitHostDeliveryAccounting("queued-import-missing", &queued.provenance);
            continue;
        }

        WinTex& source = imported->second;
        draw.ownerId = renderEntry.id;
        draw.img = source.img;
        draw.ds = source.ds;
        draw.x = renderEntry.x;
        draw.y = renderEntry.y;
        draw.w = source.w;
        draw.h = source.h;
        draw.isAHB = true;
        draw.ahb = queued.ahb;
        draw.frameProvenance = queued.provenance;
        if (source.needsTransition) {
            draw.needsTransition = true;
            source.needsTransition = false;
        }
        return true;
    }
    pendingLsfgHostDeliveries_.erase(qit);
    return false;
}

void VulkanRendererContext::recordHostSnapshotCreated(
        const LsfgFrameProvenance& provenance) {
    if (!provenance.valid || provenance.deliveryId == 0)
        return;
    if (!hostSnapshottedLsfgDeliveries_.insert(provenance.deliveryId).second)
        return;
    if (provenance.kind == 1)
        generatedSnapshotCreated_.fetch_add(1, std::memory_order_relaxed);
    else
        sourceSnapshotCreated_.fetch_add(1, std::memory_order_relaxed);
    const uint64_t snapshots =
        sourceSnapshotCreated_.load(std::memory_order_relaxed)
        + generatedSnapshotCreated_.load(std::memory_order_relaxed);
    if (snapshots == 1 || snapshots % 120 == 0)
        emitHostDeliveryAccounting("host-snapshot-created", &provenance);
}

void VulkanRendererContext::consumeQueuedLsfgHostDeliveries(
        const std::vector<DrawEntry>& draws) {
    for (const auto& draw : draws) {
        if (!draw.frameProvenance.valid || draw.frameProvenance.deliveryId == 0)
            continue;
        auto qit = pendingLsfgHostDeliveries_.find(draw.ownerId);
        if (qit == pendingLsfgHostDeliveries_.end() || qit->second.empty())
            continue;
        auto& queued = qit->second.front();
        if (queued.provenance.deliveryId != draw.frameProvenance.deliveryId)
            continue;
        AHardwareBuffer* ahb = queued.ahb;
        qit->second.pop_front();
        pendingLsfgHostDeliveryCount_.fetch_sub(1, std::memory_order_relaxed);
        releaseWindowAhbReference(ahb);
        if (qit->second.empty())
            pendingLsfgHostDeliveries_.erase(qit);
    }
    if (pendingLsfgHostDeliveryCount_.load(std::memory_order_relaxed) > 0) {
        needsRender.store(true, std::memory_order_release);
        dirtyCV.notify_one();
    }
}

void VulkanRendererContext::resetFrameQueueTelemetry() {
    frameQueuePresentedTotal_.store(0, std::memory_order_relaxed);
    frameQueueRetirementWaitTotal_.store(0, std::memory_order_relaxed);
    frameQueueRetirementWaitNsTotal_.store(0, std::memory_order_relaxed);
    frameQueueAcquireNsTotal_.store(0, std::memory_order_relaxed);
    frameQueuePresentNsTotal_.store(0, std::memory_order_relaxed);
    frameQueuePresentSamples_.store(0, std::memory_order_relaxed);
    frameQueueMaxGpuOutstanding_.store(0, std::memory_order_relaxed);
    frameQueueSmoothPressureStrikes_.store(0, std::memory_order_relaxed);
    frameQueueTelemetryEpoch_.fetch_add(1, std::memory_order_relaxed);
}

uint32_t VulkanRendererContext::activeFrameSlotCount() const {
    if (!lsfgFrameQueueEnabled_.load(std::memory_order_acquire))
        return BASE_FRAMES_IN_FLIGHT;
    const uint32_t target = effectiveFrameQueueTarget();
    return target + 1U;
}

uint32_t VulkanRendererContext::countOutstandingFrameSubmissions(bool observeCompleted) {
    uint32_t outstanding = 0;
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
        if (submissionTimeline.frameSubmissionSerial[i] == 0)
            continue;
        if (observeCompleted && vk_.GetFenceStatus
                && vk_.GetFenceStatus(device, inFlightFences[i]) == VK_SUCCESS) {
            completeObservedFence(inFlightFences[i]);
            continue;
        }
        ++outstanding;
    }
    return outstanding;
}

void VulkanRendererContext::enforceFrameQueueSubmissionBudget(uint32_t target) {
    if (!lsfgFrameQueueEnabled_.load(std::memory_order_acquire) || target == 0)
        return;

    uint32_t outstanding = countOutstandingFrameSubmissions(true);
    uint32_t observedMax = frameQueueMaxGpuOutstanding_.load(std::memory_order_relaxed);
    while (observedMax < outstanding
            && !frameQueueMaxGpuOutstanding_.compare_exchange_weak(
                observedMax, outstanding, std::memory_order_relaxed)) {}

    if (outstanding < MAX_BUFFERED_GPU_SUBMISSIONS)
        return;

    uint32_t oldestSlot = MAX_FRAMES_IN_FLIGHT;
    uint64_t oldestSerial = UINT64_MAX;
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
        const uint64_t serial = submissionTimeline.frameSubmissionSerial[i];
        if (serial != 0 && serial < oldestSerial) {
            oldestSerial = serial;
            oldestSlot = i;
        }
    }
    if (oldestSlot >= MAX_FRAMES_IN_FLIGHT)
        return;

    const auto waitStart = std::chrono::steady_clock::now();
    const VkResult waitResult = vk_.WaitForFences(
        device, 1, &inFlightFences[oldestSlot], VK_TRUE, UINT64_MAX);
    const uint64_t waitNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - waitStart).count());
    frameQueueRetirementWaitTotal_.fetch_add(1, std::memory_order_relaxed);
    frameQueueRetirementWaitNsTotal_.fetch_add(waitNs, std::memory_order_relaxed);
    if (waitResult == VK_SUCCESS)
        completeObservedFence(inFlightFences[oldestSlot]);

    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=retirement-wait target=%u slot=%u serial=%llu wait_ms=%.3f result=%d",
        target,
        oldestSlot,
        static_cast<unsigned long long>(oldestSerial),
        static_cast<double>(waitNs) / 1000000.0,
        static_cast<int>(waitResult));
}

void VulkanRendererContext::updateSmoothQueuePressure(uint64_t presentNs) {
    if (!lsfgFrameQueueEnabled_.load(std::memory_order_acquire)
            || lsfgFrameQueueTarget_.load(std::memory_order_acquire) != 2
            || activePresentMode == VK_PRESENT_MODE_FIFO_KHR) {
        frameQueueSmoothPressureStrikes_.store(0, std::memory_order_relaxed);
        return;
    }
    if (frameQueueSmoothRuntimeSuppressed_.load(std::memory_order_acquire))
        return;

    if (presentNs < SMOOTH_PRESENT_STALL_NS) {
        frameQueueSmoothPressureStrikes_.store(0, std::memory_order_relaxed);
        return;
    }

    const uint32_t pressureStrikes =
        frameQueueSmoothPressureStrikes_.fetch_add(
            1, std::memory_order_relaxed) + 1;
    if (pressureStrikes < SMOOTH_PRESENT_STALL_STRIKES) {
        __android_log_print(
            ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
            "event=smooth-pressure-strike reason=present-stall "
            "present_ms=%.3f threshold_ms=%.3f pressure_strikes=%u required=%u",
            static_cast<double>(presentNs) / 1000000.0,
            static_cast<double>(SMOOTH_PRESENT_STALL_NS) / 1000000.0,
            pressureStrikes,
            SMOOTH_PRESENT_STALL_STRIKES);
        return;
    }

    frameQueueSmoothRuntimeSuppressed_.store(true, std::memory_order_release);
    __android_log_print(
        ANDROID_LOG_WARN, "LSFG_FRAME_QUEUE",
        "event=smooth-runtime-fallback reason=present-stall "
        "present_ms=%.3f threshold_ms=%.3f pressure_strikes=%u "
        "required=%u effective_target=1",
        static_cast<double>(presentNs) / 1000000.0,
        static_cast<double>(SMOOTH_PRESENT_STALL_NS) / 1000000.0,
        pressureStrikes,
        SMOOTH_PRESENT_STALL_STRIKES);
}

void VulkanRendererContext::drainFrameQueueSubmissions(const char* reason) {
    uint32_t waited = 0;
    uint64_t waitNsTotal = 0;
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
        if (submissionTimeline.frameSubmissionSerial[i] == 0)
            continue;
        VkResult status = vk_.GetFenceStatus
            ? vk_.GetFenceStatus(device, inFlightFences[i])
            : VK_NOT_READY;
        if (status != VK_SUCCESS) {
            const auto waitStart = std::chrono::steady_clock::now();
            status = vk_.WaitForFences(
                device, 1, &inFlightFences[i], VK_TRUE, UINT64_MAX);
            const uint64_t waitNs = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now() - waitStart).count());
            waitNsTotal += waitNs;
            ++waited;
            frameQueueRetirementWaitTotal_.fetch_add(1, std::memory_order_relaxed);
            frameQueueRetirementWaitNsTotal_.fetch_add(waitNs, std::memory_order_relaxed);
        }
        if (status == VK_SUCCESS)
            completeObservedFence(inFlightFences[i]);
    }
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=transition-drain reason=%s waited=%u retirement_wait_ms=%.3f outstanding_after=%u",
        reason ? reason : "unknown",
        waited,
        static_cast<double>(waitNsTotal) / 1000000.0,
        countOutstandingFrameSubmissions(true));
}

CompletedHostPresent VulkanRendererContext::executeHostPresent(
        PendingHostPresent present) {
    VkPresentIdKHR presentIdInfo{};
    presentIdInfo.sType = VK_STRUCTURE_TYPE_PRESENT_ID_KHR;
    presentIdInfo.swapchainCount = 1;
    presentIdInfo.pPresentIds = &present.hostPresentId;

    VkPresentTimeGOOGLE googlePresentTime{};
    googlePresentTime.presentID = present.googlePresentId;
    googlePresentTime.desiredPresentTime =
        present.desiredDecision.submittedDesiredPresentTimeNs;
    VkPresentTimesInfoGOOGLE googlePresentTimes{};
    googlePresentTimes.sType = VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
    googlePresentTimes.swapchainCount = 1;
    googlePresentTimes.pTimes = &googlePresentTime;

    const void* presentNext = nullptr;
    if (present.backend == HostDisplayConfirmationBackend::PresentWait
            || (present.backend == HostDisplayConfirmationBackend::GoogleDisplayTiming
                && hostPresentWaitEnabled && vk_.WaitForPresentKHR)) {
        presentIdInfo.pNext = presentNext;
        presentNext = &presentIdInfo;
    }
    if (present.backend == HostDisplayConfirmationBackend::GoogleDisplayTiming) {
        googlePresentTimes.pNext = presentNext;
        presentNext = &googlePresentTimes;
    }

    VkSwapchainKHR scs[] = {present.swapchain};
    VkPresentInfoKHR pi{};
    pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.pNext = presentNext;
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &present.waitSemaphore;
    pi.swapchainCount = 1;
    pi.pSwapchains = scs;
    pi.pImageIndices = &present.imageIndex;

    const auto start = std::chrono::steady_clock::now();
    const uint64_t presentQueuePresentSerial =
        presentQueuePresentSerial_.fetch_add(1, std::memory_order_relaxed) + 1;
    uint64_t presentQueueBlockedNs = 0;
    VkResult result = VK_SUCCESS;
    if (hostSplitPresentQueueActive_) {
        const auto queueLockStart = std::chrono::steady_clock::now();
        std::unique_lock<std::mutex> queueLock(presentQueueMutex_);
        presentQueueBlockedNs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - queueLockStart).count());
        result = vk_.QueuePresentKHR(presentQueue, &pi);
    } else {
        const auto queueLockStart = std::chrono::steady_clock::now();
        std::unique_lock<std::mutex> queueLock(graphicsQueueMutex_);
        presentQueueBlockedNs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - queueLockStart).count());
        result = vk_.QueuePresentKHR(graphicsQueue, &pi);
    }
    const uint64_t presentNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - start).count());

    if (presentQueuePresentSerial <= 8 || presentQueuePresentSerial % 120 == 0
            || presentQueueBlockedNs >= 1000000ULL
            || presentNs >= 8000000ULL) {
        __android_log_print(
            ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
            "event=present-queue-op"
            " implementation=host-compositor"
            " graphics_queue_submit_serial=%" PRIu64
            " graphics_queue_submit_ms=%.3f"
            " present_queue_present_serial=%" PRIu64
            " present_queue_family=%u present_queue_index=%u"
            " present_queue_present_ms=%.3f present_queue_blocked_ms=%.3f"
            " present_queue_wait_semaphore_ms=-1.000"
            " present_queue_idle_ms=-1.000"
            " queue_idle_measurement=not-observable-without-added-sync"
            " wait_semaphore_measurement=not-observable-without-added-sync"
            " render_complete_semaphore=0x%" PRIx64
            " host_present_id=%" PRIu64
            " result=%d",
            present.submissionSerial,
            static_cast<double>(present.submitCallNs) / 1000000.0,
            presentQueuePresentSerial,
            graphicsQueueFamilyIndex,
            hostSplitPresentQueueActive_ ? hostPresentQueueIndex_ : 0U,
            static_cast<double>(presentNs) / 1000000.0,
            static_cast<double>(presentQueueBlockedNs) / 1000000.0,
            reinterpret_cast<uint64_t>(present.waitSemaphore),
            present.hostPresentId,
            result);
    }
    return CompletedHostPresent{
        .present = std::move(present),
        .result = result,
        .presentCallNs = presentNs,
    };
}

void VulkanRendererContext::finalizeHostPresent(
        CompletedHostPresent&& completed) {
    PendingHostPresent& present = completed.present;
    const VkResult result = completed.result;
    const uint64_t presentNs = completed.presentCallNs;

    frameQueueAcquireNsTotal_.fetch_add(present.acquireNs, std::memory_order_relaxed);
    frameQueuePresentNsTotal_.fetch_add(presentNs, std::memory_order_relaxed);
    const uint64_t sample =
        frameQueuePresentSamples_.fetch_add(1, std::memory_order_relaxed) + 1;
    uint32_t observedMax = frameQueueMaxGpuOutstanding_.load(std::memory_order_relaxed);
    while (observedMax < present.gpuOutstanding
            && !frameQueueMaxGpuOutstanding_.compare_exchange_weak(
                observedMax, present.gpuOutstanding, std::memory_order_relaxed)) {}

    if (result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR) {
        const uint64_t presented =
            frameQueuePresentedTotal_.fetch_add(1, std::memory_order_relaxed) + 1;
        recordHostPresent(present, presentNs);
        updateSmoothQueuePressure(presentNs);
        pollHostDisplayConfirmations();

        if (sample <= 8 || sample % 120 == 0 || presentNs >= 8000000ULL
                || present.hostPresentEnqueueWaitNs >= 1000000ULL) {
            const bool enabled =
                lsfgFrameQueueEnabled_.load(std::memory_order_acquire);
            const uint32_t requestedTarget = enabled
                ? std::min<uint32_t>(
                    2, lsfgFrameQueueTarget_.load(std::memory_order_acquire))
                : 0;
            const uint32_t effectiveTarget =
                enabled ? effectiveFrameQueueTarget() : 0;
            const bool smoothFallback =
                enabled && requestedTarget == 2 && effectiveTarget < 2;
            const char* mode = !enabled ? "off"
                : (requestedTarget == 0 ? "unbuffered"
                : (requestedTarget == 1 ? "balanced" : "smooth"));
            const char* fallbackReason = !smoothFallback ? "none"
                : (frameQueueSmoothFifoFallback_.load(std::memory_order_acquire)
                    ? "fifo-present-blocking"
                    : "present-stall");
            __android_log_print(
                ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
                "event=present telemetry_epoch=%llu enabled=%d requested_target=%u effective_target=%u "
                "mode=%s smooth_fallback=%d fallback_reason=%s requested_present_mode=%d "
                "active_present_mode=%d swapchain_generation=%llu active_slots=%u unique_content=%d "
                "present_queue_split=%d present_queue_index=%u host_present_worker=%d "
                "host_present_queue_depth=%u host_present_enqueue_wait_ms=%.3f "
                "gpu_outstanding=%u max_gpu_outstanding=%u frame_slot=%u submission_serial=%llu "
                "completed_submission_serial=%llu acquire_ms=%.3f submit_ms=%.3f "
                "present_ms=%.3f retirement_waits=%llu retirement_wait_ms=%.3f presented=%llu "
                "provenance_desired_time=%llu submitted_desired_time=%llu desired_stale_by_ms=%.3f "
                "desired_fallback_reason=%s refresh_period_ns=%llu phase_advance_cycles=%llu "
                "phase_advance_ms=%.3f temporal_backlog=%d",
                static_cast<unsigned long long>(
                    frameQueueTelemetryEpoch_.load(std::memory_order_relaxed)),
                enabled ? 1 : 0, requestedTarget, effectiveTarget, mode,
                smoothFallback ? 1 : 0, fallbackReason,
                static_cast<int>(requestedPresentMode),
                static_cast<int>(activePresentMode),
                static_cast<unsigned long long>(present.swapchainGeneration),
                present.hasUniqueLsfgDelivery
                    ? activeFrameSlotCount() : BASE_FRAMES_IN_FLIGHT,
                present.hasUniqueLsfgDelivery ? 1 : 0,
                hostSplitPresentQueueActive_ ? 1 : 0,
                hostPresentQueueIndex_,
                hostAsyncPresenterActive_ ? 1 : 0,
                present.hostPresentQueueDepth,
                static_cast<double>(present.hostPresentEnqueueWaitNs) / 1000000.0,
                present.gpuOutstanding,
                frameQueueMaxGpuOutstanding_.load(std::memory_order_relaxed),
                present.frameSlot,
                static_cast<unsigned long long>(present.submissionSerial),
                static_cast<unsigned long long>(
                    submissionTimeline.completedSubmissionSerial.load(
                        std::memory_order_acquire)),
                static_cast<double>(present.acquireNs) / 1000000.0,
                static_cast<double>(present.submitCallNs) / 1000000.0,
                static_cast<double>(presentNs) / 1000000.0,
                static_cast<unsigned long long>(
                    frameQueueRetirementWaitTotal_.load(std::memory_order_relaxed)),
                static_cast<double>(
                    frameQueueRetirementWaitNsTotal_.load(std::memory_order_relaxed))
                    / 1000000.0,
                static_cast<unsigned long long>(presented),
                static_cast<unsigned long long>(
                    present.desiredDecision.provenanceDesiredPresentTimeNs),
                static_cast<unsigned long long>(
                    present.desiredDecision.submittedDesiredPresentTimeNs),
                static_cast<double>(
                    present.desiredDecision.desiredStaleByNs) / 1000000.0,
                present.desiredDecision.fallbackReason
                    ? present.desiredDecision.fallbackReason : "none",
                static_cast<unsigned long long>(
                    present.desiredDecision.refreshPeriodNs),
                static_cast<unsigned long long>(
                    present.desiredDecision.phaseAdvanceCycles),
                static_cast<double>(
                    present.desiredDecision.phaseAdvanceNs) / 1000000.0,
                present.desiredDecision.temporalBacklog ? 1 : 0);
        }
    }
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_ERROR_SURFACE_LOST_KHR)
        fbResized.store(true, std::memory_order_release);
}

VkResult VulkanRendererContext::presentHostFrame(
        const PendingHostPresent& present) {
    auto completed = executeHostPresent(PendingHostPresent(present));
    const VkResult result = completed.result;
    finalizeHostPresent(std::move(completed));
    return result;
}

VkResult VulkanRendererContext::enqueueHostPresent(
        PendingHostPresent present) {
    if (!hostAsyncPresenterActive_)
        return presentHostFrame(present);

    const auto waitStart = std::chrono::steady_clock::now();
    std::unique_lock<std::mutex> lock(hostPresenterMutex_);
    hostPresenterSpaceCv_.wait(lock, [this] {
        return !hostPresenterRunning_.load(std::memory_order_acquire)
            || pendingHostPresents_.size() < MAX_HOST_PRESENT_QUEUE_DEPTH;
    });
    if (!hostPresenterRunning_.load(std::memory_order_acquire)) {
        lock.unlock();
        return presentHostFrame(present);
    }

    present.hostPresentEnqueueWaitNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - waitStart).count());
    present.hostPresentQueueDepth =
        static_cast<uint32_t>(pendingHostPresents_.size() + 1);
    hostPresentEnqueueWaitNsTotal_.fetch_add(
        present.hostPresentEnqueueWaitNs, std::memory_order_relaxed);
    hostPresentEnqueueWaitCount_.fetch_add(1, std::memory_order_relaxed);
    uint32_t observed = hostPresentQueueHighWater_.load(std::memory_order_relaxed);
    while (observed < present.hostPresentQueueDepth
            && !hostPresentQueueHighWater_.compare_exchange_weak(
                observed, present.hostPresentQueueDepth,
                std::memory_order_relaxed)) {}

    pendingHostPresents_.push_back(std::move(present));
    lock.unlock();
    hostPresenterCv_.notify_one();
    return VK_SUCCESS;
}

void VulkanRendererContext::hostPresenterLoop() {
    for (;;) {
        PendingHostPresent present{};
        {
            std::unique_lock<std::mutex> lock(hostPresenterMutex_);
            hostPresenterCv_.wait(lock, [this] {
                return !hostPresenterRunning_.load(std::memory_order_acquire)
                    || !pendingHostPresents_.empty();
            });
            if (!hostPresenterRunning_.load(std::memory_order_acquire)
                    && pendingHostPresents_.empty())
                break;
            present = std::move(pendingHostPresents_.front());
            pendingHostPresents_.pop_front();
            hostPresenterBusy_.store(true, std::memory_order_release);
        }
        hostPresenterSpaceCv_.notify_all();

        auto completed = executeHostPresent(std::move(present));

        {
            std::lock_guard<std::mutex> lock(hostPresenterMutex_);
            completedHostPresents_.push_back(std::move(completed));
            hostPresenterBusy_.store(false, std::memory_order_release);
            hostPresentCompletionPending_.store(true, std::memory_order_release);
        }
        hostPresenterDrainCv_.notify_all();
        dirtyCV.notify_one();
    }
    hostPresenterDrainCv_.notify_all();
}

void VulkanRendererContext::processHostPresentCompletions() {
    std::deque<CompletedHostPresent> completed;
    {
        std::lock_guard<std::mutex> lock(hostPresenterMutex_);
        completed.swap(completedHostPresents_);
        hostPresentCompletionPending_.store(
            !completedHostPresents_.empty(), std::memory_order_release);
    }
    for (auto& present : completed)
        finalizeHostPresent(std::move(present));
}

void VulkanRendererContext::drainHostPresenter(const char* reason) {
    if (!hostAsyncPresenterActive_ || !hostPresenterThread_.joinable())
        return;
    {
        std::unique_lock<std::mutex> lock(hostPresenterMutex_);
        hostPresenterDrainCv_.wait(lock, [this] {
            return pendingHostPresents_.empty()
                && !hostPresenterBusy_.load(std::memory_order_acquire);
        });
    }
    processHostPresentCompletions();
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=present-worker-drain reason=%s host_present_worker=1 "
        "host_present_queue_depth=0 queue_high_water=%u "
        "enqueue_wait_total_ms=%.3f enqueue_wait_count=%llu",
        reason ? reason : "unknown",
        hostPresentQueueHighWater_.load(std::memory_order_relaxed),
        static_cast<double>(
            hostPresentEnqueueWaitNsTotal_.load(std::memory_order_relaxed))
            / 1000000.0,
        static_cast<unsigned long long>(
            hostPresentEnqueueWaitCount_.load(std::memory_order_relaxed)));
}

void VulkanRendererContext::cleanupSwapchain() {
    drainHostPresenter("swapchain-cleanup");
    retireFrameQueuePresentSemaphores();
    flushHostDisplayConfirmationsUnknown("swapchain-recreate");
    if (swapchain != VK_NULL_HANDLE) {
        resetHostPhysicalCadenceTelemetry("swapchain-destroyed");
        lastAcceptedDesiredPresentTimeNs_ = 0;
    }
    for (auto fb:swapchainFBs) vk_.DestroyFramebuffer(device,fb,nullptr); swapchainFBs.clear();
    for (auto iv:swapchainViews) vk_.DestroyImageView(device,iv,nullptr); swapchainViews.clear();
    if (!cmdBufs.empty()){vk_.FreeCommandBuffers(device,cmdPool,(uint32_t)cmdBufs.size(),cmdBufs.data());cmdBufs.clear();}
    if (swapchain!=VK_NULL_HANDLE) { vk_.DestroySwapchainKHR(device,swapchain,nullptr); swapchain=VK_NULL_HANDLE; }
}

uint32_t VulkanRendererContext::findMemType(uint32_t filter, VkMemoryPropertyFlags props) {
    for (uint32_t i=0;i<memProperties.memoryTypeCount;i++)
        if ((filter&(1u<<i))&&(memProperties.memoryTypes[i].propertyFlags&props)==props) return i;
    throw std::runtime_error("memtype");
}

void VulkanRendererContext::createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
    VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem)
{
    VkBufferCreateInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO; bi.size=sz; bi.usage=usage; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateBuffer(device,&bi,nullptr,&buf)!=VK_SUCCESS) throw std::runtime_error("buffer");
    VkMemoryRequirements req; vk_.GetBufferMemoryRequirements(device,buf,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,props);
    if (vk_.AllocateMemory(device,&ai,nullptr,&mem)!=VK_SUCCESS) throw std::runtime_error("bufmem");
    vk_.BindBufferMemory(device,buf,mem,0);
}

VkCommandBuffer VulkanRendererContext::beginOneTime() {
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandPool=cmdPool; ai.commandBufferCount=1;
    VkCommandBuffer cb; vk_.AllocateCommandBuffers(device,&ai,&cb);
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk_.BeginCommandBuffer(cb,&bi); return cb;
}

void VulkanRendererContext::endOneTime(VkCommandBuffer cb) {
    vk_.EndCommandBuffer(cb);
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO; si.commandBufferCount=1; si.pCommandBuffers=&cb;
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; VkFence fence;
    vk_.CreateFence(device,&fi,nullptr,&fence);
    {
        std::lock_guard<std::mutex> queueLock(graphicsQueueMutex_);
        vk_.QueueSubmit(graphicsQueue,1,&si,fence);
    }
    vk_.WaitForFences(device,1,&fence,VK_TRUE,UINT64_MAX);
    vk_.DestroyFence(device,fence,nullptr); vk_.FreeCommandBuffers(device,cmdPool,1,&cb);
}

void VulkanRendererContext::transition(VkCommandBuffer cb, VkImage img,
    VkImageLayout ol, VkImageLayout nl, VkAccessFlags sa, VkAccessFlags da,
    VkPipelineStageFlags ss, VkPipelineStageFlags ds)
{
    VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout=ol; b.newLayout=nl; b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    b.image=img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1}; b.srcAccessMask=sa; b.dstAccessMask=da;
    vk_.CmdPipelineBarrier(cb,ss,ds,0,0,nullptr,0,nullptr,1,&b);
}

bool VulkanRendererContext::createWinTexResources(WinTex& wt, int w, int h) {

    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS) return false;
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,wt.img,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device,&ai,nullptr,&wt.mem)!=VK_SUCCESS){vk_.DestroyImage(device,wt.img,nullptr);wt.img=VK_NULL_HANDLE;return false;}
    vk_.BindImageMemory(device,wt.img,wt.mem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vi.components={swapRB?VK_COMPONENT_SWIZZLE_B:VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,swapRB?VK_COMPONENT_SWIZZLE_R:VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){destroyWinTex(wt);return false;}
    VkDescriptorSetAllocateInfo dsai{}; dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO; dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    if (vk_.AllocateDescriptorSets(device,&dsai,&wt.ds)!=VK_SUCCESS){destroyWinTex(wt);return false;}
    wt.descriptorPool=winTexPool;
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=wt.view; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=wt.ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    VkDeviceSize stgSz=(VkDeviceSize)w*h*4;
    createBuffer(stgSz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,wt.stg,wt.stgMem);
    vk_.MapMemory(device,wt.stgMem,0,stgSz,0,&wt.mapped);
    wt.cap=stgSz; wt.w=w; wt.h=h; wt.needsTransition=true;
    return true;
}

bool VulkanRendererContext::importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb) {
    if (!vk_.GetAndroidHardwareBufferPropertiesANDROID)
        return false;

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext=&fmtP;
    if (vk_.GetAndroidHardwareBufferPropertiesANDROID(device,ahb,&props)!=VK_SUCCESS)
        return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb,&desc);

    VkExternalFormatANDROID ef{};
    ef.sType=VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    ef.externalFormat=swapRB ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM;

    VkExternalMemoryImageCreateInfo emi{};
    emi.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    ef.pNext=const_cast<void*>(emi.pNext);
    emi.pNext=&ef;

    VkImageCreateInfo ii{};
    ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext=&emi; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.format=swapRB ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM;
    ii.extent={desc.width,desc.height,1};
    ii.mipLevels=1; ii.arrayLayers=1; ii.samples=VK_SAMPLE_COUNT_1_BIT;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.usage=VK_IMAGE_USAGE_SAMPLED_BIT;
    ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS)
        return false;

    VkImportAndroidHardwareBufferInfoANDROID imp{};
    imp.sType=VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    imp.buffer=ahb;

    VkMemoryDedicatedAllocateInfo ded{};
    ded.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    ded.pNext=&imp; ded.image=wt.img;

    VkMemoryAllocateInfo mai{};
    mai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.pNext=&ded; mai.allocationSize=props.allocationSize;
    mai.memoryTypeIndex=findMemType(props.memoryTypeBits,0);
    if (vk_.AllocateMemory(device,&mai,nullptr,&wt.mem)!=VK_SUCCESS){
        vk_.DestroyImage(device,wt.img,nullptr);
        wt.img=VK_NULL_HANDLE;
        return false;
    }
    vk_.BindImageMemory(device,wt.img,wt.mem,0);

    VkExternalFormatANDROID vef{};
    vef.sType=VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    vef.externalFormat=swapRB ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM;

    VkImageViewCreateInfo vi{};
    vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.pNext=&vef; vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D;
    vi.format=swapRB ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_B8G8R8A8_UNORM;
    vi.components={VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,
                   VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY};
    vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS){
        destroyWinTex(wt);
        return false;
    }

    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool=ahbTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
    VkResult dsRes=vk_.AllocateDescriptorSets(device,&dsai,&wt.ds);
    if (dsRes==VK_ERROR_OUT_OF_POOL_MEMORY){
        RLOG_E("importAHBToWinTex: descriptor pool exhausted for AHB texture");
        destroyWinTex(wt);
        return false;
    }
    if (dsRes!=VK_SUCCESS){ destroyWinTex(wt); return false; }
    wt.descriptorPool=ahbTexPool;

    VkDescriptorImageInfo dii{};
    dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    dii.imageView=wt.view; dii.sampler=sampler;

    VkWriteDescriptorSet wr{};
    wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    wr.dstSet=wt.ds; wr.dstBinding=0;
    wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);

    wt.needsTransition=true;
    wt.isAHB=true;
    wt.w=(int)desc.width;
    wt.h=(int)desc.height;
    return true;
}

void VulkanRendererContext::destroyWinTex(WinTex& wt) {
    if (wt.isAHB) {
        wt = {};
        return;
    }
    if (wt.img!=VK_NULL_HANDLE || wt.stg!=VK_NULL_HANDLE || wt.ds!=VK_NULL_HANDLE) {
        RetiredWindowTexture retired{};
        retired.texture = wt;
        retired.lastUseSubmissionSerial = wt.lastUseSubmissionSerial;
        retiredWindowTextures.push_back(retired);
    }
    wt={};
}

void VulkanRendererContext::ensureCursorTex(short w, short h) {
    if (cursorImg!=VK_NULL_HANDLE && cursorTexW==w && cursorTexH==h) return;
    cleanupCursorTex();
    VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
    ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    vk_.CreateImage(device,&ii,nullptr,&cursorImg);
    VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,cursorImg,&req);
    VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    vk_.AllocateMemory(device,&ai,nullptr,&cursorMem); vk_.BindImageMemory(device,cursorImg,cursorMem,0);
    VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=cursorImg; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    vk_.CreateImageView(device,&vi,nullptr,&cursorView);
    VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=cursorView; dii.sampler=sampler;
    VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=cursorDS; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
    vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);

    cursorTexW=w; cursorTexH=h;
}

void VulkanRendererContext::cleanupCursorTex() {
    if (cursorView!=VK_NULL_HANDLE){vk_.DestroyImageView(device,cursorView,nullptr);cursorView=VK_NULL_HANDLE;}
    if (cursorImg!=VK_NULL_HANDLE){vk_.DestroyImage(device,cursorImg,nullptr);cursorImg=VK_NULL_HANDLE;}
    if (cursorMem!=VK_NULL_HANDLE){vk_.FreeMemory(device,cursorMem,nullptr);cursorMem=VK_NULL_HANDLE;}
    if (cursorStg!=VK_NULL_HANDLE){vk_.DestroyBuffer(device,cursorStg,nullptr);vk_.FreeMemory(device,cursorStgM,nullptr);cursorStg=VK_NULL_HANDLE;cursorStgP=nullptr;cursorStgC=0;}
    cursorTexW=0; cursorTexH=0;
}

void VulkanRendererContext::ensureCursorStaging(VkDeviceSize sz) {
    if (cursorStgC>=sz) return;
    if (cursorStg!=VK_NULL_HANDLE){vk_.DestroyBuffer(device,cursorStg,nullptr);vk_.FreeMemory(device,cursorStgM,nullptr);}
    createBuffer(sz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,cursorStg,cursorStgM);
    vk_.MapMemory(device,cursorStgM,0,sz,0,&cursorStgP); cursorStgC=sz;
}

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& preUpload,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, bool curVis, bool keepOpen)
{
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb,&bi)!=VK_SUCCESS) throw std::runtime_error("begin cb");







    ahbTransitions.clear(); preUpload.clear(); postUpload.clear();

    for (auto& d : draws) {
        if (d.img==VK_NULL_HANDLE) continue;
        if (d.isAHB && d.needsTransition) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            ahbTransitions.push_back(b);
        } else if (!d.isAHB && (d.needsTransition || d.upload!=VK_NULL_HANDLE)) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            preUpload.push_back(b);
            b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            postUpload.push_back(b);
        }
    }

    if (!ahbTransitions.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    if (!preUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)preUpload.size(), preUpload.data());


    for (auto& d : draws) {
        if (d.isAHB || d.upload==VK_NULL_HANDLE || d.img==VK_NULL_HANDLE) continue;
        VkBufferImageCopy r{}; r.bufferOffset=0; r.bufferRowLength=0; r.bufferImageHeight=0;
        r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)d.w,(uint32_t)d.h,1};
        vk_.CmdCopyBufferToImage(cb, d.upload, d.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    }

    bool cursorDrawn = curVis && cursorImg!=VK_NULL_HANDLE && cursorDS!=VK_NULL_HANDLE;
    bool hasCursorCopy = hasCursorUpload && cursorImg!=VK_NULL_HANDLE && cursorUpload!=VK_NULL_HANDLE;
    if (hasCursorCopy) {
        VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        b.image=cursorImg; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.srcAccessMask=VK_ACCESS_SHADER_READ_BIT; b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &b);
        VkBufferImageCopy r{}; r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)curW,(uint32_t)curH,1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
        b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)postUpload.size(), postUpload.data());


    bool toXr = xrTargetActive.load() && xrFb!=VK_NULL_HANDLE;
    VkExtent2D tgtExt = toXr ? xrExt : swapchainExt;
    VkRenderPassBeginInfo rpi{}; rpi.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = toXr ? xrRp : renderPass;
    rpi.framebuffer = toXr ? xrFb : swapchainFBs[imgIdx];
    rpi.renderArea={{0,0},tgtExt};
    VkClearValue clr={{{0.f,0.f,0.f,1.f}}}; rpi.clearValueCount=1; rpi.pClearValues=&clr;

    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    // The immersive quad flips vertically to suit the per-window game buffers, so the scene
    // target must match their orientation: render it upside down via a negative viewport.
    VkViewport vp = toXr
        ? VkViewport{0,(float)tgtExt.height,(float)tgtExt.width,-(float)tgtExt.height,0,1}
        : VkViewport{0,0,(float)tgtExt.width,(float)tgtExt.height,0,1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);
    VkRect2D sc{{0,0},tgtExt}; vk_.CmdSetScissor(cb, 0, 1, &sc);

    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    for (auto& d : draws) {
        if (d.ds==VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &d.ds, 0, nullptr);
        WindowPushConstants pc{};
        pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f;
        pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
        pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f;
        pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
        pc.useTexAlpha = 0;
        pc.effectId = activeEffectId;
        pc.sharpness = activeSharpness;
        pc.resW = (float)std::max(1, d.w);
        pc.resH = (float)std::max(1, d.h);
        pc.effectMask = activeEffectMask;
        pc.brightness = activeBrightness;
        pc.contrast = activeContrast;
        pc.gamma = activeGamma;
        pc.outW = std::max(1.0f, (float)d.w * sx / cw * (float)tgtExt.width);
        pc.outH = std::max(1.0f, (float)d.h * sy / ch * (float)tgtExt.height);
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    if (cursorDrawn) {

        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &cursorDS, 0, nullptr);
        float cx=(float)std::max(0,(int)ptrX-curHotX), cy=(float)std::max(0,(int)ptrY-curHotY);
        WindowPushConstants cpc{};
        cpc.ndcX0=(ox+cx*sx)/cw*2.f-1.f; cpc.ndcY0=(oy+cy*sy)/ch*2.f-1.f;
        cpc.ndcX1=(ox+(cx+curW)*sx)/cw*2.f-1.f; cpc.ndcY1=(oy+(cy+curH)*sy)/ch*2.f-1.f;
        cpc.useTexAlpha = 1;
        cpc.effectId = 0;
        cpc.sharpness = 0.f;
        cpc.resW = (float)std::max(1, (int)curW);
        cpc.resH = (float)std::max(1, (int)curH);
        cpc.effectMask = 0;
        cpc.brightness = 0.0f;
        cpc.contrast = 0.0f;
        cpc.gamma = 1.0f;
        cpc.outW = (float)std::max(1, (int)curW);
        cpc.outH = (float)std::max(1, (int)curH);
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(cpc), &cpc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }
    vk_.CmdEndRenderPass(cb);

    if (keepOpen)
        return;

    VkResult endStatus = vk_.EndCommandBuffer(cb);
    if (endStatus!=VK_SUCCESS) {
        RLOG_E("recordCmdBuf: EndCommandBuffer failed with status=%d (swapRB=%d draws=%zu imgIdx=%u)",
            (int)endStatus, (int)swapRB, draws.size(), imgIdx);
        throw std::runtime_error("end cb");
    }
}


bool VulkanRendererContext::createXrTargetResources(uint32_t w, uint32_t h) {
    AHardwareBuffer_Desc d{};
    d.width=w; d.height=h; d.layers=1;
    d.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage=AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    if (AHardwareBuffer_allocate(&d,&xrAhb)!=0||!xrAhb) { RLOG_E("xrTarget: AHB alloc %ux%u failed",w,h); return false; }

    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    if (!vk_.GetAndroidHardwareBufferPropertiesANDROID ||
        vk_.GetAndroidHardwareBufferPropertiesANDROID(device,xrAhb,&props)!=VK_SUCCESS) {
        RLOG_E("xrTarget: AHB props failed"); destroyXrTargetResources(); return false;
    }

    VkExternalMemoryImageCreateInfo emi{};
    emi.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    emi.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ii{};
    ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.pNext=&emi;
    ii.imageType=VK_IMAGE_TYPE_2D; ii.format=VK_FORMAT_R8G8B8A8_UNORM;
    ii.extent={w,h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.samples=VK_SAMPLE_COUNT_1_BIT;
    ii.tiling=VK_IMAGE_TILING_OPTIMAL;
    ii.usage=VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
    ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device,&ii,nullptr,&xrImg)!=VK_SUCCESS) { RLOG_E("xrTarget: image"); destroyXrTargetResources(); return false; }

    VkImportAndroidHardwareBufferInfoANDROID imp{};
    imp.sType=VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID; imp.buffer=xrAhb;
    VkMemoryDedicatedAllocateInfo ded{};
    ded.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO; ded.pNext=&imp; ded.image=xrImg;
    uint32_t idx=0; while (idx<32 && !(props.memoryTypeBits&(1u<<idx))) idx++;
    if (idx>=32) { RLOG_E("xrTarget: no compatible memory type (bits=0x%x)", props.memoryTypeBits); destroyXrTargetResources(); return false; }
    VkMemoryAllocateInfo mai{};
    mai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; mai.pNext=&ded;
    mai.allocationSize=props.allocationSize; mai.memoryTypeIndex=idx;
    if (vk_.AllocateMemory(device,&mai,nullptr,&xrMem)!=VK_SUCCESS ||
        vk_.BindImageMemory(device,xrImg,xrMem,0)!=VK_SUCCESS) {
        RLOG_E("xrTarget: memory"); destroyXrTargetResources(); return false;
    }

    VkImageViewCreateInfo vi{};
    vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image=xrImg; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_R8G8B8A8_UNORM;
    vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
    if (vk_.CreateImageView(device,&vi,nullptr,&xrView)!=VK_SUCCESS) { RLOG_E("xrTarget: view"); destroyXrTargetResources(); return false; }

    VkAttachmentDescription att{};
    att.format=VK_FORMAT_R8G8B8A8_UNORM; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;
    VkSubpassDependency dep{}; dep.srcSubpass=VK_SUBPASS_EXTERNAL; dep.dstSubpass=0;
    dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dep.srcAccessMask=0;
    dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rci{}; rci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rci.attachmentCount=1; rci.pAttachments=&att; rci.subpassCount=1; rci.pSubpasses=&sub;
    rci.dependencyCount=1; rci.pDependencies=&dep;
    if (vk_.CreateRenderPass(device,&rci,nullptr,&xrRp)!=VK_SUCCESS) { RLOG_E("xrTarget: renderpass"); destroyXrTargetResources(); return false; }

    VkFramebufferCreateInfo fi{};
    fi.sType=VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fi.renderPass=xrRp; fi.attachmentCount=1; fi.pAttachments=&xrView;
    fi.width=w; fi.height=h; fi.layers=1;
    if (vk_.CreateFramebuffer(device,&fi,nullptr,&xrFb)!=VK_SUCCESS) { RLOG_E("xrTarget: framebuffer"); destroyXrTargetResources(); return false; }

    xrExt={w,h};
    RLOG("xrTarget: created %ux%u ahb=%p", w, h, (void*)xrAhb);
    return true;
}

void VulkanRendererContext::destroyXrTargetResources() {
    if (xrFb  !=VK_NULL_HANDLE){vk_.DestroyFramebuffer(device,xrFb,nullptr); xrFb=VK_NULL_HANDLE;}
    if (xrRp  !=VK_NULL_HANDLE){vk_.DestroyRenderPass(device,xrRp,nullptr);  xrRp=VK_NULL_HANDLE;}
    if (xrView!=VK_NULL_HANDLE){vk_.DestroyImageView(device,xrView,nullptr); xrView=VK_NULL_HANDLE;}
    if (xrImg !=VK_NULL_HANDLE){vk_.DestroyImage(device,xrImg,nullptr);      xrImg=VK_NULL_HANDLE;}
    if (xrMem !=VK_NULL_HANDLE){vk_.FreeMemory(device,xrMem,nullptr);        xrMem=VK_NULL_HANDLE;}
    if (xrAhb){AHardwareBuffer_release(xrAhb); xrAhb=nullptr;}
    xrExt={0,0};
}

int64_t VulkanRendererContext::enableXrTarget() {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    if (device==VK_NULL_HANDLE) return 0;
    // A surface resize may still be queued for the render loop; process it here so the
    // target is sized from the current swapchain extent, not the stale one.
    if (fbResized.load()) {
        for (auto& f:inFlightFences) vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX);
        cleanupSwapchain();
        try {
            createSwapchain(); createFramebuffers(); createCmdBufs();
            imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
            fbResized.store(false);
        } catch(...) { return 0; }
    }
    uint32_t w=swapchainExt.width, h=swapchainExt.height;
    if (w==0||h==0){ w=(uint32_t)surfaceWidth; h=(uint32_t)surfaceHeight; }
    if (w==0||h==0) return 0;
    // The immersive quad swapchain follows the container screen size (min width 1280);
    // rendering the scene any larger only burns GPU on pixels the quad downsamples away.
    // Keep the surface aspect.
    float qw = (float)std::max(containerWidth, 1280);
    float qh = (float)containerHeight * qw / (float)std::max(containerWidth, 1);
    float fit = std::min({qw/(float)w, qh/(float)h, 1.f});
    w = std::max(16u, (uint32_t)((float)w*fit) & ~1u);
    h = std::max(16u, (uint32_t)((float)h*fit) & ~1u);
    if (xrTargetActive.load() && xrAhb!=nullptr && xrExt.width==w && xrExt.height==h)
        return (int64_t)(intptr_t)xrAhb;
    vk_.DeviceWaitIdle(device);
    if (xrAhb!=nullptr) { xrTargetActive.store(false); destroyXrTargetResources(); }
    if (!createXrTargetResources(w,h)) return 0;
    xrTargetActive.store(true);
    needsRender.store(true); dirtyCV.notify_one();
    return (int64_t)(intptr_t)xrAhb;
}

int64_t VulkanRendererContext::xrTargetExtentPacked() {
    std::lock_guard<std::mutex> lk(renderMutex);
    return ((int64_t)xrExt.width<<32) | (int64_t)xrExt.height;
}

void VulkanRendererContext::disableXrTarget() {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    if (!xrTargetActive.load() && xrAhb==nullptr) return;
    if (device!=VK_NULL_HANDLE) vk_.DeviceWaitIdle(device);
    xrTargetActive.store(false);
    destroyXrTargetResources();
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::renderLoop() {
    while (isRunning) {
        { std::unique_lock<std::mutex> lk(dirtyMutex);
          dirtyCV.wait(lk,[this]{
              return !isRunning
                  || hostPresentCompletionPending_.load(std::memory_order_acquire)
                  || (!surfaceDetached.load()
                      && (needsRender.load() || fbResized.load()))
                  || cursorMoved.load(); }); }
        if (!isRunning) break;

        if (hostPresentCompletionPending_.load(std::memory_order_acquire))
            processHostPresentCompletions();

        if (surfaceDetached.load(std::memory_order_acquire))
            continue;
        if (!needsRender.load(std::memory_order_acquire)
                && !fbResized.load(std::memory_order_acquire)
                && !cursorMoved.load(std::memory_order_acquire))
            continue;
        if (swapchain == VK_NULL_HANDLE || cmdBufs.empty()) continue;
        try { renderFrame(); } catch(...) {}
    }
}

void VulkanRendererContext::flushDeleteQueue() {
    std::lock_guard<std::mutex> lk(renderMutex);
    reclaimRetiredAhbImports();
    reclaimRetiredWindowTextures();
}

void VulkanRendererContext::renderFrame() {
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);

    processHostPresentCompletions();
    drainLsfgProvenance();
    needsRender.store(false,std::memory_order_relaxed);
    cursorMoved.store(false,std::memory_order_relaxed);

    if (surfaceDetached.load(std::memory_order_acquire)) return;
    if (scanoutActive.load()) {
        applyScanoutBuffer();

        if (!scanoutBlackFrameDone.load()) {
            scanoutBlackFrameDone.store(true);

            std::lock_guard<std::mutex> lk(renderMutex);
            renderList.clear();
        } else {
            return;
        }
    } else {
        scanoutBlackFrameDone.store(false);
    }
    if (surfaceWidth==0||surfaceHeight==0) return;

    if (fbResized.load()) {
        for (auto& f:inFlightFences) {
            if (vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX) == VK_SUCCESS)
                completeObservedFence(f);
        }
        cleanupSwapchain();
        bool ok=false;
        try{createSwapchain();createFramebuffers();createCmdBufs();createFrameQueuePresentSemaphores();imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
ok=true;}catch(...){}
        if (ok) fbResized.store(false);
        return;
    }

    // Frame Queue depth belongs to unique LSFG content only. The raw imported
    // AHB is never retained as queued storage: after QueueSubmit the acquired
    // host swapchain image is the immutable composite snapshot, and its render
    // completion semaphore owns the handoff to WSI.
    bool uniqueLsfgContentPending =
        pendingLsfgHostDeliveryCount_.load(std::memory_order_acquire) > 0;
    if (!uniqueLsfgContentPending
            && lsfgFrameQueueEnabled_.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(renderMutex);
        for (const auto& entry : renderList) {
            const auto it = texMap.find(entry.id);
            if (it == texMap.end())
                continue;
            const auto& provenance = it->second.frameProvenance;
            if (provenance.valid && provenance.deliveryId != 0
                    && consumedLsfgDeliveries_.find(provenance.deliveryId)
                        == consumedLsfgDeliveries_.end()) {
                uniqueLsfgContentPending = true;
                break;
            }
        }
    }
    const uint32_t activeSlots = uniqueLsfgContentPending
        ? activeFrameSlotCount()
        : BASE_FRAMES_IN_FLIGHT;
    if (currentFrame >= activeSlots)
        currentFrame = 0;
    if (currentFrame >= cmdBufs.size() || cmdBufs[currentFrame] == VK_NULL_HANDLE) return;
    bool toXr = xrTargetActive.load() && xrFb!=VK_NULL_HANDLE;
    bool currentFenceWaited = false;
    bool currentFenceComplete = false;
    if (toXr) {
        currentFenceComplete = true;
        for (auto& f:inFlightFences) {
            if (vk_.WaitForFences(device,1,&f,VK_TRUE,UINT64_MAX) == VK_SUCCESS)
                completeObservedFence(f);
            else
                currentFenceComplete = false;
        }
        currentFenceWaited = true;
    } else {
        VkResult fenceStatus = vk_.GetFenceStatus
            ? vk_.GetFenceStatus(device, inFlightFences[currentFrame])
            : VK_NOT_READY;
        if (fenceStatus == VK_SUCCESS) {
            submissionTimeline.completeFrame(currentFrame);
            currentFenceWaited = true;
            currentFenceComplete = true;
        } else if (vk_.WaitForFences(
                device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX) == VK_SUCCESS) {
            submissionTimeline.completeFrame(currentFrame);
            currentFenceWaited = true;
            currentFenceComplete = true;
        }
    }
    if (!currentFenceComplete) return;

    const bool frameQueueEnabled =
        lsfgFrameQueueEnabled_.load(std::memory_order_acquire);
    const uint32_t frameQueueTarget =
        frameQueueEnabled && uniqueLsfgContentPending
            ? effectiveFrameQueueTarget()
            : 0;
    if (!toXr) {
        enforceFrameQueueSubmissionBudget(frameQueueTarget);
    }

    uint32_t imgIdx = 0;
    uint32_t nativeGeneratedImgIdx = 0;
    VkSemaphore nativeGeneratedAcquireSemaphore = VK_NULL_HANDLE;
    uint64_t acquireNs = 0;
    VkResult res = VK_SUCCESS;

    // Native LSFG is deliberately a narrow compositor path: 2x only for the
    // first bring-up slice, with the source still rendered by the existing
    // renderer and generated output copied into a second acquired WSI image.
    bool nativeRuntimeActive =
        !toXr &&
        framegenArmed &&
        framegenRequested &&
        lsfg != nullptr &&
        framegenSupported &&
        framegenMultiplier == 2 &&
        swapchainImages.size() >= 2;

    uint32_t nativeGenerations = 0;
    uint64_t nativeSourceFrame = framegenSourceFrames.load(std::memory_order_relaxed) + 1;
    if (nativeRuntimeActive) {
        if (!ensureNativeExtraAcquireSemaphores()) {
            nativeRuntimeActive = false;
            RLOG_E("Native LSFG disabled for frame: generated-image acquire sync unavailable");
        }
    }
    if (nativeRuntimeActive) {
        vkr_lsfg_set_guest_extent(lsfg, containerWidth, containerHeight);
        vkr_lsfg_set_refresh_rate(lsfg, framegenRefreshRate);
        if (!createCompositeTargets(swapchainExt.width, swapchainExt.height, 1) ||
            !vkr_lsfg_prepare(lsfg, swapchainExt.width, swapchainExt.height, swapchainFmt)) {
            nativeRuntimeActive = false;
            RLOG_E("Native LSFG disabled for frame: target/chain preparation failed");
        } else {
            nativeGenerations = vkr_lsfg_plan(lsfg, 1, nativeSourceFrame);
        }
    }

    if (!toXr) {
        const auto acquireStart = std::chrono::steady_clock::now();
        res=vk_.AcquireNextImageKHR(device,swapchain,UINT64_MAX,imgAvailSems[currentFrame],VK_NULL_HANDLE,&imgIdx);
        acquireNs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - acquireStart).count());
        if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR){fbResized.store(true);return;}
        if (res!=VK_SUCCESS&&res!=VK_SUBOPTIMAL_KHR) return;
        if (imgIdx >= swapchainFBs.size() || imgIdx >= swapchainImages.size()) {
            RLOG_E("renderFrame: invalid acquired image index=%u (fb=%zu images=%zu)",
                imgIdx, swapchainFBs.size(), swapchainImages.size());
            return;
        }

        if (imgInFlight.size()!=swapchainImages.size()) imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
        if (imgInFlight[imgIdx]!=VK_NULL_HANDLE &&
            (!currentFenceWaited || imgInFlight[imgIdx] != inFlightFences[currentFrame])) {
            VkResult imageFenceStatus = vk_.GetFenceStatus
                ? vk_.GetFenceStatus(device, imgInFlight[imgIdx])
                : VK_NOT_READY;
            if (imageFenceStatus == VK_SUCCESS) {
                completeObservedFence(imgInFlight[imgIdx]);
            } else if (vk_.WaitForFences(
                    device,1,&imgInFlight[imgIdx],VK_TRUE,UINT64_MAX) == VK_SUCCESS) {
                completeObservedFence(imgInFlight[imgIdx]);
            } else {
                return;
            }
        }
        imgInFlight[imgIdx]=inFlightFences[currentFrame];

        if (nativeRuntimeActive && nativeGenerations > 0) {
            nativeGeneratedAcquireSemaphore = nativeExtraAcquireSems_[currentFrame][0];
            res = vk_.AcquireNextImageKHR(
                device, swapchain, UINT64_MAX, nativeGeneratedAcquireSemaphore,
                VK_NULL_HANDLE, &nativeGeneratedImgIdx);
            if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_ERROR_SURFACE_LOST_KHR) {
                fbResized.store(true);
                return;
            }
            if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
                return;
            }
            if (nativeGeneratedImgIdx >= swapchainImages.size() ||
                nativeGeneratedImgIdx == imgIdx) {
                RLOG_E("Native LSFG acquired invalid generated image index=%u source=%u images=%zu",
                    nativeGeneratedImgIdx, imgIdx, swapchainImages.size());
                return;
            }
            if (imgInFlight[nativeGeneratedImgIdx] != VK_NULL_HANDLE &&
                imgInFlight[nativeGeneratedImgIdx] != inFlightFences[currentFrame]) {
                VkResult imageFenceStatus = vk_.GetFenceStatus
                    ? vk_.GetFenceStatus(device, imgInFlight[nativeGeneratedImgIdx])
                    : VK_NOT_READY;
                if (imageFenceStatus == VK_SUCCESS) {
                    completeObservedFence(imgInFlight[nativeGeneratedImgIdx]);
                } else if (vk_.WaitForFences(
                        device,1,&imgInFlight[nativeGeneratedImgIdx],VK_TRUE,UINT64_MAX) != VK_SUCCESS) {
                    return;
                } else {
                    completeObservedFence(imgInFlight[nativeGeneratedImgIdx]);
                }
            }
            imgInFlight[nativeGeneratedImgIdx] = inFlightFences[currentFrame];
        }
    }

    vk_.ResetCommandBuffer(cmdBufs[currentFrame],0);

    float ox,oy,sx,sy,cw,ch;
    short ptrX,ptrY,curHotX,curHotY,curW,curH; bool curVis;
    VkBuffer curUpload=VK_NULL_HANDLE; bool hasCurUpload=false;

    {
        std::lock_guard<std::mutex> lk(renderMutex);

        reclaimRetiredAhbImports();
        reclaimRetiredWindowTextures();

        ox=sceneOffsetX; oy=sceneOffsetY; sx=sceneScaleX; sy=sceneScaleY;
        cw=(float)containerWidth; ch=(float)containerHeight;
        ptrX=(short)pointerX.load(); ptrY=(short)pointerY.load();
        curHotX=cursorHotX; curHotY=cursorHotY; curW=cursorTexW; curH=cursorTexH;
        curVis=cursorVisible.load();

        frameDraws.clear();
        for (auto& re:renderList) {
            DrawEntry de{};
            const bool queuedLsfg =
                selectQueuedLsfgHostDelivery(re, de);
            if (!queuedLsfg) {
                auto it=texMap.find(re.id);
                if (it==texMap.end()) continue;
                WinTex& wt=it->second;
                if (wt.ds==VK_NULL_HANDLE) continue;
                de.ownerId=re.id;
                de.img=wt.img;
                de.ds=wt.ds;
                de.x=re.x; de.y=re.y; de.w=wt.w; de.h=wt.h;
                de.isAHB=wt.isAHB;
                de.ahb=wt.ahb;
                de.frameProvenance=wt.frameProvenance;
                if (wt.needsTransition) {
                    de.needsTransition=true;
                    wt.needsTransition=false;
                }
                if (wt.dirty && !wt.isAHB && wt.stg!=VK_NULL_HANDLE) {
                    de.upload=wt.stg;
                    wt.dirty=false;
                } else if (wt.isAHB) {
                    wt.dirty=false;
                }
            }
            if (de.ds == VK_NULL_HANDLE)
                continue;
            recordHostSnapshotCreated(de.frameProvenance);
            frameDraws.push_back(de);
        }

        if (isCursorImageDirty.load() && cursorImg!=VK_NULL_HANDLE && !cursorPixels.empty()) {
            VkDeviceSize csz=(VkDeviceSize)cursorTexW*cursorTexH*4;
            ensureCursorStaging(csz);
            isCursorImageDirty.store(false); hasCurUpload=true; curUpload=cursorStg;
            cursorUploadSize = csz;
        }
    }

    if (hasCurUpload && cursorStgP && !cursorPixels.empty())
        memcpy(cursorStgP, cursorPixels.data(), cursorUploadSize);

    bool effectiveCurVis = curVis && !scanoutActive.load();
    recordCmdBuf(cmdBufs[currentFrame],imgIdx,frameDraws,
        frameAhbTransitions,framePreUpload,framePostUpload,
        curUpload,hasCurUpload,
        ox,oy,sx,sy,cw,ch,ptrX,ptrY,curHotX,curHotY,curW,curH,effectiveCurVis,
        nativeRuntimeActive);

    if (nativeRuntimeActive) {
        VkCommandBuffer cb = cmdBufs[currentFrame];

        // The existing render pass ends the source in PRESENT_SRC. Move it into
        // the LSFG chain's expected GENERAL layout, feed history, and optionally
        // generate one interpolated frame into the offscreen target.
        transition(cb, swapchainImages[imgIdx],
            VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_GENERAL,
            0, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        vkr_lsfg_process(
            lsfg, cb, swapchainImages[imgIdx],
            swapchainExt.width, swapchainExt.height, nativeGenerations);

        if (nativeGenerations > 0) {
            vkr_lsfg_generate_into(
                lsfg, cb, 0, 0, composite[0].image, composite[0].view,
                swapchainExt.width, swapchainExt.height);
            blitCompositeToSwapchain(
                cb, composite[0], swapchainImages[nativeGeneratedImgIdx]);
            framegenMadeFrames += nativeGenerations;
        }

        transition(cb, swapchainImages[imgIdx],
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT, 0,
            VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

        framegenSourceFrames.store(nativeSourceFrame, std::memory_order_release);
        framegenRealFrames++;
        const VkResult endStatus = vk_.EndCommandBuffer(cb);
        if (endStatus != VK_SUCCESS) {
            RLOG_E("Native LSFG command buffer end failed: status=%d", (int)endStatus);
            return;
        }
    }

    std::vector<LsfgFrameProvenance> frameProvenance =
        classifyHostPresentProvenance(frameDraws);
    const bool hasUniqueLsfgDelivery = std::any_of(
        frameProvenance.begin(), frameProvenance.end(),
        [](const LsfgFrameProvenance& provenance) {
            return provenance.uniqueDelivery;
        });
    const HostDesiredPresentDecision desiredDecision =
        validatedHostDesiredPresentTime(frameProvenance);

    std::array<VkSemaphore, 2> wSem{};
    std::array<VkSemaphore, 2> sSem{};
    uint32_t waitSemaphoreCount = 0;
    uint32_t signalSemaphoreCount = 0;
    VkSemaphore signalSemaphore = renderDoneSems[currentFrame];

    const bool useFrameQueuePresentSemaphore =
        !toXr && (
            nativeRuntimeActive ||
            hostAsyncPresenterActive_
            || (frameQueueEnabled && hasUniqueLsfgDelivery
                && activeFrameSlotCount() > BASE_FRAMES_IN_FLIGHT));
    if (useFrameQueuePresentSemaphore) {
        if (imgIdx >= frameQueuePresentSems_.size()
                || frameQueuePresentSems_[imgIdx] == VK_NULL_HANDLE) {
            fbResized.store(true, std::memory_order_release);
            return;
        }
        signalSemaphore = frameQueuePresentSems_[imgIdx];
    }
    if (!toXr) {
        wSem[waitSemaphoreCount++] = imgAvailSems[currentFrame];
        sSem[signalSemaphoreCount++] = signalSemaphore;
        if (nativeRuntimeActive && nativeGenerations > 0) {
            if (nativeGeneratedImgIdx >= frameQueuePresentSems_.size()
                    || frameQueuePresentSems_[nativeGeneratedImgIdx] == VK_NULL_HANDLE) {
                fbResized.store(true, std::memory_order_release);
                return;
            }
            wSem[waitSemaphoreCount++] = nativeGeneratedAcquireSemaphore;
            sSem[signalSemaphoreCount++] = frameQueuePresentSems_[nativeGeneratedImgIdx];
        }
    }
    VkPipelineStageFlags wStage[2] = {
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
    };
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
    if (!toXr) {
        si.waitSemaphoreCount=waitSemaphoreCount;
        si.pWaitSemaphores=wSem.data();
        si.pWaitDstStageMask=wStage;
        si.signalSemaphoreCount=signalSemaphoreCount;
        si.pSignalSemaphores=sSem.data();
    }
    si.commandBufferCount=1; si.pCommandBuffers=&cmdBufs[currentFrame];

    const uint64_t submissionSerial = submissionTimeline.nextSubmissionSerial();
    vk_.ResetFences(device,1,&inFlightFences[currentFrame]);
    VkResult submitResult = VK_SUCCESS;
    const auto submitStart = std::chrono::steady_clock::now();
    uint64_t graphicsQueueBlockedNs = 0;
    {
        const auto queueLockStart = std::chrono::steady_clock::now();
        std::unique_lock<std::mutex> queueLock(graphicsQueueMutex_);
        graphicsQueueBlockedNs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - queueLockStart).count());
        submitResult = vk_.QueueSubmit(
            graphicsQueue,1,&si,inFlightFences[currentFrame]);
    }
    const uint64_t submitCallNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - submitStart).count());
    if (submitResult!=VK_SUCCESS) {
        __android_log_print(
            ANDROID_LOG_WARN, "LSFG_FRAME_QUEUE",
            "event=graphics-queue-submit"
            " implementation=host-compositor"
            " graphics_queue_family=%u graphics_queue_index=0"
            " graphics_queue_submit_serial=%" PRIu64
            " graphics_queue_submit_ms=%.3f graphics_queue_blocked_ms=%.3f"
            " graphics_queue_idle_ms=-1.000"
            " queue_idle_measurement=not-observable-without-added-sync result=%d",
            graphicsQueueFamilyIndex,
            submissionSerial,
            static_cast<double>(submitCallNs) / 1000000.0,
            static_cast<double>(graphicsQueueBlockedNs) / 1000000.0,
            submitResult);
        vk_.DestroyFence(device,inFlightFences[currentFrame],nullptr);
        VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
        vk_.CreateFence(device,&fi,nullptr,&inFlightFences[currentFrame]);
        return;
    }
    submissionTimeline.submitFrame(currentFrame, submissionSerial);
    renderSubmissionSerial.store(submissionSerial, std::memory_order_release);
    if (submissionSerial <= 8 || submissionSerial % 120 == 0
            || graphicsQueueBlockedNs >= 1000000ULL
            || submitCallNs >= 8000000ULL) {
        __android_log_print(
            ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
            "event=graphics-queue-submit"
            " implementation=host-compositor"
            " graphics_queue_family=%u graphics_queue_index=0"
            " graphics_queue_submit_serial=%" PRIu64
            " graphics_queue_submit_ms=%.3f graphics_queue_blocked_ms=%.3f"
            " graphics_queue_idle_ms=-1.000"
            " queue_idle_measurement=not-observable-without-added-sync result=0",
            graphicsQueueFamilyIndex,
            submissionSerial,
            static_cast<double>(submitCallNs) / 1000000.0,
            static_cast<double>(graphicsQueueBlockedNs) / 1000000.0);
    }
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        markDrawResourcesSubmitted(submissionSerial);
        consumeQueuedLsfgHostDeliveries(frameDraws);
    }
    if (!toXr) {
        uint64_t hostPresentId = hostPresentId_++;
        if (hostPresentId == 0) {
            hostPresentId = 1;
            hostPresentId_ = 2;
        }
        uint32_t googlePresentId = hostGooglePresentId_++;
        if (googlePresentId == 0) {
            googlePresentId = 1;
            hostGooglePresentId_ = 2;
        }

        HostDisplayConfirmationBackend confirmationBackend =
            HostDisplayConfirmationBackend::WsiAccepted;
        if (hostPresentWaitEnabled && vk_.WaitForPresentKHR)
            confirmationBackend = HostDisplayConfirmationBackend::PresentWait;
        if (hostGoogleDisplayTimingEnabled && vk_.GetPastPresentationTimingGOOGLE)
            confirmationBackend = HostDisplayConfirmationBackend::GoogleDisplayTiming;

        const uint32_t gpuOutstanding =
            countOutstandingFrameSubmissions(true);
        res = enqueueHostPresent(PendingHostPresent{
            .frameSlot = currentFrame,
            .imageIndex = imgIdx,
            .swapchain = swapchain,
            .waitSemaphore = signalSemaphore,
            .hostPresentId = hostPresentId,
            .googlePresentId = googlePresentId,
            .backend = confirmationBackend,
            .frameProvenance = std::move(frameProvenance),
            .desiredDecision = desiredDecision,
            .hasUniqueLsfgDelivery = hasUniqueLsfgDelivery,
            .acquireNs = acquireNs,
            .submitCallNs = submitCallNs,
            .submissionSerial = submissionSerial,
            .swapchainGeneration = hostSwapchainGeneration_,
            .gpuOutstanding = gpuOutstanding,
        });
        if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR)
            fbResized.store(true);

        if (res == VK_SUCCESS && nativeRuntimeActive && nativeGenerations > 0) {
            uint64_t generatedHostPresentId = hostPresentId_++;
            if (generatedHostPresentId == 0) {
                generatedHostPresentId = 1;
                hostPresentId_ = 2;
            }
            uint32_t generatedGooglePresentId = hostGooglePresentId_++;
            if (generatedGooglePresentId == 0) {
                generatedGooglePresentId = 1;
                hostGooglePresentId_ = 2;
            }

            LsfgFrameProvenance generatedProvenance{};
            generatedProvenance.valid = true;
            generatedProvenance.contextEpoch = hostSwapchainGeneration_;
            generatedProvenance.swapchainImageIndex = nativeGeneratedImgIdx;
            generatedProvenance.interpolationCount = nativeGenerations;
            generatedProvenance.interpolationIndex = 1;
            generatedProvenance.kind = 1;

            std::vector<LsfgFrameProvenance> generatedProvenanceList{
                generatedProvenance
            };
            const uint32_t generatedGpuOutstanding =
                countOutstandingFrameSubmissions(true);
            const VkResult generatedPresentResult = enqueueHostPresent(PendingHostPresent{
                .frameSlot = currentFrame,
                .imageIndex = nativeGeneratedImgIdx,
                .swapchain = swapchain,
                .waitSemaphore = frameQueuePresentSems_[nativeGeneratedImgIdx],
                .hostPresentId = generatedHostPresentId,
                .googlePresentId = generatedGooglePresentId,
                .backend = confirmationBackend,
                .frameProvenance = std::move(generatedProvenanceList),
                .desiredDecision = HostDesiredPresentDecision{},
                .hasUniqueLsfgDelivery = false,
                .acquireNs = 0,
                .submitCallNs = submitCallNs,
                .submissionSerial = submissionSerial,
                .swapchainGeneration = hostSwapchainGeneration_,
                .gpuOutstanding = generatedGpuOutstanding,
            });
            if (generatedPresentResult == VK_ERROR_OUT_OF_DATE_KHR ||
                generatedPresentResult == VK_ERROR_SURFACE_LOST_KHR) {
                fbResized.store(true);
            }
            if (generatedPresentResult == VK_SUCCESS) {
                presentedFrames.fetch_add(1, std::memory_order_relaxed);
                RLOG("LSFG_NATIVE: event=generated_present_queued generation=%u image=%u submission_serial=%" PRIu64,
                    nativeGenerations, nativeGeneratedImgIdx, submissionSerial);
            }
        }
    } else {
        // The XR session samples xrAhb from its own GL context with no fence handoff;
        // blocking here means the buffer is fully written whenever this thread is idle,
        // leaving only the active write window unsynchronized (a tear, not stale data).
        if (vk_.WaitForFences(
                device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX) == VK_SUCCESS)
            submissionTimeline.completeFrame(currentFrame);
    }
    const uint32_t nextSlotCount = hasUniqueLsfgDelivery
        ? activeFrameSlotCount()
        : BASE_FRAMES_IN_FLIGHT;
    currentFrame=(currentFrame+1)%nextSlotCount;
}

void VulkanRendererContext::onSurfaceResized(int w, int h) {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (w==0||h==0) return;
    surfaceWidth=w; surfaceHeight=h; fbResized.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::detachSurface() {
    surfaceDetached.store(true, std::memory_order_release);
    dirtyCV.notify_all();

    { std::unique_lock<std::shared_mutex> frameLock(frameMutex); }

    vk_.DeviceWaitIdle(device);
    submissionTimeline.completeAllFrames();
    cleanupSwapchain();
    destroyRetiredFrameQueuePresentSemaphores();
    if (surface != VK_NULL_HANDLE) {
        vk_.DestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
    }
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
}

bool VulkanRendererContext::reattachSurface(ANativeWindow* newWindow) {
    if (window) { ANativeWindow_release(window); window = nullptr; }
    window = newWindow;
    VkAndroidSurfaceCreateInfoKHR ci{};
    ci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window = window;
    if (vk_.CreateAndroidSurfaceKHR(instance, &ci, nullptr, &surface) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: CreateAndroidSurface failed");
        ANativeWindow_release(window); window = nullptr;
        return false;
    }
    {
        std::unique_lock<std::shared_mutex> frameLock(frameMutex);
        try {
            createSwapchain();
            createFramebuffers();
            createCmdBufs();
            createFrameQueuePresentSemaphores();
            imgInFlight.assign(swapchainImages.size(), VK_NULL_HANDLE);
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: swapchain recreate failed");
            return false;
        }
        surfaceDetached.store(false, std::memory_order_release);
    }
    needsRender.store(true, std::memory_order_release);
    dirtyCV.notify_all();
    __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer", "reattachSurface: OK");
    return true;
}

void VulkanRendererContext::setTransform(float ox, float oy, float sx, float sy) {
    { std::lock_guard<std::mutex> lk(renderMutex); sceneOffsetX=ox;sceneOffsetY=oy;sceneScaleX=sx;sceneScaleY=sy; }
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updatePointerPosition(short x, short y) {
    pointerX.store(x); pointerY.store(y);
    if (cursorVisible.load()) { cursorMoved.store(true); dirtyCV.notify_one(); }
}

void VulkanRendererContext::setCursorVisible(bool v) {
    cursorVisible.store(v); cursorMoved.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updateCursorImage(void* px, short w, short h, short hotX, short hotY) {
    if (!px||w<=0||h<=0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    ensureCursorTex(w,h);
    cursorPixels.resize((size_t)w*h); memcpy(cursorPixels.data(),px,(size_t)w*h*4);
    cursorHotX=hotX; cursorHotY=hotY;
    isCursorImageDirty.store(true); needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::updateWindowContent(int64_t id, void* px, short w, short h, short stride, int, int) {
    if (!px||w<=0||h<=0) return;

    void* mapped=nullptr;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        WinTex& wt=texMap[id];
        if (wt.isAHB) {
            releaseWindowAhbImports(id);
            wt = {};
        }
        if (wt.img==VK_NULL_HANDLE || wt.w!=w || wt.h!=h) {
            if (wt.img!=VK_NULL_HANDLE) destroyWinTex(wt);
            if (!createWinTexResources(wt,w,h)) { texMap.erase(id); return; }
        }
        mapped=wt.mapped;
    }

    if (!mapped) return;
    const size_t dstPitch=(size_t)w*4;
    const int32_t srcStride=stride>0?stride:w;
    uint32_t* src2=static_cast<uint32_t*>(px);
    uint8_t*  dst2=static_cast<uint8_t*>(mapped);
    for (int row=0;row<h;++row)
        memcpy(dst2+(size_t)row*dstPitch,
               &src2[(size_t)row*srcStride],(size_t)w*4);
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        auto it=texMap.find(id);
        if (it!=texMap.end()) it->second.dirty=true;
    }
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::retireAhbImport(AHardwareBuffer* ahb) {
    auto cit = ahbImportCache.find(ahb);
    if (cit == ahbImportCache.end()) return;

    RetiredAhbImport retired{};
    retired.ahb = ahb;
    retired.texture = cit->second;
    retired.lastUseSubmissionSerial = cit->second.lastUseSubmissionSerial;
    retired.retireAfterSubmissionSerial = retired.lastUseSubmissionSerial;
    retiredAhbImports.push_back(retired);
    ahbImportCache.erase(cit);
}

void VulkanRendererContext::releaseWindowAhbReference(AHardwareBuffer* ahb) {
    auto ref = ahbWindowRefCounts.find(ahb);
    if (ref == ahbWindowRefCounts.end()) return;
    if (ref->second > 1) {
        --ref->second;
        return;
    }
    ahbWindowRefCounts.erase(ref);
    retireAhbImport(ahb);
}

void VulkanRendererContext::releaseWindowAhbImports(int64_t id) {
    auto wit = windowAhbs.find(id);
    if (wit == windowAhbs.end()) return;
    for (AHardwareBuffer* ahb : wit->second)
        releaseWindowAhbReference(ahb);
    windowAhbs.erase(wit);
}

void VulkanRendererContext::evictWindowAhbImports(int64_t id, AHardwareBuffer* keepAhb) {
    auto wit = windowAhbs.find(id);
    if (wit == windowAhbs.end()) return;
    auto& history = wit->second;

    while (history.size() > MAX_AHB_IMPORTS_PER_WINDOW) {
        auto evictIt = std::find_if(
            history.begin(), history.end(),
            [keepAhb](AHardwareBuffer* candidate) { return candidate != keepAhb; });
        if (evictIt == history.end()) break;

        AHardwareBuffer* evicted = *evictIt;
        history.erase(evictIt);
        releaseWindowAhbReference(evicted);
        RLOG("AHB cache evict id=%" PRId64 " ahb=%p active=%zu retired=%zu submitted=%" PRIu64 " completed=%" PRIu64,
            id, (void*)evicted, ahbImportCache.size(), retiredAhbImports.size(),
            submissionTimeline.submittedSubmissionSerial.load(std::memory_order_acquire),
            submissionTimeline.completedSubmissionSerial.load(std::memory_order_acquire));
    }
}

void VulkanRendererContext::reclaimRetiredAhbImports() {
    const uint64_t completedSerial =
        submissionTimeline.completedSubmissionSerial.load(std::memory_order_acquire);
    auto it = retiredAhbImports.begin();
    while (it != retiredAhbImports.end()) {
        if (completedSerial < it->retireAfterSubmissionSerial) {
            ++it;
            continue;
        }

        auto& retired = *it;
        if (retired.texture.ds != VK_NULL_HANDLE)
            vk_.FreeDescriptorSets(device, ahbTexPool, 1, &retired.texture.ds);
        if (retired.texture.view != VK_NULL_HANDLE)
            vk_.DestroyImageView(device, retired.texture.view, nullptr);
        if (retired.texture.img != VK_NULL_HANDLE)
            vk_.DestroyImage(device, retired.texture.img, nullptr);
        if (retired.texture.mem != VK_NULL_HANDLE)
            vk_.FreeMemory(device, retired.texture.mem, nullptr);
        if (retired.texture.stg != VK_NULL_HANDLE) {
            vk_.DestroyBuffer(device, retired.texture.stg, nullptr);
            vk_.FreeMemory(device, retired.texture.stgMem, nullptr);
        }
        AHardwareBuffer_release(retired.ahb);
        it = retiredAhbImports.erase(it);
    }
}

void VulkanRendererContext::reclaimRetiredWindowTextures() {
    const uint64_t completedSerial =
        submissionTimeline.completedSubmissionSerial.load(std::memory_order_acquire);
    auto it = retiredWindowTextures.begin();
    while (it != retiredWindowTextures.end()) {
        if (completedSerial < it->lastUseSubmissionSerial) {
            ++it;
            continue;
        }
        auto& wt = it->texture;
        VkDescriptorPool pool =
            wt.descriptorPool != VK_NULL_HANDLE ? wt.descriptorPool : winTexPool;
        if (wt.ds  !=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,pool,1,&wt.ds);
        if (wt.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,wt.view,nullptr);
        if (wt.img !=VK_NULL_HANDLE) vk_.DestroyImage(device,wt.img,nullptr);
        if (wt.mem !=VK_NULL_HANDLE) vk_.FreeMemory(device,wt.mem,nullptr);
        if (wt.stg !=VK_NULL_HANDLE) {
            vk_.DestroyBuffer(device,wt.stg,nullptr);
            vk_.FreeMemory(device,wt.stgMem,nullptr);
        }
        it = retiredWindowTextures.erase(it);
    }
}

void VulkanRendererContext::markDrawResourcesSubmitted(uint64_t submissionSerial) {
    for (const auto& draw : frameDraws) {
        if (draw.isAHB && draw.ahb != nullptr) {
            auto active = ahbImportCache.find(draw.ahb);
            if (active != ahbImportCache.end() && active->second.img == draw.img) {
                active->second.lastUseSubmissionSerial =
                    std::max(active->second.lastUseSubmissionSerial, submissionSerial);
            } else {
                auto retired = std::find_if(
                    retiredAhbImports.begin(), retiredAhbImports.end(),
                    [&draw](const RetiredAhbImport& candidate) {
                        return candidate.ahb == draw.ahb
                            && candidate.texture.img == draw.img;
                    });
                if (retired != retiredAhbImports.end()) {
                    retired->lastUseSubmissionSerial =
                        std::max(retired->lastUseSubmissionSerial, submissionSerial);
                    retired->retireAfterSubmissionSerial =
                        retired->lastUseSubmissionSerial;
                    retired->texture.lastUseSubmissionSerial =
                        retired->lastUseSubmissionSerial;
                }
            }
            auto window = texMap.find(draw.ownerId);
            if (window != texMap.end()
                    && window->second.isAHB
                    && window->second.img == draw.img)
                window->second.lastUseSubmissionSerial = submissionSerial;
            continue;
        }

        auto window = texMap.find(draw.ownerId);
        if (window != texMap.end()
                && !window->second.isAHB
                && window->second.img == draw.img) {
            window->second.lastUseSubmissionSerial =
                std::max(window->second.lastUseSubmissionSerial, submissionSerial);
            continue;
        }
        auto retired = std::find_if(
            retiredWindowTextures.begin(), retiredWindowTextures.end(),
            [&draw](const RetiredWindowTexture& candidate) {
                return candidate.texture.img == draw.img;
            });
        if (retired != retiredWindowTextures.end()) {
            retired->lastUseSubmissionSerial =
                std::max(retired->lastUseSubmissionSerial, submissionSerial);
            retired->texture.lastUseSubmissionSerial =
                retired->lastUseSubmissionSerial;
        }
    }
}

void VulkanRendererContext::completeObservedFence(VkFence fence) {
    if (fence == VK_NULL_HANDLE) return;
    for (std::size_t i = 0; i < inFlightFences.size(); ++i) {
        if (inFlightFences[i] == fence) {
            submissionTimeline.completeFrame(static_cast<uint32_t>(i));
            return;
        }
    }
}

void VulkanRendererContext::initLsfgProvenanceSocket() {
    std::lock_guard<std::mutex> ownerLock(gLsfgProvenanceSocketOwnerMutex);

    if (gLsfgProvenanceSocketFd < 0) {
        const int fd = ::socket(
            AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (fd < 0) {
            __android_log_print(
                ANDROID_LOG_WARN, "LSFG_HOST_DISPLAY",
                "provenance-socket-bind-failed reason=socket errno=%d mode=abstract name=%s",
                errno, LSFG_PROVENANCE_SOCKET);
            return;
        }

        const int receiveBufferBytes = kLsfgProvenanceReceiveBufferBytes;
        if (::setsockopt(
                fd, SOL_SOCKET, SO_RCVBUF,
                &receiveBufferBytes, sizeof(receiveBufferBytes)) != 0) {
            __android_log_print(
                ANDROID_LOG_WARN, "LSFG_HOST_DISPLAY",
                "provenance-socket-buffer-warning errno=%d requested=%d",
                errno, receiveBufferBytes);
        }

        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        address.sun_path[0] = '\0';
        constexpr std::size_t socketNameLength =
            sizeof(LSFG_PROVENANCE_SOCKET) - 1;
        static_assert(socketNameLength + 1 <= sizeof(address.sun_path));
        std::memcpy(address.sun_path + 1, LSFG_PROVENANCE_SOCKET, socketNameLength);
        const socklen_t addressLength = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path) + 1 + socketNameLength);

        if (::bind(
                fd,
                reinterpret_cast<const sockaddr*>(&address),
                addressLength) != 0) {
            const int bindError = errno;
            ::close(fd);
            __android_log_print(
                ANDROID_LOG_WARN, "LSFG_HOST_DISPLAY",
                "provenance-socket-bind-failed reason=bind errno=%d mode=abstract name=%s",
                bindError, LSFG_PROVENANCE_SOCKET);
            return;
        }

        gLsfgProvenanceSocketFd = fd;
        __android_log_print(
            ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
            "provenance-socket-bind-ok mode=abstract name=%s rcvbuf_requested=%d",
            LSFG_PROVENANCE_SOCKET, receiveBufferBytes);
    }

    auto existing = std::find(
        gLsfgProvenanceSocketContexts.begin(),
        gLsfgProvenanceSocketContexts.end(),
        this);
    if (existing == gLsfgProvenanceSocketContexts.end())
        gLsfgProvenanceSocketContexts.push_back(this);

    VulkanRendererContext* previousOwner = gLsfgProvenanceSocketOwner;
    const uint64_t previousGeneration = previousOwner
        ? previousOwner->provenanceSocketOwnerGeneration_ : 0;
    if (previousOwner && previousOwner != this)
        previousOwner->lsfgProvenanceSocket = -1;

    gLsfgProvenanceSocketOwner = this;
    lsfgProvenanceSocket = gLsfgProvenanceSocketFd;
    provenanceSocketOwnerGeneration_ = ++gLsfgProvenanceSocketOwnerGeneration;

    if (previousOwner && previousOwner != this) {
        __android_log_print(
            ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
            "provenance-socket-owner-transfer previous_generation=%" PRIu64
            " current_generation=%" PRIu64 " reason=new-renderer",
            previousGeneration, provenanceSocketOwnerGeneration_);
    }
}

void VulkanRendererContext::closeLsfgProvenanceSocket() {
    {
        std::lock_guard<std::mutex> ownerLock(gLsfgProvenanceSocketOwnerMutex);
        auto existing = std::find(
            gLsfgProvenanceSocketContexts.begin(),
            gLsfgProvenanceSocketContexts.end(),
            this);
        if (existing != gLsfgProvenanceSocketContexts.end())
            gLsfgProvenanceSocketContexts.erase(existing);

        if (gLsfgProvenanceSocketOwner == this) {
            gLsfgProvenanceSocketOwner = gLsfgProvenanceSocketContexts.empty()
                ? nullptr : gLsfgProvenanceSocketContexts.back();
            if (gLsfgProvenanceSocketOwner) {
                gLsfgProvenanceSocketOwner->lsfgProvenanceSocket =
                    gLsfgProvenanceSocketFd;
                gLsfgProvenanceSocketOwner->provenanceSocketOwnerGeneration_ =
                    ++gLsfgProvenanceSocketOwnerGeneration;
                __android_log_print(
                    ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
                    "provenance-socket-owner-transfer current_generation=%" PRIu64
                    " reason=owner-close",
                    gLsfgProvenanceSocketOwner->provenanceSocketOwnerGeneration_);
            }
        }

        if (gLsfgProvenanceSocketContexts.empty()) {
            gLsfgProvenanceSocketOwner = nullptr;
            if (gLsfgProvenanceSocketFd >= 0) {
                ::close(gLsfgProvenanceSocketFd);
                gLsfgProvenanceSocketFd = -1;
                ++gLsfgProvenanceSocketOwnerGeneration;
                __android_log_print(
                    ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
                    "provenance-socket-release reason=no-renderers generation=%" PRIu64,
                    gLsfgProvenanceSocketOwnerGeneration);
            }
        }
        lsfgProvenanceSocket = -1;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        "LSFG_HOST_DISPLAY",
        "provenance_rx_total=%" PRIu64
        " provenance_match_total=%" PRIu64
        " provenance_miss_total=%" PRIu64,
        provenanceRxTotal_,
        provenanceMatchTotal_,
        provenanceMissTotal_);

    pendingLsfgProvenance.clear();
    lsfgSwapchainImageAhbs.clear();
}

void VulkanRendererContext::drainLsfgProvenance() {
    std::lock_guard<std::mutex> ownerLock(gLsfgProvenanceSocketOwnerMutex);
    if (gLsfgProvenanceSocketOwner != this
            || gLsfgProvenanceSocketFd < 0)
        return;
    lsfgProvenanceSocket = gLsfgProvenanceSocketFd;
    for (;;) {
        LsfgFrameProvenancePacket packet{};
        const ssize_t received = ::recvfrom(
            lsfgProvenanceSocket, &packet, sizeof(packet), MSG_DONTWAIT,
            nullptr, nullptr);
        if (received < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            return;
        }
        if (received != static_cast<ssize_t>(sizeof(packet))
                || packet.magic != kLsfgFrameProvenanceMagic
                || packet.version != kLsfgFrameProvenanceVersion
                || packet.deliveryId == 0) {
            continue;
        }

        if (packet.contextEpoch != 0
                && activeProvenanceContextEpoch_ != 0
                && packet.contextEpoch != activeProvenanceContextEpoch_) {
            flushHostDisplayConfirmationsUnknown("provenance-epoch-reset");
            pendingLsfgProvenance.clear();
            hostDeliveryQueueContextEpoch_ = packet.contextEpoch;
            hostSnapshottedLsfgDeliveries_.clear();
            lsfgSwapchainImageAhbs.clear();
            consumedLsfgDeliveries_.clear();
            lastAcceptedDesiredPresentTimeNs_ = 0;
            resetHostPhysicalCadenceTelemetry("provenance-epoch-reset");
            for (auto& [id, texture] : texMap)
                texture.frameProvenance = {};
            for (auto& [ahb, texture] : ahbImportCache)
                texture.frameProvenance = {};
            __android_log_print(
                ANDROID_LOG_INFO,
                "LSFG_HOST_DISPLAY",
                "provenance-epoch-reset previous=%" PRIu64
                " current=%" PRIu64,
                activeProvenanceContextEpoch_,
                packet.contextEpoch);
        }
        if (packet.contextEpoch != 0)
            activeProvenanceContextEpoch_ = packet.contextEpoch;

        ++provenanceRxTotal_;
        if (!provenanceFirstPacketLogged_) {
            provenanceFirstPacketLogged_ = true;
            __android_log_print(
                ANDROID_LOG_INFO,
                "LSFG_HOST_DISPLAY",
                "provenance-first-packet delivery_id=%" PRIu64
                " swapchain_image=%u kind=%s",
                packet.deliveryId,
                packet.swapchainImageIndex,
                provenanceKindName(packet.kind));
        }
        if (provenanceRxTotal_ == 1 || provenanceRxTotal_ % 120 == 0) {
            __android_log_print(
                ANDROID_LOG_INFO,
                "LSFG_HOST_DISPLAY",
                "provenance_rx_total=%" PRIu64
                " provenance_match_total=%" PRIu64
                " provenance_miss_total=%" PRIu64
                " pending=%zu",
                provenanceRxTotal_,
                provenanceMatchTotal_,
                provenanceMissTotal_,
                pendingLsfgProvenance.size());
        }

        LsfgFrameProvenance provenance{};
        provenance.valid = true;
        provenance.runtimeSessionId = packet.runtimeSessionId;
        provenance.contextEpoch = packet.contextEpoch;
        provenance.deliveryId = packet.deliveryId;
        provenance.sourceIndex = packet.sourceIndex;
        provenance.batchId = packet.batchId;
        provenance.swapchainImageIndex = packet.swapchainImageIndex;
        provenance.interpolationCount = packet.interpolationCount;
        provenance.interpolationIndex = packet.interpolationIndex;
        provenance.kind = packet.kind;
        provenance.desiredPresentTimeNs = packet.desiredPresentTimeNs;
        pendingLsfgProvenance.push_back(provenance);
        while (pendingLsfgProvenance.size() > kMaxPendingLsfgProvenance) {
            HostDisplayConfirmation dropped{};
            dropped.frameProvenance.push_back(pendingLsfgProvenance.front());
            emitHostDisplayConfirmation(
                dropped, false, true, "provenance-queue-overflow");
            pendingLsfgProvenance.pop_front();
        }
    }
}

uint64_t VulkanRendererContext::ahbIdentity(AHardwareBuffer* ahb) const {
    if (!ahb) return 0;
    using GetAhbId = int (*)(const AHardwareBuffer*, uint64_t*);
    static GetAhbId getAhbId = []() -> GetAhbId {
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
        if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
        return lib ? reinterpret_cast<GetAhbId>(dlsym(lib, "AHardwareBuffer_getId")) : nullptr;
    }();
    uint64_t id = 0;
    if (getAhbId && getAhbId(ahb, &id) == 0 && id != 0)
        return id;
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(ahb));
}

void VulkanRendererContext::bindLsfgProvenance(
        AHardwareBuffer* ahb, WinTex& texture) {
    drainLsfgProvenance();
    texture.frameProvenance = {};
    if (!ahb || pendingLsfgProvenance.empty()) {
        if (ahb)
            ++provenanceMissTotal_;
        return;
    }

    const uint64_t identity = ahbIdentity(ahb);
    auto selected = pendingLsfgProvenance.end();

    // Once an image-index/AHB relationship is learned it is authoritative for
    // that guest swapchain lifetime. This avoids binding a coalesced old event
    // to a newer image that happens to arrive first.
    for (auto it = pendingLsfgProvenance.begin();
            it != pendingLsfgProvenance.end(); ++it) {
        const auto mapped = lsfgSwapchainImageAhbs.find(it->swapchainImageIndex);
        if (mapped != lsfgSwapchainImageAhbs.end() && mapped->second == identity) {
            selected = it;
            break;
        }
    }

    // Bootstrap the mapping from the ordered present/update stream. The bridge
    // is observability only: uncertainty becomes UNKNOWN, never a pacing action.
    if (selected == pendingLsfgProvenance.end()) {
        for (auto it = pendingLsfgProvenance.begin();
                it != pendingLsfgProvenance.end(); ++it) {
            if (lsfgSwapchainImageAhbs.find(it->swapchainImageIndex)
                    == lsfgSwapchainImageAhbs.end()) {
                lsfgSwapchainImageAhbs[it->swapchainImageIndex] = identity;
                selected = it;
                break;
            }
        }
    }

    if (selected == pendingLsfgProvenance.end()) {
        ++provenanceMissTotal_;
        return;
    }

    while (pendingLsfgProvenance.begin() != selected) {
        HostDisplayConfirmation superseded{};
        superseded.frameProvenance.push_back(pendingLsfgProvenance.front());
        ++provenanceSupersededTotal_;
        emitHostDisplayConfirmation(
            superseded, false, true, "superseded-before-host-import");
        pendingLsfgProvenance.pop_front();
    }

    texture.frameProvenance = pendingLsfgProvenance.front();
    pendingLsfgProvenance.pop_front();
    ++provenanceMatchTotal_;
    if (provenanceMatchTotal_ == 1 || provenanceMatchTotal_ % 120 == 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            "LSFG_HOST_DISPLAY",
            "provenance_rx_total=%" PRIu64
            " provenance_match_total=%" PRIu64
            " provenance_miss_total=%" PRIu64
            " provenance_superseded_total=%" PRIu64
            " matched_delivery_id=%" PRIu64,
            provenanceRxTotal_,
            provenanceMatchTotal_,
            provenanceMissTotal_,
            provenanceSupersededTotal_,
            texture.frameProvenance.deliveryId);
    }
}

std::vector<LsfgFrameProvenance> VulkanRendererContext::classifyHostPresentProvenance(
        const std::vector<DrawEntry>& draws) const {
    std::vector<LsfgFrameProvenance> result;
    std::unordered_set<uint64_t> seenThisPresent;
    for (const auto& draw : draws) {
        if (!draw.frameProvenance.valid || draw.frameProvenance.deliveryId == 0)
            continue;
        if (!seenThisPresent.insert(draw.frameProvenance.deliveryId).second)
            continue;
        auto provenance = draw.frameProvenance;
        provenance.uniqueDelivery =
            consumedLsfgDeliveries_.find(provenance.deliveryId)
                == consumedLsfgDeliveries_.end();
        result.push_back(provenance);
    }
    return result;
}


void VulkanRendererContext::resetHostPhysicalCadenceTelemetry(
        const char* reason) {
    ++hostPhysicalCadenceEpoch_;
    uniquePhysicalPresent_ = 0;
    sourceUniquePhysicalPresent_ = 0;
    generatedUniquePhysicalPresent_ = 0;
    sourceUniqueWsiAccepted_ = 0;
    generatedUniqueWsiAccepted_ = 0;
    sourcePhysicalUnknown_ = 0;
    generatedPhysicalUnknown_ = 0;
    hostPhaseRescheduledTotal_ = 0;
    hostTemporalBacklogTotal_ = 0;
    firstUniquePhysicalPresentNs_ = 0;
    lastUniquePhysicalPresentNs_ = 0;
    physicalCadenceErrorsNs_.clear();
    scheduledCadenceErrorsNs_.clear();
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
        "event=physical-cadence-reset cadence_epoch=%" PRIu64
        " swapchain_generation=%" PRIu64 " reason=%s",
        hostPhysicalCadenceEpoch_,
        hostSwapchainGeneration_,
        reason ? reason : "unknown");
}

HostDesiredPresentDecision VulkanRendererContext::validatedHostDesiredPresentTime(
        const std::vector<LsfgFrameProvenance>& provenance) {
    HostDesiredPresentDecision decision{};
    if (!hostGoogleDisplayTimingEnabled) {
        decision.fallbackReason = "google-display-timing-unavailable";
        return decision;
    }

    uint64_t desired = 0;
    for (const auto& frame : provenance) {
        if (!frame.uniqueDelivery || frame.desiredPresentTimeNs == 0)
            continue;
        if (desired != 0 && desired != frame.desiredPresentTimeNs) {
            decision.provenanceDesiredPresentTimeNs = desired;
            decision.fallbackReason = "mixed-temporal-intent";
            return decision;
        }
        desired = frame.desiredPresentTimeNs;
    }
    decision.provenanceDesiredPresentTimeNs = desired;
    decision.refreshPeriodNs = hostRefreshPeriodNs_;
    if (desired == 0) {
        decision.fallbackReason = "no-temporal-intent";
        return decision;
    }

    const uint64_t nowNs = monotonicTimeNs();
    if (nowNs == 0) {
        decision.fallbackReason = "clock-read-failed";
        return decision;
    }
    constexpr uint64_t kMaxFutureDesiredPresentNs = 250000000ULL;
    if (desired <= nowNs)
        decision.desiredStaleByNs = nowNs - desired;
    if (decision.desiredStaleByNs > MAX_HOST_TEMPORAL_STALE_NS) {
        decision.temporalBacklog = true;
        decision.fallbackReason = "stale-beyond-host-budget";
        return decision;
    }

    uint64_t earliestAllowedNs = nowNs;
    if (hostRefreshPeriodNs_ != 0) {
        const uint64_t phaseLeadNs =
            std::max<uint64_t>(500000ULL, hostRefreshPeriodNs_ / 2ULL);
        if (nowNs > UINT64_MAX - phaseLeadNs) {
            decision.fallbackReason = "schedule-overflow";
            return decision;
        }
        earliestAllowedNs = nowNs + phaseLeadNs;
    }
    if (lastAcceptedDesiredPresentTimeNs_ != 0
            && lastAcceptedDesiredPresentTimeNs_ >= earliestAllowedNs) {
        if (lastAcceptedDesiredPresentTimeNs_ == UINT64_MAX) {
            decision.fallbackReason = "schedule-overflow";
            return decision;
        }
        earliestAllowedNs = lastAcceptedDesiredPresentTimeNs_ + 1ULL;
    }

    uint64_t scheduled = desired;
    if (desired < earliestAllowedNs) {
        if (hostRefreshPeriodNs_ == 0) {
            decision.fallbackReason =
                desired <= nowNs ? "stale-no-refresh-cycle" : "regression";
            return decision;
        }
        scheduled = advanceDesiredPresentPhase(
            desired, earliestAllowedNs, hostRefreshPeriodNs_,
            decision.phaseAdvanceCycles);
        if (scheduled == 0) {
            decision.fallbackReason = "phase-reschedule-overflow";
            return decision;
        }
        decision.phaseAdvanceNs = scheduled - desired;
        decision.temporalBacklog =
            decision.phaseAdvanceCycles > 1
            || decision.desiredStaleByNs >= hostRefreshPeriodNs_;
        ++hostPhaseRescheduledTotal_;
        if (decision.temporalBacklog)
            ++hostTemporalBacklogTotal_;
        decision.fallbackReason = "phase-rescheduled";
    } else {
        decision.fallbackReason = "accepted";
    }

    if (scheduled <= nowNs) {
        decision.fallbackReason = "stale-after-reschedule";
        return decision;
    }
    decision.desiredFutureByNs = scheduled - nowNs;
    if (decision.desiredFutureByNs > kMaxFutureDesiredPresentNs) {
        decision.fallbackReason = "too-far-future";
        return decision;
    }
    lastAcceptedDesiredPresentTimeNs_ = scheduled;
    decision.submittedDesiredPresentTimeNs = scheduled;
    return decision;
}

void VulkanRendererContext::emitHostDisplayConfirmation(
        const HostDisplayConfirmation& confirmation,
        bool confirmed,
        bool unknown,
        const char* reason) {
    if (confirmation.frameProvenance.empty()) return;
    const uint64_t nowNs = monotonicTimeNs();
    const uint64_t confirmationAgeNs =
        nowNs != 0 && confirmation.enqueuedAtNs != 0
            && nowNs >= confirmation.enqueuedAtNs
        ? nowNs - confirmation.enqueuedAtNs : 0;
    const bool presentMarginValid =
        confirmed && confirmation.actualPresentTimeNs != 0
        && confirmation.presentMarginRawNs <= kMaxSanePresentMarginNs;

    for (const auto& provenance : confirmation.frameProvenance) {
        const bool physicalDeliveryUnknown =
            unknown && provenance.uniqueDelivery
            && confirmation.backend != HostDisplayConfirmationBackend::WsiAccepted;
        if (physicalDeliveryUnknown) {
            if (provenance.kind == 1) ++generatedPhysicalUnknown_;
            else ++sourcePhysicalUnknown_;
        }

        uint64_t uniquePhysicalIntervalNs = 0;
        if (confirmed && provenance.uniqueDelivery
                && confirmation.actualPresentTimeNs != 0) {
            if (firstUniquePhysicalPresentNs_ == 0)
                firstUniquePhysicalPresentNs_ = confirmation.actualPresentTimeNs;
            if (lastUniquePhysicalPresentNs_ != 0
                    && confirmation.actualPresentTimeNs
                        > lastUniquePhysicalPresentNs_) {
                uniquePhysicalIntervalNs =
                    confirmation.actualPresentTimeNs
                    - lastUniquePhysicalPresentNs_;
            }
            if (confirmation.provenanceDesiredPresentTimeNs != 0) {
                physicalCadenceErrorsNs_.push_back(
                    static_cast<uint64_t>(std::llabs(
                        static_cast<long long>(confirmation.actualPresentTimeNs)
                        - static_cast<long long>(
                            confirmation.provenanceDesiredPresentTimeNs))));
                while (physicalCadenceErrorsNs_.size() > 240)
                    physicalCadenceErrorsNs_.pop_front();
            }
            if (confirmation.submittedDesiredPresentTimeNs != 0) {
                scheduledCadenceErrorsNs_.push_back(
                    static_cast<uint64_t>(std::llabs(
                        static_cast<long long>(confirmation.actualPresentTimeNs)
                        - static_cast<long long>(
                            confirmation.submittedDesiredPresentTimeNs))));
                while (scheduledCadenceErrorsNs_.size() > 240)
                    scheduledCadenceErrorsNs_.pop_front();
            }
            lastUniquePhysicalPresentNs_ = confirmation.actualPresentTimeNs;
            ++uniquePhysicalPresent_;
            if (provenance.kind == 1) ++generatedUniquePhysicalPresent_;
            else ++sourceUniquePhysicalPresent_;
        }

        std::vector<uint64_t> sortedCadenceErrors(
            physicalCadenceErrorsNs_.begin(), physicalCadenceErrorsNs_.end());
        std::sort(sortedCadenceErrors.begin(), sortedCadenceErrors.end());
        const double cadenceErrorP50Ms = sortedCadenceErrors.empty() ? 0.0
            : static_cast<double>(
                sortedCadenceErrors[sortedCadenceErrors.size() / 2]) / 1000000.0;
        const double cadenceErrorP95Ms = sortedCadenceErrors.empty() ? 0.0
            : static_cast<double>(sortedCadenceErrors[std::min(
                sortedCadenceErrors.size() - 1,
                (sortedCadenceErrors.size() * 95) / 100)]) / 1000000.0;
        std::vector<uint64_t> sortedScheduledErrors(
            scheduledCadenceErrorsNs_.begin(), scheduledCadenceErrorsNs_.end());
        std::sort(sortedScheduledErrors.begin(), sortedScheduledErrors.end());
        const double scheduledErrorP50Ms = sortedScheduledErrors.empty() ? 0.0
            : static_cast<double>(
                sortedScheduledErrors[sortedScheduledErrors.size() / 2])
                / 1000000.0;
        const double scheduledErrorP95Ms = sortedScheduledErrors.empty() ? 0.0
            : static_cast<double>(sortedScheduledErrors[std::min(
                sortedScheduledErrors.size() - 1,
                (sortedScheduledErrors.size() * 95) / 100)]) / 1000000.0;
        const double sourceDeliveryEfficiency =
            sourceUniqueWsiAccepted_ > 0
                ? static_cast<double>(sourceUniquePhysicalPresent_)
                    / static_cast<double>(sourceUniqueWsiAccepted_)
                : 0.0;
        const double generatedDeliveryEfficiency =
            generatedUniqueWsiAccepted_ > 0
                ? static_cast<double>(generatedUniquePhysicalPresent_)
                    / static_cast<double>(generatedUniqueWsiAccepted_)
                : 0.0;

        publishLsfgHostDisplayFeedback(
            confirmation, provenance, confirmed);

        __android_log_print(
            ANDROID_LOG_INFO,
            "LSFG_HOST_DISPLAY",
            "host_present_id=%" PRIu64 " host_wsi_accepted=%d "
            "host_display_confirmed=%d host_display_unknown=%d "
            "confirmation_backend=%s delivery_id=%" PRIu64
            " context_epoch=%" PRIu64 " kind=%s "
            "source_index=%" PRIu64 " interpolation_index=%u interpolation_count=%u "
            "unique_delivery=%d repeated_content_present=%" PRIu64
            " provenance_desired_time=%" PRIu64
            " submitted_desired_time=%" PRIu64 " wsi_desired_time=%" PRIu64
            " desired_stale_by_ms=%.3f desired_future_by_ms=%.3f "
            "desired_fallback_reason=%s actual_present_time=%" PRIu64
            " earliest_present_time=%" PRIu64 " present_margin=%" PRIu64
            " present_margin_raw=%" PRIu64 " present_margin_valid=%d "
            "desired_vs_actual_ms=%.3f provenance_vs_actual_ms=%.3f "
            "unique_physical_interval_ms=%.3f refresh_period_ns=%" PRIu64
            " phase_advance_cycles=%" PRIu64 " phase_advance_ms=%.3f "
            "temporal_backlog=%d reason=%s",
            confirmation.hostPresentId,
            confirmation.hostPresentId != 0 ? 1 : 0,
            confirmed ? 1 : 0,
            unknown ? 1 : 0,
            hostDisplayBackendName(confirmation.backend),
            provenance.deliveryId,
            provenance.contextEpoch,
            provenanceKindName(provenance.kind),
            provenance.sourceIndex,
            static_cast<unsigned>(provenance.interpolationIndex),
            provenance.interpolationCount,
            provenance.uniqueDelivery ? 1 : 0,
            repeatedContentPresent_,
            confirmation.provenanceDesiredPresentTimeNs,
            confirmation.submittedDesiredPresentTimeNs,
            confirmation.wsiDesiredPresentTimeNs,
            static_cast<double>(confirmation.desiredStaleByNs) / 1000000.0,
            static_cast<double>(confirmation.desiredFutureByNs) / 1000000.0,
            confirmation.desiredFallbackReason
                ? confirmation.desiredFallbackReason : "none",
            confirmation.actualPresentTimeNs,
            confirmation.earliestPresentTimeNs,
            confirmation.presentMarginNs,
            confirmation.presentMarginRawNs,
            presentMarginValid ? 1 : 0,
            confirmation.actualPresentTimeNs != 0
                    && confirmation.submittedDesiredPresentTimeNs != 0
                ? static_cast<double>(
                    static_cast<int64_t>(confirmation.actualPresentTimeNs)
                    - static_cast<int64_t>(
                        confirmation.submittedDesiredPresentTimeNs))
                    / 1000000.0 : 0.0,
            confirmation.actualPresentTimeNs != 0
                    && confirmation.provenanceDesiredPresentTimeNs != 0
                ? static_cast<double>(
                    static_cast<int64_t>(confirmation.actualPresentTimeNs)
                    - static_cast<int64_t>(
                        confirmation.provenanceDesiredPresentTimeNs))
                    / 1000000.0 : 0.0,
            static_cast<double>(uniquePhysicalIntervalNs) / 1000000.0,
            confirmation.refreshPeriodNs,
            confirmation.phaseAdvanceCycles,
            static_cast<double>(confirmation.phaseAdvanceNs) / 1000000.0,
            confirmation.temporalBacklog ? 1 : 0,
            reason ? reason : "none");

        const double uniquePhysicalFps =
            firstUniquePhysicalPresentNs_ != 0
                    && lastUniquePhysicalPresentNs_ > firstUniquePhysicalPresentNs_
                ? static_cast<double>(uniquePhysicalPresent_) * 1000000000.0
                    / static_cast<double>(
                        lastUniquePhysicalPresentNs_ - firstUniquePhysicalPresentNs_)
                : 0.0;
        const double sourcePhysicalFps =
            firstUniquePhysicalPresentNs_ != 0
                    && lastUniquePhysicalPresentNs_ > firstUniquePhysicalPresentNs_
                ? static_cast<double>(sourceUniquePhysicalPresent_) * 1000000000.0
                    / static_cast<double>(
                        lastUniquePhysicalPresentNs_ - firstUniquePhysicalPresentNs_)
                : 0.0;
        const double generatedPhysicalFps =
            firstUniquePhysicalPresentNs_ != 0
                    && lastUniquePhysicalPresentNs_ > firstUniquePhysicalPresentNs_
                ? static_cast<double>(generatedUniquePhysicalPresent_) * 1000000000.0
                    / static_cast<double>(
                        lastUniquePhysicalPresentNs_ - firstUniquePhysicalPresentNs_)
                : 0.0;

        __android_log_print(
            ANDROID_LOG_INFO,
            "LSFG_HOST_DELIVERY",
            "event=display-accounting host_present_id=%" PRIu64
            " delivery_id=%" PRIu64 " kind=%s "
            "host_wsi_accepted_total=%" PRIu64
            " host_display_confirmed_total=%" PRIu64
            " host_display_unknown_total=%" PRIu64
            " display_delivery_ratio=%.4f unique_physical_fps=%.3f "
            "source_physical_fps=%.3f generated_physical_fps=%.3f "
            "source_delivery_efficiency=%.4f generated_delivery_efficiency=%.4f "
            "source_physical_unknown=%" PRIu64 " generated_physical_unknown=%" PRIu64
            " physical_delivery_unknown=%d physical_unknown_reason=%s "
            "cadence_error_p50_ms=%.3f cadence_error_p95_ms=%.3f "
            "scheduled_error_p50_ms=%.3f scheduled_error_p95_ms=%.3f "
            "confirmation_age_ms=%.3f confirmation_pending=%zu "
            "confirmation_pending_high_water=%" PRIu64
            " confirmation_expired_total=%" PRIu64
            " confirmation_overflow_total=%" PRIu64
            " display_timing_query_failures=%" PRIu64
            " invalid_present_margin_total=%" PRIu64
            " phase_rescheduled_total=%" PRIu64
            " temporal_backlog_total=%" PRIu64
            " swapchain_generation=%" PRIu64 " cadence_epoch=%" PRIu64
            " frame_slot=%u gpu_outstanding_at_submit=%u "
            "submission_serial=%" PRIu64 " completed_submission_serial=%" PRIu64
            " submit_call_ms=%.3f present_call_ms=%.3f",
            confirmation.hostPresentId,
            provenance.deliveryId,
            provenanceKindName(provenance.kind),
            hostWsiAccepted_,
            hostDisplayConfirmed_,
            hostDisplayUnknown_,
            hostWsiAccepted_ > 0
                ? static_cast<double>(hostDisplayConfirmed_)
                    / static_cast<double>(hostWsiAccepted_)
                : 0.0,
            uniquePhysicalFps,
            sourcePhysicalFps,
            generatedPhysicalFps,
            sourceDeliveryEfficiency,
            generatedDeliveryEfficiency,
            sourcePhysicalUnknown_,
            generatedPhysicalUnknown_,
            physicalDeliveryUnknown ? 1 : 0,
            physicalDeliveryUnknown && reason ? reason : "none",
            cadenceErrorP50Ms,
            cadenceErrorP95Ms,
            scheduledErrorP50Ms,
            scheduledErrorP95Ms,
            static_cast<double>(confirmationAgeNs) / 1000000.0,
            pendingHostDisplayConfirmations.size(),
            hostConfirmationPendingHighWater_,
            hostConfirmationExpiredTotal_,
            hostConfirmationOverflowTotal_,
            hostDisplayTimingQueryFailureTotal_,
            hostInvalidPresentMarginTotal_,
            hostPhaseRescheduledTotal_,
            hostTemporalBacklogTotal_,
            confirmation.swapchainGeneration,
            hostPhysicalCadenceEpoch_,
            confirmation.frameSlot,
            confirmation.gpuOutstandingAtSubmit,
            confirmation.submissionSerial,
            submissionTimeline.completedSubmissionSerial.load(
                std::memory_order_acquire),
            static_cast<double>(confirmation.submitCallNs) / 1000000.0,
            static_cast<double>(confirmation.presentCallNs) / 1000000.0);
    }
}

void VulkanRendererContext::recordHostPresent(
        const PendingHostPresent& present,
        uint64_t presentCallNs) {
    if (present.frameProvenance.empty()) return;
    HostDisplayConfirmation confirmation{};
    confirmation.hostPresentId = present.hostPresentId;
    confirmation.googlePresentId = present.googlePresentId;
    confirmation.backend = present.backend;
    confirmation.frameProvenance = present.frameProvenance;
    confirmation.provenanceDesiredPresentTimeNs =
        present.desiredDecision.provenanceDesiredPresentTimeNs;
    confirmation.submittedDesiredPresentTimeNs =
        present.desiredDecision.submittedDesiredPresentTimeNs;
    confirmation.desiredStaleByNs =
        present.desiredDecision.desiredStaleByNs;
    confirmation.desiredFutureByNs =
        present.desiredDecision.desiredFutureByNs;
    confirmation.refreshPeriodNs =
        present.desiredDecision.refreshPeriodNs;
    confirmation.phaseAdvanceCycles =
        present.desiredDecision.phaseAdvanceCycles;
    confirmation.phaseAdvanceNs =
        present.desiredDecision.phaseAdvanceNs;
    confirmation.temporalBacklog =
        present.desiredDecision.temporalBacklog;
    confirmation.desiredFallbackReason =
        present.desiredDecision.fallbackReason;
    confirmation.enqueuedAtNs = monotonicTimeNs();
    confirmation.swapchainGeneration = present.swapchainGeneration;
    confirmation.submissionSerial = present.submissionSerial;
    confirmation.presentCallNs = presentCallNs;
    confirmation.submitCallNs = present.submitCallNs;
    confirmation.frameSlot = present.frameSlot;
    confirmation.gpuOutstandingAtSubmit = present.gpuOutstanding;
    for (const auto& provenance : present.frameProvenance) {
        if (provenance.uniqueDelivery) {
            consumedLsfgDeliveries_.insert(provenance.deliveryId);
            if (present.backend != HostDisplayConfirmationBackend::WsiAccepted) {
                if (provenance.kind == 1) ++generatedUniqueWsiAccepted_;
                else ++sourceUniqueWsiAccepted_;
            }
        } else {
            ++repeatedContentPresent_;
        }
    }
    ++hostWsiAccepted_;

    if (present.backend == HostDisplayConfirmationBackend::WsiAccepted) {
        ++hostDisplayUnknown_;
        emitHostDisplayConfirmation(
            confirmation, false, true, "wsi-accepted-only");
        return;
    }

    pendingHostDisplayConfirmations.push_back(std::move(confirmation));
    hostConfirmationPendingHighWater_ = std::max<uint64_t>(
        hostConfirmationPendingHighWater_,
        pendingHostDisplayConfirmations.size());
    while (pendingHostDisplayConfirmations.size() > kMaxPendingHostConfirmations) {
        ++hostDisplayUnknown_;
        ++hostConfirmationOverflowTotal_;
        emitHostDisplayConfirmation(
            pendingHostDisplayConfirmations.front(),
            false, true, "confirmation-queue-overflow");
        pendingHostDisplayConfirmations.pop_front();
    }
}

void VulkanRendererContext::pollHostDisplayConfirmations() {
    if (pendingHostDisplayConfirmations.empty() || swapchain == VK_NULL_HANDLE)
        return;

    if (hostGoogleDisplayTimingEnabled && vk_.GetPastPresentationTimingGOOGLE) {
        uint32_t count = 0;
        VkResult query = vk_.GetPastPresentationTimingGOOGLE(
            device, swapchain, &count, nullptr);
        if (query == VK_SUCCESS && count > 0) {
            std::vector<VkPastPresentationTimingGOOGLE> timings(count);
            query = vk_.GetPastPresentationTimingGOOGLE(
                device, swapchain, &count, timings.data());
            if (query == VK_SUCCESS) {
                for (uint32_t i = 0; i < count; ++i) {
                    const auto& timing = timings[i];
                    auto it = std::find_if(
                        pendingHostDisplayConfirmations.begin(),
                        pendingHostDisplayConfirmations.end(),
                        [&](const HostDisplayConfirmation& pending) {
                            return pending.backend
                                    == HostDisplayConfirmationBackend::GoogleDisplayTiming
                                && pending.googlePresentId == timing.presentID
                                && pending.swapchainGeneration
                                    == hostSwapchainGeneration_;
                        });
                    if (it == pendingHostDisplayConfirmations.end()) continue;
                    it->actualPresentTimeNs = timing.actualPresentTime;
                    it->wsiDesiredPresentTimeNs = timing.desiredPresentTime;
                    it->earliestPresentTimeNs = timing.earliestPresentTime;
                    it->presentMarginRawNs = timing.presentMargin;
                    const bool marginValid =
                        timing.actualPresentTime != 0
                        && timing.presentMargin <= kMaxSanePresentMarginNs;
                    it->presentMarginNs =
                        marginValid ? timing.presentMargin : 0;
                    if (timing.actualPresentTime != 0 && !marginValid)
                        ++hostInvalidPresentMarginTotal_;
                    const bool confirmed = timing.actualPresentTime != 0;
                    if (confirmed) ++hostDisplayConfirmed_;
                    else ++hostDisplayUnknown_;
                    emitHostDisplayConfirmation(
                        *it, confirmed, !confirmed,
                        confirmed ? "actual-present-time" : "no-actual-present-time");
                    pendingHostDisplayConfirmations.erase(it);
                }
            } else {
                ++hostDisplayTimingQueryFailureTotal_;
            }
        } else if (query != VK_SUCCESS) {
            ++hostDisplayTimingQueryFailureTotal_;
        }
    }

    if (hostPresentWaitEnabled && vk_.WaitForPresentKHR) {
        for (auto it = pendingHostDisplayConfirmations.begin();
                it != pendingHostDisplayConfirmations.end();) {
            if (it->backend != HostDisplayConfirmationBackend::PresentWait) {
                ++it;
                continue;
            }
            const VkResult waitResult = vk_.WaitForPresentKHR(
                device, swapchain, it->hostPresentId, 0);
            if (waitResult == VK_TIMEOUT || waitResult == VK_NOT_READY) {
                ++it;
                continue;
            }
            const bool confirmed = waitResult == VK_SUCCESS;
            if (confirmed) ++hostDisplayConfirmed_;
            else ++hostDisplayUnknown_;
            emitHostDisplayConfirmation(
                *it, confirmed, !confirmed,
                confirmed ? "present-wait-complete" : "present-wait-error");
            it = pendingHostDisplayConfirmations.erase(it);
        }
    }

    const uint64_t nowNs = monotonicTimeNs();
    if (nowNs != 0) {
        for (auto it = pendingHostDisplayConfirmations.begin();
                it != pendingHostDisplayConfirmations.end();) {
            if (it->enqueuedAtNs == 0 || nowNs <= it->enqueuedAtNs
                    || nowNs - it->enqueuedAtNs <= kMaxHostConfirmationAgeNs) {
                ++it;
                continue;
            }
            ++hostDisplayUnknown_;
            ++hostConfirmationExpiredTotal_;
            emitHostDisplayConfirmation(
                *it, false, true, "confirmation-timeout");
            it = pendingHostDisplayConfirmations.erase(it);
        }
    }
}

void VulkanRendererContext::flushHostDisplayConfirmationsUnknown(
        const char* reason) {
    for (const auto& pending : pendingHostDisplayConfirmations) {
        ++hostDisplayUnknown_;
        emitHostDisplayConfirmation(pending, false, true, reason);
    }
    pendingHostDisplayConfirmations.clear();
}

void VulkanRendererContext::updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short, short, int, int) {
    if (!ahb) return;
    std::lock_guard<std::mutex> lk(renderMutex);

    auto cit = ahbImportCache.find(ahb);
    if (cit == ahbImportCache.end()) {
        auto retired = std::find_if(
            retiredAhbImports.begin(), retiredAhbImports.end(),
            [ahb](const RetiredAhbImport& candidate) {
                return candidate.ahb == ahb;
            });
        if (retired != retiredAhbImports.end()) {
            ahbImportCache.emplace(ahb, retired->texture);
            retiredAhbImports.erase(retired);
            cit = ahbImportCache.find(ahb);
            RLOG("updateWindowContentAHB: revived AHB %p for id=%" PRId64,
                (void*)ahb, id);
        }
    }

    if (cit == ahbImportCache.end()) {
        if (!AhbImportBudget::canAllocate(
                ahbImportCache.size(), retiredAhbImports.size())) {
            RLOG_E("updateWindowContentAHB: import budget exhausted id=%" PRId64
                   " active=%zu retired=%zu submitted=%" PRIu64 " completed=%" PRIu64,
                id, ahbImportCache.size(), retiredAhbImports.size(),
                submissionTimeline.submittedSubmissionSerial.load(std::memory_order_acquire),
                submissionTimeline.completedSubmissionSerial.load(std::memory_order_acquire));
            needsRender.store(true);
            dirtyCV.notify_one();
            return;
        }

        WinTex tmp{};
        if (!importAHBToWinTex(tmp, ahb)) {
            RLOG_E("updateWindowContentAHB: import failed for id=%" PRId64
                   " active=%zu retired=%zu submitted=%" PRIu64 " completed=%" PRIu64,
                id, ahbImportCache.size(), retiredAhbImports.size(),
                submissionTimeline.submittedSubmissionSerial.load(std::memory_order_acquire),
                submissionTimeline.completedSubmissionSerial.load(std::memory_order_acquire));
            needsRender.store(true);
            dirtyCV.notify_one();
            return;
        }
        AHardwareBuffer_acquire(ahb);
        ahbImportCache[ahb] = tmp;
        cit = ahbImportCache.find(ahb);
        RLOG("updateWindowContentAHB: imported new AHB %p for id=%" PRId64
             " (%dx%d) active=%zu retired=%zu",
            (void*)ahb, id, tmp.w, tmp.h,
            ahbImportCache.size(), retiredAhbImports.size());
    }

    auto& history = windowAhbs[id];
    auto historyIt = std::find(history.begin(), history.end(), ahb);
    if (historyIt == history.end()) {
        history.push_back(ahb);
        ++ahbWindowRefCounts[ahb];
    } else if (historyIt + 1 != history.end()) {
        history.erase(historyIt);
        history.push_back(ahb);
    }

    WinTex& src = cit->second;
    bindLsfgProvenance(ahb, src);
    const LsfgFrameProvenance incomingProvenance = src.frameProvenance;
    if (!enqueueLsfgHostDelivery(id, ahb, src)) {
        evictWindowAhbImports(id, ahb);
        if (pendingLsfgHostDeliveryCount_.load(std::memory_order_relaxed) > 0) {
            needsRender.store(true, std::memory_order_release);
            dirtyCV.notify_one();
        }
        return;
    }

    WinTex& wt  = texMap[id];
    if (!wt.isAHB && (wt.img != VK_NULL_HANDLE || wt.stg != VK_NULL_HANDLE))
        destroyWinTex(wt);

    wt.img  = src.img;
    wt.mem  = src.mem;
    wt.view = src.view;
    wt.ds   = src.ds;
    wt.isAHB = true;
    wt.ahb  = ahb;
    wt.descriptorPool = ahbTexPool;
    wt.lastUseSubmissionSerial = src.lastUseSubmissionSerial;
    wt.frameProvenance = src.frameProvenance;
    wt.w    = src.w;
    wt.h    = src.h;

    const bool queuedDeliveryOwnsTransition =
        incomingProvenance.valid
        && incomingProvenance.deliveryId != 0
        && hostDeliveryQueueCapacity() >= MIN_HOST_DELIVERY_QUEUE_CAPACITY;
    // queued delivery owns first AHB transition: do not transfer/clear the
    // import transition on the latest-content texMap path before the queued
    // snapshot selects this AHB.
    if (src.needsTransition && !queuedDeliveryOwnsTransition) {
        wt.needsTransition  = true;
        src.needsTransition = false;
    }

    evictWindowAhbImports(id, ahb);
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::setRenderList(const int64_t* ids, const int* xs, const int* ys, int count) {
    std::lock_guard<std::mutex> lk(renderMutex);
    renderList.resize(count);
    for (int i=0;i<count;i++) renderList[i]={ids[i],xs[i],ys[i]};
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::removeWindow(int64_t id) {
    std::lock_guard<std::mutex> lk(renderMutex);

    auto it = texMap.find(id);
    if (it != texMap.end()) {
        if (!it->second.isAHB) destroyWinTex(it->second);
        else it->second = {};
        texMap.erase(it);
    }

    dropQueuedLsfgHostDeliveriesForWindow(id, "window-removed");
    releaseWindowAhbImports(id);

    renderList.erase(std::remove_if(renderList.begin(),renderList.end(),
        [id](const RenderEntry& e){return e.id==id;}),renderList.end());
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::cleanupAllAHBCache() {
    for (auto& [ahb, wt] : ahbImportCache) {
        if (wt.ds   != VK_NULL_HANDLE) vk_.FreeDescriptorSets(device, ahbTexPool, 1, &wt.ds);
        if (wt.view != VK_NULL_HANDLE) vk_.DestroyImageView(device, wt.view, nullptr);
        if (wt.img  != VK_NULL_HANDLE) vk_.DestroyImage(device, wt.img, nullptr);
        if (wt.mem  != VK_NULL_HANDLE) vk_.FreeMemory(device, wt.mem, nullptr);
        if (wt.stg  != VK_NULL_HANDLE) {
            vk_.DestroyBuffer(device, wt.stg, nullptr);
            vk_.FreeMemory(device, wt.stgMem, nullptr);
        }
        AHardwareBuffer_release(ahb);
    }
    ahbImportCache.clear();

    for (auto& retired : retiredAhbImports) {
        if (retired.texture.ds != VK_NULL_HANDLE)
            vk_.FreeDescriptorSets(device, ahbTexPool, 1, &retired.texture.ds);
        if (retired.texture.view != VK_NULL_HANDLE)
            vk_.DestroyImageView(device, retired.texture.view, nullptr);
        if (retired.texture.img != VK_NULL_HANDLE)
            vk_.DestroyImage(device, retired.texture.img, nullptr);
        if (retired.texture.mem != VK_NULL_HANDLE)
            vk_.FreeMemory(device, retired.texture.mem, nullptr);
        if (retired.texture.stg != VK_NULL_HANDLE) {
            vk_.DestroyBuffer(device, retired.texture.stg, nullptr);
            vk_.FreeMemory(device, retired.texture.stgMem, nullptr);
        }
        AHardwareBuffer_release(retired.ahb);
    }
    retiredAhbImports.clear();
    ahbWindowRefCounts.clear();
    windowAhbs.clear();
    pendingLsfgHostDeliveries_.clear();
    pendingLsfgHostDeliveryCount_.store(0, std::memory_order_relaxed);
    hostSnapshottedLsfgDeliveries_.clear();
}


void VulkanRendererContext::dumpRendererInfo() {
    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice,&props);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "=== RENDERER INFO ===");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "GPU: %s vendorID=0x%x driverVersion=0x%x apiVersion=%d.%d.%d",
        props.deviceName,props.vendorID,props.driverVersion,
        VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),VK_VERSION_PATCH(props.apiVersion));
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Swapchain: %dx%d fmt=%d",swapchainExt.width,swapchainExt.height,(int)swapchainFmt);
    std::string pmList;
    for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "SupportedPresentModes: [%s] current=%d",pmList.c_str(),(int)requestedPresentMode);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Filter: mode=%d (%s)", filterMode, filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Scanout: active=%d gameFrameDelivered=%d scanoutGameSC=%p",
        (int)scanoutActive.load(),(int)gameFrameDelivered.load(),scanoutGameSC);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Surface: %dx%d container: %dx%d",
        surfaceWidth,surfaceHeight,containerWidth,containerHeight);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,"=== END RENDERER INFO ===");
}

void VulkanRendererContext::setFilterMode(int mode) {
    RLOG("setFilterMode: %d -> %d (%s->%s)", filterMode, mode,
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR", mode==2?(cubicSupported?"CUBIC":"LINEAR"):mode==1?"NEAREST":"LINEAR");
    if (filterMode==mode) { RLOG("setFilterMode: already set, skipping"); return; }
    filterMode=mode;
    vk_.DeviceWaitIdle(device);
    if (sampler!=VK_NULL_HANDLE){vk_.DestroySampler(device,sampler,nullptr);sampler=VK_NULL_HANDLE;}
    createSampler();
    auto updateDS=[&](VkDescriptorSet ds, VkImageView view){
        if(ds==VK_NULL_HANDLE||view==VK_NULL_HANDLE) return;
        VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        dii.imageView=view; dii.sampler=sampler;
        VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr.dstSet=ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wr.descriptorCount=1; wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    };
    
    for (auto& [id,wt]:texMap) updateDS(wt.ds, wt.view);

    for (auto& [ahb,wt]:ahbImportCache) updateDS(wt.ds, wt.view);
    if (cursorDS!=VK_NULL_HANDLE&&cursorView!=VK_NULL_HANDLE) updateDS(cursorDS, cursorView);
    needsRender.store(true); dirtyCV.notify_one();
}

void VulkanRendererContext::setSwapRB(bool enabled) {
    if (swapRB == enabled) return;
    swapRB = enabled;
    RLOG("setSwapRB: %d", (int)swapRB);


}

void VulkanRendererContext::setEffect(int effectId, float sharpness, int effectMask, float brightness, float contrast, float gamma) {
    activeEffectId = effectId;
    activeSharpness = std::max(0.0f, std::min(1.0f, sharpness));
    activeEffectMask = effectMask;
    activeBrightness = std::max(-1.0f, std::min(1.0f, brightness));
    activeContrast = std::max(-1.0f, std::min(1.0f, contrast));
    activeGamma = std::max(0.1f, std::min(4.0f, gamma));
    RLOG("setEffect: id=%d sharpness=%.3f mask=%d brightness=%.3f contrast=%.3f gamma=%.3f",
        activeEffectId, activeSharpness, activeEffectMask, activeBrightness, activeContrast, activeGamma);
    needsRender.store(true);
    dirtyCV.notify_one();
}

void VulkanRendererContext::setLsfgFrameQueue(bool enabled, uint32_t target) {
    target = std::min<uint32_t>(2, target);
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);

    const bool previousEnabled =
        lsfgFrameQueueEnabled_.load(std::memory_order_acquire);
    const uint32_t previousTarget =
        std::min<uint32_t>(2, lsfgFrameQueueTarget_.load(std::memory_order_acquire));
    const uint64_t requestSerial =
        frameQueueConfigRequestSerial_.fetch_add(1, std::memory_order_relaxed) + 1;
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=config-request request_serial=%" PRIu64
        " previous_enabled=%d previous_target=%u requested_enabled=%d requested_target=%u",
        requestSerial,
        previousEnabled ? 1 : 0,
        previousTarget,
        enabled ? 1 : 0,
        target);
    if (previousEnabled == enabled && previousTarget == target)
        return;

    // Configuration changes are explicit recovery boundaries. Drain only
    // compositor GPU work; display confirmation remains telemetry-only.
    drainFrameQueueSubmissions("config-transition");
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        dropQueuedLsfgHostDeliveries("frame-queue-config-transition");
    }

    resetFrameQueueTelemetry();
    frameQueueSmoothRuntimeSuppressed_.store(false, std::memory_order_release);
    frameQueueSmoothFifoFallback_.store(false, std::memory_order_release);
    lsfgFrameQueueTarget_.store(target, std::memory_order_release);
    lsfgFrameQueueEnabled_.store(enabled, std::memory_order_release);
    currentFrame = 0;

    const uint32_t effectiveTarget =
        enabled ? effectiveFrameQueueTarget() : 0;
    const bool smoothFallback =
        enabled && target == 2 && effectiveTarget < 2;
    const char* mode = !enabled ? "off"
        : (target == 0 ? "unbuffered"
        : (target == 1 ? "balanced" : "smooth"));
    const char* fallbackReason = !smoothFallback ? "none"
        : (frameQueueSmoothFifoFallback_.load(std::memory_order_acquire)
            ? "fifo-present-blocking"
            : "present-stall");
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=config request_serial=%llu telemetry_epoch=%llu enabled=%d requested_target=%u effective_target=%u "
        "mode=%s smooth_fallback=%d fallback_reason=%s active_slots=%u "
        "delivery_queue_capacity=%u delivery_handoff=%s effective_gpu_target=%u "
        "gpu_outstanding=%u max_gpu_outstanding=%u retirement_waits=%llu "
        "retirement_wait_ms=%.3f presented=%llu",
        static_cast<unsigned long long>(requestSerial),
        static_cast<unsigned long long>(
            frameQueueTelemetryEpoch_.load(std::memory_order_relaxed)),
        enabled ? 1 : 0,
        target,
        effectiveTarget,
        mode,
        smoothFallback ? 1 : 0,
        fallbackReason,
        activeFrameSlotCount(),
        hostDeliveryQueueCapacity(),
        enabled ? "ordered-buffered" : "ordered-minimum",
        effectiveTarget,
        countOutstandingFrameSubmissions(true),
        frameQueueMaxGpuOutstanding_.load(std::memory_order_relaxed),
        static_cast<unsigned long long>(
            frameQueueRetirementWaitTotal_.load(std::memory_order_relaxed)),
        static_cast<double>(
            frameQueueRetirementWaitNsTotal_.load(std::memory_order_relaxed))
            / 1000000.0,
        static_cast<unsigned long long>(
            frameQueuePresentedTotal_.load(std::memory_order_relaxed)));
    if (!enabled) {
        __android_log_print(
            ANDROID_LOG_INFO, "LSFG_HOST_DELIVERY",
            "event=delivery-policy delivery_handoff=ordered-minimum "
            "delivery_queue_capacity=%u effective_gpu_target=0 "
            "graphics_family=%u family_queue_count=%u present_capable_families=%u "
            "alternate_present_family=%d second_same_family_queue_available=%d "
            "present_queue_split=%d present_queue_index=%u",
            hostDeliveryQueueCapacity(),
            graphicsQueueFamilyIndex,
            graphicsQueueFamilyQueueCount,
            presentCapableQueueFamilyCount,
            alternatePresentQueueFamilyAvailable ? 1 : 0,
            graphicsQueueFamilyQueueCount > 1 ? 1 : 0,
            hostSplitPresentQueueActive_ ? 1 : 0,
            hostPresentQueueIndex_);
    }
}

void VulkanRendererContext::setPresentMode(VkPresentModeKHR mode) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    bool supported = false;
    for (auto pm : availablePresentModes) {
        if (pm == mode) { supported = true; break; }
    }
    const VkPresentModeKHR target =
        supported ? mode : VK_PRESENT_MODE_FIFO_KHR;
    __android_log_print(
        ANDROID_LOG_INFO, "LSFG_FRAME_QUEUE",
        "event=present-mode-request requested_raw=%d requested_supported=%d "
        "pending_present_mode=%d previous_pending_present_mode=%d active_present_mode=%d "
        "swapchain_generation=%llu",
        static_cast<int>(mode), supported ? 1 : 0,
        static_cast<int>(target), static_cast<int>(requestedPresentMode),
        static_cast<int>(activePresentMode),
        static_cast<unsigned long long>(hostSwapchainGeneration_));
    if (requestedPresentMode == target) {
        RLOG("setPresentMode: already set, skipping");
        return;
    }
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        dropQueuedLsfgHostDeliveries("present-mode-transition");
    }
    requestedPresentMode = target;
    frameQueueSmoothRuntimeSuppressed_.store(false, std::memory_order_release);
    frameQueueSmoothPressureStrikes_.store(0, std::memory_order_relaxed);
    frameQueueSmoothFifoFallback_.store(false, std::memory_order_release);
    fbResized.store(true, std::memory_order_release);
    dirtyCV.notify_one();
}

std::vector<int> VulkanRendererContext::getSupportedPresentModes() const {
    std::vector<int> out;
    for (auto pm:availablePresentModes) out.push_back((int)pm);
    return out;
}

#pragma GCC diagnostic pop
