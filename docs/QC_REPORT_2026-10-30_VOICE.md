# Mokuhyo voice naturalness and conversation flow review

**Reviewed revision:** `9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de`, also the remote `main` returned by GitHub on 30 October 2026.

**Finding:** the application does not yet meet the intended natural offline interview experience. The earlier handoff identifies useful model candidates, but the implementation still introduces rigid pauses, loses speech context at synthesis boundaries, and constrains conversation into repetitive question turns. Replacing a voice model alone will leave these problems in place.

The most useful next change is a small Japanese and Spanish comparison that tests the complete speech path: conversational wording, text preparation, synthesis, joins, playback and turn-taking. Use the same candidate voice for fixed and generated interviewer lines. Preserve the existing session, history, cancellation and assessment safeguards.

This review surveys the repository and traces the speech-related paths across shared logic, desktop UI, native integration, content packs, production tools, packaging and tests. It includes the two attached conversations. It is a focused source review across the codebase, not a claim that every unrelated feature or every language's content has been exhaustively validated. No installed release, current audition outputs, physical audio devices or live model weights were available for listening or performance measurement. Lingotalk and Jumpspeak are experience references supplied by the owner; their implementation and comparative performance were not established here.

## Intent and current implementation

| Requirement | Current evidence | Assessment |
| --- | --- | --- |
| Natural prerecorded discussions, produced on the owner's RTX 5090 | Chatterbox and VOICEVOX renderers exist; separate Piper render and runtime passage fallback paths exist | Production infrastructure exists, but timing and pronunciation need correction and native review |
| Arbitrary new interview questions spoken offline on learner computers | `VoiceService.create` registers Piper and OS speech only | New natural-voice candidates have not been integrated |
| Consistent interviewer identity | A topic persona selects a voice; streamed replies request the first selected voice again | Identity is not guaranteed through fallback, subsequent turns or fixed/generated transitions |
| Responsive conversation | Sentence streaming, inference priority, warm-up, bounded audio queue and cancellation paths exist | Good foundations; complete-WAV synthesis, endpointing and UI turn ownership still limit flow |
| Proof that the result sounds natural | Blind listening tools and benchmark targets exist | No new audition scores or completed learner-hardware results accompany this checkout |

The attachment mentions `BRIEF_OFFLINE_VOICE_2026-10-26.md`, `run_audition.py`, a completed Qwen audition and later scoring. Those artifacts are absent from this branch and were not supplied as files. The attachments contain the handoff and conversation excerpts, not the full named implementation brief or generated audio. The owner's earlier request to test Qwen already authorizes an audition; there is no reason to ask for that permission again. It does not establish a quality winner or a decision to ship its runtime.

