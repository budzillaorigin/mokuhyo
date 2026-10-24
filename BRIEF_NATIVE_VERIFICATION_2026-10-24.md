# Brief — Native-speaker verification program (tools and workflow)

**Repo:** `budzillaorigin/mokuhyo` · **Base:** `main` at `v0.4.1` or newer.
**Read first:**
- `CLAUDE.md`, especially rules 5, 6 and 7;
- `BRIEF.md` §5 and §7;
- `docs/CONTENT_STATUS.md`;
- `tools/items/review.py` and `review_state.py`;
- `desktopApp/.../ui/ContentReviewScreen.kt`;
- D-042 to D-047.

## Why

Every shipped item in all 11 languages is AI-drafted, about 16,500 items (`source = "llm"`), and **none** is human-verified (`verified = true` count: 0). About 2,250 items show `checkedBy: gpt-oss:20b`, which is a second model, not a person. All audio is synthetic, from Chatterbox, VOICEVOX, Piper or OS voices.

A native Japanese speaker found that the Japanese clips have wrong pitch accent and cadence, which risks teaching errors. The labels are honest, but the content is not yet accurate.

## Owner decisions this brief implements

- **Option A:** all 11 languages keep shipping, as clearly labeled **previews**, while a native-verified **core set** grows per language.
- **The owner designs the tools and workflow. An outside language organization supplies the reviewers and the verification process.** The intended partner is the Air Force Culture and Language Center (AFCLC). Build everything so the partner can run its own process with no developer tools.

## Constraints that shape the design

1. **Reviewers likely use managed government computers.** Assume they can't install software (Mokuhyo's installers are unsigned), can't use GitHub, git or Python, and may have no internet. Review must work **offline in a standard browser (Edge/Chrome) or in Excel**, from a file that arrives by email or shared drive and goes back the same way.
2. **No claim of partner involvement in the app until the owner has the partner's written agreement.**
   - CLAUDE.md rule 7 and the score-screen disclaimer currently say "not affiliated with … AFCLC". **Leave that text unchanged in this phase.**
   - The reviewing organization's display name is **data, not code**: a per-language field that defaults to "a native-speaker reviewer". The owner sets the real name only after approval.
   - Record this as an open owner decision in D-048.
   - Never put "AFCLC" in UI strings, the product name or the bundle ID (BRIEF.md line 5).
3. **Licensing of reviewer contributions.**
   - Corrections written by US Government employees in their official duties are US Government works (public domain).
   - Add a `tools/sources/SOURCES.json` row and a `docs/LICENSES.md` row for "native review corrections", with the partner name left blank, `verbatim_ok = true`, and a note that contractor or volunteer reviewers need a CC BY 4.0 grant instead.
   - The review package and ledger record which basis applies for each reviewer.
4. **Review packages contain only shipped content**: text and audio clips. No learner data, ever.
5. **Rule 5 for reviews:** review records are **append-only facts**. A later review supersedes an earlier one, but never deletes it.

## Work in this order

One change per commit, each with a test, in the repo's `scope: …` style.

### V1 — Review state model and ledger

