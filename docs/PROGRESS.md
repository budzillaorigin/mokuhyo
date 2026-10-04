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

## Phase 3 — Reading and Listening (DLPT-style) ✅

**Built**
- Exam engine in `shared/.../exam/`: `Blueprint` (default 60-item, 180-minute full form, 8/10/10/12/10/10 items from
  ILR 0+ to 3, 30/60-minute slices; D-020), `ExamAssembler` (`test` and `practice`, balanced keys,
  no passage repeated across the last 3 forms), `ExamSession` (timer, navigation, flag, per-passage play policy:
  listening plays once on tests, questions appear after the first play), scoring and ILR estimation (`Ilr`), resume
  after a crash (`DbProgressStore`).
- Reading and Listening screens (`desktopApp/.../ui/exam/SkillScreen.kt`): practice by level with feedback and
  explanations, timed tests, results with the unofficial disclaimer, tap-to-define over the dictionary with "Add to
  review" (`PassageText.kt`), reading aids, RTL for ar/fa; listening uses the pre-rendered Ogg Opus clip, else the
  voice service at run time, and a test only draws passages this computer can play.
- "Generate more" (`GeneratePassage`): the local model drafts a passage with the shipped-bank checks; it goes to the
  "Generated on this computer" bank, labelled everywhere and excluded from estimates.
