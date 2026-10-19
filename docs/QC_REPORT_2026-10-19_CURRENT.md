# Mokuhyo current-code QC and interview readiness — 19 October 2026

**Implementation follow-up:** D-044 addresses C1–C7 on `codex/interview-readiness-d044`.
See [implementation validation](qc/2026-10-19-current/D044_VALIDATION.md) for commits, failing-main regressions,
passing suites and remaining hardware/build limits. The findings below retain their original reviewed-revision context.

**Reviewed revision:** `033f0efd401b047b770c7a975950d90fd4eef99e`, remote `main` fetched on 19 October 2026. The initial workspace was `6b5e1f3`; it was clean and was fast-forwarded before this report was written. The newest semantic version tag returned by the remote was `v0.3.0`; that tag is not the reviewed commit. No installed release executable was supplied or audited. Development builds default to `0.1.0` (`0.1.0-dev` without the launcher property), so identify this review by commit, not the About label.

**CI status:** the owner confirms that CI on `033f0ef` is green on Windows, macOS and Linux, including `gate_build` and the packaged smoke. The three fixture-isolation failures below are specific to this sandbox's staged-resource setup; they are not reported CI failures or evidence of a packaged-build regression. CI results were supplied by the owner, not independently rerun during this review.

**Decision:** do not yet describe this revision as ready for reliable, low-delay spoken interviews on ordinary computers. The earlier F1–F4 fixes are implemented, but voice-input lifecycle, recovery during question generation, and conversation durability still need work. The fastest useful path is to fix these issues, remove feedback from the speech response path, and select a model using measured conversation latency. High-quality prepared audio can improve listening passages without increasing the learner's inference workload.

This is a source and Linux headless review. It covers the desktop speaking/listening screens, shared sessions and inference gateway, JNI scheduling, capture/playback, voice services, content rendering, assessment labels, and relevant tests/build configuration. It is not an exhaustive security audit or a Windows/macOS hardware certification. No real voice/model weights were auditioned or benchmarked, and no physical microphone was exercised. Measured human conversation latency and perceived naturalness remain unverified.

The implementation handoff is [BRIEF_INTERVIEW_READINESS_2026-10-19.md](../BRIEF_INTERVIEW_READINESS_2026-10-19.md). This report supersedes the older report's open/closed status for the reviewed revision; it does not erase the earlier evidence.

## What the latest update fixed

| Earlier finding | Current source and scope |
| --- | --- |
| F1: corrupt clips escape recovery | `PassageAudio.load` maps expected decode/read errors, checks cached audio, and labels replacement synthesis. Screens use `loadOrUnavailable`. All seven loader tests pass with isolated resources. C7 records three sandbox-specific failures where staged resources shadow the intended fixtures; three-OS CI is green. |
| F2: cancelled speech enables test answers | `QuestionDelivery` now tracks delivery per question, serializes Repeat, and keeps test answers closed until heard or explicit text fallback. `Voice.say` checks cancellation. This addresses cancellation during speech; cancellation during *question generation* has a different remaining problem, C2. |
| F3: cancellation leaks an opened output | `AudioIO.playOwned` holds the resource through cancellation and `Playback.close` is idempotent. Physical disconnects and drivers that block inside `write`/`drain` still require hardware testing. |
| F4: qualification lost in History/PDF | Stored conversation context and `SpeakingTrend.label` now feed History, results and report entries, including legacy fallback and bundle coverage. |
| Short-window navigation / empty Review focus | The navigation rail scrolls and the empty review queue avoids requesting focus. Render regressions were added. |
| Windows tooling | Python 3.12 is pinned and subprocess output is explicitly UTF-8. |

## Current findings, ordered for the coding session

### C1 — P1: starting transcription can cancel its own voice-input component

**Evidence:** [VoiceCapture.kt](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/VoiceCapture.kt), lines 51–75; [SpeakingScreen.kt](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/SpeakingScreen.kt), lines 435–466 and 853–860.

