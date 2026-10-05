# Lexicon maintenance

How the Counter-UAS & Base Defense term list stays current (BRIEF_PHASE8 §B.6, P-04, N-09). Cadence and ownership are
the owner's decision (P-04); until recorded otherwise the default below applies.

## Cadence (default: quarterly)
Every quarter — January, April, July, October — and whenever the Joint Staff publishes a new DoD Dictionary:

1. **Get the new edition.** Put the PDF in `tools/sources/us/`, add or update its row in `tools/sources/SOURCES.json`
   (sha256, edition, `verbatim_ok`/`alignment_ok`/`machine_extract_ok`), add its row to `docs/LICENSES.md` in the same
   commit, and run `uv run python terms/overlap_check.py index`.
2. **Diff it against the term list.**
   ```
   cd tools
   uv run python terms/refresh.py --new us-dod-dict-<yyyy-mm> --old-id us-dod-dict-2026-08 --out refresh-review.json
   ```
   The review file lists **changed** definitions (old and new text), **deprecated** terms (no longer defined) and
   **candidates** (new terms in the list's domains). Nothing is changed automatically.
3. **Curate.** Edit `tools/terms/seed_catalog.py` (add, retire or re-point terms), rebuild the seed list and the English
   provenance (`terms/build_seed_terms.py`, `terms/extract_terms.py`), then align and draft the new or changed rows
   (`terms/align_terms.py all` — it only redrafts rows whose term or definition changed) and rebuild the tracks
   (`tracks/build_track.py all`).
4. **Gate.** `tools/gates/gate_terms.sh` (validators + overlap check) and `tools/gates/gate_content.sh`.
5. **Publish an update.** `uv run --group release python release/lexicon.py build --language <lang> --version <x.y.z>
   --previous <prev>` for each language, `verify`, then attach the files to a GitHub Release (docs/LEXICON_FORMAT.md).
   Learners import them from Settings → Content; the next app release bundles the same track.

## Contributions
- Proposals for new terms: a pull request to `tools/terms/seed_catalog.py` with a citation (source document and page),
  or a learner's "Suggest a term" note (Lexicon screen) exported from Settings → Content and queued with
  `uv run python items/review.py suggestions <file>`.
- Corrections to a target-language term or definition: a reviewer verdict through the in-app Content Review
  (`items/review.py ingest`), or a flag from a learner.
- Never add text from a `verbatim_ok = false` source, and never anything from `tools/sources/us-limited/`.

## Reminder
Add a recurring calendar reminder for the first week of each quarter titled "Mokuhyo lexicon refresh" pointing at this
file. The app does not remind anyone (it never contacts a server on its own).
