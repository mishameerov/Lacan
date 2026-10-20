# Offline speech models

The catalog offers ten choices. Downloads are separate from the APK. Sizes below are decimal MB of model files, not peak RAM usage. Installed models work offline; downloading requires a connection. Existing `whisper-tiny`, `whisper-base`, and `gigaam-v3` identifiers are preserved.

| Model | Download | Language scope | Intended comparison |
| --- | ---: | --- | --- |
| Whisper base ONNX int8 | 160.6 MB | Multilingual, including Russian and English | Same sherpa-onnx runtime as GigaAM; CPU; phone compatibility/quality verification pending |
| Whisper small ONNX int8 | 375.5 MB | Multilingual, including Russian and English | Larger ONNX comparison; CPU; phone compatibility/quality verification pending |
| Whisper tiny Q5 | 32.2 MB | Multilingual, including Russian and English | Smallest download; baseline |
| Whisper base Q5 | 59.7 MB | Multilingual, including Russian and English | Compact baseline |
| Whisper small Q5 | 190.1 MB | Multilingual, including Russian and English | First larger model to try on the same recording |
| Whisper medium Q5 | 539.2 MB | Multilingual, including Russian and English | Experimental; substantially more CPU work and RAM than tiny/base |
| Whisper large-v3-turbo Q5 | 574.0 MB | Multilingual, including Russian and English | Experimental; compare speed and transcription against medium on the target device |
| GigaAM v3 e2e CTC int8 | 319.9 MB | Russian; English/mixed speech unverified in this app | Existing compact GigaAM, with punctuation/text normalization |
| GigaAM v3 e2e CTC FP32 | 886.0 MB | Russian; English/mixed speech unverified in this app | Experimental comparison without int8 weight quantization; more storage and RAM |
| GigaAM Multilingual 600M int8 | 591.6 MB | Russian and English, plus Kazakh, Kyrgyz, Uzbek | Experimental larger GigaAM; lowercase output without punctuation |

Start with short recordings and compare the same Russian and mixed Russian/English phrases. Larger models and removing quantization do not guarantee better results on a specific recording. No app-specific accuracy benchmark supports such a claim yet. Medium, turbo, FP32, and GigaAM 600M can be slow or exceed available memory on a phone; file size alone is not a RAM requirement. Full-precision Whisper small is intentionally omitted to keep this a short, useful catalog; small Q5, medium Q5, and turbo Q5 offer distinct larger choices.

