# Offline speech models

The catalog offers thirteen choices. Downloads are separate from the APK. Sizes below are decimal MB of model files, not peak RAM usage. Installed models work offline; downloading requires a connection. Existing `whisper-tiny`, `whisper-base`, and `gigaam-v3` identifiers are preserved.

| Model | Download | Language scope | Intended comparison |
| --- | ---: | --- | --- |
| Whisper base ONNX int8 | 160.6 MB | Multilingual, including Russian and English | Same sherpa-onnx runtime as GigaAM; CPU; phone compatibility/quality verification pending |
| Whisper small ONNX int8 | 375.5 MB | Multilingual, including Russian and English | Larger ONNX comparison; CPU; phone compatibility/quality verification pending |
| Whisper medium ONNX int8 | 946.1 MB | Multilingual, including Russian and English | Experimental; sherpa CPU; phone decoding/performance unverified |
| Whisper large-v3 ONNX int8 | 1775.8 MB | Multilingual, including Russian and English | Full large-v3, 128 mel bins; experimental; high memory/CPU requirements |
| Parakeet TDT 0.6B v3 ONNX int8 | 670.5 MB | 25 languages, including Russian and English | Experimental; automatic language selection; punctuation/capitalization; CC BY 4.0 |
| Whisper tiny Q5 · legacy whisper.cpp | 32.2 MB | Multilingual, including Russian and English | Smallest download; baseline |
| Whisper base Q5 · legacy whisper.cpp | 59.7 MB | Multilingual, including Russian and English | Compact baseline |
| Whisper small Q5 · legacy whisper.cpp | 190.1 MB | Multilingual, including Russian and English | First larger model to try on the same recording |
| Whisper medium Q5 · legacy whisper.cpp | 539.2 MB | Multilingual, including Russian and English | Experimental; substantially more CPU work and RAM than tiny/base |
| Whisper large-v3-turbo Q5 · legacy whisper.cpp | 574.0 MB | Multilingual, including Russian and English | Experimental; compare speed and transcription against medium on the target device |
| GigaAM v3 e2e CTC int8 | 319.9 MB | Russian; English/mixed speech unverified in this app | Existing compact GigaAM, with punctuation/text normalization |
| GigaAM v3 e2e CTC FP32 | 886.0 MB | Russian; English/mixed speech unverified in this app | Experimental comparison without int8 weight quantization; more storage and RAM |
| GigaAM Multilingual 600M int8 | 591.6 MB | Russian and English, plus Kazakh, Kyrgyz, Uzbek | Experimental larger GigaAM; lowercase output without punctuation |

Start with short recordings and compare the same Russian and mixed Russian/English phrases. Larger models and removing quantization do not guarantee better results on a specific recording. No app-specific accuracy benchmark supports such a claim yet. Medium, turbo, FP32, and GigaAM 600M can be slow or exceed available memory on a phone; file size alone is not a RAM requirement. Existing Q5 models use the previous whisper.cpp experiment and remain available with their original installed IDs/downloads. The current ONNX alternatives use sherpa-onnx. Full large-v3 is distinct from large-v3-turbo; the ONNX large choice is the full model.

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

`whisper-base-onnx`, `whisper-small-onnx`, `whisper-medium-onnx`, and `whisper-large-v3-onnx` use the already bundled sherpa-onnx 1.12.32 runtime and CPU provider, with separate int8 encoder and decoder files and a base64 token table. Existing whisper.cpp choices and installed model IDs remain available. Choosing a model selects its adapter automatically. These exports are multilingual (`base`, `small`, `medium`, and full `large-v3`, not `.en`). There is no evidence yet that this runtime improves accuracy or speed on the user's phone; compare the same audio.

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

## Added ONNX models in 0.1.3

Medium and full Large-v3 use the same Whisper adapter as base/small, with 80 and **128** mel bins respectively, 16 kHz audio, and transcription/greedy decoding. The pinned runtime also reads the Whisper frontend dimensions from model metadata. The int8 exports are self-contained; FP32 external `.weights` files are not required. Large-v3 is not turbo or distilled. New model downloads are optional and separate from the APK. Nothing here establishes successful decoding, speed, memory use, or dictation quality on the A56: only catalog/configuration contracts have been checked for these additions. No large model was downloaded and no native inference was run during this change.

### Parakeet TDT adapter

