# Mokuhyo handoff: Part B.1 sources and term pipeline (2026-10-03)

For: the Claude session working in the Mokuhyo repo.
From: a separate Cowork session that did the Part B.1 source acquisition and set up the term pipeline with Buddy.
Everything below is on Buddy's desktop PC under `Documents\Claude Output\mokuhyo-sources\`. The folder mirrors repo paths (`tools/…`, `docs/…`), so it can be copied into the repo root.

---

## 1. What was delivered

```
mokuhyo-sources/
├── docs/TERM_PIPELINE.md              ← the adopted pipeline (read this first)
└── tools/
    ├── sources/
    │   ├── SOURCES.json               ← 56 rows: 42 acquired, 14 manual/unavailable/excluded
    │   ├── .gitignore                 ← ignores .cache/
    │   ├── .cache/text/               ← extracted text for the overlap check (do not commit)
    │   ├── us/        (11 PDFs)       ← US doctrine, public domain
    │   ├── nato/      (2 PDFs)        ← AAP-06 2019 EN/FR, UK JDP 0-01.1 2025
    │   ├── allied/<lang>/ (18 PDFs)   ← ja ko de fr pt-BR id zh-Hant
    │   └── culture/afclc/ (11 PDFs)   ← AFCLC culture guides, 2025 editions
    └── terms/
        ├── overlap_check.py           ← verbatim-overlap gate (tested)
        ├── validate_alignment.py      ← term_alignment.csv schema/provenance gate (tested)
        ├── term_alignment.csv         ← empty, header only
        ├── overlap_allow.txt          ← empty allow list (official names only)
        └── prompts/
            ├── draft_definition.md    ← step 3 prompt
            └── check_accuracy.md      ← step 4a prompt
```
Total ~620 MB, almost all PDFs. Python 3 standard library only; text extraction uses poppler `pdftotext` (falls back to `pypdf` if installed).

## 2. Sources at a glance

| Area | Acquired | Notes |
|---|---|---|
| US doctrine | DoD Dictionary June 2025 (JSOU copy) + Nov 2021; JP 3-01 (2017) and JP 3-10 (2019) via FAS mirror; ATP 3-01.81 (Aug 2023); ATP 1-02.1 brevity (Jul 2024); AFDP 3-10 + key changes; DoD C-sUAS Strategy 2021; AF doctrine advisories on Point Defense of Air Bases (Apr 2025) and Control Below the Coordinating Altitude | jcs.mil no longer links PDFs publicly. Aug 2026 Dictionary and current JPs are on JEL+ (CAC) - Buddy pulls those manually. AFI 31-101 excluded (CUI). |
| NATO / UK | AAP-06 2019 (EN/FR), UK JDP 0-01.1 2025 | Newer NATO terms live only in NATOTerm (no bulk export). |
| ja | Defense of Japan 2026 JA full + index + EN digest; 2025 JA full + EN full | 2025 is the clean JA/EN parallel pair. |
| ko | ROK Defense White Paper 2022, KO + EN | Newest; 2026 edition due ~Dec 2026. |
| de | Weißbuch 2016 (DE); Verteidigungspolitische Richtlinien 2023 DE + official EN | No public Bundessprachenamt glossary. |
| fr | Revue nationale stratégique 2025 FR + EN; AAP-06 | |
| pt-BR | MD35-G-01 Glossário das Forças Armadas, 5th ed. | Official glossary with definitions. |
| id | Buku Putih Pertahanan 2015 ID + EN | |
| zh | Taiwan MND American English–Chinese Military Dictionary (932 pp., Traditional); ROC National Defense Report 2025 ZH + EN | Simplified needs conversion + PRC-usage review. |
| es, ru, ar, fa | none (no usable public bilingual source found / Russian MoD site unreachable) | Part B fallback applies: model proposes, badged until reviewed. UNTERM usable for single look-ups only. |
| Culture | AFCLC guides: Japan, South Korea, Mexico, Brazil, Russia, Saudi Arabia, Iraq, Iran, Indonesia, China, Taiwan | AFCLC has no guide for Germany, France, Spain. CIA World Factbook was discontinued Feb 2026. |

Every acquired row has: `path`, `url`, `official_url` (where different), `edition`, `lang`, `bytes`, `sha256`, `pages`, `retrieved`, `license`, `mirror`, `notes`. Non-acquired rows have `status` = `manual-cac`, `manual`, `not-found`, `unreachable`, `unavailable`, `excluded` or `not-acquired`, with notes saying why.

## 3. Licensing conclusions (decided with Buddy)

