# Progress

Current phase: **Phases 0–7 complete. Phase 8 (release hardening) in progress.** The owner asked for all phases to run back to back, without per-phase review stops. CI (`.github/workflows/ci.yml`) builds packs and runs the shared, Android and iOS builds and tests on every push. Repo: https://github.com/budzillaorigin/tsumugi (private).

---

## Phase 8: Release hardening

### Phase 8 (iOS) (2026-09-18)

#### What was built
- **Localization (en, ja):**
  - `iosApp/Tsumugi/Localizable.xcstrings` is a String Catalog with Japanese for the UI chrome: tabs, titles, buttons, section headers, settings, empty states, the exam/OPI disclaimers, onboarding, and VoiceOver labels.
  - Views that passed `String` chrome to `Text` now use `LocalizedStringKey` or `String(localized:)` (`SectionHeader`, badges, verdicts, score and feedback texts).
  - Learning content is not translated (D-037).
- **Accessibility:**
  - Spoken names for icon-only buttons.
  - Labels and values for the review answer field, session progress, path level cells, pronunciation scores, exam choices (selected trait), the question grid, the writing canvas (direct touch) and stroke order.
  - `japaneseSpeech()` sets a Japanese locale on Japanese content so VoiceOver reads it with a Japanese voice. DLPT questions and answers, which are English, keep the English voice.
  - Dynamic Type: the one fixed-size font (pronunciation score) and the fixed-height candidate strips now scale. Review grade buttons stack when they don't fit in one row.
  - Reduce Motion: stroke order is drawn complete, and the role-play chat scrolls without animation.
- **Share extension "Read in Tsumugi"** (new `TsumugiShare` target):
  - Accepts text and one web URL, and writes one JSON file per item to `share-inbox/` in the App Group container.
  - On launch or activation, the app imports pending items into the reader and opens the newest one (`Platform/ShareInbox.swift`).
  - The extension doesn't link the Kotlin framework.
- **Release settings:**
  - `ITSAppUsesNonExemptEncryption = NO`.
  - Privacy manifests for the app (UserDefaults CA92.1, file timestamp C617.1, system boot time 35F9.1), the widget and the share extension.
  - The camera, microphone and speech-recognition usage strings are confirmed in Debug and Release. No photo-library string is needed (PhotosPicker).

#### Deferred
- Labels that come from the shared core are still English: SRS stages, item kinds, Today blocks, exam-mode and section titles, and OPI phases. Onboarding goals are the exception, because the Kotlin labels double as catalog keys.
- Most status messages built in view models, such as import and sync results, are still English.
- Japanese App Store listing text and screenshots (**Owner**).
- The size audit, crash-free soak and TestFlight (see `docs/RELEASE.md`) need the owner's Apple account.
- None of this is compiled locally (Windows). CI's macOS job is the first build of the new target and strings.

#### How to check
- Run `docs/QA.md` → "Accessibility and polish" and "Share extension" on a device.
- Xcode → Product → Archive → Generate Privacy Report shows the three declared API reasons.

### Phase 8 (Android) (2026-09-18)

#### What was built
- **Localization (en + ja):** about 660 string keys, in `res/values/strings.xml` and `res/values-ja/strings.xml`.
  - Every screen's UI text is in resources: tabs, top-bar titles, buttons, section headers, settings, empty states, dialogs, onboarding, and the exam/OPI disclaimer. Status and error messages built in coroutines use `context.getString`.
  - Shared enums (kind, stage, rating, goal, phase) are mapped in `ui/Labels.kt`.
  - Android 13+ per-app language uses `res/xml/locales_config.xml`.
  - Learning content is not translated (D-038).
- **Accessibility:**
  - `ja()` / `JaText` (`ui/Accessibility.kt`) tag Japanese learner text with a ja-JP locale span, so TalkBack reads it in Japanese. Applied to dictionary headwords, readings and examples, review prompts and answers, reader text and titles, exam passages, stems and choices, grammar points, dialogue and role-play lines, and transcripts.
  - Content descriptions on every icon-only button (media controls, line replay, previous/next question, back).
  - Semantics on custom controls:
    - The review answer field says whether it wants the reading or the meaning.
    - Stage bars read as one node each ("Guru: 12").
    - The JLPT score bars, the pronunciation gauge and the sub-scores have spoken values.
    - Exam and listening choices are a radio group (`selectable` + `Role.RadioButton`), and the question navigator has answered/unanswered state.
    - The mic button and the drawing canvas have descriptions, and each reader word is one node read in Japanese.
    - Section headings are marked as headings, and the review verdicts are live regions.
  - Font scaling: fixed-size boxes that clipped at 200% are gone. That covers the stage-bar labels, the kanji-screen header, the dictionary label columns, the pitch-diagram morae, the tally tables and the pronunciation rows. Button rows that overflowed now wrap (`FlowRow`), and rating buttons are 2×2.
  - Touch targets are at least 48dp: list rows, the self-rating checklist, the endpoint model picker, the vacation toggle.
  - The stroke-order animation respects "Remove animations".
