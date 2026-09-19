# Progress

Current phase: **v2 (BRIEF_V2.md) on branch `v2`. Phase 9 (stabilize) built; Phase 10 in progress.** The owner asked for v2 phases to run without per-phase stops; device QA and the TestFlight archive are the owner's, on the Mac (`docs/QA.md`, `docs/RELEASE.md`). The owner asked for all phases to run back to back, without per-phase review stops. CI (`.github/workflows/ci.yml`) builds packs and runs the shared, Android and iOS builds and tests on every push. Repo: https://github.com/budzillaorigin/tsumugi (private).

---

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
