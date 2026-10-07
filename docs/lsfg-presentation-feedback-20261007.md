# Host presentation and physical-feedback correction

Based on GameNative `15b9706e24df56107fbf47133ec6bf31088414bc`.
Pinned layer: `926ebc1314935030d4c58278458bd26291124c4b`.

## Findings and implementation

- A supported IDENTITY transform still differs from Android currentTransform.
  Select currentTransform and inverse-transform scene/cursor vertices, including
  the offscreen Native source composite. A measured transform change joins the
  existing debounced capability recovery; unchanged capabilities do not rebuild.
- Independent inferred interpolation-lane clocks drift when Adaptive FG density
  changes. Retain the source timeline's batch timestamps and retire missed slots.
  A sparse third interpolation no longer inherits a clock established seconds ago.
- Native Flow graph creation repeatedly compiled identical pipelines.
  Cache immutable shader/layout pipelines for the lifetime of LsfgShaders.
  Flow updates retain source images and pacer credit, retire old GPU users, and
  release retired scale-dependent allocations before creating replacements. Flow history warms; context epoch stays fixed.
  This avoids recompilation, but still allocates a new flow resource graph;
  device timing must establish the remaining update cost.
- WSI acceptance and logical output cannot satisfy adaptive output targets.
  Native control samples the host confirmation window; Legacy consumes matched
  host feedback. Available confirmation with zero delivery is a measured deficit.
  Unavailable or stale confirmation remains unknown; the feedback lease expires
  after 250 ms without a matching host packet. Suspend recovery runs independently
  of confirmation availability. Real Native policy changes reset the evidence
  window and reject retired confirmations; preference-only no-ops retain it.
- Shared WSI failure (persistent suboptimal or present p95 exceeding two refresh
  periods) inhibits density backoff driven by presentation loss. Existing Native
  admission still limits work; the compute algorithm and admission equations are
  unchanged.
- A Native FIFO preference change preserves effective Mailbox policy and activation.
  Only configuration attribution advances; no cache initialization or re-arming.
  Reuse is decided by the executor against the renderer-owned applied snapshot,
  so superseded preference requests cannot trigger initialization or skip a real
  queue/refresh policy change.
- Provenance v3 and feedback v2 transport manager transaction/configuration identity.
  Native uses the compute context epoch, separately from swapchain generation.
  The final host logs the identity alongside present/delivery IDs. Legacy rejects
  feedback from retired sessions, epochs, transactions or configurations.
- Expired worker desired times are submitted as zero to retire acquired WSI images;
  original intended timestamps remain attached for lateness attribution.

## Verification

GitHub builds run deterministic production-code regressions for sparse lane 3,
stale timestamps, handoff epochs, retired feedback, unavailable confirmation,
22 physical versus 70 logical FPS, measured zero, all rotation/mirror transforms,
and pipeline sharing/move/destruction/failure behavior. Existing Native pacer,
bridge, controller and timing checks remain enabled.

On-device acceptance still requires new logs: current_transform=pre_transform,
suboptimal recovery without rebuild loops, present latency and unique physical
cadence near the target, bounded flow-resource-update time without a context
epoch/pacer reset, no seconds-stale third slot, and consistent physical target
satisfaction across Native, Legacy, Adaptive Flow and power control.
