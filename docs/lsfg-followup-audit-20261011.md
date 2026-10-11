# LSFG follow-up audit — 2026-10-11

This read-only pass found nine correctness/control issues, one dormant helper defect, and four potential performance bottlenecks. Production code was not changed. Passing Build #787 does not establish that the remaining ownership and recovery defects are resolved.

## Baseline and evidence

- GameNative: `0440c57025ce87a32b699676ef52f366fb47a82d`, branch `feature/lsfg-host-presentation-feedback-20261007`.
- Shared lsfg-vk runtime: `5f14ebc3002401d4c762966cddf1793b3d3ec3f5` in `app/src/main/cpp/lsfg-vk-android`.
- Existing upgradeable APK: Build #787, packaged GameNative source `eb5642a6b59f0815893efff65e62b8f4c4cb669e`. Later baseline commits added audit/report material; production code is unchanged.
- Review covered Native observation/governor/resource logic, Legacy quality/performance runtimes, completion import/retirement, manager settings and handoff, FPS-cap lifecycle, host admission/presentation and Frame Queue transitions.
- Reproductions use extracted production methods or actual production Kotlin/Shared Flow classes with deterministic clocks and platform-boundary fakes. Seconds below are simulated evidence duration, not elapsed device benchmark time.
- No physical device or fresh device traces were available. Display overlap, actual latency and optimization benefits below are inferred risks where explicitly stated.
- Saved harnesses and commands: [audit evidence](audit-evidence/lsfg-20261011/README.md). They are outside application/build inputs. Some deliberately fail the desired-behavior assertion on the audited implementation; those failures are findings, not passing tests.

P1 means prioritize before calling these features stable. P2 means correct the interaction/failure path next. These are audit priorities, not measured crash frequencies.

## Correctness and control findings

### F1 — P1: Legacy completion import failure can close an unrelated descriptor

**Locations:** Shared `src/context.cpp:5856-5893,6294-6340`; `src/mini/semaphore.cpp:85-110`.

The generic/Xclipse asynchronous path clears caller FD ownership only after a successful consuming `Mini::Semaphore` constructor. The constructor already closes the FD on creation/import failure. Catch cleanup then closes the retained numeric FD again. A concurrent open can reuse that number between closes, allowing fallback cleanup to close an unrelated synchronization, file or socket descriptor. This path is active when nonconservative async SYNC_FD completion is selected.

**Independent reproduction:** extracted production caller blocks with injected import failure and deterministic FD reuse closed the unrelated descriptor at all three sites: generated-output, generated-batch and zero-history-batch (`still_open=0`). Driver behavior and concurrent FD reuse were simulated; an on-device failure was not observed.

**Potential solution:** transfer ownership unconditionally before import, using `std::exchange(fd,-1)` or an owning FD wrapper. Add allocation/import-failure tests with descriptor reuse at all three sites. Preserve exactly one owner on every success/failure branch.

### F2 — P1: backend handoff accepts a previous transition's acknowledgment

**Locations:** `LsfgVkManager.kt:944-961,1667,1703-1707`; Shared `src/hooks.cpp:681-715,762-803`. Kotlin paths are under `app/src/main/java/app/gamenative/utils/` unless specified otherwise.

Readiness accepts any `stats.txt` younger than two seconds. Neither readiness reader nor either Shared stats publisher validates/emits the consumed configuration transaction/revision. After publishing a new source-only command, a rapid Native→Legacy→Native transition can use an earlier source-only report and skip waiting for Legacy to consume the new disable. Old generation-ready reports can likewise be mistaken for current readiness.

**Independent reproduction:** unchanged extracted reader/readiness logic returned `readyForSourceOnly=true` for both identity-free old stats and explicit wrong transaction8/revision12 while the desired request was transaction9/revision13. This proves stale acknowledgment acceptance. Actual simultaneous generation remains an inferred device risk.

**Potential solution:** both stats publishers must emit the identity of the configuration actually consumed. Require the current owner/revision acknowledgment before Native activation. A timestamp-age check alone is insufficient. Preserve the existing guest-suspension-aware wait behavior and test delayed consumption during rapid backend/settings changes.

### F3 — P1: disabling Adaptive FPS Cap overwrites a newer limiter edit

**Locations:** `powercontrol/AdaptiveFpsCapController.kt:119-120,178-205`; user edits `ui/screen/xserver/XServerScreen.kt:703-718`; feature stop `powercontrol/PowerManager.kt:640-643`.

