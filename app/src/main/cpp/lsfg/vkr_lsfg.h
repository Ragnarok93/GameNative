#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "vk_dispatch.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VKR_LSFG_MAX_GENERATIONS 3u
#define VKR_LSFG_MAX_TARGETS 7u

#define VKR_LSFG_FLOW_FIXED 0u
#define VKR_LSFG_FLOW_ADAPTIVE 1u

#define VKR_LSFG_FLOW_PRESET_QUALITY 0u
#define VKR_LSFG_FLOW_PRESET_BALANCED 1u
#define VKR_LSFG_FLOW_PRESET_LOW 2u
#define VKR_LSFG_FLOW_PRESET_AUTO 3u

typedef struct VkrLsfg VkrLsfg;

typedef struct VkrLsfgFlowState {
    bool adaptive;
    uint32_t preset;
    float requested_scale;
    float active_scale;
    float target_scale;
    float minimum_scale;
    uint32_t state_index;
    uint32_t state_count;
    uint32_t generation_cap;
    bool pressure_active;
    const char* reason;
} VkrLsfgFlowState;

VkrLsfg* vkr_lsfg_create(VkDevice device, VkPhysicalDevice physical_device,
                         const char* cache_path);
void vkr_lsfg_destroy(VkrLsfg* lsfg);

void vkr_lsfg_configure(VkrLsfg* lsfg, uint32_t multiplier, uint32_t target_rate,
                        float flow_scale, uint32_t flow_mode, uint32_t flow_preset,
                        float refresh_rate, uint64_t config_revision);

void vkr_lsfg_set_refresh_rate(VkrLsfg* lsfg, float refresh_rate);

void vkr_lsfg_set_pressure(VkrLsfg* lsfg, float gpu_usage_percent, int thermal_status,
                           float source_fps, float output_fps, float frame_time_p95_ms,
                           float slow_frame_ratio);

void vkr_lsfg_note_admission(VkrLsfg* lsfg, uint32_t requested, uint32_t admitted);

void vkr_lsfg_set_guest_extent(VkrLsfg* lsfg, uint32_t width, uint32_t height);

bool vkr_lsfg_needs_rebuild(const VkrLsfg* lsfg, uint32_t width, uint32_t height,
                            VkFormat format);

const char* vkr_lsfg_rebuild_reason(const VkrLsfg* lsfg, uint32_t width, uint32_t height,
                                    VkFormat format);

bool vkr_lsfg_prepare(VkrLsfg* lsfg, uint32_t width, uint32_t height, VkFormat format);

bool vkr_lsfg_get_flow_state(const VkrLsfg* lsfg, VkrLsfgFlowState* out_state);

uint32_t vkr_lsfg_plan(VkrLsfg* lsfg, uint32_t capacity, uint64_t source_frames);

void vkr_lsfg_process(VkrLsfg* lsfg, VkCommandBuffer cmd, VkImage source,
                      uint32_t width, uint32_t height, uint32_t generations);

void vkr_lsfg_generate_into(VkrLsfg* lsfg, VkCommandBuffer cmd, uint32_t generation,
                            uint32_t target_index, VkImage target_image, VkImageView target_view,
                            uint32_t width, uint32_t height);

void vkr_lsfg_forget_targets(VkrLsfg* lsfg);

void vkr_lsfg_reset(VkrLsfg* lsfg);

#ifdef __cplusplus
}
#endif
