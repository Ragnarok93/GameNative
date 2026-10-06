#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-native-lsfg-apk.sh <apk>}"
if [[ ! -s "$apk" ]]; then
  echo "ERROR: APK is missing or empty: $apk" >&2
  exit 1
fi

entry='lib/arm64-v8a/libvulkan_renderer.so'
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

if ! unzip -p "$apk" "$entry" > "$tmp" || [[ ! -s "$tmp" ]]; then
  echo "ERROR: APK does not contain $entry: $apk" >&2
  exit 1
fi

bash tools/verify-native-lsfg-jni.sh "$tmp"
echo "Verified packaged Native LSFG JNI bridge in $apk"
