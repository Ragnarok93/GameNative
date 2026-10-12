# FFVII Remake / Snapdragon 865 — GameNative memory-configuration propagation audit

Status: **source-verified integration finding; not a demonstrated runtime fix**. Date: 2026-10-12 UTC. Scope restricted to the GameNative UI, environment construction and Wine/DXVK configuration boundaries. Root-cause analysis inside DXVK belongs in `Ragnarok93/dxvk-SD865`; driver allocation analysis belongs in the Turnip driver repository, whose exact user fork URL is not yet established.

## Source-derived mapping of the three "VRAM" controls

| GameNative control | Implementation in this repository | What it actually changes |
|---|---|---|
| Graphics → Max Device Memory (`0/512/1024/2048/4096`) | `GraphicsTab.kt`, stored as `graphicsDriverConfig.maxDeviceMemory`; `XServerScreen.kt:6108-6110` writes `WRAPPER_VMEM_MAX_SIZE` if value > 0 | The selected Vulkan wrapper's **reported first `VkMemoryHeap.size`**, according to `leegao/mesa-wrapper-CI` `gn_wrapper2/src/vulkan/wrapper/wrapper_physical_device.c` at ~930–958. It does not change the underlying KGSL allocation capacity. Value `0` emits **no override**. |
| Wine → Video Memory Size (screenshot: 2048 MB) | `WineTab.kt:55-64` assigns `config.videoMemorySize` | Wine video-memory setting. It does not impose a Vulkan `vkAllocateMemory` quota. Do not infer that the user's T30 native driver must allocate less than 2048 MiB. |
| DXVK's `dxgi.maxDeviceMemory` (user manually supplied `DXVK_CONFIG` with 3072 requested) | `DXVKHelper.java:18-64` generates `DXVK_CONFIG` and DXVK config path; `XServerScreen.kt:4143` merges user-configured `container.envVars` **after** the generated variables | DXVK `dxgi.maxDeviceMemory` is a **DXGI adapter description cap** consumed by some games' streaming logic, not Vulkan allocation enforcement. A manually supplied `DXVK_CONFIG` value wins in this environment merge path, but runtime evidence of the effective parsed 3072 MB value is still needed. |

**Observed UI context from user screenshots:** Adrenotools Turnip enabled, resource type auto, BCn emulation auto/compute, LSFG off, FEXCore 2610 FF7R-Balanced, Wine video memory 2048 MB, DXVK diagnostics on, `DXVK_SD865_POOL_EXPERIMENT` used for A/B experiments. User confirmed screenshots showing pool experiment `1` were taken *before* changing it to `0` for the control run. The screenshot timing does **not** suggest a GameNative environment propagation defect.

## Specific GameNative bug candidate: auto-generated DXVK_CONFIG is quoted

The code in `app/src/main/java/com/winlator/core/DXVKHelper.java` builds:

```java
String content = "\"";
content += "dxgi.maxDeviceMemory = " + maxDeviceMemory + "\n";
content += "dxgi.maxSharedMemory = " + maxDeviceMemory + "\n";
content = content + '"';
envVars.put("DXVK_CONFIG", content);
```

**Source finding:** The literal first character of that value is a double quote. DXVK-SD865 `src/util/config/config.cpp` `parseUserConfigLine` requires a first character that is a valid option key character `[a-zA-Z0-9._]` and then an equals sign. A leading `"` causes it to abandon the line without recording a config option. DXVK `Config::getUserConfig` splits `DXVK_CONFIG` on `;` before parsing, **not newline**. Thus the default generated double-quoted, multi-line environment value cannot be assumed to parse as the intended DXGI options. This is a **concrete incompatible serialization path** between GameNative and the DXVK fork. The original huge earlier logs also showed `Found config env: ""` in a separate run; that is consistent with an empty generated value, but without the exact per-run env capture it is not proof of the user's later manually specified 3072 config being ignored.

Crucially, later `XServerScreen.kt:4143` `envVars.putAll(container.envVars)` overwrites generated keys with user-provided keys through `EnvVars.put` backed by `LinkedHashMap.put`. Thus **if** the screenshot's manually entered `DXVK_CONFIG` contains `dxgi.maxDeviceMemory = 3072` in the value field and was saved before launch, it should replace the auto-generated malformed value in this setup path. Do not claim the manual field was ignored without a runtime `DXVK_LOG_LEVEL=info` effective-config capture.

### Recommended GameNative correction (NOT implemented in this research commit)

