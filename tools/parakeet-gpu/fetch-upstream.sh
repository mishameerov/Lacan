#!/usr/bin/env bash
set -euo pipefail
revision=f1e415713f0354920c263c66e65ebd4b138c7e67
destination="${1:-/tmp/lacan-litert-samples-probe}"
if [[ -d "$destination/.git" ]]; then
  [[ "$(git -C "$destination" rev-parse HEAD)" == "$revision" ]] || { echo 'Wrong source revision' >&2; exit 1; }
else
  git clone --filter=blob:none --no-checkout https://github.com/google-ai-edge/litert-samples.git "$destination"
  git -C "$destination" sparse-checkout init --cone
  git -C "$destination" sparse-checkout set samples/litert/speech_recognition/AndroidApp samples/litert/speech_recognition/convert
  git -C "$destination" checkout --detach "$revision"
fi
printf '%s\n' "$destination"
