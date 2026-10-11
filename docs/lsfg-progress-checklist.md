# LSFG hardening progress checklist

Updated: 2026-10-11. Source changes, tests and this checklist are checkpointed through the GitHub connector.

- GameNative branch: `feature/lsfg-host-presentation-feedback-20261007`.
- Packaged-source candidate: `eb5642a6b59f0815893efff65e62b8f4c4cb669e` (Build #787).
- Shared runtime: `Ragnarok93/lsfg-vk-android`, branch `fix/host-presentation-feedback-20261007`, commit `5f14ebc3002401d4c762966cddf1793b3d3ec3f5`.
- Audit/report checkpoints after the candidate change no packaged source; `[skip ci]` preserves its in-flight build.

## Implementation

- [x] Restore all remotely saved work after workspace maintenance.
- [x] Preserve native 2x/3x/4x slot phases and source cadence without accumulating expired-slot debt.
- [x] Scope delivery, activation, telemetry and governors to the installed runtime/session/configuration/feedback revision.
- [x] Distinguish confirmed physical FPS, untimed delivery, unavailable measurements and measured zero; expire coherent pressure measurements.
- [x] Keep Adaptive density independent of Fixed Flow; separate compute-origin and delivery-origin recovery.
- [x] Hold failed density probes at the pre-probe operating point until a sustained material change. Source-only and working 2x baselines each permit one failed probe over 300 seconds, replacing 79 and 107 repeated attempts respectively.
- [x] Preserve settled Adaptive Flow quality while rejecting empty/stale pressure and repeated unproductive probes.
- [x] Capture settings transactions and reject retired/dequeued publishers; Off remains Off through unrelated edits.
- [x] Drain presenters at actual Frame Queue enable/depth boundaries, reset incompatible governor evidence, and retire old feedback revisions. No-op settings preserve holds and settled quality/history.
- [x] Normalize power tuning to the attainable output contract in source-cap units; unknown output cannot authorize trimming.
- [x] Restore the missing handoff runtime multiplier in the Kotlin cap path.
- [x] Acknowledge FPS-cap execution before committing controller state. Reject timeout, interruption, owner/applier/pacing changes, settings edits and target ABA; installed handler rejection cannot fall through. Ordinary source limiting remains supported without LSFG.
- [x] Repair five obsolete source-contract tests while preserving ownership, telemetry, coroutine isolation and debounce guarantees.

## Validation and delivery

- [x] Native regression workflow #17 passed the complete suite at `0998eeb` (the native sources and runtime pin remain identical in Build #787).
- [x] Native governor audit: 30 focused production cases, 2,851,200 boundary observations and 20,532 real pacer/scheduler/Flow cycles.
- [x] Queue audit: 144,000 policy observations, 240,000 fence submissions and 76,816 threaded deliveries.
- [x] FPS-cap extracted production audit: all 11 cases pass; all 11 fail against the pre-fix application function. Saved as `tools/power_cap_application_audit_test.py`.
- [x] All 16 affected source contracts pass from both repository and Gradle module working directories.
- [x] Build #782 compiled Android production sources and executed 290 selected tests: 284 passed. Its five obsolete contracts and one real cap-acknowledgement regression are now addressed.
- [x] Build #787 rebuilt both native libraries; persistent signer verification, version metadata generation and presentation regressions passed.
- [ ] Build #787 Android compilation and all selected LSFG/power interaction tests, including 12 new cap behavior cases.
- [ ] Assemble debug APK and verify package identity, increasing version code, persistent signer, JNI exports and native source provenance.
- [ ] Publish the fresh verified APK for testing and feedback.
- [ ] Physical-device acceptance: actual displayed cadence, visuals, thermals, input latency and sustained operation across both backends, Fixed 2x/3x/4x and Adaptive generation, Fixed/Adaptive Flow, FIFO/Mailbox and queue Off/0/1/2.

## Current state

Build #787 is running its Android compilation/application-test step. No APK is claimed yet. The tests execute production behavior with deterministic clocks or platform boundary fakes; they do not establish physical device delivery or performance. Detailed feature behavior and acceptance scope are recorded in `docs/lsfg-behavior-audit-20261011.md`.
