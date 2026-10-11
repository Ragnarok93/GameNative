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
- [x] Build #787 Android compilation and all selected LSFG/power interaction tests passed, including 12 new cap behavior cases.
- [x] Assemble the upgradeable debug APK (Build #787).
- [x] Verify package identity, increasing version code, persistent signer, JNI exports and native source provenance.
- [x] Publish the fresh verified APK for testing and feedback.
- [ ] Physical-device acceptance: actual displayed cadence, visuals, thermals, input latency and sustained operation across both backends, Fixed 2x/3x/4x and Adaptive generation, Fixed/Adaptive Flow, FIFO/Mailbox and queue Off/0/1/2.

## Current state

Build #787 completed successfully: both native rebuilds, presentation regressions, Android production compilation, all selected LSFG/power application tests, APK assembly, update identity/native provenance verification and artifact upload passed. All 12 new FPS-cap Android behavior tests passed. No application test failure was skipped. Later commits contain only supplementary audit tools and reports; packaged source remains `eb5642a`.

[Download the verified APK bundle](https://github.com/Ragnarok93/GameNative/actions/runs/38116504753/artifacts/11694186430). Extract `GameNative-upgradeable-debug.apk`; the bundle also includes its SHA-256 checksum, build metadata and signing report.

| Field | Verified value |
| --- | --- |
| Build | #787 — success |
| Application ID | `app.gamenative` |
| Version code | `4564361` |
| Version name | `upgradeable-debug-eb5642a6b59f` |
| Packaged GameNative commit | `eb5642a6b59f0815893efff65e62b8f4c4cb669e` |
| Pinned/packaged Shared runtime | `5f14ebc3002401d4c762966cddf1793b3d3ec3f5` |
| Signing certificate SHA-256 | `0ff2ee400fcbaa8bf57a48f0297e49f139be8255fead61f96f409c3e11814d7a` |
| Artifact ZIP SHA-256 | `d8e8da68dc4190bafc0200fbb654a1e933826956eba53e0aca0cc67425de7e3c` |
| Artifact size | 601,532,785 bytes |
| Artifact availability | Through 2026-10-25 |

Workflow: https://github.com/Ragnarok93/GameNative/actions/runs/38116504753.
Native complete-suite validation: https://github.com/Ragnarok93/GameNative/actions/runs/38115572128.

Physical-device cadence, visuals, thermals, input latency and sustained operation remain unverified. The automated tests execute production behavior with deterministic clocks or platform boundary fakes; they do not establish physical display delivery or performance. Detailed feature behavior and device acceptance scope are recorded in `docs/lsfg-behavior-audit-20261011.md`.

## Follow-up audit requested 2026-10-11

- [x] Confirm current remote/local GameNative `0440c57` and pinned lsfg-vk runtime `5f14ebc`; preserve Build #787 as the validated APK.
- [x] Independently audit Native governors, Legacy runtime/resources, manager/power settings interactions and host presentation.
- [x] Reproduce high-confidence defects and separate them from device-dependent performance hypotheses.
- [x] Save and summarize prioritized findings and proposed solutions. This pass changes no production code.

Follow-up audit completed: nine correctness/control findings, one dormant helper defect and four unmeasured performance risks. Independently reproduced Native density/Flow/target recovery, Legacy FD reuse/zero polling, stale handoff acknowledgment, FPS-cap restoration and transient resource rebuild behavior. Existing governor, cadence and presentation regression suites all passed again, demonstrating the additional coverage gaps. Detailed priorities, evidence, solutions and validation scope: docs/lsfg-followup-audit-20261011.md. Portable reproductions: docs/audit-evidence/lsfg-20261011/. No production changes or new APK; Build #787 remains available, with physical-device acceptance still pending.

## Implementation follow-up — 2026-10-11

- [x] F1: transfer SYNC_FD ownership before all three completion imports, close duplicated poll FDs on failure. Shared runtime commit `3c0702456f9442aa8a31a6c070a56da4fd30d893`.
- [x] Add an assertion to the existing generated-output, generated-batch and zero-history FD-reuse audit harness (exit nonzero if an unrelated reused FD is closed).
- [ ] Verify new pinned runtime and strengthened FD test in CI; this checkpoint is not yet a release-qualified APK.
- [ ] Complete F2–F10 and evaluate B1–B4 without implying measured physical cadence.

- [x] F3/F8 first pass: user-edit ownership epoch, conditional limiter restore, and observation-generation CAS at command submission. Added assertive extracted-controller scenarios for zero/90/same-cap edits, legitimate restore and stale decision.
- [ ] Verify Kotlin source/audit compilation, Android tests, race injection and edge-case production behavior before marking F3/F8 release-ready.

- [x] F2: emit consumed Legacy transaction/revision in both Shared stats publishers and require exact source-only acknowledgment before arming Native. Pinned Shared runtime `50699e4f28b727b0cb3b98dbbddb0aad658f79a7`.
- [x] F7 first pass: failed Legacy restoration now commits `failed=true` and actual source-only measurement stride instead of desired Legacy-generation pacing.
- [x] Add assertion-based handoff identity fixture covering missing, wrong and matching acknowledgments.
- [ ] Run Android/Native suites, inject Legacy publish failure and paused-guest delayed consumption; verify F2/F7 integration on-device.
