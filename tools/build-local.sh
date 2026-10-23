#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -d .tools/jdk21/Contents/Home ]]; then export JAVA_HOME="$PWD/.tools/jdk21/Contents/Home"; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle-home}"
./tools/prepare-asr.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug "$@"
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/lacan-0.1.11-prototype.apk
shasum -a 256 artifacts/lacan-0.1.11-prototype.apk