- Each reviewable unit carries a review status derived from a ledger:
  - `unreviewed`
  - `machine-checked` (today's `checkedBy` model checks)
  - `approved`
  - `approved-with-corrections`
  - `rejected`

  A reviewable unit is a passage with its questions, an OPI question, role-play, topic, exemplar, culture card, pragmatics entry, persona, lexicon term, or prompt clip.
- The ledger lives at `content/reviews/<lang>/<batch-id>.json`, append-only. Each verdict records:
  - the item ID and the **content hash at export** (text plus audio hash);
  - the verdict and a reason code (taxonomy below);
  - the correction, if any;
  - a pseudonymous reviewer ID, the reviewer organization key and the license basis;
  - the date and the batch.

  Never store reviewer real names in the repo unless the partner asks.
- `verified = true` means: the latest verdict for **the current content hash** is `approved` or `approved-with-corrections` (and the correction has been applied). **If the content changes after approval, the item drops back to unreviewed automatically.**
- **Tests:** status derivation, supersession, the hash-mismatch reset, and append-only behavior.

### V2 — Core set per language

- Add `content/packs/<lang>/core.json`: the item IDs that make up the reviewable core. Starting target per language, roughly 150 units:
  - 30 reading and 30 listening passages, balanced across ILR 0+ to 3 and text types;
  - 40 OPI questions plus 8 role-plays;
  - the 60 most-used lexicon terms;
  - all scripted interview prompts used by guided mode.
- A selection tool proposes a balanced core and explains each pick. The owner or partner can edit the file directly.
- **Tests:** balance constraints; every listed ID exists; the gate fails when a core ID is missing.

### V3 — Offline review package (the reviewer's tool)

- `tools/review/export.py --language ja --batch core-1` writes `mokuhyo-review-ja-core-1.zip`:
  - **`review.html`**, a single self-contained offline page with no network and no external scripts. For each item it shows:
    - the text, with furigana/pinyin or a romanization toggle where relevant, plus RTL for ar/fa;
    - the questions and answer key;
    - the ILR level and text type;
    - an audio player for its clip(s).

    The reviewer picks Approve, Approve with corrections (an inline editable copy of the text and answer key), or Reject, plus a reason code and a note. A separate pronunciation verdict covers each clip. Progress saves in the browser and can also be exported mid-way.
  - **"Download my review"** writes `review-result-<lang>-<batch>-<reviewer>.json`.
  - **`review.xlsx` fallback**, text only (no audio), with the same columns, for reviewers whose browsers block local scripts.
  - **`README.txt`**: a plain-language guide covering how to open it, what to check and how to return it.
  - **`audio/`**: the clips, plus `manifest.json` with content hashes.
- The page works from a double-click on `review.html` in Edge and Chrome with networking off. Verify this with a headless browser test that has the network blocked.
- **Reason codes:**

| Code | Meaning |
| --- | --- |
| `grammar` | grammar error |
| `unnatural` | unnatural phrasing |
| `register` | wrong register or politeness level |
| `vocabulary` | wrong or misused vocabulary |
| `terminology` | incorrect military term |
| `culture` | culturally inaccurate |
| `level` | wrong ILR level for the item |
| `key` | wrong answer key, or ambiguous question |
| `pron-accent` | pronunciation: pitch accent or stress |
| `pron-segment` | pronunciation: individual sounds |
| `prosody` | rhythm, cadence or intonation |
| `audio-text-mismatch` | the audio doesn't match the text |
| `other` | anything else, explained in the note |

- **Tests:** the package round-trips; the page loads and produces a result offline; there are no external requests; the XLSX matches the HTML fields.

### V4 — Ingest and apply

- `tools/review/ingest.py <result file> [--xlsx]` does the following:
  - validates the file;
  - **refuses verdicts whose content hash doesn't match** the current item, and lists them as stale;
  - appends to the ledger;
  - applies corrections to the pack source as new content. Because the hash then changes, the corrected item is flagged "approved-with-corrections" through the ledger rule;
  - removes rejected items from the shipped bank (logged; nothing is deleted from history);
  - writes a human-readable diff report: `content/reviews/<lang>/<batch-id>.report.md`.
- If corrected text belongs to an item with audio, mark its clip **stale**. A stale clip must be re-rendered or re-recorded and then pronunciation-approved before it counts as verified.
- Extend or replace `tools/items/review.py ingest` so there's one path, not two. Keep the existing Content Review screen working, or retire it explicitly in D-048.
- **Tests:** applying corrections, refusing stale verdicts, re-ingesting the same file (idempotent), the rejected path, and audio-stale marking.

### V5 — Audio: native recordings and per-clip approval

- `tools/review/recordings.py import` for human recordings that the partner supplies:
  - **input:** a folder of WAV/FLAC files named by clip ID;
  - **checks:** trim, loudness normalization to a documented target, 24 kHz Ogg Opus encoding, and a Whisper alignment check that the words match the script (flag mismatches, don't block);
  - **records:** the speaker's pseudonymous ID, organization, license basis and credit line.
- Recorded clips replace synthetic ones and show "Recorded by a native speaker" plus the credit, where the license requires it.
- `tools/review/recordings.py script` exports a per-speaker recording script (clip ID, speaker role, text, reading aid) as PDF or HTML and CSV, so the partner's speakers can record without the app.
- A synthetic clip counts as verified only with its own `approved` pronunciation verdict for its current hash.
- **Tests:** naming and matching, the alignment-flag path, credits in the clip source label, and stale replacement.

### V6 — The app shows verification honestly

- **Per-language status:** "Native-verified: N of M core items" appears in the language switcher, on Home and in the readiness panel. A language is labeled **Preview** until its core set is 100% verified.
- **Item badges:**
  - "Native-verified" (with the reviewing organization's display name only when that data field is set);
  - "AI-generated · unreviewed";
  - "Corrections pending audio".
- **Audio badges:** "Synthetic voice — pronunciation not native-checked", "Synthetic voice — native-approved", "Recorded by a native speaker".
- **Tests and estimates:**
  - While a language is in Preview, every ILR estimate carries the provisional mark with the reason "content not yet native-verified", using the existing `provisional` path and `SpeakingTrend` labels.
  - Once a language's core is verified, timed tests draw **only** from verified items, and unverified items move to "Extra practice (unreviewed)", which never counts toward estimates.
- **Setting:** "Use native-verified content only", on by default once a core exists. It hides unverified items everywhere for learners who want only checked material.
- The live interviewer always shows the synthetic-voice label, because generated sentences can't be pre-verified.
- **Tests:** badges per state, estimate labeling in Preview, the test draw restricted to verified items, the setting, and the PDF report carrying the same labels.

### V7 — Gates, status and the release rule

- `docs/CONTENT_STATUS.md` (generated by `check_content.py`) gains columns: core size, approved, approved-with-corrections, rejected, stale, unreviewed, and audio verified.
- `gate_content` fails if:
  - a core ID is missing;
  - a `verified = true` item has no matching ledger verdict for its hash;
  - an item badged "Native-verified" has a stale clip.
- Release notes are generated with each language's Preview/Verified state. No language may be described as verified unless the gate says so.
- **Tests:** each gate failure.

### V8 — Workflow documentation for the partner

- **`docs/REVIEW_WORKFLOW.md`**, written for a non-developer partner, covering:
  - roles: the owner prepares batches; the partner assigns reviewers and returns results; the owner ingests and publishes;
  - the batch lifecycle and turnaround;
  - what reviewers receive and send back;
  - the review criteria and reason codes, with examples per language family (pitch accent for ja, tones for zh, vowel marking for ar/fa, and so on);
  - how corrections and recordings flow into a release;
  - licensing bases;
  - data handling: shipped content only, no learner data;
  - how a language moves from Preview to Verified.
- **`docs/REVIEWER_GUIDE.md`**: a two-page guide reviewers can read in 5 minutes.
- **D-048**, recording:
  - every choice made here;
  - the open owner decisions: the partner's written agreement, the display-name policy, and any change to the rule 7 disclaimer wording once there is an agreement.

## Out of scope

- Changing the disclaimer text or naming any organization in the UI.
- Running reviews yourself, or having a model "verify" content. Model checks stay `machine-checked`, never `approved`.
- Re-rendering or re-recording the bank. Build the tools; the partner supplies reviews and recordings.
- Publishing a release.

## Done when

1. V1–V8 are merged-ready, each with tests.
2. A complete dry run with **synthetic** reviewer data passes end to end for one CJK language, one RTL language and one Latin-script language:
   1. export a core batch;
   2. fill in the HTML offline with scripted verdicts;
   3. ingest;
   4. see corrections applied and audio marked stale;
   5. import a synthetic "recording";
   6. see the app's badges and Preview status update;
   7. see the gates pass.
3. The existing suites, gates and the `--smoke --no-network` startup test pass.
4. The PR lists exactly what the owner sends the partner for the first batch, and every open owner decision.