The controller notices user changes during its periodic cycle, but stop/pause restores its cached original ceiling without checking whether it still owns the currently applied limiter. A user edit immediately followed by disabling/pausing adaptive cap can be replaced by stale restoration.

**Independent reproduction:** actual production Adaptive cap/controller classes, with engine/log stubs: after adaptive60→45, setting the limiter to0 or90 before the next observation then restoring applied60 in both cases.

**Potential solution:** separate explicit user ceiling from adaptive effective cap; associate applies/restoration with an edit/owner generation. Restore only if the controller still owns its last acknowledged effective cap. Test edit→disable, edit→pause, off→stop and failed-apply variants.

### F4 — P1: a rejected Native density probe can prevent all later recovery

**Locations:** `app/src/main/cpp/lsfg/vkr_lsfg.cpp:713-718,739-771`.

Rearming requires a material source/GPU/Flow operating-point change. A transient downstream delivery failure can reject a0→1 generated-density probe. When downstream delivery heals at unchanged source/GPU/Flow, the rejected-point hold persists indefinitely; elapsed healthy time alone cannot rearm it. A queue/workload/Flow policy change can clear the hold, but normal downstream recovery need not change any of them.

**Independent reproduction:** extracted production governor remained at source-only cap0 with rejection active after300 healthy seconds at30 sourceFPS, GPU60% and unchanged Flow. The eventual-recovery assertion failed. The pacer and Flow boundary were substituted to isolate the density policy.

**Potential solution:** increasingly slow bounded probes with a maximum retry interval, or a verified downstream-recovery epoch that invalidates rejection. Revert failed probes promptly. Test both eventual recovery after a transient and resistance to rapid repeated probing under stationary sustained overload; the current stationary-hold tests should not be treated as sufficient recovery coverage.

### F5 — P1: Native Adaptive Flow cannot automatically raise quality again

**Locations:** Native `vkr_lsfg.cpp:577-579`; Shared `src/adaptive_flow_controller.cpp:778-784`.

Native truthfully marks generated-work and retained timing unavailable because it has no per-pass timestamp queries. Shared Flow can lower scale using cadence evidence, but its quality-recovery gate requires timing provenance that Native never supplies. Reduced or seeded quality can remain below its target despite healthy delivery and headroom. Manual reseeding can still change the scale.

**Independent reproduction:** extracted Native observations plus the real Shared controller stayed at scale0.80, target1.00 for300 healthy seconds at30 source/120 confirmedFPS and GPU40%. A matched control with real-valued timing observations reached1.00. The automatic-recovery assertion failed.

**Potential solution:** asynchronous Native GPU timestamp measurements are preferable. Alternatively add explicitly cadence-only bounded quality probes with fresh output/source/density regression checks and rollback. Never relabel cadence or global utilization as measured LSFG shader cost.

### F6 — P2: Native target feedback hides a GPU-backed source shortfall

**Locations:** Native `vkr_lsfg.cpp:519-531`.

Capping Flow's feedback target to sourceFPS×(density+1) prevents intentional lower density from manufacturing pressure. It also hides a requested-output shortfall at maximum density when source cadence collapses under GPU load.

**Independent reproduction:** requested120FPS, source20FPS, confirmed80FPS, maximum4x density and GPU98% produced feedback target80/no deficit and zero scale/density changes over450 seconds with the real Shared controller. All requested frames were delivered; this is a source-target control blind spot rather than invented WSI loss. Whether cheaper generation improves a particular game's source cadence requires device measurement.

**Potential solution:** carry requested cadence and attainable/retained cadence separately. Suppress pressure caused by deliberate density caps while allowing bounded measured probes for GPU-backed source starvation. Verify probes improve source/output cadence before committing and avoid CPU-limited quality loss.

### F7 — P2: failed Legacy restoration is committed as a successful desired policy

**Locations:** `LsfgVkManager.kt:1623-1654,1350-1399`.

Native retirement can receive `restored=false` from Legacy config publication and report `legacy-restore-failed`, then call `commitNativeApply(...initialized=false)` with default `failed=false`. Applied revision advances, and the measurement policy advertises requested active Legacy generation with stride2–4 while the guest can retain its previous source-only configuration. This can corrupt source/output units used by power governors.

**Evidence:** source-verified failed-publication branch and its successful commit path; file-write failure was not injected in the application during this pass.

**Potential solution:** publish explicit failure and the actual surviving source-only policy, keeping desired and applied contracts separate. Add write/rename failure injection during Native→Legacy handoff and verify revision, stride, readiness and cap behavior remain consistent.