`VoiceCapture.finish()` sets the parent's `busy` to true and launches transcription in its own `rememberCoroutineScope`. Both Interview and Topic compose `VoiceCapture` only under `!busy`. Recomposition therefore removes the component and cancels its transcription scope. The code after `withContext` that clears busy and sends/stages the transcript is skipped. An ordinary slow transcription can leave the screen on “Transcribing…” with the answer unsent. Topic mode also hides End while busy. The Interview Cancel button owns the question job, not this transcription job.

**Reproduce:** record an answer with a delayed recognizer, then Stop or let VAD stop it; allow a composition frame before transcription returns. The report's minimal Compose probe reproduces the same component/scope ownership pattern; it is not a physical recording test.

**Fix:** retain a stable capture/session controller outside conditional UI. Give it explicit recording/transcribing/confirming states and a tracked transcription job; render disabled controls without disposing their owner. Reset state in `finally`, rethrow cancellation, and retain captured PCM for retry. On a real navigation away, cancel deliberately and preserve the draft according to C3.

**Acceptance:** a delayed fake recognizer completes and sends exactly once in Interview and Topic; practice retains its confirmation text/PCM; explicit Cancel stops ASR and restores controls; disposing the screen does not send a late answer. Exercise the actual screen, not only a pure state holder.

### C2 — P1: Cancel during question generation strands an interview

**Evidence:** `SpeakingScreen.kt`, lines 372–390, 433–437 and 477–483.

Start sets `started=true`. `nextQuestion` sets busy and suspends in `session.next()` before appending a partner line. Cancel clears busy but leaves started true. The idle controls are rendered only if the last line is a partner question. On the first turn there is no line; after an answer the last line is the learner. Neither case exposes Retry, End or a new Start. The generation path also lacks a `finally` that releases `queue.foregroundBusy(true)`.

This is a source-confirmed control-flow defect, separate from the now-fixed question-*playback* cancellation. It was not driven through a full live-model window in this review.

**Fix:** model generation as its own recoverable state. Always expose End/save and a Retry-current-question action after cancellation/failure; restore foreground scheduling in `finally`. Give turns stable IDs so retry never resubmits the learner answer, advances the phase twice, or accepts a late cancelled reply.

**Acceptance:** cancel before the first token on the first question and on a later follow-up; retry and end both remain available, no duplicate turn/phase advance occurs, and queued feedback cannot leave the session permanently busy.

### C3 — P1: an interrupted interview can lose its transcript before it is saved

**Evidence:** `SpeakingScreen.kt`, lines 300–316, 349–368, 394–406, 757–782; [ConversationRepository.kt](../shared/src/commonMain/kotlin/app/mokuhyo/history/ConversationRepository.kt), lines 40–66.

The active transcript lives in remembered session state. Interview completion waits for the feedback queue, rating, cultural review and After Action Brief before saving the conversation. Disposal closes the queue without checkpointing the transcript. Navigating away, closing the app, or failing during this final work can leave no conversation row in History. Some recording/feedback rows may already exist, but they do not reconstruct the lost transcript/session automatically. Topic audio is saved only after `session.say` succeeds; a failed reply can discard the submitted answer's recording entirely.

**Fix:** create a durable in-progress conversation and append accepted turns/audio before requesting the next model response. Save the completed transcript before optional grading; append the rating/brief later with explicit pending/failed status. Recover interrupted sessions after restart. Respect the audio-retention setting; disabling retained audio must not disable transcript durability. Preserve old completed records and bundle compatibility.

**Acceptance:** simulated failure after capture, after transcript acceptance, during next-question generation and during grading preserves all committed turns; restart offers recovery; a failed partner response does not erase the learner's answer; export/import retains pending and completed sessions without duplicate turns.

### C4 — P2: feedback directly delays speech and can occupy the next interview turn

