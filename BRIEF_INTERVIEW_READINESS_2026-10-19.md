# Coding handoff — reliable, responsive spoken interviews

Base this work on `main` at `033f0efd401b047b770c7a975950d90fd4eef99e` or newer. Read [docs/QC_REPORT_2026-10-19_CURRENT.md](docs/QC_REPORT_2026-10-19_CURRENT.md), its evidence, `BRIEF.md` and `CLAUDE.md` first. Check whether newer commits already resolve each item. The earlier F1–F4 fixes have merged; do not blindly reimplement the old follow-up brief.

The owner confirms that CI on `033f0ef` is green on all three OSes, including `gate_build` and the packaged smoke. The report's three fixture-isolation failures are specific to this sandbox's staged-resource setup. C7 is optional local test-environment hardening, not a product or CI release blocker.

**User goal:** usable spoken interviews on modest supported computers, plus more natural interviewer speech and recorded listening passages. Keep offline operation, local learner data, free use, honest assessment labels, and existing model/voice provenance rules.

## Work in this order

1. **C1: stop transcription from cancelling itself.** Move recording/transcription ownership outside the conditional `!busy` UI. Track ASR cancellation, retain pending audio/text, and reset operation state in `finally`. Add delayed-recognizer UI coverage for Interview and Topic, confirmation, Stop, Cancel and navigation.
2. **C2: make generation cancellation recoverable.** Cancel before the first question and after a learner answer must expose Retry and End/save. Reset feedback scheduling in `finally`. Use stable turn IDs and prevent duplicated answers, phase advancement and late speech.
3. **C3: persist before inference and grading.** Checkpoint accepted turns and permitted recordings durably. Save the transcript before draining feedback/rating/AAB. Recover interrupted sessions and preserve bundle compatibility. A failed partner reply must not discard the learner answer.
4. **C4: deliver speech before feedback.** Topic must return/publish the reply before critique in Live and After-action modes. Defer feedback on low-resource systems. Introduce foreground-priority scheduling with bounded queueing and task-scoped cancellation; the existing Llama cancellation is global to issued work. Test already-running background work, not only queued work.
5. **C5/C6: make capture resilient and responsive.** Fix VAD startup when speech is present in frame one. Move device open/close, resampling, file writes, hashing and fluency analysis off the UI thread. Keep manual Stop, retain recoverable audio, and serialize capture/submission.
6. **Optional C7: isolate the test pack roots for sandbox reproducibility.** Corrupt-clip fixtures should be selected whether bundled resources are staged or absent. Assert the resolved path and preserve production resource precedence. Do not treat the three sandbox-specific failures as failing three-OS CI.

Use one focused fix with a meaningful regression per commit. Each regression should fail against the reviewed behavior and pass after the change. Keep the previous delivery, scoring, label, playback-cancellation and bundle tests. Update PROGRESS/DECISIONS with actual results and unresolved hardware items; do not claim a complete speech pass from mocks.

## Then optimize the live path

- Add monotonic stage timings and first-meaningful-audio p50/p95, queue wait, fallback rate, TTS real-time factor, memory and cancellation metrics. Separate cold/warm runs and answer lengths.
- Choose a small approved model based on measured latency and language quality, with memory reserved for ASR/TTS/UI. Do not recommend the largest model merely because RAM permits it.
- Separate the spoken utterance from English gloss/critique. Send only validated utterances to TTS. Add bounded sentence streaming after cancellation and validation are correct.
- Warm services before Start; prioritize capture/playback/current response. Add a short overall response deadline and an explicitly labelled guided mode using pre-rendered relevant question-bank audio.
- Target the report's proposed 2 s median / 4 s p95 on the 16 GB reference profile and 3 s / 6 s on the baseline profile with transparent fallback. These are goals, not established capabilities. Record hardware/language exceptions rather than changing the definition of latency.

## Natural speech pilot

Pilot compact local TTS behind `SpeechOutput`: Kokoro ONNX for verified supported languages, VOICEVOX Core for Japanese, and curated Piper/OS voices elsewhere. Audit complete packaging/voice licensing and provenance. Benchmark with the real LLM and Whisper active. Keep stable interviewer voices, natural short prompts, relevant follow-ups, pronunciation normalization, and interruptible playback.

For prepared passages, use the existing Chatterbox/VOICEVOX build pipeline or licensed human recordings; preserve high-quality masters and use the supported 24 kHz path where appropriate. Review casting, dialect, pacing, pronunciation, loudness, omissions and repeat artifacts. Do not claim old 16 kHz recordings improve by upsampling. Indonesian-through-Malay and Persian require specific review/alternatives.

Deliver a small blind listening comparison and full-turn timing results before replacing default voices or rerendering the complete bank. A new TTS engine is not a fix for ASR lifecycle or feedback blocking.

## Done when

The named regressions and existing relevant suites pass; required development assets are distinguished from genuine defects; a fresh install works offline without additional user-installed runtimes; interrupted interviews recover with all accepted turns; and a 30-minute physical-device interview meets published latency and cancellation goals on the supported baseline machines. Report Windows/macOS/Linux results separately. Replace “any computer” with a tested support matrix and an honest readiness result.

Return the commits/diff, commands and results, a stage-timing table, voice comparison evidence, and remaining limitations. Preserve prior records and do not publish a public release as part of this handoff alone.
