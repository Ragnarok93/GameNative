# Adaptive Frame Generation device-test signals

For the first device pass, capture `LsfgVkManager`, `LsfgAdaptive`, and native `lsfg-vk: adaptive` / `lsfg-vk: metrics` lines.

Expected controller sequence under a target that needs interpolation:

1. `warmup-complete` after the source-only baseline.
2. `probe-start` only when the proven ceiling cannot meet the target.
3. `probe-accepted` when useful output throughput improves without a source-FPS collapse.
4. `probe-rejected` followed by a non-zero cooldown when a higher level regresses throughput.
5. `stall-reset` after a long source interval, followed by a new source-only baseline.

GameNative should log an independent `outputTarget`; PowerManager source-cap changes should log that the LSFG output target is being preserved.
