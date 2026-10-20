#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && -d "$repo/.tools/jdk21/Contents/Home" ]]; then export JAVA_HOME="$repo/.tools/jdk21/Contents/Home"; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo/.tools/gradle-home}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
# Uses installed Gradle 8.13 when present, otherwise Lacan's pinned wrapper.
# This project is not included in the production build.
gradle="$repo/gradlew"
if [[ -x "$repo/.tools/gradle-8.13/bin/gradle" ]]; then gradle="$repo/.tools/gradle-8.13/bin/gradle"; fi
exec "$gradle" -p "$here" --no-daemon --max-workers=2 -Dorg.gradle.parallel=false :probe:assembleDebug "$@"
