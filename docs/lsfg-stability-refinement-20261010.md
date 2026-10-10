# LSFG stability refinement

Base: `c870d8e2e93f73318f70993e307385345d5cda17` on
`feature/lsfg-host-presentation-feedback-20261007`.

## Changes

- Use one mode-aware confirmation selector for host recording, Native feedback,
  availability getters and telemetry. Present-wait success is physical evidence
  only in FIFO. In Mailbox a replaced image's wait also succeeds; without GOOGLE
  actual presentation timing, report physical measurement unavailable rather
  than count replacements as displayed frames. This applies to both runtimes.
  Specification: https://docs.vulkan.org/refpages/latest/refpages/source/vkWaitForPresentKHR.html
- Bound Mailbox dispatch waits independently of WSI timestamp support. Rejected
  timestamps cannot fall back into sleeping on raw provenance. Valid timestamps
  retain dispatch pacing; the 250-ms existing future-intent limit also applies
  when GOOGLE timing is unavailable.
- Hold the shared frame mutex during completion-only render-loop wakes, excluding
  concurrent manager transactions that retire/reset queue and presentation state.
- Native Flow-only graph changes wait all GPU users but leave pending host
  presentations running. Composite, resolution, runtime and swapchain retirement
  retain the presenter drain. Source resources, compiled pipelines and context
  identity remain reusable.
- Native adaptive feedback uses the scheduler's refresh-clamped target. Flow
  compares confirmed physical output with a refresh-reachable target, including
  fixed multiplier mode. Generation still honors the configured fixed multiplier.

No Native shader/generation algorithm or admission policy is changed.

## Verification and limits

`tools/lsfg_stability_regression_test.py` compiles production policy, dispatch,
completion-locking and retirement paths against deterministic stubs. Regressions
fail on the base behavior: Mailbox availability is wrongly true, rejected future
intent sleeps, completion processing permits a concurrent settings writer, and
Flow retirement drains presentation. It also exercises rebuild gates and physical
target clamping. Existing presentation, display-timing and Native pacer checks
remain required. The Android workflow runs the Native bridge and selected app
tests, compiles both native libraries, and verifies APK signing/provenance.

Skipping the Flow presenter drain removes a blocking dependency; it does not
prove an on-device speedup or eliminate allocation/GPU fence stalls. Physical
FPS, input latency and sustained stability still require device testing.

## Device feedback

Install the upgradeable debug APK over the existing APK with the same persistent
debug signer. Keep the game, resolution, driver and refresh rate fixed for A/B
comparison with Build #763. Export both app and LSFG logs after each run.

1. Test Legacy and Native at fixed 2x/3x/4x for two minutes each, then Adaptive
   targets below/equal to refresh with fluctuating source FPS.
2. Compare FIFO and Mailbox. If Mailbox lacks GOOGLE timing, physical confirmation
   must be unavailable; logical/WSI FPS must not be reported as physical proof.
3. Compare Frame Queue Off and enabled depths 0/1/2. Change depths and switch
   backends repeatedly while generating frames; check for freezes, stale evidence,
   source starvation and unnecessary swapchain rebuilds.
4. Switch Fixed Flow scales, then test each Adaptive Flow preset under load.
   Native Flow-only retirement should log `reason=flow-gpu-users-only` and
   `presenter_drained=0`; non-Flow retirement should still drain presentation.
5. Run the chosen configuration for at least ten minutes. Report visible pacing,
   input latency, transition stalls and crashes alongside both log exports.
