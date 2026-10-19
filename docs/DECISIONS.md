# Decisions

Mokuhyo decisions, newest last. Tsumugi's decision log (D-001…) is kept in `docs/history/TSUMUGI_DECISIONS.md`;
Mokuhyo numbering restarts at D-001. Entries marked *(unattended default)* were taken during the unattended
Phase 0–7 run (CLAUDE.md "Unattended run") without owner review.

## D-001 Drafting endpoint address (unattended default)
The launch message carried the literal placeholder `<5090-ip>`. The owner's §7.1 Ollama server was found by probing
port 11434 on the hosts already in this Mac's ARP cache (no subnet sweep): `<server-ip>` serves exactly the six §7.1
tags (`mistral-small3.2:24b-instruct-2506-q8_0`, `gpt-oss:20b`, `mistral-nemo:12b`, `phi4:14b`, `granite3.3:8b`,
`phi4-mini:3.8b`) and answered a test completion. `tools/.env.example` sets `LLM_ENDPOINT=http://<server-ip>:11434/v1`.
If the DHCP reservation changes, edit `tools/.env` (git-ignored) — no code change.

## D-002 Phase 0 pruning: rewrite-in-place, not a fresh tree (unattended default)
Kept Tsumugi modules were moved (`git mv`, history preserved) into `app.mokuhyo.*`: `ai/` (gateway, local/endpoint
models, model manager, speech engines, validation, the OPI/role-play/free-talk/correction prompts), `exam/`
(models, assembler, session, bank validator, ILR estimator), `opi/` (session, probe map, bank models), `srs/`
(FSRS, optimizer, answer checker), `lang/ja/` (tokenizer, deinflector, conjugator, kana, furigana, pitch, mora),
`backup/crypto/` (Argon2id, BLAKE2b, XChaCha20-Poly1305), `net/Http.kt`. Everything else (reader, media, tracks,
courses, onomatopoeia, thesaurus, literature, sync, integrations, study, kanji path, writing, games, JLPT, the
upper-range DLPT banks and tests, server, iOS and Android apps) was removed. The JMdict-specific `dictionary/`
module was removed too: Phase 2 replaces it with the language-neutral schema (BRIEF §5.2); `tools/packs/build_dictionary.py`
stays as the starting point of the `ja` adapter.

## D-003 Lock only the shipping classpaths (unattended default)
`gate_core` requires a LICENSES.md row for "every dependency in the Gradle lockfile". Locking every configuration
would put compiler and test-only artifacts in the lockfile; only `runtimeClasspath`/`jvmRuntimeClasspath` (what ends
up in the jlink image) are locked. Rows may use glob patterns (`io.ktor:*`). Build tooling is listed separately.

## D-004 JVM-only KMP target with expect/actual kept
`shared` declares only `jvm()`. `commonMain` keeps `expect` declarations (NFC normalization, free space) with JVM
actuals in `jvmMain`, so an Android or iOS target could return without moving code (BRIEF §3.1).

## D-005 CI cost (unattended default)
The account's Actions minutes ran out under Tsumugi (its D-entry of 2026-09-18 paused automatic runs). CLAUDE.md
rule 11 now requires three-OS CI on every push to main, so `ci.yml` runs on push again. To keep cost bounded the
unattended run pushes once per phase (plus fixes), not per commit. If GitHub refuses the runs for billing, the gates
are run locally on macOS and the Windows/Linux part is recorded as a known gap in PROGRESS.

## D-006 Chat templates come from the GGUF (unattended default)
Tsumugi formatted prompts as Qwen ChatML in Kotlin. The tier models use different templates (Phi-4, Mistral v7,
EuroLLM ChatML, Granite), so `LocalLlmBridge.generate` now takes the message list and the native side applies the
model's own `tokenizer.chat_template` via `llama_chat_apply_template`, falling back to ChatML.

## D-007 Model catalog: bartowski Q4_K_M conversions, verified hashes (unattended default)
Tier weights are the Q4_K_M GGUF conversions published by bartowski on Hugging Face (llama.cpp imatrix quants; the
conversion is recorded in each entry's `provenance.conversion`, the developer/license are the original model's).
SHA-256 and sizes were read from the Hugging Face LFS metadata on 2026-09-30. Licenses were checked on each model
card: Phi-4-mini (MIT), Granite 3.3 2B/8B (Apache-2.0), EuroLLM-9B-Instruct (Apache-2.0; gated original, ungated
GGUF), Mistral-7B v0.3 / Nemo / Small 3.2 (Apache-2.0), Phi-4 (MIT), Whisper (MIT). Context is 8192 tokens for every
tier. Whisper `small` is bundled (`"bundled": true`), `large-v3-turbo` stays a Settings download (open decision 3,
default: download).

## D-008 macOS bundle version (unattended default)
macOS refuses a CFBundleShortVersionString starting with 0, so while the product is 0.y.z the macOS bundle carries
1.y.z; the app shows the real version from the `mokuhyo.version` system property set by the launcher.

## D-009 Tier thresholds and Ollama screening (unattended default)
`TierAdvisor` implements BRIEF §6.1's "typical hardware" column: A ≥ 8 GB RAM; B ≥ 16 GB RAM or ≥ 6 GB VRAM;
C ≥ 32 GB RAM, ≥ 12 GB VRAM, or Apple Silicon ≥ 24 GB; D Apple Silicon ≥ 64 GB or ≥ 24 GB VRAM; each also needs 1.5×
the download free on disk. The highest runnable tier is recommended. Ollama models are screened by name and GGUF
architecture for rule 13 (a `qwen2` architecture under another name marks a derivative) but by name only for the
Gemma/Llama license exclusion, because permissive models such as Mistral-Nemo report the `llama` architecture.

## D-010 Lenient dependency locking (unattended default)
Compose Desktop's Skia runtime artifact is per-OS, so a lockfile written on macOS can't match Windows or Linux
exactly. Locks are LENIENT: they record the shipping set for the license gate without failing other OSes' builds.