Several sources turned out stricter than Part B assumed: the Japan white paper is excluded from MOD's open licence; AFCLC guides forbid modification; UK JDP is UK-government-use only; MD35-G-01 is CC BY-ND; UNTERM is personal/non-commercial. Korea, France and NATO terms are unverified (`VERIFY` in the licence text).

The working principle Buddy and this session settled on:
- **Copyright protects wording, not facts.** Single terms and term equivalents are facts. Writing original content informed by these sources is fine.
- **What is not fine:** shipping a source's sentences or definitions verbatim or near-verbatim, its images, or a bulk copy of its glossary. Volume matters: ~300 copied definitions is copying the substance of a glossary.
- **LLMs do reproduce short source text** when it is in the prompt, especially definitions. So a mechanical overlap check is required; a prompt instruction alone is not enough.
- Neither party is a lawyer; if Mokuhyo goes commercial, Buddy plans to get a short IP consult.

To make this enforceable, three fields were added to every SOURCES.json row (definitions also in `field_notes` at the top of the file):

| Field | Values | Meaning |
|---|---|---|
| `verbatim_ok` | true / false / null | May its text be shipped? `true` only for US Government sources. |
| `alignment_ok` | true / false | May it be used to choose terms and confirm meaning? `true` for all acquired sources. |
| `machine_extract_ok` | true / false / "unclear" | May it be parsed or fed to an LLM in bulk? `true`: US, AFCLC, Japan (Copyright Act Art. 30-4), Brazil. `false`: BMVg (terms bar automated extraction), UK JDP, UNTERM. `unclear`: ko, fr, id, zh, NATO - treat as human review. |
| `use` | text | One-line plain-English rule for that source. |

**Suggested CLAUDE.md rule 6 update** (rule 6 currently gates on the single `license` field): gate shipped text on `verbatim_ok`, gate term provenance on `alignment_ok`, and gate what the drafting model may be fed on `machine_extract_ok`. Keep `license` as the human-readable record. Buddy has not yet seen the exact wording; propose it to him before editing CLAUDE.md.

## 4. The term pipeline (full spec: `docs/TERM_PIPELINE.md`)

1. **English source.** `seed_terms.csv` takes `term_en` + `definition_en` verbatim from US doctrine (DoD Dictionary first, then ATP 3-01.81, JP 3-01/3-10, AFDP 3-10, ATP 1-02.1). Public domain, so copying is fine.
2. **Choose the target term** from that language's allied source *before* drafting; record `term_source_id` + `term_source_page` in `tools/terms/term_alignment.csv`. Model may do the look-up only in `machine_extract_ok = true` sources; otherwise a human does. No source → model proposes, `badge = unconfirmed-term`.
3. **Draft the definition** in the target language from the US English definition using `prompts/draft_definition.md`. Allied excerpts allowed in the prompt only from `machine_extract_ok = true` sources, labelled terminology-only. `status = draft`.
4. **Check.** 4a accuracy vs. US meaning and allied usage (`prompts/check_accuracy.md` for machine-ok sources, human otherwise). 4b `overlap_check.py` - mandatory. Pass both → `status = checked` (badge stays).
5. **Human approval.** Reviewer sets `status = approved`, fills `approvedBy`, clears the badge. Approving a model-proposed term also needs `notes`.

Unapproved rows may ship only with their badge visible in the app (Part B: no unmarked invented term).

## 5. Scripts

Run from the repo root (paths are resolved relative to the script, so `tools/` must sit at the repo root).

```
python tools/terms/overlap_check.py index                     # extract text of all acquired sources (~1-2 min first time; cached by sha256)
python tools/terms/overlap_check.py check FILE [FILE...]      # .csv / .jsonl / .json; needs `lang` + text columns
        [--fields definition,example,text] [--word-n 8] [--char-n 20] [--json report.json]
python tools/terms/validate_alignment.py [tools/terms/term_alignment.csv] [--seeds seed_terms.csv]
```

**overlap_check.py**
- Compares each item only against sources in the same language family (zh-Hans/zh-Hant share `zh`).
- Fails on ≥ 8 consecutive shared words, or ≥ 20 consecutive shared characters for ja/zh/ko, with any `verbatim_ok = false` source.
- A matching run that also appears in a `verbatim_ok = true` (public-domain) source is reported `ok-PD` and passes. Needed because AAP-06 English repeats DoD definitions word for word.
- `overlap_allow.txt` = official names only; never use it to push a failing row through.
- Exit 0 clean, 1 overlap found, 2 setup error. Default fields: definition, definition_en, example, text, prompt, answer, explanation.

**validate_alignment.py** enforces: unique (seed_id, lang); known lang; `term_source_id` exists, is `alignment_ok`, covers the row's language, and has a page; badge/status/approvedBy consistency; definition present unless draft; seed_id exists in `seed_terms.csv` when that file is found (looks in repo root, `tools/terms/`, `tools/`).

