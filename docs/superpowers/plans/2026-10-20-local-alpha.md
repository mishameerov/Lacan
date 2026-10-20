# Lacan local Android alpha — Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Independent bounded packages may be delegated under the user's explicit authorization; parent owns integration. Steps use checkbox syntax.

**Goal:** Build an installable arm64 debug APK for local testing of on-device Whisper and GigaAM dictation, overlay controls, insertion and history.
**Architecture:** One Android app and a pure Kotlin core. AudioRecord writes bounded PCM windows to a private spool; a serialized worker loads one ASR model and consumes mandatory segments before optional previews. Accessibility provides current-target input; a short visible launch activity starts the microphone foreground service where required.
**Tech Stack:** Kotlin 2.2.21, AGP 8.13.2, Gradle 8.13, Compose, Room, coroutines; whisper.cpp 1.8.7 and sherpa-onnx 1.12.32. CPU arm64-v8a; compile/target API 36, min API 33.
**Spec:** docs/superpowers/specs/2026-10-20-lacan-architecture.md

## Global Constraints
- Android 13 / API 33 minimum. Galaxy A56 is primary physical target; connected during execution, Android 17 / API 37.
- PCM mono 16 kHz/16-bit. Maximum recording 600 seconds; no audio in history.
- Both Whisper and GigaAM v3 e2e CTC must be runnable in this local alpha; mixed/English path uses multilingual Whisper.
- No cloud ASR, analytics or automatic clipboard changes. Network only for explicit model downloads.
- Preview is provisional; only final result is delivered once to the current suitable field.
- Stop on screen off/lock, suppress automatic insertion; cancel discards session. History cap 100.
- Model loaded independently from sessions; one active model; GPL-3.0-only project, preserve third-party notices.

## Review Focus
- Cancellation during a slow native call must reject late text and must never free an in-use native model (Tasks 1, 3, 4).
- Focus switching/password/self fields and uncertain delivery must not replace unrelated text or duplicate input (Task 4).
- Silence, repeated words, forced chunk boundaries and a final short tail must preserve chronological coverage (Tasks 1, 3).
- Download interruption/hash mismatch must not expose a partial model; disk failures must close recording (Tasks 2, 4).
- Screen lock, revoked permissions and process death must stop audio and retain only already recognized text (Tasks 4, 5).

## File ownership and interfaces
- Parent: Gradle/build files, app manifest/resources, runtime, Android audio capture, access/overlay, history, tests/instrumentation, docs, packaging.
- Core worker: core/src/{main,test}/kotlin/org/lacan/core only.
- ASR worker: app/src/main/java/org/lacan/{asr,models}, app/src/main/cpp, tools/prepare-asr.sh, THIRD_PARTY_NOTICES.md, model catalog assets only.
- UI worker: app/src/main/java/org/lacan/ui/LacanScreen.kt only; contract file is parent-owned.

