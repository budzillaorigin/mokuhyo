# Build Brief: Unified Japanese Learning App (working name: **Tsumugi** 紡ぎ)

> **Audience:** Claude Code (Opus). This file is the complete product and engineering brief. Read it top to bottom before writing any code. Section 1 contains the standing rules that apply to every session; copy it verbatim into `CLAUDE.md` at the repo root on your first run.
>
> **Owner:** Buddy. Solo developer, ships native SwiftUI iPhone apps from Xcode, has an Ollama server on his home network, uses WaniKani + Anki (NihongoShark kanji deck) + a Notion "Japanese Learning System" today, and studies for both JLPT and the U.S. military DLPT/OPI.
>
> **Working name note:** "Tsumugi" (to spin threads together) is a placeholder. Before App Store submission, verify name availability in App Store Connect and pick something legally distinct from every reference app named in this brief. Nothing in the UI, bundle IDs, or marketing may use a reference app's name, logo, mascot, or copyrighted mnemonics.

---

## 0. Owner quick start (human section — Claude Code may skip)

One command at a time. Run each in Terminal from the folder where you want the project to live.

```bash
mkdir tsumugi && cd tsumugi          # make the project folder and enter it
git init                              # start tracking changes in this folder
cp ~/Downloads/KOTOBA_UNIFIED_APP_BRIEF.md ./BRIEF.md   # put this brief in the repo
git add BRIEF.md                      # stage the brief
git commit -m "Add build brief"       # save the first snapshot
claude                                # start Claude Code in this folder
```

Then paste this as your first message to Claude Code:

> Read BRIEF.md in full. Create CLAUDE.md from Section 1. Then execute Phase 0 of Section 12 and stop for my review before Phase 1.

---

## 1. Standing rules → `CLAUDE.md`

```markdown
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
```

---

## 2. Product vision

**One app that replaces a dozen.** Tsumugi is a single, offline-first Japanese learning app that combines the best mechanics of the tools Buddy has been using or cloning — kanji and vocab SRS (WaniKani, NihongoShark/Anki), grammar SRS (Bunpro), a daily structured path with immersion (NativShark), graded news reading (Todaii), a full offline dictionary with handwriting input (IMIWA?, Yomiwa), pop-up lookup and sentence mining (Yomikiri), stroke-order handwriting practice (Skritter), AI conversation partners with pronunciation feedback (Jumpspeak, Praktika, Laddrr, Makes You Fluent, PomoSpeak, Kigaru), video/audio immersion with dual subtitles (Lingopie, LingoGym/KotoGym), Fluent-Forever-style personal minimal-pair and picture cards, and exam simulators for the **JLPT (N5–N1)** and the **DLPT (Reading, Listening) plus an OPI speaking simulator** on the ILR scale.

**Who it's for.** Serious self-learners who already hold accounts on services like WaniKani and want one place to study, plus U.S. military linguists preparing for the DLPT/OPI who have no good practice tool.

**Business model.** Paid-once (or free) on the App Store. No in-app subscriptions. Hosted sync is a convenience with a fair-use cap; anyone can run the sync server themselves from the same repo.

### Pillars and their reference apps

| Pillar | Clone the best of | What we take |
|---|---|---|
| Kanji & vocab SRS | WaniKani, NihongoShark deck, Anki | Radical→kanji→vocab unlock tree with levels; keyword + personal mnemonic ("myStory") field; stroke-order diagrams; FSRS scheduling; WaniKani API sync so existing progress carries over |
| Grammar SRS | Bunpro | Grammar points as SRS items with cloze/fill-in sentences, JLPT-level paths, "ghost" reviews for lapsed items, linked readings |
| Daily structured path | NativShark, Laddrr | A single "Today" screen that sequences new kanji → vocab → grammar → reviews → immersion → shadowing, in phases, with an adaptive daily budget |
| Reading | Todaii, Yomikiri, Yomiwa, IMIWA? | Furigana toggle, tap-to-define, level-tagged articles from user-added feeds, save-to-SRS from any text, share-sheet reader for any pasted text or URL, OCR from camera |
| Dictionary | IMIWA?, Yomiwa | Full offline JMdict/KANJIDIC2/JMnedict, radical search, handwriting search, example sentences, pitch accent, conjugation tables, multi-kanji compound breakdown |
| Writing | Skritter | Finger/Pencil stroke practice graded against KanjiVG stroke order with per-stroke hints and raw-score writing reviews |
| Listening immersion | LingoGym (KotoGym), Lingopie | Dialogue drills with gap-fills, dual-subtitle player for user's own media and podcasts, sentence-loop and shadowing, click any subtitle word |
| Speaking | Jumpspeak, Praktika, Laddrr, Makes You Fluent, PomoSpeak, Kigaru | AI role-play conversations with an on-device LLM, "natural version" rewrite loop (Kigaru), pronunciation and pitch-accent feedback, Pomodoro-length sessions, situational "moments" |
| Personal cards | Fluent Forever | Minimal-pair ear training, personal picture-word cards, self-recorded audio cards |
| Exams | (none clone them well) | JLPT mock tests with real timing/section structure and scaled scoring; DLPT-style reading/listening with ILR-level tagging; OPI simulator that conducts and rates an interview |
| Motivation | Bunpro, WaniKani, Laddrr | Streaks, levels, heat-map, weekly challenges; leaderboard only via optional sync server |

---

## 3. Architecture

### 3.1 Repository layout

```
tsumugi/
├── BRIEF.md                    # this file
├── CLAUDE.md                   # §1 of this file
├── docs/
│   ├── PROGRESS.md             # phase checkpoints (Claude Code maintains)
│   ├── DECISIONS.md            # ADR-style decisions
│   ├── LICENSES.md             # every third-party asset + attribution text
│   ├── SYNC_PROTOCOL.md        # wire format for sync (generated in Phase 5)
│   └── CONTENT_PACKS.md        # pack formats and build instructions
├── shared/                     # Kotlin Multiplatform core (the product)
│   └── src/
│       ├── commonMain/kotlin/app/tsumugi/
│       │   ├── domain/         # entities, value objects
│       │   ├── db/             # SQLDelight schema + queries
│       │   ├── srs/            # FSRS scheduler, queues, unlock tree
│       │   ├── jp/             # tokenizer, deinflector, furigana, mora, pitch
│       │   ├── dictionary/     # JMdict/KANJIDIC2 lookup, radical search
│       │   ├── content/        # content packs: kanji levels, grammar, readers, tests
│       │   ├── ai/             # LLM/STT/TTS abstractions, prompts, grammar-constrained schemas, scoring
│       │   ├── exam/           # JLPT, DLPT, OPI engines + scoring
│       │   ├── integrations/   # WaniKani, Bunpro, Anki apkg, Notion, OpenAI-compatible client
│       │   ├── sync/           # change log, merge, transport
│       │   └── study/          # Today planner, sessions, stats
│       ├── commonTest/         # required suites (see §11)
│       ├── iosMain/            # expect/actual: keychain, files, platform tokenizer fallback
│       └── androidMain/
├── iosApp/                     # SwiftUI app (Xcode project, SPM)
│   ├── Tsumugi/
│   │   ├── App/                # entry, DI, navigation
│   │   ├── Features/           # one folder per screen group
│   │   ├── Platform/           # AVAudio, Speech, PencilKit, Vision OCR, Core ML, llama.cpp bridge
│   │   └── Resources/
│   └── TsumugiTests/, TsumugiUITests/
├── androidApp/                 # Compose scaffold — same feature folders, most screens stubbed
├── server/                     # Ktor sync server + Postgres, docker-compose, migrations
├── tools/                      # Python: content-pack builders, item QA, model prep, handwriting model training
│   ├── packs/
│   ├── items/
│   └── models/
├── content/                    # built packs (git-lfs or downloaded at build; never hand-edited)
└── .github/workflows/          # CI: shared tests, iOS build, Android build, server tests
```

