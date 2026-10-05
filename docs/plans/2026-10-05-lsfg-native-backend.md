# LSFG Native Backend Integration Plan

> **For agentic workers:** Use the host's available task-by-task implementation workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire GameNative's existing native LSFG implementation into the active Vulkan renderer as a genuinely selectable backend while preserving legacy LSFG-vk behavior and the protected Adreno synchronization path.

**Architecture:** Keep Quick Menu state, runtime state, and implementation provenance separate. Introduce the smallest native-renderer control seam necessary to connect `VulkanRendererLsfg.cpp` to the active `VulkanRendererContext`, with backend transitions quiescing host delivery and resetting the temporal epoch before resource retirement. Legacy LSFG-vk remains the fallback and reference path.

**Tech Stack:** Kotlin/Android Compose, C++17 Vulkan renderer, JNI, Gradle/CMake, GitHub connector, existing GameNative LSFG telemetry/contract tests.

## Global Constraints

- Native must not be reported active until native initialization succeeds.
- Runtime configuration and telemetry must explicitly distinguish `backend=native|legacy` and implementation provenance.
- Preserve shared multiplier, target-rate, adaptive-generation, flow-scale, refresh-rate, and source-frame controls.
- Do not redesign LSFG pacing or adaptive Flow Scale policy.
- Do not introduce normal-path `vkDeviceWaitIdle`, `vkQueueWaitIdle`, broad queue serialization, or speculative cross-device synchronization.
- Keep the second present queue telemetry-only; queue selection is not proof of concurrency.
- Preserve the established S20+ Adreno GPU-semaphore source handoff plus bounded host completion topology.
- Backend transitions must prevent old-epoch frames from reaching presentation after the new backend is authoritative.
- Do not carry optical-flow or adaptive Flow Scale temporal history across backend implementations in the initial integration.
- Native initialization failure must leave the runtime truthfully legacy or inactive and provide explicit failure telemetry.
- No title-specific compatibility hacks.
- Do not claim build/runtime success without fresh verification evidence.

---

### Task 1: Establish the native backend interface and build boundary

**Files:**
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.h`
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.cpp`
- Modify: `app/src/main/cpp/winlator/VulkanRendererLsfg.cpp`
- Modify: the existing native Vulkan CMake target containing `VulkanRendererContext.cpp` (exact CMake path to be confirmed from repository build files before editing)
- Test: existing native renderer/LSFG build target plus a focused compile check

**Interfaces:**
- Consumes: existing `VulkanRendererContext` device/swapchain/frame-generation state and `VulkanRendererLsfg.cpp` implementation.
- Produces: explicit backend enum/state and narrowly scoped native backend lifecycle methods used by JNI and the renderer.

- [ ] **Step 1: Add the focused failing test/check**

Verify that the active native renderer target references the native LSFG translation unit and that the context exposes the exact native backend entry points required by that translation unit. The check must fail before integration because the current native source is not proven to be part of the active target/interface.

- [ ] **Step 2: Verify the relevant failure**

Run the repository's existing CMake/Gradle native compilation target identified from the build files. Expected: the pre-change active target does not compile/link the native LSFG backend as a usable renderer backend.

- [ ] **Step 3: Implement the minimum behavior**

Reconcile `VulkanRendererLsfg.cpp` declarations with `VulkanRendererContext` state ownership. Add the source to the actual Vulkan renderer target. Introduce an explicit native/legacy backend state without duplicating the presentation pipeline. Keep native resource ownership local to the renderer context and expose only lifecycle/configuration methods needed by higher layers. Do not alter protected Adreno synchronization code.

Edge cases: native shaders/resources unavailable, unsupported swapchain format, renderer not initialized, backend requested before renderer readiness, and repeated selection of the already-active backend must all remain safe and non-generating until initialization succeeds.

- [ ] **Step 4: Verify the focused pass**

Run the focused native compilation target. Expected: `VulkanRendererLsfg.cpp` compiles against the reconciled context interface and links into the active renderer target without introducing duplicate symbols.

