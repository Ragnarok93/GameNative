# Phase 1 GameNative device lab

Work in WSL with Python 3.10+, ADB and an explicit device serial. The CLI uses only
the Python standard library. It does not install APKs, launch games, stop running
apps, clear caches, or change device-wide settings. No new exported production
endpoint is added: `BenchmarkSessionManager` lives under `src/debug`, and the
bridge lives in the separately installed instrumentation APK.

## Setup and commands

Build the existing upgradeable `LegacyDebug` APK and matching
`:app:assembleLegacyDebugAndroidTest` APK from the same revision. Obtain explicit
authorization before installation, then install as updates with `adb install -r`.
The existing workflow verifies the stable debug certificate, package/version,
LSFG gitlink and renderer JNI provenance. See `codex-upgradeable-builds.md`.

```bash
python3 tools/benchmark.py --serial SERIAL device-info
python3 tools/benchmark.py --serial SERIAL backup --destination ~/backups/gamenative
python3 -m unittest discover -s tools/benchmark/tests -v
python3 tools/benchmark.py validate-profile benchmark-profiles/ff7r-sd865.json
python3 tools/benchmark.py --serial SERIAL list-components
python3 tools/benchmark.py --serial SERIAL prepare-container --source STEAM_1462040 --container BENCHMARK_FF7R
python3 tools/benchmark.py --serial SERIAL install --component dxvk \
  --package artifacts/candidate.wcp --version UNIQUE_VERSION --sha256 VERIFIED_HASH
python3 tools/benchmark.py --serial SERIAL verify-config --profile benchmark-profiles/ff7r-sd865.json
python3 tools/benchmark.py --serial SERIAL recover
python3 tools/benchmark.py compare --baseline benchmark-results/BASE_ID --candidate benchmark-results/CANDIDATE_ID
```

Steam and custom-installed games are supported. For the connected S20+,
FFVII Remake's existing container is `CUSTOM_GAME_227938910`; use that source
instead of the Steam example when preparing its sandbox. A custom-game profile
uses `source: "CUSTOM_GAME"` and the numeric custom ID, not a Steam store ID.

`backup` is read-only and works before the instrumentation APK is installed.
Close GameNative and all guest processes first. It captures the shared container
home tree, datastore, databases (including any WAL), shared preferences, input
profiles, Amazon/Epic app files, and LSFG app files in a
private host archive. Disposable `.wine/.wineserver` IPC directories are excluded.
The shell-v2 transfer separates tar errors from archive bytes and checks the
remote exit status. It verifies archive completeness, SHA-256, and hashes of
container configs, database/settings files and `.sav` files. Failed transfers
remain `.partial`; they are not reported as completed backups. Reserve the device
for the copy. Symlinks are retained, not followed: external game folders or saves
behind external links need their own backup. Inspect `manifest.json` for link
targets before treating the backup as complete coverage. This command does not
implement restoration or copy installed content packages and Proton bases.

Copy `ff7r-sd865.example.json` and replace every version/source hash with verified
values. The formal schema is `benchmark-profiles/schema.json`; the CLI enforces
its Phase 1 constraints without a third-party schema dependency.
The proposed 1152x648 / 30 FPS / three cold and warm trial settings are
workload metadata, not applied gameplay settings or measurements. Phase 1 only
validates supported configuration fields; it does not yet resolve or prove every
FEX/LSFG/Proton setting. `source_sha256` pins document source provenance; only
the explicitly supplied WCP package hash is verified against transferred bytes.
Pins are not claimed as installed-runtime attestations.

Use `prepare-container` to initialize a dedicated `BENCHMARK_FF7R` configuration
sandbox through `ContainerManager.createContainer`, using the source container's
settings and a separate initialized prefix. This does not copy game saves or
produce a playable benchmark scenario. Existing benchmark IDs are never reused
or replaced by preparation. Keep the production game container unchanged.
Close GameNative and all guests
yourself before calling the bridge. The host rejects active app/guest processes
because Android instrumentation may kill a running target. Reserve the device
for the operation; concurrent interactive launches and other ADB hosts are not
supported. A host lease and app-private file lock serialize lab operations.

Installation supports **XZ-compressed WCP** archives. Zstd support is deferred and
fails explicitly. The host checks SHA-256, content type/version/code, manifest,
payload existence, safe paths and archive member types without extracting to the
host. Packages containing links are rejected in this initial implementation.
Only trusted lab-built packages should be installed. The device rechecks the hash
and manifest, calls `ContentsManager`, refuses replacement of existing versions,
and confirms registration. Use unique candidate versions. Successful content
installs persist; they are not removed during config restoration.

`verify-config` executes one transaction without launching a guest: validate,
snapshot exact original bytes/hash, write a recovery journal, apply an explicit
patch using `Container.loadData/saveDataChecked`, verify persisted readback, and
restore the exact snapshot through Android `AtomicFile`. False and empty values
are explicit, never interpreted as absent. Unknown keys and type mismatches fail.
Restoration runs on exceptions too; process death leaves a journal. `recover`
verifies the backup and restores it before any subsequent mutation is permitted.
It is configuration recovery, not a backup of game saves or a running process.

## Physical-device acceptance (pending)

1. Export valuable saves/container data, record installed certificate/version,
   and obtain authorization for the matching app/test APK updates. Verify that
   accounts, games, containers and saves survive the update.
2. Run `device-info`; confirm S20+ model `SM-G986U1`, SoC `SM8250`, expected Android
   build and selected serial. Verify a release/non-debug package refuses `run-as`.
3. Start a game; confirm bridge commands refuse to run without interrupting it.
   Close the game/app manually and verify component inventory succeeds.
4. Try a corrupt hash and wrong component/version WCP; no install should occur.
   Install a unique known-good DXVK candidate and confirm returned registration.
   Repeat the same version and verify rejection rather than replacement.
