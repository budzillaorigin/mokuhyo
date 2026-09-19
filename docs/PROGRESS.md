# Progress

Current phase: **v2 (BRIEF_V2.md) on branch `v2`. Phase 9 (stabilize) built; Phase 10 in progress.** The owner asked for v2 phases to run without per-phase stops; device QA and the TestFlight archive are the owner's, on the Mac (`docs/QA.md`, `docs/RELEASE.md`). The owner asked for all phases to run back to back, without per-phase review stops. CI (`.github/workflows/ci.yml`) builds packs and runs the shared, Android and iOS builds and tests on every push. Repo: https://github.com/budzillaorigin/tsumugi (private).

---

## Phase 12: listening, speaking and exam content (2026-09-18)

BRIEF_V2 §8 Phase 12, §6.10, §6.16, G-08 and the Appendix A content findings. Decisions D-220…D-229. The content was drafted by Claude (owner decision), so everything is `source: "llm"` / unverified with the AI badge until reviewed (rules 10, 19). The graded readers and tracks are separate Phase 12 work.

### Content (counts)

| What | Before | Now |
|---|---|---|
| Role-play scenarios | 30, all exactly 6 turns | **90** (60 new: business 10, admin 10, military/liaison 10, travel 10, family 8, medical 7, culture 4, school 1), 4–12 turns, 699 scripted turns, 2–3 `accept` alternatives per turn |
| Listening dialogues | 45 (N5–N3) | **125**: 40 natural (N5/N4/N3/N2 × 10) + 40 new scripted (N4 8, N3 10, N2 12, N1 10) + the 45 reworked; 1,214 lines, 1,434 gap targets, 306 questions |
| Drill sets (new) | none | **31** sets / 325 items (22 grammar, 9 dialogue-line) |
| OPI questions | 62 | **96**, every question tagged with a DLI-style domain |
| DLPT passages / items | 100 / 306 (0+–3) | **195 / 595**: + ILR 3+/4 reading 60/184, ILR 3+/4 listening 20/61, military/liaison at 2–3 15/44 |

### Fixes from Appendix A
- The hotel closing was re-examined: the scenario is a check-in, so お世話になります fits an arriving guest. It now closes with よろしくお願いします (お世話になります is still accepted). The departing case is the new `travel-hotel-checkout` (お世話になりました). Seven other scenarios that ended without a partner closing line were fixed (D-221).
- `n3-environment` is now a real two-person dialogue.
- Comprehension questions were reworked across all 45 original dialogues, with distractors grounded in the audio. Lines were edited in 15 N5/N4 dialogues to support that.
- The 30 original scenarios now range from 4 to 12 turns.
- Scripted-fallback matching lives in shared `RoleplaySession` (it counts turns and ignores the reply). The `accept` data is ready; the matcher is left for the coordinator (D-223).

### Tools
- `packs/build_practice.py`:
  - filler markup `{…}` → `dialogue_line.fillers`, plus `overlap`, `style` and speaker `hint`
  - `scripted_turn.accept`, `opi_question.domain`
  - drill sets
  - N1–N5 dialogues, 4–12 scenario turns, a closed category set
  - `--check FILE…`
  - pack version 2
- `packs/practice_authoring.py` + `speaking/author_scenarios.py` + `listening/author_dialogues.py`:
  - merge script entries, `batches/*.json` and the JSON without duplicating ids, keeping reviewed copies
  - `draft --endpoint URL --model NAME` appends validated endpoint drafts
- `items/ilr_bands.json` has 3+/4 bands (and `IlrBandData.kt` is regenerated).
- `items/gen_dlpt.py` handles 3+/4: ids, guides, text types, 2–4 items, upper-range text-type check, `liaison` at 2–3. `packs/build_exam.py` accepts 3+/4.
- `packs/render_audio.py`: a docstring note only (see Audio).

### Shared API
- **Practice:**
  - `DialogueStyle`, `DialogueLine.fillers/overlap/segments()/withoutFillers`, `FillerSpan`, `LineSegment`
  - `Speaker.hint`, `Dialogue(Summary).style`
  - `ScriptedTurn.accept/acceptableAnswers`
  - `OpiDomain`, `OpiQuestion.domain`
  - `DrillKind`, `DrillItem`, `DrillSet(Summary)`, `PracticeRepository.drillSets(level)/drillSet(id)`
- **Drill timing:** `DrillTiming` (FIXED/PROPORTIONAL, presets), `DrillPlayback.plan(set, timing, answerMs)`, `DrillCursor` (advance/skip/back).
- **Exam:**
  - `IlrLevel.upperRange/tested`, `DlptRange`
  - `ExamAssembler.dlpt(…, range, textTypes)`, `filterByTextType`, `textTypeCounts`
  - `ExamService.dlpt(…, range, textTypes)`, `dlptTextTypes(exam, range)`
  - `SwiftSupport.dlptFiltered/dlptTextTypes` + `DlptTextTypeCount`
- **OPI:** `OpiSession.turns/probeMap()`, `OpiTurnRecord`, `OpiTurnOutcome`, `OpiProbeMap` (floor, ceiling, breakdowns, per-level tallies, level track, domains); the scripted interview rotates domains.

### Tests
- New shared tests:
  - `DlptUpperRangeTest` (7): upper-range forms, the text-type filter and counts, the summary, service + import
  - `OpiProbeMapTest` (5): outcomes, floor/ceiling, a session log with breakdowns, domain rotation
  - `DrillPlaybackTest` (6): step order, proportional/fixed pauses, clip lengths, estimates, cursor
  - `PracticeRepositoryTest` (+2): natural fillers/segments/overlap, drill sets; accept, domain, hint and style asserted
- `RealExamPackTest` checks the real packs: an upper-range form, a liaison-filtered form, natural fillers, a drill-set plan and the probe map.
- Tools:
  - `items/test_gen_dlpt.py` 25/25 (5 new upper-range and liaison tests)
  - `packs/test_practice_authoring.py` 7/7 (markup, merge, duplicate ids, drafting against a fake endpoint)
  - `items/test_gen_jlpt.py` 13/13
- Validators: `gen_dlpt.py validate --strict` on all five DLPT banks: 0 errors, 0 warnings. `build_practice.py --check` on every batch: 0 errors. `ruff`: clean.
- Gradle (`:shared:compileCommonMainKotlinMetadata :shared:testAndroidHostTest :androidApp:assembleDebug -Ptsumugi.native=false`): green.

### Packs rebuilt (main checkout `content/packs`)
- `practice.sqlite`: 90 scenarios / 699 turns, OPI 96, 125 dialogues (natural 40, scripted 85) / 1,214 lines / 1,434 gaps / 306 questions, 31 drill sets / 325 items, 630 minimal pairs.
- `exam.sqlite`: 7 banks, 401 passages, 2,963 items, 0 band warnings.

### Audio (not rendered here)
- `dialogues`: 1,214 lines, about 924 of them new plus the lines edited in 16 originals. `exam`: +191 lines of upper-range and liaison scripts. Drill sets reuse `grammar/<point>/0` and dialogue clips, and their English cues use system TTS.
- Command: `uv run python packs/render_audio.py dialogues exam`. The cache keeps everything unchanged.

### Deferred
- Platform UI: the hands-free drill player (`UIBackgroundModes: audio`), greyed fillers in the transcript, overlap playback, the DLPT range and text-type pickers, and the probe-map chart.
- A scripted-fallback matcher using `acceptableAnswers` (shared `RoleplaySession`, D-223).
- Human review of all of the above in the review UI (G-16).
- Upper-range ILR bands are provisional until reviewed passages exist.

