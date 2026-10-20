# Parakeet LiteRT Android GPU probe

Prepared 2026-10-21. This is an isolated experiment, not a Lacan ASR engine or a speed claim.

## Chosen route and pins

LiteRT is the shortest ready-artifact route: the [official Android speech sample](https://github.com/google-ai-edge/litert-samples/tree/f1e415713f0354920c263c66e65ebd4b138c7e67/samples/litert/speech_recognition/AndroidApp) uses the published Maven artifacts `com.google.ai.edge.litert:litert:2.3.0` and `litert-gpu:2.3.0`. No PyTorch export, NeMo installation, CPU model conversion, or ExecuTorch source build is required. Android GPU uses LiteRT's OpenCL/OpenGL accelerator; this is not a Vulkan experiment.

Sources are pinned at `f1e415713f0354920c263c66e65ebd4b138c7e67`. The [model repository](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/tree/3ce205aa928f443f423214195cfe83ebe4802826) is pinned at `3ce205aa928f443f423214195cfe83ebe4802826`. The downloaded stateless INT8 artifact is exactly **614,437,424 bytes**, SHA-256 **`f25e5972fe72048f67272e26d4badfe19d876e0fa19027cb2c6c0e0fc4da692b`**. The local verified model is `/tmp/lacan-parakeet-probe/parakeet.tflite`; the download/hash operation succeeded. INT8 here is dynamic range weight quantization with floating-point activations; the publisher labels this exact artifact CPU/GPU. Device compatibility and RAM are separate gates. The FP32 reference is 2,422,415,168 bytes and was not downloaded.

`tools/parakeet-gpu/artifacts.lock.json` records complete source/model/tokenizer revisions, artifact sizes and hashes. The tokenizer comes from NVIDIA revision `541d1f99c6b0c3cd0b11a95167540bb8edefd82b`; its 1,159,960-byte JSON SHA-256 is `bd321b096832a3f270bd3b2a88823957920f1a5c5ada71114a26ea729d0cbe91`. Parakeet weights require CC BY 4.0 attribution; sample/runtime code is Apache-2.0. Retain the model card and upstream/tokenizer notices before distribution.

## Reproduce without touching a phone

From repository root:

```sh
# Default mode prints the exact URL, size and hash without downloading.
python3 tools/parakeet-gpu/fetch-model.py
# Explicit download, bounded to the pinned byte count, atomic on verified success:
python3 tools/parakeet-gpu/fetch-model.py --download
# Small isolated encoder APK:
tools/parakeet-gpu/build.sh
# Optional official full sample, with its source frontend/TDT loop/tokenizer:
tools/parakeet-gpu/fetch-upstream.sh /tmp/lacan-litert-samples-probe
python3 tools/parakeet-gpu/prepare-sample.py --source /tmp/lacan-litert-samples-probe
tools/parakeet-gpu/build-sample.sh /tmp/lacan-parakeet-full-sample
```

The existing local JDK 21, installed Gradle 8.13 and cache are reused when present. Otherwise the repository's hash-pinned wrapper is used. Set `JAVA_HOME` and `ANDROID_HOME`/`ANDROID_SDK_ROOT` for another workstation. Builds cap Gradle workers at two; the full sample uses installed NDK `27.2.12479018`, CMake `3.22.1`, compile SDK 36, minimum API 31 and ARM64. The isolated project is not included in Lacan's production Gradle settings.

`prepare-sample.py` copies only the immutable official ASR sample to a fresh temporary directory, removes missing optional NPU modules and AGP 9.1-only bundle configuration, aligns AGP/Kotlin to Lacan's existing 8.13.2/2.2.21 toolchain, and pins model/tokenizer URLs. Upstream already includes ARM64/x86_64 tokenizer static libraries and its C header; a Rust build is unnecessary. The JLibrosa dependency has a mutable upstream URL, so preparation enforces the observed SHA-256 `dc6ec9537f6e1e4f300b4021e214cc7f22bc661161d3a64ecd57b6a113a6a077` before writing it. Failure is explicit if bytes change. Gradle transitive dependencies are version resolved, not yet a fully locked distribution manifest.

## Build evidence

Both APKs assembled successfully. The encoder probe APK is `tools/parakeet-gpu/probe/build/outputs/apk/debug/probe-debug.apk` (18 MiB; SHA-256 `ca3c4710cd4daadbf991cb3efe7b33756ec03361d6ebf7b052050f234ccbcd21`). The adapted full official ASR sample assembled successfully on 2026-10-21 (41 tasks; 1m 05s). APK: `/tmp/lacan-parakeet-full-sample/app/build/outputs/apk/debug/app-debug.apk`, 26 MiB, SHA-256 `adbf961d0f8ba6ed28f8f20732a696f7578340f098811831720b512d868090d1`. Both APKs also passed `zipalign -c -P 16 -v 4` package alignment verification. This confirms published runtime packaging and native tokenizer linking; it does not demonstrate inference. LiteRT 2.3.0 has Kotlin metadata 2.4.0, so both samples preserve the upstream `-Xskip-metadata-version-check` compatibility flag with Kotlin 2.2.21. Production integration should select a supported coherent Kotlin/runtime toolchain rather than silently inheriting this experiment flag.

## Encoder-only device contract

The probe's distinct application ID is `org.lacan.parakeetprobe`. It has no microphone or network permission. It expects `parakeet.tflite` and optionally `features.f32le` in its external files directory. Feature input is float32 little endian, exactly the `encode` signature shape reported by LiteRT. Without a feature file it runs a zero-tensor dispatch smoke test, which says nothing about transcription or frontend accuracy.

`CompiledModel.Options(setOf(Accelerator.GPU))` requests GPU only, with `GpuOptions(precision=Precision.FP32, constantTensorSharing=true)`; CPU control explicitly requests `Accelerator.CPU`. Each launch compiles and performs **exactly one** `encode` invocation and readback. The UI/log/result marks partition placement **UNVERIFIED**: a requested accelerator or successful numerical output alone does not prove all graph nodes ran there. `encoder-gpu.f32le`/`encoder-cpu.f32le` and reports are saved beside the model. Compare pulled arrays using `compare-encoder.py`; it reports max/RMS error without inventing a numerical acceptance tolerance.

After device testing is explicitly authorized and its transport is selected, the manual sequence is:

```sh
adb -t TRANSPORT_ID install -r tools/parakeet-gpu/probe/build/outputs/apk/debug/probe-debug.apk
# Create directory without launching inference by using the shell app's file access.
adb -t TRANSPORT_ID shell mkdir -p /sdcard/Android/data/org.lacan.parakeetprobe/files
adb -t TRANSPORT_ID push /tmp/lacan-parakeet-probe/parakeet.tflite /sdcard/Android/data/org.lacan.parakeetprobe/files/parakeet.tflite
adb -t TRANSPORT_ID shell am start -n org.lacan.parakeetprobe/.ProbeActivity --es backend gpu
adb -t TRANSPORT_ID logcat -d -s ParakeetGpuProbe LiteRt
```

Do not interpret fallback/partition errors as successful GPU qualification. Capture native LiteRT/delegate logs and graph coverage, not only the UI label. A CPU comparison is a separate explicit launch; these scripts do not run one automatically.

## Device evidence: initial GPU gate passed

The installed isolated probe ran once on the authorized Android phone and was force-stopped after **20.54 seconds**. No microphone or Lacan settings were accessed. Native LiteRT logs show **OpenCL** device creation and actual `LITERT_CL` delegation: **1,790/1,790 encode nodes, one partition**, and **2,083/2,083 decode nodes, one partition**. This establishes encoder GPU assignment on this device/runtime/artifact combination; the decode graph was compiled but not invoked.

One zero-feature encoder input `[1,128,500]` produced **64,512 finite floats** (range approximately −0.147 to 0.171). Cold compilation, including both signatures, took **17,794 ms**; the single encoder call plus readback took **546 ms**. These are synthetic smoke observations, not speech performance or accuracy measurements. CPU comparison, real frontend input, full TDT decoding, warm runs and memory measurements were not performed. The UI conservatively prints placement unverified, while the captured native logs provide the graph assignment evidence for this specific run. A driver diagnostic labelled `E SOCL` was an OpenCL float-literal compiler *warning*; compilation and invocation subsequently completed.

The small evidence excerpt is `tools/parakeet-gpu/evidence/2026-10-21-gpu-smoke.txt`. Full own-process logs/report remain under `/tmp/lacan-parakeet-probe/`. The isolated package and verified model remain staged; the app is stopped. No CPU-only fallback or retry was invoked.

## Full speech contract and remaining work

The published stateless model exposes `encode` and `decode`; the separate `_stateful` artifact adds `decode_1`. The official decoder reads projected features of dimension 1,024, emits 8,192 vocabulary tokens plus blank ID 8,192 and duration choices 0–4, and retains explicit LSTM hidden/cell state. The publisher describes two layers with state `[2,1,640]`. Host decoding selects token/duration, handles blank advancement, and switches buffers only for nonblank emissions. The stateless decoder stops at its token capacity; do not assume long dictation is qualified with it. The **stateful 614,261,072-byte artifact** is the preferred subsequent full-speech test; it was not downloaded during this encoder gate.

The official frontend uses 16 kHz mono PCM, 128 mel bins, FFT 512, 160-sample hop, 500 feature frames per five seconds, pre-emphasis 0.97, log with a `2^-24` guard, and per-feature sample mean/std normalization. Match this exact frontend/padding before comparing speech. A raw WAV cannot be passed directly into `encode`. The source `MelSpectroProcessor`/JLibrosa implementation is available for the full sample; it has not been shown equivalent to the current sherpa frontend.

The official full sample selects CPU plus the requested accelerator as fallback and applies that compiled model to encoder and decoder signatures. It does **not** establish the proposed GPU-encoder/CPU-neural-decoder split, nor prove GPU placement just by selecting “GPU”. Lacan adapter work still includes separate downloaded-model identity/install metadata, bounded preprocessing and TDT orchestration, backend coverage evidence, result/cancellation behavior, Russian/English/mixed/silence/boundary correctness, and measured initialization/end-to-end memory/latency against the existing CPU engine. No Lacan model selection is changed by this experiment.
