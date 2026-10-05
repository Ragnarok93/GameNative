#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-xserver-art-method.sh <apk>}"
max_registers="${XSERVER_MAX_REGISTERS:-192}"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?ANDROID_HOME/ANDROID_SDK_ROOT is required}}"

dexdump="$(find "$sdk_root" -type f -name dexdump -perm -111 -print 2>/dev/null | sort -V | tail -n 1)"
if [[ -z "$dexdump" ]]; then
  echo "ERROR: Android SDK dexdump was not found" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'classes*.dex' -d "$work"

found=0
for dex in "$work"/classes*.dex; do
  dump="$work/$(basename "$dex").dump"
  "$dexdump" -d "$dex" > "$dump"

  regs="$(awk '
    /Class descriptor[[:space:]]*:/ {
      in_target = ($0 ~ /Lapp\/gamenative\/ui\/screen\/xserver\/XServerScreenKt;/)
      in_method = 0
    }
    in_target && /name[[:space:]]*:[[:space:]]*'\''XServerScreen'\''/ {
      in_method = 1
    }
    in_target && in_method && /registers_size[[:space:]]*:/ {
      print $NF
      exit
    }
  ' "$dump" || true)"

  if [[ -n "$regs" ]]; then
    found=1
    regs_dec="$((16#${regs#0x}))"
    echo "XServerScreenKt.XServerScreen registers_size=$regs_dec (raw $regs)"
    echo "Verifier safety ceiling=$max_registers"
    if (( regs_dec > max_registers )); then
      echo "ERROR: XServerScreen exceeds verifier safety ceiling" >&2
      exit 1
    fi
    if (( regs_dec >= 224 )); then
      echo "ERROR: XServerScreen is too close to the DEX register ceiling" >&2
      exit 1
    fi
    break
  fi
done

if (( found == 0 )); then
  echo "ERROR: XServerScreenKt.XServerScreen was not found in generated DEX" >&2
  exit 1
fi
