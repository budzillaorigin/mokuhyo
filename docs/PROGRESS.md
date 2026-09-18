# Progress

Current phase: **Phases 0–3 complete. Continuing to Phase 4 (reading & writing).** The owner asked for all phases to run back to back, without per-phase review stops. CI (`.github/workflows/ci.yml`) builds packs and runs the shared, Android and iOS builds and tests on every push. Repo: https://github.com/budzillaorigin/tsumugi (private).

---

## Phase 3: Grammar + Today (2026-09-18)

### What was built
- **Grammar pack** (`content/packs/grammar.sqlite`), built by `tools/packs/build_grammar.py` from `tools/packs/grammar/n5.json`, `n4.json` and `n3.json`:
  - 448 points: 127 N5, 148 N4, 173 N3.
  - 3,486 Tatoeba example sentences with the construction's span marked.
  - Explanations are LLM-drafted and labelled (D-019). `tools/items/review.py` flips reviewed points to `verified`.
  - `aliases.json` maps other apps' titles onto our points for imports.
- **`GrammarService`**:
  - JLPT-ordered lessons.
  - **Cloze reviews:** exact answers are correct; another valid conjugation of the same construction (checked with the deinflector and patterns) is accepted as "close".
  - **Sentence-build reviews:** the sentence is split into phrase chunks.
  - **Bunpro-style ghost cards:** a miss spawns an extra card on short intervals, retired after two correct answers.
  - `ReviewSession` handles grammar cards alongside kanji and vocabulary.
- **`TodayPlanner` (BRIEF §5.6):**
  - **Blocks, in order:** reviews, then new kanji/vocab lessons, then grammar.
  - **Budget:** 10, 20, 40 or 60 minutes.
  - **Lesson count adapts:** to the budget, to yesterday's accuracy (fewer after a rough day), and to the review backlog (paused above 150 due).
  - **Phases** follow path level: Foundations, Core, Intermediate, Advanced.
  - **Weekly challenges** rotate every week.
- **Bunpro CSV import** (D-020).
- **iOS widgets** "Reviews due" and "Kanji of the day" (D-021).
- **UI on both apps:** Learn → Grammar (levels, points with the AI badge, point page with examples), grammar lessons, cloze and sentence-build review screens, Today plan with budget picker and weekly challenge, Bunpro import.

### Verified
- Shared tests (Android host) pass, including `GrammarServiceTest` (cloze checking, ghost spawn and retirement through a real review session), `TodayPlannerTest` and `BunproImporterTest`.
- The Android APK builds.
- iOS builds and tests run in CI (see the latest run).

### Deferred
- Grammar "production" reviews (translate from English, graded by the on-device LLM) arrive with the AI runtime in Phase 6.
- Textbook-order paths (Genki/Tobira chapter numbers) exist only where the source files carry them. A chapter-ordered path view is a small follow-up.

---

## Phase 2: SRS core + kanji path (2026-09-18)

### What was built
- **User DB** (`srs.sq`, `user.sq`, `meta.sq`): items, cards, the append-only review log, notes (myStory), settings, word lists, sessions and integrations. Ids are deterministic, which keeps sync convergent.
- **FSRS-6** (`Fsrs.kt`, ported from py-fsrs, MIT):
  - Matches py-fsrs's published interval sequence and memory-state numbers exactly.
  - Learning steps 10 min → 1 day.
  - Fuzz is derived deterministically, so replaying reviews gives the same result on every device.
  - An on-device optimizer (`FsrsOptimizer`, Adam with exact gradients) fits the parameters.
- **SRS layer:**
  - `SrsRepository`: lessons recorded as reviews (D-018), undo, imports, replay.
  - `AnswerChecker`: WaniKani-style, with typo tolerance, synonyms, and a hint when you give a valid reading of the wrong kind.
  - `UnlockTree`, and stages over stability (D-017).
- **Kanji path pack** (`kanji-path.sqlite`): 60 levels, 243 radicals, 2,599 kanji and 7,242 words, with our own keywords. `PathService` provides lessons, level progress, skip-to-level and manual unlock.
- **Sessions:** `ReviewSession` and `LessonSession` state machines shared by both apps:
  - Typed answers with a romaji→kana IME.
  - Missed cards re-asked as unrecorded practice.
  - Undo, wrap-up, and leech detection at 8 or more lapses.
- **Stats and reminders:** `StatsService` covers streaks with vacation mode, a heat-map, accuracy by kind, stage distribution and a 7-day forecast. `ReminderPlanner` plus local notifications on both platforms (D-022).
- **Integrations:**
  - Anki `.apkg` import/export: legacy and modern zstd formats, NihongoShark decks with myStory, and an import → export → re-import round trip.
  - imiwa word lists.
  - WaniKani API v2 import onto the path, with optional posting of reviews back. The token lives in the Keychain/Keystore; mnemonics are never stored.