## D-011 Native runtime layout (unattended default)
One shared library `mokuhyo_native` holds the llama.cpp and whisper.cpp JNI surfaces on a single ggml (llama.cpp
b11040, whisper.cpp b5130, hash-pinned in `native/lock.json`). Variants: `cpu` everywhere, `metal` on macOS,
`vulkan` on Windows/Linux, under `<dir>/<variant>/`. Search order: `mokuhyo.native.dir`, the packaged app's
`<resources>/native`, then `native/build/<os>-<arch>/` in a checkout; the first directory holding any variant is used
for both attempts, so an installed app never mixes in a dev build. The GPU variant is tried first unless Settings →
AI → "Use the CPU" is on; a JVM can't unload a library, so the CPU fallback covers a GPU variant that fails to load,
and a GPU variant that loads without finding a device runs with 0 GPU layers. A GPU model load that fails (e.g. out
of VRAM) is retried once on the CPU.
- BLAS/Accelerate is off on macOS (Accelerate's new BLAS needs macOS 13.3; the build targets macOS 12).
- No OpenMP; Linux and Windows link the C++ runtime statically; the Vulkan variant uses the system's Vulkan loader.
- x86_64 builds target x86-64-v3 (AVX2/FMA/F16C, CPUs from 2013 on). Older CPUs are unsupported.
- The bridges free their models in a JVM shutdown hook: ggml's Metal backend asserts at process exit if model buffers
  are still alive.

## D-012 Voices: bundled Piper built from source, per-voice license gate (unattended default)
Piper 2023.11.14-2 is built from source per OS (`voices/build.sh|ps1`, every input hash-pinned in `voices/lock.json`;
the release binaries for macOS are broken) and runs as a separate GPL-3 executable (espeak-ng inside) with one
long-running `--json-input` process per voice, stopped after 2 minutes idle; timeouts and cancellation kill it. A
small patch (`voices/patch_piper.cmake`) adds per-line `length_scale`, UTF-8 output paths on Windows, and closes the
WAV before announcing it. Bundled voices (`voices/manifest.json`, 11): es davefx/sharvard, fr siwis/upmc,
de thorsten, pt-BR faber/cadu, ru dmitri/denis, fa amir/ganji. `tools/voices/manifest.py` refuses any voice whose
dataset licence isn't CC0/CC BY/CC BY-SA/Apache/MIT or whose base model is non-commercial.

## D-013 Languages without a bundleable voice; base-model lineage (unattended default; lineage accepted by the owner 2026-10-02)
No redistributable Piper voice exists for ja (Hi-Fi-CAPTAIN NC), ko (KSS NC; open decision 4), zh-Hans (huayan
unknown, xiao_ya/chaowen NC lineage), ar (kareem: dataset repo has no licence) or id (news_tts: unclear dataset), and
de_DE-kerstin was dropped (fine-tuned from CC BY-NC-SA RyanSpeech). These languages use the OS voice at run time
(macOS: Kyoko, Yuna, Tingting, Majed, Damayanti are preinstalled; Windows needs the speech pack; Linux has none →
text-first). Open decision 4 default (a) applies to ko, and the same rule to ja/zh/ar/id. Pre-rendered clip packs
for those languages need a voice whose output may be redistributed; Apple's and Microsoft's system voices may not be
used for distributed recordings, so shipped listening passages in these languages are spoken at run time.
**Owner review:** most kept Piper voices were fine-tuned from `en_US-lessac`, whose Blizzard-2013 Lessac data is
research-licensed. The voices' own datasets are CC0/CC BY/CC BY-SA and rhasspy publishes the voice files under MIT;
whether the base model's licence carries into the fine-tune is unresolved. Kept, flagged here and in V1_SUMMARY.
**Owner decision (2026-10-02):** keep the voices. The "fine-tuned from en_US-lessac-medium" provenance stays on
each voice's row in `docs/LICENSES.md`.

## D-014 Dictionary packs (unattended default)
Language-neutral `DictionaryDatabase` (entry, sense, form, example, fold, meta). Sources: JMdict (full), CC-CEDICT
(full), English Wiktionary via kaikki.org for the other nine, capped at the 40,000 most frequent lemmas by wordfreq
(fewer exist for ko/ar/fa/id). kaikki.org and CC-CEDICT publish only rolling exports, so they are pinned by SHA-256
and date in `tools/packs/sources.lock` like Tatoeba was; a newer download is refused until re-pinned. Fold rules
are shared with Python through `shared/src/commonTest/resources/dictionary/fold_vectors.json`. Ranking adjustments
(non-common JMdict entries −1.5 Zipf, capitalised headwords after lower-case twins, CEDICT proper names last) and
schema additions (`ord`, the `fold` table storing only keys that differ) are documented in docs/CONTENT_PACKS.md.

## D-015 Comparison folding and Persian round-trip metric (unattended default)
`Fold.forCompare` (learner input vs keys) folds case, width, diacritics, tashkeel/tatweel and Arabic letter variants,
ё/е, and katakana/hiragana. The gate's TTS→Whisper token match (LCS over the module's own word tokens) also treats
Persian می/نمی + space and + ZWNJ as the same word, since both spellings are in common use.

## D-016 Bundled fonts (unattended default)
Noto Sans, Noto Sans JP/SC/KR and Noto Naskh Arabic variable TTFs from google/fonts, pinned to commit 9710da1 with
SHA-256 in `tools/release/fonts.lock`, staged by `tools/release/stage_fonts.py`. Target-language text always uses
the font for its script; app chrome uses the platform default with Skia fallback.

## D-017 Interview rating language and robustness (unattended default)
The rating prompt asks for English evidence, rationale and next steps, but EuroLLM-9B (Tier B) consistently writes
them in the interview's language even when told twice. BRIEF §6.2 doesn't require English, so the validator accepts
feedback in either language. It still rejects ratings whose quotes are mostly not the candidate's own words, caps an
estimate above the sustained level at the sustained level (the ILR rating is the sustained level), drops non-verbatim
quotes, and keeps one to three next steps. The interviewer prompt keeps the static instructions and the transcript
first and the per-turn instructions last so llama.cpp reuses its prompt cache across turns, and rejects questions that
repeat an earlier question or echo the candidate (character-bigram similarity ≥ 0.7).
A model-suggested `next_phase` that points backwards is clamped by the session instead of rejecting the turn
(OpiSession.advance never moves back); the full-interview smoke showed it throwing away otherwise good questions.

## D-018 Update check source (unattended default)
The optional update check (off by default, at most once a day, plus "Check now") reads
`https://api.github.com/repos/budzillaorigin/mokuhyo/releases` unauthenticated and only links the newer release page;
nothing is downloaded or installed. App tags `vX.Y.Z[-pre]` count, content-pack releases (`packs-…`) and drafts don't,
and pre-releases count while the installed build is 0.x or itself a pre-release. The repository is private, so until
the owner makes it public the check reports "the release list isn't public yet". Making it public is a release decision
left to the owner.

## D-019 Phase 6 drafting budget (unattended default)
On the §7.1 server, mistral-small3.2 drafts one passage in about 40 s (ILR 0+) to 2.5 min (ILR 3), and gpt-oss:20b
checks each one, so filling every band to the §5.3 target (12 per band, 1,584 passages) would take more than a day of
GPU time. The unattended run drafts to 5 per band for Phase 3, then spends a fixed budget of 8 hours in Phase 6
topping up toward 12 (level-major, so every language gains at every level). Whatever is still short at the end is
listed in docs/CONTENT_STATUS.md by gate_content, with the `gen_dlpt.py fill` command that closes it on the owner's
GPU machine (BRIEF §11.2 Phase 6).

## D-020 Exam blueprint and listening play policy — open decision 2 (unattended default)
One default blueprint for every language (`tools/items/blueprints/default.json`): a full form is 60 items in 180
minutes, 8/10/10/12/10/10 items from ILR 0+ to 3, with 30- and 60-minute slices scaled by minutes. Listening passages
play once in tests and the questions appear after the first play; practice allows replays. Questions and choices are
in English. The owner overrides per language with `tools/items/blueprints/<lang>.json` holding only the changed fields,
from the public familiarization guides.

