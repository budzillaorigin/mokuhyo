# CLAUDE.md — Tsumugi

## What this is
A unified Japanese-learning app: SwiftUI iPhone app (ship target) + Android Compose scaffold, both on a shared Kotlin Multiplatform (KMP) core, with a self-hostable sync server and fully offline AI. Product spec lives in BRIEF.md — it is the source of truth. If BRIEF.md and this file disagree, BRIEF.md wins.

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
