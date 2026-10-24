# Parakeet GPU in Lacan 0.1.5

The catalog now offers **Parakeet TDT v3 · GPU · экспериментальная** alongside the existing ONNX CPU model. It downloads about 615 MB independently of the CPU weights. Audio processing stays on the phone. No Google account or Play model delivery is used.

## Implementation

- LiteRT 2.3.0 and litert-gpu 2.3.0, GPU-only accelerator request, FP32 GPU computation with dynamically quantized INT8 weights. Audio preprocessing, token selection and text assembly remain CPU work.
- Stateful model: `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite` from litert-community/parakeet-tdt-0.6b-v3 revision `3ce205aa928f443f423214195cfe83ebe4802826`; 614,261,072 bytes, SHA-256 `334745b8bc7fd372b1c213516f0b6338bb827b1a2abb3e77ad35fe6fea5cd16b`.
- NVIDIA tokenizer revision `541d1f99c6b0c3cd0b11a95167540bb8edefd82b`; 1,159,960 bytes, SHA-256 `bd321b096832a3f270bd3b2a88823957920f1a5c5ada71114a26ea729d0cbe91`.
- Frontend and TDT decoding adapted from the Google AI Edge sample at `f1e415713f0354920c263c66e65ebd4b138c7e67`. All GPU compilation, buffer access, inference and disposal run on one dedicated worker. Warm resources survive between dictations.
- Five-second windows overlap by 0.52 seconds, with the stride aligned to the encoder timestamp grid. Whole words are assigned using token timestamps, dividing shared audio at its midpoint; the final partial window is retained. An unambiguous matching word in shared audio is reconciled when its timestamps differ by at most 240 ms. Repeated words within either raw overlap disable that match. This fixes the observed seam duplicate without global deduplication; inaccurate timestamps and short-window context can still omit or repeat words and require broader testing.
- Automatic language detection; the language setting does not force Parakeet's decoder. Russian and English are supported by the same model.
- GPU failure is reported explicitly. Select the ONNX CPU model manually if unsupported. No hidden CPU retry. Cancellation is checked between native calls; an in-flight native call cannot be forcibly interrupted safely.

## Build constraints

JLibrosa is downloaded with a pinned SHA-256; `tools/prepare-jlibrosa.py` removes bundled old JUnit/Hamcrest classes while preserving DSP code and license resources. The Play AI delivery transitive dependency is excluded: file-based LiteRT loading does not use it. No Google Play services dependency remains in the debug runtime graph.

The prototype temporarily uses the upstream sample's `-Xskip-metadata-version-check` because LiteRT 2.3.0 carries Kotlin 2.4 metadata while Lacan uses Kotlin 2.2.21. Build and device checks cover this combination; a coherent supported toolchain remains necessary before release. This is not a release build.

## Verification

Frontend golden tests compare every output value for two inputs against the pinned upstream implementation. Tokenizer decode was compared with Hugging Face tokenizers 0.23.3 for all 8,192 vocabulary IDs, 100 seeded random sequences and special-token boundary cases: zero mismatches. Helper tests cover window tails, duration logits and whole-word overlap ownership, including genuine repeated speech.

The opt-in `ParakeetGpuSmokeTest` uses public WAV fixtures, never the microphone. It exercises Russian across two windows, English after cancellation/reuse, and silence. Required files under the app's private `files/test-fixtures` directory:

- `test-models/parakeet-tdt-gpu/parakeet.tflite` and `tokenizer.json` (verified against the catalog before installation)
- `test-audio/gigaam-example.wav` and `test-audio/jfk.wav`

Run after installing the app and test APK:

```sh
adb -t TRANSPORT_ID shell am instrument -w \
  -e class org.lacan.asr.ParakeetGpuSmokeTest -e parakeetGpuSmoke true \
  org.lacan.test/androidx.test.runner.AndroidJUnitRunner
```

Native A56 logs confirm OpenCL delegation of all three model graphs: encode 1,790/1,790 nodes, decode 163/163 nodes, decode_1 63/63 nodes, one partition each. This is actual placement evidence for this phone/runtime/model combination, not a guarantee for other GPUs. Cold compilation can take around 20 seconds. Short-fixture timing excludes initialization and is not a controlled comparison against ONNX CPU. Long dictation, thermal load, memory pressure and other vendors remain to be tested.

### 2026-10-21 local validation status

The 0.1.5 app/test APKs build, 52 JVM tests pass, lint reports zero errors (30 warnings), and APK 16 KB zip alignment passes. Real six-second Russian GPU decoding completed before the seam correction (3.354–4.372 seconds, excluding compilation) but repeated a boundary word. The final correction has a regression fixture derived from captured token timestamps and passed helper tests/review. Both final APKs were installed successfully on A56 over Wi-Fi. The final physical regression was interrupted by ADB losing its route to the phone; its outcome, English/reuse/silence assertions and the post-test UI launch are **not yet confirmed**. Do not treat this as a completed end-to-end qualification.

Local APK SHA-256: `2b390032aa017bc4fe8e7a6d3df34e230fd85e5786ab5423529e9e4c9971d592`.
