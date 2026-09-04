# Adaptive architecture

Adaptive Frame Generation is owned by the native LSFG scheduler. GameNative supplies configuration, user intent, and display context only.

The GameNative FPS limiter is the single frame-rate authority for Adaptive Frame Generation. Its active cap is applied to the real/source pacing path and is also the Adaptive controller's final-output target. Adaptive cannot retain, infer, or restore a second independent output target; when the limiter is disabled, native configuration resolves Adaptive generation inactive until a positive limiter cap is available again. Fixed multiplication still derives its output cadence from the source cap and configured multiplier.

The native scheduler never intentionally delays real source frames to hit the Adaptive target. Its controller consumes game/source cadence measured outside the previous LSFG handoff/wait/generated-present cycle, and combines that cadence with measured AHB handoff, framegen dispatch/completion, and generated-present cost. Probe thresholds are expressed relative to the measured source budget rather than to a Samsung- or Adreno-specific millisecond constant.

When generation is useful, the native output pacer places each generated frame and the following source frame on one drift-corrected monotonic timeline. When Adaptive decides that zero generated frames are required, this is an adaptive-zero state rather than user-facing Off: the current FG-capable swapchain remains valid and the source frame is presented directly.

## Off and WSI transitions

User-facing Off is serialized as `enabled = false` while preserving the user's selected multiplier, backend mode, and present-mode preference for a later re-enable. The resident Vulkan layer therefore remains available for hot reload without pretending that Adaptive-zero is Off.

Runtime config changes are staged by the native reload worker. The presentation thread never reparses `conf.toml`, performs timestamp/filesystem polling, or publishes stats synchronously. Non-WSI policy changes may be adopted from the cached snapshot without recreating the game swapchain.

Changes that alter WSI requirements are different. Enable/disable transitions, crossing the active multiplier boundary, generation-capacity changes that alter required swapchain headroom, or present-mode changes may require restoration/recreation. For those transitions the layer presents the current real game frame once with native `vkQueuePresentKHR`, then returns `VK_ERROR_OUT_OF_DATE_KHR` so the game recreates from its original swapchain request. A short transition hitch is acceptable; sustained Off must then remain a true native-present fast path with no `LsContext::present`, adaptive planning, or LSFG pacing.

The layer records the game's original `minImageCount` and present mode separately from the effective FG swapchain request. Recreated Off-state WSI therefore restores game provenance instead of leaving LSFG-inflated image count or an LSFG-selected present mode active. This contract is capability-driven and applies equally to stock Xclipse Vulkan and A6xx/Turnip or stock Adreno paths; vendor-specific exceptions require runtime evidence and explicit diagnostics.

Native telemetry reports final output, source, and generated FPS separately. The HUD may display final output FPS, but GameNative power, cluster, CPU, GPU, and bus tuning consume source FPS. Generated events therefore cannot masquerade as extra game throughput or trigger a false downclock.

## Coordinator ownership

GameNative permanently owns source pacing. `SourceFramePacingCoordinator` coalesces identical cap writes for the same live renderer, re-applies the current cap once when the renderer instance changes, and invalidates its cache at session teardown. Both source pacing sinks receive the same resolved limiter value: `XServerView.setFrameRateLimit()` for the renderer/platform hint and `ShmFramePacer.setFrameRateLimit()` for the actual source limiter. LSFG callbacks do not replace this ownership path.

## Validation contract

Physical acceptance requires separate logs from Xclipse 940 and A6xx. Each run must prove non-zero successful generated presents when the limiter target requires generation, stable source cadence and stage-cost metrics while AFG is active, and a clean sustained-Off interval after an on→off transition. CI/static contracts establish code-path correctness but do not substitute for either device run.
