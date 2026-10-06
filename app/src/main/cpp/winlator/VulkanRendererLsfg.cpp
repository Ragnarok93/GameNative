#include "VulkanRendererContext.h"
#include "../lsfg/vk_dispatch.h"
#include "../lsfg/vkr_lsfg.h"

#include <cstring>
#include <algorithm>
#include <chrono>
#include <string>
#include <cassert>

void VulkanRendererContext::createCompositePass() {
    VkAttachmentDescription att{};
    att.format = swapchainFmt;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};

    VkSubpassDescription sp{};
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sp.colorAttachmentCount = 1;
    sp.pColorAttachments = &ref;

    VkSubpassDependency deps[2]{};
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT;

    VkRenderPassCreateInfo rci{};
    rci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rci.attachmentCount = 1;
    rci.pAttachments = &att;
    rci.subpassCount = 1;
    rci.pSubpasses = &sp;
    rci.dependencyCount = 2;
    rci.pDependencies = deps;

    if (vk_.CreateRenderPass(device, &rci, nullptr, &compositePass) != VK_SUCCESS) {
        RLOG_E("Failed to create composite pass");
    }
}

void VulkanRendererContext::destroyOneComposite(VkCompositeTarget& c) {
    if (c.framebuffer != VK_NULL_HANDLE) { vk_.DestroyFramebuffer(device, c.framebuffer, nullptr); c.framebuffer = VK_NULL_HANDLE; }
    if (c.view != VK_NULL_HANDLE)        { vk_.DestroyImageView(device, c.view, nullptr); c.view = VK_NULL_HANDLE; }
    if (c.image != VK_NULL_HANDLE)       { vk_.DestroyImage(device, c.image, nullptr); c.image = VK_NULL_HANDLE; }
    if (c.memory != VK_NULL_HANDLE)      { vk_.FreeMemory(device, c.memory, nullptr); c.memory = VK_NULL_HANDLE; }
    c.width = c.height = 0;
}

void VulkanRendererContext::destroyCompositeTargets() {
    for (uint32_t i = 0; i < VK_MAX_COMPOSITE_TARGETS; i++) {
        destroyOneComposite(composite[i]);
    }
    compositeCount = 0;
    compositeBuilt = false;
}

bool VulkanRendererContext::createOneComposite(VkCompositeTarget& c, uint32_t w, uint32_t h) {
    c.width = w;
    c.height = h;

    VkImageCreateInfo ic{};
    ic.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ic.imageType = VK_IMAGE_TYPE_2D;
    ic.format = swapchainFmt;
    ic.extent.width = w;
    ic.extent.height = h;
    ic.extent.depth = 1;
    ic.mipLevels = 1;
    ic.arrayLayers = 1;
    ic.samples = VK_SAMPLE_COUNT_1_BIT;
    ic.tiling = VK_IMAGE_TILING_OPTIMAL;
    ic.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
             | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ic.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device, &ic, nullptr, &c.image) != VK_SUCCESS) return false;

    VkMemoryRequirements mr;
    vk_.GetImageMemoryRequirements(device, c.image, &mr);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    try {
        ai.memoryTypeIndex = findMemType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    } catch (...) {
        vk_.DestroyImage(device, c.image, nullptr);
        c.image = VK_NULL_HANDLE;
        return false;
    }
    if (vk_.AllocateMemory(device, &ai, nullptr, &c.memory) != VK_SUCCESS) {
        vk_.DestroyImage(device, c.image, nullptr);
        c.image = VK_NULL_HANDLE;
        return false;
    }
    if (vk_.BindImageMemory(device, c.image, c.memory, 0) != VK_SUCCESS) {
        destroyOneComposite(c);
        return false;
    }

    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = c.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ic.format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    if (vk_.CreateImageView(device, &vi, nullptr, &c.view) != VK_SUCCESS) {
        vk_.FreeMemory(device, c.memory, nullptr);
        vk_.DestroyImage(device, c.image, nullptr);
        c.memory = VK_NULL_HANDLE;
        c.image = VK_NULL_HANDLE;
        return false;
    }

    if (compositePass == VK_NULL_HANDLE) {
        createCompositePass();
    }

    VkFramebufferCreateInfo fbci{};
    fbci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fbci.renderPass = compositePass;
    fbci.attachmentCount = 1;
    fbci.pAttachments = &c.view;
    fbci.width = w;
    fbci.height = h;
    fbci.layers = 1;
    if (vk_.CreateFramebuffer(device, &fbci, nullptr, &c.framebuffer) != VK_SUCCESS) {
        vk_.DestroyImageView(device, c.view, nullptr);
        vk_.FreeMemory(device, c.memory, nullptr);
        vk_.DestroyImage(device, c.image, nullptr);
        c.view = VK_NULL_HANDLE;
        c.memory = VK_NULL_HANDLE;
        c.image = VK_NULL_HANDLE;
        return false;
    }

    return true;
}

