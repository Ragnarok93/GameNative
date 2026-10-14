---
name: gamenative-device-testing
description: Operate GameNative's WSL ADB lab for pinned deployment, isolated guest sessions, autonomous route discovery/replay, presentation telemetry, recovery and controlled baseline comparisons.
---

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
Propose one-component experiments backed by pinned build-set/dispatch-build and
existing strict campaign comparisons; never automatically promote experimental
results or modify production saves/configuration for comparison. Unvalidated
live-session runs remain regression_eligible=false even if closure succeeds.
