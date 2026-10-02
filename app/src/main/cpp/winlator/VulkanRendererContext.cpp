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
#include "window_vert.h"
#include "window_frag.h"

extern "C" __attribute__((used, visibility("default")))
const char gamenative_vulkan_renderer_build_marker[] =
    "gamenative-host-display-confirmation-v2";

namespace {
constexpr char LSFG_PROVENANCE_SOCKET[] = "gamenative-lsfg-provenance-v1";
constexpr uint32_t kLsfgFrameProvenanceMagic = 0x4c534650U; // "LSFP"
constexpr uint16_t kLsfgFrameProvenanceVersion = 1;
constexpr std::size_t kMaxPendingLsfgProvenance = 512;
constexpr std::size_t kMaxPendingHostConfirmations = 256;
constexpr int kLsfgProvenanceReceiveBufferBytes = 1024 * 1024;

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
};
static_assert(sizeof(LsfgFrameProvenancePacket) == 56);

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
    isRunning = true;
    renderThread = std::thread(&VulkanRendererContext::renderLoop, this);
}

VulkanRendererContext::~VulkanRendererContext() {
    isRunning = false; dirtyCV.notify_all();
    if (renderThread.joinable()) renderThread.join();
    std::lock_guard<std::mutex> lk(renderMutex);
    vk_.DeviceWaitIdle(device);
    submissionTimeline.completeAllFrames();
    flushHostDisplayConfirmationsUnknown("renderer-destroy");
    closeLsfgProvenanceSocket();

    for (auto& [id, wt] : texMap) {
        if (wt.isAHB) wt = {};
        else destroyWinTex(wt);
    }
    texMap.clear();
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
        vk_.DestroyFence(device, inFlightFences[i], nullptr);
    }
    vk_.DestroyCommandPool(device, cmdPool, nullptr);
    vk_.DestroyRenderPass(device, renderPass, nullptr);
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
    for (auto d : devs) {
        uint32_t qCount = 0;
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; i++) {
            VkBool32 present = VK_FALSE;
            vk_.GetPhysicalDeviceSurfaceSupportKHR(d, i, surface, &present);
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                physicalDevice = d;
                graphicsQueueFamilyIndex = i;
                return;
            }
        }
    }
    if (n > 0) physicalDevice = devs[0];
}

void VulkanRendererContext::createLogicalDevice() {
    float p=1.f;
    VkDeviceQueueCreateInfo qi{}; qi.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qi.queueFamilyIndex=graphicsQueueFamilyIndex; qi.queueCount=1; qi.pQueuePriorities=&p;

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
    if (vk_.CreateDevice(physicalDevice,&ci,nullptr,&device)!=VK_SUCCESS) throw std::runtime_error("device");
    vk_.GetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
    loadDeviceDispatch();
    if (!vk_.GetPastPresentationTimingGOOGLE)
        hostGoogleDisplayTimingEnabled = false;
    if (!vk_.WaitForPresentKHR)
        hostPresentWaitEnabled = false;
    vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);

    vk_.GetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);
    maxAnisotropy = props.limits.maxSamplerAnisotropy;
    __android_log_print(ANDROID_LOG_INFO, "LSFG_HOST_DISPLAY",
        "capability google_display_timing=%d present_wait=%d",
        hostGoogleDisplayTimingEnabled ? 1 : 0,
        hostPresentWaitEnabled ? 1 : 0);
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
    for (auto pm:availablePresentModes) if(pm==requestedPresentMode){presentMode=pm;break;}
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
    ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=pre;
    ci.compositeAlpha=compositeAlpha; ci.presentMode=presentMode; ci.clipped=VK_TRUE;
    ci.oldSwapchain=oldSwapchain;
    if (vk_.CreateSwapchainKHR(device,&ci,nullptr,&swapchain)!=VK_SUCCESS) throw std::runtime_error("swapchain");
    RLOG("swapchain created: %dx%d format=%d presentMode=%d compositeAlpha=%d imgCount=%u",
        swapchainExt.width,swapchainExt.height,(int)swapchainFmt,(int)presentMode,(int)compositeAlpha,imgCount);
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
    const bool fifoPresent = requestedPresentMode == VK_PRESENT_MODE_FIFO_KHR;
    frameQueueSmoothFifoFallback_.store(
        requested == 2 && fifoPresent, std::memory_order_release);

    if (requested == 2 && fifoPresent)
        return 1;
    if (requested == 2
            && frameQueueSmoothRuntimeSuppressed_.load(std::memory_order_acquire))
        return 1;

    return requested;
}

