# LSFG Rolling Diagnostics and Unified Export Design

Date: 2026-09-03
Branch: `Adaptive-Frame-Generation`
Scope: `Ragnarok93/GameNative` + pinned `Ragnarok93/lsfg-vk-android`

## Purpose

Replace the current Termux-assisted LSFG debugging workflow with one app-integrated rolling diagnostic session and one export action in GameNative's existing Debug settings group.

The system must preserve the evidence needed to diagnose LSFG/Adaptive Frame Generation stability, performance, disable-path correctness, pacing, WSI transitions, capability selection, and generated-frame presentation without putting filesystem or formatting work on the Vulkan present/render hot path.

The exported artifact is a single human-readable text file suitable for direct attachment to a GitHub issue or ChatGPT debugging session.

## User-facing behavior

A new settings entry named **Export LSFG Diagnostics** appears immediately below the existing **Save Logcat** entry.

When a supported game session starts, GameNative starts a bounded LSFG diagnostic session automatically. The recorder runs without requiring Termux and persists until the game session stops. It is independent of whether LSFG is currently generating frames so that enable, Adaptive-zero, disable, WSI recreation, and post-disable behavior remain observable in one timeline.

Pressing **Export LSFG Diagnostics** opens Android's normal document creation flow and proposes a filename of the form:

`gamenative-lsfg-YYYY-MM-DD_HH-mm-ss.txt`

The export combines the rolling session and point-in-time diagnostic snapshots into one text file. Failure to read one optional source does not abort the whole export; the corresponding section records the failure and all remaining sections are still written.

The existing **Save Logcat** action remains available for generic application debugging.

## Architecture

### 1. GameNative diagnostic session owner

Introduce a GameNative-side `LsfgDiagnosticSession` service/object responsible only for lifecycle, bounded storage, event ingestion, and export assembly.

Responsibilities:

- Start and stop with the active game session.
- Own a bounded append-only rolling diagnostic file outside the render/present path.
- Accept low-frequency structured samples and discrete diagnostic events from existing GameNative subsystems.
- Read native LSFG diagnostic output asynchronously.
- Assemble one exported text report when requested.
- Retain only the most recent diagnostic sessions.

The session owner must not poll or write from the UI thread or Vulkan/present hooks.

### 2. Continuous structured telemetry

Reuse existing GameNative performance telemetry rather than introducing a second per-frame sampler.

The existing `PerformanceMetricsCollector` already samples every 500 ms and records frame-time, CPU, GPU, and thermal data. The LSFG diagnostic session consumes equivalent low-frequency samples and adds the currently available LSFG source/output/generated FPS fields.

Periodic records should include, when available:

- timestamp
- source/game FPS
- generated FPS
- final output FPS
- frame-time P50/P95/max
- slow-frame count / sampled-frame count
- CPU usage
- GPU usage
- CPU temperature
- GPU temperature
- display refresh rate
- LSFG enabled state
- Adaptive enabled state
- Adaptive output target
- selected multiplier

The recorder does not emit one text record for every presented frame.

### 3. Native lsfg-vk diagnostic stream

`lsfg-vk-android` publishes a bounded, low-overhead diagnostic event stream into the same container-visible LSFG configuration area. Native presentation code only enqueues compact events or updates fixed-size counters/state; formatting and file publication remain off the hot path.

Native diagnostic events/state must cover:

- Vulkan physical device identity and driver information
- capability matrix decisions relevant to Xclipse and A6xx
- Android Hardware Buffer support/transport selection
- sync/barrier compatibility path selection
- present-mode and image-count capability decisions
- swapchain creation and destruction
- original vs effective WSI provenance
- runtime config application
- reason for controlled `VK_ERROR_OUT_OF_DATE_KHR`
- Off -> On and On -> Off transitions
- `LsContext` create/destroy/reset
- history warmup/reset
- Adaptive target and requested generation count
- actual generated count
- residual carry
- probe ceiling / proven ceiling
- Adaptive decision reason
- AHB handoff cost
- framegen dispatch cost
- framegen completion/wait cost
- generated-present work cost
- source/game cadence
- present-entry cadence for diagnostics only
- timeout/retry events
- fence/handoff failures
- generated-present failures
- fail-open/pass-through decisions
- successful generated-frame presentation counters

