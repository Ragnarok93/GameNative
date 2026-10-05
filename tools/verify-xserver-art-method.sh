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

  if grep -q "Lapp/gamenative/ui/screen/xserver/XServerScreenKt;" "$dump"; then
    # dexdump formatting varies between Android build-tools versions. Do not
    # depend on a single Class descriptor/name ordering; inspect the class block.
    class_start="$(grep -n -m1 "Lapp/gamenative/ui/screen/xserver/XServerScreenKt;" "$dump" | cut -d: -f1)"
    class_end="$(awk -v start="$class_start" 'NR > start && /^Class descriptor[[:space:]]*:/ { print NR; exit }' "$dump")"
    if [[ -z "$class_end" ]]; then
      class_end="$(wc -l < "$dump")"
    fi

    class_dump="$work/xserver-class.dump"
    sed -n "${class_start},${class_end}p" "$dump" > "$class_dump"

    method_start="$(grep -n -m1 -E "name[[:space:]]*:[[:space:]]*'XServerScreen'" "$class_dump" | cut -d: -f1 || true)"
    if [[ -n "$method_start" ]]; then
      method_abs="$((class_start + method_start - 1))"
      method_end="$(awk -v start="$method_abs" '
        NR > start && /^[[:space:]]*(name|type|access_flags|registers_size|ins_size|outs_size|insns_size)[[:space:]]*:/ && $0 ~ /name[[:space:]]*:/ { print NR; exit }
      ' "$dump" || true)"
      if [[ -z "$method_end" ]]; then
        method_end="$((method_abs + 80))"
      fi

      method_block="$work/xserver-method.dump"
      sed -n "${method_abs},${method_end}p" "$dump" > "$method_block"
      regs="$(grep -m1 -E "registers_size[[:space:]]*:" "$method_block" | sed -E 's/.*registers_size[[:space:]]*:[[:space:]]*(0x[0-9a-fA-F]+|[0-9]+).*/\1/' || true)"

      if [[ -n "$regs" ]]; then
        found=1
        if [[ "$regs" == 0x* ]]; then
          regs_dec="$((16#${regs#0x}))"
        else
          regs_dec="$regs"
        fi
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
    fi
  fi
done

if (( found == 0 )); then
  echo "ERROR: XServerScreenKt.XServerScreen was not found in generated DEX; class/method dump did not match expected dexdump layout" >&2
  exit 1
fi
