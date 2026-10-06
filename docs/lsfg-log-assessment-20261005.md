# S20+ LSFG capture assessment — 2026-10-05 22:00

Device: Samsung SM-G986U1, Snapdragon 865/Adreno 650, Android 13, 120 Hz.
Inputs: app_logs_2026-10-05_22-00-52.txt and gamenative-lsfg-2026-10-05_22-00-43.txt.
GameNative base: 95b3f3b30cca8105ea63ff57283d191673a6785c.
LSFG base: the exact pinned commit 54545ff93a46ecf846b3ffb56c396d5874ed7de2.

## Findings and disposition

| Finding | Evidence | Disposition |
| --- | --- | --- |
| Host discards valid partial timing results | Host poll accepts only VK_SUCCESS; the layer poll already accepts VK_INCOMPLETE | Fixed; process returned records, leave remaining records for a subsequent poll |
| Timing-query failures lack error codes | End-of-run host counter is 148, without count/data result diagnostics | Added bounded failure logs with stage/result/count; partial data is logged at debug level |
| Malformed metric | source_deadline_error_avg_ms= source_deadline_error_avg_ms=2.52322 | Fixed in the pinned LSFG source; numeric output regression test |
| Generated delivery remains uncertain | Final host unique_physical_fps=41.957, source_delivery_efficiency=0.9832, generated_delivery_efficiency=0.5509; 3594 expired confirmations before teardown | Confirmation coverage is defective; do not interpret unconfirmed frames as proven drops or promise a physical FPS gain |
| Fixed-mode source throughput degrades | Revision 11 structured runtime samples have median source_fps=17.798 and output_fps=53.395, wait up to 60.891 ms, flow scale commonly 0.25 | Device retest needed; output_fps is runtime accounting, not unique physical display FPS |
| Persistent temporal backlog | End-of-run provenance cadence p95=79.440 ms, submitted cadence p95=45.818 ms; phase_rescheduled_total=2401 and temporal_backlog_total=1818 | Preserve synchronization and fixed multiplier semantics; validate after confirmation repair before changing admission/pacing |
| Driver margin wraps | present_margin_raw around UINT64_MAX; invalid_present_margin_total=1620 | Existing guard sets the usable margin to zero and flags it invalid; do not reinterpret as a large real delay |
| Missing exporter artifacts | scanned_nodes=0; vsync.txt, present-vsync.txt, wrapper_diag_* unavailable | Export includes structured telemetry and UID logcat; absence alone does not prove a broken runtime feature |
| Teardown feedback errors | errno=111 accompanies swapchain recreation/destruction near 22:00:34.885 | Receiver may already be gone; no evidence that this explains steady-state throughput |
| Crash/device loss | No FATAL EXCEPTION, Fatal signal, or VK_ERROR_DEVICE_LOST signature in the inspected capture | No crash established |

Revision 10's 40 structured runtime samples have median source_fps=29.944 and output_fps=59.850.
Revision 11's 109 samples cover a different fixed-mode workload interval and pauses. This is an observation, not a controlled A/B benchmark or proof of a code regression.
The export contains repeated sections; statistics use the structured section before APP LOGCAT.
Repeated history_invalidation_reason values are state telemetry, not a count of reset events.
Generation-first deadline_admission_valid=0, fixed-mode adaptive fields=0, and presentation_cap=0 are not independently evidence of broken features.
Host display feedback remains telemetry-only; this patch does not feed unknown delivery into a density controller.

## Verification

Run: python3 tools/host_display_timing_test.py

The test compiles the actual production confirmation-poll method with a mock Vulkan driver.
The original host fails the partial-result case; the patched host passes success, partial-data,
partial-count, empty-history, and count/data error cases.
The test also compiles the actual source-deadline metric expression and verifies one numeric key.
It runs before the native renderer rebuild in the existing composite build action.
Local mock compilation used g++ with -std=c++17 -Wall -Wextra -Werror.
A full Android/NDK build and on-device presentation test are separate verification gates.

## Device retest

Repeat adaptive 60 FPS and fixed 3x with the same scene, resolution, driver, thermal state, and queue setting.
Capture runtime source/output rates alongside host unique physical source/generated rates.
Look for event=display-timing-query-partial and event=display-timing-query-failed
(stage, result, cumulative failures). Check confirmation timeout growth, phase backlog,
completion waits, and source cadence before concluding whether generated images are actually lost.
Capture an LSFG-off baseline and distinguish gameplay from settings/pause/resume intervals.
Do not change semaphore retirement, source ownership, or fixed multiplier density based on this capture alone.

Vulkan reference: https://docs.vulkan.org/refpages/latest/refpages/source/vkGetPastPresentationTimingGOOGLE.html
