# Apex native source provenance

The native Apex frame-generation algorithm files in this directory are adapted from:

- Repository: https://github.com/GunaCharanTeja/WinlatorMali
- Commit: d3339806904fc5da0d8db64f4c8e5d77648975d9
- Upstream license: MIT

Imported algorithm files: `apex_engine.h`, `apex_pipeline.cpp`,
`apex_pacing.cpp`, and `apex_shaders.h`.

GameNative-specific GPU profile selection, JNI/package boundaries, build
integration, and tests are maintained here. Adreno 6xx+ is the primary
performance target; Xclipse uses a separately capability-gated compatibility
profile. The complete upstream MIT license is preserved in `LICENSE.upstream`.
