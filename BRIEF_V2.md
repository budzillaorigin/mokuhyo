# Tsumugi v2 — Update & Fix Brief for Claude Code (Opus)

> **Audience:** Claude Code (Opus), working in the existing repo (`Japanese Learning App/`, GitHub `budzillaorigin/tsumugi`).
> **Relationship to BRIEF.md:** this file **extends** BRIEF.md and **overrides** it where the two conflict. `CLAUDE.md` stays in force; §1 below adds rules to it. Read `docs/PROGRESS.md` and `docs/DECISIONS.md` first, then this file top to bottom.
> **Basis:** a code and content audit of the repo as of 2026-09-18 (Phases 0–8 "built", nothing yet run on hardware), plus a new list of reference resources the owner wants folded in (§6).
> **Owner:** Buddy. Solo developer; dev machine is Windows 11 ARM64; owns a Mac for Xcode work; runs Ollama on a home server; studies for JLPT and the U.S. military DLPT/OPI.

---

## 0. Owner quick start (human section)

Run each line in Terminal from the repo folder, one at a time.

```bash
cd "$HOME/Japanese Learning App"          # go to the repo (adjust if your shell uses a different home path)
git status                                  # confirm the tree is clean; if files are listed, commit them first:
git add -A && git commit -m "WIP before v2" # (only if git status showed changes)
git pull                                    # get anything CI or your Mac pushed
git checkout -b v2                          # work on a branch so main stays as the Phase 0–8 snapshot
cp ~/Downloads/TSUMUGI_V2_UPDATE_BRIEF.md ./BRIEF_V2.md   # skip if the file is already in the repo
git add BRIEF_V2.md && git commit -m "Add v2 update brief"
claude                                      # start Claude Code
```

First message to paste into Claude Code:

> Read BRIEF_V2.md in full. Append §1 of it to CLAUDE.md. Then execute Phase 9 (§8) only — stabilization and release blockers — and stop. Do not start Phase 10 until I have run the Phase 9 QA on my iPhone and told you to continue.

**Why the stop matters this time:** Phases 0–8 were built back to back with no device testing. The audit found the kind of defects that only show up on hardware (audio session, LAN permissions, main-thread stalls, cancel races). Phase 9 exists to get a build onto your phone and fix what breaks *before* piling on features.

---

## 1. New standing rules → append to `CLAUDE.md`

```markdown
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
```

---

## 2. What was built (facts) and how it holds up

### 2.1 Inventory

The repo contains all eight phases of BRIEF.md: a KMP shared core (dictionary, pure-Kotlin IPADIC lattice tokenizer with 99.8% MeCab parity, deinflector/conjugator, FSRS-6 port with on-device optimizer, unlock tree and 60-level kanji path, grammar SRS with ghost cards, Today planner, reader with importers for text/URL/RSS/Aozora/EPUB, Skritter-style stroke grading, KanjiVG template handwriting recognizer, sync engine with pure-Kotlin E2E crypto, `.apkg` import/export incl. NihongoShark format, WaniKani v2 sync, imiwa/Bunpro imports, AiGateway with GBNF-constrained llama.cpp + OpenAI-compatible endpoint, whisper.cpp, VOICEVOX, pronunciation analyzer with YIN pitch tracking, JLPT/DLPT/OPI exam engines), a Ktor + Postgres sync server with passkeys, SwiftUI and Compose apps covering every tab, en/ja UI localization, accessibility passes, share extensions, iOS widgets, and content packs: 218k dictionary entries, 2,599 kanji / 7,242 words on the path, 829 grammar points, 2,368 JLPT items, 100 DLPT passages, 30 role-play scenarios, 45 dialogues, 630 minimal pairs. ~360 shared tests pass in CI.

That is an unusually complete scaffold for one day of build. It is also **pre-alpha as a product**: nothing has run on a phone, several visible screens are placeholders, and the audit found correctness bugs in the progression model that would make the app untrustworthy as a WaniKani/Anki replacement if shipped.

### 2.2 Audit summary

The full findings are in §4 (fixes) and Appendix A (complete list). Headlines:

1. **Cannot be uploaded to App Store Connect today**: the iOS AppIcon asset set is empty.
2. **The owner's core "Ollama on my LAN" story fails on both platforms**: iOS lacks `NSLocalNetworkUsageDescription` (iOS 17/18 silently deny), Android lacks a cleartext-HTTP network security config.
3. **Progress can regress or diverge**: the path level is recomputed from live FSRS stability (a few lapses demote the learner and hide unlocked lessons), and undo physically deletes review rows that may already have synced.
4. **Untested hardware paths are likely broken**: audio session category never set for playback, LlamaBridge cancel race blocks all AI after one timeout, 1 GB model download hashed on the main actor, no HTTP timeouts on endpoint mode, LLM API key sent to the Whisper STT endpoint.
5. **Visible placeholders**: the Reviews tab is a copy of Today; the reader's sentence panel says "Translation arrives with the on-device AI (Phase 6)" on every sentence.
6. **Content is honest but thin**: hand-drafted dialogues and scenarios read naturally, but 45 dialogues ≈ 5 minutes of audio, all scenarios are exactly six turns, listening stops at N3, and generated JLPT vocabulary items have a multi-key risk.
7. **Many BRIEF.md features are still missing**: Today's immersion/shadowing/speaking/writing blocks, free talk, media-player STT subtitles and clip mining, podcast RSS, grammar production reviews, Notion, AnkiConnect, CSV/PDF export, leaderboard client, picture cards, kana course, ILR 3+/4, pre-rendered audio, human review of AI-drafted content.

---

## 3. Product direction for v2

v2 has three goals, in order:

1. **Make v1 real.** Ship a TestFlight build the owner uses daily for kanji/vocab/grammar reviews with his WaniKani and NihongoShark data. This means fixing §4 and closing the v1 gaps in §5.
2. **Make immersion the center of gravity.** The new reference list (§6) is dominated by immersion tooling — jpdb's media decks and coverage, Immersion Kit's sentence-with-audio bank, AxTongue's synced lyrics, Refold's roadmap, YOMUJP/Japan Reader/Routledge-style reading tasks, Swotter/Nihongo Alive drills. The app already has the reader and media player; v2 turns them into a mining pipeline that feeds the SRS.
3. **Serve the linguist.** Translation workbench, business/keigo and daily-life tracks, ILR 3+/4 content, pre-rendered listening, and OPI improvements make the app the DLPT/OPI trainer that doesn't exist elsewhere.

