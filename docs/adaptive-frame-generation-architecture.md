# Adaptive architecture

Adaptive Frame Generation is owned by the native LSFG scheduler. GameNative supplies configuration and display context only.

The normal FPS limiter and LSFG output target are separate values. The normal limiter may constrain real/source presentation paths. `lsfgAdaptiveOutputTarget` describes the desired final presented rate. Adaptive mode treats it as an objective; fixed multiplication uses it only as the output cadence. If no target has been saved, GameNative resolves the active display refresh rate when LSFG starts.

The native scheduler never intentionally delays real source frames to hit the target. It determines how many generated frames are useful, probes higher generation levels only when required, measures their effect on source throughput, and falls back with cooldown when a probe is counterproductive.

When generation is useful, the native output pacer places each generated frame and the following source frame on one monotonic timeline. This prevents mailbox/compositor replacement of a back-to-back present burst. When no generated frame is scheduled, the output pacer is bypassed and the source path remains governed only by the existing GameNative/DXVK systems.

Hot enable, disable, flow-scale, and multiplier changes update the existing LSFG context without returning `VK_ERROR_OUT_OF_DATE` to the game. Backend/performance and present-mode selections are context-bound and take effect on natural swapchain creation instead of forcing a live game swapchain rebuild.

Native telemetry reports final output, source, and generated FPS separately. The HUD may display final output FPS, but GameNative power, cluster, CPU, GPU, and bus tuning consume source FPS. Generated events therefore cannot masquerade as extra game throughput or trigger a false downclock.
