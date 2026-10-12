# Owned game sessions and campaigns

Use the WSL checkout. Install the app and instrumentation APK from the same
upgradeable debug build with `adb install -r`; retain application ID, certificate
and increasing version code across Master, LSFG and Codex branches. Installation
authorization already given in a session does not need to be requested again.
Never uninstall or clear app data to work around an update mismatch.

The CLI supports Phase 1 profiles and schema-v2 game profiles. All privileged
operations go through debug instrumentation. `BenchmarkGameActivity` is debug-only
and non-exported; it refuses to open without an instrumentation-owned runtime.
The production launch action has no content-install or benchmark management API.
Normal JSON launch overrides now retain field presence, so explicit false, empty
and default settings can be selected without overwriting omitted fields.

## Build inputs and deployment

`fetch-component` downloads a named successful GitHub Actions artifact at an
explicit source commit, verifies the expected WCP SHA-256 and manifest, and
records the downloaded source archive hash. It does not execute downloaded build
scripts. Inspect the upstream build manifest for compiler flags and dependency
revisions; a workflow head alone is not proof of every transitive input.

```bash
python3 tools/benchmark.py fetch-component \
  --repo Ragnarok93/FEX --run-id VERIFIED_RUN_ID --artifact ARTIFACT_NAME \
  --filename EXACT_FILE.wcp --component fex --version EXACT_VERSION_NAME \
  --sha256 VERIFIED_PACKAGE_SHA256 --source-commit FULL_COMMIT \
  --destination artifacts/fex

python3 tools/benchmark.py package-dxvk --directory artifacts/dxvk-ci-output \
  --version UNIQUE_LAB_VERSION --version-code 1 --output artifacts/candidate.wcp

python3 tools/benchmark.py --serial SERIAL install --component dxvk \
  --package artifacts/candidate.wcp --version UNIQUE_LAB_VERSION --sha256 HASH
python3 tools/benchmark.py --serial SERIAL install-driver \
  --package artifacts/pinned-turnip.zip --sha256 HASH
```

DXVK packaging validates PE architecture and creates a deterministic XZ WCP from
trusted `x32/` and `x64/` DLLs. ARM64EC/ARM64X x64-directory DLLs are accepted.
Use the existing upstream build workflows to compile DXVK/FEX/Turnip variants.
Driver deployment validates the ZIP, AArch64 ELF library and metadata, then uses
AdrenotoolsManager. Existing component versions and driver identities are never
replaced. XZ WCP is supported; Zstd WCP remains explicitly unsupported.

## Resolve a profile

See `benchmark-profiles/run.schema.json` and the FFVII, Crisis Core and LSFG
`.run.example.json` files. Examples deliberately contain zero hashes and refuse
execution. The Python validator also checks constraints that JSON Schema cannot
express, such as selected versions matching component pins and input timing.

Specify every supported config field and FPS/LSFG extra. Versions are complete
GameNative entry identifiers, including version code where used by the selector.
Specify prelaunch artifact hashes and runtime artifact hashes separately: the
latter are checked after startup, including extracted/linked prefix DLLs. Use
app-relative `files/...` paths or `native/LIBRARY.so` for APK native libraries.
The attestor permits app-private and APK native library roots only.

`source_kind` distinguishes a source archive, deployment package archive or
installed manifest. All hashes must refer to real bytes. Installed-manifest
provenance can be used for calibration/exploration, but is refused as regression
evidence. Do not fabricate source hashes to make examples runnable.

`runtime_expectations` asserts actual final bionic/FEX guest environment values
immediately before process creation, including preset and overlay changes.
Generated cache paths are added to these assertions. The result also records
renderer source-cap readback, LSFG readiness and runtime binary hashes. Individual
thread affinity, every internal LSFG policy field and every loaded dependency are
not comprehensively attested: inspect process/trace/native diagnostics when those
details matter. Current runtime observation targets bionic/FEX and Vulkan host
rendering; unsupported families fail rather than silently substitute a runtime.

## FFVII Continue and autonomous character control

```bash
python3 tools/benchmark.py --serial SERIAL snapshot-workload \
  --source CUSTOM_GAME_227938910 --output artifacts/ff7-continue.json
python3 tools/benchmark.py --serial SERIAL prepare-container \
  --source CUSTOM_GAME_227938910 --container BENCHMARK_FF7R
```

Preparation refuses an existing sandbox. Reuse the already prepared sandbox for
runs; preparation initializes the prefix, not the save scenario. Snapshot the
latest production saves while idle, then put the returned pinned files into the
profile's `workload.saves`. Keep common/device metadata and all slots together so
Continue selects the same frozen latest save in every trial. Staging verifies
hashes and preserves source timestamps; it overwrites only files inside the
benchmark prefix. Production saves are not copied back or modified by the lab.

Use an `exploration` workload first to discover the main-menu Continue sequence
and route. `explore` starts one cold shader-directory session; its run ID is
printed as a JSON progress record on stderr. Other CLI commands can observe and
control this same session without starting a second instrumentation process.

