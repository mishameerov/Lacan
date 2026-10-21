# Whisper Medium ONNX: performance investigation

2026-10-21, physical Galaxy A56. The reported slow model is Medium ONNX, not legacy whisper.cpp.

## Confirmed application behavior

`SherpaWhisperEngine` uses sherpa-onnx 1.12.32, int8 encoder/decoder weights, greedy decoding, CPU provider and four threads. No GPU execution is requested by this adapter. Whisper's sequence-to-sequence decoder differs from GigaAM CTC and Parakeet TDT; comparable file sizes do not imply comparable work.

The runtime previously made disposable rolling preview calls after queue timeouts, then decoded the same audio again in authoritative segments. Native calls are serialized and Sherpa calls cannot be aborted mid-inference. Both Whisper adapters now disable these speculative calls through `allowsSpeculativePreviews`; completed segments still update the bubble, and all captured speech remains eligible for final decoding. Other engines retain their preview behavior. The call path is removed; a numerical end-to-end stop-latency improvement has not yet been measured.

## Controlled short probe

`WhisperPaddingProbeTest` uses the installed hash-verified Medium ONNX weights and the first six seconds of the public `gigaam-example.wav`, with forced Russian and four CPU threads. A plain Android Application under `IsolatedAsrRunner` prevents Lacan's eager model warmup from allocating a second model. User settings are not changed. Each setting uses a new recognizer, in the order below, with no warmup or repeated sampling.

| Tail feature frames | Initialization | Feature acceptance | Decode |
| --- | --- | --- | --- |
| 1000 (existing default, about 10 seconds) | 5.534 s | 0.068 s | 14.382 s |
| 200 (about 2 seconds) | 5.767 s | 0.067 s | 15.638 s |

Both calls returned Russian text. The shorter tail returned more of the utterance and a truncated end word; the default stopped earlier. This single sequence does not establish an optimal setting, equal accuracy or a speedup. Production tail padding remains unchanged. Native decoding alone took longer than the audio, independently of the application's now-removed preview overhead.

Reproduce (physical device only, no model downloads):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PlacanTestRunner=org.lacan.asr.IsolatedAsrRunner
# Install app + test APK, then use the selected ADB transport:
adb -t TRANSPORT_ID shell am instrument -w \
  -e class org.lacan.asr.WhisperPaddingProbeTest -e whisperPaddingProbe true \
  org.lacan.test/org.lacan.asr.IsolatedAsrRunner
```

Report: app-private `files/test-fixtures/whisper-medium-padding-probe-result.json`. The runner property defaults to the normal AndroidJUnitRunner when omitted, preserving other tests.

## Noise and hallucinations

The current segmenter uses energy to choose cuts and retains nonzero audio. It is not a speech detector. Raising an amplitude threshold would risk losing quiet speech without reliably excluding music. The original [Whisper transcription implementation](https://github.com/openai/whisper/blob/main/whisper/transcribe.py) combines no-speech probability with decoding confidence; those Python options are not exposed by our installed Sherpa Kotlin Whisper configuration. There is no equivalent app flag that reliably extracts only the speaker's voice.

A conservative speech VAD before recognition is the next appropriate experiment, with pre/post context and tests for quiet speech, noise, music and speech over music. It can avoid decoding non-speech, but is not source separation and cannot guarantee no hallucinations. No new volume gate, text blacklist or VAD is silently introduced in this change.

The upstream [versioned Sherpa implementation](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.32/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h) documents the padding/termination tradeoff. Legacy whisper.cpp has separate fallback and CPU instruction configuration; these do not explain this Medium ONNX measurement and were not changed.
