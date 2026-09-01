# Adaptive architecture

Adaptive Frame Generation is owned by the native LSFG scheduler. GameNative supplies configuration and display context only.

The normal FPS limiter and LSFG Adaptive output target are separate values. The normal limiter may constrain real/source presentation paths. `lsfgAdaptiveOutputTarget` describes the desired final presented rate and is serialized to native `fps_limit` only when Adaptive FrameGen is enabled.

The native scheduler never intentionally delays real source frames to hit the target. It determines how many generated frames are useful, probes higher generation levels only when required, measures their effect on source throughput, and falls back with cooldown when a probe is counterproductive.
