# Brief — Fix the follow-up QC findings (2026-10-19)

**Repo:** `budzillaorigin/mokuhyo` · **Base:** `main` at `cc6bfc8` · **Source:** `docs/QC_REPORT_2026-10-19_FOLLOWUP.md` (read it first, with its three screenshots in `docs/qc/2026-10-19-followup/`).

The first QC pass (D-042, PR #1) fixed most of the audio and assessment issues. The follow-up review found four paths that still break the guarantees D-042 set out to provide. Close them so the speech workflow can be treated as release-ready.

## Ground rules

- Follow `CLAUDE.md` in full: rules 5 (history never regresses), 7 (honest labels), 8 (heavy work off the UI thread, with cancel) and 11 (three OSes stay green) matter most here.
- **One fix = one commit = one regression test.** Commit scopes like `exam: corrupt pack clip reaches the recovery controls`.
- Work on a branch (`claude/qc-followup-fixes`) and open a PR against `main`. Do not force-push, and do not rewrite or delete learner records.
- Record the choices you make as **D-043** in `docs/DECISIONS.md` (same style as D-042), and add a `docs/PROGRESS.md` checkpoint at the end listing what was fixed, what was verified and what still needs hardware.
- Each regression test must **fail on `cc6bfc8` and pass after the fix**. Say in the commit body that you checked this.
- Do not touch content packs, models, manifests or lockfiles. No new dependencies.

---

## F1 — High: a corrupt clip bypasses listening recovery

**Where:** `desktopApp/.../desktop/PassageAudio.kt` (`load`, line ~27) and `ListeningPanel` in `desktopApp/.../ui/exam/SkillScreen.kt` (~line 487, `LaunchedEffect(passage.id, attempt) { audio = PassageAudio(app).load(passage) }`).

**Problem:** `decode(it)` on a pack clip runs outside any failure mapping. A truncated or damaged Ogg throws (`IllegalArgumentException: bad Ogg page at 27`) out of the `LaunchedEffect`, so the learner never sees the Try again / Leave out controls. The same is true of a damaged cached WAV (`cached.readBytes()` succeeds but is then unplayable) and of I/O errors reading either file.

**Fix:**
1. In `PassageAudio.load`, wrap pack-clip decode, cache read and render in failure handling that returns `Result.Unavailable(reason)` for expected failures (`IOException`, `IllegalArgumentException`/`IllegalStateException` from the Ogg/Opus decoder, `UnsupportedAudioFileException`). **Rethrow `CancellationException`** — never swallow it.
2. If a pack clip fails to decode and a voice is available, fall through to the voice-service path (and say so in `source`, e.g. "voice service — the pack recording was damaged"). In a **test**, keep D-042's rule that a test uses a pre-rendered clip only when it is actually usable; falling back to synthesis is acceptable, but the result must say the clip was replaced.
3. Validate a cached WAV before returning it (header parses, non-empty PCM). If it doesn't, delete that cache file and re-render once.
4. Give `Result.Unavailable` a reason the learner can act on ("This recording file is damaged. Try again, or leave it out of the score."). Retrying must not consume a play — confirm `session.canPlayAudio` / `playsOf` are untouched.
5. As a backstop, have the `LaunchedEffect` catch any non-cancellation exception from `load` and map it to `Unavailable`, so no future decoder bug can blank the panel.

**Regression tests:**
- Unit (`desktopApp` test): a resource dir containing `es/audio/broken.ogg` (64 bytes, `OggS` + zeros) — `load` returns `Unavailable` (or `Ready` from the voice service when a fake voice is installed), never throws. Same for a zero-length clip and a corrupt cached WAV (assert the cache file is removed).
- Cancellation: cancelling `load` mid-decode propagates `CancellationException`.
- UI-level: drive `ListeningPanel` (Compose desktop test or the existing render harness) with the broken clip and assert the Try again and, in test mode, Leave out controls are shown, and plays-left is unchanged.

## F2 — High: cancelling a question can bypass the "heard it" requirement

**Where:** `InterviewView` in `desktopApp/.../ui/speaking/SpeakingScreen.kt` — `voiceProblem` (~line 335), `askAloud`, `nextQuestion`, the Cancel button (~line 463), the answer gate `canAnswer` (~line 450) and the Repeat button.

**Problem:** the partner line is added to `lines` before synthesis; `voiceProblem` stays `null` until `speak` returns. Cancel during synthesis sets `busy = false`, so `canAnswer = !test || textFallback || voiceProblem == null` is true and Record/Send appear for a question that was never heard and is hidden. Repeat also runs in an untracked `scope.launch`, so it can overlap another playback and isn't stopped by Cancel.

**Fix:**
1. Replace the nullable `voiceProblem` gate with an explicit per-question delivery state, e.g.
   `enum class Delivery { PENDING, PLAYING, HEARD, FAILED, CANCELLED }` plus the failure message. Key it to the current question (index or line id) so it resets for every new question.
2. Set `PENDING` when the question is added, `PLAYING` when `Voice.say` starts, `HEARD` only on `outcome.ok`, `FAILED` on a non-ok outcome, `CANCELLED` if the coroutine is cancelled (use `try/finally` or `invokeOnCompletion` so the cancelled state is always written).
3. Test-mode answer gate: answer controls are enabled **only** when delivery is `HEARD`, or after the learner explicitly chooses "Continue as text practice". Practice keeps its current behaviour (it may show the text after a failure or cancellation).
4. After Cancel in a test, show the same recovery as a failure: "The question was stopped before it played." with Repeat and Continue as text practice.
5. Run Repeat through the same tracked `job`: ignore or disable Repeat while one is playing, set `busy` while it plays, and make Cancel interrupt active playback (not just synthesis). Confirm `Voice.say` stops its output line on cancellation; fix it there if not.

**Regression tests:**
- Extract the gate into a small pure function or state holder (e.g. `QuestionDelivery`) in `desktopApp` or `shared` and unit-test it: cancel during PENDING/PLAYING → test answers disabled; HEARD → enabled; FAILED → disabled until text fallback; new question resets state.
- Integration probe matching the report: delayed `SpeechOutput` via `SpeechProvider.set`, real `Voice.say`, cancel before audio → assert `canAnswer == false` and no audio was produced. Second case: Repeat during playback doesn't start a second playback, and Cancel stops it.

## F3 — Medium: cancelling while the output opens can leak the audio line

**Where:** the play button's coroutine in `ListeningPanel` (`SkillScreen.kt` ~line 515):
`val opened = withContext(Dispatchers.IO + NonCancellable) { runCatching { … AudioIO.open(…) } }`.

**Problem:** `NonCancellable` protects the block, but resuming into the cancelled caller throws `CancellationException` at the dispatch back, so the opened `Playback` is dropped before the `if (stop.get() || !isActive) playback.close()` branch runs. The later `withContext(Dispatchers.IO) { playback.run … }` has the same exposure.

**Fix:**
1. Move ownership of the line into a single scope that always closes it: open, the "still wanted?" check, `session.audioPlayed`, `run` and `close` all inside one `withContext(Dispatchers.IO)` block with `try/finally { playback?.close() }`, or hand the opened resource out through a holder that a `finally` in the caller closes. `close()` must be idempotent.
2. Keep D-042's order: a play is counted only after the line opened **and** the learner still wants it (not stopped, not cancelled). Cancellation before or during open never counts a play.
3. Make sure `playing` is reset in the `finally` too, so the button never stays on "Stop" after leaving and returning.
4. Apply the same pattern anywhere else that opens an `AudioIO.Playback` (search for `AudioIO.open` — e.g. `Voice.play`, History recording playback, Settings preview) and fix any that have the same handoff.

**Regression tests (`PlaybackOpenTest` or a new `PlaybackCancelTest`):** with a proxy `SourceDataLine` that records `close()` and a barrier around opening, cancel (a) before open, (b) during open, (c) immediately after open before `run`, (d) during `run`. Each case: exactly one `close()` for any line that opened, no play counted unless `run` actually started.

## F4 — Medium: provisional speaking labels are inconsistent across views

**Where:** `HistoryList` conversation rows in `desktopApp/.../ui/HistoryScreen.kt` (~line 61) and its `ConversationDetail`; interview entries in `shared/src/jvmMain/.../report/ProgressReport.kt` (~line 127) and the row data built in `ReportBuilder.kt`; `saveInterview` in `SpeakingScreen.kt`.

**Problem:** `SpeakingTrend` writes `provisional` to `ilr_estimate`, and Home and the PDF cover honour it, but History shows `1 answers · ILR 2` and the PDF entry shows `ILR 2 (AI-rated)`. Typed or text-fallback tests that were *excluded* from the trend look identical to counted ones. The reason (`trendNote`) is transient and lost after the results screen.

**Fix:**
1. **Persist the context with the conversation.** Add optional fields with defaults to `StoredConversation` (it's JSON in `turnsJson`, so no SQL migration and old rows still load): `spokenAnswers`, `typedAnswers`, `textFallback`, `calibrated`, and a `trend` status (`COUNTED_FIRM`, `COUNTED_PROVISIONAL`, `EXCLUDED`, `NOT_APPLICABLE`) with its reason string. Write them in `saveInterview` and in the self-rating save path in `InterviewResults` (which currently rebuilds `StoredConversation` without them — don't lose them there). Confirm the `.mokuhyo` bundle round-trip carries the new fields and that an older app version's bundle still imports.
2. **One label function.** Add a shared helper (e.g. in `shared/.../opi/SpeakingTrend.kt`) that, given a conversation's rating, stored context and its `ilr_estimate` row (if any), returns the display label and note. Use it in History rows, History detail, the PDF interview entries and the results screen, so the wording can't drift again. Suggested wording:
   - counted, firm: `ILR 2 (AI-rated)`
   - counted, provisional: `ILR 2* (AI-rated, provisional — not calibrated)` / `… — some answers typed`
   - excluded: `ILR 2 (AI-rated, not in speaking trend — mostly typed)` / `— text practice` / `— practice`
   - self-rated: unchanged `(self-rated)`, not in trend
3. **Older records** (no stored context): fall back to the `ilr_estimate` row — if one exists use its `provisional` flag; if none exists for a rated `OPI_TEST`, show "not in speaking trend". If neither tells you, say "recorded before 2026-10-19; trend status unknown". Never rewrite old rows (rule 5).
4. PDF: reuse the cover's `*` footnote so a provisional interview entry is explained on the same page.

**Regression tests (`HistoryAndReviewTest`, `ReportFontTest` or a new `SpeakingLabelTest`):**
- Repository → History row → PDF text for a synthetic model-rated `OPI_TEST` with `confidence = 0.3, provisional = true`: both show the provisional qualifier.
- Same for an excluded mostly-typed test: both say it isn't in the trend, and no `ilr_estimate` row exists.
- A legacy conversation with no stored context renders without crashing, using the fallback wording.
- Bundle export/import round-trip preserves the new fields.

---

## Also fix if time allows (lower priority, one commit each)

- **Navigation rail clips at short heights** (`Shell.kt` ~line 73): at ~683×467 logical px (1024×700 at 150 %) Settings falls below the viewport. Make the rail scroll vertically (`verticalScroll` on a `Column` inside `NavigationRail`) or switch to icon-only items below a height threshold. Add a render test at 1024×700 / density 1.5 asserting all nine destinations are reachable. Also check the window's minimum size.
- **`FocusRequester is not initialized` on Review** (empty queue): find the `requestFocus()` call that runs before its target is composed and guard it. Note in PROGRESS that real-window keyboard testing is still needed.

## Out of scope

Do not attempt these here — leave them listed in PROGRESS as open: sentence-streaming of interviewer replies to TTS, the Kokoro/VOICEVOX live-voice pilot, re-rendering shipped clips at 24 kHz, laptop benchmarks, physical device-disconnect tests, instructor calibration, and the missing dev assets (Japanese dictionary/tokenizer DBs, reference PDFs for `gate_terms.sh`).

## Done when

1. F1–F4 are fixed, each in its own commit with a regression test that failed on `cc6bfc8`.
2. These pass (from the repo root, after the usual environment setup):
   ```bash
   MOKUHYO_REQUIRE_PACKS=1 ./gradlew :shared:jvmTest \
     --tests '*ExamSessionTest' --tests '*SpeakingTrendTest' --tests '*TimeStretchTest' \
     --tests '*OggOpusTest' --tests '*PlaybackOpenTest' --tests '*HistoryAndReviewTest' \
     --tests '*ReportFontTest' --tests '*RealExamPacksTest' --tests '*RealOpiPacksTest' \
     --console=plain --max-workers=4
   ./gradlew :desktopApp:test --console=plain --max-workers=4
   ./gradlew :desktopApp:run --args='--smoke --no-network' --console=plain --max-workers=4
   ```
   plus the new tests, and from `tools/`: `uv run --locked ruff check .` and `uv run --locked --group content --group release python -W ignore run_tests.py`.
3. The full Kotlin suite shows no new failures beyond the two known missing-asset failures in `LanguageModuleContractTest`.
4. D-043 and a PROGRESS checkpoint are committed; the PR description maps each commit to F1–F4 and lists what still needs real hardware (speakers unplugged mid-play, real microphones, Windows/macOS windows at 150 %).
