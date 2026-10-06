#include "vkr_lsfg.h"

#include "lsfg_chain.hpp"
#include "lsfg_pacer.hpp"
#include "lsfg_shaders.hpp"
#include "adaptive_flow_controller.hpp"

#include <algorithm>
#include <cmath>
#include <memory>
#include <string>

#include <android/log.h>

#define LSFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VkrLsfg", __VA_ARGS__)
#define LSFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "VkrLsfg", __VA_ARGS__)
#define LSFG_FLOW_LOG(...) __android_log_print(ANDROID_LOG_INFO, "LSFG_NATIVE_FLOW", __VA_ARGS__)

namespace {

constexpr uint64_t LSFG_REQUIRED_FRAMES = 3;
constexpr uint64_t LSFG_RECURRENCE_FRAMES = 1;
constexpr uint64_t LSFG_TELEMETRY_INTERVAL = 120;

constexpr float LSFG_FLOW_SCALE_MIN = 0.25f;
constexpr float LSFG_FLOW_SCALE_MAX = 1.0f;
constexpr float LSFG_FLOW_SCALE_STEPS = 20.0f;

VkImageMemoryBarrier MakeTransitionBarrier(VkImage image, VkAccessFlags src_access,
                                           VkAccessFlags dst_access, VkImageLayout old_layout,
                                           VkImageLayout new_layout) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    return barrier;
}

