# FFVII Remake Intergrade — SD865 GameNative / DXVK / FEX architecture

**Status:** design-only research branch (2026-10-11); no optimization patch, WCP, APK, benchmark result, or on-device performance improvement is claimed.

**Target:** legally installed FINAL FANTASY VII REMAKE INTERGRADE, Galaxy S20+ Snapdragon 865/Adreno 650, Mr. Purple T30 Turnip, GameNative Bionic Proton 10 ARM64EC + FEX 2610, native game frames at 1280×720 container output, Unreal/FFVIIHook dynamic scale 68–100% (about 490–720p), 30 FPS objective, VSync off. Working baseline reported by user: about 15–30 FPS at 1154×648 in prior configuration. This performance report is not a controlled benchmark.

**Related exclusive branches:**
- GameNative: [feature/ff7r-sd865-architecture-20261011](https://github.com/Ragnarok93/GameNative/tree/feature/ff7r-sd865-architecture-20261011)
- DXVK 2.7.1 compatibility: [feature/ff7r-sd865-architecture-20261011](https://github.com/Ragnarok93/dxvk-SD865/tree/feature/ff7r-sd865-architecture-20261011)
- FEX 2610 SD865 ARMv8.2 candidate: [feature/ff7r-sd865-architecture-20261011](https://github.com/Ragnarok93/FEX/tree/feature/ff7r-sd865-architecture-20261011)

## 1. Governing principle: establish which Direct3D path actually runs

Two possible paths have different owners and cannot be benchmarked as if equivalent:

```text
FF7Remake_.exe
  ├── D3D11 -> Wine/Proton D3D11 DLL -> DXVK 2.7.1 -> Vulkan -> Turnip T30
  └── D3D12 -> Wine/Proton D3D12 DLL -> VKD3D-Proton -> Vulkan -> Turnip T30
                 Both branches use FEX for the x86-64 guest and GameNative as host.
```

**DXVK is not the primary renderer for a confirmed D3D12/VKD3D run.** An FPS improvement on D3D12 cannot be credited to a DXVK source change without showing a DXVK component is on the measured hot path. The user reports that GameNative sometimes runs D3D12 despite `-dx11` / `-d3d11`, whereas D3D11 previously worked through GameHub. Those flags are requests, not proof. Resolve the renderer first.

For each boot: record actual executable image and hash, full command line, loaded `d3d11.dll`, `d3d12.dll`, `dxgi.dll` and VKD3D modules, DXVK HUD's `api,version`/log when D3D11 is active, Wine DLL loading trace only during diagnostic runs, Vulkan physical-device/driver identity, effective `WINEDLLOVERRIDES` and the game-specific DXVK or VKD3D profile. Classify runs as **DX11-confirmed**, **DX12-confirmed**, or **unverified**. Never compare API paths in the same A/B experiment.

## 2. Verified repository evidence and safe starting commits

| Component | Starting branch and commit | Evidence / constraint |
| --- | --- | --- |
| GameNative | `feature/dxvk-fl10-diagnostics-20261010` @ `4a2e1cd5fa44` | Adds opt-in preservation of `DXVK_LOG_LEVEL`; `master` currently sets it to `none`. See `com/winlator/core/DXVKHelper.java`. |
| DXVK | `compat/ff7remake-dxvk271-sd865-20261010` @ `5c9d3a04b281` | Packs exact upstream DXVK 2.7.1 source ancestry `c3dd74be6baec53786d4e064a572185b70347a17`, x32/x64 PE DLLs in an isolated `.wcp`; not a GPU/performance patch. |
| FEX | `feature/fex2610-sd865-armv82-benchmark-20261010` @ `0ae689940a6f` | Build scripts pin exact FEX 2610 source `14c92681f4d62cf84d901460e0358de09c8847a7`. Baseline A77 scheduling vs experimental ARMv8.2-A+CRC, preserving JIT code. |
| Game/profile | FFVIIHook Engine.ini supplied separately | Current reported 720p experiment uses DRS `68–100%`, `33.3` ms target. Verify actual CVar values and renderer support on-device. |

Related local evidence: `Ragnarok93/dxvk-SD865/docs/FF7-REMAKE-DXVK271-COMPAT.md` and `docs/SD865-FF7-D3D11-FL10-DIAGNOSTICS.md` on its separate diagnostic branch; `Ragnarok93/FEX/docs/benchmarks/ff7r-sd865-fex2610-v82.md`. Reported DXVK 3.1.1 "DX11 feature level 10" failure remains **unresolved**. Do not bypass required Vulkan features, spoof D3D feature levels, or merge 3.x changes into the 2.7.1 compatibility branch until missing features are diagnosed.

## 3. Isolated run contract

- Pin GameNative APK commit/build, Bionic Proton/Wine version, selected FEX ARM64EC/WoW64 `.wcp`+SHA256, selected DXVK 2.7.1 `.wcp`+SHA256 **or** VKD3D version, Turnip T30 version/driver SHA, Mesa shader-cache configuration, game executable SHA256 and mods/paks/FFVIIHook.
- Retain exact Unreal Engine.ini + any dynamic-resolution behavior. Compare equal *rendered internal pixels*, not just equal 720p container output; log active screen percentage if the game exports it. Dynamic scaling is a confounder.
- Set no Vulkan or D3D11 capabilities by spoofing. Keep LSFG, frame generation, interpolators, adaptive governor modifications, and VSync **off** in true render-performance tests. Compare LSFG separately only after renderer tests pass.
- Pin controller/input, scene/save, resolution, thermal/frequency state, game launch source and identical graphics settings. Do one change group at a time. Preserve the original game files and save backup.
- Use cold-cache and warmed-cache passes as different experiments; never mistake slower first-use shader compilation for steady-state GPU cost. 2G Mesa disk cache is a *capacity limit*, not faster shader execution. Do not clear caches except in explicitly labeled cold-cache tests.
- Record exact logs/config precedence (container Environment tab may be superseded by helper). On diagnostic passes `GN_DXVK_DIAGNOSTICS=1`, `DXVK_LOG_LEVEL=info`, optionally `DXVK_SD865_DIAGNOSE=1` **only when testing the separate 3.x diagnostics build**. Disabled logging is the performance baseline.

### Required run manifest (example, fill unknowns with null)

```json
{
  "game_sha256": null, "api_confirmed": null, "gamenative_commit": null,
  "fex_wcp_sha256": null, "dxvk_or_vkd3d_wcp_sha256": null,
  "wine_proton_version": null, "turnip_driver": "T30 (verify exact build)",
  "mesa_cache_limit": "2G", "screen_output": "1280x720",
  "dynamic_scale_min_pct": 68, "dynamic_scale_max_pct": 100,
  "engine_ini_sha256": null, "mods_sha256": [],
  "lsfg": "off", "vsync": "off",
  "capture_method": null, "ambient_or_thermal_state": null,
  "scene_id": null, "trial": null
}
```

## 4. GameNative responsibilities and proposed architecture

The source already has per-container environment-variable editing (`app/gamenative/ui/component/dialog/EnvironmentTab.kt`), setup and launch arguments through `XServerScreen.kt`, and DXVK/VKD3D config creation through `com/winlator/core/DXVKHelper.java`. In `XServerScreen.kt`, `getWineStartCommand(...)` is assembled before `container.execArgs` is appended. Inspect the resulting *effective* process command and DLL set; fixing the user-visible input argument field alone may not force a game's requested API.

**Design an optional, game-specific FF7R profile** (off by default; match a verified executable/hash/store identity, not vague substring): renderer requested/observed, DXVK/VKD3D selection, FEX WCP variant, shader-cache path/limits, diagnostics toggle, user override precedence, and capture manifest. Guard all changes behind opt-in, with ability to revert to ordinary GameNative defaults in one action.

**Implementation gates:**
1. Read-only launch trace: requested launch flags, effective child executable/argv, DLL load origin, effective env, DXVK vs VKD3D selection, Vulkan ICD. Redact user-identifying paths from exported reports.
2. Audit `DXVKHelper.java` env insertion precedence. In the diagnostic base, `GN_DXVK_DIAGNOSTICS=1` preserves an explicit `DXVK_LOG_LEVEL`; `master` had hardcoded `none`. Distinguish diagnostic and production modes. Keep logs disabled in benchmarks.
3. Add an *explicitly opted-in* FF7R launch profile, not global overrides. Test manual game launch and store launch separately, preserving controller, mods and save paths.
4. Use game-specific preset WCPs selected from Contents Manager. Do not silently replace a shared DXVK DLL or all profiles. Keep driver fallback and rollback to known working versions.
5. Add per-run metadata alongside frame times; observe real present completion, not queued/LSFG synthetic frames. Ensure no extra synchronization or per-frame logging in performance builds.

Potential hot files: `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`, `app/src/main/java/com/winlator/core/DXVKHelper.java`, environment panel, FEXCore selection and `BionicProgramLauncherComponent`. Actual optimized functions require profiling.

## 5. DXVK optimization track — **D3D11-confirmed runs only**

Stay on DXVK **2.7.1** until the startup and FL10 behavior is understood. Maintain immutable 2.7.1 control WCP, and a *second* uniquely named FF7R experimental WCP built from the exact same base/architecture. Explicitly record every change from upstream and ensure correct PE x86/x64 binaries and `profile.json` layout.

Research in this priority order:
1. **Pipeline compilation/hitches:** separate first-use shader compiler stalls from per-frame steady state. Measure DXVK HUD `compiler,pipelines,drawcalls,submissions,cs,frametimes,memory,api,version` selectively; excessive HUD logging itself has cost. Profile driver-side cache hits/misses where available. Limit `dxvk.numCompilerThreads` to 2 **only as an A/B test** against default. Never assume `DXVK_ASYNC=1` / GPL async flags work in vanilla 2.7.1 or are safe.
2. **Render submit/context contention:** measure time in D3D11 immediate/deferred context, command-list submission, flush and synchronization points; only then prototype fewer redundant barriers or submissions while preserving ordering, resource hazards and memory visibility.
3. **Resource allocation/texture streaming:** track buffer/image create/destroy rate, staging uploads, peak guest/system GPU memory and transient allocations; only gate allocator/pool adjustments on measured churn. Preserve full-quality texture mips, residency correctness and cutscene loads. Texture popping or dark/broken materials are test failures.
4. **Descriptor/state churn:** profile repeated descriptor bindings or state-object creation around combat, menus and traversal. Reuse cached state **only** with proven lifetime and invalidation rules, no unbounded cache growth.
5. **Driver-specific path:** use Vulkan feature/capability probes actually reported by T30. Do not mandate unsupported Vulkan 1.4, descriptor buffer, GPL, synchronization2 or other optional features. Test dedicated fast paths on the exact driver first, with a fallback.
6. **Pacing:** present latency, image count, queue depth and sync calls are diagnosable independent of mean FPS. Never remove necessary host/display fences or treat queued presents as visible output.

**Failure gate:** DXVK 3.x on this device currently raises the game's generic D3D11 FL10 error; retrieve first real missing Vulkan requirement using the existing diagnostic branch before proposing 3.x migration. Do not equate FL10 dialog with a proven software feature-level regression.

DXVK cannot optimize the rendering stages of a D3D12-confirmed VKD3D run. Those results should instead become a **separate VKD3D investigation**, while GameNative/FEX optimization remains applicable.

## 6. FEX optimization track — both D3D11 and D3D12

Start with the **same pinned FEX 2610 source** and two supplied A/B packages:
- Control: `o3-snapdragon865`: Cortex-A77 scheduling, baseline ARMv8-A+CRC compiler target.
- Candidate: `o3-snapdragon865-v82`: `-mtune=cortex-a77`, `-march=armv8.2-a+crc` for ARM64EC and WoW64 objects. Native C++ build ISA differs; **FEX JIT output is not modified** by this existing experiment.

The GameNative FEX package contains `libarm64ecfex.dll` and `libwow64fex.dll`. The current build script pins FEX source at `14c92681f4d62cf84d901460e0358de09c8847a7`; do not casually retarget the script to a branch HEAD and misstate provenance. Preserve validation of final effective C/C++ compile commands, not just CMake flags.

**Known A/B environment used by earlier research:** `FEX_DISKCACHE=1`, `FEX_DISKCACHEMEMORYSIZE=33554432` (32 MiB). Retain same TSO, multiblock, x87, and L1/L2 presets across trials; do not copy unverified flags from other FEX releases.

Research gates after the v82 comparison:
1. Measure *actual* per-frame CPU-bound time with Game/Render thread and FEX JIT profiles, code-cache hit/miss, guest block translation/invalidations, context switches, syscalls, and native/guest lock contention. FEX runtime instrumentation must be opt-in.
2. Compare warmed disk cache on/off separately for cold start, level load, and repeated traversal. Cache-size enlargement is useful only when measurable eviction/recompilation exists.
3. Profile x86-64 branch/atomic/TSO barriers in hot translation blocks and Windows API thunk paths. No relaxed guest x86 memory semantics, incorrect atomics or exception behavior for a marginal FPS gain.
4. Profile actual thread scheduling on Kryo 585 A77-like large cores vs small cores. Do not force hard affinity, busy-spins, governor changes or more compiler threads globally. Protect UI/audio threads and thermals.
5. Only if profiles justify it, independently test hot-block JIT specialization, register allocation, code layout, block linking, or host feature detection. Every transformation requires guest instruction correctness and deterministic regression coverage.
6. Preserve ABI, unwinding, exception and Wine ARM64EC/WoW64 integration; no unverified SVE, fabricated host CPU features, invalid processor registry overrides.

**Repository policy:** `Ragnarok93/FEX/AGENTS.md` states *“AI must not be used to generate code for contributions to this project.”* This branch contains architecture/documentation only. Any FEX **code** changes must be independently authored in accordance with that rule; no AI-generated FEX patches.

## 7. Test matrix and promotion requirements

| ID | Renderer | FEX | DXVK/VKD3D | Change | Output |
| --- | --- | --- | --- | --- | --- |
| C0 | Observed baseline API | existing SD865 control | pinned working renderer | nothing | genuine baseline |
| D0 | D3D11 **confirmed** | control | DXVK 2.7.1 control | none | D3D11 stable-reference frame trace |
| D1 | D3D11 **confirmed** | control | DXVK 2.7.1 experimental | exactly one tested optimization | delta vs D0 |
| F1 | same as C0 | SD865 v82 candidate | unchanged | native ISA only | delta vs C0 |
| G1 | same as C0 | same WCP | unchanged | GameNative profile/launch only | correctness + overhead |
| X0 | D3D11 diagnostic | unchanged | DXVK 3.1.1 diagnostic (separate branch) | logging only | missing Vulkan capability, NOT FPS |
| V0 | D3D12 **confirmed** | control | pinned VKD3D | no DXVK experiment | VKD3D baseline |

Use fixed saves and 2–5 minute representative runs: menu/startup, Midgar traversal, heavy particles/combat, fast camera, cutscene/loading. Three or more warmed repeated passes per candidate; alternate order to cancel thermal drift. Capture warm and cold *separately*. Record raw positive `frame_time_ms` or monotonically increasing `present_time_ns` for actual game frames; **not LSFG generated/queued frames**. FEX already has `Scripts/GameNative/benchmark_fex2610_frametimes.py` to compare traces. Report: mean genuine FPS, 1% low, p50/p95/p99 duration, >50ms hitch count, peak CPU/RAM/VRAM, stutter, and thermal state. No synthetic or unmeasured % claims.

**Advancement gates:**
- G0: API/DLL identification proven in logs, same run manifest, no fallback renderer.
- G1: functional parity (menus, input, saves, combat, cutscenes, video/audio, restart) and no visual corruption.
- G2: real measurable p95/p99 or 1%-low benefit over noise with fixed graphics settings; 30 FPS is an objective, not an assertion.
- G3: bounded memory use, no shader cache churn, no unstable frequency/thermal regression, correct Vulkan synchronization.
- G4: reproducible WCP checksum/build/test provenance, opt-in preset and trivial rollback.
- G5: device tests pass; desktop/GitHub CI **never proves** Adreno 650 gameplay stability.

**Stop / revert:** wrong API path, startup FL10 regression, black or dark textures, missing materials, crash, stutter regression, stale frame displays, unsound synchronization, texture eviction, or any inability to capture real frame times. Keep the working control profile installable.

## 8. Work breakdown / checklist

- [x] Establish exclusive architecture branches in all three repositories.
- [x] Document existing proven **source/repo** baselines; performance claims remain unverified.
- [ ] P0 GameNative: capture effective executable/DLL/API and one full run manifest.
- [ ] P0 device: reproduce DX11-confirmed 2.7.1 and DX12-confirmed VKD3D separately.
- [ ] P0: capture genuine real-frame timestamps, GPU/CPU split, thermal/cache data with LSFG off.
- [ ] P1 FEX: compare pinned 2610 A77 vs ARMv8.2 variant using existing analyzer; determine CPU headroom.
- [ ] P1 DXVK: profile D3D11 compiler and submit costs; make exactly one targeted experimental change at a time.
- [ ] P2 GameNative: optional FF7R-specific per-game preset with diagnostic and rollback controls.
- [ ] P2 graphics: fix real 3.x requirements **only if present/implementable**; never spoof a feature.
- [ ] P3 validate across gameplay scenarios and compile a device-evidence report.
- [ ] P4 promote separately versioned `.wcp`s only after G0–G5; avoid silent migration.

This design intentionally postpones all code changes until baseline attribution is complete. Changes to FEX source must additionally honor its repository's AI code contribution restriction.