## D-021 Final model per tier — open decision 1 (unattended default)
Tier A Phi-4-mini, Tier B EuroLLM-9B-Instruct (alternates Granite-3.3-8B, Mistral-7B), Tier C Mistral-Nemo (alternate
Phi-4), Tier D Mistral-Small-3.2, all Q4_K_M. EuroLLM-9B scored 0.76–0.79 on ja/es/ar in `eval_speaking.py` (above
the 0.60 warning line). Gemma 3 and Llama 3.x stay out: their licenses aren't on the rule-6 list, and opting in is the
owner's decision.

## D-022 Whisper large-v3-turbo stays a download — open decision 3 (unattended default)
The installer bundles Whisper small only. Large-v3-turbo (1.6 GB) is offered as a download in Settings → Speech for
Tier C/D machines. Bundling it would roughly double every installer for a minority of learners.

## D-023 Two macOS DMGs — open decision 5 (unattended default)
Separate arm64 and x64 DMGs. A universal app would need lipo'd native libraries and a universal JRE from jpackage,
which Compose Desktop doesn't produce. The release workflow builds both on their own runners.

## D-024 Portuguese and Arabic variants — open decisions 6 and 7 (unattended default)
Brazilian Portuguese only (`pt-BR`) and Modern Standard Arabic only (`ar`) at launch, as the brief assumes. Dialects
and European Portuguese would be added later as separate languages through the `LanguageModule` contract.

## D-025 Release builds without GitHub Actions; no Linux installer (owner decisions, 2026-10-01)
The owner doesn't pay for extra Actions minutes, so `ci.yml` and `release.yml` are disabled (`gh workflow disable`)
and every gate runs locally. Installers are built on the owner's machines instead:
- **Apple-silicon macOS:** built natively.
- **Intel macOS:** `tools/release/build_macos_x64.sh`, which cross-compiles the native libraries and Piper for x86_64
  and runs jpackage on an x86_64 JDK under Rosetta 2.
- **Windows:** `tools/release/build_windows.ps1`, run by the owner.

Each script ends with a fresh-install smoke: install the package, then launch it with `--smoke --require-packs` on an
empty data folder. No Linux installer is published ("no one will use it on Linux"). `tools/release/build_linux.sh`
stays for anyone who wants one; under Colima/Rosetta llama.cpp's Vulkan shader generator deadlocks, so the script
bounds that step and falls back to CPU. Rule 11's three-OS CI is suspended until the owner re-enables the workflows.
gate_release checks the published pre-release (assets, checksums, pre-release flag) and the local fresh-install logs.

## D-026 Windows installer cross-built on macOS (owner request, 2026-10-02; runtime licensing accepted by the owner)
The owner's own Windows builds kept failing, there is no SSH access to a Windows machine, and GitHub Actions is off
(D-025). So `tools/release/build_windows_cross.sh` builds the Windows x64 MSI and portable zip on this Mac:
- **AI library:** `mokuhyo_native.dll` (CPU and Vulkan) cross-compiled with mingw-w64 (`native/build_mingw.sh`).
  The Vulkan variant links an import library generated from `vulkan_core.h`, and the DLL imports only four Vulkan
  1.0/1.1 functions from the driver's `vulkan-1.dll`.
- **Piper:** cross-compiled with mingw-w64 (`voices/build_mingw.sh`) with the same pinned sources and patch.
  espeak-ng's data is compiled by the host build's espeak-ng (`voices/host_espeak.sh`).
- **Java runtime:** this Mac's `jlink` links the Windows JDK's jmods; it is the same JDK version, which jlink requires.
- **Launcher and installer:** jpackage's own Windows launcher plus a hand-written `.cfg`. wixl makes a per-user MSI
  that installs into `%LOCALAPPDATA%\Mokuhyo`, with a Start-menu shortcut and the jpackage UpgradeCode. The app also
  ships a console launcher, `MokuhyoConsole.exe`, so smoke tests can be read.

The result can't be run on the build machine: first-run verification is on Windows
(`tools/gates/fresh_install_smoke.ps1`, or install it and run `MokuhyoConsole.exe --smoke --require-packs`).
**For the owner:** the mingw build links the GCC C++ runtime statically into the JNI library. That runtime is
GPL-3.0 with the GCC Runtime Library Exception, which allows this for code under any license; MSVC builds link
Microsoft's runtime the same way. Rule 6's MIT/Apache/BSD list is read as covering third-party library code, not the
compiler runtime. If you read it more strictly, build Windows with MSVC (`tools/release/build_windows.ps1`).
**Owner decision (2026-10-02):** accepted as is.

## D-027 Always dark (owner decision, 2026-10-02)
The app followed the OS light/dark setting; on the owner's Windows PC it came up light, which was too bright. The UI
is now always dark: the light colour scheme and the OS check are gone from `ui/Theme.kt`. The PDF report stays black
on white because it is a printed document.
**v0.1.1 fix:** in v0.1.0 the theme coloured only the screens that brought their own `Surface`. The first-run flow
showed the light AWT window colour with black text, and Windows drew a light title bar. Now:
- `MokuhyoTheme` paints the dark background and default text colour for every screen.
- The AWT window background is dark too, so resizing and the first frame never flash white.
- On Windows, `mokuhyo_win.dll` (`native/win/dark_titlebar.c`, our code, system DLLs only) asks DWM for a dark title
  bar in the app's colour. Windows 10 20H1+ gets dark mode; Windows 11 also gets the exact caption colour. Older
  builds ignore it.

Native OS file dialogs (export and import) remain in the OS's own style.

## D-028 Phase 8 owner decisions (2026-10-03)
Recorded from the owner's answers to BRIEF_PHASE8.md Part A before the unattended Phase 8 run.
- **A-01 / A-05 — seed term list delegated to the pipeline.** The owner did not hand-author `tools/terms/seed_terms.csv`.
  Claude Code builds it (new item C-01a): ~300 terms in the BRIEF_PHASE8 §B.2.2 domains, `term_en` and `definition_en`
  copied verbatim from the August 2026 DoD Dictionary (public domain), falling back to ATP 3-01.81, JP 3-10 (2019
  official), AFDP 3-10 and ATP 1-02.1, each with `source_doc` and `source_page`. `approvedBy` = "owner (delegated
  2026-10-03)"; the requester may still review it later through the Content Review screen.
- **Translation rule.** Where an allied source (`alignment_ok = true`) confirms both the target-language term and its
  meaning, that confirmed term is used as the translation and cited (`term_source_id` + page). Only when no confirmed
  term exists does the model propose one, with `badge = unconfirmed-term`.
- **A-06 — partner forces for personas and culture cards: the air force equivalent of each partner country.**
  ja: Japan Air Self-Defense Force (JASDF) · ko: Republic of Korea Air Force (ROKAF) · de: Luftwaffe ·
  fr: Armée de l'air et de l'espace · es: Fuerza Aérea Mexicana (Mexico, per the AFCLC guide available; Spain's
  Ejército del Aire y del Espacio as the alternate) · pt-BR: Força Aérea Brasileira · ru: Russian Aerospace Forces (VKS) ·
  id: TNI Angkatan Udara (TNI-AU) · fa: Islamic Republic of Iran Air Force (IRIAF; culture reference per the AFCLC Iran
  guide) · zh-Hans: PLA Air Force (PLAAF; the Taiwan sources and ROCAF remain the zh-Hant alternate) ·
  **ar: two persona sets — Royal Saudi Air Force (RSAF) and Qatar Emiri Air Force (QEAF)**, culture cards from the AFCLC
  Saudi Arabia guide for both, with Qatar-specific notes drawn from public sources and badged.