bool VulkanRendererContext::createCompositeTargets(uint32_t w, uint32_t h, uint32_t count) {
    if (count == 0 || count > VK_MAX_COMPOSITE_TARGETS) return false;
    if (compositeBuilt && compositeCount == count
        && composite[0].width == w && composite[0].height == h) {
        return true;
    }

    destroyCompositeTargets();
    for (uint32_t i = 0; i < count; i++) {
        if (!createOneComposite(composite[i], w, h)) {
            destroyCompositeTargets();
            return false;
        }
    }
    compositeCount = count;
    compositeBuilt = true;
    return true;
}

void VulkanRendererContext::destroyLsfg() {
    if (!lsfg) return;
    emitNativeLsfgPipelineTelemetry("runtime-destroy");
    vkr_lsfg_destroy(lsfg);
    lsfg = nullptr;
    framegenRealFrames = 0;
    framegenMadeFrames = 0;
    nativeGeneratedPresentedFrames_.store(0);
    presentedFrames.store(0);
    nativeSourceReceived_.store(0);
    nativeSourceWsiSubmitted_.store(0);
    nativeSourceWsiAccepted_.store(0);
    nativeSourceDisplayConfirmed_.store(0);
    nativeGeneratedRequested_.store(0);
    nativeGeneratedAdmitted_.store(0);
    nativeGeneratedDispatched_.store(0);
    nativeGeneratedCompleted_.store(0);
    nativeGeneratedWsiSubmitted_.store(0);
    nativeGeneratedWsiAccepted_.store(0);
    nativeGeneratedDisplayConfirmed_.store(0);
    nativeGeneratedDisplayConfirmedEpoch_.store(0);
    nativeSourceDisplayConfirmedEpoch_.store(0);
    nativeGeneratedDroppedBefore_.store(0);
    nativeGeneratedDroppedAfter_.store(0);
    nativeGeneratedSuperseded_.store(0);
    nativeGeneratedStale_.store(0);
    nativeGeneratedDeadlineRejected_.store(0);
    nativeGeneratedWsiRejected_.store(0);
    nativeGeneratedBacklogRejected_.store(0);
    nativeGpuCompletionLatencyNsTotal_.store(0);
    nativeGpuCompletionSamples_.store(0);
    nativeHostWaitNsTotal_.store(0);
    nativeHostWaitSamples_.store(0);
    nativePresentRateSampleNs_ = 0;
    nativePresentRateSourceAccepted_ = 0;
    nativePresentRateGeneratedAccepted_ = 0;
    nativePresentRateSourceConfirmed_ = 0;
    nativePresentRateGeneratedConfirmed_ = 0;
    nativeSourceWsiFps_ = 0.0;
    nativeGeneratedWsiFps_ = 0.0;
    nativeOutputWsiFps_ = 0.0;
    nativeOutputConfirmedFps_ = 0.0;
    nativeGeneratedSubmittedByFrame_.fill(0);
    nativeSubmissionStartedNs_.fill(0);
    nativeLastContextReuseRevision_ = UINT64_MAX;
    framegenSupported = false;
    nativeLsfgReady_.store(false, std::memory_order_release);
}

