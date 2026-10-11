# LSFG hardening progress checklist

Branch: `feature/lsfg-host-presentation-feedback-20261007`.
Updated: 2026-10-11. Each advancement is saved with this report through the GitHub connector.

- [x] Restore the saved source checkpoint after workspace maintenance; base `8919398`.
- [x] Preserve Legacy/Native cadence, settings ownership, Adaptive Flow, pressure validity, frame queue, and power-domain fixes already committed.
- [x] Native regression workflow #11 passed; Build #772 rebuilt both native libraries and passed native regression checks.
- [x] Correct Build #772 Kotlin integration failure: restore the handoff-published runtime multiplier in the source FPS update path; inactive generation uses 1x. Android compilation verification remains pending.
- [ ] Reproduce and fix repeated failed Native density recovery probes under unchanged operating conditions.
- [ ] Run affected regression tests and the complete workflow application checks without skipping failures.
- [ ] Build the debug APK and verify package identity, increasing version code, persistent signing certificate, JNI exports, and native source provenance.
- [ ] Provide the fresh verified APK for testing and feedback.
- [ ] Physical-device acceptance: cadence, visuals, thermals, latency, and sustained operation across both backends, modes, multipliers, Flow settings, queue depths, and presentation modes. No device result is asserted by the automated tests.

## Current evidence and next action

Stationary density RED reproduction: source 30 FPS, GPU 60%, healthy source delivery, generated delivery or admission fails whenever density exceeds zero. Both cases produced 79 recovery attempts and 78 failures in 300 simulated seconds. Minor source/GPU jitter also reproduced the loop. The regression requires one failed probe followed by a hold; sustained source/GPU change must still permit recovery. These tests intentionally fail until the rejected operating point is retained in the production governor.

Build #772 failed `compileLegacyDebugKotlin` at five references to an undeclared local `runtimeMultiplier` in `XServerScreen.applyFpsLimiterToEngines`. It did not produce an APK. The local value now derives from the existing handoff-published `lsfgRuntimeMultiplier` and active flag, consistently feeding display hints and budget diagnostics. The next workflow must verify Android compilation and application tests. Independently reproduce the stationary density recovery loop before changing governor behavior. Existing behavior audit and regression evidence are recorded in `docs/lsfg-behavior-audit-20261011.md`.
