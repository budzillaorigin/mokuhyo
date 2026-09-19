# Mac build log — Tsumugi v2 (XCODE_VERIFY_BRIEF.md)

Started 2026-09-19 14:25 CDT on the owner's Mac, branch `v2`, tree clean at `6f83e0a`.
Every rung below records the command, the first error, the diagnosis, the fix, the commit and the rebuild time.

## §1 Preconditions

| Check | Result |
|---|---|
| macOS | 26.3.1 (25D2128) |
| Xcode | 26.2 (17C52) at `/Applications/Xcode.app`; first-launch/license check passes (`xcodebuild -checkFirstLaunchStatus` exit 0) |
| `xcode-select -p` | `/Library/Developer/CommandLineTools` — **not** the full Xcode. `sudo xcode-select -s` needs a password this session can't supply, so every `xcodebuild`/`xcrun` call below runs with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, which is equivalent. **Owner:** run `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` once so the "Compile Kotlin Framework" phase and plain `xcrun` work from any shell. |
| iOS simulator runtime | iOS 26.2 (23C54). Devices: iPhone 17, 17 Pro, 17 Pro Max, Air, 16e. There is no "iPhone 16" device, so the ladder uses **`name=iPhone 17`**. |
| Java | Temurin 21.0.12.1 (JDK 21) |
| Gradle wrapper | 9.7.1 (D-002), Kotlin plugin 2.4.10 per `libs.versions.toml` |
| uv | 0.12.17 |
| xcbeautify | not installed; logs go through `tee` + `grep -E "error:"` |
| git | branch `v2`, `git status --short` empty |

Inputs the Xcode build phases expect:

- `bash tools/models/fetch_ios_frameworks.sh` — re-ran cleanly in 2.2 s from the cached zips (`tools/.cache/frameworks`, SHA-256 verified). No fix to the pinned tags was needed (F-44 stands): llama.cpp `b11040`, whisper.cpp `b5130`.
  - `iosApp/Frameworks/llama.xcframework` 192 MB — slices `ios-arm64`, `macos-arm64_x86_64` (**no simulator slice**, as the pbxproj "Embed Native Frameworks" phase already expects).
  - `iosApp/Frameworks/whisper.xcframework` 212 MB — slices `ios-arm64`, `ios-arm64_x86_64-simulator`, macos, tvos, xros.