void VulkanRendererContext::createLsfg() {
    if (lsfg || lsfgCachePath.empty() || !device || !physicalDevice) return;
    if (!nativeComputeSupported_) {
        RLOG_E("LSFG_NATIVE: event=initialization_failed reason=required-device-features");
        return;
    }

    if (!nativeVulkanDispatchLoaded_) {
        if (!vkd_load(instance, device, gipa)) {
            RLOG_E("Native LSFG dispatch unavailable; native backend remains inert");
            return;
        }
        nativeVulkanDispatchLoaded_ = true;
    }
    if (!vkd.CreateComputePipelines) {
        RLOG_E("Native LSFG compute dispatch unavailable; native backend remains inert");
        return;
    }

    lsfg = vkr_lsfg_create(device, physicalDevice, lsfgCachePath.c_str());
    if (!lsfg) {
        RLOG_E("LSFG shaders unavailable at %s; frame generation stays off", lsfgCachePath.c_str());
        return;
    }
    const bool formatSupported = compositeFormatSupported();
    framegenSupported = formatSupported && nativeSwapchainTransferSupported_;
    nativeRuntimeSessionId_ = static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
    VkPhysicalDeviceProperties nativeProps{};
    vkd.GetPhysicalDeviceProperties(physicalDevice, &nativeProps);
    RLOG(
        "LSFG_NATIVE_CONTEXT: event=capability_probe surface=%d vulkan_device=%d compute=%d "
        "compute_pipeline=%d composite_format=%d swapchain_transfer=%d sync=binary-semaphore+fence "
        "vendor_id=0x%04x device_id=0x%04x driver_version=%u api_version=%u "
        "cache_path_present=%d supported=%d",
        surface != VK_NULL_HANDLE ? 1 : 0,
        physicalDevice != VK_NULL_HANDLE && device != VK_NULL_HANDLE ? 1 : 0,
        nativeComputeSupported_ ? 1 : 0,
        vkd.CreateComputePipelines ? 1 : 0,
        formatSupported ? 1 : 0,
        nativeSwapchainTransferSupported_ ? 1 : 0,
        nativeProps.vendorID,
        nativeProps.deviceID,
        nativeProps.driverVersion,
        nativeProps.apiVersion,
        !lsfgCachePath.empty() ? 1 : 0,
        framegenSupported ? 1 : 0);
    if (!framegenSupported) {
        nativeLsfgReady_.store(false, std::memory_order_release);
        return;
    }
    nativeLsfgReady_.store(true, std::memory_order_release);

    vkr_lsfg_configure(lsfg, framegenMultiplier ? framegenMultiplier : 2u,
                       framegenTargetRate,
                       framegenFlowScale > 0.0f ? framegenFlowScale : 0.7f,
                       framegenFlowMode, framegenFlowPreset,
                       framegenRefreshRate, framegenConfigRevision);
    vkr_lsfg_set_pressure(lsfg, framegenGpuUsagePercent_, framegenThermalStatus_,
                          framegenSourceFps_, framegenOutputFps_,
                          framegenFrameTimeP95Ms_, framegenSlowFrameRatio_);
}

uint32_t VulkanRendererContext::framegenExtraImages() const {
    if (!framegenRequested) return 0;
    if (framegenTargetRate != 0) return VKR_LSFG_MAX_GENERATIONS;

    uint32_t generations = framegenMultiplier > 1 ? framegenMultiplier - 1 : 1;
    return generations > VKR_LSFG_MAX_GENERATIONS ? VKR_LSFG_MAX_GENERATIONS : generations;
}

bool VulkanRendererContext::compositeFormatSupported() {
    if (swapchainFmt == VK_FORMAT_UNDEFINED) return false;

    VkFormatProperties props{};
    vkd.GetPhysicalDeviceFormatProperties(physicalDevice, swapchainFmt, &props);

    const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
                                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                                        | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                                        | VK_FORMAT_FEATURE_BLIT_SRC_BIT
                                        | VK_FORMAT_FEATURE_BLIT_DST_BIT;
    return (props.optimalTilingFeatures & required) == required;
}