### Task 1: Build scaffold and bounded audio/text core
**Files:** root Gradle files, core/build.gradle.kts, core/src/{main,test}/kotlin/org/lacan/core/*.
**Interfaces:** `AudioSpool(file: File)` with append(ShortArray,count), sampleCount:Long, read(start:Long,end:Long):FloatArray, close/delete; `Segmenter.accept(samples:ShortArray,count:Int):List<AudioSegment>`, finish():List<AudioSegment>, `AudioSegment(start:Long,end:Long,overlapSamples:Int)`; `TranscriptAssembler.append(text:String,overlap:Boolean):String`, text:String; `SessionGate.start():Long?`, stop(id), cancel(id), accepts(id), finish(id).
- [x] Write core tests for ten-minute cap/coverage, silence and short tail, exact PCM roundtrip, limited overlap dedup preserving repeats, stale-session rejection.
- [x] Run :core:test and observe RED; implement pure Kotlin behavior; run GREEN.
- [x] Verify scaffold resolves plugins and create pinned Gradle wrapper. Include scaffold/core in the integrated local-alpha commit.

### Task 2: Verified model catalog and native engines
**Files:** asr/*, models/*, cpp/*, tools/prepare-asr.sh, THIRD_PARTY_NOTICES.md.
**Interfaces:** `ModelSpec(id,title,description,files)`; `ModelCatalog.models:List<ModelSpec>`; `ModelRepository(context).isInstalled(spec)`, suspend download(spec,onProgress), remove(spec), directory(spec):File; `AsrEngine : AutoCloseable { fun transcribe(samples:FloatArray,language:String):String; fun cancel(); }`; `EngineFactory.create(spec,directory,language):AsrEngine`.
- [x] Resolve immutable model revisions and SHA-256; verify GigaAM v3 e2e CTC int8 artifact and tokens; use multilingual Whisper tiny/base Q5.
- [x] Test hash mismatch/atomic publication behavior before implementing downloader; no remote executable code.
- [x] Implement JNI whisper adapter and sherpa adapter, preserve actual word boundaries and UTF-8 text. Cancellation cooperatively aborts Whisper; sherpa returns then session gate rejects stale results.
- [x] Compile arm64 native libraries and exercise real models on test audio in instrumentation. Record timing and actual output without claiming physical A56 performance.

### Task 3: Russian setup, models and history UI
**Files:** ui/LacanScreen.kt; parent ui/UiContract.kt and MainActivity.kt.
**Interfaces:** `@Composable fun LacanScreen(state:ScreenState, actions:UiActions)` using parent-owned immutable UI contract.
- [x] Build Russian permission explanations and direct settings actions, model download progress and select/remove, language selection, current status/start-stop/cancel, copy-only result and history view/delete/clear.
- [x] Show that GigaAM targets Russian and mixed English is not validated; show real model size.
- [x] Compile UI and verify on emulator; no extra low-value tests for static copy/colors.

### Task 4: Android dictation service, overlay and delivery
**Files:** runtime/*, audio/*, access/*, AndroidManifest.xml, res/xml/*, Android integration tests.
**Interfaces:** `AppRuntime` is the app-scoped owner, StateFlow state for UI/overlay; service starts/stops AudioCapture and runtime session; accessibility service exposes `deliver(text):DeliveryResult`.
- [x] Test target eligibility and session cancellation; exercise capture cancellation and screen-off policy in instrumentation. Review mandatory-before-preview ordering in integration (no separate scheduler unit test).
- [x] Capture PCM on independent thread to spool; serialize native inference, preview <=8 s every >=2 s, mandatory <=20 s chunks, stop at 600 s; never backlog raw arrays.
- [x] Start microphone FGS from visible Activity after explicit overlay tap. Return to previous app after successful start. Notification Stop/Cancel; no unattended mic restart.
- [x] Overlay tracks keyboard+field while idle, remains while recording/finalizing/result, supports drag and remembers normalized coordinates. Expanded panel has cancel/preview/stop; no focus theft.
- [x] Deliver once via API33 accessibility input method after revalidating target; no whole-field ACTION_SET_TEXT. Unverifiable result becomes Copy bubble; never send forms.
- [x] Stop and hide on lock, stop on revoked capture/control permission; clear temporary PCM on cancellation/completion/process restart.

### Task 5: History, recovery and model lifecycle
**Files:** history/*, runtime/*, app backup resources.
- [x] Test idempotent insert/cap100 and nonempty crash draft recovery in Room instrumentation.
- [x] Store stable draft text, retain interrupted results after restart, no automatic delivery replay; delete PCM leftovers.
- [x] Keep model warm between sessions; serialize selection/deletion with inference; release on explicit disable/idle memory pressure.
- [x] Disable platform backup/transfer, no transcript logging, no network calls from recording or engines.

### Task 6: Build, integration checks and local distribution
**Files:** README.md, docs/local-testing.md, artifacts/lacan-local-alpha.apk (ignored), tools/* as needed.
- [x] Run unit suite, lint, debug build; inspect merged manifest, packaged native ABI and signatures.
- [x] Install emulator, test permissions and real offline ASR for both models; exercise overlay insertion, fallback, cancellation and screen lock where environment permits. Record untested hardware cases honestly.
- [x] Independently review integrated change using appropriately sized reviewer, fix substantive defects, rerun affected tests.
- [x] Provide APK and precise A56 install/test instructions, model sizes and known limits. No public release/upload required for this local alpha.

## Execution decisions
- 2026-10-20: User explicitly requested plan and immediate execution. No additional plan approval gate.
- Work in the existing clean checkout on `feat/local-alpha`; separate files per agent. This keeps the local artifact in the user's project folder and avoids a worktree handoff.
- This is a local alpha, not proof that all first-release acceptance tests pass. Short-clip A56 ASR timings are measured; long-session thermals and broad editor compatibility remain unverified.
- GigaAM candidate: BahamutRU/GigaAM-v3-sherpa-onnx at b2176bb2acabc2a9983ae597e0a16cdb8ef33e77, e2e CTC int8, MIT upstream/export; must be tested before calling it functional.

## Verification record
- 19 JVM tests passed; lint: 0 errors, 28 warnings; arm64 debug APK built and signature checked.
- Real Whisper/GigaAM inference passed on offline API37 emulator and on Galaxy A56. See `docs/local-testing.md` for measured results and remaining manual acceptance work.
- Two independent integration review passes completed; late cancellation and notification-control issues corrected before packaging.
- The implementation uses an energy-based pause detector as an explicit alpha deviation from Silero; full first-release acceptance is not claimed.