- **Share target:** "Read in Tsumugi" (`ACTION_SEND` text/plain) imports shared text, or fetches a shared URL, into the reader and opens it. "Look up in Tsumugi" (`ACTION_PROCESS_TEXT`) opens the dictionary search.
- **Release polish:**
  - Adaptive vector launcher icon (a spool of thread) with a themed-icon layer, and a matching notification icon.
  - `data_extraction_rules.xml` / `backup_rules.xml`: user data is backed up, while packs, models, scratch files and keystore-encrypted secrets are excluded.
  - `proguard-rules.pro` with JNI keep rules. The release build is still unminified.
  - APK size, language, sharing and backup notes in `docs/RELEASE.md` §7.

#### Deferred
- Platform error strings from `platform/AudioCapture.kt` and the native bridges are still English only. So are English strings produced by the shared core: Today block titles and details, reminder text, weekly-challenge titles.
- Some rare long notes stay as they are: technical pack-missing hints mention file paths.
- There's no in-app language picker. Android 13+ uses the system per-app setting. Older Android follows the system language.
- There's no Glance widget (unchanged).

#### How to run
```bash
./gradlew :androidApp:assembleDebug -Ptsumugi.native=false
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# Japanese UI: Settings → Apps → Tsumugi → Language → 日本語 (Android 13+), or set the phone to Japanese.
# Share target: share a web page or selected text from Chrome → "Read in Tsumugi"; select text → ⋮ → "Look up in Tsumugi".
```

---

## Phase 7: Exams (2026-09-18)

### What was built
- **Scoring core** (`shared/exam`), tested before any UI:
  - JLPT blueprints (`tools/items/jlpt_blueprints.json`: published sections, item counts, timings, pass marks).
  - Scaled scoring per score group with sectional minimums (D-029).
  - DLPT ILR estimator (70% sustained over 20 items, with a floor for lower levels, and "provisional" for short slices).
  - Form assembly that keeps each passage's questions together and prefers verified items.
  - Timed `ExamSession`: strict sections that close when time runs out, and listening that plays once in mock mode.
  - Attempts saved in `exam_attempt`, which syncs by union.
  - Importing your own question banks, with validation.
  - "Add missed items to SRS", for grammar points and dictionary words.
- **OPI simulator** (`exam/opi/OpiSession`):
  - Five phases: warm-up, level check, probe, role-play, wind-down.
  - With a model, the interview and the rating go through `opi_interviewer_turn` / `opi_rate`, with ACTFL levels mapped to ILR.
  - Without a model, questions come from the scripted bank per ILR level, the level adapts by answer length, and the learner rates themselves against the ILR checklist (D-031).
- **Content** (`exam.sqlite`, built by `packs/build_exam.py`):
  - JLPT: 2,368 items. 1,878 are rule-generated from JMdict, Tatoeba and the grammar packs by `items/gen_jlpt.py`; 490 are AI-drafted reading and listening items. There are enough items for one full mock at every level from N5 to N1.
  - DLPT: 100 passages and 306 items across ILR 0+ to 3, AI-drafted, checked against per-level length, kanji-density and abstract-vocabulary bands (`items/ilr_bands.json`, `items/gen_dlpt.py`).
  - `review.py` handles question banks (D-034). Both generators can draft more items through the owner's own OpenAI-compatible server.
- **Grammar N2/N1:** 192 + 189 new points, 829 in total (5,116 Tatoeba examples). The Tatoeba matching order was fixed so N2/N1 points don't take N5–N3 sentences.
- **UI on both apps:**
  - Exams hub: JLPT level with the number of items available, full mock / section / item-type drills, DLPT at 180/60/30 minutes, OPI, history.
  - Runner: countdown, question grid, passage pane with the bank markup rendered, audio button, strict modes.
  - Results: scaled scores or ILR estimate, plus "Add missed to SRS".
  - Attempt review with "Explain with AI" (labeled).
  - Every exam screen carries the "unofficial practice; not affiliated with DLI/ACTFL/JLPT" disclaimer.

### Verified
- Exam scoring, assembly, session, service and OPI tests pass (37 tests).
- `RealExamPackTest` builds JLPT mocks at all five levels, and a DLPT 60-minute form, from the real pack.
- CI runs the Python item tests and bank validation.

