# Phase 8 summary — current military lexicon and the cultural layer

Phase 8 (`BRIEF_PHASE8.md` Part C, items C-00 to C-12) ran unattended on 2026-10-03/04 and shipped as **v0.2.0**, a
pre-release. Every item has a checkpoint in `docs/PROGRESS.md`. Decisions taken without the owner are D-029 to D-033
in `docs/DECISIONS.md`.

## What was built

| Item | What it is | Gate |
|---|---|---|
| C-00 | Source manifest with four flags, source guard (`limited` rows never read), overlap gate wired into `gate_core` | PASS |
| C-01a | 402 seed terms, 154 priority-1, definitions of record from public US doctrine | PASS, one gap (below) |
| C-01 | `terms_en.json`: every US citation per term across sources and editions | PASS |
| C-02 | Allied term alignment and learner definitions, 11 languages, 4,315 rows | PASS, one gap (below) |
| C-03 | Counter-UAS and base-defense track: terms, examples, drills, scenarios, dialogues, OPI probes, passages | PASS, small shortfalls |
| C-04 | Signed lexicon update channel (Ed25519), import, delta view, review queuing | PASS |
| C-05 | Culture cards citing the AFCLC field guides, every scenario covered | PASS |
| C-06 | Pragmatic flags in conversations; "Cultural appropriateness" in the OPI debrief, outside the ILR scale | PASS |
| C-07 | Pragmatics pack (7 topics) and "implied meaning" inference items | PASS, small shortfalls |
| C-08 | Partner personas: 6 per language (12 for Arabic) | PASS |
| C-09 | "This month" official news links with paste-to-practice | PASS |
| C-10 | Review surfaces and `review.py ingest` for every new content kind | PASS |
| C-11 | Live / After action / Off corrections and the After Action Brief | PASS |
| C-12 | v0.2.0 pre-release | see `docs/PROGRESS.md` |

## Hard rules, as enforced
- **Provenance:** every term carries its US source and page, or `source = llm` with a badge. Allied target terms carry
  source and page only when the checker model confirmed the term names exactly this concept.
- **No copied text:** `overlap_check.py` runs over every shipped string (222,738 fields at the end), 0 failing.
  - Drafted text that failed was redrafted or withheld, never shipped.
  - Official names and designations are on a short, reviewed allow-list.
- **`us-limited/`:** never read, extracted or sent to a model (the guard refuses every purpose; tested).
- **Cultural notes never change a rating:** the OPI rating is one call that never sees them (`PragmaticsGoldenTest`).
- **Models:** drafting on the §7.1 server only, primary `mistral-small3.2:24b-instruct-2506-q8_0` and checker
  `gpt-oss:20b`. No model outside §7.1 was substituted.

## Known gaps (logged with exact commands in `docs/PROGRESS.md`)
1. **C-01a: 78.6% doctrinal definitions** against a 90% target. The rest are modern tactical terms that no public US
   source defines; they have original definitions, badged.
2. **C-02: documented allied equivalents are far below 70%.**
   - Per language: fr 28.6%, pt-BR 25.1%, ja 16.4%, ko 11.9%. The de, id and zh-Hans sources are human-review-only,
     so 1,161 rows sit in `tools/terms/lookup_queue.csv`. es, ru, ar and fa have no allied source.
   - Verification dropped 361 retrieved citations that named a related concept rather than the term itself. A
     confident wrong citation is worse than an honest "unconfirmed term".
3. **Small content shortfalls:**
   - ru track listening 11/12 and fa track reading 17/18.
   - Inference items: 21/24 in zh-Hans, ko, ar and fa.
   - Track extras: Arabic has 11 of 12 openers and 10 of 12 OPI probes; Persian has 7 of 8 dialogues.
4. **Everything new is AI-drafted and unreviewed.** It is badged in the app; Content Review and `review.py ingest`
   are ready for a reviewer.

## Problems found and fixed on the way (each with a regression test)
- Five French definitions too close to AAP-06: redrafted with the shared run named; failing text is now withheld.
- Glossary-retrieval picks that were related entries, not the concept: the new `align_terms.py verify` stage.
- OPI probes all dropped because the model writes "ILR 2"; openers and dialogues now retry and keep partial results.
- Track examples went stale when a term changed: they are now keyed by term.
- `overlap_check.py` crashed callers when there was no text to check.
- English glosses inside Spanish, Portuguese, German and Indonesian example lines are stripped. The app's Spanish
  language check no longer reads the preposition "a" as English.

## Next
Phase 9 (`BRIEF_PHASE8.md` Part D) continues on the `phase9` branch, starting with the owner's new items N-00
(natural voices and the Windows OS-voice fix) and N-00b (speaking reliability). It stops after v0.3.0.
