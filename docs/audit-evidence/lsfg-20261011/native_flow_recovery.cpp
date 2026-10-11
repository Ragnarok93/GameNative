
#include <algorithm>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <string>
#include "adaptive_flow_controller.hpp"
constexpr unsigned VKR_LSFG_MAX_GENERATIONS=3,VKR_LSFG_FLOW_ADAPTIVE=1,VKR_LSFG_FLOW_FIXED=0;
constexpr unsigned VKR_LSFG_FLOW_PRESET_QUALITY=0,VKR_LSFG_FLOW_PRESET_BALANCED=1,VKR_LSFG_FLOW_PRESET_LOW=2,VKR_LSFG_FLOW_PRESET_AUTO=3;
constexpr float LSFG_FLOW_SCALE_MIN=.25f,LSFG_FLOW_SCALE_MAX=1.f;
struct VkExtent2D { unsigned width{},height{}; };
using VkFormat = int; constexpr int VK_FORMAT_UNDEFINED=0;
void log(const char*,...) {}
#define LSFG_FLOW_LOG(...) log(__VA_ARGS__)
namespace lsfg {
struct Device {};
struct LsfgShaders {};
struct LsfgChain { void ResetHistory() {} };
struct LsfgPacerConfig { unsigned multiplier=4,target_rate=120; float refresh_rate=120; };
struct LsfgPlan { size_t generations=3; bool warm=true; };
struct LsfgPacerStats { float target_rate=120,source_rate=30,refresh_rate=120,last_elapsed=1.f/30; bool rates_settled=true; uint64_t last_drawn=1; };
struct LsfgPacer {
    LsfgPacerConfig config{}; LsfgPacerStats stats{};
    LsfgPacerConfig Config() const { return config; }
    void SetConfig(LsfgPacerConfig value) { config=value; stats.target_rate=value.target_rate; stats.refresh_rate=value.refresh_rate; }
    LsfgPlan Plan(size_t cap,uint64_t) { return {std::min<size_t>(cap,config.multiplier-1),true}; }
    LsfgPacerStats Stats() const { return stats; }
    void Reset() {}
};
}
struct FakeFlowController : AdaptiveFlowController {
    AdaptiveFlowObservation last{};
    float observe(const AdaptiveFlowObservation& observation) { last=observation; return AdaptiveFlowController::observe(observation); }
};
typedef struct VkrLsfgPresentationPressure {
    bool confirmation_available;
    bool pressure_active;
    bool wsi_recovery_active;
    bool source_delivery_healthy;
    float generated_delivery_efficiency;
    float source_delivery_efficiency;
    float confirmation_timeout_rate;
    float output_target_deficit_ratio;
} VkrLsfgPresentationPressure;
AdaptiveFlowPreset ParseFlowPreset(uint32_t preset) {
    switch (preset) {
        case VKR_LSFG_FLOW_PRESET_BALANCED: return AdaptiveFlowPreset::Balanced;
        case VKR_LSFG_FLOW_PRESET_LOW: return AdaptiveFlowPreset::Low;
        case VKR_LSFG_FLOW_PRESET_AUTO: return AdaptiveFlowPreset::Auto;
        default: return AdaptiveFlowPreset::Quality;
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
    FakeFlowController flow_controller{};

    struct RuntimePressure {
        float gpu_usage_percent{-1.0f};
        int thermal_status{-1};
        float source_fps{};
        float output_fps{};
        float frame_time_p95_ms{};
        float slow_frame_ratio{};
        std::chrono::steady_clock::time_point sampled_at{};
        bool sample_valid{};
        bool gpu_valid{};
        bool thermal_valid{};
        bool source_valid{};
        bool output_valid{};
        bool frame_time_valid{};
        bool slow_ratio_valid{};
    } pressure;

    bool synthetic_drop_pressure{};
    VkrLsfgPresentationPressure presentation_pressure{};
    uint32_t adaptive_generation_cap{VKR_LSFG_MAX_GENERATIONS};
    double density_pressure_seconds{};
    double density_recovery_seconds{};
    uint32_t flow_transition_frames{};
    uint32_t density_transition_frames{};
    bool density_requires_gpu_recovery{};
    struct DensityRecoveryPoint {
        uint32_t cap{};
        float source_fps{};
        float gpu_usage_percent{};
        float flow_scale{};
        bool gpu_valid{};
        bool valid{};
    } density_probe_point{}, density_rejected_point{};
    double density_rearm_seconds{};
    double density_probe_healthy_seconds{};

    uint64_t frame_count{};
    uint64_t last_count{};
    size_t last_generations{};
    uint64_t plan_calls{};
    uint32_t warm_streak{};
    bool warm{};
    bool generated{};
    bool unavailable{};
};
void vkr_lsfg_reset_density_evidence(VkrLsfg* lsfg) {
    if (!lsfg) return;
    lsfg->density_pressure_seconds = 0.0;
    lsfg->density_recovery_seconds = 0.0;
    lsfg->density_probe_point = {};
    lsfg->density_rejected_point = {};
    lsfg->density_rearm_seconds = 0.0;
    lsfg->density_probe_healthy_seconds = 0.0;
    lsfg->density_transition_frames = 3;
    lsfg->synthetic_drop_pressure = false;
    lsfg->pressure = {};
    lsfg->presentation_pressure = {};
}
void vkr_lsfg_configure(VkrLsfg* lsfg, uint32_t multiplier, uint32_t target_rate,
                        float flow_scale, uint32_t flow_mode, uint32_t flow_preset,
                        float refresh_rate, uint64_t config_revision) {
    if (!lsfg) return;

    lsfg::LsfgPacerConfig config = lsfg->pacer.Config();
    const bool workload_changed = config.multiplier != multiplier
        || config.target_rate != target_rate
        || std::fabs(config.refresh_rate - refresh_rate) > 0.01f;
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
            if (seed) (void)lsfg->flow_controller.seedCurrentScale(*seed);
            lsfg->active_flow_scale = lsfg->flow_controller.currentScale();
            lsfg->flow_transition_frames = 3;
        } else {
            lsfg->active_flow_scale = lsfg->flow_controller.currentScale();
        }
    } else {
        lsfg->active_flow_scale = requested;
        lsfg->flow_transition_frames = 0;
    }
    if (workload_changed || flow_contract_changed) {
        // Preserve settled quality while rejecting evidence accumulated for a
        // different multiplier, target or panel cadence.
        if (adaptive && !flow_contract_changed) {
            const float retained_scale = lsfg->active_flow_scale;
            lsfg->flow_controller.reset();
            (void)lsfg->flow_controller.seedCurrentScale(retained_scale);
            lsfg->active_flow_scale = lsfg->flow_controller.currentScale();
        }
        lsfg->density_pressure_seconds = 0.0;
        lsfg->density_recovery_seconds = 0.0;
        lsfg->synthetic_drop_pressure = false;
        lsfg->pressure = {};
        lsfg->density_transition_frames = 3;
        lsfg->density_probe_point = {};
        lsfg->density_rejected_point = {};
        lsfg->density_rearm_seconds = 0.0;
        lsfg->density_probe_healthy_seconds = 0.0;
    }
    if (target_rate == 0) {
        // Fixed generation keeps the user's multiplier authoritative.
        lsfg->adaptive_generation_cap = VKR_LSFG_MAX_GENERATIONS;
        lsfg->density_pressure_seconds = 0.0;
        lsfg->density_recovery_seconds = 0.0;
        lsfg->density_transition_frames = 0;
        lsfg->density_requires_gpu_recovery = false;
        lsfg->density_probe_point = {};
        lsfg->density_rejected_point = {};
        lsfg->density_rearm_seconds = 0.0;
        lsfg->density_probe_healthy_seconds = 0.0;
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

    pressure.gpu_valid = std::isfinite(gpu_usage_percent)
        && gpu_usage_percent >= 0.0f && gpu_usage_percent <= 100.0f;
    pressure.thermal_valid = thermal_status >= 0 && thermal_status <= 6;
    pressure.source_valid = std::isfinite(source_fps) && source_fps >= 0.0f;
    pressure.output_valid = std::isfinite(output_fps) && output_fps >= 0.0f;
    pressure.frame_time_valid =
        std::isfinite(frame_time_p95_ms) && frame_time_p95_ms >= 0.0f;
    pressure.slow_ratio_valid =
        std::isfinite(slow_frame_ratio) && slow_frame_ratio >= 0.0f
        && slow_frame_ratio <= 1.0f;

    pressure.gpu_usage_percent = pressure.gpu_valid ? gpu_usage_percent : -1.0f;
    pressure.thermal_status = pressure.thermal_valid ? thermal_status : -1;
    pressure.source_fps = pressure.source_valid ? source_fps : 0.0f;
    pressure.output_fps = pressure.output_valid ? output_fps : 0.0f;
    pressure.frame_time_p95_ms =
        pressure.frame_time_valid ? frame_time_p95_ms : 0.0f;
    pressure.slow_frame_ratio =
        pressure.slow_ratio_valid ? slow_frame_ratio : 0.0f;
    pressure.sampled_at = std::chrono::steady_clock::now();
    pressure.sample_valid =
        pressure.gpu_valid || pressure.thermal_valid || pressure.source_valid
        || pressure.output_valid || pressure.frame_time_valid
        || pressure.slow_ratio_valid;
}
void vkr_lsfg_set_refresh_rate(VkrLsfg* lsfg, float refresh_rate) {
    if (!lsfg) return;

    lsfg::LsfgPacerConfig config = lsfg->pacer.Config();
    if (config.refresh_rate == refresh_rate) return;
    vkr_lsfg_configure(lsfg, config.multiplier, config.target_rate,
        lsfg->requested_flow_scale,
        lsfg->adaptive_flow ? VKR_LSFG_FLOW_ADAPTIVE : VKR_LSFG_FLOW_FIXED,
        lsfg->flow_preset_code, refresh_rate, lsfg->config_revision);
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
    lsfg->presentation_pressure = {};
    lsfg->density_pressure_seconds = 0.0;
    lsfg->density_recovery_seconds = 0.0;
    lsfg->adaptive_generation_cap = VKR_LSFG_MAX_GENERATIONS;
    lsfg->density_transition_frames = 0;
    lsfg->density_requires_gpu_recovery = false;
    lsfg->density_probe_point = {};
    lsfg->density_rejected_point = {};
    lsfg->density_rearm_seconds = 0.0;
    lsfg->density_probe_healthy_seconds = 0.0;
    lsfg->flow_transition_frames = 0;
    lsfg->pressure = {};
    if (lsfg->adaptive_flow) {
        lsfg->flow_controller.reset();
        const auto seed = AdaptiveFlowController::conservativeSeedScale(
            ParseFlowPreset(lsfg->flow_preset_code), lsfg->requested_flow_scale);
        if (seed) (void)lsfg->flow_controller.seedCurrentScale(*seed);
        lsfg->active_flow_scale = lsfg->flow_controller.currentScale();
    }
    if (lsfg->chain) {
        lsfg->chain->ResetHistory();
    }
}
uint32_t vkr_lsfg_plan(VkrLsfg* lsfg, uint32_t capacity, uint64_t source_frames) {
    if (!lsfg || lsfg->unavailable) return 0;

    lsfg->plan = lsfg->pacer.Plan(std::min<size_t>(capacity, VKR_LSFG_MAX_GENERATIONS),
                                  source_frames);
    const lsfg::LsfgPacerStats stats = lsfg->pacer.Stats();

    if (lsfg->adaptive_flow || stats.target_rate > 0.0f) {
        const auto now = std::chrono::steady_clock::now();
        const bool pressure_fresh =
            lsfg->pressure.sample_valid
            && now - lsfg->pressure.sampled_at <= std::chrono::milliseconds(1500);
        const bool global_pressure_valid =
            pressure_fresh && lsfg->pressure.gpu_valid;
        const bool thermal_pressure_valid =
            pressure_fresh && lsfg->pressure.thermal_valid;
        const double elapsed_seconds =
            stats.last_elapsed > 0.0f ? std::min<double>(stats.last_elapsed, 0.250) : 0.0;
        const double frame_budget_ms =
            stats.last_elapsed > 0.0f
                ? static_cast<double>(stats.last_elapsed) * 1000.0
                : (stats.target_rate > 0.0f ? 1000.0 / stats.target_rate : 16.667);
        const double requested_output_target =
            stats.target_rate > 0.0f
                ? stats.target_rate
                : (stats.source_rate > 0.0f
                    ? stats.source_rate * static_cast<double>(lsfg->pacer.Config().multiplier)
                    : 0.0);
        // Physical delivery cannot exceed panel refresh. Keep the fixed
        // multiplier authoritative for generation, while judging Flow pressure
        // against the reachable physical output instead of an impossible rate.
        const double panel_target = stats.refresh_rate > 1.0f
            ? std::min(requested_output_target, static_cast<double>(stats.refresh_rate))
            : requested_output_target;
        // A retained density cap limits reachable output until the next probe.
        // Do not report the deliberately omitted generations as Flow pressure.
        const double output_target = stats.target_rate > 0.0f && stats.source_rate > 0.0f
            ? std::min(panel_target,
                stats.source_rate * static_cast<double>(lsfg->adaptive_generation_cap + 1))
            : panel_target;
        const bool output_valid =
            pressure_fresh && lsfg->pressure.output_valid;
        const bool output_satisfied =
            output_valid && output_target > 0.0
            && lsfg->pressure.output_fps >= output_target * 0.98;
        // Source-frame p95 is measured in source periods. It must not be
        // compared to the shorter generated-output period.
        const bool output_deficit =
            output_valid && output_target > 0.0 && !output_satisfied;

        AdaptiveFlowObservation observation{};
        observation.elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::duration<double>(elapsed_seconds));
        observation.frameBudgetMs = frame_budget_ms;
        // No Native per-pass timestamp query exists yet. Do not invent shader
        // cost; output/source/global pressure remain truthful controller inputs.
        observation.totalLsfgMs = 0.0;
        observation.flowMs = 0.0;
        observation.mipmapsMs = 0.0;
        const size_t selected_generations = stats.target_rate > 0.0f
            ? std::min<size_t>(lsfg->plan.generations, lsfg->adaptive_generation_cap)
            : lsfg->plan.generations;
        observation.generationCount = selected_generations;
        observation.wsiPresentationPressure =
            lsfg->presentation_pressure.pressure_active
            || lsfg->synthetic_drop_pressure;
        observation.wsiLossRate =
            lsfg->presentation_pressure.confirmation_available
                ? std::clamp(
                    1.0 - static_cast<double>(
                        lsfg->presentation_pressure.generated_delivery_efficiency),
                    0.0, 1.0)
                : (lsfg->synthetic_drop_pressure ? 1.0 : 0.0);
        // Guest upload counters can include several window updates consumed
        // by one composition. The pacer tracks unique composed source frames.
        observation.sourceFps = stats.source_rate;
        observation.adaptiveFramegenMode = stats.target_rate > 0.0f;
        observation.scheduledGenerationDensity =
            static_cast<double>(selected_generations);
        observation.fixedMultiplierMode = stats.target_rate <= 0.0f;
        observation.outputFps = output_valid ? lsfg->pressure.output_fps : 0.0;
        observation.outputTargetFps = output_target;
        observation.outputCadenceValid = output_valid;
        observation.outputTargeted = stats.target_rate > 0.0f;
        observation.outputTargetSatisfied = output_satisfied;
        observation.fixedMultiplierBaseTarget = stats.target_rate <= 0.0f;
        observation.globalGpuUsagePercent =
            global_pressure_valid ? lsfg->pressure.gpu_usage_percent : 0.0;
        observation.globalPressureValid = global_pressure_valid;
        observation.thermalStatus =
            thermal_pressure_valid ? lsfg->pressure.thermal_status : 0;
        observation.thermalPressureValid = thermal_pressure_valid;
        observation.outputDeficit = output_deficit;
        observation.syntheticDropPressure = lsfg->synthetic_drop_pressure;
        observation.generatedWorkSample = false;
        observation.generatedCadenceSample = lsfg->last_generations > 0;
        observation.retainedGeneratedTimingSample = false;
        observation.schedulerTransition = lsfg->density_transition_frames > 0;
        observation.flowTransition = lsfg->adaptive_flow && lsfg->flow_transition_frames > 0;
        observation.valid = stats.rates_settled && frame_budget_ms > 0.0
            && stats.last_elapsed > 0.0f && stats.last_elapsed < 0.250f
            && stats.last_drawn > 0;

        const float previous_scale = lsfg->active_flow_scale;
        if (lsfg->adaptive_flow) {
            lsfg->active_flow_scale = std::clamp(
                lsfg->flow_controller.observe(observation),
                LSFG_FLOW_SCALE_MIN, LSFG_FLOW_SCALE_MAX);
        }
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
                global_pressure_valid ? static_cast<double>(lsfg->pressure.gpu_usage_percent) : -1.0,
                output_valid ? static_cast<double>(lsfg->pressure.output_fps) : 0.0,
                output_target);
        } else if (lsfg->flow_transition_frames > 0 && stats.last_drawn > 0) {
            --lsfg->flow_transition_frames;
        }

        const auto& flow = lsfg->flow_controller.telemetry();
        const bool at_minimum =
            lsfg->active_flow_scale <= flow.minimumScale + 0.0005f;
        const bool presentation_pressure =
            lsfg->presentation_pressure.confirmation_available
            && lsfg->presentation_pressure.pressure_active;
        // Admission and delivery failures belong to the density actuator,
        // independently of Flow quality. Compute relief waits for Flow's floor.
        const bool severe_pressure =
            observation.valid && !observation.schedulerTransition && !observation.flowTransition
            && (presentation_pressure || lsfg->synthetic_drop_pressure
                || ((!lsfg->adaptive_flow || at_minimum) && global_pressure_valid
                    && lsfg->pressure.gpu_usage_percent >= 96.0f
                    && output_deficit))
            && !lsfg->presentation_pressure.wsi_recovery_active;
        if (stats.target_rate > 0.0f && severe_pressure) {
            lsfg->density_pressure_seconds += elapsed_seconds;
            lsfg->density_recovery_seconds = 0.0;
            lsfg->density_rearm_seconds = 0.0;
            lsfg->density_probe_healthy_seconds = 0.0;
            if (lsfg->density_pressure_seconds >= 0.60
                    && lsfg->adaptive_generation_cap > 0) {
                lsfg->density_requires_gpu_recovery =
                    lsfg->density_requires_gpu_recovery
                    || (global_pressure_valid
                        && lsfg->pressure.gpu_usage_percent >= 96.0f
                        && output_deficit);
                --lsfg->adaptive_generation_cap;
                if (lsfg->density_probe_point.valid
                        && lsfg->adaptive_generation_cap <= lsfg->density_probe_point.cap) {
                    // Compare with the operating point BEFORE adding work.
                    // Removing the failed generation cannot itself prove that
                    // the downstream path now has capacity for another probe.
                    lsfg->density_rejected_point = lsfg->density_probe_point;
                    lsfg->density_probe_point = {};
                    lsfg->density_rearm_seconds = 0.0;
                    lsfg->density_probe_healthy_seconds = 0.0;
                    LSFG_FLOW_LOG("event=generation_density_probe_rejected generation_cap=%u source_fps=%.1f gpu_pressure=%.1f active_scale=%.2f",
                        lsfg->adaptive_generation_cap,
                        static_cast<double>(lsfg->density_rejected_point.source_fps),
                        lsfg->density_rejected_point.gpu_valid
                            ? static_cast<double>(lsfg->density_rejected_point.gpu_usage_percent) : -1.0,
                        static_cast<double>(lsfg->density_rejected_point.flow_scale));
                }
                lsfg->density_transition_frames = 3;
                lsfg->density_pressure_seconds = 0.0;
                LSFG_FLOW_LOG(
                    "event=generation_density_backoff reason=%s "
                    "generation_cap=%u active_scale=%.2f gpu_pressure=%.1f output_deficit=%d "
                    "presentation_pressure=%d generated_delivery_efficiency=%.3f "
                    "source_delivery_efficiency=%.3f confirmation_timeout_rate=%.3f",
                    presentation_pressure ? "physical-delivery-pressure"
                        : (lsfg->synthetic_drop_pressure
                            ? "synthetic-admission-pressure" : "global-gpu-pressure"),
                    lsfg->adaptive_generation_cap,
                    static_cast<double>(lsfg->active_flow_scale),
                    global_pressure_valid ? static_cast<double>(lsfg->pressure.gpu_usage_percent) : -1.0,
                    output_deficit ? 1 : 0,
                    presentation_pressure ? 1 : 0,
                    static_cast<double>(
                        lsfg->presentation_pressure.generated_delivery_efficiency),
                    static_cast<double>(
                        lsfg->presentation_pressure.source_delivery_efficiency),
                    static_cast<double>(
                        lsfg->presentation_pressure.confirmation_timeout_rate));
            }
        } else {
            lsfg->density_pressure_seconds = 0.0;
            const bool confirmation_recovered =
                !lsfg->presentation_pressure.confirmation_available
                || (lsfg->presentation_pressure.source_delivery_healthy
                    && !lsfg->presentation_pressure.pressure_active
                    && (lsfg->adaptive_generation_cap == 0
                        || lsfg->presentation_pressure.generated_delivery_efficiency >= 0.70f));
            // Healthy delivery and GPU headroom permit a bounded density probe.
            // Full requested cadence cannot be reached under our own cap, and
            // a new Flow preset must not pin that cap above its new scale floor.
            // A device without external pressure telemetry can still recover
            // from confirmed downstream loss. Native source progress and the
            // host's rolling confirmed-delivery evidence provide the proof;
            // compute-origin reductions retain the GPU headroom requirement.
            const bool confirmed_delivery_headroom =
                !lsfg->density_requires_gpu_recovery
                && !global_pressure_valid
                && stats.source_rate > 0.0f
                && (!output_valid
                    || lsfg->pressure.output_fps >= stats.source_rate * 0.97f)
                && lsfg->presentation_pressure.confirmation_available
                && lsfg->presentation_pressure.source_delivery_healthy;
            const bool recovery =
                observation.valid && !observation.schedulerTransition && !observation.flowTransition
                && stats.target_rate > 0.0f
                && ((global_pressure_valid && lsfg->pressure.gpu_usage_percent <= 88.0f)
                    || confirmed_delivery_headroom)
                && (!thermal_pressure_valid || lsfg->pressure.thermal_status <= 2)
                && confirmation_recovered
                && !lsfg->synthetic_drop_pressure;
            const auto& rejected = lsfg->density_rejected_point;
            const bool rejected_point = rejected.valid
                && lsfg->adaptive_generation_cap == rejected.cap;
            const bool material_change = rejected_point
                && (std::fabs(stats.source_rate - rejected.source_fps)
                        >= std::max(3.0f, rejected.source_fps * 0.15f)
                    || (global_pressure_valid && rejected.gpu_valid
                        && lsfg->pressure.gpu_usage_percent <= rejected.gpu_usage_percent - 10.0f)
                    || lsfg->active_flow_scale <= rejected.flow_scale - 0.02f);
            if (recovery && material_change) {
                lsfg->density_rearm_seconds += elapsed_seconds;
                if (lsfg->density_rearm_seconds >= 0.50) {
                    lsfg->density_rejected_point = {};
                    lsfg->density_rearm_seconds = 0.0;
                    LSFG_FLOW_LOG("event=generation_density_probe_rearmed generation_cap=%u source_fps=%.1f",
                        lsfg->adaptive_generation_cap, static_cast<double>(stats.source_rate));
                }
            } else {
                lsfg->density_rearm_seconds = 0.0;
            }
            if (recovery && lsfg->density_probe_point.valid) {
                lsfg->density_probe_healthy_seconds += elapsed_seconds;
                if (lsfg->density_probe_healthy_seconds >= 3.0) {
                    lsfg->density_probe_point = {};
                    lsfg->density_probe_healthy_seconds = 0.0;
                }
            } else {
                lsfg->density_probe_healthy_seconds = 0.0;
            }
            const bool recovery_held = lsfg->density_rejected_point.valid
                && lsfg->adaptive_generation_cap == lsfg->density_rejected_point.cap;
            if (recovery && !recovery_held
                    && lsfg->adaptive_generation_cap < VKR_LSFG_MAX_GENERATIONS) {
                lsfg->density_recovery_seconds += elapsed_seconds;
                const double recovery_hold =
                    lsfg->adaptive_generation_cap == 0 ? 3.0 : 2.0;
                if (lsfg->density_recovery_seconds >= recovery_hold) {
                    lsfg->density_probe_point = {lsfg->adaptive_generation_cap,
                        stats.source_rate, lsfg->pressure.gpu_usage_percent,
                        lsfg->active_flow_scale, global_pressure_valid, true};
                    lsfg->density_probe_healthy_seconds = 0.0;
                    ++lsfg->adaptive_generation_cap;
                    if (lsfg->adaptive_generation_cap == VKR_LSFG_MAX_GENERATIONS)
                        lsfg->density_requires_gpu_recovery = false;
                    lsfg->density_transition_frames = 3;
                    lsfg->density_recovery_seconds = 0.0;
                    LSFG_FLOW_LOG(
                        "event=generation_density_recovery reason=%s "
                        "generation_cap=%u active_scale=%.2f source_delivery_healthy=%d "
                        "generated_delivery_efficiency=%.3f gpu_pressure=%.1f",
                        lsfg->adaptive_generation_cap == 1
                            ? "source-only-probe" : "confirmed-recovery",
                        lsfg->adaptive_generation_cap,
                        static_cast<double>(lsfg->active_flow_scale),
                        lsfg->presentation_pressure.source_delivery_healthy ? 1 : 0,
                        static_cast<double>(
                            lsfg->presentation_pressure.generated_delivery_efficiency),
                        global_pressure_valid
                            ? static_cast<double>(lsfg->pressure.gpu_usage_percent) : -1.0);
                }
            } else {
                lsfg->density_recovery_seconds = 0.0;
            }
        }

        if (stats.target_rate > 0.0f) {
            lsfg->plan.generations =
                std::min<size_t>(lsfg->plan.generations, lsfg->adaptive_generation_cap);
        }
        if (observation.schedulerTransition && observation.valid)
            --lsfg->density_transition_frames;
    }
    // A consumed drop cannot leak into a later mode or settings transaction.
    lsfg->synthetic_drop_pressure = false;


