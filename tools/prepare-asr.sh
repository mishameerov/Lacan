#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .tools app/libs
fetch() {
    url=$1 destination=$2 expected=$3
    if [ -f "$destination" ] && [ "$(shasum -a 256 "$destination" | cut -d ' ' -f 1)" = "$expected" ]; then return; fi
    temp="$destination.part"
    trap 'rm -f "$temp"' EXIT HUP INT TERM
    curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 "$url" -o "$temp"
    actual=$(shasum -a 256 "$temp" | cut -d ' ' -f 1)
    [ "$actual" = "$expected" ] || { echo "SHA-256 mismatch: $destination" >&2; exit 1; }
    mv "$temp" "$destination"
    trap - EXIT HUP INT TERM
}
fetch 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.32/sherpa-onnx-1.12.32.aar' app/libs/sherpa-onnx-1.12.32.aar 43450d480eda70de929be82711851e8e91d82ea640f7d2296c1f6526fd792cad
fetch 'https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v1.8.7' .tools/whisper-v1.8.7.tar.gz 0b988ba5053cfa720f6d399f3f21885b01c4222178be435ca2272d6872717554
if [ ! -f .tools/whisper.cpp/CMakeLists.txt ]; then
    mkdir -p .tools/whisper-unpack
    tar -xzf .tools/whisper-v1.8.7.tar.gz -C .tools/whisper-unpack
    mv .tools/whisper-unpack/whisper.cpp-1.8.7 .tools/whisper.cpp
    rmdir .tools/whisper-unpack
fi
# Preserve dependency license texts in the APK, in addition to the source archive.
mkdir -p app/src/main/assets/licenses
cp .tools/whisper.cpp/LICENSE app/src/main/assets/licenses/whisper.cpp-MIT.txt
cp THIRD_PARTY_NOTICES.md app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md
printf '%s\n' 'Pinned ASR dependencies are ready.'