5. Prepare the dedicated sandbox and verify the source config hash is unchanged.
   On that sandbox, check explicit false/empty/default-valued patches,
   saved readback, and identical original/restored config SHA-256. Confirm the
   production container and saves are unchanged.
6. Interrupt configuration instrumentation after the journal exists. New
   mutations must refuse until `recover` restores and verifies the snapshot.
   A corrupted snapshot must refuse restoration and retain recovery evidence.
7. Run two host operations concurrently; the second must refuse the device lease.
   Inspect JSONL run IDs, ordered host timestamps and failed-run evidence.
8. Build a fresh LSFG destination artifact and validate an install-over return,
   including database/schema compatibility before switching to older code.

## Game sessions and campaigns

Schema-v2 profiles, `run`, `explore`, autonomous guest controls, binary/runtime
checks, shader-directory isolation, thermal gates, SurfaceFlinger sampling and
optional Perfetto capture are described in [benchmark-campaigns.md](benchmark-campaigns.md).
Phase 1 commands retain their deployment-only meaning. Normal JSON launch intents
now preserve supplied field presence; privileged lab sessions use the dedicated
instrumentation transaction rather than that exported action.

### Existing-game navigation safety primitive

`tools/benchmark/navigation.py` supplies a host-side `navigate` function for
visually supervised existing-game exploration without instrumentation. It does
not launch or close games and never edits container configuration. The caller
must confirm gameplay and provide an observation callback that rejects loading,
crashes and blocked movement; image changes alone do not prove map progress.
Android held key combinations are bounded to 10 seconds and the final hold is
clipped to the remaining monotonic time budget (maximum 300 seconds). Slow
observation/ADB calls can still make elapsed wall time exceed the deadline;
this is not a hard real-time or device-side watchdog. Caller integration,
physical held-key calibration, autonomous obstacle recovery, automatic close,
and per-run collection remain required. Results are never regression eligible.

`execute_session` wraps caller-owned navigation: diagnostics collection is
attempted before shutdown, shutdown still runs after input/collection failures,
and errors retain their stage. Rejected visual observation yields `aborted`, not
a completed workload. Close callbacks must verify guest retirement before returning;
a returned callback alone cannot prove that Android processes exited. These
callbacks still require a physical adapter and calibrated navigation route.


## Codex-supervised existing FF7R diagnostic loop

This path respects the user-selected existing container and Engine.ini. It is
separate from sandbox benchmark campaigns and never changes component selectors,
settings, caches or saves. Normal gameplay may autosave. It is exploratory only.

```bash
export GAMENATIVE_ADB=/mnt/c/Users/Admin/AppData/Local/Android/platform-tools/adb.exe
python3 tools/benchmark.py --serial R5CN102RCQZ live-launch --container CUSTOM_GAME_227938910
# Codex inspects screenshots, selects Continue and Yes, then visually confirms gameplay.
python3 tools/benchmark.py --serial R5CN102RCQZ live-session \
  --container CUSTOM_GAME_227938910 --route /home/main/artifacts/ff7r-live-route.json \
  --duration 300 --confirm-gameplay --allow-close
python3 tools/benchmark.py analyze-live --directory /absolute/run/directory
```

Routes contain schema_version=1, description and 1..128 actions, each with keys
(two distinct Android WASD/Shift codes) and hold_ms (1..10000). Launch refuses an
active guest. Session requires explicit initial gameplay confirmation and close
permission. It checks active home alias and original guest PID, takes screenshots,
samples CPU counters/GPU busy/frequencies/thermals/process memory/presentation,
logs inputs and literal ADB outputs/statuses, captures bounded DXVK tails, logcat,
crash-buffer and guest stat/wchan, then attempts shutdown and verifies UID idle.
Config and Engine.ini are hashed before and after; save hashes are NOT claimed
unchanged. Captured files have SHA-256 manifests. STOP in the result directory
aborts before another movement. Device-loss signals abort navigation.

Missing FEX profiling, driver fault dumps, source FPS, inaccessible frequency
counters and partial presentation-ring coverage remain explicit limitations.
Initial gameplay confirmation is supplied by Codex, not an image classifier.
Periodic screenshots are evidence for Codex supervision, not automatic semantic
scene recognition or obstacle recovery. A small scripted route can hit a wall;
map traversal/replay must be visually validated before comparative trials.
No automatic component edits, setting changes, or stable promotion are allowed.
A deadline clips input holds; slow observer/collection calls can extend elapsed
wall time. Run status fails on input/collection/close/integrity errors; rejected
observation is aborted. Collectors may have unavailable counters without failing
all diagnostics. Full translated-code profiling/GPU fault attribution needs
additional instrumented components and hardware access, not invented metrics.

Guest CPU analysis now computes user+system tick deltas with observed CLK_TCK
and PID/start-time reuse checks. 100% means one core and values may exceed100%;
this is not FEX-only overhead. Host intervals include ADB sampling latency.
For the Android13 MainActivity live path, the collector selects the anchored
SurfaceView BLAST buffer layer, not its parent/background. Zero or multiple
buffer matches remain unavailable rather than silently selecting a layer.

### Detailed log archival
After successful owned-guest/UID retirement, live-session additionally pulls full current DXGI and D3D11 logs with before/after remote SHA-256 and local SHA-256 verification. It preserves existing pre-close tails, does not delete/rotate device logs, and reports unavailable archives instead of treating tails as complete logs. Full logs can contain startup; use measurement markers for performance. Native Windows ADB from WSL requires `export GAMENATIVE_WINDOWS_STAGING=/mnt/c/Users/Admin/GameNative-lab/adb-archives`. Unique staging directories remain as evidence; no existing archive is overwritten.
