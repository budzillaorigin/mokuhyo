# Build Brief: **Mokuhyo** (目標) — DLPT & OPI practice desktop app for LEAP languages

> **Audience:** Claude Code (Opus). This brief defines a **new repository forked from Tsumugi** (`budzillaorigin/tsumugi`, branch `v2`). Read it top to bottom before touching code. §1 becomes the new repo's `CLAUDE.md`.
> **Owner:** Buddy. The decisions in this brief were made with him on 2026-09-30; do not re-litigate them. Where a detail is missing, pick the simplest option consistent with §1 and record it in `docs/DECISIONS.md`.
> **Working name:** Mokuhyo ("target"). Verify no conflicting trademark before any release; never put "LEAP", "DLPT", "DLI" or "AFCLC" in the product name, icon or bundle id — those names appear only in factual descriptions ("practice for the kinds of tests LEAP scholars take").

---

## 0. Owner quick start (human section)

One command per line, in Terminal on the Mac.

```bash
gh repo create budzillaorigin/mokuhyo --private --confirm       # new empty private repo on GitHub
git clone https://github.com/budzillaorigin/tsumugi.git mokuhyo  # start from a full copy of Tsumugi (keeps history)
cd mokuhyo
git checkout v2                                                  # the branch with everything built
git checkout -b main                                             # Mokuhyo's main starts from Tsumugi v2
git remote rename origin tsumugi                                 # keep Tsumugi reachable as "tsumugi" (for cherry-picks later)
git remote add origin https://github.com/budzillaorigin/mokuhyo.git
cp ~/Downloads/MOKUHYO_FORK_BRIEF.md ./BRIEF.md                   # this file is the new repo's BRIEF.md
git add BRIEF.md && git commit -m "Fork from Tsumugi v2; add Mokuhyo brief"
git push -u origin main                                          # first push to the new repo
claude                                                           # start Claude Code in the new repo
```

First message to paste:

> Read BRIEF.md in full. Replace CLAUDE.md with §1. Drafting endpoint for every `tools/` content script and for `eval_speaking.py` is the Ollama server in §7.1 (`http://<5090-ip>:11434/v1`, model `mistral-small3.2:24b-instruct-2506-q8_0`); if it is unreachable, log it and continue, and never substitute a model that is not on the §7.1 list. Then execute Phases 0 through 7 (§11) back to back, without stopping for my review. At the end of each phase, pass that phase's automated gate (§11.1), write the `docs/PROGRESS.md` checkpoint, commit and push, then continue. Never stop to ask me anything: where a decision is needed, take the documented default in §12, record it in `docs/DECISIONS.md`, and keep going. When Phase 7 is done, write `docs/V1_SUMMARY.md` for me and stop.

---

## 1. `CLAUDE.md` for the new repo

```markdown
# CLAUDE.md — Mokuhyo

## What this is
A free, open-source desktop app (Windows, macOS, Linux) for practicing the reading, listening and speaking proficiency tests that military linguists and LEAP scholars take: lower-range DLPT5-style multiple-choice reading and listening (ILR 0+ to 3) and an Oral Proficiency Interview simulator, in 11 languages at launch, with a topic-conversation mode. Forked from Tsumugi (Japanese-only mobile app). Spec: BRIEF.md. If BRIEF.md and this file disagree, BRIEF.md wins.

## Non-negotiables
1. **Zero setup after install.** The installer contains everything except the LLM weights, which the app downloads once after the user picks a hardware tier inside the app. No Ollama, Python, Java, drivers or language packs for the user to install. No accounts.
2. **Fully offline after the one model download.** Every feature works with no network. The only network calls: model download, optional update check (off by default until the user enables it), optional existing-Ollama detection on localhost.
3. **No paid or metered service, ever.** LLM via embedded llama.cpp; STT via embedded whisper.cpp; TTS via bundled open voices run out-of-process; grading and feedback by the local model, always labeled.
4. **Language-neutral core, language-specific packs.** All logic lives in `shared/` (KMP) behind the `LanguageModule` interface (BRIEF §4). Nothing in `shared/` may hard-code Japanese. A new language is a pack plus a `LanguageModule` registration, never a code fork.
5. **Progress and history never regress or vanish.** Test attempts, OPI/conversation transcripts, recordings, ILR estimates and SRS state are append-only local facts; export/import merges and never overwrites.
6. **Licenses.** App code: Apache-2.0. Third-party code linked into the app binary: MIT/Apache-2/BSD/Unicode only. GPL programs (e.g. Piper/espeak-ng) may be **bundled as separate executables and run as child processes**, never linked; their licenses and sources ship in `docs/LICENSES.md` and the Licenses screen. Content: CC BY / CC BY-SA / public domain, with attribution. No official DLPT, OPI, DLI, ACTFL or LEAP material, ever. Every content and model asset has a row in `docs/LICENSES.md` added in the same commit.
7. **Honest labels.** All AI-drafted content is `source = "llm"`, badged until a human review flips `verified`. In-app generated items live in a separate bank labeled "Generated on this computer" and never mix into shipped-bank statistics. Every score screen says: unofficial practice, not an official rating, not affiliated with DLI, ACTFL, AFCLC or the LEAP program.
8. **Heavy work off the UI thread.** Model load, inference, Whisper, TTS rendering, pack install, PDF export and bundle export run on `Dispatchers.IO`/`Default` with progress and cancel. No spinner without progress for more than ~2 s.
9. **Every network call has a timeout** (connect 5 s; request 30 s chat / 120 s downloads) and a cancellation path.
10. **Tests before features for the core:** exam assembly and scoring, ILR estimation, OPI session, FSRS, bundle export/import round-trip, PDF report generation, `LanguageModule` contract tests run per language pack. All in `shared/src/commonTest` or `desktopTest`.
11. **Three OSes stay green.** CI builds and tests the desktop app on Windows, macOS and Linux runners on every push to main (Linux on PRs). A change that only compiles on one OS is not done.
12. **One file per learner move.** The `.mokuhyo` backup bundle (BRIEF §8) is the only way data leaves the machine; it is complete (DB + recordings + settings + packs list), versioned and importable by any newer app version.
13. **Model provenance (owner decision, 2026-09-30).** No models developed or released by organizations based in the People's Republic of China — not the LLM tiers, not STT, not TTS, not any fine-tune or distillation of one (no Qwen, DeepSeek, Yi, GLM, InternLM, MiniCPM, CosyVoice, GPT-SoVITS, MeloTTS, or derivatives). `docs/MODELS.md` records developer, country, license and source URL for every weight file the app can download or bundle, and `content/models/manifest.json` carries a `provenance` field the model picker displays. The owner's pack-drafting scripts default to the same approved list.

## Conventions
- Kotlin 2.x KMP (`jvm` desktop target first; `android`/`ios` targets removed in Phase 0, not kept dormant), Compose Multiplatform for Desktop, SQLDelight, kotlinx-serialization/coroutines/datetime, Ktor client (downloads only), Apache PDFBox for PDF, ICU4J for text segmentation. Packaging via `jpackage` (jlink runtime image), MSI/DMG/DEB + portable zip.
- Package `app.mokuhyo.*`. Language codes are BCP-47 (`ja`, `es`, `fr`, `de`, `pt-BR`, `ru`, `zh-Hans`, `ko`, `ar`, `fa`, `id`).
- Commits imperative and scoped (`exam: per-language blueprint loader`). One fix = one commit = one regression test.
- **Unattended run.** The owner has asked for all phases in BRIEF §11 to run back to back with no review stops. Each phase ends with its automated gate (BRIEF §11.1) passing, a `docs/PROGRESS.md` checkpoint, a commit and a push — then the next phase starts. Never block on a question: take the default in BRIEF §12 (or the simplest option consistent with rules 1–13), record it in `docs/DECISIONS.md` as `D-nnn (unattended default)`, and continue. A gate that cannot be made to pass after three distinct attempts is recorded as a known gap in PROGRESS with the exact failing command and output, and the run continues. Only two things end the run early: a licensing or provenance conflict with no compliant alternative (rules 6 and 13), or a destructive/irreversible action that is not already specified (deleting the owner's data, force-pushing, publishing a public release). Phase 7 ends with `docs/V1_SUMMARY.md`.
```