return static_cast<uint32_t>(lsfg->plan.generations);
}

int main() {
 VkrLsfg r; r.adaptive_flow=true;r.flow_controller.configure(true,AdaptiveFlowPreset::Quality);
 (void)r.flow_controller.seedCurrentScale(.8f);r.active_flow_scale=.8f;r.last_generations=3;
 r.presentation_pressure.confirmation_available=true;r.presentation_pressure.source_delivery_healthy=true;
 r.presentation_pressure.generated_delivery_efficiency=1;
 for(int i=0;i<9000;++i) { vkr_lsfg_set_pressure(&r,40,0,30,120,33.333f,0);vkr_lsfg_plan(&r,3,1); }
 std::printf("nativehealthy300s scale=%.2f target=%.2f reason=%s\n",r.active_flow_scale,r.flow_controller.telemetry().targetScale,AdaptiveFlowController::reasonName(r.flow_controller.telemetry().reason));
 AdaptiveFlowController control(AdaptiveFlowPreset::Quality); (void)control.seedCurrentScale(.8f);
 auto observation=r.flow_controller.last; observation.generatedWorkSample=true;observation.totalLsfgMs=2;observation.flowMs=1;
 for(int i=0;i<9000;++i) control.observe(observation);
 std::printf("measuredtimingcontrol scale=%.2f target=%.2f\n",control.currentScale(),control.telemetry().targetScale);
 return r.active_flow_scale>=.999f ? 0 : 1;
}
