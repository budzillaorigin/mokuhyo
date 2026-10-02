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

## D-013 Languages without a bundleable voice; base-model lineage (unattended default — OWNER REVIEW)
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

## D-026 Windows installer cross-built on macOS (owner request, 2026-10-02; OWNER REVIEW of the runtime licenses)
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
