# Term pipeline: US definition → target-language term → drafted definition → checks → approval

Status: adopted 2026-10-03. Applies to every military term and definition shipped in a `LanguageModule`.

## Why it is shaped this way

- **US doctrine is public domain.** English definitions come from it and may be copied word for word.
- **Allied sources are mostly not reusable as text.** Their licences bar copying, modification or bulk extraction (see `tools/sources/SOURCES.json`). They are used to *choose and confirm* the target-language term and meaning, never as wording.
- **Models reproduce short text.** A definition drafted with a glossary in the prompt can come back near-verbatim. A mechanical overlap check is the guard, not a prompt instruction alone.
- **Part B rule:** no unmarked invented term. Anything a model proposes without a source stays badged until a human accepts it.

## Source rights fields (SOURCES.json)

| Field | Meaning | Gate use |
|---|---|---|
| `verbatim_ok` | `true` = text may be copied into shipped content | `false` sources are indexed by `overlap_check.py` |
| `alignment_ok` | `true` = may be used to choose terms and confirm meaning | `validate_alignment.py` requires it for `term_source_id` |
| `machine_extract_ok` | `true` = may be parsed / fed to an LLM in bulk; `false` = human review only; `unclear` = treat as human review | decides who does step 4a |

As of 2026-10-03: `verbatim_ok = true` only for US Government sources. `machine_extract_ok = true` for US sources (except `us-limited/`), AFCLC guides, Japan white papers (Japan Copyright Act Art. 30-4), Brazil MD35-G-01, and - cleared by Buddy 2026-10-03 - the ROK 2022 white paper (KO/EN), France RNS 2025 (FR/EN) and NATO AAP-06/NATOTerm. `false` for BMVg (de), UK JDP 0-01.1, UNTERM and the limited JPs. `unclear`: Indonesia and Taiwan.

## The five steps

### 1. English source (seed list)
- Each row of `seed_terms.csv` takes `term_en` and `definition_en` from public US doctrine: DoD Dictionary (Aug 2026) first, then ATP 3-01.81, JP 3-01, JP 3-10, AFDP 3-10, ATP 1-02.1 (brevity).
- Copy the definition verbatim; fill `source_doc` and `source_page`.
- If no US source defines the term, write the definition yourself and set `source_doc` to `authored`.

### 2. Choose the target term (per language)
- Look the concept up in that language's alignment sources (`allied/<lang>/`, AAP-06 for fr, MD35-G-01 for pt-BR, the Taiwan MND dictionary for zh, the Japan white-paper index for ja).
- Record `term`, `term_source_id`, `term_source_page` in `tools/terms/term_alignment.csv`.
- For sources with `machine_extract_ok = true`, a model may search the extracted text and propose the term + page. A human confirms anything it is unsure of.
- For `false` / `unclear` sources, a human does the look-up.
- No source has it → the model proposes a term, `term_source_id` stays empty, `badge = unconfirmed-term`.
- Only the term (a word or short name) and a page reference are taken from the source. Never copy its definition.

### 3. Draft the definition
- Prompt: `tools/terms/prompts/draft_definition.md`.
- Input: the US `definition_en` (meaning of record) and the step-2 term.
- Reference excerpts may be included only from `machine_extract_ok = true` sources, labelled "terminology check only".
- Output goes into `definition`; `status = draft`, `badge = unreviewed` (or `unconfirmed-term`).
- If the model raises `TERM-CONCERN`, route the row back to step 2 and leave it badged.

### 4. Check
**4a Accuracy.** Does the definition match the US meaning and the way the allied source uses the term?
- `machine_extract_ok = true`: model check with `prompts/check_accuracy.md`. `escalate` goes to a human.
- Otherwise: a human reads the cited page.

**4b Overlap (mandatory, mechanical).**
```
python tools/terms/overlap_check.py index          # once, and after adding sources
python tools/terms/overlap_check.py check tools/terms/term_alignment.csv
```
- Fails on any run of ≥ 8 shared words (≥ 20 shared characters for ja/zh/ko) with a `verbatim_ok = false` source.
- Runs that also appear in a public-domain source report `ok-PD` and pass, since NATO/allied glossaries often repeat DoD wording.
- On FAIL: redraft (step 3). Do not tune thresholds or add to `overlap_allow.txt` to get a row through; the allow list is for official names only.

Rows that pass 4a and 4b move to `status = checked` (badge stays).

### 5. Human approval
- A reviewer reads term + definition, sets `status = approved`, fills `approvedBy`, clears `badge`.
- Approving a model-proposed term (no `term_source_id`) also requires `notes` saying why it was accepted.
- Only `approved` rows ship without a badge. `draft`/`checked` rows may ship but must show their badge in the app.

## Gates (CI / pre-commit)

```
python tools/terms/validate_alignment.py            # schema, provenance, badge rules
python tools/terms/overlap_check.py check tools/terms/term_alignment.csv
```
Run the overlap check on any other generated content too (drills, dialogues, culture cards):
```
python tools/terms/overlap_check.py check path/to/content.jsonl --fields text,example
```
Culture cards are checked against the AFCLC guides this way (they are `verbatim_ok = false`).

## Housekeeping

- `tools/sources/.cache/` (extracted text) is for the overlap check only. Never commit it, never ship it.
- Source PDFs total ~620 MB. Do not commit them to plain git; `SOURCES.json` keeps URL + sha256 so they can be re-fetched. Use Git LFS or keep them outside the repo and point `tools/sources/` at them.
- When a source is replaced (e.g. DoD Dictionary Aug 2026), update its row (sha256, edition) and re-run `index`.

## Releasability-controlled doctrine (`us-limited/`)

Some current JPs pulled from JEL+ are marked LIMITED release or not for public release (JP 3-01 2023, 3-12, 3-16, 3-85, 5-0, 6-0). They are US Government works, so copyright is not the issue - distribution is. SOURCES.json marks them `distribution: "limited"` with all three rights fields false. They live in git-ignored `tools/sources/us-limited/`, are skipped by `overlap_check.py`, are never fed to an LLM or cloud service, and their text is never shipped. Use the public DoD Dictionary (Aug 2026) for any term they define.
