# Adaptive Frame Generation implementation checklist

- [x] Isolated branches created in both repositories.
- [x] Native source-only warmup and fractional scheduler.
- [x] Incremental generation-level probing and throughput-based fallback.
- [x] Probe cooldown and stall/config reset behavior.
- [x] Native controller diagnostics.
- [x] Independent GameNative Adaptive output-target storage.
- [x] Display-refresh automatic output-target resolution.
- [x] PowerManager source-cap callback isolated from LSFG `fps_limit`.
- [x] Source/output separation regression tests.
- [ ] Expose output target in the LSFG Quick Menu.
- [ ] Stop the LSFG Adaptive toggle from implicitly enabling the normal FPS limiter.
- [ ] Pin GameNative's LSFG gitlink to the current Adaptive native revision.
- [ ] Run Legacy debug unit/build verification.
- [ ] Run on-device controller/log validation.