void VulkanRendererContext::blitCompositeToSwapchain(VkCommandBuffer cmd, const VkCompositeTarget& src, VkImage dst) {
    transition(cmd, dst,
               VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
               0, VK_ACCESS_TRANSFER_WRITE_BIT,
               VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit blit{};
    blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.srcSubresource.layerCount = 1;
    blit.srcOffsets[1].x = (int32_t)src.width;
    blit.srcOffsets[1].y = (int32_t)src.height;
    blit.srcOffsets[1].z = 1;
    blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    blit.dstSubresource.layerCount = 1;
    blit.dstOffsets[1].x = (int32_t)swapchainExt.width;
    blit.dstOffsets[1].y = (int32_t)swapchainExt.height;
    blit.dstOffsets[1].z = 1;
    vkd.CmdBlitImage(cmd, src.image, VK_IMAGE_LAYOUT_GENERAL,
                     dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);

    transition(cmd, dst,
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
               VK_ACCESS_TRANSFER_WRITE_BIT, 0,
               VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
}

void VulkanRendererContext::waitNativeResources() {
    const auto waitStart = std::chrono::steady_clock::now();
    drainHostPresenter("native-lsfg-resource-change");
    for (auto fence : inFlightFences) {
        if (fence != VK_NULL_HANDLE
                && vk_.WaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX) == VK_SUCCESS) {
            completeObservedFence(fence);
        }
    }
    const uint64_t hostWaitNs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - waitStart).count());
    nativeHostWaitNsTotal_.fetch_add(hostWaitNs, std::memory_order_relaxed);
    nativeHostWaitSamples_.fetch_add(1, std::memory_order_relaxed);
    RLOG(
        "LSFG_NATIVE_SYNC: event=resource_retirement reason=native-lsfg-resource-change "
        "host_wait_ms=%.3f steady_state=0",
        (double)hostWaitNs / 1000000.0);
}

void VulkanRendererContext::recoverNativeAcquiredFrame() {
    // Match upstream's failed-submit recovery: acquired binary semaphores may
    // still be signalled, so they cannot be reused by the next frame.
    const VkFence stale = inFlightFences[currentFrame];
    for (auto& fence : imgInFlight) if (fence == stale) fence = VK_NULL_HANDLE;
    if (stale) vk_.DestroyFence(device, stale, nullptr);
    VkFenceCreateInfo fi{};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    inFlightFences[currentFrame] = VK_NULL_HANDLE;
    bool ok = vk_.CreateFence(device, &fi, nullptr, &inFlightFences[currentFrame]) == VK_SUCCESS;
    VkSemaphoreCreateInfo sci{};
    sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    auto replaceSemaphore = [&](VkSemaphore& semaphore) {
        if (semaphore) vk_.DestroySemaphore(device, semaphore, nullptr);
        semaphore = VK_NULL_HANDLE;
        if (vk_.CreateSemaphore(device, &sci, nullptr, &semaphore) != VK_SUCCESS) ok = false;
    };
    replaceSemaphore(imgAvailSems[currentFrame]);
    for (auto& semaphore : nativeExtraAcquireSems_[currentFrame]) {
        if (semaphore != VK_NULL_HANDLE) replaceSemaphore(semaphore);
    }
    if (lsfg) {
        waitNativeResources();
        vkr_lsfg_reset(lsfg);
    }
    nativeLastSourceFrame_ = 0;
    if (!ok) {
        RLOG_E("LSFG_NATIVE: event=recovery_failed reason=sync-allocation");
        isRunning.store(false);
        dirtyCV.notify_all();
    }
    fbResized.store(ok);
}

void VulkanRendererContext::armFrameGeneration() {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    framegenArmed = true;
}

