# LSFG follow-up audit — 2026-10-11

Read-only audit of GameNative `0440c57025ce87a32b699676ef52f366fb47a82d` and pinned lsfg-vk runtime `5f14ebc3002401d4c762966cddf1793b3d3ec3f5`. Build #787 remains the tested APK. Production fixes are outside this audit request. Findings below distinguish reproduced defects, source-verified failure paths, and unmeasured performance risks. The audit is still in progress.

## Verified finding so far

**Native density recovery can remain blocked after a transient failure.** `vkr_lsfg.cpp:710-742` rearms a rejected probe only after source/GPU/Flow changes. A downstream-only recovery does not meet these conditions. Extracted production governor reproduction: after a failed source-only-to-2x probe, 300 seconds of restored downstream capacity at 30 source FPS, GPU60% and unchanged Flow still leaves generation cap0 and the rejected-point hold active. A new workload/Flow/queue policy can clear it; ordinary transient recovery need not do so. Proposed solution: sustained downstream recovery epochs or bounded, increasingly infrequent recovery probes, with strict rollback and maximum retry interval; preserve resistance to rapid stationary oscillation.

## Additional independently verified findings

- **Native Adaptive Flow cannot automatically recover quality upward.** Native observation sets both generated timing flags false (`vkr_lsfg.cpp:577-579`), while Shared recovery requires timing provenance (`src/adaptive_flow_controller.cpp:778-784`). Real Shared controller plus extracted Native observations stayed at scale0.80, target1.00, for300 healthy seconds; a matched timing-enabled control recovered to1.00. Add truthful GPU timestamps or explicit bounded cadence-only quality probes with rollback; do not relabel cadence as measured GPU cost.
- **Native target tracking hides GPU-backed source shortfalls.** `vkr_lsfg.cpp:519-531` caps feedback target to sourceFPS×(density+1), including maximum density. At requested120FPS, source20FPS, delivered80FPS, GPU98%, the real controller made zero adjustments over450 seconds and reported output target80/no deficit. Separate requested and attainable cadence; probe source recovery with measured evidence. Whether a cheaper setting improves a particular game remains device-dependent.
- **Active Legacy completion-import failure can double-close an FD.** Shared `src/context.cpp:5866-5877,6294-6338` transfers caller ownership only after successful import on the generic/Xclipse async path. `src/mini/semaphore.cpp:85-110` already closes the FD on failure; caller fallback closes its stale numeric value again. Three extracted caller reproductions injected failure and numeric FD reuse: generated output, generated batch and history batch each closed the unrelated reopened descriptor. Transfer ownership unconditionally before consuming import, preferably using RAII, and test all failure paths.
- **Latent Legacy zero-time fence polling defect.** Both v3.1/v3.1p context wait methods return false before checking any fence at timeout0. Six all-signaled cases (0/1/3 generatedframes perversion) returned false with zero fence checks. Current callers are dormant: deferred Adreno completion is disabled, and conservative pending batch creation is unreachable under the async/conservative selector. Fix the helper before enabling these paths; this does not establish an active device collapse.

## Remaining audit work

- [x] Independently rerun Legacy zero-time polling and active FD-import failure reproductions; verify reachability.
- [x] Verify Native Adaptive Flow recovery and source-limited target tracking with the real Shared controller.
- [ ] Audit settings/cache lock contention and cap lifecycle interactions.
- [ ] Assess presentation pacing, optional acquisition waits and resource-change stalls; distinguish code-level mechanisms from measured device bottlenecks.
- [ ] Publish the completed prioritized report and recommended implementation order.