---

## 2. Product definition

**Who it's for.** Individual military linguists and LEAP scholars preparing for the lower-range DLPT5 (reading and listening, multiple-choice) and the OPI in their language, on their own Windows PC or Mac, with no IT involvement. Single learner, local data, a PDF progress report they can send a mentor.

**What it does.** Three sections, each with **Practice** and **Test**:

| Section | Practice | Test |
|---|---|---|
| **Reading** | Passages by ILR level and text type with tap-to-define, English comprehension questions, instant feedback and explanation; "add to review" for missed vocabulary/structures | Timed DLPT5-style multiple-choice form (full length or 30/60-minute slices), English questions on target-language passages, scored to an ILR estimate |
| **Listening** | Clips by level with transcript reveal after answering, replay, speed 0.8–1.0×, dictation drill | Timed listening form; each passage plays per the language's blueprint (default once), questions in English, ILR estimate |
| **Speaking** | (a) **OPI simulator**: interviewer conducts warm-up → level checks → probes → role-play → wind-down, voice-first, adaptive; rated after against the ILR speaking factors, transcript and recording kept. (b) **Topic conversation**: pick a domain and topic (or type one) and talk; corrections, natural rewrites and vocabulary notes after each turn; rolling level estimate | **OPI test mode**: full 20–30 min interview, no hints, transcript hidden until the end, rating + evidence quotes; counts toward the speaking ILR trend |

Plus: a **Home** dashboard (ILR estimate per modality with trend, next recommended activity, review queue count), **Review** (FSRS queue of missed items and looked-up words, per language), **History** (every attempt and conversation, searchable; recordings playable), **Report** (PDF export), **Backup** (export/import `.mokuhyo` bundle), **Settings** (language, model tier, voices, Whisper size, privacy, update check).

**Languages at launch (11):** Japanese, Spanish, French, German, Portuguese (Brazilian), Russian, Mandarin Chinese (Simplified), Korean, Arabic (MSA), Persian (Farsi), Indonesian. The learner can switch languages any time; data is kept per language.

**Not in scope (deliberately):** accounts, sync server, leaderboards, kanji/SRS curricula, WaniKani/Anki/Bunpro, immersion media player, lyrics, mobile targets, upper-range (3+/4) content, constructed-response formats, instructor mode (data model must not preclude it: every record carries `learnerId`).

---

## 3. Architecture

### 3.1 Repository layout (after Phase 0 pruning)