void VulkanRendererContext::beginLsfgBackendTransition(
        uint64_t transactionId, uint64_t revision) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    lsfgBackendTransitionId_ = transactionId;
    lsfgBackendTransitionRevision_ = revision;
    lsfgBackendTransitionStartGeneration_ = hostSwapchainGeneration_;
    lsfgBackendTransitionRecreationAttempts_ = 0;
    lsfgBackendTransitionRecreationCount_ = 0;
    lsfgBackendTransitionFirstRecreationFailed_ = false;
    // Coalesce any not-yet-consumed rebuild into this ownership transaction.
    // frameMutex guarantees a render already processing fbResized has finished.
    lsfgBackendTransitionRebuildPending_ =
        fbResized.exchange(false, std::memory_order_acq_rel);
    lsfgBackendTransitionPolicyDirty_ = false;
    lsfgBackendTransitionPolicyCommitted_ = false;
    lsfgBackendTransitionPolicyApplied_ = false;
    lsfgBackendTransitionExpectedGeneration_ = hostSwapchainGeneration_;
    RLOG("LSFG_BACKEND_TX: event=swapchain_transaction_begin transaction_id=%llu "
         "revision=%llu generation_before=%llu absorbed_pending_rebuild=%d",
         (unsigned long long)transactionId, (unsigned long long)revision,
         (unsigned long long)hostSwapchainGeneration_,
         lsfgBackendTransitionRebuildPending_ ? 1 : 0);
}

void VulkanRendererContext::requestLsfgSwapchainRebuild(const char* reason) {
    if (lsfgBackendTransitionId_ != 0
            && !lsfgBackendTransitionPolicyCommitted_) {
        lsfgBackendTransitionRebuildPending_ = true;
        lsfgBackendTransitionPolicyDirty_ = true;
        RLOG("LSFG_BACKEND_TX: event=rebuild_deferred transaction_id=%llu "
             "revision=%llu reason=%s generation=%llu",
             (unsigned long long)lsfgBackendTransitionId_,
             (unsigned long long)lsfgBackendTransitionRevision_,
             reason ? reason : "unknown",
             (unsigned long long)hostSwapchainGeneration_);
        return;
    }
    fbResized.store(true, std::memory_order_release);
    dirtyCV.notify_one();
}

void VulkanRendererContext::commitLsfgBackendTransitionPolicy(
        uint64_t transactionId) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    if (lsfgBackendTransitionId_ != transactionId
            || lsfgBackendTransitionPolicyCommitted_) {
        return;
    }
    lsfgBackendTransitionPolicyCommitted_ = true;
    const bool rebuild = lsfgBackendTransitionRebuildPending_;
    lsfgBackendTransitionRebuildPending_ = false;
    lsfgBackendTransitionPolicyApplied_ = !rebuild;
    lsfgBackendTransitionExpectedGeneration_ = rebuild
        && hostSwapchainGeneration_ != UINT64_MAX
        ? hostSwapchainGeneration_ + 1 : hostSwapchainGeneration_;
    RLOG("LSFG_BACKEND_TX: event=presentation_policy_committed transaction_id=%llu "
         "revision=%llu generation=%llu expected_generation=%llu rebuild_pending=%d "
         "policy_applied=%d",
         (unsigned long long)transactionId,
         (unsigned long long)lsfgBackendTransitionRevision_,
         (unsigned long long)hostSwapchainGeneration_,
         (unsigned long long)lsfgBackendTransitionExpectedGeneration_,
         rebuild ? 1 : 0,
         lsfgBackendTransitionPolicyApplied_ ? 1 : 0);
    if (rebuild) {
        fbResized.store(true, std::memory_order_release);
        dirtyCV.notify_one();
    }
}

bool VulkanRendererContext::isLsfgBackendTransitionPolicyApplied(
        uint64_t transactionId) const {
    std::shared_lock<std::shared_mutex> fl(frameMutex);
    if (lsfgBackendTransitionId_ == 0
            || lsfgBackendTransitionId_ != transactionId) {
        return true;
    }
    return lsfgBackendTransitionPolicyCommitted_
        && lsfgBackendTransitionPolicyApplied_;
}

