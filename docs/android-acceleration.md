# Android ASR acceleration: practical plan

Research checked against primary documentation on 2026-10-21. This is a proposed experiment, not a claim that an accelerator has been integrated or measured.

## Recommendation

Use a capability-tested NPU → GPU → CPU policy, with separate compatible model artifacts and honest reporting of the backend that actually executed. Given the decision to retain whisper.cpp as a legacy experiment, prioritize a LiteRT CompiledModel conversion probe for a fixed-window GigaAM CTC encoder: establish the CPU reference, then GPU, then documented supported NPUs. LiteRT supplies a common API across supported Qualcomm, MediaTek and Samsung chips. Vulkan Whisper is an optional, lower-conversion-cost alternative, not the new default. Preserve the CPU engines for recovery. There is no demonstrated switch that makes Lacan's current ONNX/GGML files run on every phone's NPU.

For the Galaxy A56 / Exynos 1580, GPU is the first bounded acceleration experiment. NPU availability is real hardware capability, but its compatibility with a public inference runtime and these ASR graphs remains unverified. Do not advertise A56 NPU support from the existence of the silicon or Android service alone.

## What Lacan currently builds

- `app/build.gradle.kts` targets arm64-v8a, minSdk 33, targetSdk 36, NDK 27.2; it vendors `sherpa-onnx-1.12.32.aar`.
- `SherpaEngine.kt` uses GigaAM through NeMo CTC with 64-bin features and `provider = "cpu"`. Its own comment identifies a dynamic time input `[batch, 64, time]`.
- `SherpaWhisperEngine.kt` uses separate INT8 ONNX encoder/decoder files and `provider = "cpu"`.
- `tools/prepare-asr.sh` pins whisper.cpp v1.8.7. CMake does not enable Vulkan; `whisper-jni.cpp` explicitly sets `use_gpu = false` and suppresses native logs.
- A read-only ZIP inventory of the AAR shows sherpa JNI/C/C++ libraries and bundled `libonnxruntime.so`; no separate QNN or LiteRT vendor runtimes. This inventory does **not** prove which EPs were statically built into ONNX Runtime. Enumerate providers or inspect exact build provenance before exposing a provider selector.

## Interfaces and real coverage

| Route | Practical scope | Consequence for Lacan |
| --- | --- | --- |
| Android NNAPI / ORT NNAPI EP | Legacy cross-vendor interface; deprecated in Android 15. ORT still documents Android packages and explicit EP registration. Driver availability and each supported operator determine delegation. | An optional diagnostic experiment for the existing ONNX graph; not the long-term foundation or proof of NPU execution. Disable NNAPI's reference CPU device while profiling and separately observe ORT CPU partitions. [Android migration](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide), [ORT NNAPI](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html). |
| LiteRT CompiledModel | Current unified CPU/GPU/NPU API, operating on LiteRT models with vendor integrations. | Requires conversion and a new engine; it cannot consume Lacan's ONNX or GGML files directly. [Overview](https://developers.google.com/edge/litert/overview). |
| Qualcomm QNN | Qualcomm-specific HTP NPU or GPU backends. Current separate QNN EP repository documents Android ARM64 Maven `com.qualcomm.qti:onnxruntime-android-qnn`, validated with ORT 1.30.0 / QNN runtime 2.51.0. | Not an Exynos/MediaTek solution. Avoid loading a second incompatible `libonnxruntime.so` beside sherpa's vendored runtime. Rebuild a coherent native stack or isolate the experiment. [Current QNN EP](https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md). |
| whisper.cpp Vulkan | Cross-vendor GPU compute, subject to Vulkan driver/features, memory and graph support. Upstream supports `GGML_VULKAN=1`. | Closest route to reusing current GGML Whisper models. CMake's pinned Vulkan backend also requires host `glslc` and SPIRV-Headers; enabling one flag and JNI GPU use is insufficient validation. [Upstream](https://github.com/ggml-org/whisper.cpp/blob/master/README.md). |