### Deferred
- **Human review:** all AI-drafted items, dialogues, scenarios and N2/N1 grammar need a pass with `tools/items/review.py` (owner). Until then they show the "AI-generated" badge.
- **Listening audio** uses on-device voices at play time rather than pre-rendered audio (D-030).
- **Out-of-level vocabulary:** 476 validator warnings flag above-level words in JLPT items. They are worth cleaning during review.
- **Upper range:** ILR 3+/4 passages are not written yet.

---

## Phase 6: On-device AI, speaking, listening (2026-09-18)

### What was built
- **AI core** (`shared/ai`):
  - `AiGateway`: JSON-schema contract, timeout, one retry that feeds the problems back to the model, deterministic fallbacks, and output checks (wrong script, invented words, how much a correction may change).
  - A prompt library of 10 tasks.
  - `LocalLlamaModel` (ChatML + GBNF) and `OpenAICompatibleModel` (json_schema → json_object → prompt-only).
  - Whisper local and endpoint recognizers, and VOICEVOX speech.
  - `ModelManager`: resumable downloads with SHA-256 checks, offering only permissively licensed models (D-032).
  - `tools/models/eval_ja.py` with 100 learner sentences.
- **Native bridges:**
  - llama.cpp b11040 and whisper.cpp b5130.
  - iOS uses prebuilt xcframeworks, linked explicitly.
  - Android builds them from source with CMake/NDK as arm64 JNI libraries.
- **App wiring** (`shared/speaking`):
  - `AiService`: engine choice, endpoint key kept in the Keychain/Keystore (D-033), model catalog bundled as `models-manifest.json`.
  - `RoleplaySession`: model or scripted turns, with corrections and a natural version.
  - `PronunciationService`: tokenizer plus Kanjium pitch targets.
  - Pomodoro activities (`study/activities`).
  - SRT/VTT subtitle parser (`media/Subtitles`).
- **Speech analysis** (`shared/speech`): YIN pitch tracking, voice detection, mora alignment against the recognizer's transcript, per-word pitch verdicts (↑↓), a fluency score, and shadowing comparison with DTW. All of it is labeled heuristic.
- **Practice pack** (`practice.sqlite`): 30 role-play scenarios with scripted fallbacks, 62 OPI questions plus ILR self-rating statements, 45 two-speaker dialogues with gaps, chunks and questions, and 630 minimal pairs derived from JMdict and Kanjium.
- **UI on both apps:**
  - A Practice tab (Speak / Listen / Write / Exams) with role-play, the pronunciation panel, listening dialogues (listen, gap-fill, order, questions), the minimal-pairs drill, a media player with dual subtitles and tap-to-look-up, Pomodoro sessions, and the OPI simulator.
  - Settings → AI & speech: model manager, own server with "test connection", speech-recognition and voice engines.
  - Platform details: D-035 (Android) and D-036 (iOS).

### Verified
- 360+ shared tests pass on the JVM and on the iOS simulator in CI, including the gateway, prompt golden tests, the model manager and YIN accuracy on synthetic signals.
- The Android APK builds with native libraries in CI.
- The iOS Swift for all the new screens compiled in CI; the link fix for whisper/llama is in the latest run.

### Not yet verified
- **Real hardware:** nothing has run on a real device or with real audio. That covers recording, speech recognition, pronunciation scores with a human voice, and loading a model and generating with it (llama has no iOS simulator slice). These are in `docs/QA.md` for the owner.
- **Model choice:** the Japanese quality benchmark (`eval_ja.py`) hasn't been run against a real model; open decision 3.

### Deferred
- Free-talk mode.
- For the media player: subtitle generation with on-device Whisper, "save clip to SRS", podcast RSS.
- FSRS scheduling for minimal pairs.
- Opt-in syncing of recordings.

---

## Phase 5: Sync (2026-09-18)

### What was built
- **Server** (`server/`):
  - Ktor 3 + Postgres 16 (SQLite for dev and tests), Flyway migrations.
  - Auth: Argon2id passwords, rotating refresh tokens with family revocation on reuse, and passkeys (WebAuthn).
  - Device registry, and push/pull with per-user sequence numbers and idempotent re-push.
  - Blobs with quota, an opt-in leaderboard (end-to-end-encrypted accounts are excluded), rate limiting and body-size caps.
  - `docker compose up` stack (server, postgres, optional Caddy TLS), a Makefile, and a README for self-hosting (D-028).
