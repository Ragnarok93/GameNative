# Adaptive Frame Generation branch contract

This branch separates two frame-rate concepts that must never be conflated:

- **Source FPS limit**: GameNative/XServer/PowerManager pacing for real game frames.
- **Adaptive output target**: LSFG's final presented-frame objective.

`lsfgAdaptiveOutputTarget` is the persisted output objective. When Adaptive FrameGen is enabled and no explicit output target exists, the runtime vsync clock resolves it from the active display refresh rate and hot-reloads `conf.toml`.

PowerManager's Adaptive FPS Cap may change the source-side limit, but its `applyLiveFpsCap` callback does not rewrite LSFG `fps_limit`.

The native scheduler treats the output target as an objective rather than a source limiter. It establishes a source-only baseline, probes one generation level at a time, rejects throughput-regressing levels, backs off after rejection, and resets learned timing after long stalls or hot configuration changes.