Naming, pricing, and hosted sync remain open decisions (§9).

---

## 4. Fixes (Phase 9) — prioritized, with acceptance tests

Items marked **verify** depend on files the audit could not read; confirm before fixing. File paths are relative to the repo root; line numbers are from the 2026-09-18 tree.

### P0 — release blockers

| # | Where | Problem | Fix | Acceptance |
|---|---|---|---|---|
| F-01 | `iosApp/Tsumugi/Resources/Assets.xcassets/AppIcon.appiconset/Contents.json` | Icon set declares one 1024×1024 slot with no image. Archive validation fails. | Generate a 1024×1024 PNG of the spool-of-thread mark (port the Android vector to SVG → PNG with a script in `tools/assets/`), plus dark/tinted variants for iOS 18. Reference it in Contents.json. | `xcodebuild archive` + `xcodebuild -exportArchive` succeed in CI (unsigned validation step, see F-06). |
| F-02 | `iosApp/Tsumugi.xcodeproj/project.pbxproj` (Info.plist keys) | No `NSLocalNetworkUsageDescription`; iOS 17/18 deny LAN connections with no prompt. `docs/RELEASE.md` §5 claims typing a URL works without it — wrong. | Add the key (+ `NSBonjourServices` if discovery is added) and an ATS `NSAllowsLocalNetworking` exception so `http://homeserver`/Tailscale names work, not just raw IPs. Fix RELEASE.md. | On a real iPhone, Settings → AI → own server `http://<lan-ip>:11434` → Test connection lists Ollama models after the local-network prompt. |
| F-03 | `androidApp/src/main/AndroidManifest.xml` | No cleartext config; Android 9+ blocks `http://` to the home server. | Add `res/xml/network_security_config.xml` permitting cleartext for private ranges and user-entered hosts (or `usesCleartextTraffic="true"` with a documented tradeoff). | Android endpoint test connection succeeds over plain HTTP. |
| F-04 | `shared/.../srs/UnlockTree.kt:38-50`, `PathService.kt`, D-017 | Current level is recomputed from live stability; lapses demote the learner and hide levels 5–12 of lessons. | Persist `passed_level` per user (new `path_progress` table, synced LWW-max). Level only ever increases (manual "reset to level N" is an explicit, confirmed action). `unlocked()` iterates `1..max(passed+1, currentDisplayed)`. | New `PathProgressTest`: pass level 4, lapse 3 kanji in level 4 → level stays ≥ 5 and level-5 lessons remain available. Sync test: two devices, one advances, merge keeps the higher level. |
| F-05 | `srs/SrsRepository.kt:190-197`, `sync/SyncEngine.kt:119-123`, `srs.sq` | Undo physically deletes a review row; if it already pushed, other devices keep it → permanent divergence. | Add `deleted_at` tombstone column to `review`; `undo()` sets it; replay ignores tombstoned rows; sync propagates the tombstone; keep the "delete before push" fast path only when `synced = 0`. | `SyncMergeTest.undoAfterPushConverges`. |
| F-06 | `.github/workflows/ci.yml` | CI compiles for `generic/platform=iOS` unsigned; never archives/validates. Entitlements, App Groups, embedded frameworks, privacy manifests are untested until the owner's first archive. | Add an `archive` job: `xcodebuild archive` with `CODE_SIGNING_ALLOWED=NO`, then a script that validates the `.xcarchive` structure (Info.plist keys present, icon present, three targets' entitlements, no missing usage strings). Document the signed archive steps for the owner's Mac in RELEASE.md. | CI job green; RELEASE.md updated. |

### P1 — serious (fix in Phase 9)