Whisper models without `.en` are multilingual. The pinned whisper.cpp **v1.8.7** download script lists `small-q5_1`, `medium-q5_0`, and `large-v3-turbo-q5_0`, so these use the existing Whisper runtime/model format. This establishes format support, not device performance or quality. See the [v1.8.7 model documentation](https://github.com/ggml-org/whisper.cpp/blob/v1.8.7/models/README.md), [v1.8.7 download script](https://github.com/ggml-org/whisper.cpp/blob/v1.8.7/models/download-ggml-model.sh), and [upstream Whisper model information](https://github.com/openai/whisper#available-models-and-languages).

## GigaAM alternatives investigated

The [official GigaAM repository](https://github.com/salute-developers/GigaAM) distinguishes the Russian v1/v2/v3 lines from the newer multilingual line. V3 exposes CTC, RNNT, e2e CTC, and e2e RNNT; the e2e variants add punctuation and text normalization. These are decoder/output variants of a similar-size acoustic model, not a GigaAM equivalent of Whisper medium/large. The catalog adds the same e2e CTC model in FP32 so users can compare int8 quantization without changing the decoder.

The [pinned sherpa-compatible export](https://huggingface.co/BahamutRU/GigaAM-v3-sherpa-onnx/tree/b2176bb2acabc2a9983ae597e0a16cdb8ef33e77) contains CTC and RNNT exports, both FP32 and int8 encoders. RNNT requires separate encoder, decoder, and joint files plus its own tokens and a transducer configuration. The app's current Nemo CTC adapter cannot load those as a single CTC ONNX file, so RNNT is not listed as a working choice. A future addition needs an explicit transducer adapter and a real decode/cancellation/memory check.

The official repository also announces **GigaAM Multilingual 220M and 600M** models (`multilingual_ctc` and `multilingual_large_ctc`), including Russian and English; it describes English performance as moderate. See [GigaAM Multilingual](https://huggingface.co/ai-sage/GigaAM-Multilingual). A [sherpa-compatible export publisher](https://huggingface.co/fussraider/GigaAM-Multilingual-sherpa-onnx-ctc/tree/9f5a77e8975211abe8511693accd3a63ee1e9f43) supplies a self-contained **600M int8** ONNX file with the existing Nemo CTC interface, `is_giga_am=1` metadata, and 64-bin frontend. The catalog includes this larger multilingual variant as experimental. Its 71-token character vocabulary produces lowercase text without punctuation. The publisher's desktop speed/accuracy observations are not Android benchmarks. The export and [base model](https://github.com/salute-developers/GigaAM/blob/main/LICENSE) carry MIT licenses. The 600M FP32 export uses more than 2 GB of external weights and is omitted.

## Artifact provenance

Metadata was read from Hugging Face's model API on 2026-10-21. Downloads use immutable revisions, exact byte lengths, and LFS SHA-256 values. Token hashes are also verified by the download manager. Repository metadata is a publisher assertion; successful installation verifies the downloaded bytes against it.

- Whisper revision: [`5359861c739e955e79d9a303bcbc70fb988958b1`](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1); [metadata API](https://huggingface.co/api/models/ggerganov/whisper.cpp/revision/5359861c739e955e79d9a303bcbc70fb988958b1?blobs=true).
- GigaAM v3 export revision: [`b2176bb2acabc2a9983ae597e0a16cdb8ef33e77`](https://huggingface.co/BahamutRU/GigaAM-v3-sherpa-onnx/tree/b2176bb2acabc2a9983ae597e0a16cdb8ef33e77); [metadata API](https://huggingface.co/api/models/BahamutRU/GigaAM-v3-sherpa-onnx/revision/b2176bb2acabc2a9983ae597e0a16cdb8ef33e77?blobs=true).

- GigaAM Multilingual export revision: [`9f5a77e8975211abe8511693accd3a63ee1e9f43`](https://huggingface.co/fussraider/GigaAM-Multilingual-sherpa-onnx-ctc/tree/9f5a77e8975211abe8511693accd3a63ee1e9f43); [metadata API](https://huggingface.co/api/models/fussraider/GigaAM-Multilingual-sherpa-onnx-ctc/revision/9f5a77e8975211abe8511693accd3a63ee1e9f43?blobs=true). The two files under `large/` are stored locally as `gigaam_multilingual_large_int8.onnx` and `gigaam_multilingual_large_tokens.txt`.

| Added artifact | Exact bytes | SHA-256 |
| --- | ---: | --- |
| `ggml-small-q5_1.bin` | 190085487 | `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb` |
| `ggml-medium-q5_0.bin` | 539212467 | `19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f` |
| `ggml-large-v3-turbo-q5_0.bin` | 574041195 | `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2` |
| `gigaam_v3_e2e_ctc.onnx` | 885950432 | `1f3a714b76877724eda494d77e7ed0d15f1c003c60928c9cb03f21d81dc68cc5` |
| `gigaam_v3_e2e_ctc_tokens.txt` (shared with int8) | 2006 | `f8eb9b115e2748db9c40a5897cae11dd0678cc0b40fd7e25f8c43b3bf28715e4` |
| `large/model.int8.onnx` | 591645642 | `6b6f195026b0f90721cd4593c664becf009a71131550b664eec71446ec351c81` |
| `large/tokens.txt` | 391 | `9b5df7987cb4ca52c1a468649ce897fab1cd182067416e29fef49dfaa7a856c2` |

## Verification status for prototype 0.1.1

All eight catalog entries have pinned metadata; newly downloaded weights passed SHA256 checks. Runtime status differs:
- Existing tiny and GigaAM v3int8 had passed Android tests in the previous prototype. The new auto-language regression passed with tiny after removing language caching.
- SmallQ5 loaded and recognized most of the public Russian poem, but omitted its final short phrase; this is not a full accuracy pass. The compatibility smoke assertion now checks distinctive words in the main passage rather than requiring the clipped final phrase; it is not an accuracy acceptance test. English small was not reached in that initial run.
- Medium test was explicitly stopped by the user to reduce computer load. Its instrumentation reports “Process crashed” because the test process was deliberately stopped, not because a native crash was diagnosed.
- Medium, turbo, GigaAM FP32 and Multilingual600M remain experimental and require actual A56 decoding/performance/quality validation. The user requested deferring heavy emulator inference to the phone. No claim is made that these models already work well on A56.

## Whisper through sherpa-onnx

`whisper-base-onnx` and `whisper-small-onnx` use the already bundled sherpa-onnx 1.12.32 runtime and CPU provider, with separate int8 encoder and decoder files and a base64 token table. Existing whisper.cpp choices and installed model IDs remain available. Choosing a model selects its adapter automatically. These exports are multilingual (`base` and `small`, not `.en`). There is no evidence yet that this runtime improves accuracy or speed on the user's phone; compare the same audio.

The adapter uses Whisper transcription, greedy decoding, and 16 kHz input. Forced Russian/English is passed to the decoder; Auto passes an empty language so sherpa detects each window afresh. The [pinned upstream adapter](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h) truncates audio near 30 seconds; this app splits any unexpectedly long call into windows of at most 25 seconds. Runtime VAD normally supplies shorter chunks. Window boundaries can affect words and context. Native sherpa decode has no immediate abort API: cancellation discards the result and skips remaining windows, and release waits for the current native call.

Public file metadata was verified on 2026-10-21 through the [base metadata API](https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-base/revision/bb53ee204431c90d314c1cc08d28d23e5b7927cc?blobs=true) and [small metadata API](https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-small/revision/8f3c18b358db4d1f2fc1eae49d75cd20989e4309?blobs=true). ONNX hashes are the publisher's LFS SHA256 metadata. Both token files were fetched at these immutable revisions and hashed locally. Weight downloads are verified by the app before installation.

| ONNX artifact | Exact bytes | SHA-256 |
| --- | ---: | --- |
| `base-encoder.int8.onnx` | 29120534 | `0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11` |
| `base-decoder.int8.onnx` | 130672026 | `9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d` |
| `small-encoder.int8.onnx` | 112442483 | `4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9` |
| `small-decoder.int8.onnx` | 262226114 | `acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee` |
| `base-tokens.txt` / `small-tokens.txt` (each) | 816730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |

Base total: 160609290 bytes. Small total: 375485327 bytes. These are storage sizes, not peak memory requirements. The exporter repositories do not declare a separate model-card license; the underlying [OpenAI Whisper weights/code are MIT licensed](https://github.com/openai/whisper#license), and [sherpa-onnx is Apache-2.0](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/LICENSE). See the [official sherpa Whisper documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/index.html).

An opt-in `SherpaWhisperSmokeTest` decodes only the first six seconds of the public Russian GigaAM fixture on a physical device. It is a native compatibility check, not an accuracy/performance acceptance test. No heavy emulator or desktop inference was run for this addition.


### A56 check for 0.1.2

The complete base ONNX files were downloaded and SHA256 verified. A six-second public Russian fixture decoded successfully on Galaxy A56 with the new adapter (1925 ms inference in one run, excluding initialization). The returned text contains word errors: this verifies compatibility, not acceptable dictation quality or a controlled speed comparison. Small ONNX inference is still untested. The user's real-device feedback currently favors GigaAM v3 FP32 over the existing Q5 Whisper models.