**Evidence:** [TopicSession.kt](../shared/src/commonMain/kotlin/app/mokuhyo/opi/TopicSession.kt), lines 79–107; `SpeakingScreen.kt`, lines 738–751; [Corrections.kt](../shared/src/commonMain/kotlin/app/mokuhyo/opi/Corrections.kt), lines 167–175; [LlamaJniBridge.kt](../shared/src/jvmMain/kotlin/app/mokuhyo/ai/jni/LlamaJniBridge.kt), lines 25–35.

Topic generates a reply, then awaits `TurnFeedback`, then returns it to the screen, which finally speaks it. This also happens in After-action mode. A deterministic probe holds the critique request and demonstrates that the already-generated reply has not returned to the voice caller. This is an ordering result, not a measured model latency.

Interview's `FeedbackQueue` checks `foregroundBusy` before starting work, but cannot preempt an already-running critique. The single-worker Llama bridge can make a newly requested interviewer turn wait behind it. Cancellation currently applies to all generations issued so far, so naive “cancel background” logic risks cancelling the foreground reply too.

`AppGraph` allows 180 seconds per normal AI attempt and 240 seconds for CPU topic critiques; invalid output can trigger another attempt. These are failure ceilings, not acceptable interactive response budgets.

**Fix:** publish/speak the validated reply immediately. Run corrections afterward, or defer them until the end in the low-resource profile. Use a foreground-priority scheduler with task-scoped cancellation and a bounded feedback backlog. Persist accepted turns first; feedback failure must not hold playback or saving hostage. Use a whole-turn deadline covering retries and queue wait.

**Acceptance:** hold a critique indefinitely and verify the next reply still reaches audio within its deadline; test a new foreground request while a critique is already running; verify cancellation stops only the intended task and feedback remains attached to the correct turn.

### C5 — P2: steady speech from the first frame can be classified as “no audio”

**Evidence:** [Vad.kt](../shared/src/commonMain/kotlin/app/mokuhyo/speech/Vad.kt), lines 31–56; `VoiceCapture.kt`, lines 85–90.

The first RMS frame initializes the noise floor. If the user begins speaking immediately and maintains a similar level, the threshold becomes three times that speech level. Forty frames of RMS `0.15` produce `NO_AUDIO` and `heardSpeech=false`. The same speech after five quiet frames produces `SPEECH_START`. Current tests begin with a quiet baseline. The UI's `NO_AUDIO` branch stops and discards the buffer.

This is a deterministic signal-level reproduction, not a claim about the frequency of this failure with human voices.

**Fix:** avoid calibrating the floor from an assumed-silent first speech frame. Use robust startup/noise estimation or an approved speech VAD, preserve pre-roll, and allow manual completion/retry if non-silent samples exist. Don't solve it by blindly lowering thresholds for every microphone.

**Acceptance:** immediate speech, low-volume speech, fan noise, clicks, initial silence and long learner pauses. Measure false stops and missed onsets. Keep push-to-talk/manual Stop available. The existing 1.5-second silence timeout is a meaningful component of end-of-turn delay; shortening it must be tested with hesitant learners.

### C6 — P2: capture and answer processing still block the UI thread

**Evidence:** `VoiceCapture.kt`, lines 58–67 and 78–96; [AudioIO.kt](../shared/src/jvmMain/kotlin/app/mokuhyo/speech/AudioIO.kt), lines 65–71 and 81–96; `SpeakingScreen.kt`, lines 261–267 and 401–405.

Record/Stop directly open/close Java Sound lines. Stop can join a thread for up to one second and resamples/normalizes the entire buffer before the coroutine starts. The answer callback synchronously writes/hashes WAV data and runs segmentation/fluency analysis. Moving Whisper off the UI thread does not remove these stalls, especially for longer answers and slower disks.

**Fix:** move device acquisition/close, resampling, WAV I/O and analysis into owned IO/CPU work. Bound capture storage, serialize start/stop, and marshal UI updates back to the UI dispatcher. Share one operation state with C1 so repeated clicks cannot open multiple microphones or submit a typed answer over an active recording.

