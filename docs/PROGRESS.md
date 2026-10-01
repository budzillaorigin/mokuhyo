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
- Windows and Linux gate_build: CI (see below).

**Deferred / known gaps:** the Vulkan variant and `native/build.ps1` are only exercised by CI; the per-language quality
badge reads `docs/MODELS.md` "Speaking eval" (filled in Phase 4); voices beyond the OS voice arrive in Phase 2;
Settings → "Check for updates" is wired in Phase 7.

**Run it:** `./gradlew :desktopApp:run` · `tools/gates/gate_build.sh` · `tools/gates/smoke_opi.sh`