void VulkanRendererContext::resetFrameQueueTelemetry() {
    frameQueuePresentedTotal_.store(0, std::memory_order_relaxed);
    frameQueueRetirementWaitTotal_.store(0, std::memory_order_relaxed);
    frameQueueRetirementWaitNsTotal_.store(0, std::memory_order_relaxed);
    frameQueueAcquireNsTotal_.store(0, std::memory_order_relaxed);
    frameQueuePresentNsTotal_.store(0, std::memory_order_relaxed);
    frameQueuePresentSamples_.store(0, std::memory_order_relaxed);
    frameQueueMaxGpuOutstanding_.store(0, std::memory_order_relaxed);
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
            || requestedPresentMode == VK_PRESENT_MODE_FIFO_KHR
            || frameQueueSmoothRuntimeSuppressed_.load(std::memory_order_acquire))
        return;

    if (presentNs >= SMOOTH_PRESENT_STALL_NS) {
        frameQueueSmoothRuntimeSuppressed_.store(true, std::memory_order_release);
        __android_log_print(
            ANDROID_LOG_WARN, "LSFG_FRAME_QUEUE",
            "event=smooth-runtime-fallback reason=present-stall "
            "present_ms=%.3f threshold_ms=%.3f effective_target=1",
            static_cast<double>(presentNs) / 1000000.0,
            static_cast<double>(SMOOTH_PRESENT_STALL_NS) / 1000000.0);
    }
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

VkResult VulkanRendererContext::presentHostFrame(
        const PendingHostPresent& present) {
    VkPresentIdKHR presentIdInfo{};
    presentIdInfo.sType = VK_STRUCTURE_TYPE_PRESENT_ID_KHR;
    presentIdInfo.swapchainCount = 1;
    presentIdInfo.pPresentIds = &present.hostPresentId;

    VkPresentTimeGOOGLE googlePresentTime{};
    googlePresentTime.presentID = present.googlePresentId;
    googlePresentTime.desiredPresentTime = 0;
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

    const auto presentStart = std::chrono::steady_clock::now();
    VkResult result = VK_SUCCESS;
    {
        std::lock_guard<std::mutex> queueLock(graphicsQueueMutex_);
        result = vk_.QueuePresentKHR(graphicsQueue, &pi);
    }
    const uint64_t presentNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - presentStart).count());

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
        recordHostPresent(
            present.hostPresentId,
            present.googlePresentId,
            present.backend,
            present.frameProvenance);
        updateSmoothQueuePressure(presentNs);
        pollHostDisplayConfirmations();

        if (sample <= 8 || sample % 120 == 0) {
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
                "mode=%s smooth_fallback=%d fallback_reason=%s active_slots=%u "
                "gpu_outstanding=%u max_gpu_outstanding=%u acquire_ms=%.3f "
                "present_ms=%.3f retirement_waits=%llu retirement_wait_ms=%.3f "
                "presented=%llu",
                static_cast<unsigned long long>(
                    frameQueueTelemetryEpoch_.load(std::memory_order_relaxed)),
                enabled ? 1 : 0,
                requestedTarget,
                effectiveTarget,
                mode,
                smoothFallback ? 1 : 0,
                fallbackReason,
                activeFrameSlotCount(),
                present.gpuOutstanding,
                frameQueueMaxGpuOutstanding_.load(std::memory_order_relaxed),
                static_cast<double>(present.acquireNs) / 1000000.0,
                static_cast<double>(presentNs) / 1000000.0,
                static_cast<unsigned long long>(
                    frameQueueRetirementWaitTotal_.load(std::memory_order_relaxed)),
                static_cast<double>(
                    frameQueueRetirementWaitNsTotal_.load(std::memory_order_relaxed))
                    / 1000000.0,
                static_cast<unsigned long long>(presented));
        }
    }
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_ERROR_SURFACE_LOST_KHR)
        fbResized.store(true, std::memory_order_release);
    return result;
}

