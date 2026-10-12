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

## Next phases

The normal `LAUNCH_GAME` parser still has default-value merge ambiguity; this lab
does not reuse it to claim isolated gameplay configuration. Later work must add
runtime-owned profile application, process retirement, readiness/workload signals,
game launch, cache isolation and actual presentation telemetry before exposing
`run`. Reuse `LsfgDiagnosticExporter.prepareReportFile` for diagnostics once run
ownership is integrated; do not substitute exported log FPS for display cadence.
No SurfaceFlinger/Perfetto collection or stable promotion is implemented here.