void VulkanRendererContext::completeLsfgBackendTransition(
        uint64_t transactionId, const char* reason) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    if (lsfgBackendTransitionId_ != transactionId) return;
    const bool invariantOk =
        lsfgBackendTransitionRecreationCount_ <= 1
        || lsfgBackendTransitionFirstRecreationFailed_;
    const bool policyOk =
        lsfgBackendTransitionPolicyCommitted_
        || !lsfgBackendTransitionPolicyDirty_;
    const bool releaseInheritedRebuild =
        lsfgBackendTransitionRebuildPending_
        && !lsfgBackendTransitionPolicyCommitted_;
    RLOG("LSFG_BACKEND_TX: event=swapchain_transaction_complete transaction_id=%llu "
         "revision=%llu generation_before=%llu generation_after=%llu "
         "recreation_attempts=%u recreation_count=%u first_recreation_failed=%d "
         "policy_dirty=%d policy_committed=%d policy_applied=%d "
         "expected_generation=%llu rebuild_pending=%d "
         "policy_ok=%d invariant_ok=%d completion_reason=%s",
         (unsigned long long)transactionId,
         (unsigned long long)lsfgBackendTransitionRevision_,
         (unsigned long long)lsfgBackendTransitionStartGeneration_,
         (unsigned long long)hostSwapchainGeneration_,
         lsfgBackendTransitionRecreationAttempts_,
         lsfgBackendTransitionRecreationCount_,
         lsfgBackendTransitionFirstRecreationFailed_ ? 1 : 0,
         lsfgBackendTransitionPolicyDirty_ ? 1 : 0,
         lsfgBackendTransitionPolicyCommitted_ ? 1 : 0,
         lsfgBackendTransitionPolicyApplied_ ? 1 : 0,
         (unsigned long long)lsfgBackendTransitionExpectedGeneration_,
         lsfgBackendTransitionRebuildPending_ ? 1 : 0,
         policyOk ? 1 : 0,
         invariantOk ? 1 : 0, reason ? reason : "unknown");
#ifndef NDEBUG
    assert(invariantOk && "one LSFG backend transition caused multiple swapchain generations");
    assert(policyOk && "LSFG backend transition completed with uncommitted presentation policy");
#endif
    lsfgBackendTransitionId_ = 0;
    lsfgBackendTransitionRevision_ = 0;
    lsfgBackendTransitionStartGeneration_ = hostSwapchainGeneration_;
    lsfgBackendTransitionRecreationAttempts_ = 0;
    lsfgBackendTransitionRecreationCount_ = 0;
    lsfgBackendTransitionFirstRecreationFailed_ = false;
    lsfgBackendTransitionRebuildPending_ = false;
    lsfgBackendTransitionPolicyDirty_ = false;
    lsfgBackendTransitionPolicyCommitted_ = false;
    lsfgBackendTransitionPolicyApplied_ = false;
    lsfgBackendTransitionExpectedGeneration_ = hostSwapchainGeneration_;
    if (releaseInheritedRebuild) {
        // A switch that aborts before touching WSI policy must not swallow an
        // unrelated surface resize that was pending when the transaction began.
        fbResized.store(true, std::memory_order_release);
        dirtyCV.notify_one();
    }
}

void VulkanRendererContext::setFrameGenerationEnabled(bool enabled) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    framegenArmed = true;
    if (framegenRequested == enabled) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    framegenRequested = enabled;
    if (!enabled) {
        if (device) waitNativeResources();
        destroyLsfg();
        destroyCompositeTargets();
        nativeLastSourceFrame_ = 0;
    } else if (device && !lsfgCachePath.empty()) {
        createLsfg();
    }
    requestLsfgSwapchainRebuild("frame-generation-enabled-state");
    RLOG("Frame generation composite path %s (supported=%d)",
         enabled ? "enabled" : "disabled", (int)framegenSupported);
}

bool VulkanRendererContext::isFrameGenerationSupported() const {
    return framegenRequested.load(std::memory_order_acquire)
        && framegenArmed.load(std::memory_order_acquire)
        && framegenSupported.load(std::memory_order_acquire)
        && nativeLsfgReady_.load(std::memory_order_acquire)
        && nativePresentationSurfaceReady_.load(std::memory_order_acquire)
        && !surfaceDetached.load(std::memory_order_acquire);
}

