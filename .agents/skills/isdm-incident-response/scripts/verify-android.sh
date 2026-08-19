#!/usr/bin/env bash
set -euo pipefail

connected=false
acknowledge=false
serial=""

usage() {
  cat <<'EOF'
Usage: verify-android.sh [--connected --acknowledge-debug-data-risk] [--serial SERIAL]

Default: run JVM tests, lint, and assembleDebug with Java 17.
Connected mode also runs connectedDebugAndroidTest. Instrumentation can reinstall
the debug app and reset debug-app data, so the acknowledgement flag is required.
EOF
}

while (($#)); do
  case "$1" in
    --connected)
      connected=true
      shift
      ;;
    --acknowledge-debug-data-risk)
      acknowledge=true
      shift
      ;;
    --serial)
      serial="${2:?--serial requires a value}"
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

if "$connected" && ! "$acknowledge"; then
  printf 'Connected tests require --acknowledge-debug-data-risk.\n' >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"

java_home_candidate="${JAVA_HOME:-}"
if [[ -z "$java_home_candidate" || ! -x "$java_home_candidate/bin/java" || "$("$java_home_candidate/bin/java" -version 2>&1 | head -n 1)" != *'"17.'* ]]; then
  java_home_candidate="/usr/lib/jvm/java-17-openjdk-amd64"
fi
[[ -x "$java_home_candidate/bin/java" ]] || {
  printf 'Java 17 was not found. Set JAVA_HOME to a JDK 17 directory.\n' >&2
  exit 1
}

sdk_candidate="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
for candidate in "$sdk_candidate" /home/vishwasamsung/.local/share/rpm-android-sdk /usr/lib/android-sdk; do
  if [[ -n "$candidate" && -x "$candidate/platform-tools/adb" && -d "$candidate/build-tools" ]]; then
    sdk_candidate="$candidate"
    break
  fi
done
[[ -d "$sdk_candidate" ]] || {
  printf 'Android SDK was not found. Set ANDROID_HOME.\n' >&2
  exit 1
}

export JAVA_HOME="$java_home_candidate"
export ANDROID_HOME="$sdk_candidate"
export ANDROID_SDK_ROOT="$sdk_candidate"

if [[ -n "$serial" ]]; then
  export ANDROID_SERIAL="$serial"
fi

cd "$repo_root/android"
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

if "$connected"; then
  command -v adb >/dev/null 2>&1 || {
    printf 'adb is required for connected tests.\n' >&2
    exit 1
  }
  adb devices -l
  bash gradlew :app:connectedDebugAndroidTest
fi
