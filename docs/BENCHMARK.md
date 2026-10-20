# Interview speed benchmark

How to measure whether spoken interviews feel like a conversation on a given computer (BRIEF_INTERVIEW_SPEED N1, N7).
Cloud builds, mocks and fake engines prove ordering and correctness, not speed. **A latency target counts as met only
when it was measured on the real hardware below, with real models, and the report is attached to an issue.**

## The headline metric

**Last learner speech → first meaningful partner audio**: from the last audible moment of the learner's answer to the
first audible word of the partner's reply. It includes the end-of-speech wait (the VAD's 1.5 s silence tail), stopping the
microphone, speech recognition, any queue wait behind other model work, the model, speech synthesis and opening the
audio device. A filler sound would not count; the app plays none. Warm-up before **Start** is reported apart and never
counted as answer time.

Every stage is in the report so a slow turn can be explained:

| Stage | From → to |
| --- | --- |
| `first_audio` (headline) | last learner speech → partner audio starts |
| `endpoint` | last learner speech → VAD end of turn (or Stop) |
| `asr_queue` / `asr` | transcription queued → started → finished |
| `llm_queue` | model call queued → started (waiting behind feedback or another call) |
| `llm_first_token` | model started → first token |
| `llm_validated` | model started → reply passed its checks |
| `tts_first_pcm` | speech synthesis started → first audio ready |
| `output_open` | audio ready → output device playing |
| `playback` | partner audio starts → ends |
| `cancel` | Cancel pressed → work actually stopped |

Reports never contain what was said: no transcript text, no audio. Only stage times, counts and identifiers (model id
and file hash, voice id, backend, thread counts, memory).

## Targets (goals from the QC report, not claims)

| Profile | Target |
| --- | --- |
| Warm conversation, 16 GB reference | first meaningful audio p50 ≤ 2 s, p95 ≤ 4 s |
| 8 GB CPU baseline | p50 ≤ 3 s, p95 ≤ 6 s; guided fallback allowed and reported |
| Prerendered prompt start | p95 ≤ 300 ms |
| Stop/Cancel | visible ≤ 100 ms, audible stop p95 ≤ 250 ms |
| Live TTS under load | real-time factor < 1 for every released language (aim ≤ 0.5) |
| Endurance | 30-minute interview, no lost or duplicated turns, bounded memory |

## Hardware matrix

Test each cell with short and two-minute answers and at least 30 warm turns, with repeated sessions before quoting a
stable p95. Report each language separately; never hide a slow language inside a combined average.

| Machine | OS | RAM | Notes |
| --- | --- | --- | --- |
| Windows x64 laptop, CPU only | Windows 10/11 | 8 GB | the baseline profile |
| Windows x64 laptop | Windows 10/11 | 16 GB | the reference profile; GPU if it has one |
| MacBook, Apple Silicon | macOS | 8 GB | |
| MacBook, Apple Silicon | macOS | 16 GB | |
| Linux CPU machine (x86-64-v3 / AVX2) | Ubuntu or Fedora | 8–16 GB | |
| Intel Mac (AVX2) | macOS | 8–16 GB | only while Intel macOS is a release target |

Within each machine, vary what a learner would: built-in, wired USB and Bluetooth microphones and headphones/speakers;
on AC power and on battery; display scaling 100/150/200 %.

## Steps for the owner (one laptop)

1. **Install** the release build for the laptop (docs/INSTALL.md). Do not use a development checkout for timing.
2. **Pick the tier** in the app (first run, or Settings → AI). Let the model and the Whisper model finish downloading.
   Note the tier and model the picker recommended and why.
3. **Run the bench** from a terminal, with the app closed. It uses the model and Whisper model the app chose, never writes
   to your history, and needs no network:
   - macOS: `/Applications/Mokuhyo.app/Contents/MacOS/Mokuhyo --bench-interview --language all --turns 30 --no-network --out ~/mokuhyo-bench`
   - Linux (DEB): `/opt/mokuhyo/bin/Mokuhyo --bench-interview --language all --turns 30 --no-network --out ~/mokuhyo-bench`
   - Windows (PowerShell, in the folder the Start menu entry points to — right-click → Open file location):
     `.\Mokuhyo.exe --bench-interview --language all --turns 30 --no-network --out $HOME\mokuhyo-bench`

   Options: `--language es,ja` for a subset; `--clips <folder>` with your own recordings named `<lang>-short.wav` and
   `<lang>-long.wav` (any sample rate) instead of the synthetic answers; `--play` to play the partner through the default
   speakers (otherwise playback goes to a null sink and `output_open` is not measured); `--cpu` to force the CPU.
   It writes `bench-interview.json` and `bench-interview.md`. "Not run on this machine" in the report names every stage
   that couldn't run and why.
4. **Run a 30-minute physical interview**: Speaking → Interview test (or practice) in one language, answering out loud
   with the real microphone, for at least 30 minutes. Use Cancel and Repeat a few times. Unplug and replug the headset once.
5. **Export the timing report**: Settings → Speech & audio → Diagnostics → **Export timing report…**. It saves a `.json`
   and a `.md` with the last session's timings only.
6. **Attach both reports to an issue** titled `Timing: <OS> <RAM> <CPU/GPU> <languages>`, with the machine row from the
   matrix above, AC or battery, and the audio devices used. Paste the `.md` tables into the issue body.

## Results template (filled in by the owner — empty until measured)

One row per OS × RAM × language. Pass/fail is against the targets above for that profile.

| OS | RAM | CPU / GPU | Language | Answers | First audio p50 / p95 (ms) | Prompt start p95 (ms) | Fallback rate | TTS RTF | Cancel latency p95 (ms) | Peak memory (MB) | 30-min endurance | Pass / fail |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Windows 10/11 | 8 GB | CPU | | short / long | | | | | | | | |
| Windows 10/11 | 16 GB | | | short / long | | | | | | | | |
| macOS (Apple Silicon) | 8 GB | | | short / long | | | | | | | | |
| macOS (Apple Silicon) | 16 GB | | | short / long | | | | | | | | |
| Linux | 8–16 GB | CPU | | short / long | | | | | | | | |

Fill one row per language you ran (copy a row). Sources: `first_audio` and `prompt_start` from the bench report's
per-language table, fallback rate and TTS RTF from its summary, cancel latency from "Cancel llm/asr/tts", peak memory
from the report, endurance from the 30-minute session (no lost or duplicated turns, memory not growing — compare the
first and last turns' peak memory in the exported report).

## Supported computers (draft — pending measurement)

Not a claim until the table above has results. Current native x86 builds require x86-64-v3 (AVX2-family) CPUs, and the
Basic tier assumes about 8 GB of RAM; "any computer" is not supportable. README and INSTALL keep their current wording
until N7 results exist.

| Profile | Expected experience | Status |
| --- | --- | --- |
| 8 GB, CPU only | guided interview often; adaptive replies when the model meets the deadline | pending measurement |
| 16 GB, CPU only | adaptive interview on the fastest model that meets the budget | pending measurement |
| 16 GB+ with a supported GPU, or Apple Silicon | adaptive interview | pending measurement |

### What this environment could not measure (2026-10-19)

The cloud container that built this phase has no native runtime (its proxy refuses the pinned llama.cpp source
download), no model, no Whisper model and no voice, so `--bench-interview` ran as a labelled dry run: only VAD
endpointing (1.5 s, the configured tail) and scripted question selection were exercised. No latency target is met or
missed by that run; every row above is still empty.
