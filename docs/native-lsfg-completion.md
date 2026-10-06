# Native LSFG 1.3 integration

Native LSFG runs in GameNative's Vulkan compositor. Legacy LSFG remains the
resident guest implicit layer. Selecting Native writes a source-only legacy
configuration, waits for an existing generating layer to acknowledge it, then
initializes the host shader engine. Selecting Legacy disables host generation
before restoring the layer configuration. Shader extraction and handoff work
run on a background executor; superseded requests are canceled.

## Rendering

- Compose each real input into a framebuffer owned by the current frame slot.
- Feed that image into native temporal history. Cursor-only redraws do not add
  source samples or generate another batch from unchanged game content.
- Support fixed 2×, 3×, and 4× and upstream adaptive output targets. The adaptive
  target is bounded by panel refresh and available swapchain images.
- Acquire optional generated outputs with one bounded deadline for the batch.
  An acquisition timeout reduces that batch's interpolation count and preserves
  its source output.
- Generate and enqueue interpolated images before the current source. Native
  uses FIFO so Mailbox cannot discard the preceding interpolations. The user's
  legacy presentation preference is restored on backend disable.
- Keep the existing asynchronous host presenter and per-image presentation
  semaphores. Wait for outstanding work only at resource/configuration changes
  and error recovery; there is no per-frame device/queue idle operation.

The logical device enables supported memory-model, float16 and storage-image
features at creation so Native can be selected without recreating the device.
Missing capabilities, unsupported image formats, failed shader/chain creation
and insufficient WSI capacity leave normal source presentation available and
produce `LSFG_NATIVE` failure logs.

Source and generated images have distinct native delivery IDs, source indices,
interpolation indices, submission IDs and swapchain epochs. Native deliveries
use host display confirmation and are never sent to the legacy feedback socket.
The Native HUD samples actual unique WSI-accepted outputs. That is an accepted
output rate; physical display confirmation is reported separately.

## Lifecycle and readiness

Java retains settings before surface creation and replays them after recreation.
One synchronized application prevents a stale request from enabling Native
after the UI disables it for Legacy. Resize, mode, flow and cache changes retire
native work before replacing resources and reset temporal history.

`runtime-initialized` means the native shader engine accepted its configuration;
it does not prove visible interpolation. Runtime generation readiness requires
an actual generated output accepted by host WSI. Look for
`LSFG_NATIVE: event=generated_present_queued` and then native
`event=display_confirmation kind=generated confirmed=1` to distinguish queued
output from confirmed display. Unsupported display-confirmation extensions
produce explicitly unknown confirmation rather than manufactured success.

The native backend uses upstream's flow-scale/guest-extent policy. The custom
legacy adaptive-flow governor and legacy performance-mode shader selection
remain legacy-specific; this change does not port those separate algorithms.
The XR compositor remains outside the native WSI frame-generation path.

## Verification

The renderer build runs deterministic production-pacer tests for fixed modes,
WSI capacity, adaptive panel limits, fractional credit and reset behavior. A
Java/JNI fake exercises production controls for absent/recreated surfaces,
pending-settings replay, cancellation and accepted-output accounting. Existing
host timing tests cover valid partial `VK_INCOMPLETE` histories and metric
formatting. Android CI also runs the native integration contract and packages
a rebuilt renderer with the `gamenative-native-lsfg-v1.3-complete` marker.

On-device Vulkan rendering has not been exercised in this workspace. Validate
the rebuilt APK on the target device using fixed modes, adaptive output,
Native↔Legacy switches while paused/resumed, surface recreation and the native
display-confirmation records before treating device compatibility as proven.
