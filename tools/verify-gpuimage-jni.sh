#!/usr/bin/env bash
set -euo pipefail

so="${1:?usage: verify-gpuimage-jni.sh <libextras.so>}"
if [[ ! -s "$so" ]]; then
  echo "ERROR: GPUImage JNI library is missing or empty: $so" >&2
  exit 1
fi

readelf_bin="${LLVM_READELF:-}"
if [[ -z "$readelf_bin" ]]; then
  if command -v llvm-readelf >/dev/null 2>&1; then
    readelf_bin="$(command -v llvm-readelf)"
  elif command -v readelf >/dev/null 2>&1; then
    readelf_bin="$(command -v readelf)"
  else
    echo "ERROR: no readelf implementation is available" >&2
    exit 1
  fi
fi

header="$("$readelf_bin" -h "$so")"
if ! grep -Eq 'Machine:[[:space:]]+AArch64' <<<"$header"; then
  echo "ERROR: GPUImage JNI library is not arm64/AArch64: $so" >&2
  printf '%s\n' "$header" >&2
  exit 1
fi

symbols="$("$readelf_bin" --wide -Ws "$so")"
symbol_names="$(awk '{print $8}' <<<"$symbols")"
required_symbols=(
  Java_com_winlator_renderer_GPUImage_hardwareBufferFromSocket
  Java_com_winlator_renderer_GPUImage_isHardwareBufferConfigurationSupported
  Java_com_winlator_renderer_GPUImage_createHardwareBuffer
  Java_com_winlator_renderer_GPUImage_destroyHardwareBuffer
  Java_com_winlator_renderer_GPUImage_lockHardwareBuffer
  Java_com_winlator_renderer_GPUImage_unlockHardwareBuffer
  Java_com_winlator_renderer_GPUImage_createImageKHR
  Java_com_winlator_renderer_GPUImage_destroyImageKHR
)

for symbol in "${required_symbols[@]}"; do
  if ! grep -Fx "$symbol" <<<"$symbol_names" >/dev/null; then
    echo "ERROR: Packaged libextras.so is missing required GPUImage JNI symbol: $symbol" >&2
    echo "GPUImage JNI symbols found:" >&2
    grep -F 'Java_com_winlator_renderer_GPUImage_' <<<"$symbol_names" >&2 || true
    exit 1
  fi
done

echo "Verified GPUImage JNI ABI and ${#required_symbols[@]} required exports in $so"
