# LSFG hardening progress checklist

Branch: `feature/lsfg-host-presentation-feedback-20261007`.
Updated: 2026-10-11. Each advancement is saved with this report through the GitHub connector.

- [x] Restore the saved source checkpoint after workspace maintenance; base `8919398`.
- [x] Preserve Legacy/Native cadence, settings ownership, Adaptive Flow, pressure validity, frame queue, and power-domain fixes already committed.
- [x] Native regression workflow #11 passed; Build #772 rebuilt both native libraries and passed native regression checks.
- [x] Correct Build #772 Kotlin integration failure: restore the handoff-published runtime multiplier in the source FPS update path; inactive generation uses 1x. Android compilation verification remains pending.
- [x] Reproduce and fix repeated failed Native density recovery probes under unchanged operating conditions. Retain the pre-probe baseline; require sustained material change before retrying a rejected point.
- [x] Extend the stationary regression to a working 2x density baseline: 107 failed retries on the old code become one failed probe, preserving the working baseline.
- [x] Notify the Native governor when Frame Queue enable/depth actually changes; preserve settled density and history while clearing incompatible rejected-probe evidence. No-op policy publication preserves the hold. Drain the presenter before clearing host evidence and retire the previous feedback revision at the next captured policy publication.
- [x] Verify the real queue setter plus real Native mode publication: stale revisions cannot consume the pending boundary; the next current revision retires the old feedback floor; metadata-only updates and no-op queue settings preserve evidence and holds, without a swapchain rebuild. The previous mode code fails this regression.
- [ ] Run affected regression tests and the complete workflow application checks without skipping failures.
- [x] Build #782: both native libraries, full native suite, Android production compilation, and all 290 selected application tests executed. 284 passed; six failures must be resolved before packaging.
- [ ] Fix the real FPS-cap application regression: background fallback reports success before main-thread execution, and a rejected installed applier falls through to an unowned fallback. Guard completion, timeout, owner and target changes with behavior tests.
- [x] Repair five outdated source-contract checks while preserving their original ownership, telemetry, isolation and debounce guarantees: repository-root lookup, renamed internal debounce scheduling and owner reservation, scoped presentation callbacks, and the split screen/controller/runtime functions. Local JUnit runs 16 checks; only the known FPS-cap completion regression remains failing.
- [ ] Build the debug APK and verify package identity, increasing version code, persistent signing certificate, JNI exports, and native source provenance.
- [ ] Provide the fresh verified APK for testing and feedback.
- [ ] Physical-device acceptance: cadence, visuals, thermals, latency, and sustained operation across both backends, modes, multipliers, Flow settings, queue depths, and presentation modes. No device result is asserted by the automated tests.

## Current evidence and next action

Frame Queue boundary RED reproduction: an actual depth change left the rejected Native probe active. Both extracted setter and actual governor boundary tests now pass. The complete actual Native mode-publication regression also fails on the previous mode code and passes on the fix, protecting the new queue policy from stale revision feedback. Actual enable/depth changes clear incompatible evidence; identical settings preserve the hold. Settled generation cap, Flow quality and frame history remain intact. The complete queue audit also passes: 144,000 policy observations, 240,000 fence submissions and 76,816 threaded deliveries. Native regression workflow #16 passed the complete native suite on `e2163fa`; Android compilation/testing and APK verification remain pending.

Stationary density RED reproduction: source 30 FPS, GPU 60%, healthy source delivery, generated delivery or admission fails whenever density exceeds zero. Both cases produced 79 recovery attempts and 78 failures in 300 simulated seconds. Minor source/GPU jitter also reproduced the loop. With the fix, each runs one failed probe then holds for the remainder of 300 seconds. Sustained changed conditions still restore full density. The 28 focused production governor cases, 2,851,200 boundary observations, 20,532 real pacer/scheduler/Flow interaction cycles, and seven host-density regressions all pass locally. The Android workflow must still validate compilation, full application checks and packaging.

Build #772 failed `compileLegacyDebugKotlin` at five references to an undeclared local `runtimeMultiplier` in `XServerScreen.applyFpsLimiterToEngines`. It did not produce an APK. The local value now derives from the existing handoff-published `lsfgRuntimeMultiplier` and active flag, consistently feeding display hints and budget diagnostics. The next workflow must verify Android compilation and application tests. Independently reproduce the stationary density recovery loop before changing governor behavior. Existing behavior audit and regression evidence are recorded in `docs/lsfg-behavior-audit-20261011.md`.

Checkpoint: added six FPS-cap behavior regressions for authoritative rejection, timeout cancellation, acknowledged application, renderer replacement, newer target edits and target edits that return to the original value. The extracted existing implementation reproduces four failures; Android/Robolectric validation follows the fix.

FPS-cap implementation checkpoint: all cap requests now wait for main-thread acknowledgement, reject stale owner/pacing/applier generations, invalidate on every target edit (including ABA), and cancel timeout/interruption/post failures. Installed handlers own rejection. Screen handlers remain available for ordinary source limiting without LSFG and reject retired views, exiting sessions and obsolete settings revisions. Manual limiter/LSFG edits invalidate pending power cap commands. All 16 affected source contracts pass from both repository and Gradle module working directories. The six new behavior tests still require Android/Robolectric CI validation.