`parakeet-tdt-0-6b-v3` uses a dedicated `sherpa-transducer` adapter and the existing sherpa-onnx **1.12.32** AAR. Its three int8 ONNX files are passed to `OfflineTransducerModelConfig` (encoder/decoder/joiner), plus `tokens.txt`; `modelType` is `nemo_transducer`, greedy search and CPU, with 128-bin/16 kHz frontend configuration. The [pinned v3 exporter](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py) explicitly writes `feat_dim=128` metadata. It is not loaded as Nemo CTC or Whisper. The [pinned Kotlin API/example](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/kotlin-api/OfflineRecognizer.kt) explicitly includes this v3 package; the [pinned Nemo implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/csrc/offline-transducer-nemo-model.cc) handles TDT durations/model metadata. These are interface/source checks, not native runtime verification.

Parakeet is an offline recognizer operating on the app's captured segments. It determines language from audio; the app's forced Russian/English setting does not constrain this model. Cancellation discards a pending native result; synchronous native inference must finish before release. The [NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) describes 25 supported languages including Russian/English and punctuation/capitalization. Its published benchmarks and GPU memory guidance are not Android results.

Parakeet TDT 0.6B v3 is by **NVIDIA**, licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Source: [NVIDIA original weights/model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3); ONNX conversion and int8 quantization distributed by [csukuangfj](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78). This application downloads that converted/quantized export without further weight modifications. Whisper remains MIT; the existing runtime remains Apache-2.0. See bundled third-party notices for attribution.

### Immutable artifact metadata

Public metadata was verified on 2026-10-21. ONNX SHA256/lengths are publisher LFS assertions; token files were fetched at these revisions and hashed locally. App installation verifies every file against the catalog. Only metadata and small token tables were fetched for this work.

- Medium revision: [`8c31d28503847560985df21f90e14f0c736e075e`](https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-medium/revision/8c31d28503847560985df21f90e14f0c736e075e?blobs=true).
- Full Large-v3 revision: [`2a6507094dd6020d939d78e3f1834a1d06267fca`](https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-large-v3/revision/2a6507094dd6020d939d78e3f1834a1d06267fca?blobs=true).
- Parakeet int8 revision: [`2bda32ec70b097a55adaa07d9a7173915b43cc78`](https://huggingface.co/api/models/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/revision/2bda32ec70b097a55adaa07d9a7173915b43cc78?blobs=true).

| Artifact | Exact bytes | SHA-256 |
| --- | ---: | --- |
| `medium-decoder.int8.onnx` | 571059257 | `595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747` |
| `medium-encoder.int8.onnx` | 374196283 | `1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e` |
| `medium-tokens.txt` | 816730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |
| `large-v3-decoder.int8.onnx` | 1008265203 | `ebc6bfd88e162a46cb3edee8a7e727e1dcbc65cabecb19e2573695e4d495e1af` |
| `large-v3-encoder.int8.onnx` | 766671985 | `d531cf17248acc43e8c09b472a0877055e770877857a5332fc1304b36534ec85` |
| `large-v3-tokens.txt` | 816730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |
| `decoder.int8.onnx` | 11845275 | `179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e` |
| `encoder.int8.onnx` | 652184281 | `acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247` |
| `joiner.int8.onnx` | 6355277 | `3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3` |
| `tokens.txt` | 93939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` |

Totals: Medium **946072270** bytes; full Large-v3 **1775753918** bytes; Parakeet **670478772** bytes. Download size is not peak RAM use. JVM tests validate artifact routing, frontend dimensions, and path/configuration contracts; they do not load native models.

## Vosk small RU CPU в 0.1.4

Vosk Android 0.3.75, модель small-ru-0.22 (Apache-2.0). Русская модель без пунктуации; язык определяется весами, смешанная речь не гарантируется. Архив 46,236,750 байт, распаковка 91,289,240 байт. SHA-256 `961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4`; mirror revision `e7ac2109d134b5f2404ba95389b2fb51916d4cab`. [Результат CPU smoke-test и ограничения](vosk-cpu-probe.md).

## Parakeet GPU в 0.1.5

`parakeet-tdt-gpu` — отдельный вариант каталога через LiteRT 2.3.0: около 615 МБ, автоматический русский/английский, GPU-only запуск. Это другой экспорт весов, ONNX-файлы для него не подходят. При несовместимости драйвера следует вручную выбрать прежний Parakeet CPU. [Архитектура, закреплённые артефакты и проверки на A56](parakeet-gpu-integration.md).
