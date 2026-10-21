#!/bin/sh
# Pinned, CPU-only build preparation. No PyTorch, model download or converter telemetry.
set -eu
cd "$(dirname "$0")/.."
export CMAKE_BUILD_PARALLEL_LEVEL=2
mkdir -p .tools/mnn .tools/knf .tools/kissfft app/src/main/assets/licenses
fetch() {
    source_url=$1 target=$2 checksum=$3
    if [ -f "$target" ] && [ "$(shasum -a 256 "$target" | cut -d ' ' -f 1)" = "$checksum" ]; then return; fi
    part="$target.part"
    trap 'rm -f "$part"' EXIT HUP INT TERM
    curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 "$source_url" -o "$part"
    [ "$(shasum -a 256 "$part" | cut -d ' ' -f 1)" = "$checksum" ] || { echo "Checksum mismatch: $target" >&2; exit 1; }
    mv "$part" "$target"
    trap - EXIT HUP INT TERM
}
mnn=.tools/mnn/MNN-3.6.1
if [ ! -d "$mnn/.git" ]; then
    git clone --depth 1 --branch 3.6.1 --filter=blob:none --sparse https://github.com/alibaba/MNN.git "$mnn"
fi
[ "$(git -C "$mnn" rev-parse HEAD)" = d407447ed56c4121a11ccbd266dc184ca1ead0c2 ] || { echo 'Wrong MNN source revision' >&2; exit 1; }
if [ ! -f "$mnn/tools/converter/CMakeLists.txt" ] || [ ! -f "$mnn/source/core/Pipeline.cpp" ]; then
    git -C "$mnn" sparse-checkout set 3rd_party cmake codegen include schema source express tools/train tools/audio tools/converter
fi
python3 tools/giga-gpu/instrument_mnn.py
python3 tools/giga-gpu/patch_mnn.py
fetch https://github.com/csukuangfj/kaldi-native-fbank/archive/refs/tags/v1.22.3.tar.gz .tools/kaldi-native-fbank-v1.22.3.tar.gz 9176cc66fc7ce1edf85cf355b06e320c57db6297df74277f575183468893cf61
if [ ! -f .tools/knf/kaldi-native-fbank-1.22.3/CMakeLists.txt ]; then tar -xzf .tools/kaldi-native-fbank-v1.22.3.tar.gz -C .tools/knf; fi
fetch https://github.com/mborgerding/kissfft/archive/febd4caeed32e33ad8b2e0bb5ea77542c40f18ec.zip .tools/kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec.zip 497103e664168ebe39580b757adbe616f6cf85a16572af581ca7bc42d0ab13fd
if [ ! -f .tools/kissfft/kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec/CMakeLists.txt ]; then unzip -q .tools/kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec.zip -d .tools/kissfft; fi
cp "$mnn/LICENSE.txt" app/src/main/assets/licenses/MNN-Apache-2.0.txt
cp .tools/knf/kaldi-native-fbank-1.22.3/LICENSE app/src/main/assets/licenses/kaldi-native-fbank-Apache-2.0.txt
cp .tools/kissfft/kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec/COPYING app/src/main/assets/licenses/kissfft-BSD-3-Clause.txt
printf '%s\n' 'Pinned MNN OpenCL, kaldi-native-fbank and kissfft sources are ready.'