- [ ] **Step 5: Run the affected integration check**

Run the existing GameNative native-renderer build/check. Expected: legacy renderer functionality remains buildable and the new native backend symbols are available to the renderer.

- [ ] **Step 6: Commit the passing deliverable**

Commit only the build/interface files for this task with a focused message such as `feat: wire native lsfg renderer boundary`.

### Task 2: Connect backend control through JNI and authoritative runtime state

**Files:**
- Modify: existing `VulkanRenderer.java` JNI wrapper (exact repository path to be confirmed before editing)
- Modify: existing Vulkan JNI implementation containing renderer control bindings (exact repository path to be confirmed before editing)
- Modify: `app/src/main/java/app/gamenative/utils/LsfgVkManager.kt`
- Modify: `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt`
- Modify: `app/src/main/java/app/gamenative/ui/component/QuickMenu.kt` only where callback/state contracts require it
- Test: `app/src/test/java/app/gamenative/utils/LsfgPacingCallSiteContractTest.kt`

**Interfaces:**
- Consumes: native backend lifecycle/configuration methods from Task 1.
- Produces: an authoritative backend request API and runtime acknowledgment carrying request serial, apply serial, generation, result, and actual backend.

- [ ] **Step 1: Add the focused failing test**

Extend the existing backend contract tests to require: immediate authoritative state change; a native request reaching the renderer control seam; runtime state remaining legacy/inactive until native initialization succeeds; successful native acknowledgment reporting `backend=native`; and failed native initialization reporting an explicit failure without falsely changing runtime provenance.

- [ ] **Step 2: Verify the relevant failure**

Run the focused Kotlin contract test. Expected: the new native-success/control-seam assertions fail because JNI/native runtime activation is not yet connected.

- [ ] **Step 3: Implement the minimum behavior**

Add the smallest JNI binding necessary to set/query the backend and shared LSFG configuration. Route Quick Menu changes through `LsfgVkManager`/authoritative state rather than direct UI-to-native mutation. Preserve request serials and apply serials. Publish runtime acknowledgment only after native initialization returns success. Keep legacy as the actual runtime backend when native initialization fails and emit `backend_apply_result` explaining the failure.

Repeated identical requests should be idempotent. A second request arriving during an outstanding transition must receive a newer request serial and must not be overwritten by a stale acknowledgment.

- [ ] **Step 4: Verify the focused pass**

Run `LsfgPacingCallSiteContractTest`. Expected: all backend state, callback, serial, and runtime truthfulness assertions pass.

- [ ] **Step 5: Run the affected integration check**

Run the Kotlin compile/test target covering Quick Menu, LSFG manager, and renderer JNI declarations. Expected: the UI and runtime layers compile with the native control seam.

- [ ] **Step 6: Commit the passing deliverable**

Commit the Kotlin/JNI control changes with a focused message such as `feat: expose native lsfg backend control`.

### Task 3: Implement lifecycle isolation, provenance, and native configuration

