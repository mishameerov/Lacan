#!/bin/zsh
set -eu
TASK_ROOT="${0:A:h:h:h}"
export JAVA_HOME="$TASK_ROOT/.tools/jdk21/Contents/Home"
export GRADLE_USER_HOME="$TASK_ROOT/.tools/gradle-home"
export CMAKE_BUILD_PARALLEL_LEVEL=2
python3 "$TASK_ROOT/tools/giga-gpu/instrument_mnn.py"
"$TASK_ROOT/.tools/gradle-8.13/bin/gradle" -p "$TASK_ROOT/tools/giga-gpu" --no-daemon --max-workers=2 :probe:assembleDebug