### 3.2 Platform split

| Concern | Where | Notes |
|---|---|---|
| Entities, DB, SRS, dictionary, tokenizer, exam engines, sync, prompts, scoring math | `shared/` | One implementation, tested once |
| UI, navigation, haptics, widgets, notifications | iOS SwiftUI / Android Compose | Feature parity checklist in `docs/PROGRESS.md` |
| Audio capture/playback, TTS voices | Platform | `AVAudioEngine`/`AVSpeechSynthesizer` on iOS; `AudioRecord`/`TextToSpeech` on Android |
| Speech-to-text | Platform behind `SpeechRecognizer` interface | iOS: `SFSpeechRecognizer` with `requiresOnDeviceRecognition = true` (ja-JP supported) as default, whisper.cpp as optional higher-accuracy engine; Android: whisper.cpp |
| LLM inference | Platform behind `LanguageModel` interface | llama.cpp (MIT) built as an xcframework for iOS and a JNI `.so` for Android; GGUF models downloaded on demand |
| Handwriting/stroke input | Platform canvas → shared grader | Canvas captures normalized stroke polylines; `shared/jp/strokes` grades against KanjiVG |
| OCR | Platform | iOS `Vision` (`VNRecognizeTextRequest`, ja); Android Google ML Kit Text Recognition v2 (Japanese, on-device, free, no API key; SDK under Google's API terms — record in LICENSES.md) |
| Secrets | Platform keychain/keystore via `SecretStore` expect/actual | |

### 3.3 Swift ↔ KMP bridge

Use **SKIE** (Touchlab, Apache-2) to expose Kotlin `Flow`s and sealed classes to Swift ergonomically. The shared framework is consumed as a local SPM package produced by the Gradle `embedAndSignAppleFrameworkForXcode` task. Keep the Swift-facing API in `shared/.../api/` as plain interfaces returning `Flow`/suspend functions; SwiftUI views observe through small `@Observable` view models that wrap those flows.

### 3.4 Data storage

- **SQLDelight** database in `shared/`, one file per domain (`user.sq`, `srs.sq`, `dictionary.sq`, `content.sq`, `exam.sq`, `sync.sq`).
- Dictionary and content packs ship as **read-only SQLite files** attached at runtime (`ATTACH DATABASE`), versioned, downloadable (JMdict pack ≈ 60 MB compressed; the app's base IPA must stay under 200 MB so packs beyond the core are on-demand downloads from the app's own static host or GitHub Releases — no third-party service dependency).
- User data (progress, review log, notes, recordings) lives in the writable DB and is the only thing synced.
- Audio recordings and user media are files referenced by path, with an optional "include recordings in sync" toggle (off by default).

### 3.5 AI runtime (§7 has the details)

```
LanguageModel  ── LocalLlamaModel (GGUF via llama.cpp)          [default]
               └─ OpenAICompatibleModel (user endpoint + key)   [optional]
SpeechRecognizer ── AppleOnDevice | WhisperCpp
Synthesizer      ── PlatformTTS | VoicevoxEndpoint (optional, self-hosted, free)
Grader           ── pure Kotlin: mora alignment, pitch contour compare, rubric scoring
```

Every AI call goes through `shared/ai/AiGateway`, which picks the engine from settings, applies the prompt template, enforces a JSON schema (GBNF grammar for local, `response_format` for endpoints), times out, and falls back to a deterministic non-AI path when the model is unavailable (e.g., grammar correction falls back to dictionary-based conjugation checks; OPI falls back to scripted question banks).

### 3.6 Sync (§8 has the details)

Self-hostable **Ktor + Postgres** server in `server/`, single `docker compose up`. Hosted instance is the default endpoint; Settings lets the user enter any base URL. Protocol is an append-only change log with per-record last-writer-wins and set-union for the review log (reviews are immutable facts, never conflict).

---

## 4. Data sources and licenses

All packs are built by `tools/packs/*.py` from these sources and written to `content/`. Attribution text goes in `docs/LICENSES.md` and the in-app Licenses screen.

| Data | Source | License | Used for |
|---|---|---|---|
| JMdict, JMnedict | EDRDG via `jmdict-simplified` (scriptin) JSON releases | CC BY-SA 4.0 (EDRDG licence) | Dictionary, vocab entries, POS, common-word flags |
| KANJIDIC2 | EDRDG | CC BY-SA 4.0 | Kanji readings, meanings, stroke count, jōyō grade, JLPT (legacy) level, Heisig indices |
| KRADFILE / RADKFILE | EDRDG | CC BY-SA 4.0 | Radical search |
| KanjiVG | Ulrich Apel | CC BY-SA 3.0 | Stroke order diagrams, stroke grading, handwriting model training |
| JmdictFurigana | Doublevil | Verify current license in repo before use (historically permissive; falls back to per-kanji alignment if unusable) | Furigana alignment for readings over kanji |
| Tatoeba (jpn–eng pairs) | tatoeba.org exports | CC BY 2.0 FR | Example sentences (filter by JLPT-level vocabulary coverage) |
| Kanjium pitch accent data | mifunetoshiro/kanjium | Verify license in repo before use; if not permissive, substitute NHK/OJAD-derived rules or UniDic accent fields (UniDic carries accent data under BSD) | Pitch accent numbers for words |
| Japanese sentence-level pitch (optional) | Derived from Kanjium + rules | own | Accent rendering in reader/speaking feedback |
| UniDic-lite / IPADIC dictionary | NINJAL / MeCab project | BSD (unidic-lite), BSD/LGPL-free variants only | Tokenizer lexicon (see §5.2) |
| JLPT vocab/kanji/grammar lists | Community-compiled (e.g., Jonathan Waller's lists, `jlpt-vocab-api`), tagged **unofficial** | CC BY / MIT per source | Level tagging. There is no official JLPT list since 2010; say so in the UI |
| Wikipedia / Wikinews (ja) | dumps | CC BY-SA | Optional reading corpus for graded passages |
| Aozora Bunko | aozora.gr.jp | Public domain texts (check per work) | Graded literary reading |
| NHK Easy News, other news | User-added RSS/URLs only | Not redistributed | Fetched client-side on the user's device at the user's request, cached locally, never stored on the sync server (Todaii-style reader without the copyright exposure) |
| ILR skill level descriptions | U.S. Government (govtilr.org) | Public domain | DLPT/OPI level definitions and rubrics |
| JLPT format, section timings, pass marks | Published by JLPT (jlpt.jp), facts only | — | Exam simulator structure. **No official test items are used** |
| Common Voice ja (optional) | Mozilla | CC0 | Native-speaker audio samples for minimal pairs, listening items |
| Fonts | Noto Sans/Serif JP, Klee One, Zen Kurenaido (handwriting-style) | OFL | UI, stroke diagrams, handwriting reference |
| LLM weights | Qwen2.5-Instruct (1.5B/3B/7B) GGUF Q4_K_M | Apache-2.0 | On-device generation and correction (see §7.1 for alternatives) |
| STT weights | whisper.cpp `ggml-base`/`small` (multilingual, ja) | MIT | Optional STT engine |
| Handwriting classifier | Trained in `tools/models/` from KanjiVG + font rendering | own | Handwriting dictionary lookup |

**Explicitly not used:** WaniKani subject content beyond what the authenticated user's own API token returns for on-screen display (never cached into content packs, never shown to other users); Bunpro grammar explanations; NativShark, Skritter, or any reference app's scripts, images, or audio; JLPT/DLPT official past papers.

---

## 5. Core modules — detailed specs

Each module lists: purpose, what it clones, data model touchpoints, UX, and acceptance criteria. Build order is in §12.

### 5.1 Domain model (shared/domain)

```
User            id, displayName, settings(json), createdAt
Item            id, kind(RADICAL|KANJI|VOCAB|GRAMMAR|SENTENCE|LISTENING|WRITING|MINIMAL_PAIR|CUSTOM),
                level(int, 1–60 path level), jlpt(N5..N1|null), ilr(0+..3|null),
                primaryText, reading, meanings[], source(pack|wanikani|anki|user|llm|verified), packId, refId
ItemRelation    parentId, childId, kind(COMPONENT|USES_KANJI|GRAMMAR_EXAMPLE|SIMILAR|CONFUSED_WITH)
Card            id, itemId, direction(RECOGNITION|RECALL|READING|WRITING|LISTENING|CLOZE|PRODUCTION),
                fsrs: stability, difficulty, due, lastReview, reps, lapses, state(NEW|LEARNING|REVIEW|RELEARNING), suspended
Review          id(uuid), cardId, ts, rating(1–4), elapsedMs, answerText?, correct?, deviceId   -- immutable, append-only
Note            itemId, myStory(md), myMnemonicImage?, userAudioPath?, tags[]       -- the NihongoShark "myStory" concept
GrammarPoint    id, title, jlpt, structure, meaning(md, own text), nuance(md), examples[] -> Sentence ids
Sentence        id, ja, en?, tokens(json), source, audioPath?, jlpt?, ilr?, pitch(json)?
Reader          id, title, sourceUrl?, body(ja), sentences[], level tags, importedAt, isUserContent
MediaItem       id, localPath|feedUrl, title, transcript(json: segments w/ timestamps), subtitles(ja, en?)
Session         id, kind(TODAY|REVIEW|LESSON|CONVERSATION|EXAM|WRITING|LISTENING), startedAt, endedAt, stats(json)
Conversation    id, scenarioId, turns[] (role, ja, en?, audioPath?, corrections(json)), rubricScores(json)
ExamAttempt     id, exam(JLPT_N5..N1|DLPT_READING|DLPT_LISTENING|OPI), startedAt, submittedAt, answers(json), scoring(json)
Integration     kind(WANIKANI|BUNPRO|NOTION|LLM_ENDPOINT|VOICEVOX|SYNC), config(json, secrets in keychain), lastSyncAt, cursor
ChangeLog       seq, table, rowId, op, payload(json), ts, deviceId, synced(bool)
```

### 5.2 Japanese text engine (shared/jp)

Clones the invisible core of Yomikiri/Yomiwa/IMIWA?: any Japanese string → tokens → dictionary forms → furigana → lookups.

- **Tokenizer:** a `Tokenizer` interface with two implementations. Phase 1 uses `expect/actual` platform tokenizers (iOS: `NLTokenizer` + `CFStringTokenizer` for readings; Android: Kuromoji, Apache-2). Phase 4 replaces both with a pure-Kotlin Viterbi lattice tokenizer over a compact UniDic-lite-derived lexicon shipped in the dictionary pack, so both platforms tokenize identically (required for sync-stable sentence token ids). Port the algorithm; do not copy GPL code.
- **Deinflector:** rule-based, own implementation (verb groups, い/な adjectives, auxiliaries, polite/causative/passive/potential/volitional chains, contractions like 〜ちゃう/〜てる/〜なきゃ). Table-driven in `deinflect_rules.json`; test file has 300+ cases.
- **Furigana:** JmdictFurigana alignment for known words; fallback per-kanji reading distribution.
- **Mora & pitch:** mora splitter (拗音, 促音, 長音 handling), pitch pattern rendering (heiban/atamadaka/nakadaka/odaka), from Kanjium.
- **Kana utilities:** hiragana/katakana/romaji conversion (Hepburn, with IME-style input for typed answers, e.g. typing `shi` produces し, `nn` → ん).
- **Lookup:** prefix and exact search over kanji form, kana reading, and English gloss (FTS5), with deinflected candidates ranked by length and commonness. Target: < 5 ms per lookup on iPhone 12.

### 5.3 Dictionary (IMIWA?, Yomiwa)

- Search box accepts kanji, kana, romaji, English, or a whole sentence (sentence mode tokenizes and lists all words).
- Handwriting search (draw a kanji): platform canvas → strokes → on-device classifier (Core ML on iOS, TFLite on Android) trained in `tools/models/handwriting/`; returns top-10 candidates, refined by stroke count.
- Radical search (KRADFILE) with stroke-count filter; multi-radical selection narrows the grid live.
- Camera OCR (Yomiwa): live text recognition with tap-to-look-up; also works on photos from the library.
- Entry view: readings with pitch accent, meanings by sense with POS tags, JLPT tag (unofficial), frequency rank, kanji breakdown (each kanji tappable → kanji view with stroke diagram animation from KanjiVG, on'yomi/kun'yomi, components, jōyō grade, Heisig/NihongoShark keyword), example sentences (Tatoeba, level-filtered), conjugation table for verbs/adjectives, related/confusable words, "Add to SRS" with direction choices, "Add to Anki" export queue.
- Word lists: user-created, importable from CSV/imiwa export (Buddy's existing `imiwa_to_anki.py` input format), exportable to `.apkg`.

### 5.4 Kanji & vocab SRS (WaniKani, NihongoShark, Anki)

**Content:** a 60-level path (`content/packs/kanji-path.sqlite`) built by `tools/packs/build_kanji_path.py`: radicals → kanji → vocab, ordered by a scoring function over frequency, jōyō grade, stroke count, and component dependencies (a kanji is never scheduled before its components). Keywords are our own: default to the KANJIDIC2 primary meaning, disambiguated so no two kanji share a keyword, with Heisig index shown as a cross-reference (numbers only, no Heisig text). Mnemonics are **the user's** (`Note.myStory`) — exactly the NihongoShark method; the app ships none and prompts the user to write one at lesson time, with an optional on-device-LLM "suggest a mnemonic from these components" button that clearly labels output as AI-generated.

**Unlock rules:** WaniKani-style. A kanji unlocks when all its radicals reach "Guru" (FSRS stability ≥ threshold, mapped to named stages Apprentice/Guru/Master/Enlightened/Burned for familiarity). Vocab unlocks when its kanji reach Guru. Level advances when 90% of the level's kanji reach Guru. User can override: manual unlock, skip level, or import known-kanji list.

**Scheduler:** **FSRS-6** (open algorithm, MIT reference implementations) in `shared/srs/Fsrs.kt` with default parameters, per-user optimizer run on-device from the review log when ≥ 1,000 reviews exist. Desired retention configurable (default 0.9). Learning steps for new cards: 10 min → 1 day, then FSRS. Lapses go to Relearning. Card directions per item kind:
- Radical: meaning
- Kanji: meaning, reading (accept any on/kun listed as primary), writing (Skritter mode, §5.7)
- Vocab: meaning, reading (typed kana via built-in IME conversion), listening (audio → type), optional production (English → type Japanese)

**Review UX:** WaniKani-flavored: item card, answer field, instant grading with typo tolerance for meanings (Damerau-Levenshtein ≤ 1 for words ≥ 5 chars, synonyms list, user-added synonyms), exact match for readings, "wrap-up" mode, undo last, keyboard shortcuts on iPad. Session summary with per-level accuracy and leech detection (≥ 8 lapses → flagged, suggest mnemonic rewrite).

**Anki/NihongoShark interop:** import `.apkg` (both legacy `collection.anki2` and `collection.anki21b` zstd-compressed SQLite; protobuf-encoded configs; media manifest) — map the NihongoShark notetype (21 fields, `KeywordToKanji` template) to Kanji items, carrying `myStory` into `Note.myStory` and stroke PNGs into media. Preserve Anki `note.id`/`card.id` in `refId` so re-export round-trips. Export any list or the whole path as `.apkg` via the `genanki`-compatible writer implemented in Kotlin (or shell out to `tools/anki/export.py` for desktop batch use). Optional AnkiConnect push over LAN for desktop Anki.

**WaniKani sync (§9.1)** maps WK subjects to path items by character, imports assignments/review statistics as initial FSRS state, and can post reviews back.

### 5.5 Grammar SRS (Bunpro)

- Grammar packs by JLPT level (`content/packs/grammar-n5.sqlite` … `n1`): ~ 120/170/200/220/250 points. Each point has: title (e.g., 〜ておく), structure pattern, our own concise meaning/nuance text (written by the content pipeline — LLM-drafted, then human-reviewed via `tools/items/review.py` before being marked `verified`), 8–12 example sentences with audio (TTS, or Common Voice where matched), related points, common mistakes, and links to external free explanations (Tae Kim, Imabi, Wasabi) as URLs only.
- Review types: **cloze** (sentence with the grammar blanked, accept any valid conjugation via the deinflector), **fill-in with hint** (English meaning shown), **sentence build** (ordered chunks), **reading recognition** (choose meaning), **production** (translate short English prompt; graded by on-device LLM rubric + rule checks, always shows the model answer).
- "Ghost" reviews: a point answered wrong spawns a shadow card with a short interval until answered correctly twice.
- Paths: JLPT order, Genki/Tobira/Quartet-aligned order (chapter mapping only, no book content), or custom.
- Bunpro import (§9.2): pull known grammar and SRS stage where the API allows; otherwise CSV.

### 5.6 Today — the structured daily path (NativShark, Laddrr)

A single screen that sequences the day:

1. Reviews due (all SRS kinds, interleaved, capped by daily budget)
2. New lessons (radical/kanji/vocab from the path, grammar from the current level) — count adapts to yesterday's accuracy and time spent
3. Immersion block: one reader passage or listening clip at the user's level (§5.8/5.9)
4. Shadowing block: 3–5 sentences from today's grammar, record and compare (§5.10)
5. Speaking moment (optional): one Kigaru-style scenario (§5.10)
6. Writing block (optional): 3 kanji in Skritter mode

Phases (NativShark-style "Phase 1: hiragana/katakana → Phase 2: core → …") are simply bands of path levels with different block weights. Daily budget in minutes is chosen by the user (10/20/40/60) and PomoSpeak-style timed sessions (5/10/15/20 min) are available from any block. Streak counts any day with ≥ 1 review; "vacation mode" freezes without breaking.

### 5.7 Writing practice (Skritter)

- Canvas (PencilKit on iOS, Compose canvas on Android) shows a faded template (toggleable), the user draws strokes; each stroke is graded in `shared/jp/strokes`: resample to 32 points, normalize to unit box, compare to the KanjiVG stroke by direction histogram + DTW distance + start/end proximity; wrong stroke order or direction → red flash and hint stroke animation; three fails → show stroke.
- Raw-squigg mode (no template) for reviews; "writing" cards use this and grade self-rated (1–4) after showing the reference, plus automatic stroke-count/order check.
- Stroke-order animation player for any kanji (from KanjiVG paths), including the NihongoShark-style per-stroke panel export (109×109 panels, gray completed / black current / red start dot) for users who want the diagram in Anki.

### 5.8 Reading (Todaii, Yomikiri, Yomiwa)

- **Reader sources:** pasted text, share-sheet from Safari/any app (text or URL → readability extraction on device), user-added RSS feeds (NHK Easy, other news, blogs — fetched on the user's device only), Aozora Bunko catalogue (public-domain works, downloadable), packs of graded passages (built from Wikipedia/Wikinews with level filtering + LLM simplification, labeled), and EPUB import (Buddy reads Japanese EPUBs on a reMarkable; keep the same files usable here).
- **Reader UI:** furigana on/off/only-above-level, tap word → popup dictionary (Yomikiri-style, non-blocking), long-press sentence → translation (on-device LLM, labeled) + grammar points detected in the sentence (matched from grammar pack patterns), add word/sentence to SRS with one tap (sentence mining, with the sentence stored as the card's context), TTS read-aloud with sentence highlighting, difficulty estimate (% known words from the user's SRS + JLPT tags), pitch-accent overlay.
- **Level tagging:** per passage compute JLPT coverage and estimated ILR level (heuristic: sentence length, kanji density, abstractness lexicon) — shown as "≈ N3 / ILR 1+".
- **Comprehension mode (Todaii):** after reading, 3–5 auto-generated questions (LLM-generated from the passage, labeled; multiple choice) to turn passive reading into DLPT-style practice.

### 5.9 Listening immersion (LingoGym/KotoGym, Lingopie)

- **Dialogue drills (LingoGym):** short scripted dialogues (packs, TTS-voiced with two distinct voices; VOICEVOX endpoint if configured) with modes: listen → gap-fill (type missing word), listen → order chunks, listen → shadow (record, compare), listen → answer question. Speed 0.7–1.2×. Sentence loop.
- **Media player (Lingopie):** user's own video/audio files (Files app, iCloud Drive, local network share) and podcast RSS. Subtitles: load `.srt`/`.vtt` if present, else generate with on-device STT (whisper.cpp for timestamps) and cache; optional English line via on-device LLM translation (labeled). Dual-subtitle view, tap-word lookup, "save clip to SRS" makes a listening card with the audio segment, A/B loop, hide-subtitle mode, comprehension quiz at chapter end. **No downloading from streaming services**; explain this in the empty state.
- **Minimal pairs (Fluent Forever):** ear-training decks for length (おばさん/おばあさん), gemination (きて/きって), voicing, pitch accent (はし/はし/はし), with TTS or Common Voice audio; adaptive drilling using the same FSRS engine.

### 5.10 Speaking (Jumpspeak, Praktika, Laddrr, Makes You Fluent, PomoSpeak, Kigaru)

- **Scenario role-plays:** packs of situations (convenience store, izakaya, doctor, station, phone call, job interview, briefing a Japanese counterpart, base gate, etc.) with a system prompt, goals, and vocabulary. Conversation loop: user speaks (STT) or types → on-device LLM replies in character at the user's level (JLPT/ILR-aware prompt), TTS voices the reply, transcript shows both. Each user turn gets: **corrections** (diff view), a **"natural version"** rewrite that keeps the user's meaning (Kigaru's loop: say it → see natural → say it again until it's fluent), and a pronunciation panel.
- **Free talk:** Makes-You-Fluent-style adaptive tutor; the model tracks a rolling "level estimate" and recurring error patterns (stored in `Conversation.rubricScores`, surfaced weekly).
- **Pronunciation feedback (heuristic, honest):** compare STT transcript to target at mora level (alignment, per-mora hits), measure speaking rate and pauses, extract F0 contour (YIN) and compare to the expected pitch pattern from Kanjium for words in the sentence → per-word accent feedback (↑↓ markers), plus playback side-by-side with TTS. Show a 0–100 composite but label the sub-scores; never claim phoneme-level accuracy the pipeline can't deliver.
- **Sessions:** Pomodoro-style timers (PomoSpeak) with a queue of mini-activities: role-play turn, sentence repeat, "what do you hear", pick-a-word, story time (Laddrr/PomoSpeak games) — each is a small `Activity` type in `shared/study/activities`.
- **Privacy:** recordings stay on device unless the user enables recording sync.

### 5.11 Exams

#### JLPT simulator (N5–N1)
- Reproduce the **published structure**: sections, item types, item counts, and timings. Store this as data in `content/packs/jlpt-blueprints.json` so it's easy to update when the JLPT changes them. Current published timings to encode: N5 語彙 20 min / 文法・読解 40 min / 聴解 30 min; N4 25/55/35; N3 30/70/40; N2 言語知識・読解 105 / 聴解 50; N1 110/55.
- Item types: 漢字読み, 表記, 語形成, 文脈規定, 言い換え類義, 用法, 文の文法1 (form), 文の文法2 (★ sentence assembly), 文章の文法, 内容理解 (短文/中文/長文), 統合理解, 主張理解, 情報検索; listening: 課題理解, ポイント理解, 概要理解, 発話表現, 即時応答, 統合理解 — each an item template in `shared/exam/jlpt/ItemTypes.kt`.
- Item bank: generated by `tools/items/gen_jlpt.py` (LLM-drafted from level vocab/grammar lists, auto-validated: answer key uniqueness, distractor plausibility, vocabulary within level, no duplicates), then human review; shipped with `source` flags. Listening items are TTS-rendered (two voices) at build time. Users can import their own item banks (JSON schema in `docs/CONTENT_PACKS.md`).
- Scoring: raw → **scaled score** per section (0–60 each; N4/N5 combine language knowledge + reading into 0–120) using a documented linear approximation, total 0–180, pass/fail against published pass marks (N1 100, N2 90, N3 95, N4 90, N5 80) and sectional minimums (19 each; 38 for combined). Report by item type with links to the grammar/vocab items missed → "add to SRS."
- Modes: full mock (real timing, no pausing, no lookups), section drill, item-type drill, review of past attempts with explanations (rule-based where possible, LLM-explained and labeled otherwise).

#### DLPT practice (Reading, Listening)
- ILR-scaled (0+, 1, 1+, 2, 2+, 3 for the lower range; 3+/4 upper-range as a later pack). Passages in Japanese, **questions and answer choices in English**, multiple choice, timed like the real test (a DLPT5 MC test runs about 3 hours; the simulator offers full-length and 30/60-minute slices).
- Passage design per ILR level, driven by the public ILR descriptors: level 1 signs/instructions/simple narrative; 2 news, factual reporting, straightforward instructions; 3 editorial, argument, hypothesis, abstract topics, idiom and nuance. `tools/items/gen_dlpt.py` generates passages + items with level metadata and a validator that checks length, kanji density, and abstract-vocabulary ratio against per-level bands; human review flags them `verified`.
- Listening: same, with TTS audio (or VOICEVOX) at natural speed; item audio is played once (like the real test) unless "practice mode" is on.
- Reporting: estimated ILR level (highest level with ≥ 70% sustained over ≥ 20 items, with a floor of consistent performance below it), by-text-type breakdown, time per item, weak-area suggestions mapped to grammar/vocab packs.

#### OPI simulator (speaking)
- The on-device LLM plays the interviewer using the OPI phase structure: warm-up → level checks → probes → role-play → wind-down. It adapts upward on strong answers, downward on breakdown, exactly as the ILR/ACTFL interview does, using the public ILR speaking descriptors as the rubric (functions, contexts/content, accuracy, text type).
- Interface: voice-first with STT, optional typed mode; transcript hidden during the interview, shown after. Duration 15–30 min.
- Rating: rubric prompt produces a structured JSON (per-factor ratings, evidence quotes, sustained level, breakdown level, estimated ILR 0+–3). Show clear disclaimer that this is practice, not an official rating. Store attempts; chart progress.
- Fallback when no LLM is loaded: scripted interview banks per level with self-rating checklists.

### 5.12 Progress, stats, motivation

- Home stats: reviews/day heat-map, level progress ring, accuracy by item kind, SRS stage distribution (Apprentice…Burned bar), forecast of upcoming reviews (FSRS), time-on-task, exam trend lines (JLPT scaled / ILR estimates).
- Streaks with freeze days; weekly challenges (generated locally, e.g., "burn 20 kanji"); optional leaderboard when sync is on (opt-in, display-name only).
- Widgets (iOS): reviews due, streak, kanji-of-the-day. Notifications: reviews-due reminders with quiet hours.
- Export: full JSON, `.apkg`, CSV of reviews, PDF study report.

---

## 6. Screens and navigation (iOS)

Tab bar: **Today · Reviews · Learn · Practice · Me**

- Today (§5.6)
- Reviews: queue by kind; session; summary
- Learn: Kanji path (level grid), Grammar (level list), Dictionary, Word lists, Readers library
- Practice: Speak (scenarios, free talk, OPI), Listen (dialogues, media, minimal pairs), Write (kanji), Exams (JLPT, DLPT)
- Me: stats, integrations (WaniKani, Bunpro, Anki, Notion, LLM endpoint, VOICEVOX, Sync), models (download/manage), settings, licenses, export

Global: search (⌘K style) reachable from every tab; share-sheet extension "Read in Tsumugi"; Safari/Action extension for word lookup on selected text. Android mirrors the tab structure; Phase 1 Android ships Today/Reviews/Dictionary only, the rest as "Coming soon" screens wired to the shared API.

---

## 7. AI runtime — details

### 7.1 Models and download manager
- Base IPA ships **no** LLM weights (keeps the store download small). First run offers a "Recommended (≈1 GB)" bundle: Qwen2.5-1.5B-Instruct Q4_K_M (Apache-2.0, solid Japanese) + whisper `base` (multilingual). Devices with ≥ 8 GB RAM can pick the 3B model; ≥ 12 GB or a Mac (Catalyst later) the 7B. Model manifest (`content/models/manifest.json`, URLs on the project's own release host + Hugging Face mirrors, SHA-256, size, minimum RAM) is versioned with the app; the app never depends on a paid or keyed download service.
- Alternative candidates to evaluate in Phase 6 and record in `docs/DECISIONS.md`: Gemma 3 (license terms are more restrictive — check), Llama 3.2 3B (community license), Japanese-tuned small models (e.g., ELYZA, Rakuten, or Sarashina variants if their licenses are permissive). Choose by a Japanese correction benchmark in `tools/models/eval_ja.py` (100 learner sentences with gold corrections; measure exact-match and GLEU).
- Inference: llama.cpp with Metal on iOS (xcframework built by `tools/models/build_llama_ios.sh`), Vulkan/CPU on Android. Context 4k, KV cache reuse per conversation, GBNF grammars for all structured outputs.

### 7.2 Prompt library (shared/ai/prompts)
Templates with typed inputs and a JSON schema per task: `correct_sentence`, `natural_rewrite`, `roleplay_turn`, `explain_grammar_in_sentence`, `translate_sentence`, `generate_reading_questions`, `suggest_mnemonic`, `opi_interviewer_turn`, `opi_rate`, `jlpt_explain_item`. Each has golden tests (`commonTest/ai/PromptGoldenTest`) run against a recorded fixture, plus an optional live eval script.

### 7.3 Endpoint mode
Settings → "Use my own AI server": base URL, API key (optional), model name; probe `/v1/models` on save. Works with Ollama (`/v1`), LM Studio, llama-server, vLLM, and any OpenAI-compatible host. Also optional Whisper-compatible STT endpoint (`/v1/audio/transcriptions`) and a VOICEVOX endpoint for TTS (`/audio_query` + `/synthesis`). All optional; the UI shows which engine handled each response.

### 7.4 Safety and quality
- All generated Japanese passes a validator: tokenizes cleanly, no non-Japanese script leaks, length bounds, and (for corrections) the correction must be accepted by the deinflector/dictionary.
- Corrections show a diff and a one-line reason; when the model's confidence (self-reported JSON field) is low, show "unsure" instead of a confident-looking fix.
- Conversation content filters: keep role-plays PG; refuse to rate or continue if the model output fails JSON validation twice (fall back to scripted turn).

---

## 8. Sync — details

### 8.1 Server (`server/`)
- Ktor 3, Exposed or SQLDelight-JVM, Postgres 16, Flyway migrations, Docker Compose (`server`, `postgres`, optional `caddy` for TLS). One env file. `make dev` runs with SQLite for zero-setup local dev.
- Auth: email + password (Argon2id) and passkeys (WebAuthn); Sign in with Apple **only if** any other third-party sign-in is added later (App Store rule). Email verification via any SMTP; magic-link optional. Tokens: short-lived access JWT + rotating refresh token; device registry.
- Endpoints: `POST /v1/auth/*`, `GET /v1/sync/pull?since=<seq>`, `POST /v1/sync/push`, `GET /v1/blobs/:id` / `PUT` (recordings, images; optional), `GET /v1/leaderboard` (opt-in), `GET /v1/health`.
- Storage: per-user change log table keyed by `(userId, seq)`; server assigns `seq`; payloads are opaque JSON encrypted **optionally** end-to-end (user passphrase, Argon2id-derived key, XChaCha20-Poly1305) — default off for simplicity, toggle in settings, documented tradeoff (no server-side leaderboard stats when E2E is on).
- Fair-use limits on the hosted instance are configuration, not code paths.

### 8.2 Client protocol (shared/sync)
- Every local write appends to `ChangeLog`. Push sends unsynced entries in order; pull fetches entries with `seq > lastSeq` and applies them.
- Merge rules: `Review` rows are set-union (immutable facts). `Card` FSRS state is **recomputed** from the union of reviews rather than merged, so two devices reviewing offline converge deterministically. `Note`, `Settings`, list membership: last-writer-wins by `(ts, deviceId)`. Deletes are tombstones.
- Content packs are not synced; the server stores which pack versions the user has so a new device can prompt downloads.
- Conflict-free by construction; a sync test suite simulates two devices with interleaved offline reviews and asserts identical state.

### 8.3 Integration with third-party syncs
WaniKani/Bunpro imports are recorded as `Review`s with `source = wanikani` so they merge like any other review, and cursors live in `Integration` per device (each device may pull; idempotent by external id).

---

## 9. Integrations

### 9.1 WaniKani (API v2)
- Settings: paste API v2 token (Personal Access Token). Store in keychain. Show the account's level, granted permissions, and a "read-only" vs "can post reviews" indicator based on token scopes.
- Rate limit 60 req/min; use `If-Modified-Since`/`ETag` and `updated_after` cursors; paginate with `pages.next_url`.
- Import: `/v2/subjects` (map by `characters`/slug to path items; subjects we don't have become `source = wanikani` items visible only to this user, displayed live from the cached API response, and **never** written into shared content packs), `/v2/assignments` (SRS stage → initial FSRS state via a mapping table), `/v2/review_statistics`, `/v2/study_materials` (user's own meaning notes/synonyms → `Note`), `/v2/level_progressions`.
- Optional two-way: when enabled, submit reviews for WK items via `POST /v2/reviews` (and `PUT /v2/study_materials`) so WaniKani stays in step. Queue offline; reconcile by `created_at`.
- Respect ToS: no scraping, no redistribution, display only for the token owner.

### 9.2 Bunpro
- API key from Bunpro settings; use whatever the public API exposes (review counts, known grammar, SRS levels; it is limited and changes). Everything else via CSV/JSON export import. Grammar-point matching by title normalization table maintained in `content/packs/grammar-aliases.json`.

### 9.3 Anki
- `.apkg` import/export as in §5.4; AnkiConnect (LAN, user's desktop) optional; AnkiMobile: export file + open-in.

### 9.4 Notion (optional)
- Buddy's "Japanese Learning System" database: with a user-supplied integration token and database IDs, push a daily study-log row and level/JLPT status updates (mirrors his current Python sync script). Free API; entirely optional; documented mapping in `docs/INTEGRATIONS.md`.

### 9.5 imiwa export & word lists
- Import imiwa export text/CSV directly (same format Buddy's scripts consume) into word lists.

---

## 10. Android scaffolding requirements

- `androidApp` builds and runs from Phase 1 with: onboarding, Today, Reviews (all card directions), Dictionary (search, entry view), Settings/Integrations/Sync. Everything else is a stub screen calling the shared API and showing "Not yet on Android."
- Platform implementations required in `androidMain`/`androidApp`: SecretStore (EncryptedSharedPreferences/Keystore), file access (SAF), TTS (`android.speech.tts`), STT (whisper.cpp JNI), llama.cpp JNI, ML Kit OCR, Compose stroke canvas, notifications, widgets (Glance) later.
- Parity checklist table maintained in `docs/PROGRESS.md`. CI builds a debug APK on every PR.
- Play Store readiness is **not** in scope for v1, but nothing may be written in a way that precludes it (no iOS-only data formats in `shared/`).

---

## 11. Testing and quality gates

Required `commonTest` suites (all must pass in CI):
- `FsrsTest`: interval math against the reference implementation's published test vectors; retention curve; optimizer convergence on synthetic logs.
- `DeinflectorTest`: 300+ surface → dictionary-form cases including chained auxiliaries and colloquial contractions.
- `TokenizerParityTest` (Phase 4+): identical tokens across implementations on a 1,000-sentence corpus.
- `FuriganaTest`, `MoraTest`, `PitchTest`, `KanaConversionTest`.
- `UnlockTreeTest`: no kanji schedules before its components; level advancement rules.
- `JlptScoringTest`, `DlptEstimateTest`, `OpiRubricParseTest`.
- `SyncMergeTest`: two-device interleaving; tombstones; FSRS state recomputation equality.
- `ApkgRoundTripTest`: import NihongoShark-format sample → export → re-import equality (fixtures in `shared/src/commonTest/resources/anki/`).
- `PromptGoldenTest`: schema validity of recorded outputs.

iOS: XCTest for view models, snapshot tests for review card layouts, an on-device performance test that asserts dictionary lookup < 5 ms and review-screen transition < 100 ms. Server: integration tests with Testcontainers Postgres.

Manual QA checklist per phase in `docs/QA.md`, including an airplane-mode pass through every screen.

---

## 12. Phased build plan

Each phase ends with `docs/PROGRESS.md` updated and a stop for owner review. Estimated effort assumes Claude Code doing the implementation with the owner testing on-device.

**Phase 0 — Foundations (repo, CI, skeletons)**
- Create the layout in §3.1; Gradle KMP setup with iOS/Android targets; SKIE; SQLDelight; Xcode project consuming the shared framework; Compose app shell; GitHub Actions for shared tests + both app builds; `docs/*.md` seeded; `tools/` Python env with `uv`.
- Acceptance: both apps launch to a placeholder Today screen backed by a shared `HelloUseCase`; CI green.

**Phase 1 — Data engine**
- Content pack builders for JMdict/KANJIDIC2/KRADFILE/KanjiVG/JmdictFurigana/Kanjium/Tatoeba; `dictionary` module + FTS; domain model + DB; platform tokenizers; deinflector; kana/mora utilities.
- Acceptance: Dictionary screen on iOS and Android with search, entry view, kanji view with stroke animation, radical search; test suites for jp/* green.

**Phase 2 — SRS core + kanji path**
- FSRS-6, card/queue logic, unlock tree, kanji-path pack builder, lessons and reviews UI (iOS full, Android reviews only), myStory notes, stats basics, streaks, notifications.
- `.apkg` import/export incl. NihongoShark format; imiwa list import.
- Acceptance: owner imports his NihongoShark deck and WaniKani token (Phase 2b below) and does a real review session on iPhone.

**Phase 2b — WaniKani integration** (can run in parallel): token flow, import, optional review posting.

**Phase 3 — Grammar + Today**
- Grammar packs N5–N3 (N2/N1 in Phase 7), grammar review types, ghost reviews, Bunpro import; Today planner with budgets and phases; iOS widgets.

**Phase 4 — Reading & writing**
- Reader (paste/share/RSS/Aozora/EPUB), popup dictionary, sentence mining, furigana engine, level estimate; pure-Kotlin tokenizer replacing platform ones; Skritter-style writing canvas and grading; handwriting search classifier (train in `tools/models/handwriting`); camera OCR.

**Phase 5 — Sync**
- Server, auth, protocol, client, E2E option, docs, docker compose, hosted deploy notes (any VPS). Sync tests. Android sync parity.

**Phase 6 — On-device AI + speaking + listening**
- llama.cpp + whisper.cpp builds, model manager, AiGateway with grammar-constrained JSON, endpoint mode, prompt library, correction/natural-rewrite loop, scenario role-plays, free talk, pronunciation panel (mora alignment + F0), Pomodoro sessions and mini-activities; dialogue drills; media player with STT subtitles; minimal pairs; VOICEVOX option. Japanese model eval and choice recorded.

**Phase 7 — Exams**
- JLPT blueprints, item templates, generation + validation pipeline, human review tool, listening rendering, mock/drill modes, scaled scoring; DLPT reading/listening packs and ILR estimator; OPI simulator and rubric; N2/N1 grammar packs.

**Phase 8 — Release hardening**
- Accessibility (Dynamic Type, VoiceOver labels in Japanese and English), localization scaffolding (en, ja UI), App Store assets, privacy nutrition labels (data not linked to identity unless sync is enabled), export-compliance answer for encryption (standard algorithms → exempt with declaration), on-demand resources / size audit (< 200 MB base), crash-free soak, TestFlight, `docs/RELEASE.md` with exact `xcodebuild`/App Store Connect steps. Android: debug APK sideload instructions only.

---

## 13. App Store and legal checklist

- No IAP subscriptions; if paid, price tier only. If free with optional hosted sync, describe hosted sync as free with fair-use limits — do not gate features behind it.
- Privacy: microphone (speaking), camera (OCR), speech recognition (on-device only; still requires the usage string), notifications, photo library (optional), local network (AnkiConnect/Ollama/VOICEVOX discovery — declare `NSLocalNetworkUsageDescription` and Bonjour types if browsing).
- Third-party API keys: user-provided; the app must work fully without them (App Review will test with none).
- Content: all packs attributable; Licenses screen; AI-generated content labeled; DLPT/OPI screens carry "unofficial practice; not affiliated with DLI/ACTFL/JLPT."
- Trademarks: do not use WaniKani, Bunpro, NativShark, Skritter, Lingopie, or other marks in the app name, screenshots, or description except in a factual "imports from WaniKani/Anki/Bunpro" sentence.
- Guideline 4.2 (minimum functionality) is satisfied by the offline dictionary/SRS even before AI models are downloaded.
- Guideline 5.1.1: account creation only required for sync; everything else works without an account.

---

## 14. Open decisions (record answers in `docs/DECISIONS.md`)

1. Final product name and bundle ID (placeholder: Tsumugi / `app.tsumugi.*`).
2. Pricing: paid-once vs free; hosted sync fair-use cap.
3. Default on-device LLM after the Phase 6 Japanese benchmark (starting assumption: Qwen2.5-1.5B-Instruct Q4_K_M).
4. Whether WaniKani review posting is on by default when a token with write scope is present (starting assumption: off; explicit toggle).
5. Pure-Kotlin tokenizer lexicon: UniDic-lite vs IPADIC-derived (starting assumption: UniDic-lite for better readings/pitch alignment; verify license text).
6. Whether to ship N1/N2 grammar packs at v1 or as a free content update.
7. Leaderboard: include at v1 (needs moderation of display names) or defer.

---

## Appendix A — Feature attribution map (what each reference app contributes)

| App | Feature(s) carried into Tsumugi |
|---|---|
| WaniKani | Radical→kanji→vocab unlock tree, 60 levels, stage names, typo-tolerant grading, wrap-up mode, API sync |
| NativShark | Phased daily path, Today sequencing, immersion + shadowing blocks, "learn in context" sentence-first vocab |
| Bunpro SRS | Grammar-as-SRS, cloze reviews, ghost reviews, JLPT/textbook paths, linked readings |
| Todaii Japanese | Level-tagged news reading with furigana toggle, tap-to-define, comprehension questions, TTS read-aloud |
| IMIWA? | Offline JMdict/KANJIDIC2, radical search, conjugation tables, word lists, export |
| Bunpo | Bite-sized grammar lessons with immediate practice, sentence-build exercises |
| Skritter | Stroke-order graded handwriting with per-stroke hints, raw-squigg reviews, stroke animations |
| Jumpspeak | Speak-first role-plays with instant feedback, real-situation scenarios |
| Lingopie | Dual-subtitle media player, click-word lookup, clip-to-flashcard, sentence loop |
| Kigaru (Talks) | "Moments" rehearsal, say → natural version → repeat loop, no-pressure private practice, readiness framing |
| Praktika | Persona-based AI tutors, structured speaking curriculum, per-session feedback report |
| Fluent Forever | Minimal-pair ear training, personal picture/audio cards, pronunciation-first onboarding |
| Makes You Fluent | Adaptive AI tutor that re-levels per session, varied lesson generation, pronunciation feedback |
| Yomiwa | Camera OCR lookup, handwriting input, example sentences |
| Yomikiri | Non-blocking popup dictionary in any text, one-tap sentence mining to SRS/Anki |
| PomoSpeak | Pomodoro sessions, 30+ mini-activities (word garden, cloze, eavesdropping, story reader), pronunciation scoring per sentence |
| Laddrr | Skills-check onboarding, adaptive daily path, story time / what-do-you-hear / pick-a-word games, cultural notes, weekly challenges |
| LingoGym (KotoGym) | Dialogue-based listening drills with gap-fill, chunk ordering, shadowing |
| Anki / NihongoShark | Personal mnemonic (myStory) field, `.apkg` round-trip, stroke-panel diagrams |

## Appendix B — Content pack build commands (owner reference)

```bash
cd tools && uv sync                       # install Python deps once
uv run packs/build_dictionary.py          # JMdict + KANJIDIC2 + KRADFILE + furigana + pitch → content/packs/dictionary.sqlite
uv run packs/build_kanjivg.py             # stroke paths + per-stroke features → content/packs/strokes.sqlite
uv run packs/build_kanji_path.py          # 60-level path
uv run packs/build_sentences.py           # Tatoeba filtered by level
uv run packs/build_grammar.py --level N5  # grammar pack (drafts need review: uv run items/review.py)
uv run items/gen_jlpt.py --level N3 --n 200
uv run items/gen_dlpt.py --skill reading --ilr 2 --n 50
uv run models/train_handwriting.py        # Core ML + TFLite export
```

## Appendix C — Glossary

FSRS: Free Spaced Repetition Scheduler (open algorithm). ILR: Interagency Language Roundtable proficiency scale (0–5). DLPT: Defense Language Proficiency Test (reading/listening). OPI: Oral Proficiency Interview. JLPT: Japanese-Language Proficiency Test (N5 easiest → N1). KMP: Kotlin Multiplatform. GBNF: llama.cpp grammar format for constrained output. Mora: Japanese timing unit (か = 1, きゃ = 1, っ = 1, ー = 1).