High-frequency values are aggregated into periodic summaries. Transition, warning, failure, timeout, and state-change events are recorded immediately through the asynchronous diagnostic path.

### 4. Existing wrapper diagnostics

The exporter includes the existing GameNative wrapper diagnostics file (`wrapper_diag_<appId>.txt`) when present. Absence is reported as `not available`, not treated as an export failure.

### 5. Point-in-time export snapshots

When the user presses Export, the exporter asynchronously captures current state that is useful for reproducibility but does not need continuous logging:

- GameNative app version/build information
- device manufacturer/model/device/build fingerprint
- Android version/API level
- ABI
- active display refresh rate
- LSFG runtime version marker
- installed native library fingerprint/hash where available
- active container/runtime variant
- selected graphics driver and relevant Vulkan/AdrenoTools environment
- LSFG `conf.toml`
- LSFG `stats.txt`
- LSFG `vsync.txt`
- current LSFG settings persisted by GameNative
- current native diagnostic state/summary
- recent GameNative PID logcat
- wrapper diagnostics
- current/latest performance metrics session data relevant to the active game

Sensitive unrelated application/account state must not be added to the diagnostic export.

## Export format

The exported file is plain UTF-8 text with stable section headers so humans and automated analysis can locate evidence reliably.

Required top-level sections:

1. `SESSION`
2. `DEVICE`
3. `APP / RUNTIME`
4. `GPU / VULKAN CAPABILITIES`
5. `LSFG CONFIGURATION`
6. `LSFG CURRENT STATE`
7. `PERFORMANCE TIMELINE`
8. `LSFG NATIVE EVENTS`
9. `WRAPPER DIAGNOSTICS`
10. `APP LOGCAT`
11. `EXPORT WARNINGS`

Each section states `not available` or a concise capture error when its source cannot be read. The export remains valid and is still saved when optional sections fail.

The report should include enough ordering/timestamps to correlate a visible hitch or artifact with configuration changes, native stage-cost pressure, WSI recreation, generated-frame activity, and system utilization.

## Storage and retention

Recommended defaults:

- maximum rolling diagnostic size per session: 20 MiB
- retained sessions: 5

These defaults match the existing bounded-session logging philosophy in GameNative.

When the cap is reached, the recorder stops appending high-frequency periodic records while preserving the file already captured. A single cap-reached event is recorded when possible. It must not repeatedly rotate or rewrite a large file during gameplay.

Old sessions are pruned only from a background/non-render path when a new diagnostic session begins.

## Performance constraints

The diagnostic system must not recreate the Termux logger by continuously spawning `logcat` or running shell commands during gameplay.

Required constraints:

- no filesystem I/O in `vkQueuePresentKHR`
- no filesystem I/O in frame pacing callbacks
- no string formatting or unbounded allocation on the native presentation hot path
- no per-frame GameNative file append
- reuse the existing 500 ms performance sampling cadence where possible
- native event submission must be bounded and non-blocking from the presentation path
- disk writes happen on a dedicated/background worker
- recent app logcat is captured only during explicit export or crash handling

Diagnostic capture must remain active while LSFG is Off, but Off must remain presentation-native; diagnostics may observe Off state but may not reintroduce `LsContext`, scheduler, pacer, config polling, or presentation-path work.

## Lifecycle

### Session start

GameNative starts `LsfgDiagnosticSession` when the game/container session begins, after the container identity/path is available. Initial metadata and LSFG configuration state are recorded once.

### Runtime

Existing GameNative metrics feed low-frequency samples. LSFG state changes and native diagnostic summaries are consumed asynchronously. Runtime enable/disable transitions remain within the same diagnostic session.

### Session stop

GameNative flushes and closes the diagnostic writer when the active game session ends. Closing the recorder must not delay or block process teardown indefinitely.

### Export after session end

The most recent completed session remains exportable from Settings under the same Export LSFG Diagnostics action. If an active session exists, the exporter takes a consistent snapshot of the existing rolling file without stopping capture.

## Integration boundaries

### GameNative

Expected integration areas:

