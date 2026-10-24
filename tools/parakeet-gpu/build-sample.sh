#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
project="${1:-/tmp/lacan-parakeet-full-sample}"
if [[ -z "${JAVA_HOME:-}" && -d "$repo/.tools/jdk21/Contents/Home" ]]; then export JAVA_HOME="$repo/.tools/jdk21/Contents/Home"; fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo/.tools/gradle-home}"
gradle="$repo/gradlew"
if [[ -x "$repo/.tools/gradle-8.13/bin/gradle" ]]; then gradle="$repo/.tools/gradle-8.13/bin/gradle"; fi
exec "$gradle" -p "$project" --no-daemon --max-workers=2 -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs=-Xmx1536m :app:assembleDebug
