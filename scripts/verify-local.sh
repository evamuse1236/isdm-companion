#!/usr/bin/env bash
set -euo pipefail
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--node-only" ) ]]; then
  echo 'Usage: scripts/verify-local.sh [--node-only]' >&2; exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
command -v node >/dev/null || { echo 'Node.js 22.13+ is required.' >&2; exit 1; }
node -e 'const [a,b]=process.versions.node.split(".").map(Number); if(a<22||(a===22&&b<13))process.exit(1)' || {
  echo 'Node.js 22.13+ is required.' >&2; exit 1;
}
npm test
if [[ "${1:-}" == "--node-only" ]]; then exit 0; fi
command -v java >/dev/null || { echo 'JDK 17 is required; configure JAVA_HOME.' >&2; exit 1; }
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && ! -f android/local.properties ]]; then
  echo 'Configure ANDROID_HOME or android/local.properties for SDK 36.' >&2; exit 1
fi
cd android
bash gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
printf '\nDebug validation finished. No release was signed, deployed, or distributed.\n'