- `SettingsGroupDebug.kt`: add the export action under Save Logcat and document-creation flow.
- new diagnostics utility/service under `app.gamenative.utils` or a focused diagnostics package.
- `PerformanceMetricsCollector`: expose/forward low-frequency metrics without duplicating samplers.
- game/container launch lifecycle: start the diagnostic session.
- game/container teardown lifecycle: stop the diagnostic session.
- `LsfgVkManager`: expose safe diagnostic paths/current config/runtime metadata needed by the exporter.

The exporter should not make `SettingsGroupDebug` responsible for assembling diagnostic content; UI code only launches the export and reports success/failure.

### lsfg-vk-android

Expected integration areas:

- a bounded diagnostic state/event publisher owned by the layer runtime
- `hooks.cpp` for WSI/config/capability/state transitions
- `context.cpp` for aggregated Adaptive/framegen stage information
- existing background runtime I/O worker or a sibling worker for diagnostic file publication

The native implementation must preserve the current rule that config/stats/diagnostic filesystem work stays off the present hot path.

## Error handling

The export is best-effort and section-oriented.

Examples:

- no active/recent LSFG session: export a report containing available device/app/logcat information and state `no LSFG diagnostic session available`
- missing `stats.txt`: mark the current-state subsection unavailable
- missing wrapper file: mark wrapper diagnostics unavailable
- malformed native diagnostic record: preserve the raw line under warnings when possible and continue
- inability to read one container file: record its path category and exception class/message without aborting other sections
- SAF output failure: report export failure to the UI because no usable artifact was written

## Test strategy

### GameNative unit tests

- rolling session enforces byte cap
- session retention keeps only configured number of recent sessions
- exporter produces all required section headers
- optional-source failure does not abort export assembly
- wrapper diagnostics absent/present behavior
- current config/stats/vsync snapshot inclusion
- metrics sample mapping preserves source/generated/output distinction
- export filename format
- active-session export does not stop or truncate capture
- diagnostic service is started/stopped by game-session lifecycle

### GameNative integration/static contracts

- Export LSFG Diagnostics appears immediately below Save Logcat
- existing Save Logcat remains unchanged
- export runs file assembly on an I/O dispatcher/background executor
- no continuous `logcat` process exists in diagnostic session code
- no new per-frame file writes are introduced

### lsfg-vk-android tests/contracts

- native diagnostic publisher does not perform filesystem operations from `vkQueuePresentKHR`
- bounded queue/ring behavior under event pressure
- capability matrix snapshot contains required portability decisions
- controlled WSI recreation records original/effective provenance and reason
- sustained Off produces no scheduler/pacer/context events after teardown except explicit Off-state summary
- Adaptive-zero remains distinguishable from Off
- stage-cost summary contains handoff/dispatch/wait/generated-present components
- successful generated-present count is exported
- high event pressure drops/coalesces low-priority periodic records rather than blocking presentation

## Acceptance criteria

The feature is accepted when:

- A user can reproduce an LSFG issue without Termux and export one text file from GameNative.
- The export contains enough evidence to determine whether LSFG was Off, Adaptive-zero, or actively generating at any relevant point in the session.
- The export proves whether generated frames were successfully presented, not merely requested.
- Source FPS, generated FPS, and output FPS remain distinct.
- Adaptive probe decisions can be correlated with measured stage cost and source cadence.
- On -> Off transitions include WSI restoration evidence and sustained-Off evidence.
- Xclipse and A6xx capability decisions are visible without vendor-specific diagnostic modes.
- Rolling diagnostics do not add filesystem work to the Vulkan present path or continuous raw logcat scraping.
- LegacyDebug CI/unit/static tests pass with the feature enabled in the build.
- Physical Xclipse 940 and A6xx validation can be performed using only the exported diagnostic file for the logging portion of the test procedure.

## Explicit non-goals

- Replacing GameNative's crash-report system.
- Capturing arbitrary system-wide logcat continuously.
- Capturing user account/Steam credentials or unrelated private application data.
- Turning diagnostics into a permanent on-screen profiler/HUD.
- Performing frame-by-frame text logging.
- Adding vendor-specific Xclipse-only or Adreno-only logging modes.