| # | Where | Problem | Fix |
|---|---|---|---|
| F-07 | `iosApp/.../App/RootView.swift:134` | Reviews tab renders `TodayView()`. | Build the Reviews tab per BRIEF §6: queue by kind with counts, "Start", session, summary, upcoming-forecast strip, leech list. |
| F-08 | `iosApp/.../Reader/ReaderViews.swift:233` | Placeholder text "Translation arrives with the on-device AI (Phase 6)". | Wire `translate_sentence` through AiGateway with the labeled result; show "Enable an AI engine to translate" when no engine. |
| F-09 | `study/ReviewSession.kt:171-190`, `ReviewView.swift:17-28` | Double-submit race: two review rows for one card, next card skipped. | Serialize session mutations (single `Mutex` or actor in the session; view model uses one long-lived task with a channel). Disable the submit control while `submit()` is in flight. Test: two concurrent `submit()` calls record exactly one review and advance exactly one card. |
| F-10 | `iosApp/.../Platform/LlamaBridge.swift:84-118,148` | `setCancelled(false)` runs before the queued job, so a cancel from a timed-out generation is lost; after one timeout every AI call waits behind a runaway generation. Unload mid-generation returns truncated text → JSON fail → retry reloads the model right after a memory warning. | Generation id per call; cancel flag checked against id inside the loop; reset the flag inside the queued block. On unload-during-generate, fail the call with a distinct `Cancelled` error that AiGateway does **not** retry. Same review for `llama_jni.cpp`. |
| F-11 | `ai/SpeechEngines.kt:91,134`, `ai/ModelManager.kt:65`, `speaking/AiService.kt:190,208`, `VoicePlayer.swift:414` | No HTTP timeouts anywhere in endpoint mode; off-LAN the app hangs 60–150 s per action. | `HttpTimeout` on every client (rule 13); VOICEVOX probe 3 s, synth 15 s; a cached "endpoint unreachable" state for 60 s after a failure so the fallback engages immediately. |
| F-12 | `speaking/AiService.kt:208` | LLM API key sent to the Whisper STT endpoint. | Separate `sttEndpointKey` (and `ttsEndpointKey`) secrets; UI fields per endpoint. |
| F-13 | `ai/ModelManager.kt:102-173`, `AiSettingsView.swift:343-373` | 1 GB download written and SHA-256-hashed in the collector's context (main actor). No free-space check. Foreground-only. | Move I/O and hashing to `Dispatchers.IO`; incremental hash while writing (no second pass); check free space ≥ 1.5× model size before starting; iOS: background `URLSession` download task with resume data; Android: `DownloadManager` or WorkManager. |
| F-14 | `iosApp/.../Platform/AudioCapture.swift:150`, `VoicePlayer`, `Speech`, `MediaPlayerView` | `AVAudioSession` only configured for recording; playback silent under the ring/silent switch; no interruption/route-change handling; session never deactivated. | A single `AudioSessionController` (playback `.playback`, record `.playAndRecord` with `.defaultToSpeaker`, deactivate with `.notifyOthersOnDeactivation`), interruption and route-change observers, and `UIBackgroundModes: audio` for the media player. |
| F-15 | iOS `PlatformServices` (**verify**) | Packs (~155 MB) and models (1–5 GB) may be written to a backed-up directory → Guideline 2.23 rejection and iCloud quota burn. | Store under Application Support with `isExcludedFromBackup = true` (models under Caches is acceptable if the app tolerates eviction — prefer Application Support + exclusion). |
| F-16 | `content/PackInstaller.kt:67-78`, D-013 | Copies 127 MB on first launch under the global `AppGraph` mutex, leaks `.part` files on failure, never verifies SHA-256, no free-space check. | Open bundled packs **in place** read-only (`?immutable=1` / `SQLITE_OPEN_READONLY` with `immutable`) and drop the copy entirely; keep `PackInstaller` only for downloaded packs, with atomic rename, hash verification, and cleanup of stale `.part` files. Remove the copy from the size audit. |
| F-17 | `srs/SrsRepository.kt:156-161` (**verify** `Stage.of`) | `stages()` calls `.min()` on a possibly empty list → crash after the first lesson if `Stage.of(null, …)` returns null. | Use `minOrNull()` with a defined "Introduced" stage for cards with no stability. Test: item with two LEARNING cards has a stage. |
| F-18 | `srs/Fsrs.kt:93,99` | `shortTermStability` clamps HARD; mean-reversion uses unclamped `initialDifficulty(EASY)`. | Match py-fsrs exactly; extend `FsrsTest` with HARD/same-day vectors from the reference. |
| F-19 | `study/TodayPlanner.kt:76-77,151` | Lesson counts and weekly challenge count INTRODUCED rows per card (2 per item). | Count distinct items. |
| F-20 | `ReviewSession.kt:217-262`, `GrammarService.kt:158-161` | Undo doesn't retire the spawned ghost; grammar cards with zero exercises are filtered from sessions but count as due forever. | Undo removes the ghost spawned by that review; cards with no exercise are suspended at pack-load with a visible "no examples yet" state. |
| F-21 | `speaking/PronunciationService.kt:30-34,65` | 助動詞 treated as particles (their kana dropped from the target); pitch looked up on the inflected surface (every conjugated verb → unknown accent). | Keep auxiliaries in the target; look up pitch by lemma and apply verb/adjective accent rules for conjugated forms (table in `jp/Pitch.kt`). Test with 食べます, 食べました, 高かった. |
| F-22 | `speech/PronunciationAnalyzer.kt:227-231` | Full `(n+1)×(m+1)` DTW matrix; 60 s clips → hundreds of MB. | Banded DTW with a rolling two-row buffer; cap input length and downsample frames beyond 20 s. |
| F-23 | `ai/LocalLlamaModel.kt:64`, `AiService.kt:96-111` | Switching models doesn't reload; conversation history never trimmed → "Prompt too long" → scripted line spliced into an AI chat. | `ensureLoaded(path)` compares the loaded path; `save()` unloads; history window keeps the system prompt + last N turns that fit `n_ctx − maxTokens − margin`; when the model fails mid-conversation, show a banner instead of splicing scripted turns. |
| F-24 | `exam/ExamSession.kt:64-131`, `ExamRunnerView.swift:147,337` | Attempts are in-memory only; clock is a 1 Hz SwiftUI timer; backgrounding across two section deadlines closes only one section. | Persist the in-progress attempt (answers + section deadlines as absolute timestamps) every answer; compute state from wall-clock on resume; "Resume attempt" on the hub. |
| F-25 | `iosApp/.../Writing/WritingViews.swift:39-46` | `DragGesture(minimumDistance: 0)` inside `ScrollView` — vertical strokes hijacked by scroll. | Use `.gesture(..., including: .gesture)` with `simultaneousGesture` disabled, or wrap the canvas in a `UIViewRepresentable` that claims touches; disable scroll while a stroke is active. |
| F-26 | `reader/ReaderService.kt:28`, `DictionaryRepository.kt:158-195` | Reader uses the dictionary longest-match tokenizer (12 deinflect + SQL round-trips per char) synchronously on import; the lattice tokenizer is unused for reading → two tokenizers, different token ids. | Reader analysis uses `LatticeTokenizer` (with dictionary lookup per token for glosses); run on IO with progress; page-level lazy analysis. |
| F-27 | `study/StatsService.kt:108`, `srs/PathService.kt:97-142` | Full review-log and all-cards scans per screen open. | Materialize `daily_stats` (date, reviews, correct, minutes) updated on write; `stages()` per level via an indexed query; cache per session. |
| F-28 | `UnlockTree.kt:53-68` | Lesson order within a level is `HashSet` order. | Order by pack position. |
| F-29 | `androidApp/src/main/cpp/llama_jni.cpp:183` | Fixed sampler seed → identical replies every session. | Random seed per generation (parity with iOS). |
| F-30 | `api/SwiftSupport.kt:129` | Single `voicevox.wav` overwritten while playing. | Unique temp file per synthesis; delete after playback. |
| F-31 | `speaking/AiService.kt:97-106`, `Settings.kt`, `PathService.kt:162-165` | AI engine/model/endpoint choice and manual unlocks are synced LWW rows. | Rule 16: per-device settings table for engine/endpoints/model; manual unlocks become per-item rows (set-union), not one JSON blob. |
| F-32 | `api/AppGraph.kt:174-210` | One mutex for all pack opens; FSRS weights loaded once per process; weight change via sync doesn't recompute. | Per-pack lazy init; on `FSRS_WEIGHTS` change (local or synced) → `reloadScheduler()` + background `recomputeAll()` with progress. |
| F-33 | `iosApp/.../Study/ReviewView.swift:12,27`, `RoleplayViews.swift:360-366` | Errors swallowed → infinite spinners. | Every async screen has an error state with retry; `SwiftSupport` returns typed failures instead of `nil`. |
| F-34 | `iosApp/.../App/TsumugiApp.swift:14` | Notification permission requested at first launch. | Ask after the first completed review session, from a screen that explains why. |

