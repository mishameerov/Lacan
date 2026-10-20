# Lacan model comparison and Material 3 plan

> Execution: existing user authorization for implementation, sized subagents and emulator-tested APK; no additional approval gate. Parent integrates and verifies. No GitHub release.

Goal: produce an installable prototype with stronger local models, complete audio coverage, independent automatic language detection, Material 3 light/dark UI and a full-bleed adaptive icon.

Architecture remains unchanged: bounded private PCM spool, serialized local engines, accessibility insertion and history. New catalog entries retain immutable revisions and SHA256. Shared preview/final Whisper inference stays context-free; automatic language must not be cached from previews.

- [x] Fix segmentation using energy only to select boundaries, minimum 8s before ordinary pause cuts, maximum20s with750ms overlap, flush all nonzero short tails. Regression tests cover quiet audio, pauses, ten-minute bounded coverage.
- [x] Remove Whisper automatic-language cache and retain positive microphone read at stop. Verify Russian/public fixture and language isolation across calls.
- [x] Add smallQ5/mediumQ5/turboQ5 and GigaAMfp32 catalog entries; adapt Giga file selection. Research multilingual Giga and document compatibility limits.
- [x] Replace custom UI with Material3 Scaffold/navigation/semantic dynamic colors; persist System/Light/Dark. Verify both appearances and theme persistence in emulator.
- [x] Remove adaptive foreground inset so Android crops artwork. Bump versionCode for in-place update.
- [x] Build unit/lint/APKs; reproduce language regression and verify light/dark UI, review changes, prepare APK and documentation. Heavy ASR testing explicitly deferred to A56 at user request; record unverified models.
- [x] Publish source branch to GitHub through user-authorized GitHub CLI, no release or release asset.

Ownership: material_ui owns ui/*; models_v2 owns ModelCatalog.kt, catalog test and docs/model-options.md; parent owns all other edits. audio_audit read-only. Reviews: no lost quiet speech; preview cannot set final language; large models remain serialized/bounded; theme/dialog readable; model paths use validated catalog; source publication excludes weights/audio/keys.

Execution notes: Independent audio audit identified omission, cached language, stop tail and unoverlapped quiet boundary; fixes reviewed and core/native regression tests added. GitHub CLI authenticated by user; tokens stored through CLI, never in source. New heavy-model decoding remains unverified by explicit user choice.