### F8 — P2: governor observation identity is lost before cap submission

**Locations:** `powercontrol/AdaptiveFpsCapController.kt:133-170`; `powercontrol/PowerManager.kt:541-553`.

Observation and decision are made under an earlier pacing/cap state. Command submission captures the current pacing/generation rather than the observation's identity. A transition between those stages can let an old-policy decision apply under a new policy and then commit controller state. Existing queued-command invalidation protects changes after submission, not this earlier boundary.

**Evidence:** source-verified race window; a deterministic paused-between-observe-and-submit integration reproduction remains to be added. Do not treat this as an observed device race frequency.

**Potential solution:** carry observation epoch, user-cap edit generation and owner identity through decision, apply acknowledgment and controller commit. Reject stale decisions at each boundary; test a transition deliberately inserted between observation and submission.

### F9 — P2: transient Native Flow rebuild failure permanently disables the runtime

**Locations:** Native `vkr_lsfg.cpp:429-458`; `lsfg_chain.cpp:27-77`; host `winlator/VulkanRendererContext.cpp:3695-3708`.

A Flow-only rebuild retires and releases the working graph before allocating its replacement. If the replacement is invalid, preparation sets `unavailable=true`, destroys it and rejects every subsequent prepare. The host also marks frame generation unsupported. A temporary descriptor/image allocation failure can therefore become permanent source-only operation for this runtime, even after memory recovers or a cheaper Flow setting is requested.

**Independent reproduction:** actual extracted prepare method with an invalid-allocation boundary, followed by healed allocation and cheaper requested scale, returned false again: `unavailable=1`, no chain, only one build attempt. The real chain constructor has invalid-return branches for descriptor/image/pass allocation, so this failure path is reachable; device OOM was not induced.

**Potential solution:** distinguish transient allocation failures from unsupported features. Keep source-only delivery operational and allow bounded cooled-down rebuild retries at a known feasible scale. Retain reusable source resources where possible. Keeping two full graphs for rollback must be weighed against memory peaks; simply restoring a permanent unsupported flag is insufficient.

### F10 — low / latent: timeout-zero Legacy polling never examines fences

**Locations:** Shared `framegen/v3.1_src/context.cpp:762-798` and `framegen/v3.1p_src/context.cpp:763-799`; selectors `src/context.cpp:1601-1610`.

With an outstanding `shouldWait`, timeout0 creates an already-expired deadline. The method returns false before checking even fully signaled fences.

**Independent reproduction:** both variants at0/1/3 generated outputs returned false with zero fence checks and `shouldWait` still set: six cases.

**Reachability correction:** the current zero-time Adreno fallback callers are dormant because deferred completion is disabled and conservative pending completion is not selected by the active async selector. This finding does not establish current S20+ collapse.

**Potential solution:** explicit nonblocking fence checks for timeout0; report completion/timing and clear pending state only when all required fences are ready. Test ready and unready fences before enabling those callers.

## Potential performance bottlenecks

| ID | Mechanism and source | Potential solution and measurement |
| --- | --- | --- |
| B1 | Cold cache work holds the global `LsfgVkManager` monitor (`:1028-1035`), also used by request allocation/backend transitions (`:382-435`). `LosslessScaling.java:180,223,285-289` can probe Vulkan, hash/validate the DLL and synchronously translate shaders. Settings/cancellation can block for the entire operation. | Use dedicated cache single-flight coordination; capture policy identity briefly, build outside the manager monitor, then revalidate before publication. Measure cold/stale-cache UI delay and superseding-request latency. |
| B2 | Legacy quality/performance contexts allocate/free a preprocessing command buffer plus one per generated output every source cycle (`v3.1_src/context.cpp:359,612`; perf`:360,613`). At30→120FPS this is approximately120 allocations/frees per second despite an eight-slot retirement ring. | Retain per-slot buffers and reset only after existing retirement fences. Measure CPU recording/driver overhead and allocator counts. Consider semaphore pooling only after measuring; imported/exported semaphore lifetime needs separate proof. |
| B3 | Legacy `waitForCompletion()` gives each fence a fresh250ms timeout (quality`:800-815`, perf`:801-816`). Eight slots×three outputs can approach6s during incremental slow progress; pending deletion collection runs under global runtime mutex (`lsfg.cpp:55-75,92-104,244-245`). | One absolute deadline for the operation; nonblocking deferred-deletion polling with resources retained on expiry. The6s figure is a code-derived ceiling per context, not normal measured latency; a configured5s per-fence timeout raises it further. |
| B4 | Host optional acquisitions share a2–20ms deadline, but generated-image reuse then calls `WaitForFences(...UINT64_MAX)` (`winlator/VulkanRendererContext.cpp:3845-3868`). Optional generation can block source work outside its stated acquisition budget. Queue changes also wait on GPU/presenter drains under the frame lock (`:6562-6597`, drain`:2861-2874`). | Include reuse readiness in admission/budget accounting or consume/retire acquired images through safe asynchronous dependencies. Do not abandon acquired images/semaphores on timeout. Instrument reuse wait, drain duration and source cadence across FIFO/Mailbox and queue depths. Driver and display stalls remain unmeasured. |