### P2 — moderate (Phase 9 if cheap, else Phase 10)

F-35 `AnswerChecker.normalizeMeaning` strips non-ASCII (café, Japanese synonyms unmatchable) → NFKC-fold + allow letters from any script. F-36 `ReaderViews.swift:176` ruby over the whole token instead of per-kanji segments → use the analyzer's segments. F-37 `SyncEngine` E2E leaves `table`, `key`, `updatedAt` in clear → encrypt the key with a deterministic keyed hash (HMAC) so the server sees opaque ids. F-38 `ci.yml` re-downloads "latest" JMdict weekly → pin source release tags in `tools/packs/sources.lock` and update deliberately. F-39 `ReaderAnalyzer.kt:163` applies raw grammar regexes (は/が match inside おはよう) → apply the same `inside_larger_word` filter the builder uses, on token boundaries. F-40 `LicensesView` silently tolerates a missing `LICENSES.md` → a Swift test asserts the bundle contains it. F-41 `WhisperRecognizer.transcribe` has no `invokeOnCancellation`. F-42 Add `CFBundleDocumentTypes` for EPUB/SRT/VTT/APKG/JSON banks. F-43 D-025 export compliance: note the annual BIS self-classification requirement in RELEASE.md. F-44 `tools/models/README.md` pins "whisper.cpp b5130" — verify `fetch_ios_frameworks.sh` resolves real release assets. F-45 Kanjium and stephenmk mapping licenses are asserted in `LICENSES.md` without citation → cite the exact license file URLs or replace the data.

### Phase 9 also includes

- **On-device QA on the owner's iPhone** (`docs/QA.md`): after the fixes, the owner runs First run, Dictionary, SRS, Reading & writing, and AI sections. Claude Code triages every failure into F-items before Phase 10.
- **TestFlight build** from the owner's Mac using the RELEASE.md steps (owner action).

---

## 5. Close the v1 gaps (Phase 10)

Everything here was specified in BRIEF.md and is missing or stubbed. Specs are in BRIEF.md; only deltas and decisions are given.

| # | Gap | Delta / decision |
|---|---|---|
| G-01 | Today blocks: immersion, shadowing, speaking moment, writing | Implement the `extraBlocks` the planner already anticipates. Immersion block picks one reader passage or dialogue at the learner's level (§6.4 difficulty score); shadowing block = 3–5 sentences from today's grammar with record/compare; speaking moment = one scenario; writing = 3 kanji. Review cap honors the daily budget (`startReviews(limit)` from the plan). Pomodoro launchable from any block. |
| G-02 | Free talk + rolling level estimate + recurring-error log | `Conversation` rows persisted (BRIEF §5.1); weekly "patterns" card on Me. |
| G-03 | Recordings on disk + side-by-side playback + self-recorded audio cards | Store recordings under Application Support (excluded from backup); opt-in recordings sync via the existing blob store. |
| G-04 | Media player: STT subtitle generation, "save clip to SRS", podcast RSS, speed 0.7–1.2×, hide-subtitle quiz | Subtitle generation via on-device Whisper with progress and cache; clip = audio segment file + subtitle line → LISTENING card. Podcasts: RSS with enclosure download and episode transcripts. Use AVPlayer (iOS) and Media3 (Android; add to LICENSES). |
| G-05 | Grammar: fill-in-with-hint, reading-recognition, LLM-graded production; textbook-order paths; example audio | Production grading = `grade_production` prompt with rubric JSON + rule checks; always shows the model answer. Textbook chapter mapping files (Genki/Tobira/Quartet numbers only). Example audio from the pre-rendered pack (§5.6). |
| G-06 | Minimal pairs on FSRS | `MINIMAL_PAIR` cards scheduled like everything else. |
| G-07 | Reader: comprehension questions, pitch overlay, learner-aware furigana, Aozora ruby retention, graded passage packs | `generate_reading_questions` prompt (labeled); furigana "only above my level" uses learner JLPT/known-words; keep Aozora ruby as authoritative readings. Graded passages: see §6.4. |
| G-08 | Exams: trend lines, ILR 3+/4 packs, resumable attempts (F-24), pre-rendered listening (§5.6) | ILR 3+/4: 30 passages each, editorial/academic/abstract, drafted via the owner's endpoint and reviewed. |
| G-09 | Integrations: Notion push, AnkiConnect (LAN), Bunpro API where available, Safari Action extension for selected-text lookup | Notion mapping in `docs/INTEGRATIONS.md`; Action extension shares the App Group inbox pattern. |
| G-10 | Export: CSV of reviews, PDF study report; import: JSON restore | PDF via platform renderers (UIGraphicsPDFRenderer / android.graphics.pdf); one shared report model. |
| G-11 | Motivation: leaderboard client (opt-in), streak freeze days (replace vacation-mode inflation), weekly challenges tied to real blocks | Freeze = a day that neither breaks nor extends the streak. |
| G-12 | Fluent Forever personal picture/audio cards | `CUSTOM` items with image + own recording; image picker; cards render the picture side. |
| G-13 | Kana course (absolute beginners) | Hiragana/katakana with our own mnemonics (Tofugu-style method, our own text and images), stroke practice via the existing canvas, and a placement check that skips it. Foundations phase in Today starts here when the onboarding kanji check scores zero. |
| G-14 | Localization of shared-core labels | Move stage/kind/block/phase labels to a shared string table with en/ja; both apps map through it. |
| G-15 | Android: Glance widget, in-app language picker, Media3 player | Parity table rows. |
| G-16 | Human review of AI-drafted content | Owner action, but Claude Code builds a **review UI in the app** (Me → Content review, hidden behind a developer toggle) that wraps `review.py`'s logic so reviewing can happen on the phone and export a JSON of verdicts the build ingests. |

### 5.6 Pre-rendered audio pack (revises D-030)

