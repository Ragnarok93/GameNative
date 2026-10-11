# LSFG follow-up audit — 2026-10-11

Read-only audit of GameNative `0440c57025ce87a32b699676ef52f366fb47a82d` and pinned lsfg-vk runtime `5f14ebc3002401d4c762966cddf1793b3d3ec3f5`. Build #787 remains the tested APK. Production fixes are outside this audit request. Findings are provisional until their call paths and reproductions are verified.

## Verified finding so far

**Native density recovery can remain blocked after a transient failure.** `vkr_lsfg.cpp:710-742` rearms a rejected probe only after source/GPU/Flow changes. A downstream-only recovery does not meet these conditions. Extracted production governor reproduction: after a failed source-only-to-2x probe, 300 seconds of restored downstream capacity at 30 source FPS, GPU60% and unchanged Flow still leaves generation cap0 and the rejected-point hold active. A new workload/Flow/queue policy can clear it; ordinary transient recovery need not do so. Proposed solution: sustained downstream recovery epochs or bounded, increasingly infrequent recovery probes, with strict rollback and maximum retry interval; preserve resistance to rapid stationary oscillation.

## Remaining audit work

- [ ] Independently rerun Legacy zero-time completed-fence polling reproduction and verify error fallback call chain.
- [ ] Verify Native Adaptive Flow recovery eligibility with the real Shared controller.
- [ ] Audit settings/cache lock contention and cap lifecycle interactions.
- [ ] Assess presentation pacing, optional acquisition waits and resource-change stalls; distinguish code-level mechanisms from measured device bottlenecks.
- [ ] Publish the completed prioritized report and recommended implementation order.