```bash
python3 tools/benchmark.py --serial SERIAL explore --profile artifacts/ff7.json \
  --variant baseline
python3 tools/benchmark.py --serial SERIAL session-status --run-id RUN_ID
python3 tools/benchmark.py --serial SERIAL control --run-id RUN_ID \
  --action '{"type":"screenshot"}' --output artifacts/menu.png
python3 tools/benchmark.py --serial SERIAL control --run-id RUN_ID \
  --action '{"type":"key","code":66,"duration_ms":0}'
python3 tools/benchmark.py --serial SERIAL control --run-id RUN_ID \
  --action '{"type":"key","code":51,"duration_ms":1000}'
python3 tools/benchmark.py --serial SERIAL control --run-id RUN_ID \
  --action '{"type":"look","dx":120,"dy":0,"duration_ms":500}'
```

Key 66 is Enter, 51 is W. Guest keys and relative pointer movement use XServer's
input APIs; system keys and arbitrary shell commands are not exposed. Tap
coordinates are guest-resolution pixels. Screenshots capture the foreground lab
window; input refuses lost focus. Commands require the matching active journal,
are atomically queued, acknowledged and recorded with device timing. Live control
is accepted only in exploration sessions, which cannot establish regressions.

After exploration, export the actual input timing, inspect the screenshots to
confirm Continue loaded the intended checkpoint, and freeze the route:

```bash
python3 tools/benchmark.py export-route --run-directory benchmark-results/RUN_ID \
  --output artifacts/route.json
```

Copy the route's `setup_actions` and `actions` into a `scripted` profile and replay
it before collecting comparative trials. Readiness is an explicitly matched
application window with content updates and verified guest environment. This is
not semantic proof of an in-game location: screenshots/route acceptance establish
the checkpoint and scenario. A `startup` profile has no saves or input actions
and reports startup/title-screen behavior only.

## Run and compare

```bash
python3 tools/benchmark.py validate-profile artifacts/ff7.json
python3 tools/benchmark.py --serial SERIAL run --profile artifacts/ff7.json \
  --variant dxvk-baseline --repeat 3
python3 tools/benchmark.py --serial SERIAL run --profile artifacts/ff7.json \
  --variant dxvk-sd865 --repeat 3
python3 tools/benchmark.py compare --baseline benchmark-results/BASE_CAMPAIGN \
  --candidate benchmark-results/CANDIDATE_CAMPAIGN --component dxvk
```

Omit `--variant` to run all variants; campaign order rotates by trial. Each cold
trial creates a fresh sandbox cache directory; its warm partner reuses it. The
scope is DXVK state cache and Mesa shader-cache directory isolation, not Android
page caches, every FEX cache, or every driver's implicit cache. No production
shader caches are cleared. Cache directories and private run artifacts remain for
inspection; budget storage before large campaigns.

The host lease spans the campaign, and the app-private lock/journal spans launch,
readiness, warmup, measurement, collection, process retirement and exact config
restoration, including restoration of the previous active-container home alias.
Runtime hooks prevent cancelled setup from continuing a new launch.
The runner stops on a failed trial or thermal-gate breach and retains failure
evidence. A new mutation is blocked until pending recovery succeeds. Interrupted
sessions receive a private cancellation marker; unresolved setup/guest retirement
retains the journal. Reserve the phone; independent ADB hosts and interactive
launches during a lab session are not supported.

## Measurements and their limits

Results contain host/device JSONL events, app-UID logcat, runtime readback and
binary attestations, renderer submission timestamps, LSFG diagnostics, process
RSS/time, Android PSS, thermal/battery data and accessible CPU/GPU frequency/busy
interfaces. Missing or protected interfaces are reported as unavailable.

SurfaceFlinger `--latency` is sampled for exactly one explicitly selected layer.
Zero/pending fences and duplicates are excluded; timestamps outside measurement
are discarded. Disjoint finite-ring samples invalidate cadence metrics rather
than inventing dropped frames. ADB sampling has overhead and does not guarantee
complete ring coverage. Average FPS, 1% low and P95/P99 apply to the named cadence
channel. Source-game FPS is not inferred from display or renderer callbacks.

Optional Perfetto capture uses a bounded uniquely named session and stops only
that session. Traces include FrameTimeline, scheduling and process stats where
supported. Enabling a source is not proof that the device produced its events.
See [Perfetto FrameTimeline documentation](https://perfetto.dev/docs/data-sources/frametimeline).
Matched generation-to-display latency and comprehensive shader/cache hit counters
remain unavailable unless the native/game diagnostics provide the required
identified events; inspect the retained diagnostics/trace instead of estimating.

Comparison separates cold/warm medians and ranges and requires matching device,
workload, telemetry, harness build and fixed component/config pins. It accepts
one varied component, refuses failed/partial collections and exploration, and
never automatically promotes a build as stable. Fewer than three trials are
marked insufficient. Display-cadence drops over 5% and P99-gap increases over 10%
are flagged as suspected regressions, with actual fixed guest environment values
checked across trials. These thresholds are screening signals, not statistical
proof of a cause. Inspect thermal/frequency and coverage evidence before
drawing a performance conclusion.

## Recovery acceptance

```bash
python3 tools/benchmark.py --serial SERIAL test-recovery --container BENCHMARK_FF7R
python3 tools/benchmark.py --serial SERIAL recover
```

The debug-only probe toggles a sandbox setting, kills its own app process after
the journaled write, verifies a real persisted mutation, verifies another
operation is blocked, and restores the exact original hash. No guest is launched.
Corrupted snapshots refuse recovery and retain evidence; corrupt-snapshot/manual
repair and guest-process-death scenarios still need explicit physical acceptance.
