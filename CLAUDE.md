# CLAUDE.md — Tsumugi

## What this is
A unified Japanese-learning app: SwiftUI iPhone app (ship target) + Android Compose scaffold, both on a shared Kotlin Multiplatform (KMP) core, with a self-hostable sync server and fully offline AI. Product spec lives in BRIEF.md, extended and overridden by BRIEF_V2.md — together they are the source of truth. If they and this file disagree, the briefs win (BRIEF_V2.md over BRIEF.md).

## Non-negotiables
1. **No subscription or metered third-party service is ever required** for any feature. AI (LLM, STT, TTS, grammar correction, pronunciation scoring) runs on-device by default. Users MAY optionally point the app at their own OpenAI-compatible endpoint (Ollama, LM Studio, llama-server, vLLM). No feature may silently degrade to "needs cloud."
2. **Offline-first.** Every screen works with airplane mode on, except the explicit Sync, WaniKani/Bunpro import, and model-download screens.
3. **Business logic lives in `shared/` (KMP).** Platform code (SwiftUI/Compose) is views, navigation, audio I/O, permissions, and platform ML bindings only. If you find yourself writing SRS math, dictionary lookup, deinflection, scoring, or sync merge logic in Swift or Kotlin/Android, stop and move it to `shared/`.
4. **Android must keep compiling.** Every PR runs `./gradlew :androidApp:assembleDebug` in CI. The Android app can be feature-incomplete but never broken.
5. **Licenses.** Only permissive (MIT/Apache-2/BSD) or attribution-share-alike data licenses (CC BY / CC BY-SA, EDRDG) are allowed. No GPL/AGPL code linked into the app binaries. No copying of any reference app's content (WaniKani mnemonics, Bunpro grammar write-ups, NativShark scripts, JLPT/DLPT official items). Record every third-party asset in `docs/LICENSES.md` with its license and attribution text; the app has a Licenses screen that renders that file.
6. **User content stays the user's.** Everything is exportable (JSON + `.apkg`). Sync is optional and self-hostable. Third-party API keys (WaniKani, Bunpro, Notion, custom LLM endpoints) are stored only in the platform keychain/keystore and never leave the device except to the service they belong to.
7. **Japanese text handling.** Never NFKC-normalize dictionary keys blindly (it breaks 〜 vs ~, half/full-width kana, ゔ). Store text as NFC. Compare readings in hiragana (katakana→hiragana fold) for search only. Use mora, not characters, for anything phonological.
8. **Tests before features for the core:** the SRS engine, tokenizer/deinflector, JLPT/DLPT scoring, and sync merge each have a test suite in `shared/src/commonTest` that must pass before UI is built on them.
9. **No fake data in production paths.** Content packs are built by reproducible scripts in `tools/`. If a script can't produce something yet, the feature shows an honest empty state, never lorem-ipsum Japanese.
10. **AI-generated content is labeled.** Any sentence, passage, test item, or explanation produced by an LLM carries `source = "llm"` in the data model and a small "AI-generated" badge in the UI. Human-verified items flip to `source = "verified"` only via the review tool in `tools/`.

## Conventions
- Kotlin 2.x, KMP with `iosArm64`/`iosSimulatorArm64`/`android` targets, SQLDelight for the shared DB, kotlinx-serialization, kotlinx-coroutines, Ktor client. Swift 5.10+/6 with strict concurrency, SwiftUI, iOS 17+ minimum. Android: Jetpack Compose, minSdk 26.
- Package/module names: `app.tsumugi.*`. Bundle IDs: `app.tsumugi.ios`, `app.tsumugi.android`.
- Commit messages: imperative, scoped (`srs: implement FSRS-6 scheduler`).
- Do not add a dependency without adding its license to `docs/LICENSES.md` in the same commit.
- Every phase in BRIEF.md §12 ends with a written checkpoint in `docs/PROGRESS.md` (what was built, what was deferred, how to run it) and a stop for owner review.

## How to work in this repo
- Start each session by reading `docs/PROGRESS.md` to find the current phase.
- Prefer small, verifiable increments. Run the shared tests (`./gradlew :shared:allTests`) before declaring a task done.
- When a spec detail is missing, choose the simplest option consistent with the non-negotiables, record the decision in `docs/DECISIONS.md`, and continue. Ask the owner only when the choice is irreversible (data model, sync protocol, licensing).

## v2 additions (from BRIEF_V2.md)

11. **Progress never regresses.** A learner's path level, unlocks, and passed levels are persisted facts, never re-derived from live SRS stability. Anything that could lower a level, hide an unlocked lesson, or make two synced devices disagree about progress is a P0 bug.
12. **The review log is append-only and tombstoned.** No physical deletes of review rows once they may have synced. Undo = tombstone. `SyncMergeTest` must cover undo-after-push.
13. **Every network call has a timeout.** Ktor clients set `HttpTimeout` (connect 5 s, request 30 s for chat, 120 s for downloads); platform calls (VOICEVOX, URLSession) set explicit bounds. No call to a user-configured endpoint may block the UI thread or run without a cancellation path.
14. **Secrets go only to the host they belong to.** One key per endpoint. Never reuse the LLM key for STT/TTS endpoints.
15. **Heavy work never runs on the main actor.** Hashing, file copies, pack installs, model loads, tokenizing a document, and full-table stats scans run on `Dispatchers.IO` (Kotlin) or a detached task (Swift) and report progress. A screen must never show a spinner with no progress and no cancel for more than ~2 s.
16. **Device-local configuration is not synced.** Model choice, endpoint URLs, audio engine, and anything tied to hardware or a LAN live in a per-device settings table. Only learner preferences sync.
17. **Fix → regression test → then feature.** Every P0/P1 item in BRIEF_V2 §4 lands with a test that fails before the fix and passes after. Phase 9 ends with the test names listed in `docs/PROGRESS.md`.
18. **Phases stop for owner device QA.** After each phase in BRIEF_V2 §8, update `docs/PROGRESS.md` and `docs/QA.md`, push, and stop. Do not continue on a "run everything" instruction from an earlier session.
19. **Content volume is a feature.** New content packs state their counts in PROGRESS and are built by scripts that can be re-run to add more. AI-drafted content ships only through `tools/items/review.py` gates, badge on until reviewed.
20. **Audio is pre-rendered where the app promises consistency.** Exam listening, dialogue drills, minimal pairs, and pitch-accent tests use a pre-rendered audio pack built with VOICEVOX at build time (see §5.6 / D-030 revision), with system TTS only as a fallback for user-created content.