D-030 chose play-time system TTS because the build machine had no Japanese voices. VOICEVOX runs on the owner's Windows PC and produces natural Japanese with **controllable accent phrases**, which system TTS cannot do. Build `content/packs/audio-<set>.zip` (Opus, 24 kHz, mono) at pack-build time with `tools/packs/render_audio.py` against a local VOICEVOX engine: exam listening scripts (two speakers), dialogue drills, grammar example sentences, minimal pairs, and the pitch-accent test items (§6.7, where the accent is set explicitly per item). Credit the VOICEVOX characters used per their terms in `LICENSES.md` and the Licenses screen. System TTS remains the fallback for user-created content and for any item whose audio is missing. Audio packs are on-demand downloads (keep the base IPA under 200 MB).

---

## 6. New features from the new reference list

Each feature names the resource(s) that inspired it, what we take, and what we deliberately don't (content and code from these resources are never copied; several are books or paid courses and are inspiration only). Appendix B is the full attribution map.

### 6.1 Media decks and coverage (jpdb.io)

The single highest-value addition. jpdb's insight: vocabulary should be learned in the order the learner's *own* media needs it, and the learner should always know how much of a given text or show they can already read.

- **Media deck builder:** any reader document, EPUB, subtitle file, media transcript, or pasted text → tokenized with the lattice tokenizer → a per-media vocabulary list ordered by frequency-within-media × global frequency, with kanji list and grammar points detected. One tap creates a deck; lessons draw from it instead of (or interleaved with) the kanji path. Per-media stats: unique words, coverage at 80/90/95/98% (how many words needed), estimated JLPT/ILR.
- **Coverage overlay:** for every reader document and media item, "You know 74% of the words · 92% of the kanji · 11 new words to reach 95%" using the learner's SRS state (Guru+ = known; Apprentice = learning). Sort the library by coverage.
- **Prebuilt frequency decks:** "Core 2k/6k/10k" from Tatoeba + Wikipedia frequency (own data), plus domain decks (§6.5). Every deck shows coverage of the learner's own media.
- **Known-words import:** WaniKani/Anki/Bunpro imports mark words known; a bulk "I know these" review of frequency bands during onboarding (jpdb's "mark known" flow) so an intermediate learner isn't drilled on 猫.
- Data: `media_deck`, `media_deck_word` (synced), coverage computed locally.

### 6.2 Sentence bank from my media (Immersion Kit, Yomikiri)

Immersion Kit indexes anime/drama lines with audio and screenshots. We build the same index **from the learner's own files** so nothing is redistributed.

- When a media item has subtitles (loaded or STT-generated), index every cue: text, tokens, timestamps, media id. "Sentence search" in the dictionary returns lines from the learner's library first, then Tatoeba.
- Tapping a result plays the clip (audio segment extracted on demand, cached) and shows a frame thumbnail (video only, `AVAssetImageGenerator` / `MediaMetadataRetriever`).
- "Mine this line" makes a SENTENCE or VOCAB card with the clip audio and thumbnail attached (the Yomikiri/Immersion Kit card format: word, sentence with the word highlighted, audio, image).
- Optional online source: the Immersion Kit public API is a free service without keys; expose it as an **optional, user-enabled** example-sentence source (like RSS), results displayed live and never cached into packs. Off by default; document its terms.

### 6.3 Lyrics and karaoke reading (AxTongue)

- Import an audio file plus `.lrc` (synced lyrics) or plain lyrics; without timestamps, align with Whisper word timestamps.
- Karaoke view: line-by-line highlight, tap word → dictionary, per-line English (learner-entered or AI-translated, labeled), grammar notes on the line, **cloze mode** hides selected words until they are sung (AxTongue's mechanic) and asks the learner to type them.
- No YouTube or streaming downloads; the empty state says so. Music the learner owns (Files/iCloud Drive) only.

### 6.4 Reading upgrades (YOMUJP, Japan Reader, Yomimono, Routledge genre-based reader, Tofugu)

- **Graded readers with audio (YOMUJP model):** our own graded stories at six levels (N6 "level 0" → N1), 300–1,500 characters, each with pre-rendered audio, vocabulary list, comprehension questions, and a "read along" mode. Content pipeline: `tools/packs/readers/` drafts stories (via the owner's endpoint) against level vocabulary lists, validates coverage (≥ 95% of tokens within level), renders audio (§5.6), and ships after review. Target 20 per level at launch, script re-runnable to add more.
- **Difficulty score for any text** (used by Today, coverage, media decks): combine known-word ratio, JLPT band coverage, sentence length, kanji density, and abstract-vocabulary ratio (reuse `ilr_bands.json`) into one 0–100 score with the JLPT/ILR label. Document the formula in `docs/CONTENT_PACKS.md`.
- **Annotation tools (Japan Reader):** box a phrase, highlight, attach a note, mark grammar spans; annotations persist per document and sync. Screenshot import → OCR → library (the existing Vision/ML Kit path) with the screenshot kept as the page image.
- **Genre-based tasks (Routledge):** each reader document gets a genre tag (news, recipe, ad, manga, essay, email, notice, editorial, academic) and a task set: pre-reading prediction question, skim/scan task with a timer, close-reading questions, and a post-reading output task (summarize in Japanese → LLM-graded, labeled). Templates per genre in `tools/packs/readers/tasks.json`.
- **Auto vocabulary list per document** (Japan Reader): every looked-up word is added to the document's list; "Drill" turns the list into context flashcards (word shown inside its sentence).
- **Guides library (Tofugu):** a curated, link-only list of free explanations (Tofugu, Tae Kim, Imabi, Wasabi, Chika Sensei's free grammar lists, Nihongo no Mori videos) attached to grammar points and topics. Links only; nothing fetched or stored.

### 6.5 Interest and domain tracks (Kanji Adventures, Business Japanese 30h, Japanese In-Law, 語彙力ドリル1100, 生活者ワークブック, にほんごで文化体験, Nihongo Now!)

A **track** = a themed word list + kanji subset + scenarios + dialogues + drills, selectable in onboarding and switchable any time. Tracks feed Today's lesson block alongside (not instead of) the main path.

| Track | Inspired by | Content |
|---|---|---|
| Gaming & VTuber | Kanji Adventures (Nihongo Picnic) | ~140 kanji / 600 words common in games, streams, chat; component breakdown + memory hints (our own); stream-chat register; pitch from our data |
| Business & keigo | にほんごで働く！ビジネス日本語30時間 | 30 workplace situations (email, phone, meetings, reports, 敬語 transformations), keigo drill type (plain → 尊敬語/謙譲語/丁寧語), business email templates with fill-ins |
| Family & household | Japanese In-Law | Household, relatives, childcare, cooking, chores, in-law etiquette phrases; dialogue drills |
| Daily-life admin | 生活者としての外国人向けワークブック; にほんごで文化体験 | City hall, health insurance, hospital, bank, garbage rules, disaster notices, school letters; "can-do" statements per situation; cultural experience tasks (festival, onsen, tea) |
| Native schoolchild vocabulary | 小学生の語彙力アップ 実践練習ドリル1100 | 1,100-style word bank of words native children learn (idioms, 四字熟語, onomatopoeia, synonyms/antonyms, 慣用句) with the drill formats of that genre: fill-the-blank sentences, pick the synonym, usage yes/no |
| Military & liaison | owner's need; ILR 2–3 | Briefings, ranks, logistics, exercises, treaties, disaster relief, base life; counterpart-briefing scenarios; ILR-leveled reading (JMSDF/JASDF press releases are official texts — link only, or user-imported) |
| Performing culture | 日本語NOW! Nihongo Now! | "Performances": short scripted exchanges in a specific cultural setting with staging notes (who bows, when to use さん, register shifts); memorize-and-perform mode where prompts fade turn by turn; evaluated by recording + self/AI check |

Content pipeline: `tools/packs/tracks/<track>.json` (words by JMdict id, kanji, scenarios, dialogues, drills), drafted through the owner's endpoint with the same review gate. **Bunka-cho materials:** check the license of the つながるひろがるにほんごでのくらし site and the ワークブック before reusing any text; if CC BY, ingest with attribution, else inspiration only.

### 6.6 Structured JLPT courses (Chika Sensei's Academy, 日本語の森 この一冊で合格する, Yomimono)

- **Course view per level:** modules that sequence kanji → vocab → grammar → quiz → mock section (Yomimono's structure), with a **mastery checkbox per grammar point** independent of SRS (Chika Sensei's checklist), progress bar per level, and a "one book to pass" mode that lists exactly what remains for the level (日本語の森's promise).
- **Monolingual mode:** at N2/N1 (and optionally earlier), explanations and glosses switch to Japanese-only (JMdict has no ja glosses; use our own ja explanations for grammar and the LLM's ja paraphrase for words, labeled). Chika Sensei's N1 list is Japanese-only by design.
- **Video links per grammar point:** user-attachable links (YouTube etc.) open externally; ship none.

### 6.7 Pitch accent perception test (コツ / kotu.io)

- Test mode: hear a word or short phrase, choose the pattern (平板 / 頭高 / 中高 / 尾高, or the mora of the downstep); adaptive difficulty; stats per pattern, per mora length, per confusable pair; "perception → production" link into the pronunciation panel.
- Audio comes from the VOICEVOX-rendered pack (§5.6) where the accent phrase is set explicitly per item, so every item's accent is guaranteed. Without the audio pack the test is hidden (system TTS can't guarantee accent).
- Minimal pairs (existing) become one drill type inside this module.

### 6.8 Onomatopoeia module (Onomato Project)

- Build the list from JMdict `on-mim` tagged entries (~1,500), grouped by theme (weather, feelings, pain, movement, texture, sounds) and by type (擬音語/擬態語/擬情語), each with Tatoeba examples, our own one-line "feel" description, and a quiz (pick the onomatopoeia for a described scene; pick the scene for the word). Illustrations: simple original SVG glyphs per theme, not per word (no copying Onomato Project's art).

### 6.9 Mini-games (Ulangi, PomoSpeak, Laddrr)

- **Reflex:** timed true/false matching (word ↔ meaning) with streak scoring. **Atom:** spelling/kana assembly under time. Both are `Activity` types in the Pomodoro queue and usable standalone; scores feed the weekly challenge. Ulangi is open source — reimplement, don't copy (check its license before even reading its code).

### 6.10 Speaking drills and shadowing sets (Japanese Swotter, Nihongo Alive)

- **Drill sets:** prompt → pause → model answer → repeat (Swotter format) built from grammar examples and track dialogues; configurable pause length; hands-free audio mode with screen-off playback (needs `UIBackgroundModes: audio`).
- **Real-conversation listening:** Nihongo Alive's value is unscripted-sounding speech (fillers, backchannels, restarts). Add a "natural" dialogue style to `author_dialogues.py` with あの, えっと, なんか, backchannel うん/へえ, overlaps; render with two VOICEVOX voices; transcript shows fillers greyed. 20 beginner + 20 intermediate at launch.
- Podcast RSS import (G-04) makes Swotter-style podcasts usable directly with the transcript from Whisper.

### 6.11 Immersion roadmap and log (Refold)

- **Roadmap:** stages 1–4 (foundations → comprehension → output → refinement) with Refold-style milestones expressed in the app's own measures (known words, hours of immersion, comprehension score on graded readers, OPI estimate). No Refold text.
- **Immersion log:** active vs passive minutes per day, by source (reader, media, podcast, dialogue), with a daily immersion target in Today and the heat-map. Media player and reader log automatically; a manual entry for offline immersion.
- **1T sentence mining:** the reader and media player highlight sentences with exactly one unknown word (given the learner's SRS state) and offer them first for mining.

### 6.12 Translation workbench (Japanese–English Translation: An Advanced Guide)

For the linguist. J→E and E→J passages by genre (news, technical, legal, literary, dialogue, military), timed **sight translation** mode (speak the translation, STT captures it), written mode, and LLM grading against a reference on a rubric (accuracy, completeness, register, naturalness) with a diff view — labeled, and never presented as an official score. Passages come from our own graded readers, Tatoeba, and Aozora (public domain), plus user-imported text. Track results as a skill line on Me.

### 6.13 Expression thesaurus and writing studio (日本語表現インフォ)

- **Expression thesaurus:** descriptive expressions grouped by scene and emotion (怒り, 安堵, 雨, 夜, 表情…) with example sentences — our own drafted clusters, JMdict senses, and Tatoeba examples; plus a **collocation view** computed from the Tatoeba + Wikipedia corpus (PMI over tokenized text, built in `tools/packs/build_collocations.py`). No hyogen.info text.
- **Writing studio:** compose Japanese text; on demand get corrections (existing prompt), register check (casual/polite/formal), suggested expressions from the thesaurus for flagged plain phrases, and readability. Saved drafts sync. Output tasks from §6.4 land here.

### 6.14 Poetry and literature corner (まほろばことば, USJETAA reading group)

- **Poetry:** 近代詩 whose authors are public domain (Aozora Bunko: 中原中也, 萩原朔太郎, 宮沢賢治, 高村光太郎 …), themed (sky, sea, moon, seasons), each with vocabulary, a plain-Japanese paraphrase and an English gloss (LLM-drafted, labeled, reviewable). No text from mahoblog.
- **Reading circle mode:** pick a short Aozora text; read sentence by sentence aloud (record), then explain in English (record or type); the app keeps the recordings and shows the dictionary/grammar for each sentence — USJETAA's format, solo. When the sync server has accounts, an optional **shared circle** lets a group pick a monthly text and share notes/questions per sentence (Phase 14).

### 6.15 Kanji explorer and etymology (Japanese Graph, Outlier Kanji Masterclass, Lorenzi's Jisho)

- **Graph view:** a force-directed node view (kanji ↔ components ↔ compounds), colored by JLPT or frequency, tap to re-center, bookmark to SRS. Cap visible nodes (Japanese Graph's clutter problem); "focus" mode shows one hop.
- **Functional components (Outlier's idea, our own data):** for each kanji mark components as semantic, phonetic, or form-only, and show **sound series** (声符): kanji sharing a phonetic component with matching or related on'yomi, so readings are learned in families (青 → 清 晴 精 請 情 静). Derive heuristically from KanjiVG component trees + KANJIDIC2 on'yomi (same component and same/related on'yomi → phonetic), label "derived" until reviewed; expose the derivation script `tools/packs/build_phonetics.py`. No Outlier text or component analyses.
- **Dictionary polish (Lorenzi's Jisho):** inflection breakdown chip under conjugated search hits ("食べさせられなかった = 食べる + causative + passive + negative + past"), instant-as-you-type results, component-combination search shortcuts, frequency rank and "common" chips in results.

### 6.16 Refinements to existing modules suggested by the list

- **Grammar detection in the reader** shows constructions (now with the false-positive filter from F-39) with a one-line explanation and "practice this point" (Chika Sensei / Nihongo no Mori style).
- **OPI simulator:** add DLI-style topic domains (family, work, current events, hypotheticals, abstract) and a "level check → probe" visualizer after the interview showing where breakdown occurred.
- **DLPT:** add a text-type filter and the ILR 3+/4 packs (G-08); passages from the military/liaison track at ILR 2–3.

---

## 7. Data, content, and licensing for v2

| Need | Source / approach | License |
|---|---|---|
| Onomatopoeia list | JMdict `on-mim` tag | CC BY-SA 4.0 (EDRDG) |
| Frequency lists (Core decks, collocations) | Tatoeba corpus + Japanese Wikipedia dump processed by `tools/packs/` | CC BY 2.0 FR / CC BY-SA |
| Public-domain poetry and reading-circle texts | Aozora Bunko (author death > 70 years; check each) | Public domain |
| Phonetic components / sound series | Derived from KanjiVG + KANJIDIC2 | CC BY-SA (derived) |
| Graded readers, tracks, translation passages, expression clusters, poetry commentary, natural dialogues | Drafted via the owner's OpenAI-compatible endpoint by scripts in `tools/packs/`, validated, reviewed, badge until `verified` | CC BY-SA 4.0 (Tsumugi contributors) |
| Pre-rendered audio | VOICEVOX engine + characters on the owner's PC | VOICEVOX terms; per-character credit required — list every character used |
| Bunka-cho daily-life materials | Verify license; ingest only if CC BY | TBD |
| Immersion Kit (optional online source) | Public API, user-enabled | Their terms; nothing cached into packs |
| Media3 (Android player) | AndroidX | Apache-2.0 |

Reference resources that are books, paid courses, or copyrighted sites (Business Japanese 30h, Japanese In-Law, 語彙力ドリル, Routledge reader, Wakabayashi's translation guide, Kanji Adventures, Chika Sensei's courses, 日本語の森, Outlier, hyogen.info, mahoblog, YOMUJP, Japan Reader, Tofugu, Nihongo Alive, Nihongo Now!) contribute **structure and pedagogy only**. `docs/LICENSES.md` gets an "Inspiration, no content used" section listing them so the boundary is explicit.

---

## 8. Phased plan v2

Every phase ends with `docs/PROGRESS.md` updated, `docs/QA.md` extended, tests green in CI, a push, and a **stop for owner device QA** (rule 18).

**Phase 9 — Stabilize and ship to TestFlight**
§4 F-01…F-34 (P0/P1), F-35…F-45 where cheap; rule 11–17 enforcement; QA on the owner's iPhone; TestFlight. Acceptance: owner completes a real review session with imported WaniKani + NihongoShark data on his phone; own-server AI works over LAN; nothing in `docs/QA.md` "First run / Dictionary / SRS" fails.

**Phase 10 — Close v1 gaps**
§5 G-01…G-16 and §5.6 audio pack. Acceptance: Today runs all six blocks; media player mines a clip into a review; a JLPT N3 listening mock plays pre-rendered audio; PDF report exports.

**Phase 11 — Immersion pipeline**
§6.1 media decks & coverage, §6.2 sentence bank, §6.3 lyrics, §6.11 roadmap & log, §6.4 difficulty score + annotations + auto vocab lists. Acceptance: import an EPUB and a video+SRT → coverage shown, deck created, 1T sentences highlighted, a clip card made, immersion minutes logged.

**Phase 12 — Content: courses, tracks, readers, audio**
§6.4 graded readers (120), §6.5 seven tracks, §6.6 course view + monolingual mode, §6.10 drill sets + 40 natural dialogues, §6.8 onomatopoeia, ILR 3+/4, N4–N1 listening dialogues (40 more), 60 more scenarios with variable turn counts. All through the review gate; the in-app review UI (G-16) is how the owner clears the badge.

**Phase 13 — Linguist and advanced modules**
§6.12 translation workbench, §6.13 thesaurus + writing studio + collocations, §6.7 pitch-accent test, §6.15 kanji graph + phonetic series + dictionary polish, §6.14 poetry corner + solo reading circle, §6.9 mini-games, §6.16 refinements.

**Phase 14 — Hosted sync and social (needs owner decisions 2 and 7)**
Hosted instance, email verification required, leaderboard, shared reading circles, recordings sync by default off, Android Play-readiness pass.

Estimated proportions: Phase 9 is small in code but gated on hardware; 10–11 are the bulk of engineering; 12 is mostly content generation and review time; 13 is feature work with modest data prep.

---

## 9. Open decisions (add to `docs/DECISIONS.md`)

1. Product name and bundle ID (still `Tsumugi` / `app.tsumugi.*`).
2. Pricing and hosted-sync fair-use cap (blocks Phase 14).
3. Default on-device model after running `eval_ja.py` on a real iPhone (Qwen2.5-1.5B assumed). Evaluate Qwen3 small models when their licenses are confirmed permissive.
4. WaniKani review posting default (assume off).
5. VOICEVOX characters to use for the audio pack (credit obligations differ per character).
6. Whether the Immersion Kit online source ships enabled-off (assumed) or is omitted.
7. Which tracks ship in the base app vs as downloadable packs (size).
8. Leaderboard and shared reading circles: v2 or later.

---

## Appendix A — Full audit findings not already in §4

- Content: all 30 scenarios are exactly six partner turns; scripted fallback only matches near the sample line; dialogue comprehension questions are keyword-spotting with distractors that never occur in the audio (`n5-morning`, `n5-phone-number`, `n4-recycling`); `hotel` scenario's closing line お世話になります is wrong for a departing guest; `n3-environment` is classroom Q&A, not a dialogue; N2/N1 dialogues absent.
- Generated JLPT items: "doesn't fit" = "not attested in Tatoeba" → multi-key 文脈規定 items; `vowel_shifts` fallback produces non-word distractors for 漢字読み; `passes(lv)` admits sentences one level harder; explanations are formulaic. Add an LLM-assisted multi-key check and a native-review sample of 100 items per level.
- DLPT bank: plausibly leveled (31 → 590 characters from 0+ to 3), English stems, balanced keys, no longest-answer bias; staged bank showed 60 passages/184 items (PROGRESS says 100/306 — confirm both files ship).
- Reader: ruby over whole tokens (F-36); grammar regex false positives (F-39); Aozora ruby dropped; `importUrl` analyzes 20k chars synchronously (F-26).
- Sync: E2E leaves keys in clear (F-37); FSRS fuzz seeded by review count shifts due dates after every pull (acceptable but document); pack-version tracking not surfaced.
- iOS: notification prompt at launch (F-34); Reviews tab duplicate (F-07); errors swallowed (F-33); `ReaderViews.swift:317` hard-codes the Aozora catalogue URL (move to config); no document types (F-42).
- CI: no archive (F-06); sources not pinned (F-38); `LICENSES.md` bundling unguarded (F-40).
- Docs: RELEASE.md wrong about local network (F-02); D-030 to be revised (§5.6); LICENSES.md citations (F-45); README pins (F-44).

## Appendix B — Resource → feature attribution

| Resource | Type | Feature(s) |
|---|---|---|
| 日本語表現インフォ (hyogen.info) | website | §6.13 expression thesaurus, collocations, writing studio |
| YOMUJP | website (graded readers with audio) | §6.4 graded readers with audio, levels N6→N1 |
| Tofugu | website | §6.4 guides library (links), G-13 kana course method |
| Chika Sensei's Japanese Academy | courses / free grammar lists | §6.6 mastery checklist, Japanese-only N1 mode |
| JPDB.io | web app | §6.1 media decks, coverage, known-words import, frequency decks |
| Immersion Kit | web app / API | §6.2 sentence bank with audio + thumbnails from own media; optional online source |
| AxTongue | web app | §6.3 synced lyrics, cloze-in-lyrics |
| にほんごで働く！ビジネス日本語30時間 | textbook | §6.5 Business & keigo track |
| Kanji Adventures for Gamers and VTuber Fans (Nihongo Picnic) | course | §6.5 Gaming & VTuber track, component hints |
| Japanese Graph | web app | §6.15 kanji graph view |
| コツ Pitch Accent Minimal Pairs Test (kotu.io) | web app | §6.7 pitch-accent perception test |
| Onomato Project | web app | §6.8 onomatopoeia module |
| Ulangi | open-source app | §6.9 Reflex and Atom games |
| Nihongo Alive: Listen & Learn from Real-Life Conversations | textbook + audio | §6.10 natural dialogues with fillers |
| まほろばことば (mahoblog) | blog | §6.14 poetry corner (public-domain texts only) |
| 日本語の森 JLPT この一冊で合格する | book series / videos | §6.6 "one book to pass" course view |
| Outlier Linguistics' Kanji Masterclass | course | §6.15 functional components and sound series (derived data) |
| Yomimono | web textbook | §6.6 module structure kanji → vocab → grammar → quiz |
| 小学生の語彙力アップ 実践練習ドリル1100 | drill book | §6.5 native schoolchild vocabulary track and drill formats |
| 生活者としての外国人向け 私らしく暮らすための日本語ワークブック | workbook (Bunka-cho) | §6.5 daily-life admin track, can-do statements |
| にほんごで文化体験 | textbook | §6.5 cultural experience tasks |
| Routledge Intermediate to Advanced Japanese Reader (genre-based) | textbook | §6.4 genre tags and task sets |
| USJETAA Japanese Reading Group | community | §6.14 reading circle mode (solo now, shared later) |
| Japanese–English Translation: An Advanced Guide | book | §6.12 translation workbench |
| Japanese Swotter | podcast | §6.10 drill sets (prompt → pause → answer), podcast RSS |
| Japanese In-Law: Words and Phrases for Day-to-Day Living | phrasebook | §6.5 Family & household track |
| Refold | methodology | §6.11 roadmap, immersion log, 1T mining |
| Japan Reader | app | §6.4 annotations, screenshot import, auto vocab list + context drill |
| 日本語NOW! Nihongo Now!: Performing Japanese Culture | textbook | §6.5 Performing culture track, memorize-and-perform mode |
| Lorenzi's Jisho | web dictionary | §6.15 inflection breakdown, instant results, component shortcuts |