**Acceptance:** use a fake slow device and a two-minute answer; Record/Stop/Cancel feedback remains responsive, resources close once, and data survives cancellation at each boundary. Profile UI stalls on the baseline laptop; a headless unit pass is insufficient.

### C7 — Sandbox-specific test setup: staged resources shadow corrupt-clip fixtures

**Evidence:** [PassageAudioLoadTest.kt](../desktopApp/src/test/kotlin/app/mokuhyo/desktop/PassageAudioLoadTest.kt), lines 60–65; [AppGraph.kt](../desktopApp/src/main/kotlin/app/mokuhyo/desktop/AppGraph.kt), lines 60–65.

The tests place corrupt files under a temporary **data** directory. `AppGraph` selects bundled/staged packs first. In this sandbox, `desktopApp/resources/common/packs` exists, so the test loads that pack root and never finds its corrupt file. Three assertions fail with the generic “No voice…” or unqualified “voice service” result. These are sandbox-specific fixture-isolation failures, not a failure of the decoder recovery implementation on its intended file. The owner confirms that CI on the same revision passes on all three OSes, including `gate_build` and the packaged smoke.

**Optional test-environment hardening:** inject the test pack/resource root explicitly, and assert the resolved path before loading. Keep normal production precedence intact. Do not delete real resources or loosen the assertions to make the sandbox suite green. Run the same tests both before and after staging release assets. Treat this as local test reproducibility work, not a product or CI release blocker.

**Confirmation:** rerunning the seven loader tests in a fresh process with an empty bundled-resource root passes all seven, without changing application code or assertions. The staged files were left in place. This isolates resource selection as the cause of the three failures and verifies the recovery implementation against its intended fixtures.

## Speech architecture and performance recommendations

The current pipeline is essentially:

```text
learner stops speaking → 1.5 s VAD tail → stop/resample → full Whisper result
→ full validated LLM JSON (possibly queued/retried) → whole-utterance TTS → playback
```

Topic adds critique before TTS. `LocalLlamaModel.complete` currently supplies `onToken = {}`. Piper is already persistent per voice, with serialized requests and idle retirement; merely making Piper persistent is not a new optimization. The LLM also stays loaded and has native prefix reuse. Measure these existing mechanisms before replacing them.

1. **Instrument the entire turn.** Record capture stop, VAD endpoint, ASR queue/start/end, LLM queue/first token/validated utterance, TTS start/first PCM, output start/end, cancellation and persistence. Use monotonic time, session/turn IDs, model/voice hashes, backend and memory figures. Keep content/audio out of routine diagnostics. Report cold start separately from warm turns. An LLM duration or TTS real-time factor alone cannot establish conversational speed.
2. **Use the smallest model that meets language quality and measured latency.** `TierAdvisor` currently selects the highest eligible tier by RAM/VRAM, not speed. A 32 GB CPU machine may get a heavier tier that is worse for conversation. Pilot the already-approved small models first; retain per-language quality thresholds. Reserve memory for the OS, UI, Whisper, TTS and KV cache, and retest on battery. Larger post-session grading is optional and should not repeatedly swap models during a conversation.
3. **Deliver the first meaningful utterance sooner.** First remove C4 and defer the English gloss/analysis from the live schema. Start TTS after the spoken utterance passes its language/repetition checks. Then consider sentence streaming through a bounded queue. Never speak partial JSON or a candidate later rejected by validation. Preserve UTF-8 across token byte boundaries, stable voice identity, punctuation and per-turn cancellation; don't splice arbitrary token fragments into TTS.
4. **Warm within a memory budget.** Preflight microphone/output/voice and warm ASR, the selected LLM and live voice before Start. Separate warm-up from answer time. Cap simultaneous inference threads; native LLM, ASR and TTS can compete for cores and memory bandwidth. Prioritize capture/playback and the current response over feedback.
5. **Offer a fast guided interview.** Pre-render the scripted question bank and approved transition/follow-up variants. Use phase/topic/level rules to select a relevant prompt when the adaptive model misses a short deadline. This mode can keep spoken practice useful on modest supported computers, but is not equivalent to open-ended adaptive interviewing. Label the mode and its effect on scoring. Do not substitute typed practice and call the speech requirement satisfied.
6. **Define supported computers honestly.** Current native x86 builds require x86-64-v3/AVX2-family features; the Basic tier assumes about 8 GB RAM. “Any computer” is not a supportable claim today. Publish supported OS/CPU/RAM ranges and an in-app readiness check. If older CPUs must be supported, package/test an appropriate baseline binary. Additional online inference would change the current offline/no-paid-service product constraints and is not required for this plan.