Generate **unquoted semicolon-delimited** DXVK_CONFIG: `dxgi.maxDeviceMemory = 3072; dxgi.maxSharedMemory = 3072`. Prefer `StringBuilder` + `String.join("; ", ...)`; avoid leading and trailing shell-style quotes inside the actual environment variable value. Alternatively rely on correctly formatted newline-delimited `dxvk.conf` file contents, not newline-separated `DXVK_CONFIG`. When merging with manual user options, preserve explicit user overrides and log a redacted, selective one-time effective configuration (or rely on DXVK INFO `Effective configuration:`). Add a pure test asserting `Config::getUserConfig` grammar: no leading quote, separate options by semicolons, manual overrides take precedence. **No physical Vulkan-memory cap should be advertised in UI for the wrapper heap-size setting.**

## Vulkan wrapper reported heap, not allocation enforcement

Pinned comparable wrapper source: [`leegao/mesa-wrapper-CI` `wrapper_physical_device.c`](https://github.com/leegao/mesa-wrapper-CI/blob/gn_wrapper2/src/vulkan/wrapper/wrapper_physical_device.c) implements both `GetPhysicalDeviceMemoryProperties` and `GetPhysicalDeviceMemoryProperties2` via native forwarding followed by overriding `memoryHeaps[0].size` when `WRAPPER_VMEM_MAX_SIZE>0`. It does **not** shown here adjust the returned `VK_EXT_memory_budget` pNext budget. The user confirmed intentionally selecting **2048 MB** in Graphics for a run that subsequently reported 2048 MiB heap. Restoring Graphics Max Device Memory to **0** resulted in the original **7951 MiB** reported heap, while real driver `VK_ERROR_OUT_OF_DEVICE_MEMORY` continued close to 4 GiB DXVK tracked allocations. This is a reporting override, not a driver repair.

A different code path: `XServerScreen.kt:5894` sets `TU_OVERRIDE_HEAP_SIZE=4096` in the **glibc Turnip** setup. In the photographed **Adrenotools Turnip / wrapper** path `XServerScreen.kt:5987+`, that assignment does not appear. Avoid attributing the user's observed ~4GiB allocations to that glibc-only env var without direct run evidence.

## A/B and follow-up

Run FFVII Remake on the same T30, PurpleVK, DXVK, FEX and 540p/low-texture save with Graphics Max Device Memory **0**; set a manual `DXVK_CONFIG=dxgi.maxDeviceMemory = 3072` in environment UI and temporarily `DXVK_LOG_LEVEL=info`. Verify *exact* `Found config env:` and `Effective configuration:` lines, plus Vulkan reported heap and DXVK allocated/used at first OOM. Then try same game/save with *no* DXGI reported VRAM cap to detect genuine engine streaming response. **Neither is a Vulkan allocation hard limit.**

Vulkan-driver `KGSL_GPUMEM_ALLOC_ID` errno-level diagnosis belongs in the Turnip driver fork; DXVK recorded `first_vkresult=-2; retry_vkresult=-2` but it cannot tell whether KGSL returned ENOMEM, IOVA exhaustion, or another error that the driver translated.

## Cross-repository provenance

- DXVK-specific baseline and allocator A/B: [`dxvk-SD865` report](https://github.com/Ragnarok93/dxvk-SD865/blob/feature/ff7r-sd865-architecture-20261011/docs/FF7R-SD865-POOL-EXPERIMENT-DEVICE-RESULT-20261012.md).
- FEX-specific observation is limited to selected FEXCore 2610 Phase0, FF7R-Balanced preset; no causally relevant FEX exception was observed.
- Turnip source underlying Mr Purple T30 is **not identified** from its public release metadata; comparable Mesa KGSL findings must not be mislabeled a specific T30 source audit.

## Turnip repository routing confirmed (2026-10-12)

User identified the designated Turnip fork as [`Ragnarok93/Banners-Turnip`](https://github.com/Ragnarok93/Banners-Turnip). The driver-specific investigation is now committed at [`docs/investigations/FF7R-SD865-KGSL-VULKAN-OOM-20261012.md`](https://github.com/Ragnarok93/Banners-Turnip/blob/research/ff7r-a650-kgsl-oom-20261012/docs/investigations/FF7R-SD865-KGSL-VULKAN-OOM-20261012.md), commit `a1f89412d024`.

**Provenance qualification:** Banners-Turnip's Standard A6xx driver is built from pinned/upstream Mesa, whereas the user's currently tested driver is Mr Purple T30 / PurpleVK; source/binary equivalence has **not** been confirmed. Turnip build, KGSL ioctl failures, driver errno and GPU virtual-memory questions belong to the linked driver report, not this subsystem's patch queue.