void VulkanRendererContext::cleanupSwapchain() {
    retireFrameQueuePresentSemaphores();
    flushHostDisplayConfirmationsUnknown("swapchain-recreate");
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
    short curW, short curH, bool curVis)
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
              return !isRunning||(!surfaceDetached.load()&&(needsRender.load()||fbResized.load()))||cursorMoved.load(); }); }
        if (!isRunning) break;

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

    const uint32_t activeSlots = activeFrameSlotCount();
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
    const uint32_t frameQueueTarget = frameQueueEnabled
        ? effectiveFrameQueueTarget() : 0;
    if (!toXr) {
        enforceFrameQueueSubmissionBudget(frameQueueTarget);
    }

    uint32_t imgIdx = 0;
    uint64_t acquireNs = 0;
    VkResult res = VK_SUCCESS;
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
    }

    vk_.ResetCommandBuffer(cmdBufs[currentFrame],0);

    float ox,oy,sx,sy,cw,ch;
    short ptrX,ptrY,curHotX,curHotY,curW,curH; bool curVis;
    VkBuffer curUpload=VK_NULL_HANDLE; bool hasCurUpload=false;

    {
        std::lock_guard<std::mutex> lk(renderMutex);

        // Fence observation above advances the completion timeline. Reclaim
        // only resources whose recorded last GPU use is now known complete.
        reclaimRetiredAhbImports();
        reclaimRetiredWindowTextures();

        ox=sceneOffsetX; oy=sceneOffsetY; sx=sceneScaleX; sy=sceneScaleY;
        cw=(float)containerWidth; ch=(float)containerHeight;
        ptrX=(short)pointerX.load(); ptrY=(short)pointerY.load();
        curHotX=cursorHotX; curHotY=cursorHotY; curW=cursorTexW; curH=cursorTexH;
        curVis=cursorVisible.load();

        frameDraws.clear();
        for (auto& re:renderList) {
            auto it=texMap.find(re.id);
            if (it==texMap.end()) continue;
            WinTex& wt=it->second;
            if (wt.ds==VK_NULL_HANDLE) continue;
            DrawEntry de{};
            de.ownerId=re.id;
            de.img=wt.img;
            de.ds=wt.ds;
            de.x=re.x; de.y=re.y; de.w=wt.w; de.h=wt.h;
            de.isAHB=wt.isAHB;
            de.ahb=wt.ahb;
            de.frameProvenance=wt.frameProvenance;
            if (wt.needsTransition) { de.needsTransition=true; wt.needsTransition=false; }
            if (wt.dirty && !wt.isAHB && wt.stg!=VK_NULL_HANDLE) {
                de.upload=wt.stg;
                wt.dirty=false;
            } else if (wt.isAHB) {
                wt.dirty=false;
            }
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
        ox,oy,sx,sy,cw,ch,ptrX,ptrY,curHotX,curHotY,curW,curH,effectiveCurVis);

    VkSemaphore wSem[]={imgAvailSems[currentFrame]};
    VkSemaphore signalSemaphore = renderDoneSems[currentFrame];
    if (!toXr && frameQueueEnabled) {
        if (imgIdx >= frameQueuePresentSems_.size()
                || frameQueuePresentSems_[imgIdx] == VK_NULL_HANDLE) {
            fbResized.store(true, std::memory_order_release);
            return;
        }
        signalSemaphore = frameQueuePresentSems_[imgIdx];
    }
    VkSemaphore sSem[]={signalSemaphore};
    VkPipelineStageFlags wStage[]={VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
    if (!toXr) {
        si.waitSemaphoreCount=1; si.pWaitSemaphores=wSem; si.pWaitDstStageMask=wStage;
        si.signalSemaphoreCount=1; si.pSignalSemaphores=sSem;
    }
    si.commandBufferCount=1; si.pCommandBuffers=&cmdBufs[currentFrame];

    const uint64_t submissionSerial = submissionTimeline.nextSubmissionSerial();
    vk_.ResetFences(device,1,&inFlightFences[currentFrame]);
    VkResult submitResult = VK_SUCCESS;
    {
        std::lock_guard<std::mutex> queueLock(graphicsQueueMutex_);
        submitResult = vk_.QueueSubmit(
            graphicsQueue,1,&si,inFlightFences[currentFrame]);
    }
    if (submitResult!=VK_SUCCESS) {
        vk_.DestroyFence(device,inFlightFences[currentFrame],nullptr);
        VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
        vk_.CreateFence(device,&fi,nullptr,&inFlightFences[currentFrame]);
        return;
    }
    submissionTimeline.submitFrame(currentFrame, submissionSerial);
    renderSubmissionSerial.store(submissionSerial, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        markDrawResourcesSubmitted(submissionSerial);
    }
    if (!toXr) {
        std::vector<LsfgFrameProvenance> frameProvenance;
        std::unordered_set<uint64_t> seenDeliveries;
        for (const auto& draw : frameDraws) {
            if (!draw.frameProvenance.valid || draw.frameProvenance.deliveryId == 0)
                continue;
            if (seenDeliveries.insert(draw.frameProvenance.deliveryId).second)
                frameProvenance.push_back(draw.frameProvenance);
        }

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
        res = presentHostFrame(PendingHostPresent{
            .frameSlot = currentFrame,
            .imageIndex = imgIdx,
            .swapchain = swapchain,
            .waitSemaphore = signalSemaphore,
            .hostPresentId = hostPresentId,
            .googlePresentId = googlePresentId,
            .backend = confirmationBackend,
            .frameProvenance = std::move(frameProvenance),
            .acquireNs = acquireNs,
            .gpuOutstanding = gpuOutstanding,
        });
        if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR)
            fbResized.store(true);
    } else {
        // The XR session samples xrAhb from its own GL context with no fence handoff;
        // blocking here means the buffer is fully written whenever this thread is idle,
        // leaving only the active write window unsynchronized (a tear, not stale data).
        if (vk_.WaitForFences(
                device,1,&inFlightFences[currentFrame],VK_TRUE,UINT64_MAX) == VK_SUCCESS)
            submissionTimeline.completeFrame(currentFrame);
    }
    currentFrame=(currentFrame+1)%activeFrameSlotCount();
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
            lsfgSwapchainImageAhbs.clear();
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
            " matched_delivery_id=%" PRIu64,
            provenanceRxTotal_,
            provenanceMatchTotal_,
            provenanceMissTotal_,
            texture.frameProvenance.deliveryId);
    }
}

