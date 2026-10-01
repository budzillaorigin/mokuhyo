# Progress

Unattended run of BRIEF §11 Phases 0–7 (owner instruction, 2026-09-30). Each phase ends with its automated gate,
a checkpoint here, a commit and a push. Tsumugi's progress log is in `docs/history/TSUMUGI_PROGRESS.md`.

## Phase 0 — Fork hygiene ✅

**Built**
- New `CLAUDE.md` (BRIEF §1), Apache-2.0 `LICENSE`, README, rewritten `docs/LICENSES.md` (machine-checked tables).
- Gradle: root project `mokuhyo`; `shared` is KMP with a single `jvm()` target (JDK 21 toolchain); new `desktopApp`
  module (placeholder `main`, replaced by Compose Desktop in Phase 1). Android, iOS, SKIE and the server are gone.
  Runtime classpaths are dependency-locked (`*/gradle.lockfile`).
- Packages renamed `app.tsumugi` → `app.mokuhyo`; Japanese code moved to `app.mokuhyo.lang.ja`.
- `tools/gates/gate_core.sh` + `check_licenses.py` (lockfile ↔ LICENSES.md, GPL refusal, manifest ids, packaged-image
  jar scan) + `check_provenance.py` (rule-13 denylist + provenance fields). `tools/run_tests.py` runs every Python test.
- Drafting endpoint configured: `tools/.env.example` (D-001), `tools/models/approved_models.json` (§7.1 list).
- `ci.yml`: gate_core on Linux for PRs, on Linux/macOS/Windows for pushes to main.
- Rule 13 clean-up: Qwen entries removed from `content/models/manifest.json` (now empty, v2 with provenance; tiers
  arrive in Phase 1) and from tests, comments and tool examples.

**Removed modules** (git history keeps them): `reader`, `media`, `tracks`, `courses`, `onomatopoeia`, `thesaurus`,
`translation` (its `GradeTranslation` prompt kept), `literature`/`poetry`, `lyrics`, `sync`, `integrations`, `study`,
`kanji`, `kana` UI drills, `writing`/strokes, `coverage`, `decks`, `cards`, `immersion`, `grammar`, `practice`
(OPI bank models kept as `opi/OpiBank.kt`), `recordings`, `review`, `export`, `api`, `audio`, `settings`, `content`,
`l10n`, `domain`, `pitch` UI, `speaking` services, `speech` (PronunciationAnalyzer/Yin/MoraAlignment), JMdict-specific
`dictionary` (rebuilt language-neutral in Phase 2), JLPT (blueprints, scoring, generated/authored banks), upper-range
DLPT banks and tests, 9 of 10 SQLDelight databases, `server/`, `iosApp/`, `androidApp/`; tools for kanji, grammar,
tracks, literature, translation, phonetics, onomatopoeia, thesaurus, practice, decks, icons, archive validation.

**Kept tests** (`./gradlew :shared:allTests`, 189 tests, all green): `ai/` AiGatewayTest, EndpointEnginesTest,
JsonSchemaTest, LocalEnginesTest, ModelManagerTest, PromptGoldenTest, Sha256Test, ValidationTest; `exam/`
IlrEstimatorTest, ExamSessionTest (rewritten DLPT-only); `opi/` OpiSessionTest, OpiProbeMapTest; `srs/` FsrsTest,
FsrsOptimizerTest, AnswerCheckerTest; `lang/ja/` ConjugatorTest, DeinflectorTest, FuriganaTest, KanaConversionTest,
MoraTest, PitchTest, LatticeTokenizerTest; `backup/` CryptoTest. Python: test_gen_dlpt (20), test_review_ingest (3).

**Gate:** `tools/gates/gate_core.sh` — PASS on macOS (arm64); CI run 36814872372 green on Linux, macOS and Windows.

