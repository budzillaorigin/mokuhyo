# Mokuhyo 0.3.0 (pre-release)

**What's new in 0.3.0: natural voices, reliable speaking practice, and new drills.** On Windows, installing 0.3.0
upgrades 0.2.x in place; your history, recordings and settings stay.

- **Natural listening audio:**
  - Every shipped listening passage, dialogue and exemplar answer is pre-recorded with natural AI voices: Chatterbox
    Multilingual, or VOICEVOX for Japanese exam listening.
  - This covers Japanese, Korean, Arabic, Chinese, Spanish, French, German, Portuguese and Russian.
  - Each clip shows the voice that read it, and the AI voices carry an inaudible watermark.
- **Speaking that works:**
  - Recording uses your microphone's own sample rate, stops by itself when you finish, and tells you when no audio
    arrives.
  - You can check, edit or retake what was heard before sending it.
  - The interviewer follows a clear plan (topic, level, question type), and the screen says which model asked.
  - If feedback fails, the conversation still goes on.
  - Settings → AI → "Test the model" shows the exact error, and the model's context limit is respected.
  - Optionally, use an Ollama server on your home network.
- **macOS asks for the microphone** the first time you record. Earlier versions couldn't ask.
- **Windows:** system voices installed through Windows (such as Haruka) are now found; "Rescan voices" is in Settings.
- **New practice:**
  - Interpretation drill: consecutive, radio relay and sight translation.
  - Numbers under stress.
  - Exercise-week storyline with a counterpart who remembers you.
  - Daily stand-to.
  - Degraded-audio listening.
  - Side-by-side terms.
  - Authentic-format reading: signs, badge forms, phone chats, shift logs, notices, schedule boards.
  - Exemplar answers at ILR 1+, 2 and 3 for 30 interview questions per language.
  - More voices per language, with speaker variety in dialogues.
- **Security profile:** an SBOM ships with the release, and `--no-network` refuses every connection.

All new content is AI-drafted and marked "unreviewed" until a person checks it. Interview estimates say
"uncalibrated" until instructor-rated recordings calibrate them.

**Unofficial practice — not an official rating. Not affiliated with DLI, ACTFL, AFCLC or the LEAP program.**

| Computer | File |
|---|---|
| Windows 10/11 (64-bit) | `Mokuhyo-0.3.0-windows-x64.msi` |
| Mac with Apple silicon | `Mokuhyo-0.3.0-macos-arm64.dmg` |
| Mac with an Intel processor (AVX2, 2014 or later) | `Mokuhyo-0.3.0-macos-x64.dmg` |

Check downloads against `SHA256SUMS`. The installers are unsigned; see `docs/INSTALL.md`.

---

## 0.2.0

**What's new in 0.2.0: current military vocabulary and the cultural layer.** On Windows, installing 0.2.0 upgrades
0.1.x in place; your history, recordings and settings stay.

- **Counter-drone and base-defense track** in all 11 languages. It has 370–400 terms, each with:
  - the target-language term (cited to an allied source where one confirms it, otherwise marked "unconfirmed term");
  - a learner definition and two example sentences;
  - drills, a Lexicon screen, and 12 role-play scenarios.
  - It also adds listening dialogues and 18 reading and 12 listening passages per language.
- **Lexicon updates:** signed term packages you can import from a file or link (Settings → Content). The app checks the
  signature and shows what changed.
- **Culture:** culture cards for every scenario, with the field-guide section each draws on. A pragmatics pack covers
  address and rank, refusals, apologies, small talk, disagreement, hospitality, and gestures and silence, with "implied
  meaning" listening items built from it.
- **Partner personas:** six per language (twelve for Arabic), each with their own register, patience and formality.
- **Corrections your way:** Live, After action or Off for each conversation. After action ends with an After Action
  Brief: turn-by-turn review, model versions, patterns, and next steps.
- **Cultural notes:** shown in conversations and interviews, labelled as not part of the ILR scale. They never change
  your level.