void CopyPresentedFrame(VkCommandBuffer cmd, VkImage source, lsfg::LsfgImage& destination,
                        VkExtent2D extent) {
    const VkImageMemoryBarrier before[] = {
        MakeTransitionBarrier(source, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                              VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL,
                              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
        MakeTransitionBarrier(destination.Handle(),
                              destination.Layout() == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_SHADER_READ_BIT,
                              VK_ACCESS_TRANSFER_WRITE_BIT, destination.Layout(),
                              VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
    };
    vkd.CmdPipelineBarrier(cmd,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, before);

    VkImageCopy region{};
    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.layerCount = 1;
    region.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.dstSubresource.layerCount = 1;
    region.extent = {extent.width, extent.height, 1};
    vkd.CmdCopyImage(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.Handle(),
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    const VkImageMemoryBarrier after[] = {
        MakeTransitionBarrier(source, VK_ACCESS_TRANSFER_READ_BIT,
                              VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                              VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        MakeTransitionBarrier(destination.Handle(), VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                              VK_IMAGE_LAYOUT_GENERAL),
    };
    vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT |
                               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, 0, nullptr, 0, nullptr, 2, after);

    destination.SetLayout(VK_IMAGE_LAYOUT_GENERAL);
}

AdaptiveFlowPreset ParseFlowPreset(uint32_t preset) {
    switch (preset) {
        case VKR_LSFG_FLOW_PRESET_BALANCED: return AdaptiveFlowPreset::Balanced;
        case VKR_LSFG_FLOW_PRESET_LOW: return AdaptiveFlowPreset::Low;
        case VKR_LSFG_FLOW_PRESET_AUTO: return AdaptiveFlowPreset::Auto;
        default: return AdaptiveFlowPreset::Quality;
    }
}

}

struct VkrLsfg {
    lsfg::Device device;
    std::string cache_path;
    std::unique_ptr<lsfg::LsfgShaders> shaders;
    std::unique_ptr<lsfg::LsfgChain> chain;
    lsfg::LsfgPacer pacer;
    lsfg::LsfgPlan plan{};

    VkExtent2D built_extent{};
    VkExtent2D peak_guest_extent{};
    VkFormat built_format{VK_FORMAT_UNDEFINED};
    float built_flow_scale{};
    float requested_flow_scale{1.0f};
    float active_flow_scale{1.0f};
    bool adaptive_flow{};
    uint32_t flow_preset_code{VKR_LSFG_FLOW_PRESET_QUALITY};
    uint64_t config_revision{};
    AdaptiveFlowController flow_controller{};

    struct RuntimePressure {
        float gpu_usage_percent{-1.0f};
        int thermal_status{-1};
        float source_fps{};
        float output_fps{};
        float frame_time_p95_ms{};
        float slow_frame_ratio{};
        std::chrono::steady_clock::time_point sampled_at{};
        bool valid{};
    } pressure;

    bool synthetic_drop_pressure{};
    uint32_t adaptive_generation_cap{VKR_LSFG_MAX_GENERATIONS};
    double density_pressure_seconds{};
    double density_recovery_seconds{};
    uint32_t flow_transition_frames{};

    uint64_t frame_count{};
    uint64_t last_count{};
    size_t last_generations{};
    uint64_t plan_calls{};
    uint32_t warm_streak{};
    bool warm{};
    bool generated{};
    bool unavailable{};
};

static float lsfg_effective_flow_scale(const VkrLsfg* lsfg, uint32_t width) {
    if (width == 0 || lsfg->peak_guest_extent.width == 0) return lsfg->active_flow_scale;

    const float ratio =
        static_cast<float>(lsfg->peak_guest_extent.width) / static_cast<float>(width);
    const float stepped = std::ceil(ratio * LSFG_FLOW_SCALE_STEPS) / LSFG_FLOW_SCALE_STEPS;
    return std::clamp(std::min(stepped, lsfg->active_flow_scale), LSFG_FLOW_SCALE_MIN,
                      LSFG_FLOW_SCALE_MAX);
}

VkrLsfg* vkr_lsfg_create(VkDevice device, VkPhysicalDevice physical_device,
                         const char* cache_path) {
    if (device == VK_NULL_HANDLE || physical_device == VK_NULL_HANDLE || cache_path == nullptr) {
        return nullptr;
    }

    auto* lsfg = new VkrLsfg();
    lsfg->device = lsfg::Device(device, physical_device);
    lsfg->cache_path = cache_path;

    lsfg->shaders = std::make_unique<lsfg::LsfgShaders>(lsfg->device, lsfg->cache_path.c_str());
    if (!lsfg->shaders->IsValid()) {
        LSFG_LOGW("shader cache at %s did not yield all modules", cache_path);
        delete lsfg;
        return nullptr;
    }

    LSFG_LOGI("frame generation shaders ready");
    return lsfg;
}

void vkr_lsfg_destroy(VkrLsfg* lsfg) {
    delete lsfg;
}

void vkr_lsfg_configure(VkrLsfg* lsfg, uint32_t multiplier, uint32_t target_rate,
                        float flow_scale, uint32_t flow_mode, uint32_t flow_preset,
                        float refresh_rate, uint64_t config_revision) {
    if (!lsfg) return;

    lsfg::LsfgPacerConfig config = lsfg->pacer.Config();
    config.multiplier = multiplier;
    config.target_rate = target_rate;
    config.refresh_rate = refresh_rate;
    lsfg->pacer.SetConfig(config);

    const float requested =
        std::clamp(flow_scale, LSFG_FLOW_SCALE_MIN, LSFG_FLOW_SCALE_MAX);
    const bool adaptive = flow_mode == VKR_LSFG_FLOW_ADAPTIVE;
    const uint32_t preset_code =
        std::min<uint32_t>(flow_preset, VKR_LSFG_FLOW_PRESET_AUTO);
    const AdaptiveFlowPreset preset = ParseFlowPreset(preset_code);
    const bool flow_contract_changed =
        adaptive != lsfg->adaptive_flow
        || preset_code != lsfg->flow_preset_code
        || std::fabs(requested - lsfg->requested_flow_scale) > 0.0005f;

    lsfg->requested_flow_scale = requested;
    lsfg->config_revision = config_revision;
    lsfg->adaptive_flow = adaptive;
    lsfg->flow_preset_code = preset_code;
    lsfg->flow_controller.configure(adaptive, preset);

    if (adaptive) {
        if (flow_contract_changed) {
            const auto seed =
                AdaptiveFlowController::conservativeSeedScale(preset, requested);
            if (seed) lsfg->flow_controller.seedCurrentScale(*seed);
            lsfg->active_flow_scale = lsfg->flow_controller.currentScale();
            lsfg->flow_transition_frames = 3;
        } else {
            lsfg->active_flow_scale = lsfg->flow_controller.currentScale();
        }
    } else {
        lsfg->active_flow_scale = requested;
        lsfg->adaptive_generation_cap = VKR_LSFG_MAX_GENERATIONS;
        lsfg->density_pressure_seconds = 0.0;
        lsfg->density_recovery_seconds = 0.0;
    }

    const auto& telemetry = lsfg->flow_controller.telemetry();
    LSFG_FLOW_LOG(
        "event=config revision=%llu mode=%s preset=%s requested_scale=%.2f active_scale=%.2f "
        "target_scale=%.2f minimum_scale=%.2f state_index=%zu state_count=%zu "
        "multiplier=%u target_fps=%u refresh=%.2f",
        (unsigned long long)lsfg->config_revision,
        adaptive ? "adaptive" : "fixed",
        AdaptiveFlowController::presetName(preset),
        static_cast<double>(requested),
        static_cast<double>(lsfg->active_flow_scale),
        static_cast<double>(adaptive ? telemetry.targetScale : requested),
        static_cast<double>(adaptive ? telemetry.minimumScale : requested),
        adaptive ? telemetry.stateIndex : 0u,
        adaptive ? telemetry.stateCount : 1u,
        multiplier,
        target_rate,
        static_cast<double>(refresh_rate));
}

void vkr_lsfg_set_pressure(VkrLsfg* lsfg, float gpu_usage_percent, int thermal_status,
                           float source_fps, float output_fps, float frame_time_p95_ms,
                           float slow_frame_ratio) {
    if (!lsfg) return;
    auto& pressure = lsfg->pressure;
    pressure.gpu_usage_percent = gpu_usage_percent;
    pressure.thermal_status = thermal_status;
    pressure.source_fps = std::max(source_fps, 0.0f);
    pressure.output_fps = std::max(output_fps, 0.0f);
    pressure.frame_time_p95_ms = std::max(frame_time_p95_ms, 0.0f);
    pressure.slow_frame_ratio = std::clamp(slow_frame_ratio, 0.0f, 1.0f);
    pressure.sampled_at = std::chrono::steady_clock::now();
    pressure.valid = std::isfinite(gpu_usage_percent)
        && gpu_usage_percent >= 0.0f && gpu_usage_percent <= 100.0f;
}

void vkr_lsfg_note_admission(VkrLsfg* lsfg, uint32_t requested, uint32_t admitted) {
    if (!lsfg || requested == 0) return;
    if (admitted < requested)
        lsfg->synthetic_drop_pressure = true;
}

void vkr_lsfg_set_guest_extent(VkrLsfg* lsfg, uint32_t width, uint32_t height) {
    if (!lsfg || width == 0 || height == 0) return;
    lsfg->peak_guest_extent.width = std::max(lsfg->peak_guest_extent.width, width);
    lsfg->peak_guest_extent.height = std::max(lsfg->peak_guest_extent.height, height);
}

void vkr_lsfg_set_refresh_rate(VkrLsfg* lsfg, float refresh_rate) {
    if (!lsfg) return;

    lsfg::LsfgPacerConfig config = lsfg->pacer.Config();
    if (config.refresh_rate == refresh_rate) return;
    config.refresh_rate = refresh_rate;
    lsfg->pacer.SetConfig(config);
}

bool vkr_lsfg_needs_rebuild(const VkrLsfg* lsfg, uint32_t width, uint32_t height,
                            VkFormat format) {
    if (!lsfg || lsfg->unavailable) return false;
    return !lsfg->chain || lsfg->built_extent.width != width
        || lsfg->built_extent.height != height || lsfg->built_format != format
        || lsfg->built_flow_scale != lsfg_effective_flow_scale(lsfg, width);
}

const char* vkr_lsfg_rebuild_reason(const VkrLsfg* lsfg, uint32_t width, uint32_t height,
                                    VkFormat format) {
    if (!lsfg || lsfg->unavailable) return "unavailable";
    if (!lsfg->chain) return "initial-create";
    if (lsfg->built_extent.width != width || lsfg->built_extent.height != height)
        return "resolution-change";
    if (lsfg->built_format != format) return "format-change";
    if (std::fabs(lsfg->built_flow_scale - lsfg_effective_flow_scale(lsfg, width)) > 0.0005f)
        return "flow-scale-change";
    return "none";
}

bool vkr_lsfg_get_flow_state(const VkrLsfg* lsfg, VkrLsfgFlowState* out_state) {
    if (!lsfg || !out_state) return false;
    const auto& telemetry = lsfg->flow_controller.telemetry();
    out_state->adaptive = lsfg->adaptive_flow;
    out_state->preset = lsfg->flow_preset_code;
    out_state->requested_scale = lsfg->requested_flow_scale;
    out_state->active_scale = lsfg->active_flow_scale;
    out_state->target_scale =
        lsfg->adaptive_flow ? telemetry.targetScale : lsfg->requested_flow_scale;
    out_state->minimum_scale =
        lsfg->adaptive_flow ? telemetry.minimumScale : lsfg->requested_flow_scale;
    out_state->state_index =
        static_cast<uint32_t>(lsfg->adaptive_flow ? telemetry.stateIndex : 0u);
    out_state->state_count =
        static_cast<uint32_t>(lsfg->adaptive_flow ? telemetry.stateCount : 1u);
    out_state->generation_cap = lsfg->adaptive_generation_cap;
    out_state->pressure_active = telemetry.computePressure || telemetry.globalPressure
        || telemetry.outputPressure || telemetry.sourcePressure || lsfg->synthetic_drop_pressure;
    out_state->reason = lsfg->adaptive_flow
        ? AdaptiveFlowController::reasonName(telemetry.reason) : "fixed";
    return true;
}

bool vkr_lsfg_prepare(VkrLsfg* lsfg, uint32_t width, uint32_t height, VkFormat format) {
    if (!lsfg || lsfg->unavailable) return false;
    if (width == 0 || height == 0 || format == VK_FORMAT_UNDEFINED) return false;

    if (!vkr_lsfg_needs_rebuild(lsfg, width, height, format)) {
        return lsfg->chain && lsfg->chain->Valid();
    }

    const float scale = lsfg_effective_flow_scale(lsfg, width);

    lsfg->chain.reset();
    lsfg->chain = std::make_unique<lsfg::LsfgChain>(
        lsfg->device, *lsfg->shaders, VkExtent2D{width, height}, format, scale);
    if (!lsfg->chain->Valid()) {
        LSFG_LOGW("chain build failed at %ux%u; frame generation unavailable", width, height);
        lsfg->chain.reset();
        lsfg->unavailable = true;
        return false;
    }

    lsfg->built_extent = VkExtent2D{width, height};
    lsfg->built_format = format;
    lsfg->built_flow_scale = scale;
    lsfg->frame_count = 0;
    lsfg->plan_calls = 0;
    lsfg->warm_streak = 0;
    lsfg->warm = false;
    lsfg->generated = false;
    lsfg->pacer.Reset();
    lsfg->flow_transition_frames = lsfg->adaptive_flow ? 3u : 0u;
    LSFG_LOGI("chain built at %ux%u, flow %ux%u scale %.2f (requested %.2f, guest %ux%u)", width,
              height, (unsigned)(width * lsfg->built_flow_scale),
              (unsigned)(height * lsfg->built_flow_scale), (double)lsfg->built_flow_scale,
              (double)lsfg->requested_flow_scale, lsfg->peak_guest_extent.width,
              lsfg->peak_guest_extent.height);
    return true;
}

uint32_t vkr_lsfg_plan(VkrLsfg* lsfg, uint32_t capacity, uint64_t source_frames) {
    if (!lsfg || lsfg->unavailable) return 0;

    lsfg->plan = lsfg->pacer.Plan(std::min<size_t>(capacity, VKR_LSFG_MAX_GENERATIONS),
                                  source_frames);
    const lsfg::LsfgPacerStats stats = lsfg->pacer.Stats();

    if (lsfg->adaptive_flow) {
        const auto now = std::chrono::steady_clock::now();
        const bool pressure_fresh =
            lsfg->pressure.valid
            && now - lsfg->pressure.sampled_at <= std::chrono::milliseconds(1500);
        const double elapsed_seconds =
            stats.last_elapsed > 0.0f ? std::min<double>(stats.last_elapsed, 0.250) : 0.0;
        const double frame_budget_ms =
            stats.last_elapsed > 0.0f
                ? static_cast<double>(stats.last_elapsed) * 1000.0
                : (stats.target_rate > 0.0f ? 1000.0 / stats.target_rate : 16.667);
        const double output_target =
            stats.target_rate > 0.0f
                ? stats.target_rate
                : (stats.source_rate > 0.0f
                    ? stats.source_rate * static_cast<double>(lsfg->pacer.Config().multiplier)
                    : 0.0);
        const bool output_valid = pressure_fresh && lsfg->pressure.output_fps > 0.0f;
        const bool output_satisfied =
            output_valid && output_target > 0.0
            && lsfg->pressure.output_fps >= output_target * 0.98;
        const double output_period_ms =
            output_target > 0.0 ? 1000.0 / output_target : 0.0;
        const bool slow_frame_pressure =
            pressure_fresh
            && (lsfg->pressure.slow_frame_ratio >= 0.08f
                || (output_period_ms > 0.0
                    && lsfg->pressure.frame_time_p95_ms > output_period_ms * 1.25));
        const bool output_deficit =
            (output_valid && output_target > 0.0 && !output_satisfied)
            || slow_frame_pressure;

        AdaptiveFlowObservation observation{};
        observation.elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::duration<double>(elapsed_seconds));
        observation.frameBudgetMs = frame_budget_ms;
        // No Native per-pass timestamp query exists yet. Do not invent shader
        // cost; output/source/global pressure remain truthful controller inputs.
        observation.totalLsfgMs = 0.0;
        observation.flowMs = 0.0;
        observation.mipmapsMs = 0.0;
        observation.generationCount = lsfg->plan.generations;
        observation.wsiPresentationPressure = lsfg->synthetic_drop_pressure;
        observation.wsiLossRate = lsfg->synthetic_drop_pressure ? 1.0 : 0.0;
        observation.sourceFps =
            pressure_fresh && lsfg->pressure.source_fps > 0.0f
                ? lsfg->pressure.source_fps : stats.source_rate;
        observation.adaptiveFramegenMode = stats.target_rate > 0.0f;
        observation.scheduledGenerationDensity =
            static_cast<double>(lsfg->plan.generations);
        observation.fixedMultiplierMode = stats.target_rate <= 0.0f;
        observation.outputFps = output_valid ? lsfg->pressure.output_fps : 0.0;
        observation.outputTargetFps = output_target;
        observation.outputCadenceValid = output_valid;
        observation.outputTargeted = stats.target_rate > 0.0f;
        observation.outputTargetSatisfied = output_satisfied;
        observation.fixedMultiplierBaseTarget = stats.target_rate <= 0.0f;
        observation.globalGpuUsagePercent =
            pressure_fresh ? lsfg->pressure.gpu_usage_percent : 0.0;
        observation.globalPressureValid = pressure_fresh;
        observation.thermalStatus =
            pressure_fresh ? std::max(lsfg->pressure.thermal_status, 0) : 0;
        observation.thermalPressureValid =
            pressure_fresh && lsfg->pressure.thermal_status >= 0;
        observation.outputDeficit = output_deficit;
        observation.syntheticDropPressure = lsfg->synthetic_drop_pressure;
        observation.generatedWorkSample = false;
        observation.retainedGeneratedTimingSample = false;
        observation.schedulerTransition = false;
        observation.flowTransition = lsfg->flow_transition_frames > 0;
        observation.valid = stats.rates_settled && frame_budget_ms > 0.0;

        const float previous_scale = lsfg->active_flow_scale;
        lsfg->active_flow_scale = std::clamp(
            lsfg->flow_controller.observe(observation),
            LSFG_FLOW_SCALE_MIN, LSFG_FLOW_SCALE_MAX);
        if (std::fabs(previous_scale - lsfg->active_flow_scale) > 0.0005f) {
            lsfg->flow_transition_frames = 3;
            const auto& telemetry = lsfg->flow_controller.telemetry();
            LSFG_FLOW_LOG(
                "event=scale_transition requested_scale=%.2f active_scale=%.2f "
                "target_scale=%.2f minimum_scale=%.2f state_index=%zu state_count=%zu "
                "pressure_state=%s gpu_pressure=%.1f output_fps=%.2f output_target=%.2f",
                static_cast<double>(lsfg->requested_flow_scale),
                static_cast<double>(lsfg->active_flow_scale),
                static_cast<double>(telemetry.targetScale),
                static_cast<double>(telemetry.minimumScale),
                telemetry.stateIndex,
                telemetry.stateCount,
                AdaptiveFlowController::reasonName(telemetry.reason),
                pressure_fresh ? static_cast<double>(lsfg->pressure.gpu_usage_percent) : -1.0,
                output_valid ? static_cast<double>(lsfg->pressure.output_fps) : 0.0,
                output_target);
        } else if (lsfg->flow_transition_frames > 0) {
            --lsfg->flow_transition_frames;
        }

        const auto& flow = lsfg->flow_controller.telemetry();
        const bool at_minimum =
            lsfg->active_flow_scale <= flow.minimumScale + 0.0005f;
        const bool severe_pressure =
            at_minimum && (
                lsfg->synthetic_drop_pressure
                || (pressure_fresh
                    && lsfg->pressure.gpu_usage_percent >= 96.0f
                    && output_deficit));
        if (stats.target_rate > 0.0f && severe_pressure) {
            lsfg->density_pressure_seconds += elapsed_seconds;
            lsfg->density_recovery_seconds = 0.0;
            if (lsfg->density_pressure_seconds >= 0.60
                    && lsfg->adaptive_generation_cap > 1) {
                --lsfg->adaptive_generation_cap;
                lsfg->density_pressure_seconds = 0.0;
                LSFG_FLOW_LOG(
                    "event=generation_density_backoff reason=flow-minimum-pressure "
                    "generation_cap=%u active_scale=%.2f gpu_pressure=%.1f output_deficit=%d",
                    lsfg->adaptive_generation_cap,
                    static_cast<double>(lsfg->active_flow_scale),
                    pressure_fresh ? static_cast<double>(lsfg->pressure.gpu_usage_percent) : -1.0,
                    output_deficit ? 1 : 0);
            }
        } else {
            lsfg->density_pressure_seconds = 0.0;
            const bool recovery =
                stats.target_rate > 0.0f && at_minimum && pressure_fresh
                && lsfg->pressure.gpu_usage_percent <= 88.0f
                && (!output_valid || output_satisfied)
                && !lsfg->synthetic_drop_pressure;
            if (recovery && lsfg->adaptive_generation_cap < VKR_LSFG_MAX_GENERATIONS) {
                lsfg->density_recovery_seconds += elapsed_seconds;
                if (lsfg->density_recovery_seconds >= 2.0) {
                    ++lsfg->adaptive_generation_cap;
                    lsfg->density_recovery_seconds = 0.0;
                    LSFG_FLOW_LOG(
                        "event=generation_density_recovery generation_cap=%u active_scale=%.2f",
                        lsfg->adaptive_generation_cap,
                        static_cast<double>(lsfg->active_flow_scale));
                }
            } else {
                lsfg->density_recovery_seconds = 0.0;
            }
        }

        if (stats.target_rate > 0.0f) {
            lsfg->plan.generations =
                std::min<size_t>(lsfg->plan.generations, lsfg->adaptive_generation_cap);
        }
        lsfg->synthetic_drop_pressure = false;
    }

    lsfg->warm = lsfg->plan.warm && lsfg->frame_count + 1 >= LSFG_REQUIRED_FRAMES;
    lsfg->warm_streak = lsfg->warm ? lsfg->warm_streak + 1 : 0;
    lsfg->generated =
        lsfg->warm && lsfg->warm_streak >= LSFG_RECURRENCE_FRAMES && lsfg->plan.generations > 0;

    if ((lsfg->plan_calls++ % LSFG_TELEMETRY_INTERVAL) == 0) {
        const float wanted =
            stats.source_rate * static_cast<float>(lsfg->plan.generations + 1);
        VkrLsfgFlowState flow{};
        vkr_lsfg_get_flow_state(lsfg, &flow);
        LSFG_LOGI("pace gen=%zu max=%zu cap=%u guest=%.1f loop=%.1f refresh=%.1f target=%.0f "
                  "slots=%.2f drawn=%llu needs=%.1fHz%s%s",
                  lsfg->plan.generations, lsfg->pacer.MaxGenerations(), capacity,
                  (double)stats.source_rate, (double)stats.loop_rate,
                  (double)stats.refresh_rate, (double)stats.target_rate, (double)stats.slots,
                  (unsigned long long)stats.last_drawn, (double)wanted,
                  (stats.refresh_rate > 0.0f && wanted > stats.refresh_rate + 1.0f)
                      ? " PANEL-BOUND"
                      : "",
                  stats.rates_settled ? (lsfg->warm ? "" : " cold") : " sampling");
        LSFG_FLOW_LOG(
            "event=state mode=%s preset=%u requested_scale=%.2f active_scale=%.2f "
            "target_scale=%.2f minimum_scale=%.2f state_index=%u state_count=%u "
            "generation_cap=%u pressure_state=%s",
            flow.adaptive ? "adaptive" : "fixed",
            flow.preset,
            static_cast<double>(flow.requested_scale),
            static_cast<double>(flow.active_scale),
            static_cast<double>(flow.target_scale),
            static_cast<double>(flow.minimum_scale),
            flow.state_index,
            flow.state_count,
            flow.generation_cap,
            flow.reason ? flow.reason : "none");
    }

    return lsfg->generated ? static_cast<uint32_t>(lsfg->plan.generations) : 0;
}

void vkr_lsfg_process(VkrLsfg* lsfg, VkCommandBuffer cmd, VkImage source, uint32_t width,
                      uint32_t height, uint32_t generations) {
    if (!lsfg || !lsfg->chain || !lsfg->chain->Valid()) return;

    const uint64_t count = lsfg->frame_count++;
    lsfg->last_count = count;
    lsfg->last_generations = generations;

    CopyPresentedFrame(cmd, source, lsfg->chain->Input(count), VkExtent2D{width, height});
    if (lsfg->warm) {
        lsfg->chain->DispatchShared(cmd, count);
    }
}

void vkr_lsfg_generate_into(VkrLsfg* lsfg, VkCommandBuffer cmd, uint32_t generation,
                            uint32_t target_index, VkImage target_image, VkImageView target_view,
                            uint32_t width, uint32_t height) {
    if (!lsfg || !lsfg->chain || !lsfg->chain->Valid()) return;
    if (target_index >= lsfg::LSFG_MAX_TARGETS) return;

    lsfg->chain->SetTarget(lsfg->device, lsfg->last_generations, generation, target_index,
                           target_view);
    lsfg->chain->DispatchGeneration(cmd, lsfg->last_count, lsfg->last_generations, generation,
                                    target_index, target_image, VkExtent2D{width, height});
}

void vkr_lsfg_forget_targets(VkrLsfg* lsfg) {
    if (!lsfg || !lsfg->chain) return;
    lsfg->chain->ForgetTargets();
}

void vkr_lsfg_reset(VkrLsfg* lsfg) {
    if (!lsfg) return;
    lsfg->pacer.Reset();
    lsfg->frame_count = 0;
    lsfg->last_count = 0;
    lsfg->last_generations = 0;
    lsfg->plan_calls = 0;
    lsfg->peak_guest_extent = VkExtent2D{};
    lsfg->warm_streak = 0;
    lsfg->warm = false;
    lsfg->generated = false;
    lsfg->plan = {};
    lsfg->synthetic_drop_pressure = false;
    lsfg->density_pressure_seconds = 0.0;
    lsfg->density_recovery_seconds = 0.0;
    lsfg->adaptive_generation_cap = VKR_LSFG_MAX_GENERATIONS;
    if (lsfg->adaptive_flow) {
        lsfg->flow_controller.reset();
        const auto seed = AdaptiveFlowController::conservativeSeedScale(
            ParseFlowPreset(lsfg->flow_preset_code), lsfg->requested_flow_scale);
        if (seed) lsfg->flow_controller.seedCurrentScale(*seed);
        lsfg->active_flow_scale = lsfg->flow_controller.currentScale();
    }
    if (lsfg->chain) {
        lsfg->chain->ResetHistory();
    }
}
