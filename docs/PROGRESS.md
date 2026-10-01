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

**Gate:** `tools/gates/gate_core.sh` — PASS on macOS (arm64).

**Deferred:** language-neutral prompts (`HOUSE_RULES` still Japanese-teacher wording; Phase 4), LocalLlamaModel's
ChatML formatting (Phase 1 switches to the GGUF's own chat template), `Validation` Japanese helpers (Phase 2/4).

**Run it:** `tools/gates/gate_core.sh`