- **This month:** links to official defense news sites per language, with a paste-to-practice reader. Nothing is
  fetched.

All new content is AI-drafted and marked "unreviewed" until a person checks it. Terms marked "unconfirmed term" have
no allied source that confirms them yet.

**Unofficial practice — not an official rating. Not affiliated with DLI, ACTFL, AFCLC or the LEAP program.**

| Computer | File |
|---|---|
| Windows 10/11 (64-bit) | `Mokuhyo-0.2.0-windows-x64.msi` |
| Mac with Apple silicon | `Mokuhyo-0.2.0-macos-arm64.dmg` |
| Mac with an Intel processor (AVX2, 2014 or later) | `Mokuhyo-0.2.0-macos-x64.dmg` |

Check downloads against `SHA256SUMS`. The installers are unsigned; see `docs/INSTALL.md`.

---

## 0.1.1

**What's new in 0.1.1:** the whole app is now dark, including the setup screens and the Windows title bar. In 0.1.0
parts of it showed a bright light background. On Windows, installing 0.1.1 upgrades 0.1.0 in place.

---

## 0.1.0

Free, open-source practice for lower-range DLPT-style reading and listening (ILR 0+ to 3) and an OPI-style speaking
interview, in 11 languages: Japanese, Spanish, French, German, Brazilian Portuguese, Russian, Chinese (Simplified),
Korean, Arabic, Persian and Indonesian. Everything runs on your computer. After the one AI-model download, the app
works with no internet.

**Unofficial practice — not an official rating. Not affiliated with DLI, ACTFL, AFCLC or the LEAP program.**

## Downloads

| Computer | File |
|---|---|
| Windows 10/11 (64-bit) | `Mokuhyo-0.1.1-windows-x64.msi` |
| Mac with Apple silicon | `Mokuhyo-0.1.1-macos-arm64.dmg` |
| Mac with an Intel processor (AVX2, 2014 or later) | `Mokuhyo-0.1.1-macos-x64.dmg` |

Check downloads against `SHA256SUMS`. The installers are unsigned. See `docs/INSTALL.md` for getting past the
SmartScreen and Gatekeeper warnings. No Linux build is provided.

## What's in it
- **Reading and Listening:** practice by level with explanations, plus timed 30-minute, 60-minute and full
  (180-minute, 60-item) tests with an estimated ILR level.
  - Banks: about 72 reading and 72 listening passages per language, roughly 1,570 passages in all.
  - Reading aids: tap-to-define with the built-in dictionary, furigana and pinyin, right-to-left Arabic and Persian.
  - "Generate more" drafts extra practice passages on your own computer.
- **Speaking:**
  - A 19-turn interview test and a shorter practice interview with the local AI model: voice in through Whisper,
    voice out through the bundled voices.
  - The model rates the interview and quotes your own words as evidence; recordings are saved for replay.
  - A topic-conversation mode with 99 topics per language.
- **Your data:** history, an ILR dashboard, a review queue (FSRS), a PDF progress report, and `.mokuhyo` backup files
  (optionally encrypted) that merge on import.
- **AI on your hardware:** pick a tier in the app (Phi-4-mini, EuroLLM-9B, Mistral-Nemo or Mistral-Small-3.2), or use
  an Ollama server you already run. No accounts, no subscriptions.

## Please know
- **All practice content is AI-drafted and not yet reviewed by a human.** It is labeled "AI-generated" throughout.
  Expect some awkward or wrong items, and report them.
- Listening in Japanese, Chinese, Korean, Arabic and Indonesian uses your computer's built-in voice. On Windows, you may
  need to add that language's speech pack in Settings → Time & language → Speech.
- The Persian voice mispronounces some words.
- On the mid-size model, the interviewer sometimes repeats a question or stays on one topic, and ratings are
  approximate.
- The update check is off by default. It can't see releases while the repository is private.
- On Windows, if the app doesn't start, run `%LOCALAPPDATA%\Mokuhyo\MokuhyoConsole.exe --smoke` and send us its output.