```
mokuhyo/
├── BRIEF.md, CLAUDE.md, README.md, LICENSE (Apache-2.0)
├── docs/                 PROGRESS, DECISIONS, LICENSES, CONTENT_PACKS, LANGUAGES, QA, RELEASE, PRIVACY, BUNDLE_FORMAT
├── shared/               KMP core, jvm target (+ commonMain kept pure so android/ios could return later)
│   └── src/commonMain/kotlin/app/mokuhyo/
│       ├── lang/         LanguageModule interface + registry, segmentation (ICU), lemma index, script utils
│       ├── dictionary/   per-language dictionary packs (generic schema), lookup, tap-to-define
│       ├── exam/         blueprints, assembly, session, scoring, ILR estimator (from Tsumugi, generalized)
│       ├── opi/          OpiSession, rubric, topic conversation, level tracker (from Tsumugi, generalized)
│       ├── ai/           AiGateway, LocalLlamaModel (JNI), Whisper (JNI), prompts per task with {language} slots, model catalog/tiers/downloader, hardware probe, Ollama detector
│       ├── speech/       recorder/player abstractions, fluency heuristics (transcript vs target, pauses, rate)
│       ├── tts/          VoiceService client (out-of-process Piper/Kokoro), OS-voice fallback, pre-rendered clip packs
│       ├── srs/          FSRS + review queue (from Tsumugi, trimmed)
│       ├── history/      attempts, conversations, recordings index, ILR estimates, learner profile
│       ├── report/       PDF progress report (PDFBox, Noto fonts per script)
│       ├── backup/       .mokuhyo bundle export/import (zip + manifest + optional XChaCha20)
│       ├── content/      PackInstaller (bundled + downloadable), generated-item bank
│       └── db/           SQLDelight: user.sq, history.sq, srs.sq, generated.sq, settings.sq
├── desktopApp/           Compose Multiplatform Desktop UI, platform audio (javax.sound), JNI loading, jpackage config
├── native/               llama.cpp + whisper.cpp JNI builds per OS (Metal / Vulkan / CPU), build scripts, prebuilt artifacts fetched by hash
├── voices/               VoiceService: bundled Piper (GPL, separate executable) + Kokoro runtime, voice manifests with provenance
├── tools/                Python: pack builders generalized with --language, content drafting (readers→passages), item validators, review tool, audio rendering, release scripts
├── content/              built packs (git-ignored; CI/Release artifacts)
└── .github/workflows/    ci.yml (3 OS), release.yml (installers + checksums)
```

### 3.2 What is kept, generalized, or removed from Tsumugi

| Tsumugi module | Mokuhyo |
|---|---|
| `exam/*` (blueprints, assembler, session, JLPT scoring, DLPT ILR estimator) | **Keep**; drop JLPT; blueprint becomes per-language data (§5.1) |
| `exam/opi/OpiSession`, prompts `opi_interviewer_turn`/`opi_rate` | **Keep**; add `{language}`, `{register notes}` slots; add topic-conversation mode |
| `ai/*` (AiGateway, LocalLlamaModel, ModelManager, OpenAICompatibleModel, SpeechEngines) | **Keep**; add hardware probe, tiers, desktop JNI, Ollama auto-detect |
| `speech/PronunciationAnalyzer`, `Yin`, `MoraAlignment` | **Replace** with language-neutral `FluencyAnalyzer` (transcript alignment by tokens, pause/rate stats). Pitch/mora code moves into the `ja` module as an optional extra |
| `srs/Fsrs*`, `AnswerChecker` | **Keep** (trim stages/unlock tree) |
| `dictionary/*` (JMdict-specific schema) | **Generalize** to a language-neutral dictionary schema (§5.2); JMdict becomes the `ja` pack builder |
| `jp/*` (tokenizer, deinflector, furigana, pitch, kana) | **Move** into `lang/ja/` as the Japanese `LanguageModule` |
| `reader/*`, `media/*`, `tracks/*`, `courses/*`, `onomatopoeia/*`, `translation/*`, `thesaurus/*`, `literature/*`, `sync/*`, `integrations/*`, `study/TodayPlanner`, kanji path, writing/strokes, games | **Remove** (keep `translation` grading prompt as a candidate for later; nothing else) |
| `server/` | **Remove** |
| `iosApp/`, `androidApp/` | **Remove** (git history keeps them) |
| `tools/items/gen_dlpt.py`, `ilr_bands.json`, `review.py`, `llm_draft.py`, `render_audio.py`, `readers/draft_readers.py` | **Keep and generalize** with `--language` (§7) |

### 3.3 Desktop runtime

