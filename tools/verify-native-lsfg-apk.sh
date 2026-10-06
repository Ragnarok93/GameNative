#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-native-lsfg-apk.sh <apk>}"
if [[ ! -s "$apk" ]]; then
  echo "ERROR: APK is missing or empty: $apk" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

extract_required() {
  local entry="$1"
  local output="$2"
  if ! unzip -p "$apk" "$entry" > "$output" || [[ ! -s "$output" ]]; then
    echo "ERROR: APK does not contain $entry: $apk" >&2
    exit 1
  fi
}

renderer="$tmpdir/libvulkan_renderer.so"
extras="$tmpdir/libextras.so"
extract_required 'lib/arm64-v8a/libvulkan_renderer.so' "$renderer"
extract_required 'lib/arm64-v8a/libextras.so' "$extras"

bash tools/verify-native-lsfg-jni.sh "$renderer"
bash tools/verify-gpuimage-jni.sh "$extras"
echo "Verified packaged Native LSFG and GPUImage JNI bridges in $apk"