void VulkanRendererContext::emitHostDisplayConfirmation(
        const HostDisplayConfirmation& confirmation,
        bool confirmed,
        bool unknown,
        const char* reason) {
    if (confirmation.frameProvenance.empty()) return;
    for (const auto& provenance : confirmation.frameProvenance) {
        __android_log_print(
            ANDROID_LOG_INFO,
            "LSFG_HOST_DISPLAY",
            "host_present_id=%" PRIu64 " host_wsi_accepted=%d "
            "host_display_confirmed=%d host_display_unknown=%d "
            "host_wsi_accepted_total=%" PRIu64
            " host_display_confirmed_total=%" PRIu64
            " host_display_unknown_total=%" PRIu64
            " display_delivery_ratio=%.4f "
            "confirmation_backend=%s delivery_id=%" PRIu64
            " context_epoch=%" PRIu64
            " kind=%s source_index=%" PRIu64 " swapchain_image=%u "
            "interpolation_index=%u interpolation_count=%u reason=%s",
            confirmation.hostPresentId,
            confirmation.hostPresentId != 0 ? 1 : 0,
            confirmed ? 1 : 0,
            unknown ? 1 : 0,
            hostWsiAccepted_,
            hostDisplayConfirmed_,
            hostDisplayUnknown_,
            hostWsiAccepted_ > 0
                ? static_cast<double>(hostDisplayConfirmed_)
                    / static_cast<double>(hostWsiAccepted_)
                : 0.0,
            hostDisplayBackendName(confirmation.backend),
            provenance.deliveryId,
            provenance.contextEpoch,
            provenanceKindName(provenance.kind),
            provenance.sourceIndex,
            provenance.swapchainImageIndex,
            static_cast<unsigned>(provenance.interpolationIndex),
            provenance.interpolationCount,
            reason ? reason : "none");
    }
}

