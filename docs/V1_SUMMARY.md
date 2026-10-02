# Mokuhyo v1 — summary for the owner

Status as of 2026-10-02. Phases 0–7 of BRIEF §11 are complete, each with its gate passing and a checkpoint in
`docs/PROGRESS.md`. **v0.1.0 is published as a pre-release** (never a full release):
https://github.com/budzillaorigin/mokuhyo/releases/tag/v0.1.0 — Windows MSI and portable zip, macOS DMGs for Apple
silicon and Intel, and `SHA256SUMS`.

## What you have
- **App:**
  - A Compose Desktop app for Windows and macOS (Apple silicon and Intel), with embedded llama.cpp and whisper.cpp
    (Metal, Vulkan or CPU) and bundled Piper voices run as a separate process.
  - 11 languages behind one `LanguageModule` contract. Each has a dictionary, segmentation, reading aids and fonts.
  - Reading and Listening practice and timed tests, an OPI-style interview with evidence-based rating, and topic
    conversation.
  - History, a dashboard, a review queue, a PDF report, encrypted `.mokuhyo` backups that merge on import, and an
    opt-in update check.
- **Content** (all AI-drafted, `source = "llm"`, badged, 0 human-verified):
  - Reading and listening passages: 1,574 of the 1,584 target, with 182–226 questions per language and skill.
  - 425 Piper listening clips.
  - Per language: 80 interview questions, 24 role-plays and 99 conversation topics.
  - Details and the commands that close each shortfall are in `docs/CONTENT_STATUS.md`.
- **Models** (`docs/MODELS.md`, all on the rule-13 approved list):
  - Tier A Phi-4-mini, Tier B EuroLLM-9B, Tier C Mistral-Nemo, Tier D Mistral-Small-3.2 (D-021).
  - Whisper small is bundled; Whisper large-v3-turbo is a download (D-022).
- **Installers:** about 1.4–1.5 GB each (runtime, Whisper small, voices, dictionaries, content).
  - macOS arm64 and Intel: built and fresh-install tested here.
  - Windows: cross-built on this Mac (D-026) and verified by you on Windows.
- **Tests and gates:** about 280 Kotlin tests and the Python tool tests. Gates: `gate_core`, `gate_build`, `gate_lang`,
  `gate_exam`, `gate_speaking`, `gate_data`, `gate_content` and `gate_release`, all runnable locally from
  `tools/gates/`.

## Decisions you should look at (`docs/DECISIONS.md`)
1. **D-013 — voice licensing (needs your review).**
   - Most bundled Piper voices are fine-tuned from `en_US-lessac`, whose base data is research-licensed. Whether that
     carries into the fine-tunes is unresolved.
   - I kept them and flagged the question. If you disagree, the affected voices drop out and those languages fall
     back to the OS voice.
   - `de_DE-kerstin` was already excluded (NC lineage).
2. **D-025 — no GitHub Actions, no Linux installer.**
   - CI and release workflows are disabled; all gates run locally.
   - Three-OS CI (CLAUDE.md rule 11) is suspended until you re-enable it.
3. **D-019 — content drafting budget.** The fill ran to about 99% of target; the rest is one command away.
4. **D-017, D-020, D-021, D-022, D-023 and D-024** — interview rating robustness, the exam blueprint, model tiers,
   Whisper packaging, two macOS DMGs, and pt-BR/MSA only.
5. **D-018 — update check.** It reads the GitHub releases list, which stays invisible until the repository is public.
   Making it public is your call.

## Known gaps
- **Dark mode on Windows:** the app is built dark-only (D-027), but your Windows install still showed the light theme.
  This is the first thing to fix in the next update. Check first whether reinstalling the same-version MSI really
  replaced the files.
- **Windows build is cross-compiled** (mingw-w64, wixl). It starts and finds everything, but the GPU (Vulkan) path and
  Piper speech on Windows haven't been exercised yet. `tools/release/build_windows.ps1` is the MSVC alternative.
- **No human review of content.** Every passage, question and interview item is AI-drafted and checked only by a
  second model. Use Settings → About → Developer tools → Content review, then `tools/items/review.py ingest`, to verify
  items.
- **Persian voice:** the TTS→Whisper round trip scores 20–50% because espeak-ng's Persian phonemes are poor
  (Phase 2 known gap). A better Persian voice is a follow-up.
- **No bundled voice for ja, zh-Hans, ko, ar and id** (no redistributable Piper voice exists). These languages use the
  OS voice at run time.
  - macOS has these voices built in.
  - Windows may need the speech pack for the language, which strains the "zero setup" rule. Pre-rendered clips would
    need a voice whose output may be redistributed.
- **Interview quality on Tier B (EuroLLM-9B):**
  - About half of the interviewer turns fall back to the scripted bank, mostly because the model rewords an earlier
    question.
  - The model tends to stay on one topic.
  - Rating agreement on the eval is 0.45 (within one ILR step).
  - Tier C/D models should do better, but haven't been evaluated yet: `tools/models/eval_speaking.py --models tier-c`.
- **Intel Mac** was tested under Rosetta only. It needs a check on real Intel hardware and requires AVX2 (2014 or
  later).
- **Unsigned installers:** SmartScreen and Gatekeeper warn users; `docs/INSTALL.md` explains the workaround.
- **10 exam passages short** (ru, ko, fa, id at ILR 1+–3); see `docs/CONTENT_STATUS.md`.

## Housekeeping
- An interim content-pack pre-release `packs-20261001-1043` exists from a dry run. The release uses
  `packs-20261002-0213` (pinned in `tools/release/packs.lock`). You can delete the interim one.
- Installed on this Mac for the builds: Rosetta 2, Colima and Docker (the Colima VM has been deleted), mingw-w64,
  msitools, vulkan-headers, spirv-headers and shaderc (all Homebrew).
- **D-026 — Windows cross-build (needs your review):** the GCC C++ runtime is linked into the Windows AI library
  under the GCC Runtime Library Exception.
