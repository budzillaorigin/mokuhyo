**Mokuhyo follow-up QC — 19 October 2026**

Reviewed `main` at [`51cb342ec7e4e3dfe65457a3340d4b8cd62c618a`](https://github.com/budzillaorigin/mokuhyo/commit/51cb342ec7e4e3dfe65457a3340d4b8cd62c618a), including the fixes merged in PR #1. Comparison starts at `219a988`, which added the [first QC report](QC_REPORT_2026-10-19.md); the intervening update changes 39 files. This review adds documentation and three screenshots only. No application source, tests, manifests or lockfiles were edited.

**Assessment**

The update makes meaningful progress: playback failures can refund test plays, unusable recordings can be excluded transparently, typed interviews no longer automatically become firm speaking estimates, and the playback pipeline preserves more of the voice's quality. The Python test suite is now self-contained and passes.

The first report's audio findings are **partially resolved**, not closed. Corrupt clips bypass the new recovery UI, cancelling an interview question can still enable answers before speech finishes, and cancellation during output opening can leak the line. Speaking estimates also lose their provisional qualification in History and individual PDF interview entries. Resolve these paths before treating the speech workflow as release-ready.

This was a Linux headless review of source, built JVM code, existing tests, rendered UI and synthetic fault probes. No production voice was auditioned, and no physical microphone, speaker disconnect, Windows/macOS installer or ordinary-laptop conversation benchmark was exercised. Screenshots use isolated synthetic learner data, not a real assessment.

**What improved since the first report**

| Earlier concern | Follow-up status |
|---|---|
| A failed listening play uses the allowance | Output opening now precedes counting; playback exceptions refund a play. Voided passages survive resume, leave the denominator, and make the saved estimate provisional. Existing regression tests pass. Corrupt-file loading and cancellation still need work: F1 and F3 below. |
| An unheard interview question leaves the learner answering blind | Ordinary no-voice/output failures now produce a message, block test answers, and offer Repeat or text practice. Text fallback is filed as practice. Cancellation remains a hole: F2. |
| Typed or uncalibrated speaking results appear firm | The new `SpeakingTrend` policy excludes practice, self-ratings, text fallback and mostly typed answers. Eligible uncalibrated or mixed-modality results are provisional. Policy tests pass; Home and the PDF cover honor the flag. History and PDF detail do not: F4. Human review and instructor calibration remain outstanding. |
| Settings previews a different TTS path | The preview now calls the same `Voice.say` service and configured output as interviews, and names the voice. Confirmed in source; actual voice/output parity needs hardware validation. |
| Microphone picker rejects formats the recorder supports | Enumeration now checks the recorder's capture candidates, including native 44.1/48 kHz formats. Code and candidate-list tests agree; device discovery was not tested with real microphones. |
| Test gates overstate coverage | Round-trip verdicts now fail missing declared clips unless explicitly allowed, and print expected/present/tested counts. Python overlap tests use an invented fixture corpus. Real-model Kotlin tests still return early while the runner reports a pass; see the coverage limits below. |
| Playback lowers pitch and discards bandwidth | WSOLA replaces sample-rate slowdown; synthetic tone tests pass. Synthesized passages retain their rate, and new imported/rendered Ogg clips use 24 kHz with backward-compatible 16 kHz decoding. This does not upgrade already shipped clips or establish perceived naturalness. |
| Stale cached voices and overlong TTS chunks | Cache identity now includes engine, voice ID, speaker and a render version, and fallback renders are not cached. Long sentences split at clauses, words and finally characters. Tests pass. A weights/runtime upgrade under the same voice ID still needs a version/hash or an explicit render-version bump. |
| No-model UI promises unavailable corrections | The rendered Speaking page disables Live corrections and explains scripted questions and self-rating. |

**Verification performed**

| Check | Result and practical limit |
|---|---|
| Full Kotlin suites, with `MOKUHYO_REQUIRE_PACKS=1` | 367 shared and 15 desktop cases: **382 reported, 2 failures, 0 errors, 0 runner skips**. Both failures are missing development assets: Japanese dictionary and tokenizer databases in `LanguageModuleContractTest`. The other 380 cases are reported passing, but stdout contains **16 conditional `SKIP` messages** for unavailable native test weights, Whisper, real LLM files, Piper and OS voices. This is not an end-to-end speech pass. |
| Focused regression run, also requiring packs | **35 cases across nine suites passed**, with no errors, runner skips or conditional skip messages. Covers session scoring/resume, speaking policy, time stretching, 16/24 kHz Ogg, output-open failures, history/review, PDF fonts, real exam packs and real OPI packs. The earlier 15 desktop cases include the new cache-key and round-trip-verdict tests. |
| Python tests and lint | **10/10 scripts passed**; Ruff passed. The two corpus-dependent failures from the first review are fixed by fixture-based tests. |
| Strict content validation | All **22 language/skill combinations** passed with zero errors or warnings. Content was not changed by this update; this verifies validator rules, not instructor review or proficiency calibration. |
| Metadata gates | License check, model-document consistency and model provenance passed. |
| Terms checks | Offline seed/alignment/feed checks passed. The full `gate_terms.sh` **failed** because reference PDFs/text caches are absent. Fixture tests do not substitute for checking shipped content against the real authorized corpus. |
| App startup | `--smoke --no-network` passed: SQLite opens, CPU native runtime loads, all 11 languages register, and a nonblank 1180×800 frame renders. |
| UI rendering | Rendered all nine destinations at 1024×700 pixels / density 1.0; Home, History, Speaking and Settings at 1536×1050 / density 1.5 and at 1024×700 / density 1.5: **17 frames total**. Inspected representative Home, History and Speaking frames. These are layout checks, not keyboard, screen-reader or native-window interaction tests. |
| Additional fault probes | Reproduced corrupt Ogg loading against `PassageAudio`; cancellation leakage with real `AudioIO.Playback` and an instrumented output line; and the interview answer-gate state using real `Voice.say`, delayed synthetic TTS and the screen's cancellation logic. Findings below distinguish these probes from a full interactive UI test. |
| PDF label check | Generated and extracted a PDF from a synthetic provisional speaking result. The cover is qualified; the interview entry is not. |

**F1 — High: corrupt clips bypass listening recovery**

At [PassageAudio.kt:27](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/PassageAudio.kt#L27), a present pack clip is decoded before any failure is mapped to `Result.Unavailable`. [ListeningPanel:487](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/exam/SkillScreen.kt#L487) calls this directly from `LaunchedEffect`. A decode/read exception escapes that effect instead of reaching the new retry/leave-out controls. Checking that a file exists and has nonzero length in the test pool does not establish that it is playable.

Reproduction: point an isolated app resource directory at an `es/audio/broken.ogg` containing 64 bytes, beginning with `OggS` and otherwise zero, and load a passage referencing it. The built implementation returned:

```text
corrupt-clip: result=null, exception=IllegalArgumentException: bad Ogg page at 27
```

This is a remaining failure path from the original issue, not evidence that a published pack contains corrupt clips. **Recommended fix:** convert expected file/decode failures into a recoverable audio result, preserve coroutine cancellation, and invalidate bad caches. Offer retry, regeneration when a voice is available, or explicit exclusion without consuming a play. Add a UI-level check that a corrupt Ogg reaches those controls rather than an uncaught effect exception.

**F2 — High: cancelling a question can bypass the “heard it” requirement**

[InterviewView:335](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/SpeakingScreen.kt#L335) initializes `voiceProblem` to null and only assigns it after `speak` returns. The partner question is added before synthesis. [Cancel:463](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/SpeakingScreen.kt#L463) cancels the job and sets `busy=false`; [the answer gate:450](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/SpeakingScreen.kt#L450) treats a null problem as permission to answer. If cancellation happens during synthesis, the assignment never runs. The test can offer Record/Send while the question stays hidden and no audio was produced.

A delayed `SpeechOutput` injected through the existing `SpeechProvider.set` hook, real `Voice.say`, and the same state transitions produced:

```text
cancel-before-speech: jobCancelled=true, busy=false, voiceProblem=null, canAnswer=true; TTS produced no audio
```

This verifies the production speech cancellation plus the screen's control-flow condition, not a physical microphone interaction. **Recommended fix:** track delivery per question explicitly as pending/playing/completed/failed/cancelled. Enable test answers only after that question's playback completes, or after explicit text-practice fallback. Reset delivery state for every question, serialize Repeat, and ensure Cancel interrupts active playback. Add a delayed-TTS cancellation regression that asserts the answer controls stay disabled.

**F3 — Medium: cancelling while output opens can leak the audio line**

[ListeningPanel:515](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/exam/SkillScreen.kt#L515) returns an opened `Playback` from `withContext(Dispatchers.IO + NonCancellable)` and closes it afterward if the parent is inactive. `NonCancellable` protects the IO block, but switching back to the cancelled caller still performs the dispatcher handoff's cancellation check. The result can be discarded before the cleanup branch is reached.

A barrier-controlled reproduction using the app's coroutine version, actual `AudioIO.Playback`, and a proxy `SourceDataLine` that records `close()` showed:

```text
cancel-during-open: cancelled=true, reachedCleanup=false, outputClosed=false
```

The probe cancelled the caller while its IO block was opening, then allowed it to return. This models leaving the Listening screen while opening is in progress; no physical device was used. The probe closed the line afterward. **Recommended fix:** keep acquisition and cleanup in a common `try/finally` ownership scope, or use a cancellation-safe resource handoff. Include the later dispatch to `playback.run` in that ownership design. Verify cancellation before, during and immediately after opening, with exactly one eventual close and no consumed play before playback starts.

**F4 — Medium: provisional speaking labels are inconsistent across views**

The new policy writes provisional status into the estimate table, but [History's conversation rows:61](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/HistoryScreen.kt#L61) display the conversation rating without consulting that status. The [PDF cover:97](../shared/src/jvmMain/kotlin/app/mokuhyo/report/ProgressReport.kt#L97) is fixed, while [individual interview entries:127](../shared/src/jvmMain/kotlin/app/mokuhyo/report/ProgressReport.kt#L127) still show only `ILR 2 (AI-rated)`. “AI-rated” identifies the source; it does not say the estimate is provisional or excluded from the oral trend.

Reproduction: save a synthetic model-rated `OPI_TEST` conversation plus its speaking estimate with `confidence=0.3, provisional=true`, then open Home, History and export a PDF. [Home shows `ILR 2*` and a provisional explanation](qc/2026-10-19-followup/provisional-home.png); [History shows `1 answers · ILR 2`](qc/2026-10-19-followup/provisional-history.png). Extracted PDF text contains both:

```text
Spanish Español: Reading — · Listening — · Speaking 2*
...
19 Oct 2026 · Interview test · ILR 2 (AI-rated)
```

**Recommended fix:** carry the same qualification and exclusion reason to every assessment display and export. Persist the response modality, calibration/model context and fallback status rather than keeping only the transient `trendNote`. Define how older results with unknown context should be displayed, while preserving the original learner records. Add one repository-to-UI/PDF regression using a provisional result and another using an excluded typed interview.

**Layout and usability observations**

At 1024×700 logical pixels, including a 1536×1050 render at density 1.5, representative pages rendered and all nine navigation destinations remained visible. The no-model Speaking copy is clearer, and disabled Live corrections now match available functionality. Content below the viewport still requires scrolling.

In the separate **1024×700 physical-pixel / density 1.5 stress case** (about 683×467 logical pixels), [the navigation rail clips Settings below the viewport](qc/2026-10-19-followup/navigation-150-stress.png). [The rail](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/Shell.kt#L73) has no scroll container. This is a simulated small effective viewport, not proof that the native minimum-size window permits this exact configuration on Windows/macOS. Make the rail scrollable or compact at short heights, then verify actual laptop resolutions and OS scaling. This layout predates the reviewed fixes.

The render log also emitted `FocusRequester is not initialized` on Review. Rendering continued. Check the empty review queue with keyboard navigation on a real window before classifying this as an interaction defect. A per-language readiness panel and a short guided first practice session remain useful next product improvements.

**Natural live voices on ordinary laptops: updated recommendation**

Keep the current response model for the next voice experiment. This update improves the audio pipeline without a frontier model, but it does not add a new live TTS engine: live speech still uses Piper with OS fallback. Naturalness and conversational delay remain unmeasured here.

1. **Finish recovery and cancellation first.** A more natural voice still needs reliable interruption, retry and device recovery. F1–F3 should have regression coverage before measuring the new experience.
2. **Exercise the quality changes with actual assets.** Re-render or re-import a small representative clip set at 24 kHz; old shipped clips remain 16 kHz. Compare normal/0.8×/0.9× speech with native speakers. Tone-frequency tests confirm pitch behavior but do not rule out speech artifacts, damaged consonants or awkward pauses.
3. **Reduce first-sentence delay.** Sentence delivery is still outstanding: [LocalLlamaModel:111](../shared/src/commonMain/kotlin/app/mokuhyo/ai/LocalLlamaModel.kt#L111) discards token callbacks, and interview speech waits for the returned text. Explore a bounded queue of validated spoken sentences, with synthesis overlapping playback and immediate cancellation. Do not speak raw partial JSON. Track time to first audio separately from TTS speed.
4. **Pilot small local TTS behind the existing interface.** The first report's Kokoro-82M ONNX pilot for supported languages remains reasonable; compare VOICEVOX Core for Japanese and retain selected Piper/OS voices elsewhere. These are candidates, not engines integrated or benchmarked by this update. Check the whole bundled runtime/phonemizer license and provenance chain. Keep the existing build-time Chatterbox workflow for prepared material while measuring live alternatives.
5. **Benchmark the whole conversation.** Use an 8 GB laptop and a 16 GB laptop with the real local LLM and Whisper active. Record first-audio p50/p95, sustained synthesis time divided by audio duration, peak memory, cancellation latency and failures. Faster-than-real-time synthesis under combined load, responsive interruption, and an offline 20-minute conversation without lost turns are acceptance goals. Blind native-speaker comparisons should score pronunciation and naturalness separately from ASR round-trip accuracy.

The remaining cost is engineering, packaging and evaluation. A paid frontier response option is not required to pursue these improvements. No new model download, provider integration or performance claim is part of this review.

**Reproducing the standard checks**

Activate the configured JDK/Gradle/uv environment first. In this cloud workspace that is `source /workspace/.mokuhyo-cloud/env.sh`; use the repository's documented prerequisites elsewhere. From the repository root:

```bash
MOKUHYO_REQUIRE_PACKS=1 ./gradlew :shared:allTests :desktopApp:test --console=plain --max-workers=4
MOKUHYO_REQUIRE_PACKS=1 ./gradlew :shared:jvmTest \
  --tests '*ExamSessionTest' --tests '*SpeakingTrendTest' --tests '*TimeStretchTest' \
  --tests '*OggOpusTest' --tests '*PlaybackOpenTest' --tests '*HistoryAndReviewTest' \
  --tests '*ReportFontTest' --tests '*RealExamPacksTest' --tests '*RealOpiPacksTest' \
  --console=plain --max-workers=4
./gradlew :desktopApp:run --args='--smoke --no-network' --console=plain --max-workers=4
tools/gates/gate_terms.sh
```

From `tools/`:

```bash
uv run --locked ruff check .
uv run --locked --group content --group release python -W ignore run_tests.py
uv run --locked --group content python -W ignore items/gen_dlpt.py validate --language all --strict
uv run --locked python gates/check_licenses.py
uv run --locked python models/models_doc.py --check
uv run --locked python gates/check_provenance.py
uv run --locked python -W ignore terms/gate_terms.py --no-sources
```

The full Kotlin and full terms commands have the environment failures described above; the focused checks do not establish a complete release gate. The startup smoke was run directly against the built runtime classpath using the same application entry point and arguments.

The three linked screenshots are included with this report. Detailed logs, saved XML results from the full run, temporary Kotlin probe sources, remaining frames and the synthetic PDF are local to `/workspace/.mokuhyo-qc-followup/`; they are not bundled in the repository. The probe setups and decisive observations are recorded above so their limitations remain visible after this cloud environment is gone.
