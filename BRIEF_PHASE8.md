# Mokuhyo Phase 8 — Current military lexicon (counter-UAS / base defense) and the cultural-nuance layer

Four parts. **Part D** is the optional Phase 9 follow-on (interpretation drills, numbers, storyline, rater calibration, exemplars, degraded audio, authentic formats, maintenance tooling). **Part A** is the punch list (who does what, in what order). **Part B** is the implementation guide: where materials come from, how to prepare them, file formats, and how each recommendation maps onto the app as built. **Part C** is the brief to paste into Claude Code once Part A's "owner" items are done. Save this file in the repo as `BRIEF_PHASE8.md`. Updated 2026-10-03 after the source acquisition handoff (see §B.1 status and §B.0).

Standing context: Mokuhyo is the Compose Desktop fork of Tsumugi (BRIEF.md, CLAUDE.md rules 1–13, v0.1.1 released). The content pipeline (`tools/`), tracks, scenarios, OPI banks, exam banks, the review tool and the §7.1 drafting endpoint on the 5090 all exist. Phase 8 adds content and three features; it does not change the architecture.

---

# Part A — Punch list

Ordered. "Owner" items are yours and gate the Claude Code session; "CC" items are Claude Code's and run unattended per the Phase 0–7 rules. Nothing in the CC column starts until every Owner item in the "Before launch" block is checked.

## Before launch (Owner)

| # | Item | Done when |
|---|---|---|
| A-01 ✅ | **Decided 2026-10-03 (D-028):** no pre-launch requester approval. The seed list is built by the pipeline (C-01a); the requester reviews afterwards in the Content Review screen. | Recorded in `docs/DECISIONS.md` D-028. |
| A-02 ✅ | **Done 2026-10-03** (see `docs/handoffs/MOKUHYO_HANDOFF_2026-10-03.md`). US doctrine sources in §B.1.1 are in `tools/sources/us/` (and NATO/UK into `tools/sources/nato/`) with rows in `tools/sources/SOURCES.json`. | Files present; `uv run python tools/sources/check.py` lists them with no missing license fields. |
| A-03 ✅ | **Done 2026-10-03.** Allied references are in `tools/sources/allied/<lang>/` for ja, ko, de, fr, pt-BR, id, zh-Hant, with per-row `verbatim_ok` / `alignment_ok` / `machine_extract_ok` flags in `SOURCES.json`. **No usable public bilingual source was found for es, ru, ar, fa** — for those the model proposes terms and they stay `unconfirmed-term` until a reviewer accepts them. | Remaining owner item: verify by hand the KOGL type on the ROK white paper, SGDSN's terms for the French RNS, and NATO NSO terms for AAP-06; update `license_verified` in `SOURCES.json`. |
| A-04 ✅ | **Done 2026-10-03.** AFCLC guides (2025 editions) are in `tools/sources/culture/afclc/` for Japan, South Korea, Mexico, Brazil, Russia, Saudi Arabia, Iraq, Iran, Indonesia, China, Taiwan. **AFCLC publishes no guide for Germany, France or Spain**; for de/fr and the es-Spain variant, culture cards draw on the pragmatics the `LanguageModule` already carries plus public sources listed in `SOURCES.json`, and are badged. The guides' terms forbid modification: cards are **original wording informed by them**, never excerpts. | Remaining owner item: confirm the Mexico-vs-Spain choice for Spanish and the Saudi/Iraq choice for Arabic in `docs/DECISIONS.md`. |
| A-05 ✅ | **Delegated to Claude Code (D-028, item C-01a).** Was: produce the approved English seed term list at `tools/terms/seed_terms.csv` (format §B.2.1; `definition_en` copied verbatim from the **August 2026 DoD Dictionary** first, then ATP 3-01.81 / JP 3-10 / AFDP 3-10 / ATP 1-02.1 — all public domain). Start from the starter list in §B.2.2, extend to roughly 300 terms, mark domain and priority, get the requester's sign-off. | CSV validates (`tools/terms/validate_alignment.py --seeds` finds it), ≥ 250 rows, `approvedBy` filled. |
| A-06 ✅ | **Decided 2026-10-03 (D-028):** personas and culture cards use each partner country's **air force**: JASDF, ROKAF, Luftwaffe, Armée de l'air et de l'espace, Fuerza Aérea Mexicana (Spain alternate), Força Aérea Brasileira, VKS, TNI-AU, IRIAF, PLAAF (ROCAF alternate), and for Arabic **two sets: Royal Saudi Air Force and Qatar Emiri Air Force**. | In `docs/DECISIONS.md` D-028. |
| A-07 | Start Ollama on the 5090 with the §7.1 models (`mistral-small3.2:24b-instruct-2506-q8_0` primary, `gpt-oss:20b` checker) reachable from the Mac; confirm with the curl test. | `curl http://<ip>:11434/v1/models` lists both. |
| A-08 | On the Mac: commit the handoff files (`SOURCES.json`, `tools/terms/*`, `docs/TERM_PIPELINE.md`, `docs/handoffs/*`; the PDFs are git-ignored and stay local — `SOURCES.json` carries URL + sha256 to re-fetch them). | `git status` clean; `tools/sources/us-limited/` and `tools/sources/.cache/` are **not** in the commit. |
| A-09 ✅ | **Approved 2026-10-03 (D-028):** CLAUDE.md rule 6 is replaced by the §B.0 wording; Claude Code applies it in C-00. | Recorded. |

## During the run (CC, unattended)