- **JVM 21** runtime image via jlink inside the installer; Compose Desktop UI; SQLDelight JVM driver (SQLite).
- **Native:** `libllama` and `libwhisper` as shared libraries with the JNI surface Tsumugi already has for Android (`llama_jni.cpp`, `whisper_jni.cpp`), built per OS: macOS arm64/x86_64 (Metal), Windows x86_64 (Vulkan + CPU), Linux x86_64 (Vulkan + CPU). Built in CI by `native/build.sh|ps1`, pinned to llama.cpp/whisper.cpp tags, artifacts hash-pinned in `native/lock.json`. Loaded via `System.load` from the app's lib dir; failure to load the GPU variant falls back to the CPU variant automatically and says so in Settings.
- **Audio:** capture and playback through `javax.sound.sampled` (16 kHz mono PCM for Whisper); device picker in Settings; level meter; recordings saved as Opus or WAV under the app data dir.
- **Voice service (TTS):** a small bundled executable per OS wrapping Piper (GPL — separate process, stdin/stdout JSON protocol) and Kokoro-82M (Apache-2.0; for `ja`, optionally `zh`, `es`, `fr`, `pt`), started on demand and stopped when idle. No MeloTTS or other PRC-origin voice models (rule 13). OS voices (macOS `say`/AVSpeech via JNI-free CLI, Windows SAPI via PowerShell/WinRT CLI shim) are the fallback for any language whose bundled voice fails. Pre-rendered clip packs (Tsumugi's VOICEVOX Japanese exam/dialogue audio) are supported as a third source. The app never links espeak-ng or Piper code.
- **Data dir:** `%APPDATA%/Mokuhyo`, `~/Library/Application Support/Mokuhyo`, `~/.local/share/mokuhyo`: `db/`, `recordings/`, `models/`, `packs/`, `logs/`. Models and packs are excluded from any OS backup hints where the OS offers one.
- **Updates:** Settings → "Check for updates" (manual, plus optional automatic check) against GitHub Releases; downloads open the installer, never self-patch. Content pack updates are separate small downloads.

---

## 4. The `LanguageModule` contract

Everything language-specific hangs off one interface, registered per BCP-47 code. Contract tests in `shared/src/commonTest/lang/LanguageModuleContractTest` run for every registered module with its real pack.

```kotlin
interface LanguageModule {
    val code: String                     // "ja", "es", "zh-Hans", …
    val nameEnglish: String; val nameNative: String
    val script: ScriptInfo               // direction (LTR/RTL), needsSegmentation, hasSpaces, casing, fonts
    fun segment(text: String): List<Token>          // ICU BreakIterator default; ja lattice override; zh ICU dictionary-based
    fun lemma(token: Token): List<String>           // from pack's form→lemma index; identity for isolating scripts
    fun normalizeForCompare(s: String): String      // diacritics/tashkeel stripping (ar), ё/е (ru), width folding (ja), casing
    val dictionary: DictionaryPack?                 // generic schema (§5.2)
    val voices: List<VoiceSpec>                     // bundled voice ids by gender/role, with license refs
    val sttLanguage: String                         // whisper language code
    val examBlueprint: ExamBlueprint                // §5.1
    val opiProfile: OpiProfile                      // register system notes, formality markers, typical level-check topics, role-play seeds
    val topicCatalog: TopicCatalog                  // §6.3 domains/topics with language-specific prompts
    val readingAids: ReadingAids                    // furigana (ja), pinyin (zh), romanization toggle (ru/ar/fa/ko), tashkeel toggle (ar)
}
```

Per-language notes Claude Code must implement (details in `docs/LANGUAGES.md`):

| Code | Segmentation / lemma | Dictionary source (license) | Voice (bundled) | Aids |
|---|---|---|---|---|
| ja | Tsumugi lattice tokenizer + deinflector | JMdict/KANJIDIC2 (CC BY-SA, EDRDG) | Kokoro-82M ja (Hexgrad, US, Apache-2.0; run out-of-process because its G2P can call espeak-ng) + Tsumugi VOICEVOX clip packs (VOICEVOX, Japan) | furigana, pitch (optional) |
| zh-Hans | ICU dictionary segmentation | CC-CEDICT (CC BY-SA 4.0) | Piper zh_CN (Rhasspy, US; verify the voice's training-data license) or Kokoro zh | pinyin, traditional toggle (OpenCC data, Apache-2) |
| ko | ICU + simple particle/ending splitter | Wiktionary via kaikki.org (CC BY-SA 3.0) | **Open decision 4**: no permissive, non-PRC Korean neural voice exists in Piper/Kokoro today. Launch options: (a) OS voice at run time (macOS Yuna is preinstalled; Windows needs the Korean speech pack, which breaks rule 1 → show a one-click prompt and degrade to text), (b) ship Korean listening as pre-rendered clips the owner produces once with a commercially licensed voice whose terms allow redistribution, (c) train a Piper voice from a CC-licensed Korean dataset if one with a redistributable license can be found. The OPI interviewer for `ko` may be text-first until resolved. | romanization (RR) |
| es, fr, de, pt-BR, id | ICU word break; lemma index built from Wiktionary inflection tables | kaikki.org Wiktionary extracts (CC BY-SA 3.0) | Piper es_ES/es_MX, fr_FR, de_DE, pt_BR, id_ID | — |
| ru | ICU; lemma index from Wiktionary | kaikki (CC BY-SA) | Piper ru_RU | stress marks toggle, romanization |
| ar | ICU; tashkeel-insensitive matching; root display where available | kaikki (CC BY-SA) + Arabic Wordnet if license permits | Piper ar_JO | tashkeel toggle, RTL layout throughout |
| fa | ICU; ZWNJ-aware | kaikki (CC BY-SA) | Piper fa_IR | RTL, romanization |

Voice model licenses vary per voice even inside Piper; `tools/voices/manifest.py` records each voice's license and refuses to bundle one that is not redistributable. If a language ends up with no bundleable voice, its listening content ships as **pre-rendered clips** generated on the owner's machine with that voice (the rendering happens offline, outside the product) and the OPI interviewer uses the OS voice.

---

## 5. Content model

### 5.1 Exam blueprints (per language, data not code)

`content/packs/<lang>/blueprint.json`: section list (reading, listening), ILR levels covered (0+, 1, 1+, 2, 2+, 3), items per level per form, time limits (full form, 60-min and 30-min slices), passage play policy for listening (`plays: 1`, `questionsVisibleBeforeAudio: false` by default; the owner adjusts per language from the public familiarization guides), text-type mix per level (signs/notices/instructions at 0+–1; news, letters, factual reports at 2; editorials, argument, abstract topics at 3), question types (main idea, detail, inference, purpose, vocabulary-in-context, tone), and English-question rule. The assembler keeps each passage's questions together, prefers `verified` items, balances key positions, and never repeats a passage the learner saw in the last N forms.

### 5.2 Dictionary pack schema (language-neutral)

`entry(id, lang, headword, reading?, pos, frequencyRank?)`, `sense(entryId, ord, gloss_en, domain?, register?)`, `form(surface, entryId, tags)` for inflected forms → lemma, `example(entryId, text, translation?, source)`. Built per language by `tools/packs/build_dictionary.py --language xx`. Lookup: exact → lemma index → fuzzy (diacritics/case-insensitive) → prefix. Target < 5 ms on a laptop.

### 5.3 Exam item banks

Same JSON schema as Tsumugi's DLPT banks (`passages[]`, `items[]`, `source`, `verified`, `refs`) with `language` and `textType`, validated by `gen_dlpt.py validate --strict --language xx` against per-language `ilr_bands.json` (length, lexical rarity from the dictionary's frequency ranks, abstract-vocabulary ratio, sentence length). Listening passages carry `script[]` with speaker/voice hints and are rendered to clips by `render_audio.py --language xx` (Piper/Kokoro on the build machine) or spoken at run time by the voice service when a clip is missing.

**Launch targets per language:** reading 72 passages / 216 items (12 passages per ILR band), listening 72 / 216, OPI question bank 80 + 24 role-plays + the topic catalog (§6.3). Japanese starts from Tsumugi's existing DLPT bank (195 passages) and liaison content.

### 5.4 In-app generated bank

"Generate more" (Reading, Listening, Speaking topics) uses the local model with the same validators; results go to `generated.sq` with `bank = "local"`, show "Generated on this computer", are excluded from shipped-bank statistics, and can be deleted in bulk. Listening generation renders audio via the voice service. Generation is rate-limited by tier (Tier A: reading only, 1 passage per request).

---

## 6. Speaking

### 6.1 Model tiers and the in-app picker (first run and Settings → AI)

The app probes RAM, GPU/VRAM (Metal/Vulkan device name and memory), CPU cores and free disk, then recommends one tier and shows all four. Candidate weights obey rule 13 (no PRC-origin models) and rule 6 (permissive license: MIT/Apache-2.0). Verify license and developer at each model card before pinning; record SHA-256 and `provenance` in `content/models/manifest.json`:

| Tier | Typical hardware | Default model (GGUF Q4_K_M) | Developer / license | Download | Alternates to benchmark | Expected OPI turn latency |
|---|---|---|---|---|---|---|
| A — Basic | 8 GB RAM laptop, no usable GPU | Phi-4-mini-instruct (3.8B) | Microsoft (US), MIT | ~2.5 GB | IBM Granite 3.3 2B (Apache-2.0) | 10–25 s on CPU |
| B — Standard | 16 GB RAM laptop, or 6–8 GB VRAM | EuroLLM-9B-Instruct | UTTER consortium (EU), Apache-2.0; covers all 11 launch languages except Persian and Indonesian explicitly | ~5.5 GB | IBM Granite 3.3 8B (US, Apache-2.0); Mistral-7B-Instruct-v0.3 (France, Apache-2.0) | 4–10 s |
| C — Advanced | 32 GB RAM, or 12–16 GB VRAM, or 24 GB Apple Silicon | Mistral-Nemo-Instruct-2407 (12B) | Mistral AI (France) + NVIDIA (US), Apache-2.0 | ~7.5 GB | Phi-4 (14B, Microsoft, MIT) | 3–8 s |
| D — Workstation | 64 GB+ Apple Silicon, or 24 GB+ VRAM | Mistral-Small-3.2-24B-Instruct | Mistral AI (France), Apache-2.0 | ~14.5 GB | — (Gemma 3 27B and Llama 3.x are **excluded**: Google and Meta terms are not permissive under rule 6; the owner can opt them in explicitly) | 3–6 s with GPU |

Language coverage is uneven across these families (Persian and Indonesian are the weakest everywhere), so the per-language benchmark below is not optional: the picker shows a per-language quality badge for the selected tier and may recommend a different default for a given language (e.g. Granite over EuroLLM for Japanese/Korean if the eval says so).

Rules: the picker explains the trade-off in one sentence per tier; a tier the machine cannot run is shown disabled with the reason; the download is resumable, hash-verified, and runs in the background with a progress pill in the title bar; the learner can switch tiers later. If an Ollama server is detected on `localhost:11434`, Settings offers "Use my Ollama" with its model list as a fifth option, off by default, and any model there whose family is on the rule-13 exclusion list is listed greyed out with the reason. Per-language quality is benchmarked by `tools/models/eval_speaking.py` (a 60-prompt set per language scoring JSON validity, target-language purity, register control, and rubric agreement against a reference) and the results table lives in `docs/MODELS.md`; a tier whose score for a language is below threshold shows a warning for that language.

### 6.2 OPI simulator

Tsumugi's `OpiSession` generalized: phases warm-up → level checks → probes → role-play → wind-down; adapts up on sustained performance and down on breakdown; interviewer voice via the voice service; STT via Whisper (bundled `small` multilingual; `large-v3-turbo` optional download in Settings for accuracy). Rating prompt returns per-factor ILR evidence (functions, context/content, accuracy, text type), sustained level, breakdown level, estimate 0+–3, and three concrete next steps; shown with the unofficial disclaimer and the transcript with the learner's turns playable. Scripted fallback bank per language when no model is installed (self-rating checklist against the public ILR descriptors).

### 6.3 Topic conversation

Domains and example topics shipped per language (`topics.json`, ~100 topics each), editable, plus "type your own":

| Domain | Examples |
|---|---|
| Daily life | introductions, family, housing, food, shopping, health, transport, weather, hobbies, weekend plans |
| Work & study | your job, schedules, meetings, email follow-ups, education, career goals |
| Society & current events | news summary, local issues, environment, technology, education policy (ILR 2–3 probes) |
| Travel & culture | airports, hotels, directions, customs, festivals, etiquette |
| Military — garrison | ranks and units, base life, schedules, duty roster, PT, inspections |
| Military — operations | briefing a counterpart, exercise coordination, logistics and supply, movement orders, airfield operations, communications checks |
| Military — field situations | checkpoint, medevac request, casualty report, convoy, HADR/disaster relief, evacuation of civilians |
| Interpreter / liaison | escort-interpreter tasks, sight translation of a notice, relaying a commander's intent, clarifying ambiguity, cultural brokering |
| Abstract & hypothetical | opinions, hypotheticals, policy argument, ethics (ILR 3 practice) |

Each turn: the partner replies in the target language at the learner's rolling level; corrections (diff), a natural rewrite, and 1–3 vocabulary notes appear under the learner's turn; "say it again" repeats the turn. Conversations are saved with transcript, audio and the rolling level; the recurring-error log feeds the review queue.

### 6.4 Fluency feedback (language-neutral, honest)

Transcript-vs-target token alignment (for shadowing/read-aloud drills), speaking rate, pause ratio, false starts; a 0–100 composite labeled heuristic. No phoneme-level claims. The Japanese module may add pitch feedback from Tsumugi as an extra.

---

## 7. Content pipeline (tools/, generalized)

### 7.1 Drafting endpoint (set up by the owner before the run; Claude Code uses it, never installs it)

An Ollama server runs natively on the owner's Windows machine with an RTX 5090 (32 GB VRAM), exposed on the LAN at `http://<5090-ip>:11434/v1` (the owner writes the actual IP into the launch message; a DHCP reservation keeps it stable). It is configured with `OLLAMA_CONTEXT_LENGTH=16384` and `OLLAMA_KEEP_ALIVE=2h`. The models it serves, all compliant with rules 6 and 13:

| Tag on the server | Role | Developer / license |
|---|---|---|
| `mistral-small3.2:24b-instruct-2506-q8_0` | **Primary drafting model** for every `draft` command (readers → passages, exam items, OPI banks, topics, dictionary glosses where needed) and the reference grader in `eval_speaking.py` | Mistral AI (France), Apache-2.0; 8-bit, ~25 GB — fits the 5090 with 16k context |
| `gpt-oss:20b` | Secondary: second-opinion validator in the LLM quality checks, fallback if the primary is busy or errors three times in a row | OpenAI (US), Apache-2.0 |
| `mistral-nemo:12b` | App Tier C candidate — scored by the eval gate | Mistral AI / NVIDIA, Apache-2.0 |
| `phi4:14b` | Tier C alternate — scored by the eval gate | Microsoft (US), MIT |
| `granite3.3:8b` | Tier B alternate — scored by the eval gate | IBM (US), Apache-2.0 |
| `phi4-mini:3.8b` | Tier A — scored by the eval gate | Microsoft (US), MIT |

Rules for the pipeline: all scripts read `LLM_ENDPOINT` and `LLM_MODEL` from the environment (defaults in `tools/.env.example` point at the table above) and accept `--endpoint/--model` overrides; every request carries a timeout and up to three retries with backoff; a script that cannot reach the endpoint logs the exact command to re-run and exits non-zero without writing partial packs; EuroLLM-9B (Tier B default) is not in Ollama's library, so the eval gate pulls its GGUF from Hugging Face (`ollama pull hf.co/<utter-project repo>` or direct llama.cpp) and records the source in `docs/MODELS.md`. Nothing outside this table is ever used for drafting; if the owner later adds a model to the server, it must be added here (with developer and license) first.

- `build_dictionary.py --language xx` (per-language source adapters: jmdict, cedict, kaikki).
- `gen_dlpt.py draft --language xx --skill reading|listening --ilr 2 --n 12 --endpoint URL --model NAME` (owner's GPU machine with a large model) and `validate --strict --language xx`.
- `draft_opi.py --language xx` (question bank, role-plays, topic prompts), `draft_topics.py`.
- `review.py` per language and type; the in-app Content Review screen (developer toggle) exports verdicts.
- `render_audio.py --language xx` through the voice service binaries (Piper/Kokoro) on the build machine; VOICEVOX kept for Japanese.
- `build_packs.py --language xx` → `content/packs/<lang>/*.sqlite` + manifest; `release.py` assembles installers with all packs and voices.
- `tools/README.md` cookbook: exact commands per language, expected minutes, how to add a 12th language (adapter + blueprint + voice + topics + registration).

---

## 8. Data, backup and report

### 8.1 Local data model (SQLDelight)
`learner(id, displayName, createdAt)`; `attempt(id, learnerId, lang, modality, mode PRACTICE|TEST, startedAt, submittedAt, formJson, answersJson, scoreJson, ilrEstimate, provisional)`; `conversation(id, learnerId, lang, kind OPI|TOPIC, topic?, startedAt, endedAt, turnsJson, ratingJson?, rollingLevelJson, audioDir)`; `recording(id, conversationId|attemptId, path, durationMs, sha256)`; `ilr_estimate(id, learnerId, lang, modality, at, value, source attemptId|conversationId, confidence)`; `review_item`/`card`/`review` (FSRS, per lang); `generated_*` (local bank); `settings` (device-local). Deletes are tombstones.

### 8.2 `.mokuhyo` bundle
Zip: `manifest.json` (format version, app version, created, learner, languages, counts, checksums), `db.sqlite` (export copy), `recordings/…`, `settings.json`, `packs.json` (which content/voice/model packs were installed — the importing app offers to download missing ones, never bundles weights). Optional passphrase: Argon2id → XChaCha20-Poly1305 over the whole zip (Tsumugi's pure-Kotlin implementation), extension `.mokuhyo` either way with an `encrypted` flag in a cleartext header. Import is a **merge**: by ids; conflicting settings prompt; recordings copied; nothing overwritten. Round-trip and cross-version tests required; `docs/BUNDLE_FORMAT.md` documents it for third parties.

### 8.3 PDF progress report
PDFBox, embedded Noto fonts for every launch script (Sans JP/SC/KR, Naskh Arabic, Sans for Latin/Cyrillic). Contents: cover (learner name, language(s), date range, disclaimer), ILR trend per modality (vector charts drawn with PDFBox, no raster), test history table, OPI/conversation summaries (date, kind, topic, estimate, interviewer's three next steps), weak areas (text types, question types, recurring errors), review-queue stats. Options: date range, languages, include transcripts (off by default). **PDF only; no HTML export anywhere in the app.**

---

## 9. UI (Compose Desktop)

Left rail: **Home · Reading · Listening · Speaking · Review · History · Report · Settings**. Language switcher in the title bar. First-run flow: welcome → choose language(s) → hardware scan → tier choice (skippable: "Reading/Listening only for now") → audio device check (mic level, test voice) → Home. Keyboard-first (number keys for choices, space to play/pause, Enter to submit). Window ≥ 1024×700; scales with OS text size; dark mode; RTL layout for `ar`/`fa`; screen-reader labels; Japanese/Chinese fonts bundled (Noto) so CJK never falls back to boxes on Windows.

---

## 10. Packaging, distribution and QA

- `jpackage` per OS in CI (`release.yml`, tag-triggered): Windows MSI + portable zip, macOS DMG (arm64 and x86_64, or a universal via lipo if feasible), Linux DEB + AppImage/tarball. Installers ~1.5–2.5 GB (content, Whisper small, voices, JRE, native libs). Checksums and a signed SHA256SUMS published with every release.
- **Unsigned/self-signed by decision.** `docs/INSTALL.md` has screenshots for the SmartScreen "More info → Run anyway" and Gatekeeper "Open anyway" steps and the `xattr -d com.apple.quarantine` alternative; the website/README repeats them. Switching to signed builds later is a CI secret, not a code change.
- CI (`ci.yml`): Linux on PR (shared tests, desktop build, Python tests); all three OSes on push to main (build + tests + headless smoke launch); nightly size guard.
- `docs/QA.md`: per-OS manual list (audio devices, GPU variant selection, model download pause/resume, offline mode toggle, RTL, PDF fonts, bundle round-trip across OSes).

---

## 11. Phases (run back to back, unattended — no owner review stops)

### 11.1 Automated gates (replace the human review; every phase must pass its gate before the next begins)

Because no one is checking between phases, the gates are the quality control. Each is a script under `tools/gates/` so CI and Claude Code run the same thing:

| Gate | Checks |
|---|---|
| `gate_core.sh` (every phase) | `./gradlew :shared:allTests :desktopApp:test` green; `ruff` + Python tests green; `docs/LICENSES.md` has a row for every dependency in the Gradle lockfile and every file under `content/models/manifest.json` and `voices/manifest.json` (script diffs them); no PRC-origin model ids anywhere in manifests (rule 13 denylist regex); no GPL artifact in the app classpath or JNI lib dir (license scan of the jlink image). |
| `gate_build.sh` (Phase 1 onward) | `jpackage` succeeds for the current OS; the packaged app launches headless (`--smoke`) and exits 0 after opening the DB, loading native libs (CPU variant), listing registered languages and rendering one Compose frame offscreen; CI runs it on all three OSes on push. |
| `gate_lang.sh` (Phase 2 onward) | `LanguageModuleContractTest` passes for all 11 languages with real packs: segmentation, lemma lookup, dictionary hit on 20 known words per language, a TTS render of one sentence per language (or an explicit, logged fallback for `ko`), Whisper round-trip of a rendered sentence per language with ≥ 60% token match. |
| `gate_exam.sh` (Phase 3 onward) | For every language: a full reading form and a full listening form assemble from shipped packs at every ILR band with no repeats; scoring and ILR estimate deterministic on fixtures; `gen_dlpt.py validate --strict --language xx` 0 errors. |
| `gate_speaking.sh` (Phase 4 onward) | OPI session completes all five phases against a mock model and against the scripted fallback for every language; `eval_speaking.py` runs against the Tier B model on the build machine for at least `ja`, `es`, `ar` and writes `docs/MODELS.md`; rating JSON validates 100% on golden fixtures. |
| `gate_data.sh` (Phase 5 onward) | Bundle export → import round-trip on fixtures (plain and encrypted) is lossless and merge-idempotent; PDF report renders for a fixture learner in all 11 languages and every page passes a font-fallback check (no `.notdef` glyphs); cross-OS fixture bundles from CI artifacts import cleanly. |
| `gate_content.sh` (Phase 6 onward) | Pack counts meet §5.3 targets per language (or the shortfall is logged with the drafting command to close it); all items validated; every listening passage has audio or a logged fallback. |
| `gate_release.sh` (Phase 7) | Installers for all three OSes built in CI from a tag, checksums published, `docs/INSTALL.md` present, a fresh-VM install script (`tools/gates/fresh_install_smoke.ps1|sh`) installs and launches. |

Content that the owner was going to draft by hand (Phase 6) is drafted by Claude Code instead through the §7.1 endpoint (`mistral-small3.2:24b-instruct-2506-q8_0` on the 5090); all of it `source = "llm"`, badged, counts reported.

### 11.2 Phase list

**Phase 0 — Fork hygiene.** Rename packages to `app.mokuhyo`, remove the modules in §3.2 "Remove", drop ios/android targets, keep git history, new `CLAUDE.md`, Apache-2.0 `LICENSE`, prune `docs/` (keep DECISIONS history under `docs/history/`), make `./gradlew :shared:allTests` green with only kept modules. Gate: `gate_core`; PROGRESS lists removed modules and kept tests.

**Phase 1 — Desktop shell and AI runtime.** Compose Desktop app with the rail, first-run flow, settings; JNI builds of llama/whisper for the three OSes (CPU first, then Metal/Vulkan); hardware probe; tier picker; resumable downloads; Ollama detection; `jpackage` installers from CI; smoke test on all three OSes. Gate: `gate_core` + `gate_build` on all three OSes; a scripted smoke (`--smoke-opi`) loads the Tier A model from a local fixture path and completes one Japanese OPI turn with synthesized audio in and transcript out.

**Phase 2 — Language layer.** `LanguageModule` + contract tests; ICU segmentation; dictionary schema + builders for all 11 languages; voice service with Piper/Kokoro and OS fallback; RTL; fonts. Gate: `gate_lang` for all 11 languages (Korean voice fallback logged per open decision 4).

**Phase 3 — Reading and Listening.** Blueprints per language, practice modes, test modes, scoring, ILR estimates, history; Japanese content migrated; Review queue from misses and lookups. Gate: `gate_exam`; a scripted end-to-end test takes a timed reading and listening form in Japanese and Spanish and records estimates.

**Phase 4 — Speaking.** OPI simulator and test mode, topic conversation with domains, fluency feedback, scripted fallback, per-language prompts, eval harness and `docs/MODELS.md`. Gate: `gate_speaking`; a scripted full OPI test in two languages against the Tier B model on the build machine (mock model in CI); rating with evidence; conversation saved and replayable.

**Phase 5 — Data, backup, report.** History screens, ILR dashboard, `.mokuhyo` export/import with encryption and merge, PDF report. Gate: `gate_data`, including the cross-OS bundle import from CI artifacts.

**Phase 6 — Content at scale.** Claude Code builds the per-language validators/adapters/renderers, the in-app "generate more", and the Content Review screen, then drafts the packs for all 11 languages itself with the tools (§7) through the §7.1 endpoint (`mistral-small3.2:24b-instruct-2506-q8_0` on the 5090, `gpt-oss:20b` as the second-opinion checker), all `source = "llm"` and badged. Gate: `gate_content`; any shortfall against §5.3 is logged with the exact `draft` command that closes it, so the owner can top up later on his GPU machine.

**Phase 7 — Release hardening.** Installer polish, update check, `docs/INSTALL.md`, accessibility pass, QA on all three OSes, first GitHub Release with checksums. Gate: `gate_release`; v0.1.0 is published as a **pre-release** (never a public full release without the owner), `docs/INSTALL.md` and `docs/V1_SUMMARY.md` written, and the run stops.

---

## 12. Open decisions (record in `docs/DECISIONS.md`)

1. Final model per tier and quantization after `eval_speaking.py` runs on real hardware (Phase 4), within the rule-13 approved list; whether the owner opts in Gemma 3 / Llama 3.x despite their non-permissive terms.
2. Per-language listening play policy and section timings (owner, from public familiarization guides).
3. Whether Whisper `large-v3-turbo` ships inside the installer for tiers C/D or stays a download.
4. Korean voice (see §4 table): OS voice, owner-produced pre-rendered clips, or a self-trained Piper voice.
5. Universal macOS binary vs two DMGs.
6. Portuguese: Brazilian only at launch (assumed) or both variants.
7. Arabic: MSA only (assumed); dialect packs later as separate "languages".

---

## Appendix A — Attribution and licenses to record on day one

Code: llama.cpp, whisper.cpp, ggml (MIT); ICU4J (Unicode); PDFBox (Apache-2); Compose Multiplatform, Kotlin libs, SQLDelight, Ktor (Apache-2); FSRS port (MIT); Kokoro-82M runtime (Apache-2.0, run out-of-process); Piper + espeak-ng (GPL-3, separate executables — ship their source links and license texts; never link). Data: JMdict/KANJIDIC2 (EDRDG CC BY-SA), CC-CEDICT (CC BY-SA 4.0), Wiktionary via kaikki.org (CC BY-SA 3.0), OpenCC (Apache-2), Noto fonts (OFL), ILR descriptors (US Government, public domain), Tatoeba (CC BY 2.0 FR). Models (all with developer/country recorded per rule 13): Phi-4 family (Microsoft, MIT), EuroLLM (UTTER/EU, Apache-2.0), IBM Granite 3.3 (Apache-2.0), Mistral-7B v0.3 / Mistral-Nemo / Mistral-Small-3.2 (Apache-2.0), Whisper weights (OpenAI, MIT), Kokoro voices (Apache-2.0), each Piper voice individually. Content: Mokuhyo contributors CC BY-SA 4.0, AI-drafted and badged until reviewed. "Inspiration only, no content used": Tsumugi's reference apps, DLPT familiarization guides, ACTFL/ILR OPI manuals.
