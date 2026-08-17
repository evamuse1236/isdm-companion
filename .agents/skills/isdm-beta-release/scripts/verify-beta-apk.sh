#!/usr/bin/env bash
set -euo pipefail

apk=""
expected_package="org.isdm.companion"
expected_version=""
expected_code=""
expected_cert=""
expected_min_sdk="26"
expected_target_sdk="36"

usage() {
  cat <<'EOF'
Usage: verify-beta-apk.sh APK [options]

Options:
  --expected-package ID       Default: org.isdm.companion
  --expected-version NAME     Required expected versionName
  --expected-code NUMBER      Required expected versionCode
  --expected-cert SHA256      Required permanent signer SHA-256 digest
  --expected-min-sdk NUMBER   Default: 26
  --expected-target-sdk NUM   Default: 36

The script verifies alignment, signatures, package/version/SDK badging, signer
identity, and prints the APK SHA-256. It never reads signing passwords or keys.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if (($#)); then
  apk="$1"
  shift
fi

while (($#)); do
  case "$1" in
    --expected-package)
      expected_package="${2:?missing package}"
      shift 2
      ;;
    --expected-version)
      expected_version="${2:?missing version}"
      shift 2
      ;;
    --expected-code)
      expected_code="${2:?missing code}"
      shift 2
      ;;
    --expected-cert)
      expected_cert="${2:?missing certificate digest}"
      shift 2
      ;;
    --expected-min-sdk)
      expected_min_sdk="${2:?missing minimum SDK}"
      shift 2
      ;;
    --expected-target-sdk)
      expected_target_sdk="${2:?missing target SDK}"
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

[[ -f "$apk" ]] || {
  usage >&2
  exit 2
}
[[ -n "$expected_version" && -n "$expected_code" && -n "$expected_cert" ]] || {
  printf 'Expected version, code, and permanent certificate digest are required.\n' >&2
  exit 2
}

sdk_candidate="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
for candidate in "$sdk_candidate" /home/vishwasamsung/.local/share/rpm-android-sdk /usr/lib/android-sdk; do
  if [[ -n "$candidate" && -d "$candidate/build-tools" ]]; then
    sdk_candidate="$candidate"
    break
  fi
done
[[ -d "$sdk_candidate/build-tools" ]] || {
  printf 'Android SDK build-tools were not found. Set ANDROID_HOME.\n' >&2
  exit 1
}

build_tools="$(find "$sdk_candidate/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -n 1)"
[[ -n "$build_tools" ]] || {
  printf 'No Android build-tools version was found.\n' >&2
  exit 1
}
tools_dir="$sdk_candidate/build-tools/$build_tools"
aapt="$tools_dir/aapt"
apksigner="$tools_dir/apksigner"
zipalign="$tools_dir/zipalign"

for tool in "$aapt" "$apksigner" "$zipalign"; do
  [[ -x "$tool" ]] || {
    printf 'Missing Android build tool: %s\n' "$tool" >&2
    exit 1
  }
done

"$zipalign" -c -P 16 4 "$apk"
signature_output="$("$apksigner" verify --verbose --print-certs "$apk")"
printf '%s\n' "$signature_output"
grep -F 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$signature_output" >/dev/null
grep -F 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$signature_output" >/dev/null

badging="$("$aapt" dump badging "$apk")"

actual_package="$(sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" <<<"$badging")"
actual_version="$(sed -n "s/^package:.*versionName='\\([^']*\\)'.*/\\1/p" <<<"$badging")"
actual_code="$(sed -n "s/^package:.*versionCode='\\([^']*\\)'.*/\\1/p" <<<"$badging")"
actual_min_sdk="$(sed -n "s/^sdkVersion:'\\([^']*\\)'.*/\\1/p" <<<"$badging")"
actual_target_sdk="$(sed -n "s/^targetSdkVersion:'\\([^']*\\)'.*/\\1/p" <<<"$badging")"
actual_cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$signature_output" | head -n 1 | tr '[:lower:]' '[:upper:]' | tr -d ':')"
normalized_expected_cert="$(tr '[:lower:]' '[:upper:]' <<<"$expected_cert" | tr -d ':[:space:]')"

[[ "$actual_package" == "$expected_package" ]] || {
  printf 'Package mismatch: expected %s, got %s\n' "$expected_package" "$actual_package" >&2
  exit 1
}
[[ "$actual_version" == "$expected_version" ]] || {
  printf 'Version mismatch: expected %s, got %s\n' "$expected_version" "$actual_version" >&2
  exit 1
}
[[ "$actual_code" == "$expected_code" ]] || {
  printf 'Version code mismatch: expected %s, got %s\n' "$expected_code" "$actual_code" >&2
  exit 1
}
[[ "$actual_min_sdk" == "$expected_min_sdk" ]] || {
  printf 'Minimum SDK mismatch: expected %s, got %s\n' "$expected_min_sdk" "$actual_min_sdk" >&2
  exit 1
}
[[ "$actual_target_sdk" == "$expected_target_sdk" ]] || {
  printf 'Target SDK mismatch: expected %s, got %s\n' "$expected_target_sdk" "$actual_target_sdk" >&2
  exit 1
}
[[ "$actual_cert" == "$normalized_expected_cert" ]] || {
  printf 'Signer mismatch: expected %s, got %s\n' "$normalized_expected_cert" "$actual_cert" >&2
  exit 1
}

printf 'Package: %s\n' "$actual_package"
printf 'Version: %s (%s)\n' "$actual_version" "$actual_code"
printf 'SDK: min=%s target=%s\n' "$actual_min_sdk" "$actual_target_sdk"
printf 'Signer SHA-256: %s\n' "$actual_cert"
sha256sum "$apk"