| # | Item | Gate |
|---|---|---|
| C-00 | Adopt the handoff: read `docs/TERM_PIPELINE.md` and `docs/handoffs/MOKUHYO_HANDOFF_2026-10-03.md`; wire `tools/terms/overlap_check.py` and `tools/terms/validate_alignment.py` into `tools/gates/` and CI; apply the rule 6 wording from §B.0 to `CLAUDE.md` if `docs/DECISIONS.md` records the owner's approval (otherwise apply it as an unattended default and say so); add `tools/sources/fetch_sources.py` that re-downloads every `status: acquired` row from `SOURCES.json` by URL and verifies sha256 (for CI and other machines); never read, extract, upload or feed `tools/sources/us-limited/` to any model. | Gates green on the empty `term_alignment.csv`; `fetch_sources.py --check` passes against the local files. |
| C-01a | **Build the seed term list** (owner-delegated, D-028): `tools/terms/build_seed_terms.py` writes `tools/terms/seed_terms.csv` (~300 rows, format §B.2.1) using the §B.2.2 starter list as the required core and extending it by domain from the public-domain US sources; `term_en` and `definition_en` are copied verbatim from the **August 2026 DoD Dictionary** where it defines the term, otherwise ATP 3-01.81 → JP 3-10 (2019 official) → AFDP 3-10 → ATP 1-02.1, each with `source_doc` + `source_page`; terms with no doctrinal definition get a one-sentence original definition marked `definition_source = "original"`. `approvedBy` = `owner (delegated 2026-10-03)`. | `validate_alignment.py --seeds` passes; ≥ 250 rows; every priority-1 starter term present; ≥ 90% of rows have a doctrinal definition citation. |
| C-01 | Source ingestion: `tools/terms/extract_terms.py` pulls candidate terms + definitions from the `verbatim_ok = true` US sources (DoD Dictionary Aug 2026 first), merges with `seed_terms.csv`, writes `terms_en.json` with provenance per term. | Every seed term resolved to ≥ 1 source citation or flagged `unsourced`. |
| C-02 | Allied term alignment per `docs/TERM_PIPELINE.md` steps 2–4: `tools/terms/align_terms.py` chooses the target term from that language's allied source **before** drafting and records `term_source_id` + `term_source_page` in `tools/terms/term_alignment.csv`; the model may search only `machine_extract_ok = true` sources (others are queued for human look-up); **translation rule (D-028): when an `alignment_ok` source confirms both the target-language term and its meaning, that confirmed term is the translation, cited by source and page**; languages with no source (es, ru, ar, fa) get model-proposed terms with `badge = unconfirmed-term`; definitions are drafted from the US English definition with `prompts/draft_definition.md`, checked with `prompts/check_accuracy.md`, and **must pass `overlap_check.py`** before `status = checked`. | `validate_alignment.py` 0 errors; `overlap_check.py` clean on every drafted definition and example; per language with a source: ≥ 70% of terms have a documented equivalent. |
| C-03 | **Counter-UAS & Base Defense track** per language (`tracks/cuas-base-defense.<lang>.json`): term entries with both-language definitions, loanword/calque/native note, two example sentences in military register, collocations; drills (meaning, fill-in, keigo/register where the language has it, radio-brevity mini-drill); 12 scenarios; 8 dialogues; 12 OPI probes; 18 reading passages (6 per band 1/2/3) and 12 listening passages; audio rendered. | `gate_content` targets met or shortfall logged with the `draft` command; all validators 0 errors. |
| C-04 | **Lexicon update channel**: versioned lexicon packages (`lexicon-<lang>-<domain>-<version>.json`, signed manifest), Settings → Content → "Import lexicon update" (file or URL), the "What's new in this lexicon" screen, and new terms auto-queued to Review first. | Round-trip test: build v1 → import → build v2 with 20 added/5 changed terms → import shows the correct delta and queues exactly the new terms. |
| C-05 | **Culture cards**: `culture/<lang>.cards.json` drafted from the ECFGs (own wording, cited section), attached to every scenario, persona and OPI role-play by tag; shown before the role-play and referenced in the debrief. | Every scenario in every language has ≥ 1 card; cards cite an ECFG section; no sentence copied verbatim (similarity check). |
| C-06 | **Pragmatic flags** in role-play and topic conversation: the correction pass adds `register`, `face`, `directness`, `ritual` flags with a one-line cultural reason; a "Cultural appropriateness" line (not an ILR factor; clearly labeled) in the OPI debrief. | Golden tests for the new prompt fields; three fixture turns per language produce the expected flag. |
| C-07 | **Pragmatics pack** per language (`pragmatics/<lang>.json`): address and rank etiquette, indirectness and refusals, apology/thanks, safe and taboo small talk, disagreement in meetings, hospitality obligations, gestures and silence; each entry with examples; 24 listening "implied meaning" inference items per language built from it. | Pack validates; items pass the exam validator; the OPI interviewer persona references the pack. |
| C-08 | **Personas** in speaking practice: senior counterpart, peer officer, junior enlisted, interpreter, local contractor, civilian official — each with register rules from the pragmatics pack and a culture card. | Persona picker in Speaking; each persona's system prompt has golden tests. |
| C-09 | **Current-events reading set** (links only): `tools/terms/feeds.json` of allied-ministry press/release pages per language; a Reading → "This month" list that opens links externally and offers "paste text to practice". No fetching, no storing. | Screen renders; links validated at build. |
| C-10 | Review surfaces: the in-app Content Review covers terms, cards, pragmatics entries; `review.py` ingests verdicts; `docs/CONTENT_PACKS.md` and `docs/LICENSES.md` updated with every new source. | Review round-trip on fixtures. |
| C-11 | **Live vs after-action corrections** (§B.5): a per-session "Corrections" mode — *Live* (today's behaviour), *After action* (no corrections, flags or rewrites shown during the conversation; everything is still recorded and delivered as an After Action Brief at the end), *Off* (nothing recorded beyond the transcript). Applies to OPI practice, topic conversation and persona practice; OPI **test** mode is always after-action. | Golden tests: identical turn produces identical correction records in Live and After-action, differing only in when they surface; AAB renders on fixtures; the setting persists per mode and is in the `.mokuhyo` bundle. |
| C-12 | Release v0.2.0 as a pre-release with the new packs bundled; `docs/PROGRESS.md` checkpoint; `docs/PHASE8_SUMMARY.md`. | `gate_release`. |

## After the run (Owner + requester)

| # | Item |
|---|---|
| P-01 | Requester reviews the English seed → target term alignments for the languages they know in the Content Review screen; you ingest verdicts and rebuild. |
| P-02 | Spot-check 10 culture cards per language against the ECFG; fix wording; mark verified. |
| P-03 | Publish the first lexicon update package independently of the app (a GitHub Release asset) to prove the update channel end to end. |
| P-04 | Decide cadence: who maintains the term list, how often (quarterly is realistic), and where contributions go (PRs to `tools/terms/seed_terms.csv`). Write it into `docs/LEXICON_MAINTENANCE.md`. |

---

# Part B — Implementation guide

## B.1 Materials to acquire

**Status 2026-10-03: acquired.** A separate Cowork session did this with Buddy; the result is in the repo under `tools/sources/` with `tools/sources/SOURCES.json` (62 rows: 48 acquired, 2 superseded, 12 pending/unavailable), extracted text in `tools/sources/.cache/text/` (git-ignored, for the overlap check), and the term pipeline under `tools/terms/`. Read `docs/handoffs/MOKUHYO_HANDOFF_2026-10-03.md` and `docs/TERM_PIPELINE.md` before anything else. The tables below are kept as the acquisition record and for the gaps that remain.

Actual layout (differs from the plan below; the plan's `tools/sources/mission/…` and `culture/ecfg/` paths are superseded):

```
tools/sources/
├── SOURCES.json            every file: path, url, sha256, pages, license, verbatim_ok, alignment_ok, machine_extract_ok, distribution, use
├── .gitignore              ignores .cache/, us-limited/, and all PDFs
├── us/                     DoD Dictionary Aug 2026 (primary) + Jun 2025, JP 3-01 (2017 public), JP 3-10 (2019 official), ATP 3-01.81, ATP 1-02.1, AFDP 3-10 + changes, DoD C-sUAS Strategy 2021, two AF doctrine advisories (point defense of air bases; control below the coordinating altitude)
├── us-limited/             JEL+ pulls with LIMITED / not-for-public-release markings — Buddy's personal reference ONLY: git-ignored, never extracted, never fed to any model, never shipped
├── nato/                   AAP-06 2019 EN/FR; UK JDP 0-01.1 2025 (UK-government-use licence: alignment-only)
├── allied/{ja,ko,de,fr,pt-BR,id,zh-Hant}/   white papers / glossaries, bilingual where available
├── culture/afclc/          AFCLC culture guides 2025: Japan, South Korea, Mexico, Brazil, Russia, Saudi Arabia, Iraq, Iran, Indonesia, China, Taiwan
└── .cache/text/            extracted text per source (overlap_check.py index)
tools/terms/
├── overlap_check.py        verbatim-overlap gate (≥ 8 shared words, or ≥ 20 chars for ja/zh/ko, against any verbatim_ok=false source → FAIL; PD matches → ok-PD)
├── validate_alignment.py   schema/provenance gate for term_alignment.csv
├── term_alignment.csv      header only; filled by C-02
├── overlap_allow.txt       official names only
└── prompts/draft_definition.md, check_accuracy.md
```

### B.0 Licensing rules adopted with the sources (and the proposed CLAUDE.md rule 6 wording)

Several sources are stricter than first assumed: the Japan white paper is excluded from MOD's open licence (but Japanese Copyright Act Art. 30-4 permits machine analysis), AFCLC guides forbid modification, the UK JDP is UK-government-use only, Brazil's MD35-G-01 is CC BY-ND, UNTERM is personal/non-commercial; Korean, French and NATO terms are unverified. Working principle: copyright protects wording, not facts — term equivalents are facts and original content informed by a source is fine; shipping a source's sentences, definitions, images or a bulk copy of its glossary is not. Because LLMs reproduce short source text that appears in their prompt, the mechanical overlap check is mandatory. Releasability is separate from copyright: the `us-limited/` JPs are US Government works but distribution-controlled, so they carry `distribution: "limited"` and all three flags false.

**Proposed replacement for CLAUDE.md rule 6 (owner approves in A-09):**

> 6. **Licenses and releasability.** App code: Apache-2.0. Third-party code linked into the app binary: MIT/Apache-2/BSD/Unicode only; GPL programs may be bundled only as separate executables run as child processes. Every content source has a row in `tools/sources/SOURCES.json` with four flags that the gates enforce: `verbatim_ok` (its text may ship — true only for US Government public works), `alignment_ok` (it may be used to choose terms and confirm meaning), `machine_extract_ok` (it may be parsed or fed to a model in bulk; `"unclear"` means human review), and `distribution` (`"limited"` sources are never read, extracted, uploaded, fed to any model, committed or shipped). Shipped text passes `tools/terms/overlap_check.py` against every `verbatim_ok = false` source. No official DLPT, OPI, DLI, ACTFL or LEAP material, ever. Every content and model asset has a row in `docs/LICENSES.md` added in the same commit; `license` stays the human-readable record.

If the owner has not recorded a decision when the run starts, Claude Code applies this wording as an unattended default and notes it.

### B.1.1 US doctrine and lexicon (public domain — US Government works)

| Source | Why | Where |
|---|---|---|
| DOD Dictionary of Military and Associated Terms (current edition) | Canonical English definitions for the seed list | jcs.mil → Doctrine → DOD Dictionary |
| JP 3-01, Countering Air and Missile Threats | C-UAS within IAMD; weapons control status, ROE vocabulary | jcs.mil Joint Publications |
| ATP 3-01.81, Counter-Unmanned Aircraft System (C-UAS) | The most complete current C-UAS glossary and TTP language (detect/track/ID/defeat, Group 1–5, kinetic/non-kinetic) | armypubs.army.mil |
| AFDP 3-10, Force Protection; AFI 31-101 (unclassified portions) if available | Base defense vocabulary: BDOC, ECP, sector, QRF, FPCON | doctrine.af.mil; e-publishing.af.mil |
| JP 3-10, Joint Security Operations in Theater | Base and area security terms | jcs.mil |
| DoD Counter-Small UAS Strategy (2021) and JCO public releases | The terms in current use (sUAS, FPV, loitering munition, layered defense, RF detection, EW defeat) | defense.gov |
| Multi-Service Brevity Codes (unclassified ATP 1-02.1 / AFTTP 3-2.5) | Radio brevity for the mini-drill | armypubs / ALSA public releases |
| NATO AAP-06 Glossary of Terms and Definitions (English/French) | Bilingual official terminology; the French alignment source | nato.int (NSO public documents). **License: NATO copyright; reproduction of definitions is generally permitted with attribution — verify the current terms page and record it.** |

### B.1.2 Allied bilingual references (term alignment; license varies — record each)

| Language | Reference | Notes |
|---|---|---|
| ja | *Defense of Japan* white paper (日本の防衛), English and Japanese editions, latest year; JASDF/JSDF public glossaries | Government of Japan Standard Terms of Use (CC BY 4.0-compatible) — verify on mod.go.jp |
| ko | ROK *Defense White Paper*, Korean and English editions | KOGL Type 1 (attribution) — verify |
| de | Bundeswehr/BMVg public releases; NATO AAP-06 German equivalents where the Bundessprachenamt publishes them | © BMVg; alignment-only unless terms allow |
| fr | AAP-06 (bilingual); Ministère des Armées releases | AAP-06 as above |
| es | Ministerio de Defensa (Spain) and SEDENA/Mexico releases, NATO Spanish terminology where public | alignment-only |
| pt-BR | Ministério da Defesa (Brazil) glossário das Forças Armadas (MD35-G-01) | Brazilian government work — verify terms |
| ru | Open-source Russian-language defense press and MoD public glossary pages | alignment-only; take care with provenance |
| ar | Arab League / GCC defense releases; NATO Arabic terminology lists if public; UN terminology (UNTERM, EN/AR, public) | UNTERM is an excellent public EN/AR/RU/ZH/FR/ES term base — verify its terms of use |
| fa | Open-source Persian defense press; UN/NGO humanitarian glossaries (EN/FA) | alignment-only |
| id | Kemhan (Indonesia MoD) and TNI releases | alignment-only |
| zh-Hans | UNTERM (EN/ZH); Taiwan MND bilingual releases for Traditional variants | alignment-only |

Where a language has no usable bilingual source, the drafting model proposes the equivalent and it stays badged until a reviewer accepts it. That is acceptable; what is not acceptable is an unmarked invented term.

### B.1.3 Culture (public domain)

| Source | Use |
|---|---|
| AFCLC **Expeditionary Culture Field Guides** (one per country; airuniversity.af.edu → AFCLC → Expeditionary Culture Field Guides) | Backbone for culture cards and the pragmatics pack. US Government work. Use their structure (communication, hierarchy, hospitality, religion, time, gender, taboos) but write our own sentences; cite the guide and section on every card. |
| CIA World Factbook; State Department country pages | Facts for personas and current-events framing |
| Peace Corps culture and language guides (public domain) | Everyday pragmatics examples for several of the languages |
| Existing Mokuhyo `opiProfile` register notes per language | Already in the `LanguageModule`; the pragmatics pack extends them |

Not to be used: DLI/DLIFLC course materials, commercial culture-training products, anything marked FOUO/CUI.

## B.2 The seed term list

### B.2.1 `seed_terms.csv` format

```
id,term_en,domain,priority,definition_en,source_doc,source_page,notes,approvedBy
cuas-001,unmanned aircraft system (UAS),cuas,1,"An aircraft without a human pilot onboard and its associated elements…",ATP 3-01.81,1-2,"Group 1–5 classification applies",<name>
```

`domain` ∈ {cuas, base-defense, airspace, ew, roe, c2, logistics, medical, hadr, brevity}. `priority` 1 = must ship, 2 = should, 3 = nice. The validator rejects duplicate terms, empty definitions, and unknown domains.

### B.2.2 Starter list (extend to ~300 from the sources; this is the skeleton)

**Counter-UAS:** unmanned aircraft system (UAS); small UAS (sUAS); Group 1 / 2 / 3 / 4 / 5 UAS; first-person-view (FPV) drone; loitering munition (one-way attack UAS); swarm; fixed-wing / rotary-wing / multirotor; counter-UAS (C-UAS); layered defense; detect – track – identify – defeat; kinetic defeat; non-kinetic defeat; radio-frequency (RF) detection; radar detection; electro-optical/infrared (EO/IR) sensor; acoustic sensor; passive vs active sensor; jamming; spoofing; GNSS/GPS denial; directed energy (high-energy laser, high-power microwave); interceptor drone; net capture; hard kill / soft kill; command-and-control (C2) link; datalink; frequency band (2.4/5.8 GHz); remote ID; drone operator location; launch point; recovery point; flight profile; loiter; ingress / egress; mass raid; saturation; collateral damage estimate; engagement zone; weapons-control status (weapons free / tight / hold); rules of engagement (ROE); hostile act / hostile intent; positive identification (PID); track number; air picture / common operational picture; airspace control measure; restricted operations zone; no-drone zone; temporary flight restriction; air defense warning (red/yellow/white).

**Base defense:** base defense operations center (BDOC); force protection; force protection condition (FPCON); entry control point (ECP); vehicle search area; perimeter; sector / sector sketch; observation post; listening post; quick reaction force (QRF); security forces; defender; patrol (mounted/dismounted); random antiterrorism measure; standoff distance; barrier plan; blast mitigation; alarm condition; post-attack reconnaissance; unexploded ordnance; shelter in place; bunker; cover and concealment; accountability; lockdown; all clear; incident commander; emergency operations center; mass notification; indirect fire; rocket/artillery/mortar warning; insider threat; host-nation security forces; status of forces agreement (SOFA); jurisdiction; local national; escort; badge / access roster; shift handover; log / blotter; chain of custody.

**Airspace and C2:** airspace coordination; air tasking order; air defense artillery; identification friend or foe (IFF); mode 5; squawk; altitude block; deconfliction; kill box; call sign; brevity; check-in / check-out; SITREP; SALUTE report; nine-line (medevac); grid reference; bearing / range; time on target.

**Radio brevity mini-drill (English prompt → target-language rendering and read-back):** bogey, bandit, hostile, friendly, unknown; splash; spike; no joy / tally; bingo; cease fire; weapons hold; stand by; say again; roger / wilco; break break.

**Cross-cutting (the words that break conversations):** authority, delegation, coordination, approval, liaison, interoperability, information sharing, caveat, classification, release, exercise, drill, scenario, lesson learned, after-action review.

## B.3 How each lexicon recommendation maps onto the app

**Track.** Reuse `build_tracks.py`'s schema with one addition per word: `equivalents[]` (`{text, kind: loanword|calque|native|acronym, source, verified}`) and `registerNote`. The track feeds Today's lesson mix and Review exactly like existing tracks. Drill types already exist (meaning, fill-in, usage, keigo/register where applicable); add `brevity` (hear the English brevity word → produce and read back the target-language radio form the partner force uses, or the English form if that is doctrine — the alignment step records which).

**Scenarios, dialogues, probes, passages.** Same authoring scripts; the seed is the term list plus a scenario catalog in `tools/terms/scenarios.json` (BDOC shift handover; drone sighting report up the chain; airspace-coordination call with host-nation ADA; ECP incident with a local national; joint perimeter patrol brief; post-incident debrief; requesting a QRF; explaining FPCON changes to a local contractor; coordinating a no-drone-zone notice with municipal officials; a HADR drone-survey tasking). Passages follow DLPT text types per band (notice/sign at 1, news/report at 2, editorial/analysis at 3) and the per-language `ilr_bands.json`.

**Lexicon update channel.** A lexicon package is `lexicon-<lang>-<domain>-<semver>.json`: `manifest {id, lang, domain, version, created, publisher, sha256, previousVersion?}`, `terms[]` (the full set, not a diff; the app computes the delta), `attribution`. Signed with an Ed25519 key whose public half ships in the app (`tools/release/keys/`); unsigned packages import with a warning and are labeled "unverified publisher". Import from Settings → Content (file or URL), stored in `lexicon_package` and `lexicon_term` tables (user DB migration), merged by `(lang, domain, termId)`; new and changed terms are queued into Review first (FSRS new cards with a "lexicon update" tag) and listed in "What's new". The release pipeline (`tools/release/lexicon.py`) builds and signs packages from the track sources, so the shipped track and the update channel share one source of truth. Export of a learner's own term notes rides the existing `.mokuhyo` bundle.

**Current-events set.** `feeds.json` is a list of `{lang, title, url, publisher}`; the Reading tab shows them under "This month" with an external-open action and "Paste text to practice" (the learner pastes a release into the existing paste-text reader). Nothing is fetched by the app, so there is no copyright exposure and no network requirement.

## B.4 How each culture recommendation maps onto the app

### B.4.1 Culture cards

`culture/<lang>.cards.json`: `{id, lang, country, tags[], title, body (≤ 60 words, own wording), doThis[], avoidThis[], source {doc, section}, verified}`. Tags are the same vocabulary scenarios and personas already carry (`rank`, `hospitality`, `refusal`, `time`, `gender`, `religion`, `gift`, `meal`, `meeting`, `radio`, `gate`). The scenario screen shows the matching cards in a "Before you start" panel; the debrief lists which cards applied and whether the model flagged a related issue. Cards are reviewable content like everything else.

### B.4.2 Personas

`personas/<lang>.json`: six personas per language (senior counterpart, peer officer, junior enlisted, interpreter, local contractor, civilian official), set in the partner country's **air force** per D-028 (two Arabic sets: RSAF and QEAF, so `ar` has twelve), each with name, rank/title in the target language, default register, patience/formality parameters the prompt uses, and the pragmatics entries it enforces. The Speaking screens get a persona picker; OPI test mode keeps its standard interviewer.

### B.4.3 Pragmatic flags

Extend the existing correction schema with `pragmatics: [{kind: register|face|directness|ritual|taboo, severity, what, why, better}]`. The prompt receives the persona and the relevant pragmatics entries; the UI shows flags under the learner's turn with the cultural reason and a one-tap "say it again". In the OPI debrief, a separate "Cultural appropriateness" block (not an ILR factor, labeled as such) summarizes recurring flags. Golden tests pin the JSON.

### B.4.4 Pragmatics pack and inference items

`pragmatics/<lang>.json`: entries `{id, topic, rule, examples[{situation, say, dontSay, why}], source}` across the topics in C-07. From these, `gen_dlpt.py draft --kind inference` writes 24 listening items per language where the correct answer depends on what the speaker implies (a softened refusal, an indirect request, a face-saving excuse) — the kind of item the DLPT uses at 2 and 2+.

## B.5 Live vs after-action corrections

**Why.** Interrupting a learner with a correction, a rewrite and a pragmatic flag after every turn teaches accuracy but kills flow, and flow is what the OPI measures. The learner should be able to hold a whole conversation uninterrupted and get the full critique afterwards — the way a real after-action review works.

**The setting.** A three-way `CorrectionsMode` chosen when a speaking session starts (and changeable in Settings → Speaking as the default per activity):

| Mode | During the conversation | At the end |
|---|---|---|
| **Live** | Corrections, natural rewrite, vocabulary notes and pragmatic flags appear under each learner turn (current behaviour); "say it again" available | Session summary as today |
| **After action** (default for OPI practice; selectable for topic and persona practice) | Nothing but the conversation: partner replies, transcript, timer. No flags, no diffs, no badges. The learner cannot peek mid-session. | The **After Action Brief** (below) |
| **Off** | As After action | Transcript and recording only; no analysis recorded (for free-flow warm-ups). Clearly labeled "no feedback will be generated" |

OPI **test** mode ignores the setting and always behaves as After action. The chosen mode is stored on the `conversation` row so History shows how the session was run.

**Recording without showing.** In After-action mode the correction pipeline still runs on every learner turn, exactly as in Live — same prompts, same JSON, same pragmatic flags — but writes to `conversation_turn_feedback` instead of the UI. To keep the conversation fluid on slower tiers, the feedback job runs at lower priority than the partner's next reply (queued per turn; it may lag the conversation and catch up during pauses or at the end). Golden tests assert that the stored records are identical between Live and After-action for the same input; only presentation timing differs.

**The After Action Brief (AAB).** Generated when the session ends (or when the learner ends it early), shown as a screen and saved; exportable into the PDF progress report:

1. Summary: duration, turns, topic/persona, rolling ILR level across the session (sparkline), the three things to work on next.
2. Turn-by-turn review: each learner turn with its correction diff, natural rewrite, vocabulary notes and pragmatic flags, with the partner's reply for context and "play my audio / play the model version" buttons; "add to review" per item.
3. Patterns: recurring grammar errors, register slips, avoidance (topics or structures the learner steered around), fillers and pause statistics from the fluency heuristic.
4. Cultural appropriateness: the pragmatic flags grouped by culture-card tag, each with the card it relates to (labeled: not part of the ILR scale).
5. For OPI practice: the phase map (where level checks and probes happened, where breakdown occurred) and the rating with evidence quotes, exactly as test mode produces it.

Recurring errors from AABs feed the existing recurring-error log and the Review queue; the learner can choose "queue all" or pick items.

**UI touches.** A mode chip on the session screen ("After action — feedback at the end") so the learner knows why nothing is appearing; the end-of-session button reads "End and show After Action Brief"; History rows show the mode and link to the AAB; Settings → Speaking has the per-activity defaults and a "keep raw audio for AAB playback" toggle (on by default; recordings stay local as before).

## B.6 Review and maintenance

Everything new is `source: "llm"` and badged until reviewed. The requester reviews term alignments for their languages in the Content Review screen; verdicts export as JSON and `review.py --ingest` applies them. `docs/LEXICON_MAINTENANCE.md` sets the cadence (quarterly term-list refresh from new doctrine releases), the contribution path (PR to `seed_terms.csv` with a source citation), and the release path (`tools/release/lexicon.py` → signed package → GitHub Release asset → learners import or the next app release bundles it).

---

# Part C — Claude Code kickoff brief (paste after Part A "Before launch" is complete)

> Read `BRIEF_PHASE8.md` in full; it extends `BRIEF.md` and `CLAUDE.md` rules 1–13 stay in force. This is Phase 8: current military lexicon (counter-UAS and base defense) and the cultural-nuance layer. Run it unattended, end to end, exactly as Phases 0–7 ran: each punch-list item C-01…C-12 ends with its gate passing, a `docs/PROGRESS.md` checkpoint, a commit and a push; never stop to ask me; take defaults and record them as `D-nnn (unattended default)`; log any gate that cannot pass after three distinct attempts and continue. Do not remove or weaken any existing feature.
>
> Inputs you will find in the repo: `tools/sources/SOURCES.json` and the PDFs under `tools/sources/us/`, `nato/`, `allied/<lang>/` and `culture/afclc/` (local only, git-ignored; respect every row's `verbatim_ok` / `alignment_ok` / `machine_extract_ok` / `distribution` flags — `tools/sources/us-limited/` is never read, extracted or fed to any model), `docs/TERM_PIPELINE.md` and `docs/handoffs/MOKUHYO_HANDOFF_2026-10-03.md` (the adopted term pipeline and the licensing decisions — read both first), `tools/terms/` (overlap and alignment gates, prompts, the empty `term_alignment.csv`), and `docs/DECISIONS.md` D-028 (owner decisions: the seed list is yours to build in C-01a from the August 2026 DoD Dictionary; confirmed allied terms are used as translations; personas are each country's air force, with two Arabic sets for the Royal Saudi Air Force and the Qatar Emiri Air Force; rule 6 wording approved). The drafting endpoint is the §7.1 Ollama server at `http://<5090-ip>:11434/v1`, primary model `mistral-small3.2:24b-instruct-2506-q8_0`, checker `gpt-oss:20b`; if unreachable, log and continue, never substitute a model outside §7.1.
>
> Build order: C-00 adopt the handoff (gates wired, rule 6 applied, `fetch_sources.py`) → C-01a build `tools/terms/seed_terms.csv` from the public-domain US sources → C-01 extract and source the English terms → C-02 align target-language equivalents per `TERM_PIPELINE.md` before asking the model, every drafted definition through `overlap_check.py` → C-03 the Counter-UAS & Base Defense track in all 11 languages with scenarios, dialogues, OPI probes, reading and listening passages, and rendered audio → C-04 the signed lexicon update channel with import, delta view and review queuing → C-05 culture cards from the ECFGs, own wording, cited sections, attached to every scenario and persona → C-06 pragmatic flags in corrections and the labeled "Cultural appropriateness" block in the OPI debrief → C-07 the pragmatics pack and 24 inference listening items per language → C-08 personas with a picker → C-09 the link-only current-events set → C-10 review coverage for every new content type and the docs → C-11 the Live / After action / Off corrections mode for all speaking practice with the After Action Brief (§B.5): in After-action mode nothing is shown during the conversation but every correction, rewrite and pragmatic flag is still recorded and delivered at the end; OPI test mode is always after-action → C-12 v0.2.0 pre-release with `docs/PHASE8_SUMMARY.md`.
>
> Hard rules for this phase: every term carries provenance (source document and page, or `source: "llm"` and badged); no text from a `verbatim_ok = false` source is reproduced — `tools/terms/overlap_check.py` runs on every drafted definition, example, passage and card and is a gate, not advice; `us-limited/` is untouchable; culture cards cite the ECFG section they draw on; pragmatic flags never alter the ILR rating; "Cultural appropriateness" is labeled as not part of the ILR scale; the lexicon package format is documented in `docs/LEXICON_FORMAT.md` and signed packages verify against the shipped public key; `docs/LICENSES.md` gains a row for every source file under `tools/sources/` in the same commit that first reads it. When Phase 8 is complete, stop.

---

# Part D — Phase 9 (optional, runs after Phase 8 in the same session unless told otherwise)

Ordered the way they should ship. Each item has a gate; the same unattended rules apply. Owner inputs are marked; where an owner input is missing at run time, Claude Code builds the feature against fixtures, logs the missing input in `docs/PROGRESS.md`, and continues.

## D.1 Punch list

| # | Item | Gate |
|---|---|---|
| N-01 | **Consecutive interpretation drill** (Speaking → Interpret). Play a chunk (English or target language, 1–3 sentences, from the track dialogues and passages), optional note-taking pause (configurable 0–20 s), the learner renders it in the other language by voice; the next chunk follows. Scored per chunk by the translation grader (accuracy, completeness, register) with the AAB pattern (§B.5) as the default presentation; `.mokuhyo` and PDF report pick up the results. Variants: **radio relay** (same drill over the degraded-audio chain from N-05 with brevity words) and **sight translation** (a notice/order on screen, timed, voice rendering). | Golden tests for chunking and scoring; a scripted session completes in two languages; grader JSON validates 100% on fixtures. |
| N-02 | **Numbers under stress drill** (Listening → Numbers, and in the daily stand-to). Times (12/24 h), dates, grids (MGRS), bearings/ranges, call signs, tail numbers, phone numbers, frequencies, counts of personnel/vehicles/UAS, and the target language's spelling alphabet (和文通話表, Buchstabiertafel, Spanish/Portuguese/French military alphabets, NATO alphabet where the partner force uses it). Items are generated, not drafted (number grammar per language in the `LanguageModule`), rendered by the voice service, answered by typing or speaking; adaptive on error type. | Number-rendering tests per language (cardinal/ordinal/time/date forms); 500-item generated set validates; the drill runs offline on Tier A. |
| N-03 | **Exercise-week storyline.** A multi-session scenario chain per language with a persistent counterpart (name, rank, memory of prior sessions, evolving relationship): Day 1 arrival and handover → Day 2 drone sighting → Day 3 intrusion and QRF → Day 4 incident with a local national → Day 5 joint after-action with the host nation. Conversation memory summarized per session into `storyline_state`; branches on the learner's choices; culture cards and personas from Phase 8 apply. | State round-trips through the bundle; a scripted five-session run completes; the counterpart's recall of prior facts passes golden tests. |
| N-04 | **Rater calibration set + confidence band.** *(Owner input: 10 instructor-rated practice recordings per language, in `tools/sources/calibration/<lang>/` with the human ILR rating and rationale.)* The eval harness scores the model's rating against the human set per language and tier (exact and ±1 agreement); the app shows the OPI estimate with a confidence band and the sentence "Based on N calibrated samples for <language> on <tier>"; `docs/MODELS.md` gets the agreement table. Without the owner input: the harness runs on fixtures and the band reads "uncalibrated". | Harness runs; UI shows the band; agreement table generated. |
| N-05 | **Exemplar answers.** For 30 OPI prompts per language, reviewed responses at ILR 1+, 2 and 3 (drafted via the §7.1 endpoint, badged until reviewed), each with a two-line "why this is a 2 and not a 3" note tied to the ILR factors, audio rendered. Shown after an OPI practice session next to the learner's own answer, and browsable under Speaking → Exemplars. | 90 exemplars per language validate; audio rendered; linked from the AAB. |
| N-06 | **Degraded-audio chain** (practice only; never in test mode). Voice-service post-processing with presets — telephone, VHF/UHF radio, flightline, generator room, crowd, vehicle interior — built from a band-pass filter, noise beds (own recordings or CC0), compression/clipping and optional cross-talk; a difficulty slider; per-item "replay clean" after answering. | DSP unit tests; A/B fixtures; the listening screens expose the preset; test mode cannot enable it. |
| N-07 | **Speaker variety.** At least two voices per language and gender from the approved voice pool (rule 13), regional variants where available (es_MX/es_ES, pt_BR/pt_PT, ar variants if licensed); listening passages, dialogues and personas rotate voices; the manifest records provenance per voice. | Voice manifest validates; every language has ≥ 2 voices or a logged gap; dialogues render with distinct voices. |
| N-08 | **Authentic-format reading** at ILR 0+–2: signage, visitor-badge form, SMS/LINE/WhatsApp-style exchange, shift-log entry, municipal notice, schedule board — rendered as styled layouts (a `format` field per passage with a Compose renderer each), RTL-aware. 6 per format per language, drafted and badged. | Renderers snapshot-tested; items validate; formats appear in practice and in test forms at the right bands. |
| N-09 | **Doctrine-refresh script.** `tools/terms/refresh.py` diffs a new edition of any source in `tools/sources/us/` against `terms_en.json` and emits candidate additions, changed definitions and deprecations as a review file; a quarterly reminder is documented in `docs/LEXICON_MAINTENANCE.md`. | Runs against two fixture editions and produces the expected diff. |
| N-10 | **Suggest a term / flag this item.** In-app actions on any term, passage or conversation turn that write to a local `suggestions.json`; exported with the `.mokuhyo` bundle and as a standalone file; `review.py --suggestions` ingests them into the curator's queue. No network. | Round-trip test; the file validates; the review tool lists them. |
| N-11 | **SBOM and no-network install profile.** CycloneDX SBOM generated in CI for every release (Gradle plugin, Apache-2.0) and attached to the GitHub Release; `docs/SECURITY_PROFILE.md` describing network behaviour (none except the explicit model download and the optional update check), data locations, signing status, provenance rules, and an "air-gapped install" procedure that side-loads a model file from Settings. A `--no-network` flag that disables even the optional calls. | SBOM attached to the release; the flag is honoured (network calls fail closed in a test); the doc exists. |
| N-12 | **Daily stand-to.** A Today recipe of 8–10 minutes: a numbers drill set, three lexicon items due, one listening clip at the learner's band, one speaking turn in after-action mode; one tap to start; a weekly summary card. | Planner tests; the recipe respects the hardware tier (no speaking turn without a model). |
| N-13 | **Side-by-side terms for multi-language linguists.** A term view showing English → each language the learner has enabled, with the loanword/calque/native note and audio per language; filterable by domain. | Screen renders for 1, 2 and 3 enabled languages; snapshot tests. |
| N-14 | Release v0.3.0 pre-release; `docs/PHASE9_SUMMARY.md`. | `gate_release`. |

## D.2 Owner inputs for Phase 9 (none block the run)

- N-04: ten instructor-rated recordings per language with ILR ratings and rationale. Ask the requester whether their instructors can produce these for the languages they hold; the app degrades to "uncalibrated" without them.
- N-06: if you want real noise beds rather than synthesized ones, record a few minutes each of flightline, generator and vehicle-interior ambience on a phone and drop them in `tools/sources/audio/noise/` with a CC0 note; otherwise Claude Code synthesizes.
- N-07: no input needed unless you want specific regional voices prioritised.
- Decide whether Phase 9 runs immediately after Phase 8 in the same session (default) or waits for the Phase 8 review.

## D.3 Kickoff addendum (append to the Part C message if Phase 9 should run in the same session)

> When Phase 8 is complete, continue directly into Phase 9 (Part D of `BRIEF_PHASE8.md`), items N-01 through N-14 in order, under the same unattended rules. Owner inputs that are absent (calibration recordings, noise beds) do not block: build against fixtures, label the result "uncalibrated" or "synthesized" in the UI, and log the missing input. Stop after v0.3.0.