- **A-09 — CLAUDE.md rule 6 replaced** with the four-flag wording in BRIEF_PHASE8 §B.0 (`verbatim_ok`, `alignment_ok`,
  `machine_extract_ok`, `distribution`); `tools/terms/overlap_check.py` is a gate on all shipped text. Approved by the
  owner 2026-10-03; Claude Code applies it in C-00.

## D-029 Phase 8 C-00: how the handoff's gates are wired (unattended default, 2026-10-03)
- **Rule 6** in `CLAUDE.md` is the §B.0 wording, as approved in D-028 (A-09).
- **One guard for every source read.** `tools/terms/sources.py` is the only way scripts open a source: `require(id,
  purpose)` with purpose `overlap` (mechanical comparison), `parse` / `llm` (needs `machine_extract_ok = true`) or
  `align` (needs `alignment_ok = true`). `distribution: limited` rows — and any path under `us-limited/` — are refused
  for every purpose before a file is opened. Page-split text for parsing is cached in `tools/sources/.cache/pages/`
  (git-ignored), only for `machine_extract_ok = true` sources. Build machines need poppler's `pdftotext`.
- **`tools/gates/gate_terms.sh`** (called by `gate_core.sh`): `fetch_sources.py --check`, `overlap_check.py index`,
  `validate_seeds.py` (from C-01a), `validate_alignment.py`, then `overlap_check.py check` on `term_alignment.csv` plus
  every string of the source-of-truth content files the pack builders copy into the packs (`tools/items/bank`,
  `tools/opi`, and the Phase 8/9 folders `tools/tracks`, `tools/culture`, `tools/pragmatics`, `tools/personas`, …).
  Latin-script strings are checked as English and as the file's language; in ja/zh/ko files the CJK runs are checked
  as characters and the Latin runs as English words. Thresholds stay at the handoff's 8 words / 20 characters.
- **First run on the existing banks:** two drafted strings failed (a French listening line shared 8 words with the
  RNS 2025; a Chinese listening passage's English title/explanations shared "the founding of the People's Republic of
  China" with three English sources). Both were reworded rather than allow-listed; the French clip is re-rendered with
  the next audio pass (C-03).
- **CI** sets `MOKUHYO_NO_SOURCES=1`: the PDFs are not in git and the Aug 2026 DoD Dictionary is CAC-only (JEL+), so CI
  runs the validators and says the overlap check was skipped. CI is disabled anyway (owner, 2026-10-01); the full gate
  runs on the build Mac.
- **`fetch_sources.py`** re-downloads every acquired/superseded row by URL (http(s) or a `file://` mirror) and verifies
  SHA-256; `--check` is offline. Rows with a non-URL source ("JEL+ (CAC)") are reported as manual. Limited rows are
  never downloaded, opened or hashed. `tools/sources/check.py` (named in A-02) lists every row with its flags.
- **`docs/LICENSES.md` → "Reference sources"**: one row per acquired/superseded SOURCES.json id (the six limited JPs
  are listed as never read or shipped); `check_licenses.py` fails when one is missing.

## D-030 Phase 8 C-01a: how the seed list is built (unattended default, 2026-10-03)
- **Catalog.** `tools/terms/seed_catalog.py` holds the §B.2.2 starter list (priority 1, every term kept) and a
  by-domain extension of DoD Dictionary terms (priority 2/3). `build_seed_terms.py` resolves each row and writes
  `seed_terms.csv` (402 rows: cuas, base-defense, airspace, ew, roe, c2, logistics, medical, hadr, brevity). The
  §B.2.1 columns gain `acronym`, `definition_source` (doctrine | original) and `source_id` (the SOURCES.json id).
  Cross-cutting starter terms are filed under `c2`.
- **Source order.** DoD Dictionary Aug 2026 → ATP 3-01.81 glossary → JP 3-10 (2019) glossary → JP 3-01 (2017)
  glossary → ATP 1-02.1 (brevity rows) → **the June 2025 and November 2021 DoD Dictionary editions** (added: same
  publisher, public domain, both in SOURCES.json; the Aug 2026 edition dropped terms such as *call sign*,
  *situation report* and *exercise*). AFDP 3-10 has no glossary.
- **Body-text definitions.** When no glossary defines a term, a sentence from the public doctrine's body text may
  serve, copied verbatim with its page — only if the term is the sentence's grammatical subject followed by is/are/
  means/refers to, the primary model picks it as a general definition, and the checker model (gpt-oss:20b) agrees.
  Choices are kept in `seed_quotes.json` so the build is reproducible offline. 3 rows resolved this way.
- **Wrong senses are not used.** Where the only doctrinal entry is a different sense (*detection* = CBRN, *cover* =
  intelligence cover, *accountability* = legal obligation), the row uses an original definition and says why.
- **Verbatim is verified, not assumed.** Definitions are trimmed to whole sentences that appear verbatim in the
  cited source (page breaks and running headers tolerated); `validate_seeds.py` re-checks every doctrine row against
  the source text, plus ids, domains, priorities, approvedBy, ≥ 250 rows and every priority-1 starter term.
- **Result:** 402 rows, 316 (78.6%) with a doctrinal definition, 86 original. The C-01a target of ≥ 90% is not met
  (known gap, PROGRESS): the starter list is mostly modern tactical vocabulary — FPV drone, interceptor drone, net
  capture, vehicle search area, lockdown, all clear, blotter, radio procedure words — that no public US glossary
  defines. Padding the list with ~450 more dictionary terms to dilute the originals would not serve learners.
  Original rows are marked `definition_source = original` / `source_doc = authored` and stay reviewable.
- **Superseded public-domain editions count as public domain in the overlap gate.** `overlap_check.py` now indexes
  `status: superseded` rows too, so a DoD Dictionary (Nov 2021) definition that AAP-06 repeats reports `ok-PD`
  instead of failing (3 seed rows).

## D-031 Phase 8 C-04: lexicon update channel (unattended default, 2026-10-03)
- **One file, embedded signature.** A package is a single JSON file; the Ed25519 signature covers the canonical JSON
  of everything except itself (docs/LEXICON_FORMAT.md). Python (`tools/release/lexicon.py`) signs, the JDK's own
  Ed25519 verifies (no new app dependency). A cross-language fixture signed by Python is verified by the Kotlin test.
- **Key custody.** The publisher key was generated on the build Mac at `~/.mokuhyo-keys/lexicon-ed25519.key`
  (mode 600, outside the repo); the public half is `tools/release/keys/lexicon-ed25519.pub.json` (key id
  4c3f54aa7b5109a4) and ships in the jar. **Owner: back the private key up**; to rotate, add a second key to the
  public file in an app release before signing with it.
- **URL import is a learner-started download** (one GET, connect 5 s / request 120 s, 20 MB cap, cancel). It extends
  rule 2's list of network calls exactly as BRIEF_PHASE8 C-04 requires; docs/PRIVACY.md lists it. File import needs
  no network.
- **Unsigned packages** import only after a warning and stay labelled "unverified publisher"; a bad signature or an
  unknown key is refused.
- **Review queuing: added terms only** (the C-04 gate: "queues exactly the new terms"). Changed and removed terms are
  shown in Lexicon → What's new; a changed term already in Review keeps its card.
- **Current version** per (lang, track): the highest imported version, unless the shipped track is newer (an app
  update supersedes an older package). Packages are append-only rows in the learner DB (schema 1 → 2 migration,
  `1.sqm`) and travel in the `.mokuhyo` bundle.

## D-032 Phase 8 C-06 and C-11: pragmatic flags and corrections modes (unattended default, 2026-10-03)
- **Flags ride the existing correction pass.** `topic_turn` gains `pragmatics[] {kind, severity, what, why, better}`
  (kinds register | face | directness | ritual | taboo). The prompt receives the persona (C-08) and up to six rules
  from the language's pragmatics pack (C-07: the persona's entries, else address and refusal norms). The prompt says
  flags never lower `turn_level`; the golden test pins that a flagged turn keeps its level.
