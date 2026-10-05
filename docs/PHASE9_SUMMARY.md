# Phase 9 summary: follow-on features, natural voices and speaking reliability

Phase 9 (`BRIEF_PHASE8.md` Part D, items N-00 to N-14, including the owner's N-00 and N-00b added 2026-10-03) ran on the
`phase9` branch while Phase 8 content drafted, merged into main after v0.2.0, and shipped as **v0.3.0**, a
pre-release. Checkpoints are in `docs/PROGRESS.md`; decisions taken without the owner are D-040 and D-041 (the second
became an owner decision) in `docs/DECISIONS.md`.

## What was built

| Item | What it is | Gate |
|---|---|---|
| N-00 | Natural voices: Windows OS-voice fix (EncodedCommand, SAPI + WinRT, Rescan), Chatterbox pre-render (owner exception D-041), VOICEVOX for Japanese exam listening, Piper ar/id re-check | PASS for shipped content; live-engine gaps |
| N-00b | Speaking reliability: native-rate capture, VAD, confirm/edit/retake, Info.plist microphone key, Whisper prompt, slot-filling interviewer, LAN Ollama, error reasons, rolling log, Test the model, reply/critique split, context capped at `n_ctx_train`, GGUF labels | PASS, one gap (coherence) |
| N-01 | Consecutive interpretation drill (consecutive, radio relay, sight translation) | PASS |
| N-02 | Numbers under stress (ICU spellout, spelling alphabets, 500-item generated set) | PASS |
| N-03 | Exercise-week storyline with a remembering counterpart | PASS |
| N-04 | Rater calibration harness and confidence band | PASS on fixtures; owner input missing ("uncalibrated") |
| N-05 | Exemplar answers at ILR 1+, 2, 3 with "why" notes, audio, linked from the AAB | PASS, ko 87 / ar 81 of 90 |
| N-06 | Degraded-audio listening (radio, phone, noise, wind) | PASS |
| N-07 | Speaker variety: 19 Piper voices with measured gender, rotation, regional variants; Chatterbox pools 2F+2M per language | PASS, gaps (fa/ru/pt-BR female Piper voices) |
| N-08 | Authentic-format reading (six formats, RTL-aware) | PASS, 382 / 396 passages |
| N-09 | Doctrine refresh tooling | PASS |
| N-10 | Suggest a term / flag this item | PASS |
| N-11 | SBOM and no-network install profile | PASS |
| N-12 | Daily stand-to | PASS |
| N-13 | Side-by-side terms | PASS |
| N-14 | v0.3.0 pre-release with SBOM: https://github.com/budzillaorigin/mokuhyo/releases/tag/v0.3.0 | PASS (`gate_release`) |

## Owner decisions in this phase
- **Chatterbox Multilingual is a named exception to rule 13 (D-041).** Its speech tokenizer is CosyVoice2's (Alibaba).
  - It runs only at build time on the owner's RTX 5090; only rendered audio ships.
  - The provenance gate allows exactly this entry, and `docs/MODELS.md` and `docs/LICENSES.md` state the component.
  - Chinese is segmented with ICU, so the PRC-origin `pkuseg` is never installed.
- **Reference voices are donated TTS voices only.** No LibriVox or MLS readers.
- **The RTX 5090 is a shared machine.** The owner's CPU work comes first.
  - Renders run at below-normal priority with capped threads and restart every 25 clips to free leaked memory.
  - Only Claude's own processes are touched, by PID.

## Measured quality
- **TTS → Whisper round trip** (large-v3-turbo, number-aware token match; gate 80 %): ja 90 % (exam clips VOICEVOX
  98 %), ko 91 %, ar 92 %, zh-Hans 93 %, es 97 %, fr 93 %, de 97 %, pt-BR 99 %, ru 97 %. Clips under 70 % were re-rendered
  sentence by sentence.
- **Interviewer coherence** (simulated learner at ILR 1–2+, judged by the reference model; targets ≥ 90 % sensible,
  < 10 % scripted):

  | Tier | Model | Sensible turns | Scripted turns |
  |---|---|---|---|
  | B | EuroLLM-9B | 76 % | 12 % |
  | C | Mistral Nemo 12B | 87 % | 0 % |
  | D | Mistral Small 3.2 24B | 87 % | 1 % |

## Bugs found and fixed along the way (each with a regression test)
- `ContextWindow` was never applied, so long conversations overflowed the model's context.
- EuroLLM opened at 8192 tokens although trained at 4096, and the engine label came from the tier rather than the file.
- A failed critique dropped the whole turn, and a turn without a level crashed the rolling level.
- Whisper segments were glued together without spaces in every spaced language.
- The interviewer started a new role-play on every turn.
- Windows OS voices weren't found: PowerShell scripts lost their quotes in Java's launcher.
- The macOS bundle lacked the microphone usage string, so macOS never asked and recordings were silent.
- Chatterbox cut long answers off at ~40 s; lines are now rendered sentence by sentence.

## Known gaps
1. **Interviewer coherence** is below the 90 % sensible-turn target on every tier (above).
2. **No live natural voice.** Live Chatterbox on Tier B+ and Kokoro for Tier A Japanese are not built; they need a JVM
   port. Live speech uses Piper or the OS voice.
3. **No calibration data yet.** No instructor-rated recordings exist, so every interview estimate says
   "uncalibrated".
4. **Content shortfalls:**
   - Authentic-format passages: 382 / 396.
   - Exemplar answers: ko 87 / 90, ar 81 / 90.
   - Female Piper voices for ru, fa and pt-BR: none verified.
5. **Windows checks pending (owner):** OS-voice detection with Haruka, capture and level on Windows, installer smoke.
6. **Persian audio** stays on Piper, which Chatterbox doesn't support; its exemplar clips score 67 % in the round trip.
7. **Everything new is AI-drafted and unreviewed** (badged).