- **UI on both apps:** Today, lessons, reviews, kanji path (level grid, item pages with stroke order and a myStory editor), dictionary "Add to reviews"/"Add to list", word lists, Me (stats, heat-map, vacation mode, settings, Licenses screen, Import & export).

### Verified
- About 190 shared tests pass on the Android host, including FSRS reference vectors, the optimizer on synthetic data, the Anki round trip, zstd vectors and WaniKani against a mock server.
- The Android APK builds.
- iOS: the app, the Kotlin/Native framework and the Swift tests (dictionary on the real pack, lookups under 5 ms on the simulator) passed in CI.

### Deferred
- **Your acceptance check** (import the NihongoShark deck and WaniKani token, then do a real review session on your iPhone) needs you and your devices.
- **Real-deck checks:** the NihongoShark detection uses field-name keywords and should be checked against your actual deck. The `.anki21b` fixture is synthetic.

---

## Phase 1: Data engine (2026-09-18)

### What was built
- **Content pack builders** (`tools/packs/`):
  - `build_dictionary.py`: JMdict, KANJIDIC2, KRADFILE/RADKFILE, JmdictFurigana, Kanjium pitch, and unofficial JLPT levels.
  - `build_kanjivg.py`: stroke paths.
  - `build_sentences.py`: Tatoeba sentences, a word→sentence index, and frequency ranks.
  - `build_all.py`: runs all three plus `manifest.json`.
  - Output: `content/packs/dictionary.sqlite`, 127 MB (46 MB compressed), built in about 1 minute.
  - The schema is `shared/.../dictionary.sq` (single source; DECISIONS D-008).
- **Japanese text engine** (`shared/jp`):
  - `Deinflector`: rule table, 441 test forms including chained auxiliaries and colloquial contractions.
  - `Conjugator`: 26 rows per verb or adjective, with a round-trip test against the deinflector (~800 checks).
  - `Kana`, `Romaji` (full conversion plus an incremental IME for answer fields), `Mora`, `Pitch` (all four patterns), `Furigana` (fallback aligner).
  - `strokes/SvgPath`: flattens KanjiVG path data into polylines for both apps.
- **Dictionary** (`shared/dictionary`): `DictionaryRepository`.
  - Search: kanji/kana/katakana-folded exact matches, deinflection checked against part of speech, prefix completion, romaji→kana, English reverse index, and sentence mode.
  - Details: entry view data (furigana, pitch, kanji breakdown, conjugations, Tatoeba examples), kanji detail (strokes, components, words), radical search with live narrowing.
  - `tokenize()` does dictionary longest-match (D-011).
- **Packs at runtime**: `PackInstaller` copies bundled packs into app storage on first launch, keyed on the manifest version (D-013). `AppGraph` is the composition root both apps hold.
- **Android**:
  - Application-scoped `AppGraph`, Material 3 theme, and Japanese locale on Japanese text so shared Chinese/Japanese characters use Japanese glyph shapes.
  - Per-tab back stacks, with global search from every tab.
  - Dictionary search, entry, kanji (animated stroke order) and radical search screens.
  - The Gradle `bundlePacks` task puts the pack in the APK (67 MB debug APK).
- **iOS**: the same screens in SwiftUI.
  - `NavigationStack` per tab, a global search button, and `Font.japanese` (Hiragino) for Japanese text.
  - Furigana, pitch diagram, and animated stroke order (`TimelineView` + `Canvas`).
  - A "Bundle Content Packs" build phase, and Swift tests for search plus the < 5 ms lookup budget.
- **CI**: a `packs` job builds the real pack once and hands it to the Android and iOS jobs, so both run tests on real data.

### Verified on the dev machine
- `./gradlew :shared:allTests`: 81 tests green on the Android host, including `RealPackSmokeTest` against the real pack.
  - 食べさせられなかった→食べる, cat→猫, kanji→漢字, sentence mode, 語 has 14 strokes, 言+口 radicals find 語.
  - About 2.4 ms per lookup on the JVM (D-015).
- `./gradlew :androidApp:assembleDebug` builds, with the pack bundled.

### Not yet verified
- iOS: Kotlin/Native compile, SKIE bridging of the new API, SwiftUI build, and Swift tests. The first macOS CI run will be the first compile.
- Android UI on a device: there's no emulator for Windows on ARM (D-016).

