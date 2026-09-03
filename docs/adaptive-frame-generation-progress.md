Current native Adaptive branch head intended for and pinned by GameNative: `dc958e14a85cac02e9314c75479215b5c3e6411f`.

Implemented architecture:

- Adaptive output FPS is an objective independent from the real/source FPS limiter.
- The native controller consumes source/game cadence measured outside LSFG's previous handoff, framegen wait, generated presents, and output pacing.
- Probe accept/reject uses source cadence plus measured AHB handoff, framegen dispatch/completion, and generated-present cost relative to the source-frame budget; the policy does not hard-code Xclipse or A6xx timing constants.
- Probe/cooldown windows scale with measured source FPS, proven levels can back off under sustained cost pressure, and fractional carry is preserved while bounded against bursts.
- Generated frames and the following source frame are paced on one drift-corrected output timeline so Android can present them instead of replacing a back-to-back burst in the compositor queue.
- Runtime config parsing/timestamp polling and stats publication are off the Vulkan present thread. The present path adopts only a staged/cached snapshot.
- User-facing Off is `enabled=false`; Adaptive-zero is a separate state. WSI-affecting enable/multiplier/present-mode transitions may perform one native source present and return `VK_ERROR_OUT_OF_DATE_KHR` so the game recreates from its original swapchain request. Sustained Off then uses native `vkQueuePresentKHR` only.
- Original game `minImageCount` and present mode are retained as WSI provenance so LSFG-inflated image count or present-mode choices do not remain active after Off.
- Flow-scale/performance backend selections remain context-bound and take effect at context creation; they are not claimed as live mutations of an existing `LsContext`.
- Android AHB source-image first-use layout is tracked per shared image rather than inferred from global frame count, which is necessary after long Adaptive-zero/direct-present intervals.
- The safe cross-device AHB fence plus bounded `waitContext` path remains the common Xclipse/A6xx baseline. Framegen completion uses a short primary wait plus one remainder retry within the same total timeout budget.
- GameNative reads source and output metrics separately. Power, cluster, and automatic performance tuning consume source FPS so generated-frame events cannot trigger false headroom decisions.

Native CI status for `dc958e14a85cac02e9314c75479215b5c3e6411f`:

- Android capability/portability policy suites: passed.
- Native scheduler/pacer policy tests: passed.
- Android arm64-v8a build: passed.
- Android x86_64 build: passed.

Still required before hardware acceptance:

- Build and unit-test the paired GameNative LegacyDebug revision after updating its native gitlink/runtime marker.
- Validate sustained generation and on→off WSI restoration on Exynos 2400 / Xclipse 940.
- Validate the same contracts on A6xx using the relevant stock Adreno and/or Turnip/Vortek chain.
- Capture validation logs proving source FPS, generated FPS, output FPS, stage costs, successful generated presents, capability decisions, and a clean sustained-Off interval on both GPU classes.