- **OPI: a separate review, never an input to the rating.** `opi_cultural_review` runs after `opi_rate` on the same
  transcript and is shown in its own block headed "Cultural appropriateness" with the line "Not part of the ILR scale —
  it does not change your level or rating." The rating call never sees cultural notes (tested).
- **One correction pass for Live and After action.** Topic, persona and scenario conversations make the same single
  `topic_turn` call in both modes, so the stored records are identical by construction; After action only hides
  them. Interview practice gets per-answer feedback through `turn_feedback`, run by a background queue that waits while
  the interviewer's next question is being generated and drains at the end. Off asks only for the partner's reply
  (`partner_reply`) and, for interviews, skips the rating — the session is labelled "no feedback will be generated".
- **Live keeps today's end** (save and close); After action ends on the After Action Brief. Every mode stores its
  mode on the conversation; Live and After action also store each turn's record in `conversation_turn_feedback`
  (schema 2 → 3, `2.sqm`), and After action stores the AAB (`conversation.aabJson`). History shows the mode and opens
  the AAB; the PDF report lists AAB next steps, patterns and cultural-note counts. Defaults per activity and "keep my
  recorded turns" are learner settings (they travel in the bundle): interview practice After action, topic and
  persona/scenario practice Live. Interview tests are always After action.
- **AAB summary** (`aab_summary`: three next steps, grammar, register, avoidance) falls back to the session's own
  recurring corrections when no model answers. Cultural flags are grouped by culture-card tag (register → rank,
  face → face, directness → refusal, ritual → hospitality, taboo → religion) with the matching card's title.

## D-033 Phase 8 C-02: allied term alignment (unattended default, 2026-10-03)
- **Three distinct attempts at a documented equivalent**, in order: (1) exact search of the model's three candidates in
  the language's `machine_extract_ok = true` sources (AAP-06 English/French entry pairs read directly); (2) glossary
  retrieval — the model picks among the six most similar entries of MD35-G-01 (pt-BR), AAP-06 (fr) or the Japanese
  white-paper index, or none; (3) parallel editions — the model copies the term the ja/ko/fr edition of a bilingual
  white paper uses on the pages aligned with the English mention. Every pick is verified to occur on the cited page.
- **de, id, zh-Hans** sources are `machine_extract_ok = "unclear"`: never parsed in bulk; their rows keep the model's
  term with `badge = unconfirmed-term` and go to `tools/terms/lookup_queue.csv` (1,161 rows) for a human.
- **es, ru, ar, fa** have no allied source: model-proposed terms, `badge = unconfirmed-term` (as specified).
- **Overlap is absolute:** a definition still failing `overlap_check.py` after the redraft rounds (including a targeted
  redraft that names the shared run of words, `align_terms.py fixoverlap`) is written with an empty definition and a
  note, never with the copied wording.
- Checker-model verdicts other than `pass` leave the row at `status = draft` with the problems in `notes`.
- **Every cited term is verified** (`align_terms.py verify`, added after review): the checker model must agree the cited
  term is the target-language term for exactly this concept — not broader, narrower, related or mixed with English. A
  rejection drops the citation (`unconfirmed-term`, note names the rejected term). Precision over coverage: a wrong
  term with a real citation would mislead learners more than an honest "unconfirmed" badge.
## D-040 Phase 9 N-07: speaker variety (unattended default, 2026-10-03)
- **Cap raised from two to four Piper voices per language** (`MAX_PER_LANGUAGE`), aiming at two female and two male.
  The installer grows by ~280 MB of new model files (es_MX-claude-high, fr_FR-mls, de_DE-mls, pt_PT-tugão); extra
  speakers of an already-bundled multi-speaker model (sharvard M, upmc Jessica, MLS speakers) cost nothing — manifest
  entries name the shared files with `model`.
- **Gender is measured, not guessed from a name.** `tools/voices/measure.py` synthesizes a fixed sentence and
  `pitch.py` takes the median F0: below 150 Hz male, above 190 Hz female, between ambiguous. Every entry records
  `measuredF0Hz`; `manifest.py check` refuses a gender label that contradicts the measurement unless `genderSource`
  says "unverified". This relabelled fa_IR-ganji (unknown → male, 98 Hz) and left dmitri (185 Hz) and faber (179 Hz)
  as unverified name-based labels.
- **Regional variants:** es-MX (claude, Apache-2.0) next to es-ES; pt-PT (tugão, CC0) in the Portuguese pack, labelled
  as European Portuguese. `region` is recorded per voice. es_MX-ald (Unlicense, 160 Hz) and pt_BR-jeff (154 Hz) were
  allowed but left out: ambiguous gender and no new coverage. No Arabic variant: ar_JO-kareem has no license.
- **Unlicense** joins the allowed dataset licenses (public-domain dedication, like CC0).
- **Rotation** (`VoiceRotation`): passages start at a stable hash of their id, distinct speakers in a script get
  distinct voices (same-gender speakers take consecutive voices), a persona keeps one voice of their gender.
  Already-rendered pack clips keep their voice until re-rendered (`render-audio --force`).

## D-041 Phase 9 N-00(b): Chatterbox Multilingual — owner exception to rule 13 (owner decision, 2026-10-04)
- BRIEF_PHASE8 N-00(b) names Chatterbox Multilingual (Resemble AI, MIT) as the natural-voice engine and calls it "not
  PRC-origin". Checking the code before adopting it: its speech tokenizer is `S3TokenizerV2` loading
  `speech_tokenizer_v2_25hz`, the tokenizer trained by Alibaba's FunAudioLLM team for **CosyVoice2-0.5B** (and built on
  SenseVoice). Chatterbox's own acknowledgements credit CosyVoice and S3Tokenizer; the S3Tokenizer repository states
  the v2 25 Hz model comes from `iic/CosyVoice2-0.5B`. Rule 13 bans CosyVoice "or derivatives" by name, so a model
  that ships and runs CosyVoice's trained tokenizer is out, whoever packaged it.
