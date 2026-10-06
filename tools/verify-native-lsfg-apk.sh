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

pulse_component='app/src/main/java/com/winlator/xenvironment/components/PulseAudioComponent.java'
pulse_asset="$(
  grep -oE 'pulseaudio-gamenative-[0-9]+\.tzst' "$pulse_component" | head -n 1
)"
if [[ -z "$pulse_asset" ]]; then
  echo "ERROR: unable to resolve configured PulseAudio asset from $pulse_component" >&2
  exit 1
fi
if ! unzip -Z1 "$apk" | grep -Fx "assets/$pulse_asset" >/dev/null; then
  echo "ERROR: APK is missing configured PulseAudio asset: assets/$pulse_asset" >&2
  exit 1
fi

echo "Verified packaged Native LSFG, GPUImage JNI, and PulseAudio runtime payloads in $apk"