### More natural live speech

- Select one interviewer voice and register per session; avoid switching timbre after a transient failure without telling the learner. Keep questions short, ask one thing at a time, and use relevant follow-ups grounded in the last answer. Stable role-play context, varied question forms and language-appropriate politeness improve realism independently of the synthesizer.
- Pilot **Kokoro-82M via an ONNX runtime** only for language/voice combinations its selected release actually supports. Pilot **bundled VOICEVOX Core** for Japanese. These remain candidates, not integrated engines or verified speed improvements. Audit the precise weights, runtime, phonemizer, voice terms and provenance; the app's licensing/provenance rules still apply. Preserve the existing Piper/OS fallback while measuring alternatives.
- The present Piper catalog covers `es`, `fr`, `de`, `pt-BR`, `ru`, `fa` (19 catalog voices); five other launch languages depend on installed OS voices for live speech. OS voice availability differs by platform and installation. Ship an approved offline voice or report a language as unavailable before an interview begins. Persian's known pronunciation problem needs native review; a synthetic round-trip score does not resolve it.
- Keep Chatterbox primarily in the prepared-audio build pipeline until combined-load laptop measurements justify a live option. The existing renderer explicitly targets the render host. A heavier voice running alongside Whisper and the LLM can increase lag even if it sounds better in isolation.
- Use language-aware pronunciation dictionaries/text normalization for names, ranks, acronyms, dates, decimals and numbers. Apply voice-specific controls only where supported. Avoid uniform slowing, random pitch changes, artificial filler on every turn and simulated breathing as a substitute for accurate pronunciation/prosody.
- Start with half-duplex plus immediate Stop/Repeat. Automatic barge-in requires echo handling, playback-reference suppression and a defined policy for how an interrupted test question becomes “heard”; an open microphone alone can transcribe the app's own voice.

