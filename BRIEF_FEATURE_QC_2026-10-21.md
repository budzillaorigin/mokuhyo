# Brief: full feature QC of Mokuhyo v0.4.0 (for ChatGPT Codex)

**Repo:** `budzillaorigin/mokuhyo` · **Review target:** `main` at `8bbbe8f` or newer, which is the `v0.4.0` pre-release tag.
**Read first:** `CLAUDE.md` (the 13 non-negotiables), `BRIEF.md` §2 and §9, `BRIEF_PHASE8.md`, `docs/QA.md`, `docs/RELEASE_NOTES.md`, and D-042 to D-045 in `docs/DECISIONS.md`.
**Earlier QC reports:**
- `docs/QC_REPORT_2026-10-19.md`
- `docs/QC_REPORT_2026-10-19_FOLLOWUP.md`
- `docs/QC_REPORT_2026-10-19_CURRENT.md`

Their findings (F1–F4, C1–C7) are fixed and merged. Re-check them, but don't re-report a fixed item unless you can reproduce it on the current code.

## Goal

Check **every user-facing feature** in v0.4.0 and record whether it works. This review covers the whole app. Earlier QC passes looked only at speech, and their findings are already fixed.

The deliverable is a feature-by-feature status matrix, findings with reproduction steps, and a prioritized fix brief that Claude Code can carry out.

## Ground rules

- **Do not change application code, content packs, models, manifests, lockfiles or workflows.** This is a review. Your output is documentation, screenshots and small diagnostic scripts only.
- **Deliver through GitHub.** Last time, Codex's files stayed in its private `/workspace`, and the owner had to ask for them.
  - Commit to a branch named `codex/feature-qc-2026-10-21`.
  - Open a pull request against `main`. Don't merge it.
  - Give the owner the PR link and the head commit hash.
- **Be precise about what you tested.** Give every check exactly one of these statuses:

| Status | Meaning |
| --- | --- |
| **Works** | Exercised end to end. Say how. |
| **Partial** | Works with a defect or a gap. Name it. |
| **Broken** | Reproducible failure, with repro steps. |
| **Not testable here** | Needs hardware, models, voices, a GUI or a network you don't have. Say exactly what's missing. |
| **Code-reviewed only** | Read the source; didn't run it. |

  Mocks and headless renders prove control flow and layout, not real-device behavior. Never mark a speech or latency feature **Works** from mocks alone.
- **Honor the product rules while reviewing.** A feature that "works" but violates a non-negotiable is a finding. Examples:
  - a score screen without the unofficial-practice disclaimer;
  - AI content without its "unreviewed" label;
  - a network call in `--no-network` mode;
  - a learner record that can be overwritten.
- **Use only synthetic learner data.** Never put real recordings, transcripts or personal data in the repo.
- **Respect licensing rules.** Never read or commit any `distribution = "limited"` source from `tools/sources/SOURCES.json` (rule 6). Never use official DLPT/OPI/DLI/ACTFL/LEAP material.

## Environment setup

1. Clone the repo and check out `v0.4.0`. Record the exact commit.
2. Try to build the native CPU library (`native/build.sh cpu`) and Piper (`voices/build.sh`). If downloads are blocked, record the exact error and continue with `--allow-no-native`.
3. If you can download models, use **only** ones listed in `content/models/manifest.json`:
   - Tier A `phi-4-mini-instruct-q4km`;
   - `whisper-small`;
   - one Piper voice.

   A real Tier A model plus Whisper turns many "Not testable" rows into real results. Try it before giving up.
4. Also try the published v0.4.0 artifacts. The repo is private, so use `gh release download v0.4.0 --repo budzillaorigin/mokuhyo`. Get the Linux portable `.tar.gz` and `.deb`, and verify them against `SHA256SUMS`.
5. Run the standard gates and record their results:
   - `tools/gates/gate_core.sh`
   - `gate_content.sh`
   - `gate_data.sh`
   - `gate_exam.sh`
   - `gate_lang.sh`
   - `gate_speaking.sh`
   - `gate_terms.sh` with `MOKUHYO_NO_SOURCES=1`
   - `./gradlew :desktopApp:run --args='--smoke --no-network'`

## Known issue to investigate first

The v0.4.0 Release workflow (run `39355829686`) built every installer and published them. In that run:
- `fresh install (linux-x64)` **failed with exit code 100**, which usually means an `apt-get`/`dpkg` dependency failure installing the `.deb`.
- The Windows, macOS arm64 and macOS x64 fresh installs passed.