The upstream [llama.cpp TTS documentation at the pinned b11040 tag](https://github.com/ggml-org/llama.cpp/blob/b11040/tools/tts/README.md) was rechecked. It does document Qwen3-TTS Base and Pocket TTS through `libmtmd`. However, [Mokuhyo's build](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/native/CMakeLists.txt#L94) disables tools and does not build a TTS worker. The upstream demonstration is a starting point for integration, not an already shipped persistent voice service. The documented Qwen language list overlaps eight of Mokuhyo's languages; Arabic, Persian and Indonesian still need a separate solution. Pocket's exact per-language weights, speaker assets and terms remain candidate-specific checks.

## Prioritized findings

P1 means a significant correctness issue or a major obstacle to the requested voice experience. P2 means an important quality improvement or a measurement limitation. Source-confirmed behavior is distinguished below from an acoustic effect that still needs listening evidence.

### V1 P1 The production splitter changes numbers before they reach TTS

[textsplit.py:6](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/tools/voices/textsplit.py#L6) splits after every period, including periods inside numbers. Repacking can introduce spaces even when the input is shorter than the chunk limit.

Direct reproduction with the current function:

```text
Input:  La temperatura es 3.5 grados. ¿Y mañana?
Output: La temperatura es 3. 5 grados. ¿Y mañana?
```

A scan of 9,460 script and exemplar lines found 18 affected lines in eight languages. Examples include `123.45 MHz` in the Spanish track dialogue and `8.000 Euro` / `20.000 Euro` in German listening content. The full inputs, transformed chunks and JSON locations are in [the review evidence](qc/2026-10-30-voice/evidence.json). This proves text corruption; it does not prove how each existing audio file pronounced it.

**Fix:** make chunking preserve the source text and protected numeric/abbreviation spans. Share segmentation cases with the JVM's existing ICU path. Apply locale-aware spoken normalization separately, where needed, without changing the displayed transcript. Preserve the truncation protection for long generations. Test decimals, grouping separators, abbreviations, times, initials, ellipses, quotations and CJK boundaries. Remove the assumption that one character limit represents comparable speech duration across languages.

### V2 P1 Several rendering paths impose incompatible fixed pauses

[Chatterbox rendering:80](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/tools/voices/chatterbox_render.py#L80) adds 250 ms between chunks and the job's pause after every line, including the final line. [Passage jobs:65](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/ChatterboxJobs.kt#L65) set that pause to 600 ms. [VOICEVOX rendering:64](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/tools/voices/voicevox_render.py#L64) also appends the job pause. [Runtime passage synthesis:134](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/PassageAudio.kt#L134) uses 350 ms for the same speaker and 700 ms for a speaker change; `RenderAudio` reuses that rule. Prompt jobs are a separate case and set their line pause to zero.

These delays are added to whatever leading/trailing silence the engine already generated. The compositor does not distinguish a clause continuation, a completed thought, a quick response or a deliberate hesitation. The resulting audible gaps need measurement, but the rigid additions are source-confirmed.

**Fix:** introduce one boundary policy shared by production and runtime passage rendering. Record whether a boundary is a continuation, sentence end or speaker turn. Measure each clip's edge silence and add only the remaining gap needed for that boundary. Preserve internal pauses and breaths; do not indiscriminately trim quiet speech or overlap two speakers. Remove automatic final-line padding unless a playback requirement needs it. Tune timing with native listening trials rather than replacing all gaps with one smaller constant or random jitter.

### V3 P1 The natural live voice package is still missing

[VoiceService:80](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/jvmMain/kotlin/app/mokuhyo/tts/VoiceService.kt#L80) registers Piper followed by OS speech. The voice manifest contains 19 voices across Spanish, French, German, Brazilian Portuguese, Russian and Persian. It contains no live voice for Japanese, Korean, Mandarin, Arabic or Indonesian. Those languages depend on installed OS voices for arbitrary speech; `OsVoice` supplies none on Linux. Prerecorded content cannot fill arbitrary generated questions.

Both checked-in pilot prompt indexes, Spanish and Japanese, have 116 entries and zero audio hashes. No passage/prompt Ogg audio is present in this checkout. Release assets may be supplied separately; this is not evidence that an installed release lacks all clips. It does mean this checkout cannot demonstrate the proposed fixed/live voice experience.

**Fix:** implement one evaluated backend behind the existing service, with a complete versioned voice package and a persistent worker. Include the model, decoder/projector and other required assets, an approved reference/preset, hashes and license records. Use local paths at runtime. Pass release-package offline tests before describing the language as having a bundled adaptive interviewer. Keep language and hardware coverage explicit.

### V4 P1 Sentence streaming does not preserve utterance-level delivery

[SpeechEngine:20](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/jvmMain/kotlin/app/mokuhyo/tts/VoiceService.kt#L20) returns a completed WAV. [Voice.sayStreamed:118](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/Voice.kt#L118) synthesizes each sentence independently. The next sentence is prefetched, which is useful, but no preceding/following speech context or delivery intent is passed to the engine. Every sentence opens and drains its own audio line through `Voice.output` and `AudioIO`.

This architecture permits repeated sentence-final intonation, uneven joins and device reopen delays. Their magnitude depends on the engine and device. A short one-sentence question still waits for the complete sentence's WAV. The Piper model process is already persistent; repeatedly cold-loading Piper is not the current problem. OS speech does start a new `say`/PowerShell process per request.

**Fix:** retain sentence streaming as a compatibility path. Add a capability-aware speech request carrying voice identity, locale, validated speech text and optional context/delivery information. For a capable engine, consume incremental PCM from one utterance and keep one output line open for that reply. For WAV engines, compare coherent short utterance synthesis against sentence synthesis before choosing the default. Do not split every comma to improve startup time: that can further damage phrasing. Add cancellation, format-change and bounded-buffer checks for the streaming path.

### V5 P1 Voice identity can change during a reply and auditions can mislabel it

[VoiceService candidates:59](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/jvmMain/kotlin/app/mokuhyo/tts/VoiceService.kt#L59) intentionally falls through to other voices after failure. `Voice.sayStreamed` retains the originally selected `used` voice even if a later sentence returns a different voice. With a requested persona voice, it retains that requested identity even if the first sentence falls back. The next sentence may try the original voice again. OPI does not persist a selected speech identity across turns, and fixed clips carry an independent voice.

The evaluation path has a related correctness error: [ListeningKit:45](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/ListeningKit.kt#L45) labels a returned WAV with requested `v`, rather than returned `SpokenAudio.voice`. A failed candidate can therefore appear in the blind kit as another voice's audio. Its source filter also accepts only Piper and opt-in OS voices, despite the comment claiming a new engine automatically participates.

**Fix:** give each interview/persona a persistent voice selection. Track requested and actual voice IDs, including every fallback. A voice failure after playback starts needs an explicit recovery policy; avoid silently changing identity mid-reply. For auditions, require the exact candidate to render or mark the sample missing. Make candidate selection explicit, and permit new engines without silently including restricted recording sources. Test A failing and B succeeding, including a failure after sentence one.

### V6 P1 The response deadline ends before speech is ready

[AiGateway:192](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/ai/AiGateway.kt#L192) considers the deadline satisfied when a sentence is handed to the speech channel. It does not wait for synthesis or audible output. [PiperEngine:39](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/jvmMain/kotlin/app/mokuhyo/tts/PiperEngine.kt#L39) allows `20,000 + 150 × text.length` ms per request, capped at 180,000 ms; multiple candidate voices can be tried. A 100-character sentence can consequently wait up to 35 seconds on one request even after meeting the text deadline.

**Fix:** distinguish text-ready, audio-ready and output-start deadlines. Give synthesis and fallback attempts a shared remaining response budget. If nothing has played, cancel the failed generation and use a known ready fallback according to the chosen mode. After speech starts, use an explicit interruption/recovery policy. Measure short-query latency as well as real-time factor: a slow initial packet can feel bad even when total synthesis is faster than playback.

### V7 P2 Capture remains a manual alternating exchange

[Vad:15](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/speech/Vad.kt#L15) uses a 1.5-second silence endpoint. [VoiceCapture:147](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/VoiceCapture.kt#L147) constructs it with defaults. Recognition starts after recording finishes. Practice defaults to transcript confirmation, and the screens keep capture unavailable during the partner's response. Each new answer requires Record.

These are reasonable deliberate-practice controls, but together they cannot deliver the continuous conversational interaction described by the owner. The silence wait alone consumes most of the existing two-second warm p50 goal. Shortening it blindly risks cutting off learners thinking in another language.

**Fix:** preserve manual practice and test controls; add an explicit conversational practice mode with automatic re-arming after output and configurable learner patience. Evaluate several endpoint settings against hesitant answers, quiet speakers and noise. Start with a reliable “I'm done” control and visible Stop/interrupt. Voice barge-in requires echo handling and accounting for partially heard replies; enabling the microphone during speaker output alone is insufficient. Persona patience currently changes only prompt wording, not the VAD setting.

### V8 P2 The language protocols encourage a questionnaire and discourage repair

[TopicTurn:390](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/opi/SpeakingPrompts.kt#L390) requires “1–3 sentences” and a question every turn. The live `PartnerReply` derives its system text from that prompt by cutting it at the literal text “Then give feedback.” It still introduces the partner as a tutor. There is no concrete guidance for answering the learner's question first, short relevant reactions, varying turn length, or staying with one thread.

[OpiInterviewerTurn:122](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/opi/SpeakingPrompts.kt#L122) bans any repetition/rephrasing of an earlier question. Its similarity validator can reject a needed clarification. [OpiSession.nextDomain:345](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/opi/OpiSession.kt#L345) selects an unused domain every turn; its pool is not filtered by phase or level. This competes with the instruction to build on the previous answer.

**Fix:** use explicit shared prompt fragments instead of slicing another task's prose. Separate the professional interview protocol from ordinary discussion. Permit meaningful acknowledgments, clarification and deliberate repetition when requested. Track the current topic and outstanding question; allow a few related follow-ups before changing domains, while preserving OPI coverage and phase requirements. Filter domain choices to suit the phase. Suggested replacement wording appears below.

### V9 P2 Content generation and fallback text can undermine natural delivery

[gen_dlpt.py:546](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/tools/items/gen_dlpt.py#L546) applies an “authentic document … written” instruction to both reading and listening, while the listening-specific instruction only adds “natural spoken” language. The in-app `GeneratePassage` similarly lacks a detailed spoken-dialogue protocol. Output checks focus on language, length and schema, not a plausible exchange.

There are concrete register mismatches in the fallback bank: the Spanish profile specifies `usted`, but `es-q-warmup-0+-01` asks `¿Cómo te llamas?`; the Russian profile specifies `вы`, but its first warm-up asks `Как тебя зовут?`. Both questions are valid informal language, but switching between them and formal live questions changes the interviewer's relationship to the learner.

**Fix:** define spoken genres separately: a briefing may appropriately sound prepared; an informal discussion should use responsive, uneven turn lengths and ordinary syntax. Keep grammar and register native at lower levels by simplifying ideas and vocabulary. Audit fixed and generated prompts against the same register policy, then regenerate affected audio. Add reviewed examples per language rather than translating an English conversational style indiscriminately. Do not add forced fillers or stage directions to all utterances.

### V10 P1 Partial speech and fallback are not represented as delivered conversation

[AiGateway:226](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/ai/AiGateway.kt#L226) counts sentences handed to the channel as “spoken.” If a later sentence fails validation, [SpeakingScreen:535](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/ui/speaking/SpeakingScreen.kt#L535) waits for the existing stream and then plays a different scripted question. `OpiSession` records that fallback question, while the learner may have heard an earlier question too. The existing stream test explicitly expects this two-question sequence. In topic mode, a failed partial reply returns no exchange even if some speech already played.

There is also an ordering defect in [SpokenReply:223](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/speech/SentenceStream.kt#L223): a producer-side synthesis failure completes `stop`, and the consumer then skips previously queued good audio. For example, while A plays, B can be queued and C can fail; B is then discarded. This path is source-confirmed but was not executed in the Kotlin suite here.

**Fix:** distinguish generated, queued, started, completed and interrupted delivery. Attach stable utterance IDs and played spans to existing turn records. Define how a partially spoken response is repaired and what the learner should answer; do not silently replace it with another topic. Represent synthesis failure as an ordered queue event so a later failure cannot erase earlier ready audio unless an explicit cancellation policy requires it. Exercise failures before first audio, between sentences and during playback.

### V11 P1 Production identity and cache behavior make auditions unreliable

[chatterbox_render.py:66](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/tools/voices/chatterbox_render.py#L66) calls `from_pretrained(device=device)` without a checkpoint selector, while `docs/MODELS.md` identifies `t3_mtl23ls_v3`. The installed production package determines the actual default; this checkout does not prove which checkpoint generated existing clips. Both production renderers skip any existing output file. Changing the model or reference can therefore leave old audio in place.

[make_refs.py](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/tools/voices/make_refs.py) produces synthetic Piper references. The Japanese Chatterbox pool uses Spanish, French and Portuguese references; several other pools also mix languages or regional accents. This is a plausible influence on voice character and prosody, not proof that reference choice caused the reported Japanese defects. An ASR round-trip score cannot settle it.

**Fix:** record actual package/model revisions and weight hashes on the 5090 before rerendering. Key outputs by text, engine/model, reference hash, seed/settings, segmenter and timing policy. Separate comparison directories; explicitly invalidate stale audio. Test reference choice separately from model choice. Prefer approved references in the target language and intended register, with natural phrasing and clean recording conditions. Preserve lossless masters. A voice description or reference does not make unsupported style controls work in a native backend.

### V12 P2 Current diagnostics do not measure the gaps the owner hears

[Voice.render:88](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/Voice.kt#L88) marks `TTS_FIRST_PCM` after the entire WAV returns. `OUTPUT_START` is marked after opening the device, before writing audio or passing any leading silence. [AppGraph:277](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/desktopApp/src/main/kotlin/app/mokuhyo/desktop/AppGraph.kt#L277) records the first listed voice, which may differ from a persona or fallback. The report's fallback category describes the LLM response, not every TTS substitution.

The listening kit tests isolated questions and converts sample rates but does not match perceived loudness. It has a useful general naturalness score, but no structured seam/turn-timing scores. The benchmark's null sink cannot establish audible gaps or physical cancellation. In its physical-output helper, playback exceptions are swallowed, so a failed device should also be surfaced in the results.

**Fix:** log actual per-utterance engine/voice, first PCM, synthesis completion, speech onset within the PCM, buffer underruns, inter-segment gaps, and final speech offset. Label device-open timing as a proxy and calibrate it with loopback or recorded device output. Keep text/audio out of ordinary diagnostics. Extend blind evaluation to connected replies and short multi-turn sessions, with matched loudness and separate cadence, pause placement, pitch/stress, continuity and responsiveness ratings. Report missing candidates and synthesis failures as well as means. Keep the existing real-hardware requirements and 30-minute endurance gate.

### V13 P2 Transcript-only assessment still claims pronunciation evidence

[OpiRate:255](https://github.com/budzillaorigin/mokuhyo/blob/9bbb0c23e74d0fdf2963c1fab91b1b3d48f1e4de/shared/src/commonMain/kotlin/app/mokuhyo/opi/SpeakingPrompts.kt#L255) asks for “pronunciation as reflected in the transcript,” although this task receives text. This was already identified in the handoff and remains present. ASR can normalize a mispronunciation or make a recognition error; neither supports a pronunciation judgment by this task.

**Fix:** restrict this assessment to transcript-supported grammar, vocabulary, functions and discourse. Retain separately labeled audio fluency heuristics. Pitch accent, tones and phoneme accuracy need their own evaluated acoustic evidence. This does not change the partner's sound, but it matters when claiming that the interview teaches natural speech.

## Suggested language protocols

Keep the existing small spoken JSON field so the model can start the response promptly. Put feedback and translations in their existing separate tasks. Use explicit prompt components for shared rules and mode-specific behavior.

For a discussion partner, adapt this wording to the selected language and scenario:

> Respond to the meaning of the learner's latest turn. If they asked a question, answer it first. Stay with the current situation unless they change it or it is finished. Use ordinary spoken {language} with the agreed register. Usually give one short thought; add a second when it helps. A brief acknowledgment is optional and should relate to what they said. Ask at most one follow-up question when there is something worth asking. A reply may end without a question. Avoid repeated praise, stock introductions, essay-style summaries and unnecessary restatement of their answer. If the meaning is unclear, ask a short specific clarification. Simplify vocabulary and ideas for the learner's level while keeping native grammar and phrasing. Return only the words to be spoken in the reply field; keep coaching and stage directions out of it.

For the OPI interviewer:

> Conduct the current phase in a calm professional manner. Ask one clear question or give one prompt at a time. Where appropriate, connect it briefly to a detail in the candidate's answer. Stay on the session's current topic until the planned follow-ups are complete. Move to a new topic with a brief natural transition when needed. Do not coach or correct the candidate during the interview. If they ask you to repeat or clarify, do so without supplying the answer or treating the repair as a new proficiency probe. Use concise spoken {language} in the specified register. Do not add routine praise or repeat the full candidate answer.

These prompts need matching state/validator changes. In particular, an explicit repair turn must be exempt from the unconditional near-duplicate rejection. Topic continuity must be decided consistently with the session plan. Evaluate small local models with concrete reviewed examples rather than assuming more instructions always improve output.

A short Spanish discussion example illustrates the difference in the intended behavior. It is a draft for native review:

```text
Learner: Ayer perdí el tren porque la reunión terminó tarde.
Partner: Qué mala suerte. ¿Pudo tomar otro?

Learner: Sí, pero llegué una hora tarde.
Partner: Menos mal que pudo llegar.
```

The second response can leave space for the learner to continue. A question on every turn is unnecessary. In formal interview mode, a follow-up such as `¿Qué hizo cuando se dio cuenta de que había perdido el tren?` can elicit narration while staying with the same event.

For authored discussions, supply the speakers' relationship, purpose, register and current situation. Ask for a connected exchange with short reactions and occasional longer explanations where the genre calls for them. Keep pronunciation aids and timing metadata separate from spoken text. Repeated ellipses, `[pause]` tags, invented breaths and random fillers are not a general prosody solution; some engines will speak them literally or exaggerate them.

## Implementation order and acceptance

| Work package | Concrete scope | Completion evidence |
| --- | --- | --- |
| 1 Correct the comparison | Fix numeric text preservation, exact-candidate labeling, source selection and render identities; retain current masters | Regression cases preserve numbers and abbreviations; a failing voice is marked missing; changing any render input invalidates the output |
| 2 Improve prepared speech flow | Unify boundary handling; distinguish genres; review 20–30 Japanese and Spanish utterances plus several connected dialogues | Blind comparison of current vs corrected composition, holding model/reference constant; native reviewers mark joins and pronunciation |
| 3 Audition the live voice | Compare current baseline and authorized Qwen candidate; evaluate the precise native output separately from Python output | Same representative text, multiple takes, exact metadata, native scores, measured memory and first-audio latency on learner hardware |
| 4 Integrate a consistent interviewer | Persistent backend, complete offline assets, voice identity, context/PCM capability where available, one output stream per reply | Arbitrary new questions work offline in a release package; fixed/live transitions preserve identity; failures and cancellation are observable |
| 5 Improve conversational behavior | Shared prompt components, topic continuity, repair turns, patience setting and optional automatic capture | Scripted interactions test question answering, clarification, hesitant learners, topic changes and no unwanted coaching in test mode |
| 6 Validate the whole session | Delivery accounting, audible timing, native listening and endurance | No missing/duplicated delivered turns; no unreported voice fallback; device tests and a 30-minute session pass |

Roll out language by language, with a configuration switch back to the existing backend and preserved audio masters. A rollback must not alter learner history. Do not bulk-regenerate the corpus until the selected configuration passes the small comparison.

The first audition should separate four effects: model choice, reference choice, synthesis unit (whole short turn versus independent sentences), and composition policy. Change one at a time before comparing complete configurations. Include brief acknowledgments, questions, two-sentence follow-ups, numbers/dates/names, a long turn requiring chunking, and fixed-to-live transitions. Include Japanese pitch accent and phrasing review, and Spanish register/accent review. Then evaluate full conversations with slow and hesitant learner answers. Choose by paired native preference and specific defect reports as well as the existing scores; no universal naturalness threshold is established by this review.

Existing latency goals remain useful: warm 16 GB p50 ≤ 2 s / p95 ≤ 4 s to first meaningful speech, 8 GB CPU p50 ≤ 3 s / p95 ≤ 6 s with disclosed guided fallback, prerecorded start p95 ≤ 300 ms, audible cancellation p95 ≤ 250 ms, TTS RTF < 1, and 30-minute endurance. These are targets, not results. Add measurements for first-speech onset and unintended gaps so a fast initial acknowledgment cannot hide a stalled meaningful follow-up.

## Validation and review limits

The following existing scripts passed: `python tools/voices/test_textsplit.py` (four cases), `python tools/voices/test_manifest.py` (19 voices, zero problems), and `python tools/voices/test_listening_kit.py`. The numeric corruption was reproduced separately and found in the corpus; the current splitter suite does not cover it.

The Gradle wrapper was downloaded through the configured proxy and the requested `:shared:jvmTest :desktopApp:test` task graph reached configuration, but execution was blocked by the absence of a JDK 21 compiler/toolchain. Java 21 runtime is present and `javac` is absent. These suites are not reported as passing. The broader Python item-generation test could not start because its `wordfreq` dependency is absent. No application source or model policy was changed; this deliverable adds the review and its evidence only.

The existing strengths should be retained: persistent Piper processes, sentence validation and prefetch, inference prioritization, off-path feedback/glosses, checkpoint recovery, corrupt-clip recovery and honest synthetic/unofficial labels. The next implementation should extend these pieces. Native listening of the exact shipping output is still required to establish that cadence and pronunciation meet the owner's goal.