void VulkanRendererContext::setFrameGenerationShaders(const std::string& cachePath) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    if (!framegenArmed) return;
    if (!cachePath.empty() && cachePath == lsfgCachePath && lsfg != nullptr) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    // Cache-path changes before Native LSFG is created must not stall the
    // renderer. Only an existing native context can own GPU work that requires
    // a conservative teardown wait here.
    if (lsfg != nullptr && device) waitNativeResources();
    destroyLsfg();
    lsfgCachePath = cachePath;
    if (framegenRequested && device && !lsfgCachePath.empty()) {
        createLsfg();
    }
    requestLsfgSwapchainRebuild("frame-generation-shader-cache");
}

void VulkanRendererContext::setSourceFrameCount(uint64_t count) {
    framegenSourceFrames.store(count, std::memory_order_relaxed);
}

void VulkanRendererContext::setFrameGenerationRefreshRate(float hz) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    framegenRefreshRate = hz > 1.0f ? hz : 60.0f;
    if (lsfg)
        vkr_lsfg_set_refresh_rate(lsfg, framegenRefreshRate);
}

void VulkanRendererContext::setFrameGenerationMode(
        int multiplier, int targetRate, int flowScalePct, int flowMode, int flowPreset,
        uint64_t configRevision) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    if (!framegenArmed) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    const uint32_t previous_images = framegenExtraImages();
    const uint32_t nextMultiplier = static_cast<uint32_t>(std::clamp(multiplier, 2, 4));
    const uint32_t nextTarget = static_cast<uint32_t>(std::max(targetRate, 0));
    const float nextFlow = static_cast<float>(std::clamp(flowScalePct, 25, 100)) / 100.0f;
    const uint32_t nextFlowMode =
        flowMode == static_cast<int>(VKR_LSFG_FLOW_ADAPTIVE)
            ? VKR_LSFG_FLOW_ADAPTIVE : VKR_LSFG_FLOW_FIXED;
    const uint32_t nextFlowPreset =
        static_cast<uint32_t>(std::clamp(
            flowPreset,
            static_cast<int>(VKR_LSFG_FLOW_PRESET_QUALITY),
            static_cast<int>(VKR_LSFG_FLOW_PRESET_AUTO)));
    if (configRevision != 0 && framegenConfigRevision != 0
            && configRevision < framegenConfigRevision) {
        RLOG(
            "LSFG_NATIVE_CONFIG: event=discarded_stale_revision requested_revision=%llu "
            "current_revision=%llu stage=native-renderer",
            (unsigned long long)configRevision,
            (unsigned long long)framegenConfigRevision);
        return;
    }
    if (nextMultiplier == framegenMultiplier
            && nextTarget == framegenTargetRate
            && nextFlow == framegenFlowScale
            && nextFlowMode == framegenFlowMode
            && nextFlowPreset == framegenFlowPreset
            && configRevision == framegenConfigRevision) {
        return;
    }

    framegenMultiplier = nextMultiplier;
    framegenTargetRate = nextTarget;
    framegenFlowScale = nextFlow;
    framegenFlowMode = nextFlowMode;
    framegenFlowPreset = nextFlowPreset;
    framegenConfigRevision = configRevision;
    if (lsfg) {
        vkr_lsfg_configure(lsfg, framegenMultiplier, framegenTargetRate,
                           framegenFlowScale, framegenFlowMode, framegenFlowPreset,
                           framegenRefreshRate, framegenConfigRevision);
    }

    const uint32_t nextImages = framegenExtraImages();
    const bool swapchainCapacityIncrease =
        framegenRequested && nextImages > previous_images;
    const bool flowResourceRebuildRequired =
        lsfg != nullptr
        && swapchainFmt != VK_FORMAT_UNDEFINED
        && swapchainExt.width > 0 && swapchainExt.height > 0
        && vkr_lsfg_needs_rebuild(
            lsfg, swapchainExt.width, swapchainExt.height, swapchainFmt);
    const char* flowRebuildReason = flowResourceRebuildRequired
        ? vkr_lsfg_rebuild_reason(
            lsfg, swapchainExt.width, swapchainExt.height, swapchainFmt)
        : "none";

    // Multiplier and target-FPS changes are scheduler scalars and never reset
    // LSFG history. Growing from 2x/3x to a denser mode can still require more
    // WSI images; recreate only the swapchain/composite capacity in that case.
    if (swapchainCapacityIncrease) {
        requestLsfgSwapchainRebuild("swapchain-capacity-increase");
        RLOG(
            "LSFG_NATIVE_CONTEXT: event=surface_rebuild_requested "
            "reason=swapchain-capacity-increase previous_extra_images=%u "
            "requested_extra_images=%u revision=%llu",
            previous_images,
            nextImages,
            (unsigned long long)framegenConfigRevision);
    }
    RLOG(
        "LSFG_NATIVE_CONTEXT: event=config_update context_epoch=%llu revision=%llu "
        "rebuild_required=%d rebuild_reason=%s multiplier=%u target_fps=%u flow_mode=%s "
        "flow_preset=%u requested_scale=%.2f swapchain_capacity_increase=%d",
        (unsigned long long)nativeLsfgContextEpoch_,
        (unsigned long long)framegenConfigRevision,
        flowResourceRebuildRequired ? 1 : 0,
        flowRebuildReason,
        framegenMultiplier,
        framegenTargetRate,
        framegenFlowMode == VKR_LSFG_FLOW_ADAPTIVE ? "adaptive" : "fixed",
        framegenFlowPreset,
        (double)framegenFlowScale,
        swapchainCapacityIncrease ? 1 : 0);

    dirtyCV.notify_one();
}

