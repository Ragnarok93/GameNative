#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: verify-xserver-art-method.sh <apk>}"
max_registers="${XSERVER_MAX_REGISTERS:-192}"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?ANDROID_HOME/ANDROID_SDK_ROOT is required}}"
target_class="Lapp/gamenative/ui/screen/xserver/XServerScreenKt;"

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
  [[ -f "$dex" ]] || continue
  dump="$work/$(basename "$dex").dump"
  "$dexdump" -d "$dex" > "$dump"

  if ! grep -qF "$target_class" "$dump"; then
    continue
  fi

  found=1
  regs_line="$(awk -v target="$target_class" '
    /Class descriptor/ {
      in_target = index($0, target) != 0
      in_method = 0
    }
    in_target &&
      $0 ~ /^[[:space:]]*name[[:space:]]*:/ &&
      $0 ~ /'XServerScreen'[[:space:]]*$/ {
      in_method = 1
      next
    }
    in_target && in_method && $0 ~ /registers_size[[:space:]]*:/ {
      print $0
      exit
    }
  ' "$dump")"

  if [[ -z "$regs_line" ]]; then
    echo "ERROR: found $target_class in $(basename "$dex"), but could not locate XServerScreen registers_size" >&2
    echo "DEX method names present in target class:" >&2
    awk -v target="$target_class" '
      /Class descriptor/ { in_target = index($0, target) != 0 }
      in_target && $0 ~ /^[[:space:]]*name[[:space:]]*:/ { print "  " $0 }
    ' "$dump" | head -n 80 >&2
    exit 1
  fi

  regs="$(sed -E 's/.*registers_size[[:space:]]*:[[:space:]]*(0x[0-9a-fA-F]+|[0-9]+).*/\1/' <<<"$regs_line")"
  if [[ -z "$regs" || "$regs" == "$regs_line" ]]; then
    echo "ERROR: unable to parse registers_size from: $regs_line" >&2
    exit 1
  fi

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
done

if (( found == 0 )); then
  echo "ERROR: $target_class was not found in generated DEX" >&2
  exit 1
fi