## What this pass did not establish

- No new independent ordinary-path Fixed2x/3x/4x arithmetic defect was reproduced here. Completion ownership, failed rebuilds and stale handoff can still disrupt generation at any multiplier.
- Shared Adaptive Flow graphs correctly reuse pipelines/shaders/samplers/output images; ordinary scale requests select prepared graphs. Native Flow-only rebuilds preserve compiled pipelines and full-resolution source images. Repeated shader compilation is not supported as an explanation for ordinary Flow-transition stalls.
- Mailbox desired-dispatch pacing has a250ms future guard; it should not be described as unbounded desired-time sleeping. Host driver/fence/drain waits are separate risks.
- Severe thermal status blocks recovery; Shared intentionally avoids using thermal status alone as a quality actuator. No new independent thermal defect was confirmed.
- Logical submission counts, confirmed output telemetry and deterministic tests still do not prove physical panel delivery, image quality or input latency.

## Validation performed in this pass

The existing governor, cadence and presentation regression scripts all passed again on unchanged production code. Governor coverage included2,851,200 observations across72 operating points and20,532 real pacer/scheduler/Flow interaction cycles. Cadence tests covered28,800 healthy slots,4,000 stalled batches,7,200 Fixed batches and12,000 fluctuating-density/source batches. Presentation tests retained their sparse-slot, policy ownership, physical-feedback truth, FIFO/Mailbox, transform and resource-reuse checks.

These successes coexist with the new reproductions above. Existing coverage exercises recovery after material workload changes; it misses healed downstream-only recovery, Native timing-ineligible Flow recovery, stale Legacy handoff identities and cap restoration before the next user-edit observation. F7/F8 require additional deterministic failure/race injection before fixes are accepted. No Android/device suite or new APK build was required for these documentation/evidence-only changes.

## Recommended implementation and validation order

1. Fix F1 FD ownership and F2 consumed-configuration acknowledgment first. Add failure injection and delayed-consumption transition tests.
2. Fix F3/F7/F8 desired/applied cap and policy ownership. Assert observation, command, acknowledgment, revision and sample units agree through rapid edits and failed publication.
3. Fix F4/F5 recovery contracts and F6 target-domain separation. Test stationary overload, healed downstream stalls, source20↔30↔60 changes, unavailable sensors, measured zero and bounded probe rollback.
4. Fix F9 allocation recovery and F10 latent polling; consolidate B3 deadlines. Test memory-pressure and timeout handling without freeing resources still in use.
5. Optimize B1/B2/B4 after collecting timings. Validate both backends, Fixed2x/3x/4x and Adaptive generation, Fixed/Adaptive Flow, FIFO/Mailbox, Frame Queue Off/0/1/2, and source acceleration/starvation.
6. Device acceptance: timestamp source arrival, generation submission/completion, presentation submission and confirmed delivery in one trace. Compare generated density/cadence, expired frames, p95 source delay, input latency and sustained thermals before/after each change. Report unavailable timing honestly.

## Completion checklist

- [x] Verify baseline and preserve existing signed APK.
- [x] Review Native controls, Legacy runtime, manager/power ownership and host presentation/resource paths.
- [x] Independently run density, Flow, target-tracking, FD reuse, zero-poll, cap-restoration, acknowledgment and resource-rebuild reproductions.
- [x] Save reproducible evidence and distinguish source-verified races/failure paths from measured behavior.
- [x] Publish prioritized findings and proposed solutions through the GitHub connector.
- [ ] Implement the fixes in a subsequent implementation pass.
- [ ] Validate physical display cadence, sustained operation and performance on-device.

No new APK was compiled for this read-only pass. Build #787 remains the available tested package; audit findings are not fixed in it.
