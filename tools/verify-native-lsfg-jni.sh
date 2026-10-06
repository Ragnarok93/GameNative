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

# --wide is required: GNU/LLVM readelf otherwise truncates long JNI symbol
# names in the table, producing a false "missing symbol" failure.
symbols="$("$readelf_bin" --wide -Ws "$so")"
required_symbols=(
  Java_com_winlator_renderer_lsfg_LosslessScaling_nativeBuildCache
  Java_com_winlator_renderer_lsfg_LosslessScaling_nativeCacheMatchesSource
  Java_com_winlator_renderer_lsfg_LosslessScaling_nativeSupportsFp16
)

symbol_names="$(awk '{print $8}' <<<"$symbols")"
for symbol in "${required_symbols[@]}"; do
  # Avoid grep -q in an awk|grep pipeline under pipefail: grep exits as soon
  # as it finds a match, which SIGPIPEs awk and turns a real match into a
  # false-negative pipeline status.
  if ! grep -Fx "$symbol" <<<"$symbol_names" >/dev/null; then
    echo "ERROR: Packaged Vulkan renderer is missing required Native LSFG JNI symbol: $symbol" >&2
    echo "LosslessScaling JNI symbols found:" >&2
    grep -F 'Java_com_winlator_renderer_lsfg_LosslessScaling_' <<<"$symbol_names" >&2 || true
    exit 1
  fi
done

echo "Verified Native LSFG JNI ABI and ${#required_symbols[@]} required exports in $so"
