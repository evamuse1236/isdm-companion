#!/usr/bin/env bash
set -euo pipefail

serial=""
old_apk=""
new_apk=""
clean_install=false

usage() {
  cat <<'EOF'
Usage: test-beta-update.sh --serial emulator-N --old OLD.apk --new NEW.apk [--clean-install]

Installs the old APK, records the package data inode, performs adb install -r
with the new APK, and requires the inode to remain unchanged. --clean-install
then uninstalls and installs the new APK; it is allowed only on emulator-*.
EOF
}

while (($#)); do
  case "$1" in
    --serial)
      serial="${2:?missing serial}"
      shift 2
      ;;
    --old)
      old_apk="${2:?missing old APK}"
      shift 2
      ;;
    --new)
      new_apk="${2:?missing new APK}"
      shift 2
      ;;
    --clean-install)
      clean_install=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ "$serial" == emulator-* ]] || {
  printf 'This script is restricted to emulator-* serials.\n' >&2
  exit 2
}
[[ -f "$old_apk" && -f "$new_apk" ]] || {
  usage >&2
  exit 2
}

adb_cmd=(adb -s "$serial")
"${adb_cmd[@]}" get-state >/dev/null
"${adb_cmd[@]}" install "$old_apk"

before="$("${adb_cmd[@]}" shell dumpsys package org.isdm.companion | tr -d '\r')"
before_inode="$(sed -n 's/.*ceDataInode=\([0-9]*\).*/\1/p' <<<"$before" | head -n 1)"
[[ -n "$before_inode" ]] || {
  printf 'Could not read the pre-update ceDataInode.\n' >&2
  exit 1
}

"${adb_cmd[@]}" install -r "$new_apk"
after="$("${adb_cmd[@]}" shell dumpsys package org.isdm.companion | tr -d '\r')"
after_inode="$(sed -n 's/.*ceDataInode=\([0-9]*\).*/\1/p' <<<"$after" | head -n 1)"

printf '%s\n' "$after" | grep -E 'versionCode=|versionName=|ceDataInode='
[[ "$before_inode" == "$after_inode" ]] || {
  printf 'Data inode changed across update: %s -> %s\n' "$before_inode" "$after_inode" >&2
  exit 1
}

printf 'Update preserved ceDataInode=%s\n' "$after_inode"

if "$clean_install"; then
  "${adb_cmd[@]}" uninstall org.isdm.companion
  "${adb_cmd[@]}" install "$new_apk"
  printf 'Clean install completed on emulator %s.\n' "$serial"
fi
