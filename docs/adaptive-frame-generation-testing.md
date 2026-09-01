# Adaptive Frame Generation device-test signals

For the first device pass, capture `LsfgVkManager`, `LsfgAdaptive`, and native `lsfg-vk: adaptive` / `lsfg-vk: metrics` lines.

Expected controller sequence under a target that needs interpolation:

1. `warmup-complete` after the source-only baseline.
2. `probe-start` only when the proven ceiling cannot meet the target.
3. `probe-accepted` when useful output throughput improves without a source-FPS collapse.
4. `probe-rejected` followed by a non-zero cooldown when a higher level regresses throughput.
5. `stall-reset` after a long source interval, followed by a new source-only baseline.

GameNative should log an independent `outputTarget`; PowerManager source-cap changes should log that the LSFG output target is being preserved.

For visible-output validation, do not use a 60 FPS source with a 60 FPS Adaptive target: the correct controller decision is zero generated frames. Use a stable 30 FPS source with a 60 FPS output target, or 50 FPS with a 60 FPS target to exercise fractional scheduling. Confirm that native metrics approach the target with non-zero `generated_fps`, while `source_fps` remains close to the ungenerated baseline.

Toggle LSFG off and on repeatedly while the game is running. The game swapchain must not receive `VK_ERROR_OUT_OF_DATE`, Wine must not assert in `vkCreateSwapchainKHR`, and the post-disable power metrics must immediately return to source FPS rather than retaining multiplied output counts.