- `cd tools && uv sync && uv run python packs/build_all.py` — packs were already present from a build at 08:11 today; re-run started 14:29 in the background (log in this session's scratchpad). Existing `content/packs`:

| Pack | Size |
|---|---|
| dictionary.sqlite | 130.2 MB |
| tokenizer.sqlite | 24.9 MB |
| exam.sqlite | 3.2 MB |
| kanji-path.sqlite | 2.1 MB |
| grammar.sqlite | 1.8 MB |
| tracks.sqlite | 1.5 MB |
| practice.sqlite | 1.4 MB |
| readers.sqlite | 0.7 MB |
| linguist.sqlite | 0.7 MB |
| manifest.json | 9 packs |
| audio-*.zip (7 sets) + audio-manifest.json | 213 MB total; only pitch (2.1 MB) and minimal-pairs (6.6 MB) are bundled by the "Bundle Content Packs" phase |

## §0 rule 2 — user-visible feature list (from docs/PROGRESS.md)

This is the acceptance list. After the ladder is green, every line is walked in the simulator (§4) and anything whose behaviour changed is called out under "Behaviour changes" at the end of this file.

**Shell and Today**
1. Five-tab shell (Today · Reviews · Learn · Practice · Me), global dictionary search from the toolbar.
2. Today plan: budget picker, weekly challenge, every block launches its screen, shadowing block, focus timer from any block (G-01), streak freezes (G-11).
3. Onboarding: goals, kana course, placement check, kanji count, optional tracks step, optional "I know these" words step (G-13, §6.1, §6.5).

**Reviews / Study**
4. Reviews tab: queue by item kind, forecast, leeches (F-07 — known placeholder-ish state, see §4).
5. Review session: kanji, vocab, grammar cloze / sentence-build, flashcards; modes fill-in-with-hint, meaning choice, production (AI grade or self-grade), minimal pair (G-05/G-06); undo = tombstone (F-05); double-submit blocked (F-09).
6. Lessons (kanji path, deck lessons with interleave/deck-only, grammar lessons).
7. Kana course (G-13). Personal picture/audio cards (G-12).

**Learn hub**
8. Kanji path: 60 levels, level grid, item pages with stroke order + myStory editor, skip-to-level, manual unlock, "Reset to level N" (F-04).
9. JLPT courses: modules, step progress, mastery checkboxes, quiz item-type drills, mock sections, "one book to pass" (§6.6).
10. Grammar: levels, points with AI badge, examples with pack-audio play button, guide links, grammar lessons; monolingual explanations toggle (§6.6).
11. Graded readers: library by level, genre chips, difficulty badge, Read (read-along with pack clips / system voice), Words, Quiz, Tasks incl. AI-graded summary (§6.4).
12. Library / Reader: import text, EPUB, Aozora, feeds; tokenized reader with furigana ("above my level"), pitch overlay, tap-to-look-up, sentence translation (F-08, labeled), comprehension questions (G-07), annotations (pencil mode), Words-in-this-text + drill, screenshot import (OCR), 1T sentences, coverage card, grammar constructions panel with "Practice this point" (§6.4, §6.16).
13. Tracks: select/deselect/only-this, track page (word lessons, kanji, drills, role-plays, dialogues, can-do, cultural tasks, ILR readings), drills: keigo, email, fill-in, synonym, meaning, usage, perform (§6.5).
14. Onomatopoeia: theme tiles with SVG glyphs, search, type filter, word detail, scene↔word quiz (§6.8).
15. Decks: media decks from library text/EPUB/subtitles/pasted, Core 2k/6k/10k, deck detail (Known/Not known swipe), lesson controls (§6.1).
16. Dictionary: as-you-type instant results, inflection chip, common/JLPT/rank chips, entry page (kanji, radicals, add to reviews / lists, "I know this word", Sentences section grouped by source incl. Immersion Kit opt-in, Expressions + collocations links, monolingual paraphrase), Parts/Explore/Kanji-with-parts shortcuts (§6.13, §6.15).
17. Kanji explorer: Canvas graph, focus, JLPT/frequency colouring, node cap, component roles, sound families, component search, bookmark to SRS (§6.15).
18. Radicals browser; component search; sound-series list.
19. Language arts: Thesaurus (emotion/scene clusters), Writing studio (drafts autosave, corrections, register check + rewrites, suggestions, readability, reader tasks), Translation workbench (passages by genre/direction, written + sight mode with English on-device recognizer, AI grade with diff or rubric self-assessment, history), Poetry corner (themes, Aozora source card), solo Reading circle (record each sentence, explain, per-sentence help, saved sessions) (§6.12–§6.14).
20. Handwriting practice (stroke grading, canvas not hijacked by scroll F-25), Scan (VisionKit live text + Vision photo OCR), Word lists, Personal cards.

**Practice hub**
21. Speak: role-play scenarios (model or scripted turns, corrections, natural version; failure banner F-23), free talk + weekly speaking patterns (G-02), pronunciation panel (pitch ↑↓ verdicts, fluency), shadowing (DTW), recordings (add mine, side-by-side playback, recordings-sync switch G-03).
22. Listen: dialogues (listen, gap-fill, order, questions; natural dialogues with greyed fillers and overlapping lines), minimal pairs drill, pitch-accent test (adaptive session, stats, "Say it"; hidden without the pitch pack), speaking drill sets (hands-free player, pause presets, background/lock-screen controls), media player (dual subtitles, tap-to-look-up, Whisper subtitle generation with progress/cancel, 0.7–1.2× speed, clip to SRS, hide-subtitle quiz, sentence-bank indexing, "mine this line", 1T lines, coverage card), podcasts (G-04), lyrics (import, karaoke, Whisper align, cloze, LRC export, line study) (§6.3).
23. Write: writing practice, writing studio link.
24. Games: Reflex and Atom, standalone and in the Pomodoro queue, best scores / week points (§6.9). Pomodoro sessions.
25. Exams: JLPT/DLPT hub (level, item counts, full mock / section / item-type drill, DLPT 180/60/30 with range picker + text-type chips), timed runner (countdown, grid, passage pane, play-once listening with pack audio), results (scaled score / ILR estimate, add missed to SRS), attempt review ("Explain with AI"), bank import, resumable attempts (F-24), disclaimers; OPI simulator (five phases, probe map after interview) (§6.16).
26. Translate entry point (→ workbench).

**Me hub**
27. Stats: streak, heat-map, stages, accuracy, forecast; vacation mode; streak freezes; weekly challenge; opt-in leaderboard (client stays off by default, D-314).
28. Immersion log + roadmap card; immersion log screen (target, heat-map, totals, manual entry, recent sessions) (§6.11). Translation skill line (Swift Charts).
29. Import & export: Anki `.apkg`, imiwa, Bunpro CSV, WaniKani token; export reviews CSV, PDF report, JSON backup + merge restore, `.apkg` (G-10).
30. Integrations: Notion push, AnkiConnect (G-09).
31. Sync: server URL, sign in / create account (10-char min), status, sync now, E2E passphrase, sign out, "Check your email" + Resend email (Phase 14).
32. Settings: reminders (notification permission asked after first session F-34), AI & speech (engine choice, model manager with background resumable downloads F-13, own OpenAI-compatible server + test connection, one key per endpoint F-12, STT engine, VOICEVOX), Audio packs (installed sets, install from Files / typed server URL, remove), Interests (tracks), Monolingual mode, Example sentences (Immersion Kit toggle), language picker (en/ja), developer toggle → Content review (G-16), Licenses screen (renders `docs/LICENSES.md`, F-40).
33. Widgets: "Reviews due" and "Kanji of the day" (D-021). Share extension "Read in Tsumugi" and Action extension "Look up in Tsumugi" (pure Swift, App Group `group.app.tsumugi`).

**Cross-cutting**
34. Localization en + ja via `Localizable.xcstrings`; accessibility labels, Japanese VoiceOver voice for learner text, Dynamic Type, Reduce Motion (Phase 8).
35. Every async screen has error + Retry (F-33) and an honest empty state when its pack is missing (rule 9). AI output carries the "AI-generated" badge (rule 10).
36. Packs open in place, read-only (F-16); packs/models/tts excluded from backup (F-15); audio session controller (F-14); on-device llama/whisper linked on device only, "not available in Simulator" on sim.

**Known, pre-existing, not to be fixed here (brief §4.2):** Reviews tab (F-07) and reader translation (F-08) are the "known" items from BRIEF_V2; Aozora ruby not kept with saved documents (G-07); Phase 9 strings not yet in `Localizable.xcstrings` (fall back to English); poem ruby shown as a list; dictionary instant search has no Retry (D-302).

---

## §2 Build ladder

### Rung 1a — shared Kotlin compiles for iOS
- **Command:** `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64`
- **Result:** BUILD SUCCESSFUL in 1m 48s (first run also installed NDK 29.0.14206865 for the Android project configuration and downloaded the Kotlin/Native LLVM 21 toolchain into `~/.konan`). Warnings only (unused variables, `!!` on non-null, `ExperimentalCoroutinesApi` opt-ins in tests). No errors.

### Rung 1b — shared tests on the iOS simulator
- **Command:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:iosSimulatorArm64Test`
- **First failure:** no error printed. The task linked `test.kexe` and then hung: after 35 min the test process (`simctl spawn --standalone <iPhone 17 Pro> test.kexe --ktest_logger=TEAMCITY`) had used 9 s of CPU and was blocked. Gradle buffers the TeamCity log, so nothing identified the test.
- **Diagnosis:** `sample <pid>` on the test process. The main thread is parked in `runBlocking` inside `ContentReviewSourcesTest.practiceListsOpiQuestionsAndDrillLines` (`ContentReviewSourcesTest.kt:166`). The `Dispatchers.IO` worker is inside `PracticeReviewSource.scenarios` (`ContentReview.kt:342`): its cursor mapper calls `turns("scripted_turn", id)`, which issues a second `executeQuery` on the same `SqlDriver` while the first cursor is still open. The SQLDelight native driver's reader pool (`Pool.borrowEntry` → `PoolLock.loopForConditionalResult`) waits for the one reader connection that the outer cursor holds. Deadlock. The JVM driver (`androidHostTest`) tolerates a nested query on one connection, which is why CI's JVM run and every earlier session passed. The production iOS `packDriver` (`PlatformServices.ios.kt`) uses the same default single reader, so the Content review screen (developer toggle, G-16) would have hung on a device when the practice pack has `llm` scenarios.
- **Fix (shared/):** `scenarios()` now reads every column into a row list first and calls `turns()` from the `.map` after the cursor closes, the same shape `dialogues()` and `ReadersReviewSource` already use. `SqlDriver.rows` got a doc comment stating that the mapper must not query. No behaviour change: the same candidates, ids, fields and display text. The regression test is the existing `ContentReviewSourcesTest.practiceListsOpiQuestionsAndDrillLines` run on `iosSimulatorArm64Test` (hung before, passes after); it is unchanged because its assertions already cover the output.
- **Commit:** `456f816`
- **Rebuild:** JVM `ContentReviewSourcesTest` 43 s, then `iosSimulatorArm64Test` 56 s (link + run): **712 tests, 0 failures** across 106 suites.

### Rung 2 — the framework Xcode links
- **Command as written in the brief** (`-Pkotlin.native.cocoapods.archs=arm64 -Pconfiguration=Debug -Psdk_name=iphonesimulator`): rejected in 0.6 s with `Please run the embedAndSignAppleFrameworkForXcode task from Xcode ('SDK_NAME', 'CONFIGURATION', 'TARGET_BUILD_DIR', 'ARCHS' and 'FRAMEWORKS_FOLDER_PATH' not provided)`. The Kotlin plugin reads Xcode's environment, not Gradle properties.
- **Reproduced the run-script phase** (`cd "$SRCROOT/.." && ./gradlew :shared:embedAndSignAppleFrameworkForXcode`) with the variables Xcode exports: `SDK_NAME=iphonesimulator CONFIGURATION=Debug ARCHS=arm64 PLATFORM_NAME=iphonesimulator TARGET_BUILD_DIR=<scratch> BUILT_PRODUCTS_DIR=<scratch> FRAMEWORKS_FOLDER_PATH=Tsumugi.app/Frameworks`.
- **Result:** BUILD SUCCESSFUL in 35 s. SKIE 0.10.14 tasks ran (`skieCreateConfiguration…`, `skieUnpackSwiftSources…`), then `linkDebugFrameworkIosSimulatorArm64` and `assembleDebugAppleFrameworkForXcodeIosSimulatorArm64`. `embedAndSign…` itself is SKIPPED because the framework is static (`isStatic = true`), which is correct. Output: `shared/build/xcode-frameworks/Debug/iphonesimulator/Shared.framework` (142 MB, Debug), exactly the `FRAMEWORK_SEARCH_PATHS` entry in `project.pbxproj`. No fix needed.
- **Note for the owner:** the build also runs `skieUploadAnalyticsDebugFrameworkIosSimulatorArm64` (SKIE's opt-out telemetry, on by default). Not changed here; `skie { analytics { enabled.set(false) } }` in `shared/build.gradle.kts` turns it off if wanted.

### Rung 3 — Xcode simulator build
- **Command:** `xcodebuild -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi -destination 'platform=iOS Simulator,name=iPhone 17' -configuration Debug build` (no "iPhone 16" device exists on this Mac).
- **Attempt 1 — first error** (the three run-script phases all ran; Kotlin framework built inside Xcode; Swift compiled up to two errors):
  ```
  Features/Study/ReviewModes.swift:108:34: error: cannot convert value of type 'PairSide_' to expected argument type 'PairSide'
  Features/Study/ReviewModes.swift:122:27: error: value of type 'PairSide' has no member 'text'
  ```
- **Diagnosis:** two Kotlin types share the simple name `PairSide`: the data class `app.tsumugi.study.PairSide` (a word of a minimal pair: text, reading, accent, gloss, which `MinimalPairPrompt` exposes and Swift renders) and the enum `app.tsumugi.audio.PairSide` (A/B, used only to build audio clip keys). The Objective-C export flattens packages and suffixes whichever type it meets second with `_`. `SwiftSupport.pairClipKey` was written on the assumption that the enum would be the one renamed ("without Swift naming the clashing `PairSide` enum"), but on this build the enum took `PairSide` and the data class became `PairSide_`. The order isn't stable, so Swift can't rely on either spelling.
- **Fix (shared/ + Android, rule 3):** rename the enum to `MinimalPairSide` so there is no clash at all. Touched `audio/AudioKeys.kt` (declaration + `minimalPair(pairId, side)` parameter), `api/SwiftSupport.kt` (import + `pairClipKey`), `commonTest/audio/AudioKeysTest.kt`, and the two Android call sites (`features/study/ReviewModes.kt`, `features/practice/MinimalPairsScreen.kt`). Clip keys, the Swift API and behaviour are unchanged. No Swift edit: `ReviewModes.swift` already spells the data class as `PairSide`, which is now its only Swift name.
- **Verification before rebuild:** `:shared:testAndroidHostTest --tests '*AudioKeysTest*'` and `:androidApp:assembleDebug -Ptsumugi.native=false` (rule 4), then attempt 2 below.
- **Attempt 1 wall time:**  (includes the in-Xcode Gradle framework build). Swift warnings: 38, all of the kind D-006 expected (Sendable / "sending … risks data races" / `@preconcurrency` hints in `ModelDownloads.swift`, `Recordings.swift`, `ShareInbox.swift`, `OpenedFiles.swift`, `PackAudio.swift`, `MediaDecoding.swift`, `PersonalCardsView.swift`, `LessonView.swift`, `PathViews.swift`), plus "Metadata extraction skipped. No AppIntents.framework dependency found" from `appintentsmetadataprocessor` (harmless). None turned into errors.
- **Commit (attempt 1 fix):** `9b82d83`. Android check: `AudioKeysTest` + `:androidApp:assembleDebug -Ptsumugi.native=false` BUILD SUCCESSFUL in 1m 9s.
- **Attempt 2 — first error** (wall ; the `PairSide` errors are gone):
  ```
  Features/Practice/MediaPlayerView.swift:595:43: error: value of optional type 'Int?' must be unwrapped to a value of type 'Int'
  ```
- **Diagnosis:** `isOneTarget` compared `$0.cueIndex?.intValue == Int32(index)`. `OneTargetSentence.cueIndex` is a Kotlin `Int?`, bridged as `KotlinInt?` (an `NSNumber`), and `NSNumber.intValue` is a Swift `Int` in this SDK (checked: `intValue: Int`, `int32Value: Int32`, `int64Value: Int64`), so the left side is `Int?` and the right side `Int32`. A Swift typo in a file that had never been compiled (PROGRESS.md listed this exact read as uncertain), not an interop mismatch.
- **Fix (Swift):** compare with `index` (already an `Int`). Same behaviour: a cue row is mint-highlighted when a 1T sentence points at it.
- **Commit (attempt 2 fix):** `b831183`.
- **Attempt 3:** `** BUILD SUCCEEDED **` in , 454 warnings, 0 errors. Rung 3 green.

### Rung 4 — Swift tests
- **Command:** `xcodebuild -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi -destination 'platform=iOS Simulator,name=iPhone 17' test`
- **Result:** `** TEST SUCCEEDED **` in  (build + run). 5 tests, 0 failures: `LicensesTests/bundleContainsLicenses`, `DictionaryTests/searchFindsDeinflectedVerb` (食べさせられなかった → 食べる against the bundled pack), `englishAndRomajiSearch`, `kanjiHasStrokes`, `lookupIsFast` (under the 10 ms Debug ceiling). No fix needed.

### Rung 5 — launch smoke on the simulator
- **Commands:** `xcrun simctl boot "iPhone 17"`, install of `…/Debug-iphonesimulator/Tsumugi.app` (284 MB Debug, includes XCTest frameworks from the test build; `packs/` holds all 9 sqlite packs, both manifests, `models-manifest.json`, `audio-pitch.zip`, `audio-minimal-pairs.zip`; `Frameworks/` holds `whisper.framework` only, as designed for the simulator), `xcrun simctl launch --console-pty booted app.tsumugi.ios`.
- **Note:** ending the `--console-pty` launcher after 25 s also ended the app (it's attached), so the smoke was repeated with a detached `simctl launch`. The app stayed alive past 20 s (pid confirmed), `log show --predicate 'process == "Tsumugi"'` matched only Default-level system lines whose *category names* contain "error" (`BoardServices:XPCErrors`), no fault/error/crash lines, and `~/Library/Logs/DiagnosticReports` has no Tsumugi `.ips`. The `app.tsumugi.model-downloads` background URLSession registers at launch as F-13 intends.
- **Screen reached:** the onboarding screen (紡ぎ · "Welcome to Tsumugi" · goal picker · Next), screenshot taken with `simctl io booted screenshot`. Home screen shows the F-01 icon.
- **Dictionary check (食べる for たべる):** there is no UI automation on this Mac (see §4 below), so the check was made in the app bundle by the test target: added `DictionaryTests/readingSearchFindsVerb` (a one-line `#expect` next to the existing deinflection test) and ran `-only-testing:TsumugiTests/DictionaryTests`: 5/5 passed in . Commit `4c7b675`.

### Rung 6 — device build (Release, arm64, unsigned)
- **Command:** `xcodebuild -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi -destination 'generic/platform=iOS' -configuration Release CODE_SIGNING_ALLOWED=NO build`
- **Result:** `** BUILD SUCCEEDED **` in , 472 warnings, 0 errors. Links `Shared`, `llama` and `whisper` (the `OTHER_LDFLAGS[sdk=iphoneos*]` set) and the "Embed Native Frameworks" phase copies both device slices. No fix needed.

### Rung 7 — unsigned archive
- **Command:** `xcodebuild … -configuration Release CODE_SIGNING_ALLOWED=NO archive -archivePath /tmp/Tsumugi.xcarchive`
- **Result:** `** ARCHIVE SUCCEEDED **` (Sat Sep 19 15:28:56 CDT 2026 → Sat Sep 19 15:29:45 CDT 2026), 456 warnings, 0 errors. No fix needed.
- **Info.plist audit:** `CFBundleIconName = AppIcon` (+ iPad), `NSLocalNetworkUsageDescription`, `NSMicrophoneUsageDescription`, `NSCameraUsageDescription`, `NSSpeechRecognitionUsageDescription` all present with the D-036/F-02 texts; `UIBackgroundModes = ["audio"]`; `ITSAppUsesNonExemptEncryption = false` (still the owner's legal call, PROGRESS "Not yet verified"); `MinimumOSVersion 17.0`; version 0.0.1.
- **Icon (F-01):** `AppIcon60x60@2x.png`, `AppIcon76x76@2x~ipad.png` in the bundle, 8 renditions in `Assets.car`.
- **Bundle:** `PlugIns/` TsumugiAction.appex, TsumugiShare.appex, TsumugiWidget.appex (376 KB together); `Frameworks/` llama.framework (7.7M) + whisper.framework (3.9M), device slices only; `PrivacyInfo.xcprivacy`; `LICENSES.md`; `packs/` with the 9 sqlite packs, both manifests, `models-manifest.json`, `audio-pitch.zip`, `audio-minimal-pairs.zip`.
- **`tools/ci/validate_archive.py /tmp/Tsumugi.xcarchive`:** `OK: archive structure valid`.
- **Size audit:** `Tsumugi.app` **214 MB** uncompressed against the 200 MB base target (BRIEF_V2 §9 item 7 / D-315). Breakdown: `packs/` 167 MB (dictionary.sqlite 130 MB, tokenizer.sqlite 25 MB, the rest 12 MB), main binary 35M, native frameworks 12 MB, extensions 0.4 MB. App Store thinning and compression will bring the download below this; the uncompressed install is what the owner should judge. Nothing was removed.

---

## §4 Finish

### 4.1 Full shared suite
`./gradlew :shared:allTests`: BUILD SUCCESSFUL in 55 s (up to date after the earlier runs). `testAndroidHostTest` **728 tests, 0 failures**; `iosSimulatorArm64Test` **712 tests, 0 failures**.

### 4.2 Feature walk in the simulator
**How it was driven.** macOS Accessibility control (System Events / AppleScript) needs a permission prompt only the owner can accept, so the walk used Meta's `idb` instead, which reads the simulator's own accessibility tree and taps by coordinate without that permission. Installed this session: `brew tap facebook/fb`, `brew trust --formula facebook/fb/idb-companion`, `brew install idb-companion` (1.6.1), `uv tool install fb-idb` (`~/.local/bin/idb`). The walker script lives in this session's scratchpad (not committed); it opens each hub link, screenshots it, records headings/node counts, flags any text matching *couldn't / error / not installed / retry / coming soon / placeholder / unavailable / failed / missing*, and swipes back. Every screenshot was taken with `simctl io screenshot`.

**Onboarding (item 3):** goal picker → kanji-count picker → "Quick kanji check (1/12)" (skipped with "Skip — start from level 1") → "Words you already know" page of 40 frequency words with Select all / Clear / Mark these and show more / Done → tracks step (7 tracks, "Skip — main path only") → "Start learning" / "Import my progress first". Reached Today. ✅

**Tabs (items 1, 2, 4, 27, 28):** Today (streak card, Freeze today, answers today, budget picker, "Foundations phase · about 21 min planned", 5 plan steps incl. Immersion with the AI-generated badge and Shadowing, weekly challenge, "Level 1 · 0% of kanji at Guru") ✅ · Reviews ("Due now — Nothing is due right now", Start, 7-day forecast, Leeches empty state) ✅ · Learn ✅ · Practice ✅ · Me (Progress, Vacation mode, 140-day heat-map, stage counts, Immersion today/target, Immersion log, Roadmap milestones, Conversation, Translation, Leaderboard, Export, Import & export, Sync, Settings, Integrations) ✅

**Learn hub — every link opened (items 6–20):** Kana (Hiragana section) · Kanji path (60 levels; Level 1 → "Radical (0/19) · Kanji (0/18)" → item ノ with "Used in" and "My story") · JLPT courses · Grammar (JLPT N5 → "N5 grammar" → です with "Watch out") · Graded readers (18 story buttons) · Reading (library, feeds) · Tracks · Onomatopoeia (14 theme tiles) · Decks (+ Core decks) · Dictionary (typed `taberu` → first hit **食べる, たべる, to eat**; entry: Word / Kanji / Conjugation / Sentences / Collocations, Add to reviews, Add to list, I know this word, Explain in Japanese, Listen on each sentence) · Kanji explorer · Radical search (224 radical buttons, "1 strokes" section) · Search by components · Expression thesaurus · Writing studio · Translation workbench (Passages) · Poetry corner (Themes) · Reading circle (Texts) · Draw to search (canvas, Undo/Clear/Strokes) · Scan text · Word lists · Personal card (Picture). None flagged. ✅

**Practice hub — every link opened (items 21–26):** Free talk · Role-play scenarios (daily section) · Speaking drills · Pronunciation check · Speaking session (Pomodoro) · OPI simulator · Dialogues (11 buttons) · Pitch-accent test (Drills; visible because the pitch pack is bundled) · Minimal pairs · Media player · Podcasts (honest empty state: "Add a podcast's RSS feed address…", + button) · Lyrics (empty state) · Kanji writing (Level 1 · 18 kanji) · Draw to search · Reflex and Atom (Games, This week) · Translation workbench · Exam simulators (JLPT section) · AI & speech settings (Language model section). None flagged. ✅

**Me hub — every link opened (items 27–32):** Freeze today (confirmation) · Immersion log (Last 20 weeks) · Translation workbench · Leaderboard (off by default, D-314) · Export (Review log) · Import & export (Anki…) · Sync (Account) · Settings (Lessons per batch stepper, Optimize from my reviews, Japanese-only explanations, Show examples from Immersion Kit, Sync my recordings and pictures, Tracks, Audio packs, AI & speech, Notion and AnkiConnect, Content review tools; Licenses opens and renders) · Integrations (Notion). ✅

**Not walked here (need hardware, a file, a server, or a session):** a full review/lesson session, microphone/camera screens beyond opening them, model download and the LAN endpoint, sync against a server, share/action extensions and widgets (built and archived, not exercised), audio playback. These are the owner's device QA in `docs/QA.md`.

**Observed, pre-existing, not changed (brief §4.2 "known"):** Reviews shows its implemented queue/forecast/leeches (F-07 is done; it's simply empty on a fresh install). The Me "Conversation" section is a heading with no rows until there are speaking sessions. Strings added in Phase 9 still fall back to English. Poem ruby shows as a list (D-307).

### Behaviour changes
None. The three code fixes (`456f816`, `9b82d83`, `b831183`) change no user-visible behaviour: same review candidates, same clip keys, same 1T highlight. One test was added (`4c7b675`).

### Tools and environment notes for the owner
- `xcode-select` still points at CommandLineTools. Run `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` once; until then any terminal build needs `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- `idb` / `idb-companion` were installed for the walk (above). Remove with `brew uninstall idb-companion && uv tool uninstall fb-idb` if unwanted.
- The iPhone 17 simulator has Tsumugi installed with onboarding completed and a `taberu` lookup in its history; `xcrun simctl erase 7EAA5683-B9F3-4A0B-8881-F90FBB155C88` resets it.
- SKIE analytics upload runs on every framework build (Rung 2 note).