Candidate project references for implementation evaluation: [Kokoro](https://github.com/hexgrad/kokoro), [Kokoro ONNX](https://github.com/thewh1teagle/kokoro-onnx), [VOICEVOX Core](https://github.com/VOICEVOX/voicevox_core). Pin and review the exact version before integration; these links do not establish laptop performance or complete packaging compliance.

### More natural recorded passages

Prepared passages can use expensive rendering once and play cheaply on the learner's machine. Keep high-quality source masters and encode delivery audio at its justified native bandwidth (24 kHz is already supported). Regenerate from the original higher-quality source where possible; upsampling an old 16 kHz clip cannot restore lost detail. No source-pack Ogg clips are present in this review checkout, so shipped audio quality was not auditioned.

Create a small reviewed pilot across narration, announcements, dialogue, numbers and longer passages before rerendering the whole bank. Cast stable voices per character and dialect; edit phrase boundaries and pauses for meaning rather than applying the same gap everywhere (`PassageAudio` currently inserts 350/700 ms; the render tools also use fixed gaps). Keep test semantics and transcript alignment unchanged. Retain synthetic provenance and all required credits.

Use native listeners to score intelligibility, pronunciation, rhythm, stress/intonation and speaker consistency separately. Check omissions, hallucinated words, repeated phrases, clipped starts/ends, excessive silence and loudness jumps. Establish a consistent loudness/true-peak target after auditioning the corpus. Verify normal and 0.8×/0.9× playback; WSOLA tone tests do not establish natural speech quality. Chatterbox's current `id → ms` mapping is an approximation requiring Indonesian review; Persian is unsupported by that renderer. Human-recorded, appropriately licensed material is a good option where model pronunciation fails.

## Proposed acceptance targets — not measurements of this build

Agree on these as engineering goals, then adjust based on actual supported hardware/language results. Measure from the **last audible learner speech to the first meaningful partner audio**, including endpoint detection, ASR and queue wait. Do not count a filler sound as a fast response.

| Metric | Initial target |
| --- | --- |
| Warm adaptive conversation, supported 16 GB reference machine | p50 ≤ 2 s; p95 ≤ 4 s for representative short/medium answers |
| Baseline supported 8 GB CPU profile | p50 ≤ 3 s; p95 ≤ 6 s, using a relevant guided fallback when needed; report fallback rate separately |
| Pre-rendered prompt start once selected | p95 ≤ 300 ms on local storage |
| Stop/Cancel | Visible acknowledgement ≤ 100 ms; audible stop p95 ≤ 250 ms; exercise blocking device calls |
| Live TTS under simultaneous app load | Real-time factor < 1 for every released language/profile; aim ≤ 0.5 for headroom and measure first PCM separately |
| UI | No sustained >100 ms main-thread task on capture/answer path; controls remain usable under thermal/battery load |
| Endurance / durability | 30-minute interview with no lost/duplicated accepted turns, no overlapping prompts, bounded memory, no growing queue |
| Failure recovery | Mic/output unplug, sleep/wake, cancellation, missing/corrupt asset, full disk and forced restart recover explicitly without false scoring |

Test Windows x64 on an 8 GB CPU laptop and 16 GB laptop, macOS Apple Silicon (8/16 GB), and a supported Linux CPU machine; include Intel macOS if still a supported release target. Use built-in, wired USB and Bluetooth devices, speakers and headphones, AC and battery, and 100/150/200% scaling. Run short and two-minute answers and at least 30 warm turns per cell, with repeated sessions before claiming stable p95. Stratify results by language and answer length; do not hide slow languages inside a combined average. A four-vCPU cloud build is not a substitute for this matrix.

## Verification and evidence

Full results, commands, logs and opt-in diagnostic sources are in [the evidence summary](qc/2026-10-19-current/EVIDENCE.md).

- **Standard Kotlin suites in this sandbox:** 408 cases reported (377 shared + 31 desktop), five environment-related failures: two missing Japanese development databases and three sandbox-specific corrupt-clip fixture-isolation failures. Zero runner skips, but 16 conditional integration-skip messages for unavailable model/voice assets.
- **Isolated corrupt-clip checks:** all seven pass with the intended resource root. This confirms C7 without changing the production implementation.
- **Three additional diagnostic probes:** reproduce C1's Compose lifecycle mechanism, C4's topic feedback blocking and C5's VAD startup behavior. Their assertions describe defects, not release success.
- **Python:** 12/12 scripts and Ruff pass. All 22 content banks pass strict structural validation. License/provenance/model-document checks pass.
- **Startup:** native CPU runtime, SQLite, all 11 languages and a nonblank frame pass with `--smoke --no-network`.
- **Three-OS CI (owner-confirmed):** green on `033f0ef`, including `gate_build` and the packaged smoke. These results are separate from the sandbox run above.

The sandbox run is not a complete release-gate result and should retain its recorded failures and coverage limits. It does not contradict the owner-confirmed green three-OS CI. Neither the headless probes nor the CI build/packaged-smoke results establish real-microphone reliability, natural voice quality or the proposed conversation-latency targets; those require the hardware evaluation described above.

All changes made for this review are documentation and diagnostic fixtures. No application code, shipped content, model manifest, dependency lockfile or learner data was edited. No release was published.