- Compliant alternatives exist inside N-00 itself, so the run continues (CLAUDE.md: only a conflict with *no*
  compliant alternative ends it): pre-rendered clips → Kokoro-82M (Apache-2.0, StyleTTS 2 lineage) → VOICEVOX
  (Japanese, pre-render only) → Piper → OS voice → text. The "TTS → Whisper ≥ 80%" and "every ja/ko/ar/zh passage
  pre-rendered" criteria are met with these engines where they cover a language, and logged as gaps where they don't.
- Reversible: if the owner decides the tokenizer is acceptable (or Resemble ships a model without it), the
  `--engine chatterbox` path can be added later; nothing in the voice manifest format depends on this choice.
- **Owner decision (2026-10-04), superseding the above:** "Use chatterbox. It's the only option that keeps natural
  sounding voices." Chatterbox Multilingual is adopted as a named exception to rule 13. Conditions kept so the
  exception stays narrow and visible:
  - only this model (`chatterbox-multilingual`), listed in `OWNER_EXCEPTIONS` in `tools/gates/check_provenance.py`;
    its manifest entry must carry `"ownerException": "D-041"` and `provenance.prcComponent` naming the CosyVoice2
    speech tokenizer (Alibaba / FunAudioLLM), or the gate fails;
  - `docs/MODELS.md`, `docs/LICENSES.md` and the voice picker state the component's origin;
  - every other rule-13 check (LLM tiers, STT, other TTS, drafting defaults) is unchanged; no other CosyVoice-family
    model is allowed by this decision.

## D-042 Functionality QC of 2026-10-19: how its findings are fixed (unattended default, 2026-10-19)
Source: `docs/QC_REPORT_2026-10-19.md`. Each choice below is the simplest one consistent with rules 5 and 7.
- **Listening test audio that won't play (QC #1).** A play is counted only once the output line is open and started
  (`AudioIO.open`). If the output then fails, the play is given back (`ExamSession.audioFailed`). The learner's own
  Stop still counts. If a recording can't be played at all, the learner can choose to leave it out of the score
  (`ExamSession.voidPassage`, with a confirmation). Its questions are then not scored, rather than counted wrong.
  `AttemptScoring.voidedItems` records how many were left out and `complete` becomes false. The result screen, History
  and Home say so, and the estimate is saved as provisional. A test only uses a pre-rendered clip when the clip file
  itself is in the pack, not just its index entry.
