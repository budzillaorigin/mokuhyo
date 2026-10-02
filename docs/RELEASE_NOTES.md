# Mokuhyo 0.1.1 (pre-release)

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
