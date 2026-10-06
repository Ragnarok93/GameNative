#!/usr/bin/env bash
set -euo pipefail

so="${1:?usage: verify-native-lsfg-jni.sh <libvulkan_renderer.so>}"
if [[ ! -s "$so" ]]; then
  echo "ERROR: Vulkan renderer is missing or empty: $so" >&2
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
  echo "ERROR: Native LSFG JNI library is not arm64/AArch64: $so" >&2
  printf '%s\n' "$header" >&2
  exit 1
fi

symbols="$("$readelf_bin" -Ws "$so")"
required_symbols=(
  Java_com_winlator_renderer_lsfg_LosslessScaling_nativeBuildCache
  Java_com_winlator_renderer_lsfg_LosslessScaling_nativeCacheMatchesSource
  Java_com_winlator_renderer_lsfg_LosslessScaling_nativeSupportsFp16
)

for symbol in "${required_symbols[@]}"; do
  if ! awk '{print $8}' <<<"$symbols" | grep -Fxq "$symbol"; then
    echo "ERROR: Packaged Vulkan renderer is missing required Native LSFG JNI symbol: $symbol" >&2
    exit 1
  fi
done

echo "Verified Native LSFG JNI ABI and ${#required_symbols[@]} required exports in $so"