- Content tools: `tools/items/gen_dlpt.py` drafts on the §7.1 server (mistral-small3.2), gpt-oss:20b checks every
  item (answer key re-derived blind, ambiguity), failed drafts are dropped; `packs/build_packs.py` (strict validation
  first) and `packs/render_audio.py` (Piper clips through the app's own voice service).

**Content (all `source = "llm"`, badged, unreviewed except Japanese from Tsumugi)**

| lang | reading passages / items | listening passages / items | listening clips |
|---|---|---|---|
| ja | 69 / 212 | 46 / 138 | OS voice |
| es | 26 / 70 | 27 / 71 | 27 |
| fr | 28 / 75 | 26 / 68 | 26 |
| de | 28 / 76 | 25 / 68 | 25 |
| pt-BR | 28 / 76 | 24 / 62 | 24 |
| ru | 17 / 43 | 25 / 66 | 25 |
| zh-Hans | 25 / 63 | 22 / 58 | OS voice |
| ko | 23 / 59 | 25 / 65 | OS voice |
| ar | 22 / 59 | 26 / 68 | OS voice |
| fa | 24 / 63 | 21 / 54 | 21 |
| id | 22 / 56 | 27 / 71 | OS voice |

Drafted to 5 passages per band (D-019); the checker dropped 2–8 of 30 per bank. Phase 6 tops up toward 12.

**Gate `tools/gates/gate_exam.sh` (macOS arm64): PASS**
- `gen_dlpt.py validate --strict --language all`: 0 errors, 0 warnings in all 22 banks.
- Packs build for all 11 languages. Full Reading and Listening forms assemble with every ILR band present and no
  repeats in every language (35–57 of 60 items; shortfalls are reported per band and close as Phase 6 adds content).
- Timed 60-minute forms in Japanese and Spanish taken end to end by a simulated ILR-2 learner: estimates recorded
  READING 2 / LISTENING 2 in both.

**Run it:** `tools/gates/gate_exam.sh`; app: Reading or Listening in the left rail.

## Phase 4 — Speaking (OPI-style) ✅

**Built**
- `OpiSession` (`shared/.../opi/`): warm-up → level check → probes → role-play → wind-down, 19 interviewer turns in
  test mode and 11 in practice; the working level adapts to answer length against per-language typical word counts;
  the interviewer prompt is ordered for llama.cpp prompt-cache reuse; near-duplicate or echoed questions are rejected
  (character-bigram similarity) and fall back to the scripted bank; a backward `next_phase` is clamped (D-017).
- Rating (`OpiRate`): functions, context/content, accuracy and text type with verbatim evidence quotes, sustained and
  breakdown levels, rationale, next steps; normalised (estimate capped at sustained, invented quotes dropped). No model
  or a failed rating → the learner self-rates against the ILR descriptions. Topic conversation mode (`TopicSession`,
  `GenerateTopics`).
- Speaking screen: interview practice and test, topic conversation, recordings saved per turn (WAV), transcript,
  rating with evidence, unofficial disclaimer; History plays recordings back.
- OPI packs for all 11 languages (`tools/opi/<lang>.json` → `content/packs/<lang>/opi.json`): 80 scripted questions,
  24 role-plays, 99 topics each, drafted by mistral-small3.2 via `tools/opi/draft_opi.py`, `source = "llm"`.
- `eval_speaking.py` (60 prompts per language through the app's own prompts and validators) → `docs/MODELS.md`.

**Gate `tools/gates/gate_speaking.sh` (macOS arm64, Apple M4): PASS**
- Kotlin: sessions complete all five phases in all 11 languages against a mock model and the scripted fallback;
  golden ratings validate 100 %; failure modes rejected (95 tests).
- Eval, Tier B EuroLLM-9B on the §7.1 server: ja 0.79, es 0.76, ar 0.78 (JSON validity 1.00 everywhere; rating
  agreement 0.45 is the weak spot). es/ja latencies in MODELS.md are inflated: that run shared the GPU with drafting.
- Full OPI test through the embedded engine (EuroLLM-9B Q4_K_M on Metal, Piper/OS voice → Whisper small): es and ja
  each 19 turns, rated 1+ with evidence quotes and next steps, 38-turn transcript and 19 recordings saved and verified
  by SHA-256 on reload. About half the interviewer turns fell back to the scripted bank (mostly near-repeat
  questions); quality note for owner QA: EuroLLM tends to stay on one topic the candidate mentioned.

**Run it:** `tools/gates/gate_speaking.sh` (SKIP_EVAL=1 / SKIP_FULL=1 without the server or the 5.6 GB model).

## Phase 5 — Data, backup, report ✅

**Built**
- History (every attempt and conversation, searchable, recordings playable), Home dashboard (ILR estimate per skill
  with trend, next activity, review queue), Review (FSRS queue of missed items and looked-up words).
- `.mokuhyo` bundle (`docs/BUNDLE_FORMAT.md`): MKHY container, optional XChaCha20 encryption in chunks, learner data,
  recordings, settings (learner scope only), generated content; import merges (learner remap, review-item rekey,
  tombstones propagate) and is idempotent.
- PDF progress report (PDFBox, bundled Noto fonts, Arabic shaping and bidi), Report screen.

**Gate `tools/gates/gate_data.sh`: PASS**
- Bundle round trip plain and encrypted is lossless and merge-idempotent; PDF report renders for a fixture learner in
  all 11 languages with no `.notdef` glyphs.
- Cross-OS: CI jobs `fixture bundles` (Linux, macOS, Windows) write bundles and `cross-OS bundle import` imports each
  on the other two OSes — green in CI run 36827787084.

**Run it:** `tools/gates/gate_data.sh`; app: Settings → Backup, Report.

## Phase 6 — Content at scale ✅ (10 passages short of target)

**Built**
- Per-language validators, adapters and renderers (`gen_dlpt.py` ILR bands, `ScriptCheck`, `render_audio.py`), in-app
  "Generate more" for passages and conversation topics, Content Review screen (Settings → About → Developer tools)
  exporting verdicts for `tools/items/review.py ingest`.
- `fill --hours` drafting budget (D-019); the English-question check now accepts quoted target-language words (every
  Russian ILR 2+ draft was being rejected; regression test in `tools/items/test_gen_dlpt.py`).
- `tools/gates/gate_content.sh` + `check_content.py` → `docs/CONTENT_STATUS.md`.

**Content** (drafted by mistral-small3.2 on the §7.1 server, checked by gpt-oss:20b; all `source = "llm"`, badged,
0 human-verified): 1,574 of 1,584 reading/listening passages (12 per ILR band per skill in 7 languages; ru, ko, fa,
id are 1–3 short at ILR 1+–3), 182–226 items per language and skill; 425 Piper listening clips (es, fr, de, pt-BR, ru,
fa: every passage); 80 OPI questions + 24 role-plays + 99 topics in each of the 11 languages. Per-language table and
the commands that close each shortfall: `docs/CONTENT_STATUS.md`.

**Gate `tools/gates/gate_content.sh`: PASS** — strict validation of all 22 banks, packs for all 11 languages, counts
against §5.3 with shortfalls logged; every listening passage has a clip or a run-time voice (OS voice for ja, zh-Hans,
ko, ar, id; none on Linux — known gap, D-013).

**Run it:** `tools/gates/gate_content.sh`; top up: `cd tools && uv run --group content python items/gen_dlpt.py fill --language all --skill both --per-band 12`.

## Phase 7 — Release hardening ✅ — v0.1.0 published as a pre-release

**Built**
- Opt-in update check (off by default, daily, "Check now"; D-018). Accessibility pass: labelled checkbox and radio
  rows, headings, keyboard word lookup in passages. Always-dark theme (D-027; see known issue below).
- Release builds without GitHub Actions (D-025):
  - macOS arm64: `tools/release/build_macos_arm64.sh`, wipes the Compose output first.
  - Intel Mac: `tools/release/build_macos_x64.sh`, an x86_64 cross-build plus jpackage on an x86_64 JDK under
    Rosetta.
  - Windows x64: `tools/release/build_windows_cross.sh`, cross-built on macOS (D-026) — mingw-w64 native CPU +
    Vulkan and Piper, a jlink'd Windows runtime, jpackage's launcher, and a wixl MSI.
  - `tools/release/build_windows.ps1` remains for an MSVC build on Windows.
- No Linux installer (owner decision; `tools/release/build_linux.sh` kept).
- Final content packs `packs-20261002-0213` (`tools/release/packs.lock`).
- `docs/RELEASE_NOTES.md`, `docs/INSTALL.md`, `docs/V1_SUMMARY.md`.

**Release** https://github.com/budzillaorigin/mokuhyo/releases/tag/v0.1.0 — **pre-release**, tag `v0.1.0` at
18e84a0. Assets: `Mokuhyo-0.1.0-windows-x64.msi`, `-windows-x64-portable.zip`, `-macos-arm64.dmg`,
`-macos-x64.dmg`, `SHA256SUMS`.

**Gate `tools/gates/gate_release.sh v0.1.0`: PASS**
- It is a pre-release, not a draft. All installers and `SHA256SUMS` are present, and GitHub's digests match.
- `INSTALL.md` and `RELEASE_NOTES.md` exist.
- macOS fresh-install smokes passed (arm64 natively, Intel under Rosetta): the DMG installs, launches on an empty data
  folder, and finds 11 packs, 11 Piper voices and Whisper small.
- **Windows (owner, 2026-10-02):** the MSI installs and the app works. The first cross-build crashed in Skia
  (missing `icudtl.dat`), which was fixed before release. `MokuhyoConsole.exe --smoke --require-packs` found all
  packs, voices and Whisper.

**Known issues carried into the next update**
- **Windows still shows the light theme** even though the build is dark-only (D-027). The jars inside the Windows
  image contain only the dark scheme, so the cause is still open. First check: whether the reinstall actually replaced
  the files (same version 0.1.0 MSI), then Windows-specific Compose behaviour.
- The Windows build is cross-compiled; the Vulkan path and Piper speech on Windows were not exercised beyond
  start-up.
- See `docs/V1_SUMMARY.md` for the rest: content unreviewed, the Persian voice, OS voices for ja/zh/ko/ar/id,
  interviewer quality, unsigned installers, and the voice-licensing review (D-013).

## v0.1.1 — dark everywhere (2026-10-02) ✅

https://github.com/budzillaorigin/mokuhyo/releases/tag/v0.1.1 — pre-release. The v0.1.0 notes point to it.

**Changes**
- `MokuhyoTheme` paints the dark background and text colour for every screen; in v0.1.0 the first-run flow showed the
  light window colour.
- Dark AWT window background.
- Dark Windows title bar through `mokuhyo_win.dll` (`native/win/dark_titlebar.c`, DWM, D-027).

**Gate `gate_release.sh v0.1.1`: PASS.** The macOS fresh-install smokes (arm64 and Intel) passed. On Windows, the
owner installed it as an in-place upgrade from 0.1.0 and confirmed it is fully dark.

**Also before going public:** the git history was rewritten (2026-10-02):
- Commit emails now use the GitHub privacy address.
- LAN addresses are replaced with placeholders.
- A backup of the old history is at `~/mokuhyo-history-backup-2026-10-02.bundle` on the build Mac.

# Phase 8 — Current military lexicon and the cultural-nuance layer (BRIEF_PHASE8.md, unattended from 2026-10-03)

## C-00 — Adopt the handoff ✅
- Rule 6 replaced with the four-flag wording (D-028/A-09); source access goes through `tools/terms/sources.py`, which
  refuses `us-limited/` for every purpose (D-029).
- `tools/sources/fetch_sources.py` (+ `check.py`), `tools/gates/gate_terms.sh` wired into `gate_core.sh` and CI,
  `docs/LICENSES.md` "Reference sources" (49 rows) enforced by `check_licenses.py`.
- Tests: `tools/terms/test_terms_gates.py` (guard, fetch round-trip with a tampered file, alignment validator, mixed-
  script flattening, a copied AFCLC run fails the overlap check).
- The overlap gate caught two drafted strings in the existing banks; reworded (D-029).

**Gate:** `tools/gates/gate_terms.sh` PASS on the empty `term_alignment.csv` and all existing banks (78,938 strings,
0 failing); `fetch_sources.py --check`: 44 verified, 0 problems, 6 limited rows never touched.

## C-01a — Seed term list ✅ (one criterion is a known gap)
- `tools/terms/build_seed_terms.py` + `seed_catalog.py` + `doctrine.py` (glossary parsers) + `verbatim.py` →
  `tools/terms/seed_terms.csv`: **402 rows**, 154 priority 1 (the whole §B.2.2 starter list), `approvedBy = owner
  (delegated 2026-10-03)`. Definitions: DoD Dictionary Aug 2026 251, ATP 1-02.1 50, ATP 3-01.81 9, DoD Dictionary
  2021/2025 5, JP 3-01 1, original 86 (D-030).
- `tools/terms/validate_seeds.py` (in gate_terms): format, provenance, verbatim-in-source check, starter coverage.

**Gate:** `validate_alignment.py --seeds` passes (seed file found); ≥ 250 rows ✅; every priority-1 starter term ✅;
**≥ 90% doctrinal: known gap** after three distinct attempts (Aug 2026 dictionary + glossaries → 78%; adding the 2025
and 2021 dictionary editions → 79%; verified body-text definitions → 79%; the remaining rows are terms no public US
source defines). Exact command and output:

```
$ cd tools && uv run python terms/validate_seeds.py --min-doctrinal 0.9
validate_seeds: 402 rows, 316 doctrinal (78.6%), 0 errors
validate_seeds: doctrinal share 78.6% is below 90%
```

## C-01 — English term ingestion ✅
- `tools/terms/extract_terms.py` → `tools/terms/terms_en.json` (`mokuhyo-terms-en/1`): per seed term the definition
  of record with its citation, every glossary definition of the term across the public sources and editions, and up
  to three page mentions per source (DoD Dictionary, ATP 3-01.81, JP 3-10, JP 3-01, AFDP 3-10, the two AF doctrine
  advisories, the DoD C-sUAS strategy, ATP 1-02.1); 57 glossary `candidates` not yet in the seed list.

**Gate:** every seed term resolved or flagged — 366 of 402 have ≥ 1 source citation, 36 are `"status": "unsourced"`
(modern tactical terms with original definitions: FPV drone, interceptor drone, lockdown, radio procedure words…).

## C-04 — Lexicon update channel ✅ (lands before C-02/C-03 finish drafting)
The term alignment (C-02) and the track drafting (C-03) run on the §7.1 server for hours, so the app side of the track
and the update channel were built meanwhile and land first; C-02 and C-03 are checkpointed when their content passes.
- **Track in the app (C-03 app side):** `shared/.../lexicon/Track.kt` (terms with both-language definitions, sources,
  kinds, examples, collocations; drills; scenarios; dialogues), a **Lexicon** screen (terms by domain with search and
  badges, meaning / fill-in / register / radio-brevity drills, listening dialogues), a **Scenarios** tab in Speaking
  (briefing + key terms, then a role-play where the partner keeps the scenario role), a topic filter in Reading and
  Listening practice (`track` on passages), review kinds `TERM` and `PRAGMATIC`.
- **Update channel (C-04):** `lexicon-<lang>-<domain>-<semver>.json` packages built and Ed25519-signed by
  `tools/release/lexicon.py` (key D-031), verified in the app against the shipped key; Settings → Content → *Import
  lexicon update* (file or URL); Lexicon → *What's new*; added terms queued to Review; `lexicon_package` /
  `lexicon_term` tables (schema 2, migration `1.sqm`), carried in the `.mokuhyo` bundle. Format:
  `docs/LEXICON_FORMAT.md`.

**Gate:** `LexiconPackageTest` (jvmTest) — Python-signed v1 imports clean against the shipped baseline; v2 with
20 added / 5 changed terms imports with exactly that delta and queues exactly the 20 new terms; re-import is a no-op;
tampered terms, a tampered manifest and an unsigned copy are caught; canonical JSON matches Python's.
`tools/release/test_lexicon.py` passes. Full Kotlin suite (`:shared:allTests :desktopApp:test`) green.

## C-06 — Pragmatic flags ✅
- `topic_turn` returns `pragmatics[] {kind, severity, what, why, better}` (register, face, directness, ritual, taboo);
  the prompt carries the persona and the pragmatics pack's rules. Live conversations show "Cultural notes — not part
  of your level" under the learner's turn ("Say it again" stays).
- OPI debrief: `opi_cultural_review`, a separate call after the rating, shown as **Cultural appropriateness** with
  "Not part of the ILR scale — it does not change your level or rating." Stored with the conversation (D-032).

**Gate:** `PragmaticsGoldenTest` — prompt fields pinned (schema kinds, instructions, persona block text); three fixture
turns in each of the 11 languages produce the expected flag through the gateway and the topic session with the turn
level unchanged; invalid kinds rejected; the OPI rating is one call that never sees cultural notes.

## C-11 — Live / After action / Off corrections and the After Action Brief ✅ (built ahead of C-05…C-10)
- `CorrectionsMode` per session, chosen on the Speaking setup card (defaults per activity in Settings → Speech & audio
  → Speaking: interview practice After action, topic and persona/scenario Live; interview tests always After action).
- After action: the conversation only (mode chip, no corrections, flags or rewrites); every record still goes to
  `conversation_turn_feedback`; the end button reads "End and show After Action Brief". Interview feedback runs in a
  background queue that yields to the interviewer. Off: transcript and recordings only, "no feedback will be
  generated".
- After Action Brief: summary with three next steps, turn-by-turn review with "▶ my audio" / "▶ model version" and
  add-to-review (or queue all), patterns (grammar, register, avoidance, fluency), cultural appropriateness grouped by
  culture-card tag; for interviews the phase map and the rating with evidence above it. Saved on the conversation,
  shown from History, listed in the PDF report. Schema 2 → 3 (`2.sqm`).

**Gate:** `CorrectionsModeTest` (identical records in Live and After action; Off records nothing; the feedback queue
waits for the interviewer and matches a direct run; OPI test always After action; the AAB builds from records with and
without a model), `AfterActionStorageTest` (mode, AAB and turn feedback round-trip through a `.mokuhyo` bundle; the
default-mode setting is learner-scoped), `AfterActionRenderTest` (the AAB renders on fixtures in es, ja, ar). Full
Kotlin suite green.

## C-09 — Current-events reading set ✅
- `tools/terms/feeds.json`: 22 official defense press/news links, at least one per language (ministries and air
  forces; Saudi and Qatari pages for Arabic). Reading → **This month** opens them in the learner's browser and offers
  **Paste text to practice** (tap-to-define, reading aids; the pasted text is not saved). Nothing is fetched or stored.
- `tools/terms/validate_feeds.py` checks the list (schema offline in gate_terms; a live link check in gate_content).

**Gate:** `ThisMonthRenderTest` renders the screen (es, ja, ar); `validate_feeds.py`: 22 links, 0 errors (ROKAF, the
Saudi and Qatari ministries, mil.ru, IRNA and the PRC pages are marked `reachability: unreliable` — they refuse or
geo-block scripted requests — and don't fail the build).

## C-10 — Review surfaces and docs ✅
- Content Review covers terms, culture cards, pragmatics entries, personas, scenarios and dialogues;
  `tools/items/review.py ingest` applies their verdicts (terms → `approved` + `approvedBy`, badge cleared; others →
  `verified`; rejections removed and logged); `tools/items/review_state.py` keeps verdicts across rebuilds.
- `docs/CONTENT_PACKS.md` documents every Phase 8 pack file and the term pipeline files; `docs/LICENSES.md` has a row
  for every source (C-00) and the build tools added in Phase 8.

**Gate:** `tools/items/test_review_ingest.py` round-trip on fixtures for every new kind (accept, reject, carried state).

## C-02 — Allied term alignment ✅ (one criterion is a known gap)
- `tools/terms/align_terms.py` (propose → confirm → retrieve → draft → check → overlap → write) wrote
  `tools/terms/term_alignment.csv`: 4,315 rows over 11 languages (371–402 per language), each with term, kind,
  `term_source_id` + `term_source_page` where documented, a learner definition drafted from the US definition and
  checked by the checker model; badges `unreviewed` / `unconfirmed-term` (D-033). 1,161 rows queued for human look-up
  in `tools/terms/lookup_queue.csv` (de, id, zh-Hans).

**Gate:** `validate_alignment.py`: 4,315 rows, 0 errors ✅; `gate_terms.sh` PASS — `overlap_check.py` 88,735 text fields,
0 failing ✅ (five French definitions too close to AAP-06 were redrafted naming the shared run; regression test
`test_write_withholds_overlap_failures`). **≥ 70% documented equivalents per language with a source: known gap** after
three distinct attempts (D-033). **Correction (same day):** a review of the track text found that many retrieved terms
were related entries, not the concept (e.g. "point repère" for entry control point). A checker-model verification of
every cited term (`align_terms.py verify`) rejected 361 citations; those rows keep the model's term with
`unconfirmed-term` and a note naming the rejected term. The figures below are after verification:

| lang | exact search | + retrieval / parallel edition | after verification |
|---|---|---|---|
| fr | 36.7% | 60.9% | 28.6% |
| pt-BR | 20.8% | 63.8% | 25.1% |
| ja | 19.6% | 31.1% | 16.4% |
| ko | 12.9% | 16.3% | 11.9% |
| de, id, zh-Hans | 0% (sources human-review-only) | queued for look-up | — |

```
$ cd tools && uv run python terms/align_terms.py verify --language ja,ko,fr,pt-BR
  ja: 61 of 130 rejected · ko: 18 of 69 rejected · fr: 131 of 248 rejected · pt-BR: 151 of 249 rejected
  ja        402 rows · documented   66 ( 16.4%) · checked  397
  ko        387 rows · documented   46 ( 11.9%) · checked  381
  fr        402 rows · documented  115 ( 28.6%) · checked  399
  pt-BR     387 rows · documented   97 ( 25.1%) · checked  385
```
The public allied sources (white papers, one Brazilian glossary, AAP-06) don't name most counter-UAS and base-defense
terms; the remaining terms need the human look-up queue or new sources.

## C-05 — Culture cards ✅
- `tools/culture/build_cards.py` → `tools/culture/<lang>.cards.json`: 24 cards per language (26 for Arabic: two
  Qatar-specific), each tagged with the scenario/persona vocabulary (rank, meeting, gate, radio, hospitality, face,
  time, refusal…), with title, body, do / avoid lists and the AFCLC Expeditionary Culture Field Guide section and page
  it draws on. France and Germany have no field guide, and neither do the Qatar cards: those cards say "general
  knowledge" and are badged. All AI-drafted, `verified = false` until reviewed in Content Review.
- `tools/gates/check_culture.py` (new, in `gate_content.sh`) validates cards, pragmatics and personas.

**Gate:** `check_culture.py --only cards`: 11 languages, 0 errors (every one of the 12 scenarios has ≥ 1 card in every
language; every card cites a field-guide section and page or says general knowledge); `gate_terms.sh` PASS —
overlap_check over 92,378 text fields including all cards, 0 failing (no sentence copied from the guides).
- Fixes found on the way, each with a regression test in `tools/terms/test_terms_gates.py`: `overlap_check.py` now
  writes its JSON report even when there is no text (it crashed the pragmatics build); the official French force name
  is on the overlap allow-list (a proper name, not copied prose).

## C-03 — Counter-UAS and base-defense track ✅ (small shortfalls logged)
- `tools/tracks/build_track.py` → `tools/tracks/cuas-base-defense.<lang>.json` → `content/packs/<lang>/track-cuas-base-defense.json`:
  371–402 terms per language with the aligned term (C-02), learner definition, two example sentences, collocations
  and register note; 361–671 drills (meaning, fill-in, register, radio brevity); 12 scenarios with partner openers
  (all 12 in every language except Arabic, 11); 8 listening dialogues (Persian 7) rendered as audio where a voice is
  bundled; 12 OPI probes per language (Arabic 10) merged into `tools/opi/<lang>.json` as `<lang>-cuas-probe-NN`.
- Track passages (`gen_dlpt.py fill --track cuas-base-defense`): reading 6 and listening 4 per band at ILR 1, 2, 3.
- Every example, collocation, drill and dialogue passes `overlap_check.py` before the track file is written
  (`scrub`; 4 items dropped). Official AAP-06 / MD35-G-01 designations used as terms are on the overlap allow-list.
- Fixes on the way, each with a regression test (`tools/tracks/test_build_track.py`, `tools/terms/test_terms_gates.py`):
  OPI probes were all dropped because the model writes "ILR 2"; openers/dialogues now retry and keep partial results;
  examples are redrafted when the aligned term changes; allow-listed designations match with an attached "l'"/"da".

**Gate:** `gate_content.sh` PASS (strict validation 0 errors, packs, counts, feeds, culture); `gate_terms.sh` PASS
(222,738 text fields, 0 failing). Shortfalls logged (the gate allows them with the draft command):

| lang | band | have / want | close with |
|---|---|---|---|
| ru | track listening | 11 / 12 passages | `cd tools && uv run --group content python items/gen_dlpt.py fill --language ru --skill listening --track cuas-base-defense` |
| fa | track reading | 17 / 18 passages | `cd tools && uv run --group content python items/gen_dlpt.py fill --language fa --skill reading --track cuas-base-defense` |

## C-07 — Pragmatics pack and inference items ✅ (small shortfalls logged)
- `tools/pragmatics/<lang>.json` → `packs/<lang>/pragmatics.json`: the seven topics (address and rank, refusals,
  apology and thanks, small talk, disagreement, hospitality, gestures and silence), 19–21 entries per language, each
  with a rule and examples (say / don't say / why), citing the field-guide section where one exists.
- `build_pragmatics.py` strips English glosses from example lines and drops examples that read as English
  (`langtext.reads_as_english`, the Python mirror of the app's `ScriptCheck`); `check_culture.py` enforces it.
  The app's Spanish function-word list gained "a" (valid Spanish like "¿Puedo ayudar a limpiar?" was read as English).
- Inference items: `gen_dlpt.py fill --track pragmatics` — listening "implied meaning" passages at ILR 2 and 2+.
- The OPI interviewer and topic partner carry the pack's norms (`AppGraph.culturalNotes` → `OpiSession`,
  `TopicSession`); `PragmaticsGoldenTest` pins it.

**Gate:** `check_culture.py` 0 errors; `RealCulturePacksTest` (built packs, `MOKUHYO_REQUIRE_PACKS=1`) 3/3; items pass
the strict exam validator in `gate_content`. Inference items: 24 in ja, es, fr, de, pt-BR, ru, id; **21 of 24** in
zh-Hans, ko, ar and fa after the top-up rounds — close with
`cd tools && uv run --group content python items/gen_dlpt.py fill --language zh-Hans,ko,ar,fa --skill listening --track pragmatics`.

## C-08 — Partner personas ✅
- `tools/personas/<lang>.json` → `packs/<lang>/personas.json`: six roles per partner force (senior counterpart, peer
  officer, junior enlisted, interpreter, local contractor, civilian official) — twelve for Arabic (RSAF and QEAF) —
  each with name, rank title, register, patience, formality, a greeting in the language, and links to the pragmatics
  entries and culture card it draws on. Persona picker in Speaking (C-04/C-11 app side).

**Gate:** `RealCulturePacksTest.personaPromptsHaveTheGoldenShape` (each persona's system-prompt block has the golden
shape; greetings in the language) and `PragmaticsGoldenTest` pass; `check_culture.py` 0 errors. Full Kotlin suite
(`:shared:allTests :desktopApp:test`, packs required) green.

## C-12 — v0.2.0 pre-release ✅

https://github.com/budzillaorigin/mokuhyo/releases/tag/v0.2.0 — **pre-release**, tag `v0.2.0` at 0166e87.
Assets: `Mokuhyo-0.2.0-windows-x64.msi`, `-windows-x64-portable.zip`, `-macos-arm64.dmg`, `-macos-x64.dmg`,
`SHA256SUMS`. The packs bundled in the installers are the Phase 8 packs built from `content/packs/` at that commit
(track, culture cards, pragmatics, personas, feeds), with audio rendered for es, fr, de, pt-BR, ru and fa.

**Gate `tools/gates/gate_release.sh v0.2.0`: PASS.** It is a pre-release (not a draft); all installers and
`SHA256SUMS` are present and GitHub's digests match. The macOS fresh-install smokes passed: arm64 natively and Intel
under Rosetta. `gate_core` and `gate_content` passed at the release commit. Summary: `docs/PHASE8_SUMMARY.md`.

**Pending (owner):** the Windows install check, as for v0.1.x. The MSI was cross-built on macOS and has not been
installed on Windows yet.

**Build note:** the first Intel build failed with "No space left on device". The owner freed space by deleting
regenerable build output. Builds now run one at a time, with intermediates removed between them. The Windows
cross-build needs the macOS app image in `desktopApp/build`, so that image has to be kept or rebuilt
(`./gradlew :desktopApp:createDistributable`).
# Phase 9 — Follow-on (BRIEF_PHASE8 Part D; built on branch `phase9` while Phase 8 content drafted, merged after v0.2.0)

## N-02 — Numbers under stress ✅
- `NumberGrammar` on every `LanguageModule` (ICU4J spell-out + per-language time/date glue: `IcuNumbers`), spelling
  alphabets (NATO; Japanese katakana NATO and 和文通話表; German DIN 5009; Russian), `NumberItems` (times, dates, MGRS
  grids, bearing/range, call signs, tail numbers, phone numbers, frequencies, counts, spelling) and an adaptive
  `NumberDrill`. Listening → **Numbers**: play, type, adaptive on the kinds you miss. No model, no network.

**Gate:** `NumbersTest` — cardinal, ordinal, time and date forms pinned for all 11 languages, 12-hour forms, a 500-item
generated set per language validates (unique ids, every kind, each item accepts its own answer), the drill adapts.

## N-06 — Degraded audio ✅
- `Degrade` (16 kHz PCM): RBJ band-pass, synthesized noise beds (pink, radio static, turbine, mains hum, babble,
  engine) mixed at a target SNR (difficulty 0–1 → +20…0 dB), tanh compression, radio clipping, optional cross-talk.
  Listening practice shows Conditions (Telephone, VHF/UHF radio, Flightline, Generator room, Crowd, Vehicle interior),
  a difficulty slider and "Replay clean"; tests never show them and `Degrade.apply(testMode = true)` returns the clean
  audio. Noise is labelled "synthesized" (no owner recordings were supplied — logged input gap).

**Gate:** `DegradeTest` — band-pass gains, SNR exact to 0.01 dB, deterministic and length-preserving per preset, harder
is noisier, test mode is always clean, cross-talk mixes in.

## N-09 — Doctrine refresh ✅
- `tools/terms/refresh.py` diffs a new glossary edition against `terms_en.json` → changed, deprecated, candidates;
  `docs/LEXICON_MAINTENANCE.md` sets the quarterly procedure and reminder.

**Gate:** `tools/terms/test_refresh.py` on two fixture editions (1 changed, 1 deprecated, 1 candidate; CLI too).

## N-10 — Suggest a term / flag this item ✅
- `SuggestionStore` (`<data dir>/suggestions.json`): Lexicon → Suggest a term and Flag on each term, Flag on exam
  feedback and on After Action Brief turns; carried in the `.mokuhyo` bundle (merged by id); Settings → Content →
  Export suggestions; `tools/items/review.py suggestions <file>` queues them for the curator.

**Gate:** `SuggestionsTest` (bundle round-trip, idempotent merge, standalone export validates, bad input refused);
`test_review_ingest.py::test_suggestions_queue`.

## N-11 — SBOM and no-network install profile ✅
- **`--no-network`** (or `MOKUHYO_NO_NETWORK=1`): `NetworkPolicy` plus a client plugin in `mokuhyoHttpClient` (the only
  way the app makes HTTP clients) refuse every request before it reaches the network; Settings → Privacy says so and
  the This month links are disabled.
- **Air-gapped install:** Settings → AI → *Install a model from a file (no network)* — `ModelManager.installFromFile`
  checks size and SHA-256 against the catalogue before installing.
- **SBOM:** CycloneDX Gradle plugin (`:desktopApp:cyclonedxDirectBom`, CycloneDX 1.6, runtime classpath); the macOS build
  produces it, `collect.py` names it `Mokuhyo-<version>-sbom.cdx.json`, and `gate_release` requires it from v0.3.0.
- `docs/SECURITY_PROFILE.md`: network behaviour, data locations, signing status, provenance rules, air-gapped steps.

**Gate:** `NoNetworkTest` (requests fail closed — the engine is never reached; the update check reports a failure);
`ModelManagerTest.sideLoadsAVerifiedFileWithoutNetwork`; SBOM generated (155 components). The SBOM-on-release part of
the gate is checked by `gate_release` at v0.3.0 (N-14).

## N-12 — Daily stand-to ✅
- `StandToPlanner` builds an 8–10 minute recipe: a numbers set, lexicon items (due terms first, else priority-1 track
  terms), one listening clip at the learner's band (nearest band with unseen clips), and one speaking turn whose
  feedback comes at the end — only with a model; without one the time goes to more numbers and terms.
- Home → **Daily stand-to** (one tap) runs the steps, saves to the `stand_to` log (schema 3 → 4, `3.sqm`, carried in the
  bundle) and shows this week's summary on the card.

**Gate:** `StandToTest` — full recipe with a model, no speaking turn without one, nearest band and no-clip fallbacks all
stay within 8–10 minutes, weekly summary arithmetic; migration verified.

## N-13 — Side-by-side terms ✅
- `SideBySide.rows` matches terms across the enabled languages by seed id; Lexicon → **Compare languages** shows English
  → each language with its kind, the radio note, source confirmation or badge, and ▶ audio per language; domain
  filter and search.

**Gate:** `SideBySideTest` (row snapshots for 1, 2 and 3 languages, domain filter and search) and
`SideBySideRenderTest` (the screen renders for 1, 2 and 3 languages).

## N-01 — Consecutive interpretation drill ✅
- Speaking → **Interpret**: track dialogue lines chunked into 1–3 sentences (`Chunker`), played in the target language or
  English, a configurable note-taking pause (0–20 s) with a notes box, the rendering by voice (Whisper in the output
  language) or typed; `grade_interpretation` scores accuracy, completeness and register (0–5), lists omissions and
  distortions and gives a better version. Presented After-action (feedback at the end). Variants: **radio relay**
  (radio-sounding dialogues through the N-06 radio channel) and **sight translation** (a track notice on screen for 45 s).
- Saved as conversation kind `INTERPRET` (results in the stored record, so the `.mokuhyo` bundle carries them);
  History shows the results; the PDF report lists the session score.

**Gate:** `InterpretTest` — chunking pinned (joining short lines, splitting long ones, both directions, unspaced
scripts), grader JSON validates on fixtures (and bad scores are rejected), scripted sessions complete in Spanish and
Japanese with nothing graded until the end, live grading when asked.

## N-03 — Exercise-week storyline ✅
- Five linked sessions (`Storyline.DAYS`: arrival and handover → drone sighting → intrusion and QRF → incident with a
  local national → joint after-action review) with one counterpart chosen from the language's personas. After each day
  `storyline_summary` stores what the counterpart remembers (facts, a two-sentence summary) and how the learner
  handled it (the branch key); the next day's situation branches on it and the counterpart's prompt carries the memory.
  Speaking → Scenarios → **Exercise week**. Culture cards and personas apply as in scenarios.
- Append-only `storyline` / `storyline_day` rows (schema 4 → 5, `4.sqm`), carried in the bundle; the state is derived.

**Gate:** `StorylineTest` — a scripted five-session run completes with branches following the choices; the day-5 prompt
recalls the facts of days 1–4 and day 1 recalls none (golden); an invalid summary falls back; the state round-trips
through a `.mokuhyo` bundle.

## N-07 — Speaker variety ✅ (with logged gaps)
- `voices/manifest.json`: 19 Piper voices (was 11) — es 4 (2 F incl. es-MX, 2 M), fr 4 (2 F, 2 M), de 4 (2 F, 2 M),
  pt-BR 3 (incl. pt-PT), ru 2, fa 2. Every entry records developer, country, licenses, dataset, base model, `region`
  and `measuredF0Hz` (D-040). Multi-speaker models serve several entries through `model`.
- `VoiceRotation` rotates listening passages by id, gives distinct speakers distinct voices, and fixes one voice per
  persona; used by pack rendering (`--render-audio`), live passage audio and persona speech.

**Gate:** `manifest.py check` 0 problems; `check_licenses` / `check_provenance` 0 problems; `VoiceRotationTest`
(4) and `VoiceCatalogTest` pass; `PiperEngineTest.dialoguesRenderWithDistinctVoices` renders a two-speaker dialogue
with two different voices in es, fr, de, pt-BR, ru and fa (real Piper, real voices).

**Known gaps (logged, not blocking):**
- No verified female voice for **ru** (irina: no license; ruslan: non-commercial), **fa** (all four candidates measure
  male) or **pt-BR** (faber 179 Hz and tugão 179 Hz are ambiguous). Dialogues there use two distinct voices whose
  gender may not match the script.
- **ja, ko, zh-Hans, ar, id** have no redistributable Piper voice (D-013); they keep the OS voice, so variety depends on
  the voices the user's OS has installed.
