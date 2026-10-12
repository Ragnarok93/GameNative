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
