# Isolated GigaAM v3 e2e CTC FP32 OpenCL probe

This standalone diagnostic has no microphone, network permission or automatic CPU retry. The qualified adapter is separately integrated in Lacan.
The test worker holds PARTIAL_WAKE_LOCK for at most180 seconds and releases it
in finally; visible Activity keeps its screen on during the probe. It does not
wake the screen, bypass lockscreen, or change system settings. The wake-lock timeout does not terminate native execution; stop a non-completing diagnostic with `adb shell am force-stop org.lacan.gigaprobe`.
One explicit invocation; CPU baseline requires a separate `backend=cpu` launch.

## Pinned source and conversion

MNN tag `3.6.1`, commit `d407447ed56c4121a11ccbd266dc184ca1ead0c2`
([upstream](https://github.com/alibaba/MNN/tree/d407447ed56c4121a11ccbd266dc184ca1ead0c2)).
Source license: Apache-2.0, retained in LICENSE-MNN.txt.
Default CPU backend also builds pinned KleidiAI1.16.0; its Apache/BSD notices
are retained in LICENSE-KLEIDIAI-*.txt. Archive SHA256:
`57b0a95c559ec6a4b1460bc742af600bfb6cdff8dc39154b376c25347d7b0891`.

```sh
git clone --depth 1 --branch 3.6.1 --filter=blob:none --sparse https://github.com/alibaba/MNN.git .tools/mnn/MNN-3.6.1
git -C .tools/mnn/MNN-3.6.1 sparse-checkout set 3rd_party cmake codegen include schema source express tools/train tools/audio tools/converter
.tools/giga-gpu-env/bin/python tools/giga-gpu/convert.py .tools/test-models/gigaam-v3-fp32/gigaam_v3_e2e_ctc.onnx /private/tmp/lacan-gigaam-fp32.mnn
```

Use MNN3.6.1 and numpy in the isolated environment. `convert.py` invokes `_tools`
directly: the standard MNN CLI imports an optional telemetry logger which can
attempt to install aliyun-log-python-sdk. No FP16 or int8 weight option is used.
The existing source ONNX SHA256 is
`1f3a714b76877724eda494d77e7ed0d15f1c003c60928c9cb03f21d81dc68cc5`.

## Build

Local Android SDK `local.properties`, NDK27.2.12479018, CMake3.22.1, JDK21,
Gradle8.13, AGP8.13.2, Kotlin2.2.21. `build.sh` limits Gradle and native jobs to2.
It applies a diagnostic-only hook to pinned MNN Pipeline::executeCallBack;
the reproducible patch is `instrument_mnn.py`. The hook records the scheduled
Execution::backend type before each execution, including internal operators.
The main app also applies `patch_mnn.py`, a pinned fix clearing stale convolution auxiliary kernels on resize. See the integration regression in `docs/gigaam-gpu-probe.md`.

```sh
tools/giga-gpu/build.sh
```

Output APK: `probe/build/outputs/apk/debug/probe-debug.apk`, package
`org.lacan.gigaprobe`. arm64 only; linker uses16KiB maximum page size.
OpenCL vendor library is optional in the manifest; no Internet permission.
MNN OpenCL backend uses Precision_High for FP32 storage and computation.
CPU ARM FP16 kernels disabled. GPU mode uses buffer storage and tuning disabled (`MNN_GPU_TUNING_NONE`).
The preceding diagnostic used `MNN_GPU_TUNING_FAST`; its long resize interval was contaminated by device sleep and is not evidence of a tuning hang. The final experiment uses NONE, preserving FP32 and buffer storage. To reproduce the previous
configuration, replace NONE with FAST in probe.cpp and rebuild; the report
label must be updated to match. Disabling tuning does not skip OpenCL program
compilation, weight transfer, or queue synchronization.

## Input/output contract

Place these in `/sdcard/Android/data/org.lacan.gigaprobe/files/`:

- `model.mnn`: exact converted FP32 model
- `features.f32le`: finite little-endian FP32, contiguous `[1,64,T]`
- `frames.txt`: decimal T,1..2500
- `length.txt`: valid feature count,1..T; usually T

No zero-input substitute: missing fixtures fail. Intent string extra `backend`
is `gpu` by default or explicit `cpu`. Session_Debug/Backend_Fix are used;
when requested GPU resolves to CPU at session level, invocation is refused.
Unsupported individual operators can use MNN's CPU backup and are reported.

Append-only `progress-<backend>.txt` and GigaGpuProbe logcat stage markers
identify model load, session create, resize, input copy, inference and readback
boundaries without speech data.

Writes `logits-<backend>.f32le`, `report-<backend>.txt`,
`execution-<backend>.tsv` (instrumented scheduled Execution backend), and
`placement-<backend>.tsv` (output tensor storage backend, supplemental evidence).
Backend IDs: CPU0, OpenCL3; other/unknown IDs require investigation.
Shape is recorded; expected reference6s fixture gives `[1,150,257]`.

Compare logits against exact ONNX reference, frame argmax and CTC text before
making accuracy or residency claims. Reports include cold loading, session
creation/resize and callback-instrumented synchronous invocation/readback times;
these are diagnostic timings, not production throughput measurements.
A successful run alone does not qualify GPU residency or speed across devices.


## Production engine resize and cancellation regression

`tools/prepare-giga-mnn.sh` applies `patch_mnn.py` to pinned MNN3.6.1. It clears
shape-specific convolution pre/post kernels before resize; upstream can switch
from GEMM to direct convolution while retaining those old kernel arguments.
The minimal patch preserves model inputs and FP32 weights; numerical checks are
still required after changing input lengths.

A subsequent cancellation test must cancel after native GPU dispatch has begun,
then wait for the cancelled transcription to return before starting the next
segment or closing. Confirm no text escapes cancellation, then reset the session
and transcribe a different duration, and finally close on the GPU worker.
Cancelling before entering native inference does not exercise this lifecycle.
MNN3.6.1 ignores the callback-run sync parameter; production therefore calls
public Tensor::wait on output/input before cancellation or error exits can skip
readback. The pinned OpenCL backend implements that wait using queue.finish().
This is a lifetime guarantee, not a hard timeout or an unsafe GPU interruption.
