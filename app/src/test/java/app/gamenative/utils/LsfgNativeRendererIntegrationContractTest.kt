package app.gamenative.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LsfgNativeRendererIntegrationContractTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "app/src/main/cpp/winlator/VulkanRendererContext.h").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir"))
    }

    @Test
    fun nativeTimedPresentationPrefersMailboxAndLegacyRestoreIsRevisionSafe() {
        val root = repoRoot()
        val manager =
            File(root, "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt").readText()
        val context =
            File(root, "app/src/main/cpp/winlator/VulkanRendererContext.cpp").readText()

        assertTrue(manager.contains("renderer.setVkPresentMode(1)"))
        assertTrue(manager.contains("native-retired-current-snapshot"))
        assertTrue(manager.contains("legacy_restore_after_native_retire"))
        assertTrue(manager.contains("result == \"legacy-restored\""))

        assertTrue(
            "GameNative compositor must not double-apply Android ROTATE_90",
            context.contains(
                "caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR",
            ),
        )
        assertTrue(context.contains("const bool transformChanged = false"))
    }

    @Test
    fun nativeRendererStateHasItsRequiredIntegrationSeam() {
        val root = repoRoot()
        val header = File(root, "app/src/main/cpp/winlator/VulkanRendererContext.h").readText()
        val context = File(root, "app/src/main/cpp/winlator/VulkanRendererContext.cpp").readText()
        val native = File(root, "app/src/main/cpp/winlator/VulkanRendererLsfg.cpp").readText()
        val cmake = File(root, "app/src/main/cpp/CMakeLists.txt").readText()

        assertTrue(header.contains("struct VkrLsfg;"))
        assertTrue(header.contains("VkCompositeTarget"))
        assertTrue(header.contains("VkrLsfg* lsfg"))
        assertTrue(header.contains("framegenRequested"))
        assertTrue(header.contains("createCompositeTargets"))
        assertTrue(cmake.contains("winlator/VulkanRendererLsfg.cpp"))

        assertTrue(native.contains("#include \"../lsfg/vk_dispatch.h\""))
        val createDeviceEnd = context.indexOf("vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);")
        val createDeviceBody = context.substring(0, createDeviceEnd)
        assertTrue(!createDeviceBody.contains("vkd_load(instance, device, gipa)"))

        assertTrue(native.contains("vkd_load(instance, device, gipa)"))
        assertTrue(context.contains("vkd_unload()"))
        assertTrue(native.contains("vkr_lsfg_create"))
        assertTrue(context.contains("vkr_lsfg_generate_into"))
        assertTrue(context.contains("nativeExtraAcquireSems_"))
        assertTrue(context.contains("nativeExtraAcquireSems_[currentFrame][g]"))
        assertTrue(context.contains("nativeGeneratedImgIndices"))
        assertTrue(context.contains("vkr_lsfg_plan(lsfg, nativeCapacity, nativeSourceFrame)"))
        assertTrue(context.contains("vkr_lsfg_process("))
        assertTrue(context.contains("blitCompositeToSwapchain("))
        assertTrue(context.contains("signalSemaphoreCount=signalSemaphoreCount"))
        assertTrue(context.contains("nativeRuntimeActive"))
        assertTrue(!context.contains("framegenMultiplier == 2"))
        assertTrue(header.contains("uint32_t framegenFlowMode = VKR_LSFG_FLOW_FIXED"))
        assertTrue(context.contains("nativeLsfgContextEpoch_"))
        assertTrue(header.contains("nativeLastContextReuseRevision_"))
        assertTrue(header.contains("nativePresentRateSampleNs_"))
        assertTrue(context.contains("nativeLastContextReuseRevision_ != framegenConfigRevision"))
        assertTrue(context.contains("wsi_accepted_output_fps=%.2f"))
        assertTrue(context.contains("confirmed_output_fps=%.2f"))
        assertTrue(context.contains("host_wait_total_ms=%.3f"))
        assertTrue(context.contains("event=presentation_evidence_reset reason=swapchain-recreate"))
        assertTrue(context.contains("nativeGeneratedPresentedFrames_.store(0"))
        assertTrue(context.contains("vkr_lsfg_note_presentation_drop(lsfg, 1)"))

        val vkr = File(root, "app/src/main/cpp/lsfg/vkr_lsfg.cpp").readText()
        assertTrue(vkr.contains("AdaptiveFlowController"))
        val pacer =
            File(root, "app/src/main/cpp/lsfg/lsfg_pacer.hpp").readText()
        val pacerImpl =
            File(root, "app/src/main/cpp/lsfg/lsfg_pacer.cpp").readText()
        assertTrue(pacer.contains("AdaptiveFrameScheduler adaptive_scheduler"))
        assertTrue(pacerImpl.contains("adaptive_scheduler.plan"))
        assertTrue(pacerImpl.contains("adaptive_scheduler.configure"))
        assertTrue(cmake.contains("lsfg-vk-android/src/adaptive_scheduler.cpp"))
        assertTrue(vkr.contains("generation_density_backoff"))
        assertTrue(vkr.contains("vkr_lsfg_set_pressure"))
        assertTrue(vkr.contains("output_deficit"))
        assertTrue(vkr.contains("bool gpu_valid{}"))
        assertTrue(vkr.contains("bool output_valid{}"))
        assertTrue(vkr.contains("observation.globalPressureValid = global_pressure_valid"))
        assertTrue(vkr.contains("pressure_fresh && lsfg->pressure.output_valid"))
        assertTrue(vkr.contains("effective_scale"))
        assertTrue(vkr.contains("transition=%d warm=%d"))

        val standaloneCmake =
            File(root, "app/src/main/cpp/vulkan-renderer-build/CMakeLists.txt").readText()
        assertTrue(standaloneCmake.contains("adaptive_flow_controller.cpp"))
        assertTrue(standaloneCmake.contains("CMAKE_CXX_STANDARD 20"))

        val manager = File(root, "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt").readText()
        val quickMenu = File(root, "app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt").readText()
        assertTrue(manager.contains("data class NativeRuntimeConfigSnapshot"))
        assertTrue(manager.contains("requested_revision"))
        assertTrue(manager.contains("applied_revision"))
        assertTrue(manager.contains("discarded_stale_revision"))
        assertTrue(manager.contains("LSFG_LEGACY_CONFIG: event=legacy_layer_state"))
        assertTrue(manager.contains("reason = \"native-backend\""))
        assertTrue(manager.contains("LSFG_NATIVE_CONFIG: event=%s"))
        assertTrue(manager.contains("present_policy=%s queue_policy=%s legacy_present_policy=%s"))
        assertTrue(manager.contains("shared-host-frame-queue+native-admission"))
        assertTrue(manager.contains("snapshotIsCurrent"))
        assertTrue(manager.contains("reserveRuntimeRequest"))
        assertTrue(manager.contains("before-legacy-publication"))
        assertTrue(manager.contains("latestBackendRequest"))
        assertTrue(manager.contains("legacyAckRequired"))
        assertTrue(manager.contains("legacy_source_only_wait"))
        assertTrue(manager.contains("live-backend-transition"))
        assertTrue(quickMenu.contains("revision = request.revision"))
        assertTrue(quickMenu.contains("backendGeneration = request.backendGeneration"))
        assertTrue(quickMenu.contains("updateConfigAtRuntimeCaptured"))
        assertTrue(quickMenu.contains("snapshot.armed"))
        assertTrue(manager.contains("VulkanRenderer.LSFG_FLOW_ADAPTIVE"))
        assertTrue(manager.contains("setFrameGenerationPressure"))
        assertTrue(manager.contains("direction=native-to-legacy"))
        assertTrue(manager.contains("reason = \"native-to-legacy-handoff\""))
        assertTrue(manager.contains("reason = \"native-retired\""))
        assertTrue(manager.contains("isSnapshotRevisionCurrent"))
        val publishStart = quickMenu.indexOf("private fun publishRuntimeConfig")
        val publishBody = quickMenu.substring(publishStart)
        assertTrue(!publishBody.contains("LsfgVkManager.refreshNativeRuntime(container)"))
        assertTrue(context.contains("MAX_FRAMES_IN_FLIGHT + g"))
        assertTrue(context.contains("&composite[currentFrame]"))
        assertTrue(context.contains("extraAcquireDeadline"))
        assertTrue(context.contains("recoverNativeAcquiredFrame()"))
        assertTrue(context.contains("generatedProvenance.uniqueDelivery = true"))
        val generatedPresent = context.indexOf("const VkResult generatedPresentResult = enqueueHostPresent")
        val generatedDesiredValidation =
            context.indexOf("validatedHostDesiredPresentTime({generatedProvenance})")
        val sourceDesiredValidation =
            context.indexOf("desiredDecision = validatedHostDesiredPresentTime(frameProvenance)", generatedDesiredValidation)
        val sourcePresent =
            context.indexOf("res = enqueueHostPresent(PendingHostPresent{", generatedPresent)
        assertTrue(generatedPresent >= 0 && sourcePresent > generatedPresent)
        assertTrue(
            "Native desired-time cursor must advance generated slots before source",
            generatedDesiredValidation >= 0 &&
                sourceDesiredValidation > generatedDesiredValidation &&
                sourcePresent > sourceDesiredValidation,
        )
        val renderStart = context.indexOf("void VulkanRendererContext::renderFrame()")
        val renderEnd = context.indexOf("void VulkanRendererContext::", renderStart + 8)
        val renderBody = context.substring(renderStart, if (renderEnd > renderStart) renderEnd else context.length)
        assertTrue(!renderBody.contains("DeviceWaitIdle"))
        assertTrue(
            Regex("ensureNativeExtraAcquireSemaphores\\(\\)")
                .findAll(renderBody)
                .count() == 1,
        )

        val createSyncStart = context.indexOf("void VulkanRendererContext::createSyncObjects()")
        val createSyncEnd = context.indexOf(
            "bool VulkanRendererContext::ensureNativeExtraAcquireSemaphores()",
            createSyncStart,
        )
        assertTrue(createSyncStart >= 0)
        assertTrue(createSyncEnd > createSyncStart)
        val createSyncBody = context.substring(createSyncStart, createSyncEnd)
        assertTrue(!createSyncBody.contains("nativeExtraAcquireSems_"))

        assertTrue(native.contains("swapchainCapacityIncrease"))
        assertTrue(native.contains("reason=swapchain-capacity-increase"))
        assertTrue(native.contains("nextImages > previous_images"))
        assertTrue(native.contains("stage=native-renderer"))
        assertTrue(native.contains("configRevision < framegenConfigRevision"))
        val backlogAdmission = context.indexOf("event=admission source_index=")
        assertTrue(backlogAdmission >= 0)
        val backlogWindow = context.substring(
            (backlogAdmission - 1800).coerceAtLeast(0),
            (backlogAdmission + 800).coerceAtMost(context.length),
        )
        assertTrue(backlogWindow.contains("nativeGeneratedBacklogRejected_"))
        assertTrue(backlogWindow.contains("rejection_reason=%s"))
        assertTrue(backlogWindow.contains("nativeLastAdmissionReason_"))
        assertTrue(!backlogWindow.contains("nativeGeneratedDeadlineRejected_"))

        val shaderStart = native.indexOf("void VulkanRendererContext::setFrameGenerationShaders")
        val shaderEnd = native.indexOf("void VulkanRendererContext::setSourceFrameCount", shaderStart)
        assertTrue(shaderStart >= 0)
        assertTrue(shaderEnd > shaderStart)
        val shaderBody = native.substring(shaderStart, shaderEnd)
        assertTrue(shaderBody.contains("if (lsfg != nullptr && device) waitNativeResources();"))
        assertTrue(!shaderBody.contains("if (device) vk_.DeviceWaitIdle(device);"))

        // Native desired-presentation ownership is explicit for both synthetic
        // outputs and their source boundary; no generated present may carry an
        // empty HostDesiredPresentDecision.
        assertTrue(context.contains("buildNativePresentationSchedule("))
        assertTrue(header.contains("SourceProtectedTimeline nativeSourceTimeline_"))
        assertTrue(context.contains("nativeSourceTimeline_.observe"))
        assertTrue(context.contains("nativeSourceTimeline_.syntheticDesiredTimeNs"))
        assertTrue(context.contains("generatedProvenance.desiredPresentTimeNs"))
        assertTrue(context.contains("nativeSource.desiredPresentTimeNs"))
        assertTrue(context.contains("validatedHostDesiredPresentTime({generatedProvenance})"))
        assertTrue(!context.contains(".desiredDecision = HostDesiredPresentDecision{}"))

        // Adaptive Flow receives physical-delivery pressure and can temporarily
        // degrade to source-only without marking the backend failed.
        assertTrue(vkr.contains("vkr_lsfg_set_presentation_pressure"))
        assertTrue(vkr.contains("adaptive_generation_cap > 0"))
        assertTrue(vkr.contains("physical-delivery-pressure"))
        assertTrue(vkr.contains("source-only-probe"))
        assertTrue(vkr.contains("presentation_pressure.pressure_active"))
        assertTrue(vkr.contains("lsfg->warm && generations > 0"))

        // NATIVE_GENERATING is physical-delivery qualified, not a one-present
        // WSI-acceptance latch.
        assertTrue(manager.contains("NATIVE_WSI_GENERATING"))
        assertTrue(manager.contains("NATIVE_PRESENTATION_DEGRADED"))
        assertTrue(manager.contains("NATIVE_CONFIRMATION_UNAVAILABLE"))
        assertTrue(manager.contains("sustained-generated-display-confirmation"))
        assertTrue(manager.contains("getGeneratedDisplayConfirmedFrameCount"))
        assertTrue(manager.contains("measurement=%s"))
        assertTrue(manager.contains("\"display-confirmed\""))

        // Pressure/support control-plane calls cannot convoy behind the
        // frame-wide native shared_mutex or the Java renderer monitor.
        val javaRenderer =
            File(root, "app/src/main/java/com/winlator/renderer/VulkanRenderer.java").readText()
        val pressureStart = native.indexOf("void VulkanRendererContext::setFrameGenerationPressure")
        val pressureEnd = native.indexOf("uint64_t VulkanRendererContext::getGeneratedFrameCount", pressureStart)
        val pressureBody = native.substring(pressureStart, pressureEnd)
        assertTrue(!pressureBody.contains("frameMutex"))
        val supportStart = javaRenderer.indexOf("public boolean isFrameGenerationSupported()")
        val supportEnd = javaRenderer.indexOf("public boolean hasNativeSurface()", supportStart)
        val supportBody = javaRenderer.substring(supportStart, supportEnd)
        assertTrue(!supportBody.contains("synchronized (lock)"))
        assertTrue(!supportBody.contains("nativeIsFrameGenerationSupported"))
        assertTrue(javaRenderer.contains("ReentrantReadWriteLock nativeLifetimeLock"))
        val applyFramegenStart =
            javaRenderer.indexOf("public boolean applyFrameGenerationSettings")
        val applyFramegenEnd =
            javaRenderer.indexOf("public void setFrameGenerationShaders", applyFramegenStart)
        val applyFramegenBody =
            javaRenderer.substring(applyFramegenStart, applyFramegenEnd)
        assertTrue(applyFramegenBody.contains("xServerView.post"))
        assertTrue(applyFramegenBody.contains("computeEffectsRequireCompositor") ||
            applyFramegenBody.contains("setEffect("))
        assertTrue(applyFramegenBody.contains("nativeSetLsfgFrameQueue("))
        assertTrue(applyFramegenBody.contains("pendingLsfgFrameQueueEnabled"))
        assertTrue(applyFramegenBody.contains("pendingLsfgFrameQueueTarget"))
        assertTrue(!javaRenderer.contains("nativeOwnsFrameQueuePolicy"))
        assertTrue(manager.contains("shared-host-frame-queue+native-admission"))
        assertTrue(context.contains("nativeLsfgContentPending"))

        // Native must preserve the same selected present-mode policy as Legacy.
        // The previous hardcoded FIFO path poisoned admission with FIFO blocking.
        val applyPresentStart =
            javaRenderer.indexOf("public boolean applyFrameGenerationSettings")
        val applyPresentEnd =
            javaRenderer.indexOf("public void setFrameGenerationShaders", applyPresentStart)
        val applyPresentBody =
            javaRenderer.substring(applyPresentStart, applyPresentEnd)
        assertTrue(applyPresentBody.contains("nativeSetPresentMode(handle, pendingPresentMode)"))
        assertTrue(!applyPresentBody.contains("pendingPresentMode = 2"))
        assertTrue(!applyPresentBody.contains("nativeSetPresentMode(handle, 2)"))
        val presentSetterStart =
            javaRenderer.indexOf("public void setVkPresentMode(int mode)")
        val presentSetterEnd =
            javaRenderer.indexOf("private void replayFrameGenerationLocked()", presentSetterStart)
        val presentSetter =
            javaRenderer.substring(presentSetterStart, presentSetterEnd)
        assertTrue(presentSetter.contains("nativeLifetimeLock.readLock().lock()"))
        assertTrue(!presentSetter.contains("synchronized (lock)"))
        assertTrue(manager.contains("renderer.setVkPresentMode("))
        assertTrue(manager.contains("if (snapshot.presentMode == \"mailbox\") 1 else 2"))

        // Android WSI uses the surface's current transform unless it is
        // genuinely unsupported; identity support alone is not an override.
        assertTrue(context.contains("VkSurfaceTransformFlagBitsKHR pre = caps.currentTransform"))
        assertTrue(context.contains("(caps.supportedTransforms & pre) == 0"))
        assertTrue(context.contains("transformChanged"))
        assertTrue(context.contains("transform_changed=%d"))

        // Native admission must distinguish presenter service from blocked
        // vkQueuePresentKHR tail latency. A FIFO p95 >= one source period must
        // not automatically zero every synthetic opportunity.
        val admissionStart2 =
            context.indexOf("uint32_t VulkanRendererContext::nativeHostSyntheticAdmissionCapacity")
        val admissionEnd2 =
            context.indexOf("void VulkanRendererContext::emitNativeLsfgPipelineTelemetry", admissionStart2)
        val admissionBody2 = context.substring(admissionStart2, admissionEnd2)
        assertTrue(admissionBody2.contains("p50PresentNs"))
        assertTrue(admissionBody2.contains("p95PresentNs"))
        assertTrue(admissionBody2.contains("serviceEstimateNs"))
        assertTrue(admissionBody2.contains("hostRefreshPeriodNs_"))
        assertTrue(admissionBody2.contains("std::min(serviceEstimateNs, refreshBoundNs)"))
        assertTrue(!admissionBody2.contains("occupied) * p95PresentNs"))
        assertTrue(context.contains("admission_service_estimate_ms="))

        // Adaptive Flow remains the first actuator. The shared controller
        // retains target-driven downsteps under presentation pressure until the
        // preset floor; only then may Adaptive FG density back off.
        val sharedFlowController =
            File(
                root,
                "app/src/main/cpp/lsfg-vk-android/src/adaptive_flow_controller.cpp",
            ).readText()
        val normalizedSharedFlowController =
            sharedFlowController.replace(Regex("\\s+"), " ")
        assertTrue(
            normalizedSharedFlowController.contains(
                "!downstepBaselineWsiPressure_ || observation.adaptiveFramegenMode",
            ),
        )
        assertTrue(vkr.contains("const bool at_minimum ="))
        assertTrue(vkr.contains("at_minimum && ("))
        assertTrue(vkr.contains("presentation_pressure"))
        assertTrue(vkr.contains("lsfg->synthetic_drop_pressure"))
        assertTrue(!vkr.contains("structural_presentation_pressure"))
    }
}