Reproduce it with `tools/gates/fresh_install_smoke.sh` on Ubuntu 22.04 if you can, and find the cause. Linux isn't an official release target (D-025), so rate the severity accordingly, but name the fix.

## Feature checklist

Cover every row and add any feature you find that isn't listed. For each feature, check that it:
- does what the release notes say;
- handles empty, error and cancel paths;
- shows the honest labels rule 7 requires;
- keeps working offline.

Check all **11 languages** where content differs: `ja es fr de pt-BR ru zh-Hans ko ar fa id`. At minimum, spot-check one CJK language, one RTL language and one Latin-script language end to end.

### 1. Install, first run and shell
- Installers and portable builds start. `--smoke` passes on the packaged app.
- First-run flow: welcome → languages → hardware scan → tier choice (with N2's timing check) → audio check → Home.
  - "Reading/Listening only for now" skips the model and the app still works.
- Nine-item navigation rail: Home, Reading, Listening, Speaking, Lexicon, Review, History, Report, Settings.
  - The rail scrolls at short heights.
  - The language switcher in the title bar changes everything, and each language keeps its own data.
- Window at 1024×700, and at 150%/200% scaling. Dark mode everywhere (D-027).
- The update check is off by default.

### 2. Home
- An ILR estimate per modality, with its trend and provisional `*` marks.
- The next recommended activity and the review queue count.
- The empty state for a new learner.

### 3. Reading (practice and test)
- Practice by ILR level and text type, with feedback and an explanation per item.
- Tap-to-define with mouse and keyboard; furigana/pinyin; RTL for ar/fa; "add to review".
- Authentic-format reading: signs, badge forms, phone chats, shift logs, notices, schedule boards.
- Timed tests in 30-min, 60-min and full (180-min, 60-item) forms:
  - the timer works;
  - quitting mid-test and relaunching offers to resume;
  - scoring gives an ILR estimate with the disclaimer;
  - short or incomplete forms are marked provisional.
- "Generate more": the passage is labeled "Generated on this computer" and kept out of estimates and shipped-bank statistics.

### 4. Listening (practice and test)
- Practice: transcript reveal after answering, replay, speeds 0.8/0.9/1.0× with pitch kept (WSOLA), dictation drill.
- Test: plays allowed per the blueprint; a play counts only once audio starts; failed plays are given back.
- Recovery: Try again / Leave out for damaged or missing clips, and voided passages make the result provisional.
- Degraded-audio practice. Confirm it's refused in test mode.
- Clip source labels: pre-rendered (Chatterbox/VOICEVOX credits) vs voice service vs OS voice.
- Numbers-under-stress drill.

### 5. Speaking
- **Interview practice and interview test (OPI simulator).**
  - Phases: warm-up → level checks → probes → role-play → wind-down.
  - Readiness preflight before Start (N4).
  - The question-delivery gate in tests (F2).
  - Cancel/Retry/End at every stage (C2).
  - Checkpointing and recovery after interruption (C3).
  - Guided fallback when a turn misses its deadline, and the "guided" label (N5).
  - Text-practice fallback.
  - Rating with evidence quotes and a self-rating when no model is present.
  - `SpeakingTrend` labels in results, History and the PDF (F4).
- **Topic conversation:** pick or type a topic. The reply is spoken before feedback (C4). Corrections modes Live / After action / Off. After Action Brief.
- **Scenarios and role-plays** with personas: six per language, twelve for Arabic. Cultural notes are labeled "not part of the ILR scale".
- **Interpretation drill:** consecutive, radio relay, sight translation.
- **Exercise-week storyline** (the counterpart remembers you) and **daily stand-to**.
- **Exemplar answers** at ILR 1+, 2 and 3.
- **Voice capture:** mic picker, level meter, VAD auto-stop, starting to speak immediately (C5), confirm/edit/retake the transcript, and responsiveness while recording (C6).
- **No-model mode:** Live corrections disabled, questions described as scripted.

### 6. Lexicon
- The counter-drone/base-defense track: terms, definitions, examples, "unconfirmed term" labels, drills.
- Signed term-package import from a file or link: signature check, change list, and refusal of a tampered package.
- "This month": official news links per language and a paste-to-practice reader. Nothing is fetched automatically.
- Side-by-side terms.

### 7. Review
- FSRS queue per language: grading, scheduling, and the empty-queue state (no focus warning).
- Items arrive from reading misses, look-ups and lexicon drills.

### 8. History
- Every attempt and conversation, searchable and filterable.
- Recordings play back.
- Interrupted interview snapshots show up.
- Delete is a tombstone: the record is hidden, not destroyed (rule 5).

### 9. Report (PDF)
- Export with the date range and languages.
- Fonts render in all 11 scripts. Check with extracted text and rendered pages.
- The disclaimer, provisional footnotes, speaking labels and After Action Brief sections.

### 10. Backup (`.mokuhyo` bundle)
- Export and import, optionally encrypted.
- Merge without duplicates or overwrites.
- Bundles from older versions import. Use or create fixtures if possible.
- Interrupted-session checkpoints survive a round trip.

### 11. Settings
- **AI:**
  - the tier picker with the timing check and its stated reason;
  - "Slower — better for end-of-session grading" labels;
  - "Use the CPU";
  - "Use my Ollama" — PRC-origin and Gemma/Llama models greyed out with a reason (rule 13);
  - thread-cap overrides and the turn-deadline setting;
  - "Test the model".
- **Speech and audio:**
  - input and output devices, voices per language, "Rescan voices";
  - "Play test voice" goes through the same path as interviews;
  - the per-language readiness panel;
  - Diagnostics: timing report and export. Confirm the export contains **no transcript text or audio**.
- **Content:** lexicon updates and content review screen.
- **Privacy:** an explanation of every network call.
- **Updates:** off by default.

### 12. Cross-cutting
- **Offline:** with `--no-network`, every screen works and no connection is attempted. Prove this with a network monitor or a blocked-socket test, not just by reading code.
- **Honest labels:** every score screen carries the unofficial-practice disclaimer; AI content is badged "unreviewed"; generated items are kept separate (rule 7).
- **Accessibility:**
  - keyboard-first use: number keys for choices, space to play/pause, Enter to submit;
  - screen-reader labels on controls;
  - focus order.
- **RTL and fonts:** ar/fa content is RTL while the app chrome stays LTR; no tofu (blank boxes) in CJK on any screen.
- **Data safety:** confirm learner records are append-only. Look for any path that overwrites or deletes ILR estimates, attempts, recordings or SRS state.
- **Security profile:** the SBOM ships with the release. See `docs/SECURITY_PROFILE.md` and verify its claims.
- **Content gates:** strict validation of all 22 language/skill banks, the license/provenance/model-doc gates, and the overlap check (skipped without sources, so record that).
- **Developer CLI tools:** each runs or fails gracefully with a clear message.
  - `--smoke-opi`
  - `--smoke-lang`
  - `--roundtrip`
  - `--bench-interview`
  - `--prompt-audio`
  - `--listening-kit`
  - `--screenshots`

## Deliverables (all in the PR)

1. **`docs/QC_REPORT_2026-10-21_FEATURES.md`**, containing:
   - **Summary:** an overall verdict on whether v0.4.0 is fit for the owner's own hardware testing, and what would block a wider pre-release.
   - **Feature matrix:** one row per checklist item, with columns for Area, Feature, Status (from the five above), Evidence (command, test, screenshot or file:line) and Finding ID.
   - **Findings** numbered `Q1, Q2, …` and ordered by severity:
     - **P0:** data loss, a false score, or a rule violation.
     - **P1:** a feature broken for normal use.
     - **P2:** a degraded feature or a missing label.
     - **P3:** polish.

     Each finding needs file:line evidence, exact reproduction steps, the expected result, the actual result, a recommended fix and an acceptance test.
   - **Not testable here:** everything that needs real hardware, models or voices, folded into a checklist the owner can run on a Mac or Windows laptop. Don't duplicate `docs/BENCHMARK.md`; link to it.
2. **`docs/qc/2026-10-21-features/`**, containing:
   - screenshots (at least Home, Reading, Listening, Speaking, Lexicon, Review, History, Report and Settings, plus one RTL and one CJK screen);
   - `EVIDENCE.md` with commands and results;
   - small logs. Keep raw logs short, and summarize long ones.
3. **`BRIEF_FEATURE_FIXES_2026-10-21.md`** at the repo root: a coding handoff for Claude Code in the style of `BRIEF_INTERVIEW_READINESS_2026-10-19.md`.
   - Findings are in priority order.
   - Each finding is one fix, one commit and one regression test that fails on the current `main`.
   - Acceptance criteria for each finding.
   - An explicit out-of-scope list.
4. **PR description:** summarize the verdict, the number of findings by severity, the counts of Works / Partial / Broken / Not testable, and the environment limits.

## Done when

- Every checklist row has a status and evidence.
- Every Broken or Partial row has a numbered finding with a repro.
- The Linux fresh-install failure has a stated cause or a clear "couldn't reproduce because…".
- The three deliverables are pushed in one PR against `main`, and the owner has the link.
