---
name: gamenative-device-testing
description: Operate GameNative's WSL ADB lab for pinned deployment, isolated guest sessions, autonomous route discovery/replay, presentation telemetry, recovery and controlled baseline comparisons.
---

Use the project's Agent Skills workflow in `AGENTS.md`: invoke the available
`using-agent-skills` entrypoint and only phase-relevant skills. Use measurement
before performance changes, error recovery plus regression tests for failures,
and shipping gates for APK deployment. Do not claim completion until runtime
acceptance is evidenced; preserve the existing device safety boundaries below.

Read `docs/device-testing.md` from the repository root before a device lab run.
For gameplay, read `docs/benchmark-campaigns.md` and validate a schema-v2 profile.
Use `tools/benchmark.py` rather than writing container files through ADB.

- Select an explicit serial and record model, SoC, Android version and fingerprint.
- Validate the profile and verified component source/package hashes. Example pins
  are intentionally rejected. Do not silently substitute built-in versions.
- Confirm GameNative and guest processes are closed before instrumentation;
  even list operations may otherwise terminate an active target process.
- Use the persistent upgradeable debug identity and a matching instrumentation
  APK from the same build. Check existing user authorization before APK installs
  or device-wide changes; ask only when it is missing.
- Use `prepare-container` for a dedicated `BENCHMARK_` configuration sandbox. On failure,
  retain JSONL evidence and run `recover` while idle before another mutation.
- Record workload/save/route/readiness and separate cold/warm trials when adding
  gameplay testing. Do not clear production shader caches or app data.
- Use `snapshot-workload` while idle to freeze Continue saves/metadata. Explore
  through screenshots and guest key/camera controls; export and validate a replay
  route before comparative trials. Live-controlled exploration is not regression
  evidence. Source saves stay unchanged; only sandbox copies receive input/autosaves.
- Distinguish persisted configuration, final launch-environment assertions, runtime
  binary hashes, and settings without comprehensive runtime acknowledgement.
- Keep source-game FPS, renderer submissions and display cadence separate. Inspect
  capability/coverage limitations, thermals and trace/native diagnostics. Comparison
  refuses mismatched controls and never automatically promotes a stable build.


## User-authorized existing-container exploration

When the user requires their existing FF7R container, use `live-launch` and
`live-session` instead of a fresh benchmark prefix. Never claim source save
immutability: normal gameplay may autosave. Do not supply container overrides.
Codex must visually inspect title, Continue selection, confirmation, loading and
actual controllable gameplay before passing `--confirm-gameplay`. Log screenshot
paths and phase transitions in the checkpoint. No blind timed loading as FPS.
Use the exact serial, `--allow-close` only with user-authorized test retirement,
and `--duration 300`. Inspect periodic screenshots and STOP if loading, a dialog,
blocked movement or another game appears. A process PID or changing screenshot
hash is not semantic gameplay/progress confirmation. Navigation actions must be
calibrated WASD/Shift combinations; do not change user input profiles to repair
controls. The CLI can issue inputs/log/retire autonomously; Codex supplies visual
supervision, route decisions and optimization analysis. Independent scene
recognition and obstacle avoidance are not implemented.

Inspect `result.json`, `analysis.json`, `commands.jsonl`, `diagnostic-manifest.json`,
`presentation.json`, screenshots and raw native/logcat records after closure.
Check original guest retirement, config/Engine.ini hashes and unavailable counters.
Separate cumulative queue timings from per-frame metrics and whole-device CPU
counters from frequency proxies. Correlate log timestamps; startup and historical
crash records must not contaminate gameplay conclusions. Missing FEX profiler or
Turnip fault access is missing evidence, not a zero or proof of no bottleneck.

For optimization, prioritize reproducible device loss and validated movement.
Prefer high-impact experimental profiles backed by pinned build-set/dispatch-build.
Clearly labeled combined profiles are allowed during aggressive screening; their
effects cannot be attributed to individual changes until a winning bundle is dissected.
Use existing strict campaign comparisons for single-component validation; never automatically promote experimental
results or modify production saves/configuration for comparison. Unvalidated
live-session runs remain regression_eligible=false even if closure succeeds.

## Direct verified DXVK selection

For the user's explicitly authorized existing FF7R container, prefer API-v2
`tools/benchmark_control.py` import/select/restore transactions documented in
`docs/device-testing.md`. No screenshot-driven settings fallback. Require matching
upgradeable APKs, idle full UID, immutable payload pins and pending-journal recovery.
Pass `--component-verification` to live-session before candidate inputs/capture;
only mapped matching DLLs establish a loaded build. Restore selection while idle
and separately verify baseline runtime DLLs after its normal launch when needed.

## Camera matching at the measurement boundary

A preflight gameplay screenshot is not proof of the measured camera. Compare
both runs' actual `initial-gameplay.png`, periodic observations and final capture
before accepting an A/B delta. The user-selected benchmark view is the busy
street in Sector5 Center District, not the wall/speakers/blue-cloth view. Wall
captures remain descriptive and are excluded from target-workload comparisons.
Loading-to-gameplay camera changes can make a
preflight wall view turn into a busy street before sampling. Let the camera
settle, verify fixed landmarks throughout the measurement, and use `STOP` if it
drifts. Reject the pairing, not the algorithm, when cameras differ. Reuse pinned
binaries and existing valid scene captures; do not rebuild components to repair
a mismatched workload. Normal gameplay may autosave; never overwrite saves.

## Upstream and fork resources

Before a new component optimization, consult `docs/optimization-source-resources.md`,
refresh relevant exact source revisions, compare with existing backports, and
apply its compatibility/provenance/measurement gates. StevenMXZ A6xx/A7xx and
DXVK-Sarek/mobile forks are research resources, not automatic replacement builds.

## Aggressive screening (user-authorized)

Prioritize large measured bottlenecks over repeating a2% exploratory signal.
Use one60-second busy-street screen per profile. Bundled experimental changes are
allowed when every change and binary is pinned and reversibly applied. As triage
heuristics, prioritize profiles showing >=10% mean completion-interval or >=15%
P99 improvement, with no crash/device-loss/visible graphics regression; these
thresholds are not statistical proof. Repeat promising winners >=3 times per
profile and confirm native performance independently before retention/promotion.
Bisect winning bundles afterward; never attribute a bundle's effect to one knob.
Preserve saves, Engine.ini,720p target, rollback and OS thermal protections.
Driver changes require real A650 compatibility and mapped-binary verification.
No new instrumentation unless it enables a concrete high-impact experiment.

User restriction: do not download/install StevenMXZ driver binaries. Use their
code/patches as references only, adapt justified changes in our Banners-Turnip
repository, and build/attest our own experimental driver.