### How to run
```bash
cd tools
uv run python packs/speaking/author_scenarios.py && uv run python packs/listening/author_dialogues.py   # merge
uv run python packs/build_practice.py && uv run python packs/build_exam.py
uv run python items/gen_dlpt.py validate --strict items/bank/dlpt_*.json
uv run python packs/test_practice_authoring.py && uv run python items/test_gen_dlpt.py

## Phase 12 (tracks): interest and domain tracks (2026-09-19)

BRIEF_V2 §6.5: seven tracks, each with a word list, kanji subset, scenarios, dialogues and drills, selectable in onboarding and switchable any time. Decisions D-210…D-219. Other Phase 12 content (graded readers, more dialogues, scenarios and ILR items) is built by other agents. Platform UI is not built yet; the hooks are listed below.

### What was built
- **Pack:** `tracks.sqlite`, with `tracks.sq` as its schema (`TracksDatabase`, `PackInstaller.TRACKS`).
  - Built by `tools/packs/build_tracks.py` from `tools/packs/tracks/*.json` and wired into `build_all.py`.
  - Subcommands: `validate` lists every problem; `resolve` fills JMdict ids; `draft --endpoint URL --model NAME` extends a track through any OpenAI-compatible endpoint, appending only items that validate and never reusing ids.
- **Content:** drafted by Claude (owner decision). All of it is `source: "llm"`, `verified: false`, with the badge on:

| Track | Levels | Words | Kanji | Scenarios (turns) | Dialogues | Drills | Other |
|---|---|---|---|---|---|---|---|
| Gaming & VTuber (`gaming`) | N5–N2 | 478 | 142 (explicit, with hints) | 10 (67) | 10 | 40: 20 fill-in, 20 meaning | – |
| Business & keigo (`business`) | N4–N1 | 253 | 150 (derived) | 30 (223) | 8 | 84: 14 email templates, 70 keigo | – |
| Family & household (`family`) | N5–N2, ILR 0+–2 | 266 | 150 (derived) | 10 (68) | 10 | 40: 20 fill-in, 20 usage yes/no | – |
| Daily-life admin (`daily-life`) | N5–N2 | 267 | 150 (derived) | 12 (74) | 8 | 24: 24 fill-in | 12 situations / 51 can-do; 8 cultural tasks |
| Native schoolchild vocabulary (`schoolchild`) | N4–N1 | 912 | 249 (derived) | 4 (26) | 4 | 230: 70 fill-in, 40 meaning, 60 synonym/antonym, 60 usage yes/no | – |
| Military & liaison (`military`) | N3–N1, ILR 2–3 | 271 | 150 (derived) | 12 (104) | 8 | 20: 20 meaning | 14 ILR readings; 5 links |
| Performing culture (`performing`) | N5–N2 | 182 | 150 (derived) | 6 (40) | 6 | 20: 20 performances | – |
| **Total** | | **2629** | **1141** | **84 (602)** | **54** | **458** | 12 situations / 51 can-do; 8 tasks; 14 readings; 5 links |

- **Size targets:**
  - Gaming: 142 kanji (target ~140) with our own breakdowns and memory hints, and 478 words (target 600, so 80%).
  - Business: all 30 situations.
  - Schoolchild: 912 words (83% of the 1,100-style target).
  - Military: ILR 2–3 throughout, with fictional briefings, and JMSDF/JASDF/JGSDF/MOD pages as links only.
- **Shared (`app.tsumugi.tracks`):**
  - `TrackRepository`: tracks, words, lessons, kanji, scenarios and dialogues (as the practice models), drills, situations, tasks, readings and links.
  - `TrackService`: selection as a synced setting, the onboarding API, lessons mixed into Today, and can-do checks.
  - Drill models with checking: `KeigoDrill` (via `KeigoRules`), `EmailDrill`, `FillInDrill`, `SynonymDrill`, `UsageDrill`, `MeaningDrill`, `PerformDrill`, plus `PerformanceSession` (memorize-and-perform).
  - `AnswerText.normalize` for comparing typed answers.
- **Minimal hooks:**
  - `AppGraph.trackRepository()`, `AppGraph.tracks`, `AppGraph.dialogue(id)`.
  - `roleplay(id)` falls back to track scenarios.
  - `startLessons()` and `today()` mix in and count track words.
  - `LessonSession.batch`.
- **No user-DB migration.** Selections (`tracks.selected`) and can-do ticks (`tracks.canDo`) are synced settings.

### UI hooks for the platform agents
- **Onboarding step:** `graph.tracks.onboardingOptions()` returns a `TrackSummary` per track (title, `levelLabel`, `description`, `counts`, `wordsLeft`, `selected`). Save with `chooseInOnboarding(ids)`.
- **Settings or Learn → Tracks:** `tracks()`, `select`/`deselect`/`switchTo`. A track page uses `trackRepository()?.lessons(id)`, `kanji(id)` (show `breakdown`/`hint` for gaming), `scenarios(id)` (open with `graph.roleplay(scenario.id)`), `dialogues(id)` (open with `graph.dialogue(id)` in the existing listening screen), `drills(id, type)`, `situations(id)` (tick with `tracks.setCanDo(situation.canDoId(i), done)`), `tasks(id)`, `readings(id)` and `links(id)`.
- **Drill screens:**
  - Keigo and fill-in: typed `check(text)`; fill-in can show `choices`.
  - Email: render `segments` with a field or chips per `Slot`, then `check(slot, text)`.
  - Synonym and meaning: `check(index)`. Usage: `check(saysCorrect)`.
  - Perform: `PerformanceSession(drill)`, then `prompts()`, `deliver(line, sttTranscript)` or `selfRate`, and `nextRound()`. Show `staging` and each line's `stage`.
- **Badge:** everything with `isAiGenerated` shows the AI-generated badge.
- **Today:** nothing to do. Lessons already include track words when a track is selected.

### Tests
- `TracksTest` (11): pack rows to models (scenarios and dialogues as practice models, unknown drill types skipped); selection as a synced setting; Today mixing (half the batch, path items complete on the path, track words join reviews, known words skipped, fills the batch without a path, empty when done); round-robin across tracks; can-do; no pack (honest empty states); keigo rules (special and regular, suru nouns, お/ご, forms); keigo check (kana, katakana, punctuation, rule forms, humble rejected); email, fill-in and choice drills; memorize-and-perform fading; lesson splitting.
- `RealTracksPackTest` (androidHostTest, real pack): every row loads, every drill parses and accepts its model answer, every performance can be completed, and the keigo rules agree with the authored answers. Before the rules were extended, they disagreed on 3 of 60 rule-covered drills (お気に召す, 承る, 存じておる); those forms were added.
- `build_tracks.py draft` was smoke-tested against a fake endpoint: an unknown word and a malformed drill were rejected, and ids advanced.

### Deferred
- `tools/items/review.py` and the in-app review (G-16) don't read track files yet. The badge stays on until they do (another agent's file). Everything else about review is ready: items carry `source`/`verified`.
- `render_audio.py` doesn't render track dialogues yet (another agent's file), so they use TTS; the clip keys `dialogue/<id>/<ord>` already fit. Performances have no audio.
- Whether tracks ship in the base app or as downloads (BRIEF_V2 §9 item 7). The pack is 1.5 MB and is bundled like the others.
- An AI check of a performed line through the gateway (the session exposes the transcript and the script).
- Gaming words: 478 of the 600 target. Extend with `draft gaming --kind words`.

### How to run
```
cd tools && uv run python packs/build_tracks.py      # or packs/build_all.py
./gradlew :shared:compileCommonMainKotlinMetadata :shared:verifySqlDelightMigration :shared:testAndroidHostTest :androidApp:assembleDebug -Ptsumugi.native=false
```

---

## Phase 12: courses, monolingual mode, onomatopoeia (shared + content, 2026-09-18)

BRIEF_V2 §6.6 (structured JLPT courses and monolingual mode) and §6.8 (onomatopoeia), in the shared core and packs. Decisions D-230…D-239. There's no platform UI yet; the hooks are listed below. All the new text is AI-drafted by Claude (owner decision): `source = "llm"`, and the badge stays on until reviewed.

### Content counts
| Content | Count | Where |
|---|---|---|
| Japanese grammar explanations (`meaning_ja` + `nuance_ja`) | **829 / 829** points: N5 127, N4 148, N3 173, N2 192, N1 189 (the brief's minimum was the 381 N2+N1) | `tools/packs/grammar/n*.json` → `grammar.sqlite` `grammar_point_ja` |
| Onomatopoeia words (JMdict on-mim) | **1,334** (1,340, minus 6 explicit entries) | `dictionary.sqlite` `onomatopoeia` |
| …with our English feel line | **1,309**; the 720 most frequent all have one (the brief asked for 600) | `tools/packs/onomatopoeia/entries.json` |
| …with a Japanese feel line | **1,307** | same |
| …with Tatoeba examples (up to 3) | **502** | pack `sentence` table |
| Themes, with one original SVG glyph each | **12**: sounds 320, movement 183, manner 162, appearance 140, voice 116, texture 106, state 91, feelings 88, body 46, eating 44, pain 20, weather 18 | `tools/packs/onomatopoeia/themes.json` |
| Types | 擬音語 / 擬態語 / 擬情語 | — |
| Course modules from the real packs | N5 16 (79 kanji, 479 words, 127 grammar, 3 sections) · N4 19 (166, 448, 148, 3) · N3 22 (367, 1,169, 173, 3) · N2 24 (367, 890, 192, 2) · N1 24 (1,151, 1,501, 189, 2) | derived at run time |

### What was built
- **Courses (`app.tsumugi.courses`).**
  - `CourseBuilder` (pure) and `CourseService`. Each JLPT level becomes modules of kanji → vocab → grammar → quiz → mock section.
  - Modules are derived from the kanji-path items with that JLPT tag, the grammar points at that level, the exam bank's item types (module quizzes) and the blueprint sections (mocks).
  - There's a per-level progress bar (`overview()`) and a "one book to pass" list (`remaining(level)`): unlearned kanji and words, unmastered grammar points, and mock sections not yet passed.
- **Grammar mastery checkbox.** `GrammarMasteryStore` keeps a checkbox per grammar point that is independent of SRS. It lives in the new `grammar_mastery` table, synced LWW on the flag, and is included in the JSON backup.
- **Monolingual mode.**
  - `MonolingualSettings` holds the synced `monolingual.fromLevel`. It's off by default and starts at N2 when turned on, or earlier if the learner chooses.
  - `Explanations.grammar(point)` returns our Japanese explanation from the pack, or English with `japaneseMissing`.
  - `Explanations.word(request)` returns the JMdict glosses, or the new `paraphrase_word_ja` LLM paraphrase. The paraphrase is labeled and cached in the device-local `ai_paraphrase` table. Without a model it falls back to English with `unavailableReason`.
- **Onomatopoeia (`app.tsumugi.onomatopoeia`).**
  - `OnomatopoeiaRepository` provides themes with glyphs and counts, filtering by theme and type, search, and detail with examples.
  - `OnomatopoeiaQuiz` asks "pick the word for the scene" and "pick the scene for the word". Its distractors never share a reading or gloss with the answer.
- **Schema.**
  - User DB `7.sqm` (v7 → v8) adds `grammar_mastery` and `ai_paraphrase`. `databases/7.db` is its starting snapshot. It was renumbered from 8 at merge, and the no-op placeholder was dropped (D-231).
  - The grammar pack gets `grammar_point_ja`. The dictionary pack gets `onomatopoeia` and `onomatopoeia_theme`, whose schema is `onomatopoeia.sq`.
- **Tools.**
  - `packs/grammar_ja.py` (status/check/merge/draft).
  - `packs/build_onomatopoeia.py` (build/status/merge/draft), wired into `build_all.py` after `build_decks.py`.
  - `packs/llm_draft.py`, the shared OpenAI-compatible client (`--endpoint URL --model NAME`).
  - Re-runs only add missing ids or fields.

### Hooks for the platform UIs (AppGraph)
- **`courses`:**
  - `courseLevel()` / `setCourseLevel(n)`.
  - `overview()` returns `List<LevelProgress>` for N5…N1, each with `progress.fraction` / `percent`.
  - `course(level)` returns a `JlptCourse`: `modules` (each with `steps`, `nextStep`, `kanji`, `words`, `grammar`, `quiz`, `mock`), `currentModule`, `progress` and `sections`.
  - `remaining(level)` returns a `LevelRemaining`.
  - `setMastered(pointId, bool)` / `masteredIds()`.
  - A quiz step launches `exams().jlptTypeDrill(level, type)`, and a mock step launches `exams().jlptSection(level, sectionId)`.
- **`monolingual`:** `fromLevel()` / `setFromLevel(n or null)` / `enabled()` / `setEnabled(bool)` / `languageFor(level, learnerLevel)`.
- **`explanations`:**
  - `grammar(point)` and `grammar(point, language)` return a `GrammarExplanation` with `language`, `meaning`, `nuance`, `aiGenerated` and `japaneseMissing`.
  - `word(ParaphraseRequest, learnerLevel, generate)` returns a `WordExplanation` with `language`, `glosses`, `paraphrase`, `example`, `note`, `engine`, `cached` and `unavailableReason`. Lists pass `generate = false`.
  - `paraphrase(...)` / `forgetParaphrase(...)`.
- **`onomatopoeia()`** returns an `OnomatopoeiaRepository`, or null when there's no dictionary pack:
  - `available()`.
  - `themes()` returns theme `svg` strings (viewBox 64, `currentColor`).
  - `words(theme, type, withFeelOnly)`, `search(q)`.
  - `detail(entryId)` includes `examples`.
  - `quiz(count, kind, theme, seed)` returns `OnomatopoeiaQuestion` values with `prompt`, `promptJa`, `choices`, `options`, `answer` and `isCorrect(i)`.
  - Show the badge when `word.aiGenerated`.

### Tests
- **commonTest:**
  - `CourseBuilderTest` (9 tests).
  - `MonolingualAndMasteryTest` (5): mastery LWW sync across two devices, cached paraphrase, the setting.
  - `OnomatopoeiaTest` (5): quiz ambiguity rules, repository, empty state for older packs.
  - `PromptGoldenTest`: `paraphrase_word_ja`, a good answer plus a bad English one and a circular one.
- **androidHostTest:**
  - `UserDbMigrationTest`: v7 → v8 markers.
  - `RealPhase12PackTest`: the real packs, with courses for all five levels, ja explanations for every N2/N1 point, and 600+ described onomatopoeia plus a 30-question quiz.

### Deferred
- **Platform UI.** None was built here: the course view, mastery checkboxes, monolingual toggle, onomatopoeia browser and quiz.
- **Review tooling.** `tools/items/review.py` doesn't show or verify `meaning_ja`/`nuance_ja` or onomatopoeia entries yet. It's outside this change's scope (tools/packs only), so the badge stays on for all of them.
- **Onomatopoeia examples.** 832 words have no example sentence. The pack's Tatoeba subset has none containing them, and adding sentences is a `build_sentences.py` change.
- **Video links.** User-attachable video links per grammar point (§6.6) aren't built.

### How to run
```
cd tools && uv run python packs/build_grammar.py && uv run python packs/build_onomatopoeia.py   # or packs/build_all.py
uv run python packs/grammar_ja.py status && uv run python packs/build_onomatopoeia.py status
./gradlew :shared:compileCommonMainKotlinMetadata :shared:verifySqlDelightMigration :shared:testAndroidHostTest :androidApp:assembleDebug -Ptsumugi.native=false
```

## Phase 11 (Android UI): immersion pipeline and audio packs (2026-09-18)

The Android screens for everything Phase 11 built in the shared core, plus pre-rendered audio (rule 20). Decisions D-180…D-189. iOS is being built in parallel by another agent.

### What was built (androidApp only)
- **Audio packs (rule 20):** bundled pitch and minimal-pair packs install at startup (`ensureBundled`). Exam listening (`exam/<owner>/<line>`, play-once kept in strict modes), dialogues, minimal pairs (practice and reviews), grammar examples (new ▶ per example) and matching shadowing sentences play pre-rendered clips and fall back to TTS. Settings → Audio packs: installed sets with sizes, versions and voice credits; install from a file (SAF) or a typed server URL (no default URL, D-096), with progress and cancel; remove.
- **Media decks:** Learn → Decks (create from text, EPUB or subtitles; from any reader document or the player's coverage card), preview with coverage, 80/90/95/98% targets, JLPT/ILR, kanji and grammar, save, deck page with "Study this deck" (interleave or deck only), Core 2k/6k/10k with "covers X% of your media".
- **Coverage:** overlay on reader documents and in the media player, difficulty badges, library sort by coverage with background profiling, and "Your media" (sentence-bank media sorted by coverage, reopened with their cues).
- **Known words:** "Mark known" on dictionary entries, reader words and deck rows; an optional onboarding step "I know these" through frequency bands for non-beginners, also reachable from Decks.
- **1T:** highlighted in the reader (with a 1T tab) and in the player's subtitle list, with "Mine".
- **Sentence bank:** the player indexes subtitles after loading or generating them; dictionary entries show Sentences from my media (clip playback, video frames), Tatoeba and, when turned on, Immersion Kit; "Mine this line" cuts the audio, grabs a frame and attaches both. Immersion Kit switch (off by default) with the terms note in Settings.
- **Lyrics:** Practice → Songs: import audio + LRC or plain lyrics, alignment with progress and cancel, karaoke view (line and word highlight, tap a word for the dictionary), per-line translation (own or AI with badge), grammar notes, cloze mode (auto or per-word picks), .lrc export.
- **Immersion log:** reader, media player, podcasts, songs and dialogues log automatically; Me has the roadmap card and the immersion card (heat-map, by source, manual entry, daily target).
- **Reader:** annotations (tap-to-select, highlight/box/note/grammar span, Notes tab), Words tab with drill and "add all to reviews", screenshot import (Photo Picker → ML Kit OCR → `importScreenshots`, page pictures shown), guide links on grammar points.
- Strings in `values` and `values-ja` (293 new).

### Shared additions
None. The app module now compiles against Okio (already shipped via `shared`) because audio-pack APIs expose Okio types.

### Deferred
- Online (Immersion Kit) lines don't play audio or show pictures (D-185), and dictionary sentence hits can't be mined yet (D-189).
- Reader tokens still color by SRS stage only; words marked known don't change the reader's "known" styling (shared deferral above).
- Nothing here was run on a device yet: only `:androidApp:assembleDebug` was verified.

### How to run
```
./gradlew :androidApp:assembleDebug -Ptsumugi.native=false
```

## Phase 11 (iOS UI) and audio packs on iOS (2026-09-18)

The SwiftUI screens for everything in the two Phase 11 shared-core sections below, plus rule 20 audio on iOS. Decisions D-190…D-198. **Not compiled:** CI is paused (D-140) and there's no Xcode here. Every new interop spot is listed under "iOS: unverified since CI paused". The Kotlin bridge additions compile (`:shared:compileCommonMainKotlinMetadata`).

### What was built (`iosApp/Tsumugi`)
- **Audio packs (rule 20):**
  - `ensureBundled()` runs at launch.
  - `Platform/PackAudio.swift`: `PackAudio.path(key)`, `VoicePlayer.say(_:key:graph:)` / `sayLines(_:graph:)`, and `PackClipPlayer`, which plays pack clips and falls back to TTS.
  - Pack clips play in: exam listening (strict play-once unchanged) and attempt review, dialogues, minimal pairs, grammar examples (a play button per example), and shadowing. Side-by-side playback plays `pack:` references.
  - **Settings → Audio packs** (`Features/Settings/AudioPacksView.swift`): installed sets with size, clip count, version and credits; install from Files (copied out of the security scope off the main actor, then `installFile`); install from a typed server URL (`fetchManifest` → `download`, device-local, no default, D-096). Progress for each phase, Cancel, Retry, and Remove.
- **Media decks and Core decks** (`Features/Decks/DeckViews.swift`, Learn → Decks):
  - Create a deck from a library text, an EPUB, subtitles or pasted text, and from the media player's subtitles or the reader's menu. The preview shows words for 80/90/95/98%, JLPT/ILR, coverage, kanji and study-order words, with progress and Cancel.
  - Save, optionally with "study this deck". Then a deck list and a detail view with words (swipe Known / Not known), kanji, grammar links, rename and delete.
  - Lesson controls: study this deck, interleave or deck only, stop. The Core 2k/6k/10k decks are paged by frequency, with known counts and "covers X% of your media".
- **Coverage overlay:**
  - `UI/CoverageUI.swift` builds the localized "You know X% of the words · Y% of the kanji · N new words to reach 95%" sentence and a difficulty badge (JLPT · ILR · score).
  - Reader: a card at the top of each text.
  - Media player: a card after subtitles load.
  - Library: a Recent / Coverage sort, per-row known % and difficulty badge, and "Measure coverage of N more texts" (`profileLibrary`, progress and Cancel).
- **Mark known:**
  - The dictionary entry has "I know this word" (and Undo). The reader popup has Known / Not known; marked words stop being coloured as unknown and coverage refreshes.
  - Onboarding has an optional "Words you already know" step, pages of 40 from the frequency list (D-196).
- **1T sentences:**
  - Reader: "Highlight one-new-word sentences" (mint; the target word stronger), plus a list with Mine and Show (scrolls to the sentence).
  - Media player: "Lines with one new word", each with Mine and a jump to the line, and highlighted in the cue list.
- **Sentence bank:**
  - Media player: indexes cues after .srt/.vtt load or Whisper generation, with a stored locator (D-191). "Mine line" cuts the clip and grabs a frame, then `attachMedia`.
  - Dictionary entry: a "Sentences" section grouped by source (your media per title, Tatoeba, Immersion Kit). Library lines play their clip (cut on demand and cached) and show a frame thumbnail from `AVAssetImageGenerator`. "Mine" makes a word card or a sentence card.
  - Settings → Example sentences: the Immersion Kit toggle, off by default, with the terms note. Its results are text only (D-198).
- **Lyrics** (Practice → Lyrics, `Features/Practice/LyricsViews.swift`):
  - A songs list with an honest empty state. Import audio (copied into Application Support, D-192) plus an .lrc/.txt file or pasted lyrics.
  - Karaoke view: line and word highlight from `Karaoke.at`, tap a line to seek, "Align with Whisper" (progress, Cancel), cloze mode (pauses when a hidden word is sung, then type it or show it), and LRC export via the share sheet.
  - Line study (press and hold a line): tap words for the dictionary, grammar notes, your own translation, or an AI translation with its badge.
- **Immersion log** (Me):
  - The reader logs a ticket while the text is open. The media player, lyrics and dialogues report the time actually played; podcasts log as PODCAST. Open tickets are stopped on backgrounding (D-194).
  - Me has an immersion card (today against the target, heat-map) and a roadmap card (the stage, its milestones with progress, rule-11 persistent).
  - Immersion log screen: daily target, 20-week heat-map, active/passive totals, minutes per source, manual entry, and recent sessions (swipe to delete).
- **Reader** (`ReaderViews.swift`, `ReaderExtrasViews.swift`):
  - Annotations: pencil mode, tap the first and last word, then highlight / box / note / grammar (D-193). A Notes list covers edit, delete and detached annotations.
  - "Words in this text" (every popup records the lookup) with remove, "Add all to reviews" and Drill (context cards: type the reading, type the meaning, or self-check).
  - Screenshot import: PhotosPicker for up to 30 pictures → the existing Vision OCR → `importScreenshots`, with per-picture progress and Cancel. The pictures show as a page strip.
  - Guide links on every grammar point, opened in the browser.
- **Strings:** 310 new `Localizable.xcstrings` keys with Japanese. Every new async screen has an error + Retry state (F-33).

### Deferred
- Online (Immersion Kit) lines show no image or audio (D-198).
- The reader's furigana ("above my level") still ignores words marked known (the shared Deferred item below). The reader only stops colouring words the learner marked in this session.
- Lyrics translations and cloze picks aren't in the LRC export (shared, D-163).

### How to check
On the Mac, build as described in "iOS: unverified since CI paused", fix any interop names from the list there, then run the Phase 11 items in `docs/QA.md`.

---

## Phase 11 (shared core, part 1): media decks, coverage, known words, difficulty, 1T (2026-09-18)

BRIEF_V2 §6.1, §6.4 (difficulty score) and §6.11 (1T mining). Decisions D-150…D-159. Platform UI is not built yet; the hooks are listed below.

### What was built (`shared/…/coverage/`, `shared/…/decks/`)
- **Media decks** (`MediaDeckService`): `vocabularyForDocument/Text/Subtitles/Epub` → `MediaVocabulary` (words in study order, kanji with counts, grammar point ids, stats: unique words, words for 80/90/95/98%, JLPT/ILR) → `save` → synced `media_deck` + `media_deck_word`. `decks()`, `deck(id)`, `deckWords`, `rename`, `delete`.
- **Coverage overlay** (`CoverageService`): `documentCoverage(id)`, `subtitleCoverage(mediaKey, srt)` and `textCoverage(text)` return `DocumentCoverage` (TextCoverage `summary` = "You know X% of the words · Y% of the kanji · N new words to reach 95%", plus the difficulty). `librarySortedByCoverage()` and `profileLibrary(onProgress)` fill in the rest.
- **Learner knowledge** (`LearnerKnowledge`): a snapshot of SRS stages (Guru+ known, Apprentice learning) plus `known_word`, cached behind a fingerprint query.
- **Known words** (`KnownWords`): `markKnown`, `markUnknown`, `frequencyBatch(afterOrd, size)` for the onboarding "I know these" flow, and `bands()`.
- **Core decks**: `frequencyDecks()` (Core 2k/6k/10k with known counts and "covers X% of your media") and `frequencyDeckWords`. Every media deck also reports `libraryCoverage`.
- **Deck lessons** (`DeckLessons`): `activate(deckId, mode)`, INTERLEAVE or DECK_ONLY. `AppGraph.startLessons()` mixes deck words with path items, and Today counts them via `adjust`.
- **Difficulty score** (`DifficultyScorer`, formula in `docs/CONTENT_PACKS.md`) replaces `SimpleImmersionDifficulty` in Today (`ScoredImmersionDifficulty`).
- **1T sentences**: `oneTargetSentences(documentId)`, `oneTargetCues(srt)` (with cue index and times), and `mineOneTarget`.

### Schema and packs
- User DB `5.sqm` (v5 → v6) with the `databases/5.db` snapshot: `media_deck`, `media_deck_word` and `known_word` (synced, triggers, in `SyncTables` and `SYNC_PROTOCOL.md`), plus the device-local `text_profile`.
- `dictionary.sqlite` gains `freq_word` (`tools/packs/build_decks.py`, run by `build_all.py` after `build_sentences.py`): **10,000 words** (Core 2k/6k/10k = its prefixes), all with Tatoeba hits (lowest count 4), 253 function words left out. Owner: rebuild packs (`uv run python packs/build_all.py`, or just `packs/build_decks.py` on an existing dictionary pack).
- `IlrBandData.kt` is generated from `tools/items/ilr_bands.json` by `tools/packs/gen_ilr_bands.py` (`--check` in CI).

### Tests
- `CoverageTest` (11): profiles, snapshot matching (jmdict/v:/wk:/anki:, marks, kanji), overlay and words-to-95%, cache invalidation by reviews and marks, library sort, 1T in documents and cues, the difficulty ordering, the abstract measure, the immersion matcher, frequency batches.
- `MediaDeckTest` (5): document, subtitles and text decks, save/replace/delete, Core decks with library coverage, and deck lessons (interleave, completion with context, skip known, Today adjust).
- `Phase11SyncTest`: decks, words and known words sync, the known flag is LWW, deck delete propagates, profiles stay local.
- `UserDbMigrationTest`: extended to v6.
- `DifficultyCalibrationTest` (real packs): DLPT means per ILR level 0+ 14.4 · 1 15.5 · 1+ 22.8 · 2 41.2 · 2+ 51.0 · 3 57.3; 98% of pairs two or more levels apart in order; 59/60 labels within one step. It also checks that the real pack has the 10,000-word Core list.

### UI hooks for the platform agents (all on `AppGraph`)
- `coverage`: overlay on the reader and player (`documentCoverage`, `subtitleCoverage`, with progress), the library sort, 1T highlight lists (`oneTargetSentences`, `oneTargetCues`) and "Mine" (`mineOneTarget`).
- `decks`: "Create deck" from a document, EPUB path, subtitles or text (preview `MediaVocabulary` → `save`), the deck list and detail, and Core decks.
- `deckLessons`: "Study this deck" (`activate`) and a mode switch.
- `knownWords`: "Mark known" on reader words, and onboarding batches.
- All new suspend APIs are `@Throws`.

### Deferred
- The reader's own `known` flag (furigana "above my level", `reader_doc.known_ratio`) still reads SRS stages only, so words marked known don't hide furigana yet. The coverage overlay does include them.
- Without the path pack, Today shows no lessons block for deck lessons (D-154).
- The Wikipedia-based frequency list (D-152).
## Phase 11: Immersion pipeline, shared core part 1 (BRIEF_V2 §6.2, §6.3, §6.4 annotations/vocab, §6.11) (2026-09-18)

Shared code and tests only; the platform screens are next. Decisions D-160…D-169. The media decks, coverage, difficulty score, frequency decks, known words and 1T mining are a parallel part of Phase 11.

### What was built
- **Sentence bank (§6.2):** `AppGraph.sentenceBank` indexes every cue of a media item with subtitles (`index(mediaId, title, kind, locator, cues, source, onProgress)`) by JMdict id and lemma. `AppGraph.sentenceSearch.forEntry(entryId, word, reading)` returns library lines, then Tatoeba, then optional online lines, each tagged with its source. Library hits carry `ClipSpan(start, end, thumbnailMs)` for on-demand extraction.
- **Mine this line:** `AppGraph.sentenceMiner.mineLine(SENTENCE|VOCAB, …)` → `MineDraft` (the platform cuts the audio and grabs the frame) → `attachMedia(draft, durationMs, imageWritten)` → `render(itemId)` (sentence split around the highlighted word, audio/picture paths, TTS fallback).
- **Immersion Kit (optional):** `AppGraph.onlineExamples`, off by default per device, v2 API, session-only memory cache, never stored (docs/INTEGRATIONS.md).
- **Lyrics & karaoke (§6.3):** `AppGraph.lyrics`: import audio + `.lrc` (line or enhanced word LRC) or plain lyrics; `align(songId, mediaHash, pcm)` via Whisper segments; `Karaoke.at(lines, positionMs)`; per-line translation (learner's or labeled AI); `lineStudy` (tap words, grammar notes); cloze (`setCloze`/`autoCloze`/`clozeSession`), LRC export. Device-local.
- **Immersion log and roadmap (§6.11):** `AppGraph.immersion` (start/stop tickets, `report`, `addManual`, `delete`, `days`, daily target), synced by union + tombstone. The Today immersion block completes when the target is met; `stats.immersionHeatmap(days)`. `AppGraph.roadmap.status()`: four stages, milestones in known words, hours, graded-reader score (Phase 12 hook) and OPI; the reached stage never drops (rule 11).
- **Reader (§6.4):** `reader.annotations` (box, highlight, note, grammar span; synced per row by document key, re-anchored by quote), `reader.importScreenshots(pages)` with page images, `reader.vocabulary` (auto list of looked-up words, context-card `drill`), `reader.addDocumentWordsToReviews`, and `GuidesLibrary` (about 90 curated links, link-only).

### Schema
`migrations/6.sqm` → schema v7 (`databases/6.db` is the v6 snapshot, generated after the decks migration merged): `immersion_session` and `reader_annotation` (synced; triggers in `immersion.sq` / `readerNotes.sq`), plus the device-local `media_index`, `media_cue`, `media_cue_token`, `lyrics_song`, `reader_doc_meta` and `reader_doc_vocab`. Renumbered from `5.sqm` at merge time, as planned in D-169.

### Tests
`ImmersionTest` (5), `LyricsTest` (9), `SentenceBankTest` (5), `AnnotationsTest` (5), host `GuidesGrammarIdsTest`, and the v5 → v6 part of `UserDbMigrationTest`.

### UI hooks for the platform agents
- Media player: after loading or generating subtitles, call `sentenceBank.index(…)`. Play a hit's `clip` span; the frame is at `thumbnailMs`. "Mine this line" → `sentenceMiner.mineLine` → cut audio into `draft.audio.path`, frame into `draft.image?.path` → `attachMedia`. Log with `immersion.start(MEDIA, ACTIVE|PASSIVE, mediaId, title)` / `stop(ticket)`.
- Dictionary entry: a "Sentences" section from `sentenceSearch.forEntry`, grouped by `SentenceSource`; an Immersion Kit toggle (`onlineExamples.setEnabled`) with its label.
- Reader: `immersion.start(READER, ACTIVE, docId, title)` on open, `stop` on leave; `vocabulary.recordLookup(docId, token, sentence, gloss)` on every word popup; annotation tools over a selection (`annotations.add/update/delete/forDocument`); a "Words" tab with Drill; screenshot import (OCR each picture into `screenshots.screenshotFile()`, then `importScreenshots`), showing `screenshots.pageImages(docId)`; guides from `GuidesLibrary.forGrammarPoint(pointId)` open in the browser.
- Lyrics: a songs list, import (audio file + .lrc/.txt picker), "Align with Whisper" with progress and cancel, a karaoke view driven by `Karaoke.at`, cloze mode, per-line English with the AI badge when `aiTranslated`. The empty state says that there's no streaming or downloading: the songs are the learner's own files.
- Me: immersion heat-map and per-source breakdown (`immersion.days(n)`), manual entry, daily target setting, the roadmap card (`roadmap.status()`); the Today immersion block reads the target automatically. Podcasts log `PODCAST`, dialogues `DIALOGUE`.

### Deferred
- Translations and cloze picks aren't in the LRC export. Word-level Whisper timestamps aren't used (segment level, even spread by morae).
- Graded-reader comprehension feeds the roadmap once Phase 12 ships graded readers (`roadmap.comprehension`).

---

## Phase 9: Stabilize (BRIEF_V2 §4) (2026-09-18)

Every P0 and P1 item (F-01…F-34) is fixed, along with most P2 items (F-35…F-45). Decisions are D-040…D-085.

### Release blockers (P0)
- **F-01 App icon:** light, dark and tinted 1024 px variants, rendered from the Android vector (`tools/assets/render_icon.py`; CI checks they match).
- **F-02 iOS local network:** `NSLocalNetworkUsageDescription`, plus ATS `NSAllowsLocalNetworking` and exceptions for `*.ts.net` / `*.home.arpa` in `iosApp/TsumugiInfo.plist`.
- **F-03 Android cleartext:** allowed through `network_security_config.xml`; the hosts the app itself calls stay https-only.
- **F-04 Path progress persisted:** `path_progress` (merged by taking the higher level) and `path_unlock` (merged by union); a confirmed "Reset to level N".
- **F-05 Review undo:** tombstones instead of deletes; they sync, with a fast path for rows never pushed.
- **F-06 CI archive:** CI archives an unsigned Release build, and `tools/ci/validate_archive.py` checks it: icon, usage strings, three targets, frameworks, privacy manifests, LICENSES and packs.

### Serious (P1)
- **F-07 Reviews tab (iOS):** queue by item kind, forecast and leeches.
- **F-08 Reader translation:** a labeled `translate_sentence` result.
- **F-09 Double submit:** blocked by a Mutex in the shared session and disabled controls on both apps.
- **F-10 Cancellation:** per-generation ids on both native bridges; a cancelled call is never retried.
- **F-11 Timeouts:** on every client, plus a 60 s "endpoint unreachable" cache.
- **F-12 Keys:** one key per endpoint.
- **F-13 Model downloads:** hashed while writing, with a 1.5× free-space check. They run in the background: a background URLSession on iOS, WorkManager on Android.
- **F-14 iOS audio:** one `AudioSessionController`, with interruption and route-change handling and background audio.
- **F-15 iOS backups:** packs, models and tts are excluded.
- **F-16 Packs:** iOS opens them in place. Android copies with a verified hash, an atomic rename and progress.
- **F-17 Stages:** stage lookup can no longer fail. The audit's crash couldn't happen through the database, but the code is hardened anyway.
- **F-18 FSRS:** already matched py-fsrs 6.3.2; reference vectors added.
- **F-19 Lesson counts:** count distinct items.
- **F-20 Grammar cards:** undo retracts the ghost card it spawned; points without examples are held out of reviews.
- **F-21 Pitch targets:** looked up by lemma, with conjugation rules.
- **F-22 Shadowing DTW:** banded, two rows.
- **F-23 Local model:** reloads on switch, trims history, and shows a failure banner instead of splicing in scripted turns.
- **F-24 Exam attempts:** resumable and timed by wall clock on both apps.
- **F-25 Writing canvas:** no longer hijacked by scrolling.
- **F-26 Reader tokenizer:** the lattice tokenizer, run in the background with progress.
- **F-27 Stats:** a materialized `daily_stats` table and an indexed stages query.
- **F-28 Lesson order:** follows the pack.
- **F-29 Android seed:** random per generation.
- **F-30 VOICEVOX temp files:** unique per synthesis.
- **F-31 Device settings:** device-local `device_setting` table; manual unlocks are per-item rows.
- **F-32 Pack slots and scheduler:** each pack opens in its own slot, and new FSRS weights trigger a background recompute with progress.
- **F-33 Error states:** error and retry on every async screen.
- **F-34 Notification permission:** asked after the first review session, with an explanation.

### P2
- **F-35 Meaning answers:** accepted in any script.
- **F-36 iOS furigana:** placed per segment.
- **F-37 End-to-end sync:** hides keys behind HMAC ids (protocol v2).
- **F-38 Pinned sources:** 18 sources pinned in `sources.lock`, with the Tatoeba exports mirrored to this repo's `sources-tatoeba-2026-09-12` release.
- **F-39 Grammar highlighting:** matched on token boundaries.
- **F-40 LicensesTests:** check the licenses file ships in the bundle.
- **F-41 Whisper:** can be cancelled.
- **F-42 File types:** document types and "Open with" on both platforms.
- **F-43 Export compliance:** notes in RELEASE.md.
- **F-44 Frameworks:** pins verified.
- **F-45 License citations:** corrected, and an "Inspiration, no content used" section added.

### Schema
The user database uses SQLDelight migrations now (`migrations/1.sqm`, `2.sqm`, version 3). `verifySqlDelightMigration` runs in the build, and `UserDbMigrationTest` migrates real v1 data.

### Regression tests (rule 17)
- **Path and SRS:**
  - `PathProgressTest` (4 tests, including `levelStaysPassedAfterLapsesAndLevelFiveLessonsStayAvailable`).
  - `SyncMergeTest.{undoAfterPushConverges, undoBeforePushNeverLeavesTheDevice, undoDuringAPushTombstones, pathProgressMergesToTheHigherLevel, pulledSettingsAreReportedToTheApp}`.
  - `ReviewSessionTest.{concurrentSubmitsRecordOneReview, wrapUpDuringSubmitIsDeferredNotLost}`.
  - `UnlockTreeTest.{stageIsTotalForStartedCards, lessonOrderFollowsPackPosition}` and `SrsRepositoryTest.itemWithTwoLearningCardsHasAStage`.
  - `FsrsTest` reference vectors for Hard and same-day reviews.
  - `TodayPlannerTest.lessonCountsAreDistinctItems`.
  - `GrammarServiceTest.{undoOfAMissRetractsTheGhostItSpawned, pointsWithoutExamplesAreHeldOutOfReviews}`.
  - `materializedStatsMatchTheReviewLog`.
  - `deviceSettingsNeverSync` and `UserDbMigrationTest`.
- **Network, AI and models:**
  - `TimeoutsTest` (6 tests) and `EndpointKeysTest` (3 tests).
  - `ModelManagerTest.{hashesWhileWritingWithoutSecondPass, resumeRehashesThePartialFileOnce, corruptResumedPartIsCaughtByIncrementalHash, refusesToStartWithoutOneAndAHalfTimesTheSpace}` and `DownloadedModelInstallerTest` (4 tests).
  - `PackInstallerTest` (9 tests).
  - `LocalEnginesTest.{switchingModelsReloads, modelLoadedOutsideTheSlotIsReplaced, slotUnloadForgetsThePath}` and `RoleplaySessionTest` (5 tests).
  - `cancellationIsNeverRetried`, `cancelledStatusBecomesAiCancelled`, `whisperCancellationStopsTheBridge` and `whisperCancelledStatusBecomesAiCancelled`.
  - `SynthesizedAudioFilesTest` (3 tests).
- **Speech and reader:**
  - `PronunciationTargetsTest`: 食べます, 食べました, 高かった.
  - `PronunciationTest.{rollingDtwMatchesTheFullMatrix, sixtySecondShadowingStaysSmallAndFast}`.
  - `ReaderLatticeTest` (3 tests), `particleInsideAWordIsNotAGrammarHit` and `endingInsideAnAdjectiveIsNotTai`.
  - `meaningsInAnyScriptMatch` and `meaningNormalizationFoldsCaseAndWidth`.
- **Exams:** `ExamResumeTest` (5 tests).
- **iOS:** `LicensesTests`.

### Not yet verified (owner, on the Mac and iPhone)
- The Phase 9 QA pass in `docs/QA.md` and the signed TestFlight archive (`docs/RELEASE.md` §4).
- Export compliance: `ITSAppUsesNonExemptEncryption = NO` may be wrong, because end-to-end sync uses our own XChaCha20 (D-067). This is the owner's legal call.

### Known gaps carried forward
- Aozora ruby isn't kept with saved documents yet (G-07).
- The server leaderboard still counts reviews that were later undone. The leaderboard comes in Phase 14.
- Background model downloads on iOS verify the file in a second pass after it arrives.
- Strings added in Phase 9 aren't in `Localizable.xcstrings` yet. They fall back to English.

### iOS: unverified since CI paused
CI stopped running on push at D-140. The last CI compile got as far as `Platform/Recordings.swift`. Swift that compiled up to `ccce711` (Phase 9) is trusted. Everything below was written after that and has only been checked by reading it against the Kotlin sources (Kotlin/Native + SKIE 0.10.14 naming rules). The Kotlin framework itself did build for iOS in that last run.

**Swift files changed since `ccce711`:**
- App: `App/RootView.swift`, `App/TsumugiApp.swift`, `UI/SharedText.swift` (new).
- Today: `Features/Today/TodayView.swift`.
- Study: `ReviewView.swift`, `ReviewModes.swift` (new), `KanaCourseView.swift` (new), `PersonalCardsView.swift` (new), `GrammarViews.swift`, `LessonView.swift`, `PathViews.swift`, `ReviewsHomeView.swift`, `StudyComponents.swift`.
- Practice: `FreeTalkView.swift` (new), `PodcastViews.swift` (new), `ShadowingView.swift` (new), `MediaPlayerView.swift`, `PracticeHubView.swift`, `PronunciationViews.swift`, `RoleplayViews.swift`.
- Reader: `ReaderViews.swift`, `ReadingQuestionsSheet.swift` (new).
- Me: `MeView.swift`, `MotivationViews.swift`, `ContentReviewView.swift`, `ExportView.swift`, `IntegrationsView.swift` (the last four are new).
- Other features: `Features/OnboardingView.swift`, `Features/Writing/WritingViews.swift`.
- Platform: `Recordings.swift` (new), `MediaDecoding.swift` (new), `AudioCapture.swift`, `ShareInbox.swift`, `VoicePlayer.swift`.
- Action extension (new target, doesn't link `Shared`): `TsumugiAction/ActionViewController.swift`.

**Fixed in the audit (not yet compiled):**
- `RecordingSaver.save` returns the recording id. The Kotlin `Recording` clashes with SQLDelight's table class `app.tsumugi.db.Recording`, so Swift never spells `Recording_` now.
- `MeView.swift` reads the developer switch with `deviceSettings.get(key:) == "true"`. It no longer calls `bool(key:default:)`, because `default` is a C keyword in the Objective-C header.
- `ConversationPatternsCard.label` takes `Shared.ErrorType`, module-qualified so it can't resolve to Swift's old `ErrorType` name.
- `ReaderPitch.overlay` has `@Throws` like every other exported `suspend fun`.

**Interop spots that are still uncertain (check these first if the build fails):**
- Default arguments: SKIE 0.10.14's default-argument interop is off (there's no `skie {}` block in `shared/build.gradle.kts`), so every Swift call passes every Kotlin parameter. The audit checked each new call. A "missing argument" error means a call was missed.
- Nested and sealed types that Swift spells out: `ReviewStateAsking` (the same form as `ReviewStateRevealed` and `ReviewStateAnswered`, which compiled) and `LeaderboardStateFailed(message:)` (`LeaderboardView.load`). The `onEnum(of:)` case names are `TodayLaunch` → `.reviews/.lessons/.kana/.grammar/.immersion/.shadowing/.speaking/.writing`, `LeaderboardState` → `.notSignedIn/.optedOut/.encrypted/.failed/.rows`, and `ReadingQuestionsResult` → `.ready/.unavailable`.
- Enum cases that are only ever read through SKIE: `QuizMode.type` / `.pick` (a case named `type`), `FreezeResult.noFreezesLeft`, `ErrorType.wordChoice`, `AnswerMode.meaningChoice/.fillHint/.production/.minimalPair`, `DownloadState.done/.failed`.
- Properties with keyword-like names: `TodayBlock.optional` (`TodayView.blockRow`).
- Companion constants: `FocusTimer.companion.OPTIONS_MINUTES` (a `List<Int>`, read as `[KotlinInt]`) and `ClipService.companion.DEFAULT_PADDING_MS`. `MediaPlayback.shared.SPEED_*` follows the `FsrsOptimizer.shared.MIN_REVIEWS` pattern that compiled.
- Suspend functions that return primitives, which Swift sees boxed: `exportReviewCsv` / `exportBackup` (`KotlinInt`, read with `Int(truncating:)`), `recordings.totalBytes()` (`KotlinLong`), and `isOptedIn`, `isEnabled` and `fileExists` (`KotlinBoolean`). `stats.freeze(day:)` returns a Kotlin enum that Swift switches on directly.
- Kotlin interfaces implemented in Swift: `PcmDecoder: NSObject, PcmWindowReader` (`durationMs() -> Int64`, `read(startMs:endMs:onDone:)` with `(KotlinFloatArray?, String?) -> Void`), in the same shape as `WhisperBridge`.
- Overloads: `SwiftSupport.reviewQueue(graph:)` and `reviewQueue(service:kind:)`. `ReaderToken.showFurigana(mode:learnerJlpt:)` (a member) sits next to the new extension `showFurigana(mode:level:)`.
- The new `TsumugiAction` target in the pbxproj: IDs `7A5E…80`–`8B` are all defined and referenced consistently, and the settings mirror `TsumugiShare`. It hasn't been built yet.
- Strict concurrency is `complete` in Swift 5 mode, so Sendable problems (for example `UIImage` captured in `Task.detached` in `PersonalCardsView.savePicture`) appear as warnings, not errors.

**What to run first on the Mac:**
1. `bash tools/models/fetch_ios_frameworks.sh`. It fetches llama/whisper. The app also builds without them.
2. Build for the simulator. The Kotlin framework is built by the Xcode "Compile Kotlin Framework" phase (`./gradlew :shared:embedAndSignAppleFrameworkForXcode`), so a JDK 21 must be on `PATH` / `JAVA_HOME` for Xcode:
   ```sh
   xcodebuild build -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi \
     -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO 2>&1 \
     | tee build/xcodebuild.log | grep -E "error:|warning: .*/iosApp/|BUILD (SUCCEEDED|FAILED)"
   ```
   Use any installed iPhone simulator (`xcrun simctl list devices available`). Then run CI's full check: `xcodebuild test` with the same arguments plus `-resultBundlePath build/TestResults.xcresult`, and the unsigned Release `xcodebuild archive … -destination generic/platform=iOS` followed by `python3 tools/ci/validate_archive.py build/Tsumugi.xcarchive` (see `.github/workflows/ci.yml`).
3. If a Swift name doesn't resolve, look it up in the generated header `shared/build/xcode-frameworks/Debug/iphonesimulator*/Shared.framework/Headers/Shared.h` (its `swift_name` attributes) or in SKIE's Swift files next to it. Don't guess the name.


**Phase 11 iOS UI and audio packs (D-190…D-198), not yet compiled. Check these first if the build fails.**

New files:
- `Platform/PackAudio.swift`, `Platform/MediaLibrary.swift`, `UI/CoverageUI.swift`
- `Features/Settings/AudioPacksView.swift`, `Features/Decks/DeckViews.swift`
- `Features/Dictionary/SentenceBankViews.swift`, `Features/Reader/ReaderExtrasViews.swift`
- `Features/Practice/LyricsViews.swift`, `Features/Me/ImmersionViews.swift`

Changed files:
- `ReaderViews.swift` (library and reader rewritten), `MediaPlayerView.swift`, `EntryView.swift`, `OnboardingView.swift`
- `GrammarViews.swift`, `ListeningViews.swift`, `MinimalPairsView.swift`, `ShadowingView.swift`
- `ExamRunnerView.swift`, `AttemptReviewView.swift`, `Recordings.swift`, `MeView.swift`, `RootView.swift`, `PracticeHubView.swift`, `TsumugiApp.swift`

The uncertain spots:
- **`KotlinLong(longLong:)`:** the boxed-`Long` initializer, used to build `[KotlinLong]` for `markKnown` / `markUnknown` / `mineLine(entryId:)`. Earlier code only ever built `KotlinInt(int:)` and `KotlinBoolean(bool:)`. Call sites:
  - `DeckViews.swift:562`, `:702`
  - `SentenceBankViews.swift:46`
  - `OnboardingView.swift:179`
  - `ReaderViews.swift:812`, `:816`
  - `MediaLibrary.swift:143`

  If the initializer is spelled differently, use `KotlinLong(value:)`, or add a bridge that takes `[Int64]`.
- **Progress closures with boxed primitives** (`(Double) -> Unit` → `(KotlinDouble) -> Void`, read with `.doubleValue`):
  - `DeckViews.swift:370`, which feeds the four `vocabularyFor…` calls
  - `ReaderViews.swift:471` (`documentCoverage`), `:491` (`oneTargetSentences`)
  - `MediaPlayerView.swift:401` (`subtitleCoverage`), `:408` (`oneTargetCues`)

  `profileLibrary { done, total in … }` gets `KotlinInt`s, read with `Int(truncating:)` (`ReaderViews.swift:173`). All of these rely on a trailing closure after a SKIE async call.
- **Object-typed progress closures:**
  - `sentenceBank.index(…) { p in p.done / p.total }` (`MediaPlayerView.swift:394`)
  - `alignLyrics(…) { p in p.fraction }` (`LyricsViews.swift:607`)
- **Suspend functions returning primitives, assumed boxed:**
  - `addDocumentWordsToReviews` → `KotlinInt` (`ReaderExtrasViews.swift:89`)
  - `immersion.targetMinutes()` → `KotlinInt` (`ImmersionViews.swift:279`)
  - `onlineExamples.enabled()` → `KotlinBoolean` (`SentenceBankViews.swift:302`)

  Suspend `Unit` functions (`markKnown`, `activate`, `setMode`, `deactivate`, `delete`, `setTargetMinutes`, `removeAudioPack`) are assumed to return `Void`.
- **The `new…` getter:** `TextCoverage.newWordsTo95` is never read from Swift. `SwiftSupport.coverageWordsTo95` wraps it (`CoverageUI.swift:10`).
- **Kotlin enum members read through SKIE Swift enums:**
  - `CoreDeck.id` / `.title` (`DeckViews.swift:69`, `:180`, `:670`)
  - `RoadmapStage.number` (`ImmersionViews.swift:133`, `:141`)
- **Enum case names:**
  - `LyricsTiming` `.lrcWords/.lrcLines/.aligned` (`LyricsViews.swift:67`). The `NONE` case is deliberately never named.
  - `DeckLessonMode.interleave/.deckOnly`
  - `MilestoneMeasure.knownWords/.immersionHours/.readerComprehension/.opiLevel` (`ImmersionViews.swift:39`, `:48`, `:54`)
  - `ReferenceKind.packAudio` (`Recordings.swift:203`)
  - `ClozeState.close` (a case named `close`; `LyricsViews.swift:466`)
  - `AnnotationKind.box/.highlight/.note/.grammar`
  - `ImmersionOrigin.reader/.media/.podcast/.dialogue/.manual`
  - `MediaKind.video/.audio`, `CueSource.file/.generated` (`MediaPlayerView.swift:114`)
  - `MineKind.vocab/.sentence`, `WordState.known/.learning`
- **Keyword argument label:** `reader.screenshots.screenshotFile(extension: "jpg")` (`ReaderExtrasViews.swift:513`), where the Kotlin parameter is named `extension`.
- **Overloads:** `vocabulary.recordLookup(documentId:token:sentence:gloss:)` next to the 8-argument overload (`ReaderViews.swift:904`).
- **Types with members of clashing types:** `ClozeAnswer.verdict` has the clashing `Verdict` type. Swift reads only `accepted` / `expected` (`LyricsViews.swift:532`). `ClozeSession.score` and `ContextDrill.score` are `Pair`s and are never read.
- **Objects and companions:**
  - `AudioKeys.shared.exam/dialogue/grammar(…)` (`PackAudio.swift:16`–`24`)
  - `ReferenceClip.companion.pack(audioKey:)` (`PackAudio.swift:95`)
  - `GuidesLibrary.shared.forGrammarPoint(pointId:topics:)` (`GrammarViews.swift:166`)
  - `Karaoke.shared.at(lines:positionMs:)` (`LyricsViews.swift:546`)
- **Nullable `Long`/`Int`/`Double` fields read boxed:**
  - `SentenceHit.clip?.thumbnailMs?.int64Value` (`SentenceBankViews.swift:235`)
  - `OneTargetSentence.cueIndex?.intValue` / `.startMs?.int64Value` (`MediaPlayerView.swift:367`, `:595`)
  - `Milestone.current?.doubleValue` (`ImmersionViews.swift:48`)
  - `MineDraft.thumbnailMs?.int64Value` (`MediaLibrary.swift:153`)
- **StateFlow with an optional element:** `for await p in app.graph.audio.progress` (`AudioPacksView.swift:106`), the same pattern as `recomputeProgress`.
- **Data-class initializer from Swift:** `ScreenshotPage(image:ocrText:)` (`ReaderExtrasViews.swift:518`).
- **Decoding a pack clip:** `PcmDecoder.read` → `KotlinFloatArray.get(index:)` (`PackAudio.swift:118`).
- **Other APIs:** `AVAssetImageGenerator.image(at:)` (iOS 16+), `URL.bookmarkData(options: .minimalBookmark…)` for Files-picked media, and two `.sheet(item:)` modifiers plus one `.fileImporter` per view. SwiftUI honours only one file importer per view, so `DecksHomeView` shares one.

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
| Widgets | ✅ | ✅ (Glance: due reviews + streak, G-15) |
| Settings / Licenses | ✅ | ✅ |
| AI & speech settings (engines, model downloads, own server, STT, VOICEVOX) | | ✅ |
| Practice: role-play, pronunciation panel, OPI, Pomodoro session | | ✅ |
| Listening: dialogues, minimal pairs, media player with dual subtitles | | ✅ (Media3 ExoPlayer, G-15) |
| Exams: JLPT/DLPT hub, timed runner, results, attempt review, bank import | | ✅ |
| Onboarding | — | ✅ |
| Sync | Phase 5 | ✅ |
| Localization (en + ja UI) | | ✅ (in-app language picker on all versions, shared-core labels via `L10n`, G-14/G-15) |
| Accessibility (screen reader labels, Japanese speech for learner text, large fonts, 48dp targets, reduced motion) | | ✅ |
| Share / text-selection entry points ("Read in Tsumugi", "Look up in Tsumugi") | | ✅ |
| App icon, backup rules, release notes | | ✅ (sideload APK only) |
| Today: every block launches its screen, shadowing block, focus timer from any block (G-01) | | ✅ |
| Review modes: fill-in-with-hint, meaning choice, production (AI grade or self-grade), minimal pair (G-05, G-06) | | ✅ |
| Free talk + weekly speaking patterns on Me (G-02) | | ✅ |
| Recordings: add my recording, side-by-side playback, recordings sync switch (G-03) | | ✅ |
| Media: subtitle generation (Whisper, progress/cancel), speed 0.7–1.2×, clip to SRS, hide-subtitle quiz, podcasts (G-04) | | ✅ |
| Reader: furigana above my level, pitch overlay, comprehension questions (G-07) | | ✅ |
| Integrations: Notion push, AnkiConnect (G-09) | | ✅ |
| Export: reviews CSV, PDF report, JSON backup + merge restore (G-10) | | ✅ |
| Streak freezes, weekly challenge, opt-in leaderboard (G-11) | | ✅ |
| Personal picture/audio cards (G-12) | | ✅ |
| Kana course, placement check, onboarding kanji count (G-13) | | ✅ |
| Content review behind a developer toggle (G-16) | | ✅ |
| Audio packs: pre-rendered exam/dialogue/pair/grammar audio with TTS fallback, Settings → Audio packs (D-180) | | ✅ |
| Media decks, Core 2k/6k/10k, deck lessons (§6.1) | | ✅ |
| Coverage overlay, difficulty badge, library sort, "Your media" (§6.1, §6.4) | | ✅ |
| Mark known + onboarding "I know these" (§6.1) | | ✅ |
| Sentence bank: dictionary sentences from my media, clips + frames, mine this line, Immersion Kit switch (§6.2) | | ✅ |
| Lyrics: songs, alignment, karaoke, translation, grammar notes, cloze (§6.3) | | ✅ |
| Immersion log + roadmap on Me, automatic logging (§6.11) | | ✅ |
| 1T sentences in the reader and player (§6.11) | | ✅ |
| Reader: annotations, Words tab + drill, screenshot import, guide links (§6.4) | | ✅ |