- **Interview speech (QC #2).** `Voice.say` returns an outcome (played / no voice / playback failed), and the
  interview acts on it:
  - The interview-test button needs an installed voice.
  - If a question can't be heard in a test, the learner can fix the audio and press Repeat. Or they can choose
    "Continue as text practice": the question text is shown and the interview no longer counts as a test.
  - Practice shows the question text automatically when it couldn't be heard.
- **Speaking estimate (QC #3)** (`SpeakingTrend`). Only a model-rated *test* answered mostly by voice joins the
  speaking trend. It is firm (confidence 0.6) only when that language and tier are calibrated (`Calibration.band`) and
  every answer was spoken. Otherwise it is provisional (confidence 0.3). Mostly-typed tests, text-fallback tests,
  practice and self-ratings stay out, and the results screen says why. No schema change: the response modality decides
  whether and how a row is written, and the `provisional` flag carries it to Home, History and the PDF.
- **One voice path (QC #4).** "Play test voice" and every other screen go through the voice service and the
  output device set in Settings (`Voice.say` / `Voice.play`). The test names the engine and voice that played.
- **Microphones (QC #5).** The input picker lists any device that supports one of the recorder's capture formats.
- **Gates (QC #6).**
  - `--roundtrip` fails when a declared clip is missing, unless `--allow-missing` is given. It prints expected /
    present / tested counts per language.
  - The two Python tests that needed the restricted source texts now use an invented fixture corpus
    (`tools/terms/overlap_fixture.py`), selected through `MOKUHYO_OVERLAP_SOURCES_JSON` / `MOKUHYO_OVERLAP_CACHE`.
- **Playback quality.**
  - Slow playback (0.8–0.9×) is time-stretched (WSOLA, `TimeStretch`), so the voice keeps its pitch.
  - Synthesized passages keep the voice's own sample rate.
  - New Chatterbox imports and Piper renders are encoded at 24 kHz; the Ogg header carries the rate, so existing
    16 kHz clips still decode. Shipped clips stay 16 kHz until re-imported or re-rendered with `--force`.
  - The passage cache key names the engine, voice id and speaker that spoke each line, plus a render version.
  - Pauses are 350 ms within one speaker and 700 ms at a turn change.
  - The build-time renderer splits an overlong sentence at clauses, then words, then characters
    (`tools/voices/textsplit.py`).
- **No model installed.** Live corrections are not offered. Interview practice describes its questions as scripted.
- **Not done in this pass (need people, hardware or a larger change):**
  - a human-reviewed starter bank;
  - instructor calibration samples;
  - native-speaker checks: Indonesian through the Malay model, and blind voice comparisons;
  - sentence-by-sentence streaming of interviewer replies to TTS;
  - a Kokoro / VOICEVOX Core live-voice pilot, plus a provenance check of Kokoro's phonemizer chain before adoption;
  - a per-language readiness panel and a guided first session;
  - visual QA at 1024×700 and 150 % scaling, and with keyboard navigation and screen readers;
  - laptop benchmarks and device-disconnect tests on real Windows/macOS hardware.

## D-043 Follow-up QC of 2026-10-19: closing the paths D-042 left open (unattended default, 2026-10-19)
Source: `docs/QC_REPORT_2026-10-19_FOLLOWUP.md` and `BRIEF_QC_FOLLOWUP_2026-10-19.md`. Each choice is the simplest one
consistent with rules 5, 7, 8 and 11.
- **Damaged recordings (F1).** `PassageAudio.load` treats a damaged or unreadable file as a recoverable result, never
  an exception out of the screen:
  - "Expected" failures are `IOException`, `IllegalArgumentException`, `IllegalStateException`,
    `UnsupportedAudioFileException` and `IndexOutOfBoundsException` (decoders indexing past a truncated buffer).
    `CancellationException` is always rethrown first; it is itself an `IllegalStateException`.
  - A clip counts as usable only when its WAV header parses and it holds at least one frame. So a zero-length clip
    or `OggS` followed by zeros is damaged, not `Ready`.
  - A damaged pack clip is replaced by the voice service when a voice exists, in tests as in practice. The source line
    says "voice service — the pack recording was damaged", so the replacement is visible. Without a voice, the result
    is `Unavailable`: "This recording file is damaged. Try again, or leave it out of the score (practice shows the
    transcript instead)."
  - A damaged cached render is deleted and rendered again once.
  - `loadOrUnavailable` is the screens' backstop: any other exception becomes `Unavailable`. Listening, Stand-to and
    the Lexicon use it.
  - Retrying never touches `ExamSession` plays.
- **Interview delivery (F2).** `QuestionDelivery` tracks each question's state: pending → playing → heard / failed /
  cancelled.
  - The state is keyed to the question's transcript index, and every new question resets it.
  - Test answers open only when the question was heard, or after "Continue as text practice". Once heard, a question
    stays answerable: a Repeat that fails or is cancelled doesn't take it away again.
  - After a Cancel, a test shows "The question was stopped before it played." with Repeat and the text-practice
    fallback. Practice shows the question text after a failure or a Cancel.
  - Repeat runs as the tracked job: one playback at a time, and Cancel stops it.
  - `Voice.say` now stops playback when its coroutine is cancelled, and plays nothing if cancelled during synthesis.
    The other `Voice.play` callers (History and transcript ▶, exemplars, drills) still play to the end once started.
    They have no answer gate to protect.
- **Output ownership (F3).** `AudioIO.playOwned` holds the opened line from inside the non-cancellable open block,
  and a `finally` in the caller closes it.
  - `Playback.close` is idempotent.
  - A play is counted in `started`, after the line opened and while the learner still wants it. This is D-042's order.
  - Every other `AudioIO` user opens and plays inside one blocking call, with `run`'s own `finally`. There is no
    coroutine handoff for the line to leak through, so they are unchanged.
- **Speaking labels (F4).**
  - **What is stored.** `StoredConversation` gains optional fields: `spokenAnswers`, `typedAnswers`,
    `textFallback`, `calibrated`, `trend` and `trendReason`. They live in `turnsJson`, so no SQL migration is needed.
    Older rows and bundles decode with nulls.
  - **Status values.** `trend` is `COUNTED_FIRM`, `COUNTED_PROVISIONAL`, `EXCLUDED` or `NOT_APPLICABLE` (no estimate).
    `trendReason` is a short phrase that goes into the label.
  - **Self-ratings.** The self-rating save copies what `saveInterview` stored instead of rebuilding the record. It
    used to drop the English glosses too. It files the self-rating as out of the trend.
  - **One label.** `SpeakingTrend.label` is the only wording, used by History rows and detail, the PDF entries and
    the results headline:
    - `ILR 2 (AI-rated)`
    - `ILR 2* (AI-rated, provisional — not calibrated | some answers typed)`
    - `ILR 2 (AI-rated, not in speaking trend — mostly typed | text practice | practice)`
    - `ILR 2 (self-rated)`
  - **Older records.** These have no stored context, and they are never rewritten:
    - an estimate row decides by its `provisional` flag;
    - a rated `OPI_TEST` with no row, or practice, says "not in speaking trend";
    - anything else says "recorded before 2026-10-19, trend status unknown".

    Pre-D-042 tests were saved with `provisional = false`, so they still read `ILR 2 (AI-rated)`. That follows the
    brief's rule: use the row's flag.
  - **Footnote.** The PDF puts the cover's `*` footnote (`SpeakingTrend.PROVISIONAL_NOTE`) under a provisional
    interview entry.
- **Navigation rail.** The rail scrolls vertically, rather than switching to icons only. The window's minimum size
  stays 1024×700. That size is in AWT units, which are logical on HiDPI Windows and macOS. So a window as short as the
  QC's stress case happens mainly when the screen itself is that small at its scaling.
- **Review focus.** Focus is requested only when a card is shown.
- **Tests that can't run on `cc6bfc8`.** Some regression tests use APIs this fix introduces (`QuestionDelivery`,
  `playOwned`, the label). They can't compile on `cc6bfc8`, so they were run against a copy of `cc6bfc8`'s logic placed
  behind the new names: the old gate, the old Play coroutine, the old save path. They fail there and pass after the
  fix. The commit bodies record each run.
- **Still open (need people, hardware or a larger change):**
  - **From D-042:** sentence-streaming of interviewer replies to TTS; the Kokoro / VOICEVOX Core live-voice
    pilot; re-rendering shipped clips at 24 kHz; laptop benchmarks; physical device-disconnect tests; instructor
    calibration.
  - **Missing dev assets:** the Japanese dictionary and tokenizer DBs, and the reference PDFs for `gate_terms.sh`.
  - **Real-window checks:** keyboard testing on Review and the rail; Windows/macOS windows at 150 %.
## D-044 — recoverable spoken interviews and foreground speech priority (owner handoff, 2026-10-19)

Implemented on `codex/interview-readiness-d044`, based on pulled main `9a1c761`. The owner requested C1–C4 in
order, one fix per commit with regressions failing on main, plus fixture isolation. The brief also requires C5/C6.
The earlier D-042/D-043 delivery, playback and assessment-label work remains in place.

- **Capture ownership:** Interview and Topic keep `VoiceCapture` composed outside the generation-busy UI.
  Visibility does not cancel transcription. Capture has its own operation state, explicit ASR Cancel/retry,
  retained pending PCM/text, cancellation propagation and `finally` cleanup. Confirmation and stale callbacks
  are covered by tests using a synthetic JavaSound input and delayed recognizer.
- **Cancellation:** Retry/End remain available when generation stops before the first question or after an
  answer. OPI turn-record indices are stable within the session. An unanswered committed question is re-delivered
  without advancing phases; a repeated answer submission cannot append a duplicate. Feedback scheduling resets
  in `finally`, and Cancel joins the stopped job before recovery proceeds.
- **Durability:** accepted turns and permitted recordings are checkpointed before inference; transcript snapshots
  are saved before feedback drain/rating/AAB. Checkpoints are immutable ordinary conversation rows with optional
  JSON recovery metadata and nullable `endedAt`, requiring no schema or bundle-format change. History shows the
  latest interrupted snapshot; Speaking offers recovery. Completion writes the canonical session row while earlier
  checkpoints remain stored and exportable. Existing append-only bundle import keeps successive snapshots.
  Failed Topic replies retain the unanswered learner turn. Audio-write errors retain PCM in the active screen
  for Retry, and navigation during persistence cannot skip the permitted audio write. Re-recordings use distinct
  files rather than overwriting an earlier recording.
- **Speech before feedback:** Topic `say` commits/returns the partner reply; a separate feedback pass updates the
  exchange. After-action and CPU-only sessions defer critique until the end; CPU Live explains this in the UI.
  The gateway serializes issued inference, cancels only its active background task, and waits for cancellation
  cleanup before issuing foreground work. This ordering matters because the native Llama cancel covers already
  issued work. Interview feedback queue capacity is 32; end-of-session feedback waits at most 30 seconds. Missing
  feedback is not fabricated, and accepted transcripts remain recoverable independently of grading.
- **Capture responsiveness:** device open/Stop/close, conversion, recording writes/hashes and fluency analysis
  run off the UI thread. Cancellation during device acquisition still closes the returned handle. Each recording
  is capped at two minutes. The first loud frame is no longer assumed to be background silence. RMS-only VAD
  deliberately retains ambiguous loud noise/voice for ASR/manual Stop; real noisy-room tuning remains unverified.
- **Fixture isolation:** tests explicitly choose their temporary packs root and assert the resolved file. Normal
  bundled → data-dir → checkout precedence is unchanged. The three reviewed failures were sandbox-specific;
  the owner's green three-OS CI at `033f0ef`, including build/smoke, is not contradicted.
- **Scope/provenance:** no new model, voice engine, voice weights or provenance exception. Timing-based model
  selection, compact voices, prerecorded fallback questions, streaming and passage rerendering are a later phase.
  Rule 13 continues to apply, with only the existing D-041 Chatterbox exception.
- **Delivery:** open a PR against main and leave it unmerged. A separate workflow, restricted to a merged PR from
  this branch in this repository, dispatches `ci.yml` on main after the owner merges. It checks out no PR code and
  uses only `contents: read` and `actions: write`. No public release is part of this change.

Validation: **384 shared + 42 desktop tests, zero failures/errors**, 14 failing regression assertions against
unchanged main, Python 12/12, license/provenance checks, native CPU build, distributable and packaged offline smoke.
27 conditional stdout skips identify absent real models/voices/dictionaries; JUnit's zero skip count is not a full
integration pass. Full local staging hits a proxy 403 fetching an existing Piper voice; Debian packaging lacks
`fakeroot`. Windows/macOS CI and 30-minute physical-device latency/quality evidence remain outstanding.
See [D-044 evidence](qc/2026-10-19-current/D044_VALIDATION.md) for commands, commits and limits.

## D-045 — faster, more natural spoken interviews (owner brief, unattended defaults, 2026-10-19)

Source: `BRIEF_INTERVIEW_SPEED_2026-10-19.md` (N1–N7), on branch `claude/interview-speed` from main `c8c04ac`. One
change per commit with its test; the PR stays unmerged. Nothing new became a default voice, model or engine; no
provenance exception was added (rule 13 and D-041 unchanged).

- **N1 measurement.** `TurnTimeline` (shared) records monotonic marks per session and turn for the brief's stages and
  numbers only — it has no API that takes text or audio, and fallback reasons are stored as categories
  (`FallbackKind`). The headline `first_audio` runs from the learner's last speech, back-dated by the VAD's silence
  tail, to the first meaningful partner audio. Deep layers mark through a coroutine-context `TimelineTrace`, so
  background work (feedback, glosses) never marks a turn. Turn 0 and cold turns are reported apart; warm-up (N4) has
  its own section. Peak memory: Linux `VmHWM`, macOS/Windows current RSS sampled per turn (the app process only;
  Piper runs separately). `--bench-interview` uses the app's own model and Whisper (read-only from its data folder),
  synthetic answers from `tools/bench/answers.json` (or the owner's own WAVs), the real VAD over each clip for the
  endpoint, and writes JSON + Markdown; anything without real model + Whisper + voice is labelled a dry run.
- **N2 tier choice.** The live budget is the model's share of a turn: first validated sentence ≤ 2 s, estimated by
  the on-device timing check as first token + 24 tokens at the measured rate on the live interviewer prompt with
  Whisper and the voice loaded. Recommendation = the most capable runnable tier meeting it, at or above the language's
  quality floor; slower tiers stay selectable, marked "Slower — better for end-of-session grading". **Before any
  timing**, a computer without a usable GPU is recommended at most Tier B (the 32 GB CPU-only case); with a GPU the
  memory fit still decides. The fit reserves the KV cache (≈ context × weights / 40 000), Whisper ×1.3, 0.3 GB voice and
  0.6 GB UI, within 80 % of RAM, or weights + cache within VRAM. Quality floors come only from non-fixture calibration
  rows (n ≥ 5, within-one ≥ 80 %); today every floor is "unverified" and nothing is excluded by it. A session pins its
  model (`AiGateway.pinned`); post-session grading with a larger model was not added (optional in the brief).
- **N3 first sentence.** The live schemas are the spoken field alone; English glosses come from a background `gloss`
  task, deferred to the end wherever feedback is (CPU-only or After action) because any prompt between turns evicts the
  conversation's KV prefix. Streaming: complete sentences (ICU with abbreviation suppression, punctuation fallback) are
  each checked (script/language, repetition, length) before they are spoken; a failure before anything was spoken is
  retried, after that the turn stops and falls back. The whole-turn deadline (default 8 s; 5/8/12 s or off in
  Settings) covers queue, generation and retries **until the first sentence is ready**; a reply that started in time
  is not cut off. It applies only when the bank has a question to fall back on. Topic conversations stream but have no
  deadline (no scripted bank to fall back to); they keep the progress line and the hard ceilings.
- **N4 readiness.** Interview Start runs the preflight checklist (microphone, tone, voice, voice process, Whisper,
  model warm-up on the interviewer's own prompt). Not-installed components are said, not failures; with no voice,
  practice runs as labelled text practice and a test offers "Guided text practice instead". Thread caps: model + voice
  ≤ the budget and recognition + voice ≤ the budget, one core kept for audio/UI from six cores; overrides in Settings →
  AI. Piper's cap reaches ONNX Runtime through `MOKUHYO_TTS_THREADS` (new `patch_piper.cmake` step, applied to the
  pinned sources and checked idempotent here; first compiled by the release build). Background LLM work during
  recognition can still exceed the budget; it is deferrable and preempted by live replies.
- **N5 guided interviews.** A guided turn plays the scripted question's prerendered clip (24 kHz Ogg Opus, 32 kbit/s)
  when `prompts.json` has an intact clip rendered from the current text; otherwise live TTS, then text. Approved
  transitions/follow-ups were **not** rendered: no approved set exists in the packs, and the brief forbids adding pack
  content beyond the index. Trend effect: ≥ 50 % scripted questions → excluded ("guided interview — scripted
  questions"); ≥ 10 % → a counted test is provisional ("partly guided"). Pilot indexes for `es` (Piper) and `ja`
  (VOICEVOX `vv:8`) are committed; clips must be rendered on a host with Piper / the VOICEVOX engine. Estimated size
  (from text length, not measured): es 2.5 MB, ja 2.2 MB, all 11 ≈ 25 MB. The VOICEVOX character credits, missing from
  `docs/LICENSES.md` although the Japanese exam clips already used them, were added.
- **N6 voice pilot — both candidates stopped after the audit, before any engine code.** Kokoro-82M: developer is a
  pseudonymous account ("hexgrad"/"@rzvzn"), country not established — the brief's stop condition. VOICEVOX Core:
  Japanese project, MIT core, but it links VOICEVOX's ONNX Runtime build and loads voice models under custom terms
  (no reverse engineering) — not on rule 6's list for components shipped in the installer. Details in
  `docs/MODELS.md`. The blind listening kit (`--listening-kit`, `tools/voices/listening_kit.py`) compares the sources
  that exist (Piper, prerendered clips, optionally OS voices, off by default per D-013). Persian's pronunciation and
  Indonesian-through-Malay still need native review; nothing here claims either is fixed.
- **Findings for later phases.** With a 1.5 s VAD tail inside the headline, the 2 s p50 target leaves ≈ 0.5 s for
  ASR, model and voice; meeting it likely needs a shorter, possibly adaptive end-of-speech tail (owner decision).
  Background work between turns re-decodes the conversation prompt (now avoided on CPU; still true on GPU in Live
  mode). Pronunciation dictionaries for names, ranks and acronyms remain out of scope.
- **Not measured.** No latency target is claimed. This environment could build neither the native runtime (proxy 403
  on the pinned llama.cpp source) nor Piper, and has no model, Whisper model or voice; `--bench-interview` ran as a dry
  run. The owner's hardware runs (docs/BENCHMARK.md) decide every target.
