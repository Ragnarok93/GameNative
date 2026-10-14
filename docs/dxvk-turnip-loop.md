# DXVK / Turnip improvement loop

## Evidence contract

Never compare a manual session with an isolated campaign or accept a startup
failure as a performance trial. Current FFVII sandbox startup is still under
investigation; manual ADB analog touch control works, but automated Continue and
route replay have not yet passed acceptance.

Every candidate requires source/build identity, component archive hashes and
effective runtime binary/configuration checks. Change exactly one component;
hold the game/save/route, renderer, FEX, Proton, resolution, frame cap, LSFG,
harness build and thermal policy constant. Keep baseline and candidate packages
immutable. Regression analysis must use the existing strict `compare` checks;
source-game FPS is not inferred from presentation or overlay counters.

## Execution

1. Build with a pinned `dispatch-build` plan; correlate the resulting exact CI
   run before `build-set` collection. Inspect upstream compiler/dependency inputs.
2. Verify DXVK WCP or Turnip ZIP identity and package SHA. Deploy additively through
   `install` or `install-driver` while idle, never replacing selected production
   components. Use native Windows ADB from WSL where USB/IP stalls.
3. Freeze a save manifest and validated route. Run schema-v2 campaigns with three
   cold/warm pairs per variant; stop on launch/configuration/thermal failure.
4. Inspect events.jsonl, actual environment/binary attestation, raw logcat,
   Perfetto and device diagnostics before making a causal claim.
5. Compare complete matched campaigns. A suspected regression is a screening
   signal, not a diagnosis. Make a targeted candidate change and repeat; do not
   automatically promote a build based on build success or apparent overlay FPS.

## Failure logging

For explicitly requested temperature-monitor-only diagnostics set
`telemetry.thermal_max_status` to `null`. Thermal sampling continues during
measurement, but runner admission/measurement does not reject heat. Android's
thermal protections are never changed. These trials are marked `monitor_only`
and ineligible for regression promotion; heat remains a confounder. Integer
0..3 retains the original enforced policy. Startup failure diagnostics also
collect final thermal state; source-game FPS is still not inferred.

Host `diagnostic-manifest.json` is attempted for both successful and failed trials.
It hashes captured files and records absent/failed retrievals explicitly. Status,
phase events, UID process inventory and thermal state remain available after
workload failure. New instrumentation also records pre-retirement runtime
environment/process state and preserves DXVK logs on exceptions.

`failure-dxvk/` contains bounded log tails (at most 2 MiB each, at most 16 files);
this is not complete shader/cache telemetry. Device captures are bounded to
16 MiB per DXVK file. Diagnostics failure must not replace the original run error
or skip process/configuration restoration. A diagnostic manifest never changes
failed evidence into a successful benchmark.

Known missing acceptance: sandbox guest startup, automatic Continue/route replay,
validated presentation-layer coverage and actual baseline/candidate campaigns.
Known missing measurements: comprehensive shader-cache hit counters and identified
LSFG generation-to-display latency. Build-run correlation is currently explicit
rather than automatic. These gaps must remain visible in reports.