**Deferred:** language-neutral prompts (`HOUSE_RULES` still Japanese-teacher wording; Phase 4), LocalLlamaModel's
ChatML formatting (Phase 1 switches to the GGUF's own chat template), `Validation` Japanese helpers (Phase 2/4).

**Run it:** `tools/gates/gate_core.sh`

## Phase 1 — Desktop shell and AI runtime ✅

**Built**
- `desktopApp`: Compose Multiplatform for Desktop. Left rail (Home · Reading · Listening · Speaking · Review · History ·
  Report · Settings), title bar with the language switcher and a model-download progress pill, first-run flow
  (welcome → languages → hardware scan → tier choice, skippable "Reading/Listening only for now" → microphone level,
  record/play-back and test voice → Home), Settings (Languages, AI engine/tier/Ollama, Speech & audio, Privacy &
  updates, About & licenses rendering `docs/LICENSES.md`). Screens of later phases show honest empty states.
- AI runtime (`native/`, `shared/src/jvmMain/.../ai/jni/`): llama.cpp b11040 + whisper.cpp b5130 in one JNI library
  per variant (cpu; metal on macOS; vulkan on Windows/Linux), GPU-first with automatic CPU fallback, model chat
  templates applied natively, GBNF grammars, stop strings, cancellation, device listing for the hardware probe (D-011).
- Model catalog (`content/models/manifest.json`, `docs/MODELS.md` generated): tiers A–D defaults + alternates, Whisper
  small (bundled) and large-v3-turbo (download), each with SHA-256 and provenance (D-007). `TierAdvisor` (D-009),
  hardware probe (native devices, OS fallbacks), resumable hash-verified downloads (`DownloadCenter`), `OllamaDetector`
  (localhost only, rule-13/rule-6 screening).
- Learner database (SQLDelight `MokuhyoDatabase`, BRIEF §8.1): learner, attempt, conversation, recording,
  ilr_estimate, exam_in_progress, review_item/card/review, generated_passage, setting (device vs learner scope);
  tombstones, never physical deletes.
- Audio (`javax.sound`): 16 kHz mono capture with level meter, playback with speed, device lists, WAV conversion. OS
  voice fallback (macOS `say`, Windows SAPI) until Phase 2's voice service.
- Packaging: Compose `nativeDistributions` (jpackage + jlink runtime) → DMG / MSI / DEB; `tools/release/stage_resources.py`
  stages native libraries and bundled weights; app icon (`tools/assets/render_icon.py`).
- Gates: `tools/gates/gate_build.sh` (native cpu build → stage → package → packaged `--smoke` → license scan of the
  image) and `tools/gates/smoke_opi.sh` (packaged `--smoke-opi`). CI runs gate_build on Linux/macOS/Windows on push.
- Content pipeline groundwork (used from Phase 2 on): `tools/llm.py` (§7.1 endpoint, approved-model check, timeouts,
  retries, fallback, curl transport for macOS Local Network privacy), `tools/langtext.py` (wordfreq measures for all 11
  languages), multi-language `tools/items/gen_dlpt.py` (validate/draft/check/merge/fill/status), per-language ILR bands;
  Tsumugi's Japanese banks migrated to `tools/items/bank/ja/` (69 reading / 46 listening passages, strict-valid).

**Gate results (macOS arm64, Apple M4 16 GB)**
- `gate_core`: PASS (Kotlin 200+ tests incl. 9 native JNI tests on Metal and CPU; Python 10 + 3 tests; licenses;
  provenance).
- `gate_build`: PASS — `Mokuhyo-1.1.0.dmg` (600 MB incl. Whisper small; the 1.1.0 is the macOS bundle version, D-008),
  packaged `--smoke`: DB opened, CPU library loaded, 11 languages, offscreen frame rendered.
- `smoke_opi.sh` (packaged app, Tier A Phi-4-mini Q4_K_M from `tools/.cache/models`): Metal — first interviewer turn
  3.8 s incl. model load, Whisper small transcript of the OS-voice answer 1.3 s with 95 % character overlap, model
  follow-up 3.4 s. CPU (`--cpu`) — 9.5 s / 2.0 s / 10.7 s. OK on both.
- CI run 36818087433: gate_core and gate_build green on Linux, macOS and Windows (after fixing the Linux launcher
  path and letting CMake pick the installed Visual Studio).

**Deferred / known gaps:** the Vulkan variant and `native/build.ps1` are only exercised by CI; the per-language quality
badge reads `docs/MODELS.md` "Speaking eval" (filled in Phase 4); voices beyond the OS voice arrive in Phase 2;
Settings → "Check for updates" is wired in Phase 7.

**Run it:** `./gradlew :desktopApp:run` · `tools/gates/gate_build.sh` · `tools/gates/smoke_opi.sh`

## Phase 2 — Language layer ✅ (one known gap)

**Built**
- `LanguageModule` contract (`shared/.../lang/LanguageModule.kt`) with `DictionaryPack`, `SpeechOutput`, `ReadingAids`,
  `ScriptInfo`, `VoiceSpec`; `LanguageRegistry` builds all 11 modules from installed packs.
- Segmentation: ICU4J word break (dictionary-based for zh); Japanese uses the lattice tokenizer over mecab-ipadic
  (`content/packs/ja/tokenizer.sqlite`, 25 MB) for dictionary forms and furigana; Korean particle/ending stripping.
- `Fold.forCompare` per language (D-015); `ScriptCheck` (target-language purity, foreign-script leaks, Latin-script
  function-word test) used by every AI prompt validator.
- Dictionaries for all 11 languages (D-014): language-neutral schema, exact → lemma → fuzzy → prefix lookup, mean
  ~2 ms, p95 ≤ 3.2 ms; built packs: ja 219k entries (40 MB), zh-Hans 124k (23.5 MB), es/fr/de/pt-BR/ru 40k each
  (12–33 MB), ko 38k, ar 27k, fa 17k, id 36k.
- Voice service (D-012, D-013): Piper 2023.11.14-2 built from source as a separate GPL executable with a JSON-lines
  protocol, 11 redistributable voices (es, fr, de, pt-BR, ru, fa), OS-voice fallback by locale (macOS `say`,
  Windows SAPI), null when nothing can speak a language.
- Reading aids: furigana (ja), pinyin + Traditional toggle (zh), romanization (ru, ko, ar, fa, ja), Arabic short-vowel
  toggle. RTL passages for ar/fa. Bundled Noto fonts (D-016) with a glyph-coverage test.
- `docs/LANGUAGES.md` (per-language table, how to add a 12th language).

**Gate `tools/gates/gate_lang.sh` (macOS arm64)**
- Contract tests with real packs (MOKUHYO_REQUIRE_PACKS=1): segmentation/offsets, folding fixtures, lemmas, ja lattice
  lemma + furigana, reading aids, 20 known-word dictionary hits × 11 languages, font coverage — PASS.
- TTS → Whisper small round trip (≥ 60 % token match): ja 82 % (OS Kyoko), es 93 %, fr 79 %, de 75 %, pt-BR 92 %,
  ru 82 % (Piper), zh-Hans 92 % (OS Tingting), ko 78 % (OS Yuna, open decision 4 fallback), ar 100 % (OS Majed),
  id 78 % (OS Damayanti) — PASS; **fa FAIL (known gap)**.
- **Known gap — Persian TTS intelligibility.** Command: `tools/gates/gate_lang.sh` (or
  `./gradlew :desktopApp:run --args="--smoke-lang --whisper $PWD/tools/.cache/models/ggml-small.bin --languages fa"`).
  Output: `smoke-lang: fa FAIL voice=piper:fa_IR-amir-medium match=50% "جلسه فردا سات ده در سالون طبقه سی وومبر بزار میشد."`
  Three attempts: (1) voice fa_IR-ganji: 20–40 %; (2) Whisper large-v3-turbo: amir 30 %, ganji 50 %; (3) Persian
  می + space/ZWNJ normalisation: still 20–50 %. Cause: espeak-ng's Persian phonemisation mispronounces words
  (جلسه → "kalase", طبقه → "tabaqiye"); Whisper hears what was said. Persian listening still plays with the Piper
  voice, labelled as an imperfect synthetic voice; a better Persian voice is an owner follow-up.
- `gate_core`: PASS (258 Kotlin tests, Python tests incl. 15 dictionary-builder tests).

**Run it:** `tools/gates/gate_lang.sh` (builds packs and voices' prerequisites: `voices/build.sh`,
`python3 tools/voices/manifest.py --fetch`)
