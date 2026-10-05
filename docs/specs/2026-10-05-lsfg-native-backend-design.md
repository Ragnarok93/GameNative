# LSFG Native Backend Integration Design

**Date:** 2026-10-05  
**Repository:** Ragnarok93/GameNative  
**Branch:** fix/lsfg-backend-state-queue-telemetry-20261004  
**Status:** Approved architecture; implementation pending written-plan review

## 1. Purpose
Establish a real, explicitly identifiable native LSFG backend alongside the existing legacy lsfg-vk path without weakening the protected Adreno synchronization topology or conflating UI state with runtime state.

## 2. Current Problem
The Quick Menu can change the requested renderer state later than the user interaction. More importantly, the current branch deliberately reports native requests as runtime-bridge-unavailable/legacy because the existing native implementation is not yet proven to be wired into the active renderer build and JNI/control path.

Therefore the system must not claim that native LSFG is active merely because the UI selection is Native.

## 3. Backend State Model
Use four distinct concepts:
- backend_request: what the UI/user requested.
- authoritative backend state: the immediately committed application state.
- runtime backend state: what the renderer has actually initialized and is executing.
- implementation provenance: explicit native or legacy identity attached to runtime metrics and frame provenance.

Every request receives a monotonic request serial. Runtime application receives a separate apply serial. Backend generations identify temporal/lifecycle epochs.

Required telemetry:
- backend_request
- backend_state_changed
- backend_runtime_applied
- backend_apply_latency_ms
- backend_apply_result
- request_serial
- backend_apply_serial
- backend_generation

A native request that cannot initialize must remain visibly/reportably distinct from a successfully initialized native runtime.

## 4. Renderer Boundary
Expose a narrow backend abstraction around frame generation. Both implementations consume the existing shared frame-generation controls:
- multiplier
- target rate
- adaptive frame generation
- flow scale
- refresh rate
- source-frame information
- output/presentation integration

The native implementation must not duplicate GameNative's entire pacing or presentation pipeline.

The existing legacy LSFG-vk backend remains the protected compatibility path.

Native provenance must explicitly identify the implementation, rather than emitting only generic lsfg-vk identifiers.

## 5. Transition Lifecycle
A backend change is a renderer lifecycle transition:
1. Record backend_request.
2. Increment request/generation bookkeeping as appropriate.
3. Quiesce host delivery/compositor ownership.
4. Reset the temporal epoch.
5. Retire resources owned by the old backend.
6. Initialize the requested backend.
7. Validate successful initialization.
8. Publish backend_runtime_applied.
9. Resume host delivery.

Temporal optical-flow history and adaptive Flow Scale history are not transferred between implementations during the initial integration.

The transition must prevent frames generated under the previous backend/epoch from being presented after the new backend becomes authoritative.

## 6. Synchronization Constraints
Do not redesign the protected Adreno synchronization path.

Specifically, the integration must not replace or weaken the established S20+ reference topology involving GPU-semaphore source handoff and bounded host completion.

Do not introduce normal-path:
- vkDeviceWaitIdle
- vkQueueWaitIdle
- broad queue serialization
- speculative cross-device synchronization

Existing synchronization required for explicit lifecycle teardown may remain isolated to the transition path and must not become a per-frame mechanism.

## 7. Second Present Queue
The existing second-queue experiment remains telemetry-only.

Instrument enough information to establish whether graphics and presentation actually overlap:
- graphics_queue_submit_serial
- graphics_queue_submit_ms
- graphics_queue_blocked_ms
- present_queue_present_serial
- present_queue_present_ms
- present_queue_blocked_ms
- queue family/index
- render-complete semaphore
- host present ID

Do not infer concurrency merely from selecting a second queue. Do not add queue-idle waits to make the telemetry easier to interpret.

## 8. Native Integration Boundary
The existing VulkanRendererLsfg.cpp implementation must be reconciled with VulkanRendererContext before being enabled.

Required integration work:
- include the native implementation in the actual build target;
- reconcile declarations/state ownership with VulkanRendererContext;
- establish the smallest JNI/control seam required to enable/configure native LSFG;
- route shared frame-generation controls through that seam;
- establish explicit native frame provenance;
- ensure native lifecycle teardown is ordered with host delivery quiescence;
- preserve legacy behavior when native is not selected or initialization fails.

No title-specific hacks are part of this integration.

## 9. Configuration Semantics
Runtime configuration must include explicit backend identity:

backend=native|legacy

Telemetry/configuration headers must distinguish:
- requested backend;
- actual runtime backend;
- implementation provenance;
- backend generation.

The existing generation mode, multiplier, adaptive target, and flow-scale settings remain shared rather than being duplicated per backend.

## 10. Failure Semantics
Native initialization failure must:
1. report the failure explicitly;
2. leave runtime provenance as legacy if legacy remains active;
3. avoid claiming native runtime activation;
4. avoid presenting frames from an incompletely initialized native epoch;
5. preserve a clean recovery path to legacy.

A successful UI selection is therefore not equivalent to successful runtime activation.

## 11. Testing
Add contract-level coverage for:
- immediate authoritative backend state;
- callback routing;
- request/apply serials;
- native-request failure reporting;
- successful native runtime identity;
- legacy runtime identity;
- lifecycle ordering;
- temporal epoch isolation;
- provenance identity;
- queue telemetry presence;
- absence of new normal-path queue/device idle waits.

Runtime validation should compare equivalent workloads on S20+ and S25 FE/Xclipse 940 where applicable.

## 12. Performance/Regression Gate
No native integration change is considered successful solely because it builds.

Acceptance requires evidence that:
- native output is genuinely produced by the native implementation;
- source frames remain protected;
- generated frames are not falsely attributed;
- no stale temporal frames cross backend generations;
- host delivery behavior is measurable;
- no new Adreno regression is introduced;
- normal-path synchronization cost does not materially regress the existing reference;
- native performance is evaluated separately from the existing completion-bound behavior.

## 13. Explicit Non-Goals
This phase does not:
- redesign LSFG pacing globally;
- redesign Adreno synchronization;
- change adaptive Flow Scale control policy;
- make physical display confirmation a real-time control loop;
- remove the existing legacy backend;
- add title-specific compatibility behavior;
- treat queue selection as proof of parallel execution;
- preserve temporal history across backend transitions.

## 14. Implementation Principle
Prefer the smallest integration seam that turns the already-present native implementation into a real backend while leaving the mature legacy pipeline and protected synchronization behavior intact.

Any change that requires broad synchronization redesign, new per-frame waits, or substantial changes to pacing should be treated as a separate proposal rather than being smuggled into native-backend integration.