ONNX Runtime has no generic Android `gpu` EP that automatically maps to arbitrary Mali/Xclipse/Adreno hardware. Its EPs are distinct integrations: QNN GPU remains Qualcomm-specific; CUDA and DirectML are different platforms. [ORT EP catalog](https://onnxruntime.ai/docs/execution-providers/).

### Current LiteRT NPU support is a chip matrix

The current Samsung page lists **Exynos 2500 and 2600**, with AOT and on-device compilation. Exynos 1580 is not listed; do not infer compatibility. [Samsung integration](https://developers.google.com/edge/litert/next/samsung).

The Qualcomm page lists Snapdragon 8 Gen 1, 8+ Gen 1, 8 Gen 2, 8 Gen 3, 8 Elite and 8 Elite Gen 5. The MediaTek page lists Dimensity 7300, 8300, 9000, 9200, 9300, 9400 and 9500. These published lists are a starting qualification matrix, not a guarantee for every graph or firmware. [Qualcomm integration](https://developers.google.com/edge/litert/next/qualcomm), [MediaTek integration](https://developers.google.com/edge/litert/next/mediatek).

Prefer these CompiledModel pages over older Interpreter delegate pages that still say Samsung is coming soon. An API's broad manufacturer check must not replace successful vendor initialization and measured graph assignment.

### Android 17's `npu` service

The attached A56 reports `android.npumanager.INpuManagerService`. Android 17 adds a standard NPU Manager for model-load reservations, scheduling, priorities and preemption; vendor drivers still perform inference. Thus this service is evidence of platform resource coordination, not a universal ONNX loader or evidence that GigaAM/Whisper can execute on this NPU. `dumpsys npu` policy output cannot establish model compatibility. [AOSP NPU Manager](https://source.android.com/docs/core/perf/npu-manager).

## Model and packaging constraints

Graph operator names alone are insufficient: tensor types, shapes, quantization scheme, constant versus dynamic inputs and firmware/compiler versions determine support. Current QNN EP supports floating-point and quantized HTP models, but requires fixed shapes and a supported operator subset. Older QNN documentation's quantized-only statement must not be applied blindly to new versions. Lacan's existing dynamic-time CTC graph and INT8 Whisper artifacts therefore need inspection, not renaming. [Current QNN requirements](https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md).

For LiteRT, start from an exportable source model and reproduce feature extraction/tokenization exactly. Test fixed audio windows and masks; for an autoregressive decoder, examine cache dimensions and repeated small dispatches. Start with the encoder/CTC graph rather than promising full NPU decoding. Consult the [per-backend operator matrix](https://developers.google.com/edge/litert/inference). Quantization should be calibrated on representative Russian, English, silence and noisy speech, then checked for WER/CER regression. INT8 weights alone do not establish an NPU-compatible quantization recipe; Google currently recommends static quantization for NPU. [Quantization guide](https://developers.google.cn/edge/litert/quantization/model_optimization).

GPU fallback also requires qualification: unsupported operators can divide a graph across CPU/GPU and add transfer overhead. The older LiteRT GPU delegate executes quantized models through a floating-point representation; smaller storage does not imply integer GPU execution or lower memory. [GPU limitations](https://developers.google.com/edge/litert/performance/gpu).

LiteRT documents both AOT and on-device compilation, downloadable vendor runtime modules and Play distribution. Its native C++ path permits explicitly packaged runtime/dispatch/backend libraries; inference need not require a network service. However, an on-demand Play module is unavailable during a first offline launch until downloaded. A standalone APK must actually include compatible vendor dependencies; a device-group fallback named `other` does not supply them automatically. Qualcomm instructions also use legacy JNI packaging, while Lacan currently disables it. Resolve packaging and 16 KB page compatibility deliberately. [NPU deployment](https://developers.google.com/edge/litert/next/npu), [native SDK](https://developers.google.com/edge/litert/next/cpp_sdk).

An open-source frontend is not the same as fully open-source vendor firmware/compiler/runtime dependencies. LiteRT's [Apache-2.0 source license](https://github.com/google-ai-edge/LiteRT/blob/main/LICENSE) and sherpa's source license do not license every vendor binary or downloaded model. Record redistribution terms and notices for each shipped dependency/model; source reproducibility and offline execution are separate acceptance criteria.

Sherpa now has documented Qualcomm HTP Android demos, dedicated build steps, QNN libraries and SoC-specific model artifacts. That is evidence of a viable Qualcomm path, not proof that Lacan's AAR or GigaAM/Whisper exports support it. Check language coverage and model licenses before substituting an ASR model. [Sherpa QNN demos](https://k2-fsa.github.io/sherpa/onnx/android/apk-qnn-asr-streaming.html), [Android native packaging steps](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/qnn/build-android-demo.rst).

## Bounded experiment and ship criteria

1. **Inventory without a benchmark marathon.** Record the exact model hashes, native runtime versions, public provider capabilities and device GPU/Vulkan features. Capture initialization errors and actual graph assignment; expose requested backend separately from actual execution. Keep logs free of audio/transcripts by default.
2. **First GPU spike: one CTC model, one device.** Attempt a fixed-window export of the known working GigaAM FP32 model into LiteRT. Establish CPU numerical/transcription parity before attempting GPU compilation on A56. Record unsupported operations and conversion failures explicitly; this feasibility step may fail. If it works, run one short fixed consented clip for correctness/cancellation and a bounded warm/cold CPU comparison. Stop at the first correctness, memory or driver issue. An optional alternative is a separate Vulkan whisper.cpp build using existing GGML weights; this avoids model conversion but revives the legacy engine and is not the preferred default.
3. **First NPU spike: one fixed-window encoder.** Choose one model with accessible source/export path; export a LiteRT CPU reference and GPU version, then compile the same fixed-window graph for one documented supported NPU device. Verify numerical/transcription agreement and actual delegation before extending to Qualcomm, MediaTek and supported Exynos hardware. A56 NPU remains a separate unsupported-chip investigation unless public runtime compatibility is established.
4. **Measure end-to-end before shipping.** With a small reproducible set, record cold/warm initialization, real-time factor, finalization latency, peak memory, WER/CER and sustained thermal/energy behavior. Include silence, short speech and cancellation. Count frontend/decoder/transfers, not just accelerator kernel time. Predeclare a meaningful speed/energy improvement with an acceptable accuracy budget; reject a partially delegated route that loses overall.
5. **Ship only qualified combinations.** Store compatibility by model hash/runtime/device/driver, retain a user-visible backend override, and retry a failed initialization on GPU then CPU. Report unsupported NPU as unsupported. Do not silently label CPU partitions as NPU success, repeatedly retry a broken driver, or switch models/languages during fallback without telling the user.

Today Lacan can ship its existing offline CPU paths; no accelerator route has been demonstrated in this work. Vulkan Whisper is an alternative native integration; the preferred common LiteRT path first requires proving model conversion. Broad NPU support requires conversion, vendor dependencies and a device matrix; it is a separate engineering milestone, with LiteRT currently the strongest common interface to investigate.
