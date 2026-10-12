---
name: gamenative-device-testing
description: Operate GameNative's WSL ADB lab CLI for pinned component deployment, dedicated-container configuration checks, recovery, and structured Phase 1 evidence.
---

Read `docs/device-testing.md` from the repository root before a device lab run.
Use `tools/benchmark.py` rather than writing container files through ADB.

- Select an explicit serial and record model, SoC, Android version and fingerprint.
- Validate the profile and verified component source/package hashes. Example pins
  are intentionally rejected. Do not silently substitute built-in versions.
- Confirm GameNative and guest processes are closed before instrumentation;
  even list operations may otherwise terminate an active target process.
- Use the persistent upgradeable debug identity and a matching instrumentation
  APK from the same build. Check existing user authorization before APK installs
  or device-wide changes; ask only when it is missing.
- Configuration checks use a dedicated `BENCHMARK_` container clone. On failure,
  retain JSONL evidence and run `recover` while idle before another mutation.
- Record workload/save/route/readiness and separate cold/warm trials when adding
  gameplay testing. Do not clear production shader caches or app data.
- Phase 1 readback is persisted configuration, not effective guest configuration.
  `compare` compares deployment evidence only. Do not infer performance or promote
  a candidate without reproducible physical-device baseline measurements.
