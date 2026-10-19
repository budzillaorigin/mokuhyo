# Brief — Faster, more natural spoken interviews (next phase after D-044)

**Repo:** `budzillaorigin/mokuhyo` · **Base:** `main` at `b23716d` or newer (D-044 merged in PR #4; three-OS CI green on `b49bfde`).
**Read first:** `CLAUDE.md`, `BRIEF.md`, `docs/QC_REPORT_2026-10-19_CURRENT.md` (sections "Speech architecture and performance recommendations", "More natural live speech", "Proposed acceptance targets"), `docs/qc/2026-10-19-current/D044_VALIDATION.md`, and D-042 to D-044 in `docs/DECISIONS.md`. The second half of `BRIEF_INTERVIEW_READINESS_2026-10-19.md` ("Then optimize the live path" and "Natural speech pilot") is the starting point for this brief. This brief puts that work in order and gives each step a scope.

**Goal:** spoken interviews that feel like a conversation on supported laptops. The interviewer should answer quickly, sound natural, and never leave the learner waiting with no explanation. Everything stays offline, free and local (rules 1–3). Labels stay honest (rule 7). Model provenance rules stay in force (rule 13).

**What D-044 already fixed** (don't redo it): capture ownership, recoverable cancellation, checkpointing turns before inference, speech before feedback, foreground-priority `InferenceScheduler`, the VAD first-frame fix, and capture work off the UI thread.

## Ground rules

- Work on a branch (`claude/interview-speed`). Open a PR against `main` and leave it unmerged.
  - Don't add workflow files. Push-to-main CI already covers all three OSes.
- One change, one commit, one regression or measurement test. Use the repo's commit style: `speech: …`, `ai: …`, `opi: …`. Don't use `fix(speech): …`.
- Record choices as **D-045** in `docs/DECISIONS.md`. Add a `docs/PROGRESS.md` checkpoint.
- **New models or voice engines (N6):** follow rule 13 and rule 6. Each one needs a `docs/MODELS.md` row, a `content/models/manifest.json` provenance entry and a `docs/LICENSES.md` row, all in the same commit.
  - Stop and report instead of shipping if any of these is true: the developer or country can't be established; a component comes from a PRC-based organization; a GPL component would have to be linked into the app instead of run as a separate process.
  - Nothing new becomes a **default** without the owner's sign-off. Ship it behind an "Experimental" setting.
- Don't rerender the shipped passage bank. Don't change content packs except for N5's new prerendered prompt-audio index.
- Don't commit large raw logs. Put a short summary table in the PR and in PROGRESS. At most, add a few small representative logs under `docs/qc/`.
- Mocks prove ordering and correctness, not speed. Never report a latency number as met unless it was measured on real hardware with real models (see N7).

## Work in this order

### N1 — Measure the whole turn (do this first; everything else is judged by it)

1. Add a `TurnTimeline` in `shared/` that records monotonic timestamps per session and turn for these events:
   - capture: capture stop, VAD endpoint;
   - speech recognition: ASR queued / start / end;
   - model: LLM queued / start / first token / validated utterance;
   - speech output: TTS start / first PCM;
   - playback: output start / output end;
   - control: cancel requested / cancel effective;
   - saving: checkpoint saved.
   Also record the model id/hash, voice id, backend (CPU/GPU), threads, peak RSS, cold vs warm, and answer length in seconds. Record **no** transcript text or audio.
2. The headline metric is **last learner speech → first meaningful partner audio**, including endpointing, ASR and queue wait. A filler sound doesn't count.
3. Add `--bench-interview` to the desktop app. It plays a fixed set of recorded learner answers (short and two-minute, per language) through the real ASR → LLM → TTS path, with no microphone, for N warm turns after one cold turn. It writes a JSON + Markdown report: p50/p95 per stage, TTS real-time factor, fallback rate, memory and cancellation latency.
   - The answer clips must be **synthetic or self-recorded**, with a `docs/LICENSES.md` row. Never use official test audio.
4. Add a diagnostics panel in Settings → Speech & audio. It shows the last session's timeline table and has an "Export timing report" button. The export contains timings only.
5. Write `docs/BENCHMARK.md`: the hardware matrix from the QC report's acceptance section, plus exact steps the owner can follow on a laptop (install, pick the tier, run `--bench-interview`, run a 30-minute physical interview, export, attach the report to an issue).

**Tests:** the timeline orders events correctly under a fake pipeline; cancellation and retry produce the right entries; the bench report schema round-trips; the export contains no transcript text.

### N2 — Pick the model for conversation speed, not just RAM

`TierAdvisor` (`shared/.../ai/Hardware.kt`) recommends the **highest** tier that fits. On a 32 GB CPU-only laptop that can mean a 12B model that is too slow for conversation.

1. Add a short on-device **timing check** after model download and from Settings. It uses a fixed prompt with the live schema and measures time to first token and tokens per second, with Whisper and the voice loaded too.
2. The recommendation becomes the most capable tier that meets the live-turn budget (target ≤ 2 s p50 on the reference profile). It must also stay at or above that language's quality floor. Larger models stay available, marked "Slower — better for end-of-session grading" when they miss the budget.
3. Keep one conversation model loaded for the whole session. Optional post-session grading may use a larger model **only** after the conversation ends, and never swaps models mid-interview.
4. Reserve memory for Whisper, TTS, the UI and the KV cache in the fit calculation. Show the timing result and the reason for the recommendation in the picker.
   - Quality floors per language: use `content/models/calibration.json`, and the existing Phi-4-mini vs Granite 2B choice at tier A, where it exists. Where no evidence exists, record "unverified" instead of inventing a floor.

**Tests:** `TierAdvisorTest` cases where a slow measured tier is not recommended even though RAM permits it; the quality floor wins over speed; the recommendation explains itself; no mid-session swap.

### N3 — Get the first sentence out sooner

1. **Slim the live schema.** The live interviewer turn returns only the spoken utterance plus the minimal control fields. The English gloss (`english` / `reply_english` in `SpeakingPrompts.kt`) and analysis move to the background feedback pass or end-of-session work. The gloss still appears in the UI when ready, so practice users lose nothing.
2. **Stream sentences.** `LocalLlamaModel.complete` currently passes `onToken = {}` (`LocalLlamaModel.kt` ~line 111). Wire token streaming into a bounded queue of **complete, validated sentences** taken from the utterance field. Each sentence passes the same script, language and repetition checks before it reaches TTS. While sentence N plays, synthesize sentence N+1.
   - Never speak partial JSON, a sentence that is still being generated, or a reply that later fails validation. If validation fails after sentences have played, stop, mark the turn, and fall back as N5 describes.
   - Handle UTF-8 split across token boundaries (the JNI bridge already buffers this; keep it), keep one voice for the whole turn, and allow cancellation per turn.
   - Sentence splitting must be language-aware for all 11 languages. Reuse ICU segmentation and handle `。`, `؟`, `؛`, `।`-style and Persian/Arabic punctuation.
3. Add a **whole-turn deadline**, configurable with a sensible default (e.g. 8 s), covering queue wait, generation and retries. When it's missed, N5's guided fallback speaks instead of leaving the learner waiting. Keep the 180/240 s ceilings only as hard failure limits.

**Tests:** a fake streaming model that emits tokens with delays plays the first sentence before the last token arrives; a reply that becomes invalid mid-stream doesn't speak unvalidated text; cancel mid-sentence stops audio and generation; multi-byte splits in ja/zh/ko/ar/fa/ru come out intact; the deadline triggers the fallback.

### N4 — Warm up and check readiness before Start

1. Pressing **Start** first runs a preflight: microphone opens, output plays a short tone, the voice for this language exists, ASR is loaded, the LLM is loaded and warmed with a short prefix, and the TTS process is up. Show a short checklist with progress (rule 8).
   - Warm-up time is reported separately and never counted as answer latency.
2. **Per-language readiness panel** (D-042 listed this as outstanding): for each language, show the live voice source (Piper, an OS voice, or none), ASR, the model, and the last bench result.
   - If a language has no approved offline voice on this machine, say so **before** the interview starts. Offer guided text practice that is clearly labeled, instead of a broken spoken test.
3. Cap total native threads across LLM, ASR and TTS so they don't starve each other or the audio threads. Make the caps configurable and record them in the timeline.

**Tests:** each failing preflight check blocks Start with an actionable message; the readiness panel covers all 11 languages; the thread cap is applied.

### N5 — Guided fallback with prerendered questions

1. Use the existing build-time renderers (`tools/voices/`: Piper for its languages, VOICEVOX for Japanese, Chatterbox under D-041) to prerender the **scripted question bank** in each `content/packs/<lang>/opi.json`, plus a small set of approved transitions and follow-ups.
   - Index them per pack with hashes. Stay at 24 kHz.
   - Every clip carries provenance and credits (VOICEVOX character credits included) and has its `docs/LICENSES.md` rows.
   - Render a **pilot subset** first: 2 languages, one Piper and Japanese/VOICEVOX. Check the packaged size increase and report it before rendering all 11.
2. When the adaptive model misses the N3 deadline, or no model is installed, the session picks a relevant prerendered prompt by phase, topic and level, and plays it (target ≤ 300 ms to start).
3. Label the mode honestly: "Guided interview — scripted questions" on screen, in History and in the PDF.
   - Set how fallback turns affect the speaking estimate, in line with `SpeakingTrend`. A session that is mostly guided is provisional or excluded, with the reason stored.
   - Record the fallback rate in the timeline.
4. A guided turn is still **spoken**. Never substitute typed practice and count it as speech.

**Tests:** a missed deadline picks a phase-appropriate prompt; with no model the whole session is guided; labels flow to History and PDF through `SpeakingTrend.label`; the fallback rate is recorded; missing or corrupt prompt audio falls back to live TTS, then to text, without crashing.

### N6 — Compact local voice pilot (experimental, behind a setting)

1. Put candidate engines behind the existing `SpeechOutput` interface, run out-of-process like Piper:
   - **Kokoro-82M via ONNX Runtime**, only for languages its pinned release actually supports;
   - **VOICEVOX Core**, for Japanese live speech.
   Pin the exact versions and hashes.
2. **Provenance and license audit first, before any code lands.** Cover the weights, runtime, phonemizer chain (Kokoro's G2P, e.g. misaki/espeak-ng) and voice terms. Record developer, country, license and source for each in `docs/MODELS.md`.
   - Remember the rule 6 linking limits: GPL only as a separate executable.
   - If anything fails rule 6 or 13, or provenance can't be established, **stop that engine** and record why in D-045.
3. The engines are off by default and live under "Experimental voices". The app must never need a separate install by the user (rule 1). Measure packaged size per platform.
4. Persian needs native review for its known pronunciation problem. Indonesian (served through the Malay model) also needs native review. Don't claim either is fixed from a synthetic round-trip score.
5. Add a **blind listening kit**: a script that renders the same 10 interviewer prompts per language with Piper/OS vs each candidate, in shuffled, unlabeled files, plus a scoring sheet the owner can give native speakers. Score intelligibility, pronunciation and naturalness separately.

**Tests:** the engine starts, synthesizes and is cancelled within the stop budget; it falls back to Piper/OS when missing; the bench reports TTS real-time factor under combined load. Provenance gates (`check_provenance.py`, `models_doc.py --check`, `check_licenses.py`) pass with the new rows.

### N7 — Hardware results (owner runs; Claude Code prepares)

Claude Code can't run real laptops. Prepare everything the owner needs and leave the result columns empty:
- the `docs/BENCHMARK.md` runbook from N1;
- a results template table with one row per OS × RAM × language and columns for p50/p95 first audio, fallback rate, TTS RTF, cancel latency, peak memory and pass/fail against targets;
- a PROGRESS entry stating which targets are still unmeasured.

**Targets (from the QC report; goals, not claims):**

| Profile | Target |
| --- | --- |
| Warm conversation, 16 GB reference | first meaningful audio p50 ≤ 2 s, p95 ≤ 4 s |
| 8 GB CPU baseline | p50 ≤ 3 s, p95 ≤ 6 s, guided fallback allowed and reported |
| Prerendered prompt start | p95 ≤ 300 ms |
| Stop/Cancel | visible ≤ 100 ms, audible stop p95 ≤ 250 ms |
| Live TTS under load | RTF < 1 for every released language (aim ≤ 0.5) |
| Endurance | 30-min interview, no lost or duplicated turns, bounded memory |

## Out of scope for this phase

- Rerendering the full listening-passage bank, and loudness normalization of passages.
- Pronunciation dictionaries for names, ranks and acronyms. Note any obvious problems for a later phase.
- Automatic barge-in. Stay half-duplex with immediate Stop/Repeat.
- Any online or paid inference.
- Publishing a release or tagging a version.
- Changing the "Supported computers" claim in README/INSTALL before N7 results exist. Do add a draft support-matrix section marked "pending measurement".

## Done when

1. N1–N5 are merged-ready with tests. N6 is either integrated behind the Experimental setting, or stopped with the provenance/license reason recorded.
2. `./gradlew :shared:allTests :desktopApp:test` passes, apart from known missing dev assets. Python tests and ruff pass. The license/provenance/model-doc gates pass. `--smoke --no-network` passes locally.
3. `--bench-interview` runs end to end with whatever real models are available in the environment. Its report is summarized in the PR, and any stage that couldn't run is named.
4. D-045, the PROGRESS checkpoint, `docs/BENCHMARK.md` and the blind-listening kit are committed. The PR description maps commits to N1–N7 and lists exactly what the owner must run on real hardware.