**Files:**
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.h`
- Modify: `app/src/main/cpp/winlator/VulkanRendererContext.cpp`
- Modify: `app/src/main/cpp/winlator/VulkanRendererLsfg.cpp`
- Modify: `app/src/main/java/app/gamenative/utils/LsfgVkManager.kt`
- Modify: `app/src/main/java/app/gamenative/utils/LsfgQuickMenuHelper.kt`
- Modify: existing native telemetry/provenance emission sites
- Test: `app/src/test/java/app/gamenative/utils/LsfgPacingCallSiteContractTest.kt` plus a new focused lifecycle/provenance contract test if the existing suite cannot express ordering

**Interfaces:**
- Consumes: backend control seam from Task 2.
- Produces: native/legacy provenance, backend generation isolation, shared runtime configuration, and ordered transition lifecycle.

- [ ] **Step 1: Add the focused failing test**

Require lifecycle ordering `request → quiesce → temporal reset → old-backend retirement → new-backend initialization → runtime acknowledgment → resume`, require old-generation frames to be rejected from presentation, and require native metrics/provenance to identify the native implementation rather than generic `lsfg-vk`.

- [ ] **Step 2: Verify the relevant failure**

Run the focused lifecycle/provenance test. Expected: it fails because current native integration does not yet own the complete transition/provenance contract.

- [ ] **Step 3: Implement the minimum behavior**

On backend change, quiesce host delivery ownership, advance the backend/temporal generation, invalidate old queued deliveries, retire old backend resources, initialize the requested backend, apply the existing shared frame-generation settings, and resume delivery only after successful runtime state publication. Native and legacy frame provenance must include implementation identity and backend generation. Keep the existing queue telemetry unchanged except for backend/generation fields needed to correlate transitions.

Do not transfer optical-flow history or adaptive Flow Scale state across generations. Do not move physical display confirmation into the control loop.

Edge cases: native initialization failure, rapid native→legacy→native requests, swapchain recreation during transition, renderer destruction during transition, and unavailable native resources must all leave no stale native delivery active.

- [ ] **Step 4: Verify the focused pass**

Run the lifecycle/provenance contract test. Expected: transition ordering, generation isolation, and implementation identity pass.

- [ ] **Step 5: Run the affected integration check**

Run the native renderer build plus the LSFG Kotlin contract tests. Expected: both layers agree on backend identity and generation semantics.

- [ ] **Step 6: Commit the passing deliverable**

Commit the lifecycle/provenance changes with a focused message such as `feat: isolate lsfg backend lifecycle and provenance`.

### Task 4: Verify performance and regression safety on protected paths

**Files:**
- Test/inspect only: existing Adreno synchronization sections in `VulkanRendererContext.cpp/.h`
- Test: existing queue telemetry contract `app/src/test/java/app/gamenative/utils/LsfgHostQueueTelemetryContractTest.kt` and native build/runtime validation artifacts

**Interfaces:**
- Consumes: completed native backend and lifecycle implementation.
- Produces: fresh evidence for legacy compatibility, native provenance, queue behavior, and absence of normal-path synchronization regressions.

- [ ] **Step 1: Add the focused verification checks**

Verify the protected Adreno markers remain unchanged and verify no newly added normal-path `vkDeviceWaitIdle`/`vkQueueWaitIdle` calls exist. Verify second-queue telemetry reports actual queue family/index and serial/timing fields.

- [ ] **Step 2: Verify the relevant failure/safety baseline**

Run the static contract checks before runtime testing. Expected: protected synchronization markers remain present and no new normal-path queue/device idle calls are introduced.

- [ ] **Step 3: Run native/legacy runtime validation**

Build an upgradeable debug APK from the branch and perform equivalent native-versus-legacy captures on the S25 FE/Xclipse 940. Run the legacy path on the S20+/Adreno reference. Collect one continuous log per app run.

Expected native evidence: explicit native implementation provenance, native generation counters increasing when generation is active, no stale-generation deliveries after switching, and no false `lsfg-vk` attribution.

Expected legacy evidence: existing legacy provenance remains intact, source handoff topology remains unchanged, and no new crashes/hitches attributable to backend integration appear.

- [ ] **Step 4: Verify the focused pass**

Run the full affected test/build suite. Expected: focused contract tests pass and the debug APK builds successfully.

- [ ] **Step 5: Run the affected integration check**

Compare runtime captures for source FPS, generated FPS, host backlog/drop counters, GPU completion timing, queue timing, and backend transition latency. Treat native performance as a separate measurement; do not declare success merely because the APK launches.

- [ ] **Step 6: Commit the passing verification updates**

Commit only test/telemetry changes required by the verification findings. Do not change synchronization or pacing merely to make a benchmark look better.

---

## Unresolved externally observable decisions

- If native initialization fails while legacy is already running, the design recommends retaining legacy as the runtime backend; no separate user-visible error surface beyond the existing Quick Menu telemetry/logging is required by the current specification.
- The native backend's exact JNI method names and the exact CMake target path are repository-dependent and must be taken from the current source/build files rather than invented; these are engineering integration details, not product behavior.
- No performance threshold beyond 'no material regression' is numerically defined yet; runtime comparison should therefore report measured deltas rather than silently inventing a pass/fail percentage.

## Upstream LSFG version audit (2026-10-05)

Before implementation, the upstream lineage was checked rather than assuming a "1.3.0 prerelease" exists. The public PancakeTAS lsfg-vk release history currently exposes 1.0.0 and 2.0.0-dev prereleases; there is no upstream lsfg-vk 1.3.0 release/tag. The LSFG model lineage is separately at LSFG 3.1. The Android lsfg-vk fork used by this project is based on lsfg-vk 1.0.0 and its current release branch already contains the Android AHardwareBuffer/Vortek/Turnip and shader-float16 updates verified against the fork's latest release branch.

The native GameNative compositor implementation is a different lineage: it derives from Eden's native Vulkan LSFG implementation, which Eden merged in PR #4263, rather than from an lsfg-vk "1.3.0" release. Therefore no nonexistent 1.3.0 code will be fabricated or transplanted. The native integration work will instead use the merged Eden implementation and the later proven compositor-side fixes as the source-of-truth lineage, while preserving the repository's existing protected Adreno synchronization behavior.

**Implication:** the pre-integration task is not an lsfg-vk version bump. It is an audit/reconciliation of the native Eden-derived engine against the current compositor-side implementation, especially generation planning, history priming, target management, provenance, and multi-present scheduling. Those changes must be integrated before native backend activation is declared functional.


## Progress update — 2026-10-05

Completed the first real native-renderer integration slice on this branch:
- reconciled the native helper with VulkanRendererContext state and Vulkan dispatch;
- added explicit native renderer arm/control JNI seams;
- connected authoritative backend selection to the host renderer;
- added swapchain transfer-usage gating required for compositor output;
- integrated native LSFG history processing and one generated-frame (2x) output into the actual render submission;
- acquired a second WSI image, signaled per-image present semaphores, and queued the generated image as a distinct present;
- added native source/generated counters and render-loop contract coverage;
- preserved the legacy path and avoided DeviceWaitIdle/QueueWaitIdle in renderFrame().

Current deliberate limitation: native generation is bring-up-gated to a single generated frame (2x). 3x/4x scheduling remains a separate follow-up so the first runtime proof does not claim unsupported multiplier behavior.


## CI follow-up
- 2026-10-05: Upgradeable Debug APK run `37269234025` failed during the native Vulkan renderer rebuild because `VulkanRendererContext.h` contained a duplicate `armFrameGeneration()` declaration. The duplicate was removed in commit `bfc4d9e6a9b6aea3461a2282b99bbad5573071a6`; subsequent native build fixes are being validated from the current branch head.


## CI verification — 2026-10-05
- Corrected the standalone Vulkan renderer CMake target so C++17 applies only to C++ sources; `vk_dispatch.c` now compiles as C.
- Added the pinned `lsfg-vk-android/thirdparty/dxbc` target and its include path to the renderer, restoring the `dxbc_modinfo.h` dependency and DXBC translator linkage.
- Added `lsfg_dll.c` to the renderer target so the existing shader-module loader/cache symbols (`lsfg_load_modules` / `lsfg_release_modules`) resolve at final link.
- Fresh Upgradeable Debug run `37275238166` at commit `541dd163bf49a298e31ab9cdc0e19167a4b7e247` passed native renderer rebuild, all selected Legacy LSFG/FrameRating tests, `assembleLegacyDebug`, update-identity/native-provenance verification, and artifact upload.
- Uploaded artifact: `GameNative-upgradeable-debug`; SHA-256 `34e1042cde7d0a90c82c85fbd64e0f3f913f7a9f168772c60af9cc7f33132058`.