### Deferred
- Handwriting search and camera OCR: Phase 4, as the brief schedules.
- Word lists and "Add to SRS": Phase 2, along with the user-data model.
- JMnedict (names): not bundled yet. It adds ~40 MB for names only; to be revisited with on-demand packs.

---

## Phase 0: Foundations (2026-09-18)

### What was built
- Repo layout from BRIEF.md §3.1: `shared/`, `androidApp/`, `iosApp/`, `server/`, `tools/`, `content/`, `docs/`, `.github/workflows/`.
- `CLAUDE.md` copied verbatim from BRIEF.md §1.
- **Gradle KMP build** (`gradle/libs.versions.toml`, wrapper 9.7.1): `shared` targets Android (`com.android.kotlin.multiplatform.library`), `iosArm64` and `iosSimulatorArm64`, with SKIE, SQLDelight (`TsumugiDatabase`, placeholder `app_meta` table), kotlinx-coroutines, kotlinx-serialization and Ktor client.
- **Shared API:** `HelloUseCase` exposes a `Flow<Greeting>`. `SharedGraph` is the composition root both apps call. `Platform` and `DatabaseDriverFactory` are expect/actual on both platforms.
- **Android app** (`app.tsumugi.android`): Compose shell with the five tabs (Today · Reviews · Learn · Practice · Me). Today renders the shared greeting; the other tabs show "Coming soon."
- **iOS app** (`app.tsumugi.ios`, iOS 17+): `iosApp/Tsumugi.xcodeproj` with the same five-tab `TabView`. `TodayViewModel` (`@Observable`) iterates the SKIE-bridged flow. A "Compile Kotlin Framework" run-script phase builds `Shared.framework`. There's a shared scheme and one Swift Testing test.
- **CI** (`.github/workflows/ci.yml`) has three jobs:
  - Linux: shared tests plus `:androidApp:assembleDebug`, uploading the APK.
  - macOS: shared iOS simulator tests plus `xcodebuild test`.
  - Tools: `uv sync --locked` plus `ruff`.
- **tools/**: uv project (`pyproject.toml`, `uv.lock`) with `packs/`, `items/` and `models/` folders.
- Docs seeded: DECISIONS, LICENSES, CONTENT_PACKS, SYNC_PROTOCOL (stub), QA.

### Verified on the dev machine (Windows 11 ARM64)
- `./gradlew :shared:allTests`: `HelloUseCaseTest` passes on the Android host. iOS test tasks are skipped because Kotlin/Native can't target iOS from Windows.
- `./gradlew :androidApp:assembleDebug`: builds `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- `uv sync` and `uv run ruff check .` pass.

### Not yet verified (needs a Mac or the first CI run)
- iOS: Kotlin/Native framework build, SKIE output, the hand-written Xcode project, the SwiftUI app launch and the Swift test.
- Android app **launch**: the APK builds, but no emulator or device was available here.
- CI itself: the repo has no GitHub remote yet.

### Deferred
- Empty module packages (`domain/`, `srs/`, `jp/`, …) get created with their first code (Phase 1+). Git doesn't track empty folders.
- `TsumugiUITests` target: added with the first real UI flow (Phase 2).
- `server/`: placeholder README only; the Ktor server is Phase 5.

### How to run
```bash
# Shared tests + Android APK (any OS with JDK 21 + Android SDK 37)
./gradlew :shared:allTests :androidApp:assembleDebug
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk

# iOS (macOS with Xcode 16+ and JDK 21)
open iosApp/Tsumugi.xcodeproj   # pick an iPhone simulator, Run (Cmd-R), Test (Cmd-U)

# Python tools
cd tools && uv sync && uv run ruff check .
```

---

## Android parity checklist

| Feature | iOS | Android |
|---|---|---|
| Tab shell, global search | ✅ | ✅ |
| Today plan | ✅ | ✅ |
| Lessons / Reviews (kanji, vocab, grammar cloze/build, flashcards) | ✅ | ✅ |
| Kanji path (levels, items, myStory) | ✅ | ✅ |
| Grammar (levels, points, lessons) | ✅ | ✅ |
| Dictionary (search, entry, kanji, radicals, add to reviews/lists) | ✅ | ✅ |
| Word lists | ✅ | ✅ |
| Stats (streak, heat-map, stages, accuracy) | ✅ | ✅ |
| Import/export (Anki, imiwa, Bunpro, WaniKani) | ✅ | ✅ |
| Reminders | ✅ | ✅ |
| Widgets | ✅ | — (Glance later) |
| Settings / Licenses | ✅ | ✅ |
| Onboarding | — | — |
| Sync | Phase 5 | Phase 5 |
