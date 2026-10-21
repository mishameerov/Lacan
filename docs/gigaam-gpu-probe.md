# GigaAM v3 FP32: Android GPU investigation

The existing `gigaam-v3-fp32` CPU model is full FP32, not 32-bit quantization. This experiment preserves its FP32 weights and evaluates MNN/OpenCL on a physical Galaxy A56, without replacing the working CPU adapter.

## Route and artifacts

No ready GigaAM LiteRT artifact was located. Unlike Parakeet, the existing ONNX file cannot simply be supplied to LiteRT's compiled-model API. The selected bounded experiment uses MNN's direct ONNX conversion and Android OpenCL runtime. Qualification began in a separate test APK with no microphone or Internet permission. The successful route is now integrated as a separate experimental `gigaam-v3-fp32-gpu` catalog entry.

- Original ONNX: 885,950,432 bytes; SHA256 `1f3a714b76877724eda494d77e7ed0d15f1c003c60928c9cb03f21d81dc68cc5`.
- MNN 3.6.1, source commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2`, Apache-2.0. Source, build, diagnostic patch and licenses are described in [the probe instructions](../tools/giga-gpu/README.md).
- Converted FP32 model: 886,031,144 bytes; SHA256 `bdebf9ad1f4e5221096c723fbafbe0c726a4903ea623dc33557d51bed5f5e76c`. No FP16 or int8 weight conversion is enabled. No model weights are committed.
- OpenCL requests `Precision_High`, meaning FP32 storage/computation; MNN's normal precision mode would use FP16 storage. Actual per-operation execution backend is recorded, because MNN permits CPU fallback for unsupported operations.

The model accepts features `[1,64,T]` and an integer feature length, returning `[1,T/4,257]` logits. GPU neural inference, frontend/CTC decoding, successful model conversion and numerical agreement are separate checks.

## Numerical gate on the development machine

The public six-second Russian fixture yields 600 feature frames and logits `[1,150,257]`. `tools/giga-gpu-reference.py` generates the saved FP32 feature tensor and original ONNX CPU output. Frontend parameters: 16 kHz mono, 25 ms Hann window, 10 ms stride, 400-point FFT, 64 mel bins, zero dither/preemphasis, no DC removal, unsnipped edges. The source model has no per-feature normalization metadata. The original ONNX runtime and converted MNN CPU model receive the exact same saved tensor.

MNN CPU versus ONNX CPU: maximum absolute logit difference 0.00051546, RMS difference 0.00003478, all frame argmax symbols identical, and greedy CTC text identical. This validates this conversion on one fixture; it is not a broad accuracy evaluation. Local neural-call times (0.184 s ONNX, 0.252 s MNN, two CPU threads) are development-machine observations and say nothing about Android GPU performance.

Reference environment: Python 3.14, MNN 3.6.1, numpy 2.5.4, onnxruntime 1.31.0, kaldi-native-fbank 1.22.3. The reference script disables ORT telemetry. The reproducible converter bypasses MNN CLI's optional telemetry logger.

## Android qualification

The standalone arm64 APK builds with NDK 27.2 and MNN OpenCL enabled. Both native libraries and APK packaging pass 16 KiB alignment. Execution hooks distinguish scheduled operation backends from tensor storage; requesting GPU alone is not accepted as evidence of placement. CPU execution must be explicitly requested as a separate run. Debug-host/callback overhead makes these diagnostic timings unsuitable for a production throughput claim.

The initial measurement was contaminated by device sleep and is excluded from performance results. A diagnostic retry records native phase markers and holds a partial wake lock for at most 180 seconds, without unlocking the phone or changing system settings. The working Lacan CPU model is stopped while the standalone probe runs to remove competing model memory. Final device results are recorded below.

### Awake A56 result

Cold load: 3127.6 ms; session preparation: 7879.3 ms. Six-second saved-feature inference: enqueue 6.27 ms plus readback 1468.56 ms. GPU execution is asynchronous: the enqueue number alone is not inference latency. All 1101 observed neural operations used OpenCL; zero CPU fallback operations. Compared with ONNX FP32, maximum absolute logit difference was 0.00005245, RMS 0.00000693; every frame argmax and the CTC text matched. This is one fixture, not a general speed comparison with the phone CPU.

### Lacan integration

The GPU entry uses a dedicated retained worker/session, FP32 OpenCL buffer mode without tuning, native kaldi-native-fbank preprocessing and CTC decoding. Neural CPU fallback is rejected explicitly. Audio preprocessing and token decoding remain on CPU. Segments are limited to 20 seconds; long dictation uses the existing segmentation pipeline. Speculative repeated previews are disabled for this adapter.

No hosted converted artifact is assumed. Import the exact pinned `.mnn` file through Android's document picker; tokens are bundled, all files are size/hash checked before atomic installation. For the local A56 test the same verified files are staged via ADB. Existing GigaAM CPU remains independently selectable. Build preparation: `sh tools/prepare-asr.sh`.

An opt-in end-to-end device test is `GigaGpuSmokeTest`, built with `-PlacanTestRunner=org.lacan.asr.IsolatedAsrRunner` and run with `-e gigaGpuSmoke true`. It uses the public six-second WAV, checks reference text, cancellation and warm reuse across resized inputs.

### Variable-length regression

The first integrated 600-frame speech call matched the reference (1494 ms including frontend/CTC), but the next 200-frame silence call crashed in vendor OpenCL at MNN `ConvBufExecution::onExecute`. Source inspection identified stale pre/post kernels: `onResize` can switch from GEMM to ordinary convolution for shorter inputs without clearing kernels bound to previous buffers. `tools/giga-gpu/patch_mnn.py` pins the original source hash and clears these optional kernels at each resize. The same six-second / cancel / two-second silence / six-second reuse test is the regression gate. No silence bypass or fixed padding is introduced.

The resize regression passed on A56 after the patch: cold initialization 10.858 s, first six-second transcription 1.523 s, two-second silence 1.258 s, then six-second reuse 2.444 s for resize/transfer/inference/readback. Both speech outputs exactly matched the reference and silence returned empty. Every call observed 1101 OpenCL operations and zero foreign operations. Shape changes affect runtime; these figures are not a steady-state CPU/GPU comparison.

Cancellation additionally drains the OpenCL queue through `Tensor::wait` before returning or permitting cleanup. MNN 3.6.1's `runSessionWithCallBackInfo(..., sync=true)` does not itself implement that synchronization. A separate timed cancellation race followed by reset/resized reuse guards this lifecycle.

Final APK regression passed (`OK (1 test)`, 21.981 s), including timed cancellation and subsequent resized reuse. Cold initialization 10.350 s; six-second full transcription 1.513 s; two-second silence 1.260 s; resized six-second neural path 2.578 s. All completed calls reported 1101 GPU operations and zero foreign operations. APK SHA-256: `bcee27061e99d6155966feba72de3abdfcdfc27599b1b4eed84b0dfde5b23ccd`.
