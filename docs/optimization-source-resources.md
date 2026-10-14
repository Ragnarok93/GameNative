# Optimization source references

Verified via GitHub API on 2026-10-14. Research references, not installed/tested
components or measured performance gains. Keep the existing matched Turnip
repeat experiment next; do not substitute a driver during that comparison.

## Turnip

- Mesa is the implementation authority; use exact source SHAs, not packaging
  labels as source identities. Existing driver is upstream Mesa76b9372.
- StevenMXZ: https://github.com/StevenMXZ/Adreno-Tools-Drivers
  Default A8xx is not the S20+/Adreno650 target. A6xx/A7xx branch pinned at
  a6aa12c71bb49789d0a53c6d96ccfdd3d34659a5.
- Release v37 includes Turnip_v26.3.0-R6.zip described for A6xx/A7xx;
  publisher SHA256 5fa2305eb9eff64d9347a670e6ca5ef7c518f60e9e782a4db7ee5588c9852d81.
  https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v37
  This is a candidate reference only: no local download/hash/runtime validation.
- gamesir release offers Rutabaga-v3, described as extracted from Gamehub and
  compatible with A6xx/A7xx/A8xx. Its FF7R benefit and source provenance remain
  unverified. https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/gamesir
- A6xx branch contains force_sysmem_no_autotuner.patch: an unconditional
  return true in use_sysmem_rendering. This is a hypothesis resource, NOT a
  demonstrated optimization. Do not assume the clean build script applies this
  stored patch. Existing GPU trace finding does not establish forced SYSMEM wins.
- A8xx-only V37 variants and Qualcomm proprietary releases must not be confused
  with A650 Turnip candidates. Vulkan1.4 labels alone neither prove nor disprove
  Vulkan1.3 application compatibility; check hardware/extensions/loader/runtime.

## DXVK / Sarek

- Upstream https://github.com/doitsujin/dxvk
- https://github.com/pythonlover02/DXVK-Sarek
- Mobile fork https://github.com/zeyadadev/DXVK-Sarek (dxvk-mali-unified)

Pinned reference heads:
- https://github.com/pythonlover02/dxvk-sarek @ 0c0e8f6f62c7cb37dbf5d38413517efa00c08fef
- https://github.com/zeyadadev/DXVK-Sarek @ b6653094a2b8f3b824ffcf8f50aaa3b3da41263b
- https://github.com/doitsujin/dxvk @ a78a467cadb22bee928904290cf8e7921b121851

Recent D3D11 leads include meta-copy image-view support (Sarek), removal of
front-end draw-buffer validation (mobile fork), and submission synchronization
(upstream). Commit messages identify leads only; diffs, dependencies, equivalence
to our 2.7.1/3.1.1 backports, compatibility and FF7R relevance require review.
Do not wholesale replace our known-good DXVK. Prioritize measured hot paths,
selectively port one justified change, preserve Vulkan1.3, attest source/package/
mapped DLL hashes, test graphics correctness and repeat performance/rollback.
Mali workarounds and relaxed UAV/barrier hacks are not generic Adreno650 wins.

## Selection gate

Use upstream and relevant GitHub forks before developing an optimization.
Resolve every mutable branch/tag to exact source SHA; preserve build flags and
patch provenance. Record publisher digest separately from locally verified
package hash and actual mapped binary hash. Third-party binaries with missing
source attribution remain exploratory and cannot establish a reproducible source
optimization. No component is promoted without matched repeated gameplay evidence.
