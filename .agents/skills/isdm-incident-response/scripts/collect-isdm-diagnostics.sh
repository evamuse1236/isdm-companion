#!/usr/bin/env bash
set -uo pipefail

package_name="org.isdm.companion"
serial=""
output_dir=""

usage() {
  cat <<'EOF'
Usage: collect-isdm-diagnostics.sh [--serial SERIAL] [--output DIR]

Collect read-only ISDM Companion device evidence. The script does not clear
logs, stop or launch the app, install packages, change permissions, or read
coordinates. Output files are created with owner-only permissions.
EOF
}

while (($#)); do
  case "$1" in
    --serial)
      serial="${2:?--serial requires a value}"
      shift 2
      ;;
    --output)
      output_dir="${2:?--output requires a value}"
      shift 2
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

command -v adb >/dev/null 2>&1 || {
  printf 'adb is required.\n' >&2
  exit 1
}

if [[ -z "$serial" ]]; then
  mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if ((${#devices[@]} != 1)); then
    printf 'Expected exactly one authorized device; found %d. Use --serial.\n' "${#devices[@]}" >&2
    adb devices -l >&2
    exit 1
  fi
  serial="${devices[0]}"
fi

adb_cmd=(adb -s "$serial")
"${adb_cmd[@]}" get-state >/dev/null

if [[ -z "$output_dir" ]]; then
  output_dir="${TMPDIR:-/tmp}/isdm-diagnostics-$(date -u +%Y%m%dT%H%M%SZ)"
fi

umask 077
mkdir -p -- "$output_dir"
chmod 700 -- "$output_dir"

capture() {
  local name="$1"
  shift
  {
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } >"$output_dir/$name.txt" 2>&1 || {
    printf '\nCommand failed with exit code %d.\n' "$?" >>"$output_dir/$name.txt"
  }
}

capture devices adb devices -l
capture model "${adb_cmd[@]}" shell getprop ro.product.model
capture android-version "${adb_cmd[@]}" shell getprop ro.build.version.release
capture sdk "${adb_cmd[@]}" shell getprop ro.build.version.sdk
capture build-fingerprint "${adb_cmd[@]}" shell getprop ro.build.fingerprint
capture package "${adb_cmd[@]}" shell dumpsys package "$package_name"
capture app-ops "${adb_cmd[@]}" shell cmd appops get "$package_name"
capture exit-info "${adb_cmd[@]}" shell dumpsys activity exit-info "$package_name"
capture jobs "${adb_cmd[@]}" shell dumpsys jobscheduler "$package_name"
capture crash-log "${adb_cmd[@]}" logcat -b crash -d -v threadtime
capture tagged-logcat "${adb_cmd[@]}" logcat -d -v threadtime -t 2000 'ISDMCompanion:V' 'AndroidRuntime:E' '*:S'
capture private-log "${adb_cmd[@]}" shell run-as "$package_name" cat files/diagnostics/companion.log
capture private-log-previous "${adb_cmd[@]}" shell run-as "$package_name" cat files/diagnostics/companion.log.1

{
  printf 'collected_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'serial=%s\n' "$serial"
  printf 'package=%s\n' "$package_name"
  printf 'privacy=owner-only; no coordinates requested\n'
} >"$output_dir/manifest.txt"

printf 'Diagnostics written to %s\n' "$output_dir"
