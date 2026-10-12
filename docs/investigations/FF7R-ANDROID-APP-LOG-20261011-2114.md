# FFVII Remake S20+ — Android host log, 2026-10-11 21:14 CDT

**Source:** user-exported `app_logs_2026-10-11_21-14-29.txt`, 1,085 application/Android lines. This is an **Android host** log, not a DXVK per-module diagnostic and not the older October 6 crash report. Raw private logs have not been committed.

The log records an Android SurfaceView/XServer swapchain re-creation, followed by UI navigation and eventual Steam service disconnect (apparently user-initiated). It contains LSFG host display **reset/drain** messages associated with destroying and re-creating the swapchain, but **does not show a new LSFG generated-frame failure or establish that LSFG caused FFVII Remake's Vulkan allocator OOM**.

Notable Android gralloc capability-probe errors around 21:13:56.811:

```text
qdgralloc: GetGpuPixelFormat: No map for format: 0x38
Gralloc4: isSupported(1, 1, 56, 1, ...) failed with 1
GraphicBufferAllocator: Failed to allocate (4 x 4) layerCount 1 format 56 usage b00: 1
AHardwareBuffer: GraphicBuffer(w=4, h=4, lc=1) failed (Unknown error -1)
```

This **4×4 unsupported format probe** is a separate Android gralloc/WSI symptom: do not equate it to the game's real failed 4–256-MiB KGSL buffer allocation. Whether this probe is expected and safely falls back requires source inspection or per-feature tracing. No matching Android Java fatal exception occurs in this export; the bundled October 6 `pluvia_crash` remains a historical JNI `UnsatisfiedLinkError` from a different build/session.

**Own-category scope:** Investigate gralloc format 0x38 probing, potential retry-on-unsupported-format noise and SurfaceView lifecycle only in GameNative's renderer / Vulkan wrapper integration. No reason to modify Turnip KGSL allocation code or DXVK's pool sizing on the basis of these gralloc probe lines.

**Separate, stronger native evidence:** Another device run, documented at [Turnip driver native KGSL ENOMEM and memory-pressure report](https://github.com/Ragnarok93/Banners-Turnip/blob/research/ff7r-a650-kgsl-oom-20261012/docs/investigations/FF7R-A650-SYSTEM-MEMORY-PRESSURE-DEVICE-RESULT-20261012.md), established `KGSL_GPUMEM_ALLOC_ID` returning `ENOMEM` and Android MemAvailable ~391–490 MiB during FF7R OOM. DXVK's allocation-level observations belong in [dxvk-SD865](https://github.com/Ragnarok93/dxvk-SD865).