**Tested 2026-10-03 on the real sources (all 42 indexed):**
- Copied AAP-06 FR text → FAIL (12–14 words). Copied Japan white-paper span → FAIL against both 2025 and 2026 editions (30 chars). Copied AFCLC Japan sentence → FAIL (12 words).
- AAP-06 English run that also appears in the DoD Dictionary → `ok-PD`, passes.
- Original pt-BR, ja and en text → clean. A 6-item check runs in ~3 s.
- Validator: 10 seeded errors all caught (missing page, duplicate, wrong-language source, approved-without-reviewer, missing badge, bad lang); valid file and empty template pass.

## 6. Integrating into the repo - to do

1. Copy `docs/` and `tools/` from `mokuhyo-sources` into the Mokuhyo repo root. Merge with any existing `tools/` contents; nothing here should overwrite existing files except possibly `tools/sources/`.
2. **Do not commit the PDFs to plain git** (~620 MB). Options: Git LFS for `tools/sources/**/*.pdf`, or keep PDFs outside the repo and commit only `SOURCES.json` (URL + sha256 make them re-fetchable). Ask Buddy which; he is new to git, so give exact commands one step at a time with what each does.
3. Make sure `tools/sources/.cache/` stays ignored (the included `.gitignore` does this) and is excluded from any build/installer.
4. Wire both scripts into the existing gates / CI next to the seed_terms.csv validator.
5. Propose the CLAUDE.md rule 6 change (section 3) to Buddy.
6. If Part B's seed_terms.csv validator lives elsewhere, point `validate_alignment.py --seeds` at it or move the CSV to one of the default locations.

## 7. Open items for Buddy (not blocking)

- ~~Pull from JEL+ with CAC: DoD Dictionary Aug 2026, current JP 3-01 and JP 3-10.~~ Done - see section 8.
- Verify by hand: KOGL type on the ROK white paper, SGDSN's conflicting terms (diffusion@sgdsn.gouv.fr), NATO NSO terms for AAP-06.
- es / ru / ar / fa have no allied term source: expect those rows to stay `unconfirmed-term` until a reviewer accepts them.

## 8. Update (later 2026-10-03): JEL+ pulls filed, and a releasability rule

Buddy pulled eight documents from JEL+ with his CAC. They are filed as follows (SOURCES.json now has 62 rows: 48 acquired, 2 superseded, 12 pending):

| File | Where | Status |
|---|---|---|
| DoD Dictionary, **August 2026** | `us/DoD_Dictionary_2026-08.pdf` | Public, no restriction stated. **Now the primary source for `definition_en`.** June 2025 kept as a diff baseline. |
| JP 3-10 (25 Jul 2019, validated 6 Aug 2021) | `us/JP_3-10_2019-07-25_validated-2021-08-06.pdf` | Official copy; doc says unclassified JPs are unrestricted. Replaces the FAS mirror (moved to `us/_superseded/`, row `us-jp-3-10-fas`). |
| JP 3-01 (6 Apr 2023, Ch 1 13 Mar 2024) | `us-limited/` | **LIMITED release - CAC holders only.** |
| JP 3-12 Cyberspace Ops (2022) | `us-limited/` | LIMITED release. |
| JP 6-0 Joint Communications (2023) | `us-limited/` | LIMITED release. |
| JP 5-0 Joint Planning (2025) | `us-limited/` | **Not for public release.** |
| JP 3-16 Multinational Considerations (Sep 2026) | `us-limited/` | Release governed by a JEL+ memo; treated as not public. |
| JP 3-85 Joint EMS Ops (Mar 2026) | `us-limited/` | Release governed by a JEL+ memo; treated as not public. |

**New rule - releasability is separate from copyright.** These JPs are US Government works (no copyright), but their distribution is controlled. For them SOURCES.json sets `distribution: "limited"`, `verbatim_ok: false`, `alignment_ok: false`, `machine_extract_ok: false`. That means:
- `us-limited/` is git-ignored (added to `tools/sources/.gitignore`). Never commit it to the repo, especially a public one.
- Never ship their text, and never upload them or feed them to a cloud service or LLM (including this kind of session). They are Buddy's personal reference.
- `overlap_check.py` was patched to skip `distribution: "limited"` rows; their text is not extracted into `.cache/`.
- Terms they define are published in the public DoD Dictionary - cite that. For JP 3-01, the public 2017 edition (`us-jp-3-01`) stays the only quotable JP 3-01 text.

Housekeeping: `tools/sources/Pull for LEAP/` is now empty (this session could not delete it). Two stale cache files for the superseded 2021 dictionary remain in `.cache/text/`; harmless.