- **Client** (`shared/sync`):
  - SQLite triggers write dirty-row markers (D-026).
  - `SyncEngine`: reviews merge as a set union, cards are recomputed, everything else is last-writer-wins by (updated_at, deviceId), and tombstones propagate.
  - `SyncAccount` stores tokens in the Keychain/Keystore and handles opt-in end-to-end encryption (Argon2id + XChaCha20-Poly1305 in pure Kotlin, D-025).
- **Apps:** Me → Sync screen (server URL, sign in or create account, status, sync now, end-to-end passphrase, sign out). Sync runs when the app opens or becomes active, and after review sessions.
- **Protocol:** `docs/SYNC_PROTOCOL.md` (v1).

### Verified
- **SyncMergeTest:** two devices with interleaved offline reviews end up with identical review sets and identical recomputed FSRS cards. Also covers last-writer-wins tie-breaks, tombstones, no echo, idempotent push, and end-to-end round trips.
- **Crypto:** RFC test vectors pass.
- **Server:** 21 tests pass on SQLite, including passkeys through webauthn4j's emulated authenticator. The Postgres Testcontainers test runs in CI.

### Deferred
- **Hosted instance:** none exists yet. This is open decision 2.
- **Email verification:** sent but not required to sign in.
- **Recordings sync:** the blob store exists but the app doesn't upload recordings yet. That comes with Phase 6 recordings, as an opt-in.

---

## Phase 4: Reading & writing (2026-09-18)

### What was built
- **Tokenizer:** a pure-Kotlin Viterbi analyzer over an IPADIC pack (`tokenizer.sqlite`, 25 MB). `TokenizerParityTest` gives 99.8% sentence parity with MeCab (D-023).
- **Reader** (`shared/reader`):
  - Importers for pasted text, web articles (readability extraction, Shift_JIS/EUC-JP), RSS/RDF/Atom feeds, the Aozora Bunko catalogue and texts, and EPUB.
  - Paragraph/sentence/token view models with furigana modes (all, none, unknown words only).
  - Known-word ratio and difficulty labels such as "≈ N3 / ILR 1+".
  - Grammar detection per sentence from the grammar pack's patterns, and sentence mining that adds a word to reviews with its sentence as context.
  - Documents stay on the device (D-027).
- **Writing** (`shared/jp/strokes`):
  - Skritter-style stroke grading (resampling, direction histograms, DTW, start/end checks) that catches wrong direction, order, shape and position, and shows a hint after 3 misses.
  - A raw writing checker for reviews, a handwriting recognizer (D-024), and SVG stroke panels in the NihongoShark style.
- **OCR:** iOS uses VisionKit live text plus Vision on photos; Android uses ML Kit (Japanese, bundled model, offline).
- **Speech:** read-aloud with the system voice on both platforms (AVSpeechSynthesizer / TextToSpeech), with word highlighting.
- **UI on both apps:**
  - Learn → Reading: library, reader with tap-a-word popup, "Add to reviews", sentence panel with grammar and Listen, feeds, Aozora.
  - Learn → Draw to search, and Learn → Scan text.
  - Writing practice screens, plus writing cards in reviews (draw, check, compare with the animated stroke order, then rate). Turn writing cards on in Settings.

### Verified
- About 263 tests pass on the JVM, covering shared and server code, including stroke grading and recognition, reader importers and analysis, and tokenizer parity.
- The Android APK builds (123 MB debug, including the dictionary and tokenizer packs and the ML Kit model).
- iOS is verified by CI.

### Deferred
- **Share-sheet extensions:** "Read in Tsumugi" on iOS and ACTION_SEND on Android come with Phase 8 polish. For now, text is pasted into the reader.
- **Sentence translation and comprehension questions** arrive with the Phase 6 AI runtime.
- **Aozora ruby hints:** readings from Aozora's ruby markup aren't kept with saved documents yet. The reader shows furigana from the dictionary instead.

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
| AI & speech settings (engines, model downloads, own server, STT, VOICEVOX) | | ✅ |
| Practice: role-play, pronunciation panel, OPI, Pomodoro session | | ✅ |
| Listening: dialogues, minimal pairs, media player with dual subtitles | | ✅ |
| Exams: JLPT/DLPT hub, timed runner, results, attempt review, bank import | | ✅ |
| Onboarding | — | ✅ |
| Sync | Phase 5 | ✅ |
| Localization (en + ja UI) | | ✅ (per-app language on Android 13+) |
| Accessibility (screen reader labels, Japanese speech for learner text, large fonts, 48dp targets, reduced motion) | | ✅ |
| Share / text-selection entry points ("Read in Tsumugi", "Look up in Tsumugi") | | ✅ |
| App icon, backup rules, release notes | | ✅ (sideload APK only) |