void VulkanRendererContext::recordHostPresent(
        uint64_t hostPresentId,
        uint32_t googlePresentId,
        HostDisplayConfirmationBackend backend,
        const std::vector<LsfgFrameProvenance>& frameProvenance) {
    if (frameProvenance.empty()) return;
    HostDisplayConfirmation confirmation{};
    confirmation.hostPresentId = hostPresentId;
    confirmation.googlePresentId = googlePresentId;
    confirmation.backend = backend;
    confirmation.frameProvenance = frameProvenance;
    ++hostWsiAccepted_;

    if (backend == HostDisplayConfirmationBackend::WsiAccepted) {
        ++hostDisplayUnknown_;
        emitHostDisplayConfirmation(
            confirmation, false, true, "wsi-accepted-only");
        return;
    }

    pendingHostDisplayConfirmations.push_back(std::move(confirmation));
    while (pendingHostDisplayConfirmations.size() > kMaxPendingHostConfirmations) {
        ++hostDisplayUnknown_;
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
                                && pending.googlePresentId == timing.presentID;
                        });
                    if (it == pendingHostDisplayConfirmations.end()) continue;
                    const bool confirmed = timing.actualPresentTime != 0;
                    if (confirmed) ++hostDisplayConfirmed_;
                    else ++hostDisplayUnknown_;
                    emitHostDisplayConfirmation(
                        *it, confirmed, !confirmed,
                        confirmed ? "actual-present-time" : "no-actual-present-time");
                    pendingHostDisplayConfirmations.erase(it);
                }
            }
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

    if (src.needsTransition) {
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
    if (previousEnabled == enabled && previousTarget == target)
        return;

    // Configuration changes are explicit recovery boundaries. Drain only
    // compositor GPU work; display confirmation remains telemetry-only.
    drainFrameQueueSubmissions("config-transition");

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
        "event=config telemetry_epoch=%llu enabled=%d requested_target=%u effective_target=%u "
        "mode=%s smooth_fallback=%d fallback_reason=%s active_slots=%u "
        "gpu_outstanding=%u max_gpu_outstanding=%u retirement_waits=%llu "
        "retirement_wait_ms=%.3f presented=%llu",
        static_cast<unsigned long long>(
            frameQueueTelemetryEpoch_.load(std::memory_order_relaxed)),
        enabled ? 1 : 0,
        target,
        effectiveTarget,
        mode,
        smoothFallback ? 1 : 0,
        fallbackReason,
        activeFrameSlotCount(),
        countOutstandingFrameSubmissions(true),
        frameQueueMaxGpuOutstanding_.load(std::memory_order_relaxed),
        static_cast<unsigned long long>(
            frameQueueRetirementWaitTotal_.load(std::memory_order_relaxed)),
        static_cast<double>(
            frameQueueRetirementWaitNsTotal_.load(std::memory_order_relaxed))
            / 1000000.0,
        static_cast<unsigned long long>(
            frameQueuePresentedTotal_.load(std::memory_order_relaxed)));
}

void VulkanRendererContext::setPresentMode(VkPresentModeKHR mode) {
    bool supported = false;
    for (auto pm : availablePresentModes) if (pm == mode) { supported = true; break; }
    VkPresentModeKHR target = supported ? mode : VK_PRESENT_MODE_FIFO_KHR;
    RLOG("setPresentMode: requested=%d supported=%d -> applying=%d",
        (int)mode, (int)supported, (int)target);
    if (requestedPresentMode==target) { RLOG("setPresentMode: already set, skipping"); return; }
    frameQueueSmoothRuntimeSuppressed_.store(false, std::memory_order_release);
    frameQueueSmoothFifoFallback_.store(false, std::memory_order_release);
    requestedPresentMode=target;
    fbResized.store(true); dirtyCV.notify_one();
}

std::vector<int> VulkanRendererContext::getSupportedPresentModes() const {
    std::vector<int> out;
    for (auto pm:availablePresentModes) out.push_back((int)pm);
    return out;
}

#pragma GCC diagnostic pop