void VulkanRendererContext::setFrameGenerationPressure(
        float gpuUsagePercent, int thermalStatus, float sourceFps, float outputFps,
        float frameTimeP95Ms, float slowFrameRatio) {
    // High-frequency control-plane telemetry is intentionally lock-free.
    // renderFrame snapshots these atomics immediately before vkr_lsfg_plan(),
    // so JNI never waits for the frame-wide renderer lock.
    framegenGpuUsagePercent_.store(gpuUsagePercent, std::memory_order_relaxed);
    framegenThermalStatus_.store(thermalStatus, std::memory_order_relaxed);
    framegenSourceFps_.store(sourceFps, std::memory_order_relaxed);
    framegenOutputFps_.store(outputFps, std::memory_order_relaxed);
    framegenFrameTimeP95Ms_.store(frameTimeP95Ms, std::memory_order_relaxed);
    framegenSlowFrameRatio_.store(slowFrameRatio, std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getGeneratedFrameCount() const {
    return framegenMadeFrames.load(std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getGeneratedPresentedFrameCount() const {
    return nativeGeneratedPresentedFrames_.load(std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getPresentedFrameCount() const {
    return presentedFrames.load(std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getDisplayConfirmedFrameCount() const {
    return nativeSourceDisplayConfirmedEpoch_.load(std::memory_order_relaxed)
        + nativeGeneratedDisplayConfirmedEpoch_.load(std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getGeneratedDisplayConfirmedFrameCount() const {
    return nativeGeneratedDisplayConfirmedEpoch_.load(std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getSourceDisplayConfirmedFrameCount() const {
    return nativeSourceDisplayConfirmedEpoch_.load(std::memory_order_relaxed);
}

bool VulkanRendererContext::isDisplayConfirmationAvailable() const {
    return hostGoogleDisplayTimingEnabled || hostPresentWaitEnabled;
}

uint64_t VulkanRendererContext::getRealFrameCount() const {
    return framegenRealFrames.load(std::memory_order_relaxed);
}

uint64_t VulkanRendererContext::getSourceFrameCount() const {
    return framegenSourceFrames.load(std::memory_order_relaxed);
}
