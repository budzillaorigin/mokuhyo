# Content packs

Read-only SQLite files built by `tools/packs/*.py` into `content/packs/` (git-ignored, never hand-edited), plus `manifest.json` listing each pack's file, version (`<pack_version>-<sha256 prefix>`), hash and size. The Android build (`bundlePacks` task) and the Xcode "Bundle Content Packs" phase copy them into the app. `PackInstaller` copies them into app storage on first launch and whenever the manifest version changes (DECISIONS D-013).

```bash
cd tools && uv sync
uv run python packs/build_all.py      # ~1 min after the first download (~100 MB of sources, cached in tools/.cache)
# Tatoeba files come from this repo's `sources-tatoeba-*` release (private repo: export GH_TOKEN or `gh auth login`)
```

| Pack | Builder | Phase | Status |
|---|---|---|---|
| `dictionary.sqlite`: JMdict, KANJIDIC2, KRADFILE/RADKFILE, furigana, pitch, JLPT tags | `packs/build_dictionary.py` | 1 | ✅ |
| same file, `stroke` table (KanjiVG) | `packs/build_kanjivg.py` | 1 | ✅ |
| same file, `sentence`/`sentence_word` tables (Tatoeba) + frequency ranks | `packs/build_sentences.py` | 1 | ✅ |
| same file, `freq_word` table: 10,000-word frequency list behind Core 2k/6k/10k and the "I know these" bands | `packs/build_decks.py` | 11 | ✅ |
| `IlrBandData.kt` (not a pack): ILR bands + abstract lexicon for the difficulty score, generated from `items/ilr_bands.json` | `packs/gen_ilr_bands.py` (`--check` verifies) | 11 | ✅ |
| `kanji-path.sqlite` (60 levels: 243 radicals, 2,599 kanji, 7,242 words) | `packs/build_kanji_path.py` | 2 | ✅ |
| `grammar.sqlite` (N5–N1: 829 points, 5,116 Tatoeba examples; matched in order n3, n4, n5, n2, n1 so harder points don't take easier points' sentences) | `packs/build_grammar.py` from `packs/grammar/n*.json` | 3 / 7 | ✅ |
| `exam.sqlite`: JLPT blueprints + 7 banks (2,368 JLPT items: 1,878 rule-generated, 490 AI-drafted; DLPT 195 passages / 595 items, AI-drafted: core 0+–3 100/306, upper range 3+/4 80/245, liaison 2–3 15/44) | `packs/build_exam.py` from `items/jlpt_blueprints.json` and `items/bank/*.json` | 7 / 12 | ✅ |
| `practice.sqlite`: 90 scenarios (699 scripted turns), 96 OPI questions with DLI domains, 125 dialogues (40 natural), 31 drill sets (325 items), 630 minimal pairs | `packs/build_practice.py` | 6 / 12 | ✅ |

| `exam.sqlite`: JLPT blueprints + 4 banks (2,368 JLPT items: 1,878 rule-generated, 490 AI-drafted; DLPT 100 passages / 306 items, AI-drafted) | `packs/build_exam.py` from `items/jlpt_blueprints.json` and `items/bank/*.json` | 7 | ✅ |
| `practice.sqlite`: 30 scenarios, 62 OPI questions, 45 dialogues, 630 minimal pairs | `packs/build_practice.py` | 6 | ✅ |
| `tracks.sqlite`: 7 interest/domain tracks, 2629 words, 84 scenarios, 54 dialogues, 458 drills | `packs/build_tracks.py` from `packs/tracks/*.json` | 12 | ✅ (AI-drafted, badge on) |
| `audio-<set>.zip`: VOICEVOX audio for exam, dialogues, minimal pairs, pitch test, grammar examples (on-demand downloads, not bundled) | `packs/render_audio.py` | 10 | ✅ (grammar partial) |

## dictionary.sqlite

Schema: `shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/dictionary.sq` (single source of truth; the builders execute its CREATE statements). `PRAGMA user_version = 1` so SQLDelight never tries to create or migrate it.

Encodings:
- JSON string arrays; `""` means `[]`, or `["*"]` (applies to all forms) for `applies_*` columns.
- `entry_kana.search` = reading folded katakana→hiragana (search only).
- `furigana.segments` = `ruby=rt|ruby|ruby=rt`, e.g. `食=た|べ|物=もの`. Only forms containing kanji.
- `pitch.accents` = comma-separated downstep positions (0 = heiban).
- `gloss_index.term` = lowercased gloss word, or `=` + whole gloss (≤ 4 words) for exact matches.
- `entry.rank`: lower = more common. Bands: common → on a JLPT list → Tatoeba frequency.

Size: ~127 MB raw, ~46 MB gzip. 218k entries, 10k kanji, 80k strokes, 112k example sentences.

### Frequency list (`freq_word`, Phase 11, DECISIONS D-152)

`tools/packs/build_decks.py` runs after `build_sentences.py` and writes `freq_word(ord, entry_id, count)`: the 10,000 most frequent JMdict words, ord 1 first. Core 2k/6k/10k are the prefixes `ord <= 2000/6000/10000`.
- `count` = Tatoeba sentences whose index line resolves to the word, recovered from `entry.rank` (build_sentences subtracts it inside a 999,999-wide bucket), so no second corpus pass or download is needed.
- Order: count (desc), JMdict common, easier JLPT level, rank, id.
- Function words (first sense only `prt`/`aux*`/`cop`, with optional `exp`/`conj`, or a kana-only `exp`) are left out: they are grammar, and coverage never counts them (`WordStats.isFunctionWord`, same rule).
- Current build: 10,000 words, all with Tatoeba hits (lowest count 4), 253 function words skipped. `pack_meta`: `freq_words`, `freq_words_with_tatoeba`, `freq_decks`.
- The Japanese Wikipedia dump BRIEF_V2 §7 mentions isn't used (D-152). Packs built before Phase 11 lack the table; the app then shows no Core decks (an honest empty state) and the "I know these" flow returns nothing.

## tracks.sqlite: interest and domain tracks (BRIEF_V2 §6.5, DECISIONS D-210…D-219)

A track is a themed word list (JMdict ids), a kanji subset, role-play scenarios, two-speaker dialogues and drills. Some tracks add can-do situations, cultural tasks, ILR reading passages or link-only references. Learners pick tracks in onboarding and can switch any time. Track words join Today's lessons alongside the main path, taking up to half of each batch (D-213).

Schema: `shared/src/commonMain/sqldelightTracks/app/tsumugi/tracks/db/tracks.sq` (`TracksDatabase`, `PackInstaller.TRACKS`). Sources: `tools/packs/tracks/<track>.json`, plus optional part files `<track>.<part>.json` whose lists are appended to the main file's. Builder and validator: `tools/packs/build_tracks.py`, run by `build_all.py` after `build_practice.py`.

```bash
cd tools
uv run python packs/build_tracks.py                     # validate every track and build content/packs/tracks.sqlite
uv run python packs/build_tracks.py validate [track…]   # validate only; lists every problem and prints counts
uv run python packs/build_tracks.py resolve [track…]    # fill JMdict ids + glosses for words written as text + reading
uv run python packs/build_tracks.py build gaming        # a pack with only some tracks (testing)
# Options: --dictionary PATH (or $TSUMUGI_DICTIONARY) to use another checkout's dictionary pack, --out PATH.
```

### Launch counts (2026-09-19, all `source: "llm"`, badge on until reviewed)

| Track | Levels | Words | Kanji | Scenarios (turns) | Dialogues | Drills | Other |
|---|---|---|---|---|---|---|---|
| Gaming & VTuber (`gaming`) | N5–N2 | 478 | 142 (explicit, with hints) | 10 (67) | 10 | 40: 20 fill-in, 20 meaning | – |
| Business & keigo (`business`) | N4–N1 | 253 | 150 (derived) | 30 (223) | 8 | 84: 14 email templates, 70 keigo | – |
| Family & household (`family`) | N5–N2, ILR 0+–2 | 266 | 150 (derived) | 10 (68) | 10 | 40: 20 fill-in, 20 usage yes/no | – |
| Daily-life admin (`daily-life`) | N5–N2 | 267 | 150 (derived) | 12 (74) | 8 | 24: 24 fill-in | 12 situations / 51 can-do; 8 cultural tasks |
| Native schoolchild vocabulary (`schoolchild`) | N4–N1 | 912 | 249 (derived) | 4 (26) | 4 | 230: 70 fill-in, 40 meaning, 60 synonym/antonym, 60 usage yes/no | – |
| Military & liaison (`military`) | N3–N1, ILR 2–3 | 271 | 150 (derived) | 12 (104) | 8 | 20: 20 meaning | 14 ILR readings; 5 links |
| Performing culture (`performing`) | N5–N2 | 182 | 150 (derived) | 6 (40) | 6 | 20: 20 performances | – |
| **Total** | | **2629** | **1141** | **84 (602)** | **54** | **458** | 12 situations / 51 can-do; 8 tasks; 14 readings; 5 links |

The §6.5 targets were: Gaming ~140 kanji / 600 words, Business 30 situations, Schoolchild a 1,100-style bank. Gaming has 142 kanji and 478 words (80% of 600). Business has all 30 situations. Schoolchild has 912 words (83% of 1,100).

### Source format (one file per track)

- **Header:**
  - `track` (the file stem), `titleEn`, `titleJa`, `description`, `inspiredBy`.
  - `jlpt: [easiest, hardest]`. Every item's level must be inside it.
  - Optional `ilr: "2-3"`. When present, every scenario, dialogue and reading needs an ILR inside the range.
  - Optional `kanjiLimit`.
  - `source`, `verified`, `license`, `attribution`.
- **`words`:** `{text, reading, id, gloss, topic, category, note}`.
  - `id` is a JMdict id; `resolve` fills it.
  - `category` is `""` or `yojijukugo`, `kanyouku`, `kotowaza`, `onomatopoeia`, `synonym`, `antonym` or `keigo`.
  - Lessons are consecutive words of one topic, 8 per lesson.
  - Pitch and JLPT come from the dictionary pack.
- **`kanji`:** explicit list (the gaming track), `{kanji, keyword, components, breakdown, hint}`.
  - Every kanji must occur in one of the track's words.
  - Without the list, the builder derives the subset: kanji used by the words, most-used first, up to `kanjiLimit` (default 150), with KANJIDIC2 keywords and KRADFILE components.
- **`scenarios`:** the practice-pack format (`tools/packs/speaking/scenarios.json`) with 3–16 scripted turns.
- **`dialogues`:** the practice-pack format with 4–16 lines, voice hints and 1+ questions; `ilr` is optional.
- **`drills`:** each has `id`, `type` and optional `topic`/`jlpt`.
  - `keigo`: `{plain, target: sonkeigo|kenjogo|teineigo, form: dictionary|masu|past|masu-past|te, sentence with one （　　）, en, answers[], explanation, verb?}`. The builder adds `verbReading`/`verbClass` from JMdict.
  - `email`: `{title, situation, subject, body with ｛1｝…｛n｝, blanks: [{answers, choices?, hint}], en?}`.
  - `fill_in`: `{sentence with one （　　）, answers, choices?, word?, en, explanation}`. With choices, exactly one answer is a choice.
  - `synonym`: `{relation: synonym|antonym, word, choices (3–5), answer, explanation}`.
  - `usage`: `{word, sentence, correct: bool, explanation}`.
  - `meaning`: `{word, choices, answer, explanation}`.
  - `perform`: `{title, titleJa, setting, register, speakers[2], learner, staging[], lines: [{speaker, ja, en, stage?}]}`.
- **`situations`:** `{id, titleEn, titleJa, canDo: [{en, ja}] (2–8)}`.
- **`tasks`:** `{id, titleEn, titleJa, place, before[], during[], after[], phrases[], etiquette[]}`.
- **`readings`:** `{id, title, ilr, genre, body, questions: [{question, choices, answer}]}`. Length and kanji density are checked against `items/ilr_bands.json`.
- **`links`:** `{title, url (https), note}`. Link only.

**Ids** are `<track>-<kind>-NNN` (a-z, 0-9, `-`). They are unique across tracks and never equal a practice-pack id, so audio keys `dialogue/<id>/<ord>` can't collide.

### Validator

`validate`/`build` check every item and list every problem, not just the first:
- **JMdict:** each word's id exists and has that form and reading, with no duplicate entries in a track. Scenario vocabulary, dialogue gaps and drill words are exact JMdict headwords, and every gap occurs in its line.
- **Text:** all Japanese is NFC.
- **Answer keys:** indexes are in range; choices are distinct; one blank per fill-in or keigo sentence; email slots are numbered in order; fill-in choices contain exactly one answer.
- **Ids:** unique, with the track prefix.
- **Levels:** JLPT and ILR inside the track's range; ILR readings inside their band.
- **Structure:** turn and line counts; two speakers with voice hints; the learner has 2+ lines in a performance.

`shared/src/androidHostTest/.../RealTracksPackTest` then loads the built pack with the app's models. It checks that every drill accepts its own model answer, that every performance can be completed, and that the keigo rules agree with the authored answers (≤ 10% disagreement; the disagreements are printed).

### Adding more

- **By hand:** edit the JSON, run `resolve` for new words, then `validate`.
- **Through an LLM** (e.g. Ollama on the GPU box):
  ```
  uv run python packs/build_tracks.py draft gaming --kind words --count 40 --endpoint http://HOST:11434/v1 --model qwen2.5:14b
  uv run python packs/build_tracks.py draft business --kind drills --type keigo --count 20 --endpoint … --model …
  ```
  `--kind` is `words`, `scenarios`, `dialogues` or `drills`.
  - New items get fresh ids and are resolved against JMdict. Only items that pass the full validator are appended.
  - Re-runs add items without duplicating ids.
  - The key, if any, comes from `$TSUMUGI_LLM_KEY`.
- **Review:** everything stays `source: "llm"` with the badge until it's reviewed. `tools/items/review.py` doesn't read track files yet (deferred, see PROGRESS).

## Difficulty score (BRIEF_V2 §6.4, DECISIONS D-156)

`app.tsumugi.coverage.DifficultyScorer` turns a text's `TextProfile` (tokenized with the reader's lattice tokenizer) into a 0–100 score with a JLPT/ILR label. Four text components, each scaled to 0..1:

| Component | Measure | Scaling | Weight |
|---|---|---|---|
| V: JLPT band coverage | for each level N5…N1, the share of content-word occurrences *not* on the JLPT lists at that level or easier; averaged over the five levels | as is | 0.20 |
| S: sentence length | mean Japanese characters per sentence | (len − 10) / 40 | 0.45 |
| K: kanji density | kanji / non-whitespace characters | (d − 0.25) / 0.25 | 0.05 |
| A: abstract vocabulary | abstract-lexicon hits / approximate tokens, measured exactly like `tools/items/gen_dlpt.py` on the lexicon from `ilr_bands.json` | ratio / 0.08 | 0.30 |

`textScore = round(100 × (0.20 V + 0.45 S + 0.05 K + 0.30 A))`. Labels from `textScore`: < 15 → N5 / ILR 0+, < 19 → N4 / 1, < 32 → N3 / 1+, < 46 → N2 / 2, < 55 → N1 / 2+, else above N1 / 3.

The learner's score adds the known-word ratio k (Guru+ or marked known, share of word occurrences): `score = round(0.5 × textScore + 50 × min(1, (1 − k) / 0.30))`. A text where 30% or more of the words are unknown gets the full 50 points; one where every word is known scores half its text score. Without learner data, `score = textScore`.

Function words (particles, auxiliaries, copulas) never count as words. Kanji density gets a low weight because the DLPT bank shows it is flat across levels (0+ signs are the most kanji-dense).

**Calibration** (`DifficultyCalibrationTest`, real packs, the 60 DLPT reading passages): mean text score per ILR level 0+ 14.4 · 1 15.5 · 1+ 22.8 · 2 41.2 · 2+ 51.0 · 3 57.3. 98% of passage pairs two or more levels apart score in order, and 59/60 labels are within one ILR step of the bank's level. The cut points are the midpoints between those means. Re-run the test after changing weights or banks; it prints the table.

Today's immersion block matches texts to the learner with this score (`ScoredImmersionDifficulty`: distance between the learner's JLPT level and the text's continuous band position, harder texts × 1.5, −0.5 when 90%+ of the words are known).

## Exam item banks (`tools/items/bank/*.json`, also the user-import format)

`tools/items/jlpt_blueprints.json` holds the published JLPT structure (sections, item types, counts, timings, pass marks). It is facts only and is edited by hand when the JLPT changes. Item banks are JSON files that `packs/build_exam.py` validates and loads into `exam.sqlite`. Users can import a bank in the same format from Me → Import.

```jsonc
{
  "bank": "dlpt-reading-core",          // unique id; imported banks are prefixed "user:"
  "title": "DLPT reading: core bank",
  "license": "CC BY-SA 4.0",             // or the source's license
  "attribution": "Tsumugi contributors",
  "passages": [
    {
      "id": "dr-2-news-001",             // unique across all banks
      "exam": "DLPT_READING",            // JLPT | DLPT_READING | DLPT_LISTENING
      "level": "2",                      // JLPT: "N5".."N1"; DLPT: "0+","1","1+","2","2+","3", upper range "3+","4"
      "textType": "news",                // free tag used in reports and the DLPT text-type filter: sign, notice, email, narrative, news, editorial, liaison, academic, literary, lecture, ...
      "title": "Local election turnout",  // English, shown in reviews only
      "body": "…",                        // Japanese text (NFC). Listening passages leave body empty and use script.
      "script": [ {"speaker": "A", "voice": "female", "text": "…"} ],  // listening only; rendered with on-device TTS
      "source": "llm",                   // llm | generated | human | tatoeba | …
      "verified": false                  // flipped by tools/items/review.py after human review
    }
  ],
  "items": [
    {
      "id": "dr-2-news-001-q1",
      "exam": "DLPT_READING",
      "level": "2",
      "type": "main_idea",               // JLPT: an item type from the blueprint (kanji_reading, grammar_form, …); DLPT: main_idea, detail, inference, purpose, vocabulary_in_context, tone
      "passageId": "dr-2-news-001",      // optional
      "stem": "What is the main point of the article?",  // DLPT stems and choices are English; JLPT ones Japanese
      "choices": ["…", "…", "…", "…"],   // exactly 4 (JLPT listening 発話表現 uses 3, 即時応答 uses 3)
      "answer": 2,                       // 0-based index
      "explanation": "…",                // English; why the key is right and the distractors wrong
      "script": [ … ],                   // optional per-item audio (JLPT listening 即時応答 etc.)
      "refs": ["g:n3-ni-yoru-to", "v:1234567"],   // grammar point ids / JMdict ids to offer "add to SRS"
      "source": "llm",
      "verified": false
    }
  ]
}
```

Validation (`packs/build_exam.py`, also run on user imports): ids unique; `answer` in range; choices distinct and non-empty; every `passageId` exists; JLPT `type` is in the blueprint for that level; text is NFC; DLPT passage length and kanji density fall inside the per-ILR-level band in `items/ilr_bands.json`. Anything `source = "llm"` and not `verified` shows the "AI-generated" badge.

### Markup and id conventions

- **JLPT markup:**
  - `<u>…</u>` underlines the target.
  - `（　　）` is a blank to fill.
  - In sentence_assembly, each slot is `＿＿＿` and the starred slot is `＿★＿`, separated by U+3000. The answer is the chunk that goes in ★.
  - In text_grammar, the passage body contains `［1］`…`［5］`, and each item's stem is just the marker.
  - In info_retrieval, tables are `| a | b |` lines with the header row first.
  - Integrated reading puts `Ａ` / `Ｂ` on their own lines before each text.
- **Voices:** `female`, `male` or `narrator`.
- **refs:** `g:<grammar id>`, `v:<JMdict id>`, and `t:<Tatoeba id>` (source-sentence attribution). The app ignores prefixes it doesn't know.
- **DLPT ids** write "+" as "p" (`dr-2p-editorial-001`). An item id is its passage id plus `-qN`.

### Tools

- `items/gen_jlpt.py generate`: deterministic, about 40 s, reads the built packs.
- `items/gen_jlpt.py validate`: prints the coverage table.
- `gen_jlpt.py draft` and `gen_dlpt.py draft`: draft more items through any OpenAI-compatible endpoint (e.g. the owner's Ollama); output is `source: "llm"`.
- `items/gen_dlpt.py validate [--strict]`: checks the ILR bands in `items/ilr_bands.json` and warns when the key is the longest choice in more than 40% of a level's items. Upper-range (3+/4) passages must have 2–4 items and an upper-range text type (reading: editorial, academic, essay, literary, commentary; listening: lecture, discussion, commentary, interview, speech). The 3+/4 bands are provisional (drafted passages only). Banks: `dlpt_reading.json` + `dlpt_listening.json` (core 0+–3), `dlpt_reading_upper.json` (30 passages each at 3+ and 4, 184 items), `dlpt_listening_upper.json` (10 scripts each at 3+ and 4, 61 items), `dlpt_liaison.json` (military/liaison text type at 2, 2+ and 3: 9 reading + 6 listening, 44 items).
- `items/review.py`: human review. It sets `verified: true` and adds `reviewed {by, on}` (DECISIONS D-034).
- **Measures used by the band checks:**
  - Length is non-space characters.
  - Kanji density is kanji / characters.
  - The abstract ratio is abstract-lexicon hits per token run.

## Practice pack (`practice.sqlite`, Phase 12 content)

Built by `packs/build_practice.py` from `packs/speaking/scenarios.json`, `packs/speaking/opi.json` and
`packs/listening/dialogues.json`, plus the drill sets it derives. Everything drafted is `source: "llm"` and shows the
AI badge until reviewed (rule 10); `items/review.py --ingest` flips entries to `verified` in those JSON files.

**Current build (2026-09-18):**

| Content | Count | Notes |
|---|---|---|
| Role-play scenarios | 90 (699 scripted turns) | 30 original + 60 new (business 10, admin 10, military 10, travel 10, family 8, medical 7, culture 4, school 1). Turns 4–12: 4×5, 5×10, 6×14, 7×14, 8×14, 9×11, 10×11, 11×6, 12×5. Every turn has 2–3 `accept` alternatives |
| OPI questions | 96 over ILR 0+–3 | every question has a DLI-style domain; 2, 2+ and 3 each cover family, work, current events, hypotheticals and abstract topics |
| Listening dialogues | 125 (1,214 lines, 1,434 gap targets, 306 questions) | scripted: N5 14, N4 23, N3 26, N2 12, N1 10; natural: N5 10, N4 10, N3 10, N2 10 (493 lines, 73 overlapping) |
| Drill sets | 31 (325 items) | grammar: N5 5, N4 5, N3 5, N2 4, N1 3 (10 sentences each, ord-0 Tatoeba examples); dialogue lines: scripted N5–N1 and natural N5–N2 (12 each; natural N2 9) |
| Minimal pairs | 630 | unchanged |

### Source files and re-runs

- `speaking/author_scenarios.py` and `listening/author_dialogues.py` merge three sources into the JSON: the entries
  written in the script, then `batches/*.json` (same entry format as the JSON; the Phase 12 launch batches are
  `scenarios_business_admin`, `scenarios_family_medical`, `scenarios_military_travel`, `natural_beginner`,
  `natural_intermediate`, `scripted_n4_n3`, `scripted_n2_n1`), then entries only in the JSON. **Re-runs never
  duplicate ids**: an authored entry replaces its JSON copy unless a reviewer touched that copy (`source: "verified"`,
  `reviewed` or `rejected`), which is kept unless `--force`; the same id in two sources is an error.
- `… draft --endpoint URL --model NAME` drafts more through any OpenAI-compatible endpoint (the owner's Ollama),
  validates each draft with the build's own checks (JMdict included), gives it an unused id, appends it to
  `batches/llm-drafts.json` and merges:

```bash
uv run python packs/listening/author_dialogues.py draft --endpoint http://<lan-ip>:11434/v1 --model qwen2.5:14b \
    --level 3 --style natural --count 5 [--topic "..."]
uv run python packs/speaking/author_scenarios.py draft --endpoint http://<lan-ip>:11434/v1 --model qwen2.5:14b \
    --level 2 --category business --turns 9 --count 5
uv run python packs/build_practice.py --check packs/listening/batches/new.json   # validate a batch, write nothing
uv run python packs/test_practice_authoring.py                                   # markup, merge and draft tests
```

### Formats

- **Scenario:** `{id, titleEn, titleJa, jlpt, ilr, category, setting, learnerRole, partnerRole, register, goals[2–4],
  vocabulary[≥3 JMdict forms], phrases[≥2], partnerNotes?, turns[4–12]: {partnerJa, partnerEn, intent, sample,
  accept[]}}`. Categories: daily, health, travel, work, business, official, admin, military, family, social, school,
  culture. The build writes the LLM system prompt from the fields (role, setting, register, level, goals, vocabulary,
  then `partnerNotes`). `accept` → `scripted_turn.accept` (JSON array) → `ScriptedTurn.accept`.
- **OPI question:** `[phase, promptJa, promptEn, note, domain]`; domain ∈ personal, family, work, daily_life, travel,
  current_events, hypothetical, abstract, situation (role-plays). The build fails if ILR 2, 2+ or 3 lacks one of the
  five DLI domains.
- **Dialogue:** `{id, title, jlpt 1–5, topic, style: scripted|natural, speakers[2]: {id, name, voice, age, hint?},
  lines: {speaker, ja, en, gaps[], overlap?}, questions: {question, choices, answer}}`. Scripted dialogues have 6–12
  lines, natural ones 8–18. `hint` is a delivery note for the renderer and reviewers.
- **Filler markup (natural style):** fillers, hesitations and abandoned restarts are wrapped in braces in `ja`:
  `{えっと、}明日{、あ、}明後日なんだけど`. Braces don't nest; a natural dialogue needs at least 3 spans; the markup
  is an error in a scripted dialogue. The build strips the braces (the stored and spoken `ja` keeps the words) and
  stores the spans as `dialogue_line.fillers`, JSON `[[start, end], …]` in UTF-16 units. `DialogueLine.segments()`
  splits a line into filler / non-filler runs for greying, and `withoutFillers` drops them. Gap words must occur
  outside the fillers (the first such occurrence is used). `overlap: true` → `dialogue_line.overlap = 1`: the line
  starts before the previous one ends (backchannels such as うん。/へえ。 are their own short lines, and
  interruptions end the previous line with …).
- **Drill sets** (`drill_set`, `drill_item`; BRIEF_V2 §6.10, Swotter format): derived at build time, no LLM step.
  Grammar sets take, per level, points spread evenly in order, each with its first rendered example (ord 0 or 1,
  6–40 characters, with an English translation). Dialogue sets take lines of 6–32 characters without fillers or
  overlaps, round robin over one (style, level) group of dialogues. Items: `prompt_en` (the cue), `answer_ja`,
  `audio_key` (an existing `grammar/…` or `dialogue/…` clip), `ref` (`g:<point>` / `d:<dialogue>`), `source`. A set
  is `derived` when every item is Tatoeba text, `llm` when any is AI-drafted. Playback timing is shared:
  `DrillPlayback.plan(set, DrillTiming)` → prompt, answer pause (fixed, or proportional to the answer's clip length
  or estimate), model answer, repeat pause, gap; `DrillCursor` steps through it for a hands-free player.

## Audio packs (`audio-<set>.zip`, BRIEF_V2 §5.6)

Pre-rendered VOICEVOX speech for the audio the app promises to keep consistent (CLAUDE.md rule 20). Built by `tools/packs/render_audio.py` into `content/packs/` (git-ignored). The packs are **not bundled**: they're installed on demand from a URL the learner sets, or from a file picked in Files (DECISIONS D-095..D-097). Without a pack the app falls back to system TTS, except the pitch test, which stays hidden.

### Format

- **Archive:** `audio-<set>.zip`, stored (uncompressed) and deterministic.
  - `index.json`: `{format: 1, set, codec: "aac-lc", container: "m4a", sampleRate: 24000, channels: 1, bitrate, engine, credits: ["VOICEVOX:…"], clips: {key: {file, bytes, ms, voice, text, downstep?, display?}}}`.
  - `clips/<content hash>.m4a`: AAC-LC, 24 kHz mono, 48 kbps (D-091). Identical clips are stored once.
  - `items.json`: pitch pack only. The pitch-accent test items (D-094): `{id, entryId, text, reading, moraCount, downstep, pattern, group, gloss, spoken, confusableWith, source}`.
- **`audio-manifest.json`:** `{format, packs: [{file, set, version, sha256, bytes, clips, audioSeconds, credits}]}`. Upload it next to the zips; the app reads it to download and verify them.
- **`audio-build-log.jsonl`:** one line per set per run, with counts, size, rendered vs cached clips, seconds per clip, and stylized contours.

### Clip keys

The app builds keys only with `app.tsumugi.audio.AudioKeys` and looks them up with `AppGraph.audio.clip(key)`, which returns a file path or null (fall back to TTS).

| Set | Key | Source |
|---|---|---|
| `exam` | `exam/<passage or item id>/<line index>` | `exam_passage.script`, `exam_item.script` (JLPT listening + DLPT listening), 0-based line |
| `dialogues` | `dialogue/<dialogue id>/<ord>` | `practice.sqlite` `dialogue_line` |
| `minimal-pairs` | `pair/<id>/a`, `pair/<id>/b` | `practice.sqlite` `minimal_pair`; PITCH pairs spoken with が |
| `pitch` | `pitch/<item id>` (`p<JMdict id>`) | built by the renderer from dictionary words with one Kanjium accent; spoken as reading + が |
| `grammar` | `grammar/<point id>/<ord>` | `grammar.sqlite` `grammar_example`; default 2 per point, `--grammar-all` for all |

### How to render

1. **Install the engine (once).**
   - Download the Windows CPU `.vvpp` from the VOICEVOX/voicevox_engine releases (0.25.2 used). A `.vvpp` is a zip.
   - Unpack it to `%LOCALAPPDATA%\voicevox_engine`.
   - Start it: `run.exe --host 127.0.0.1 --port 50021`.
   - Check it answers: `curl http://127.0.0.1:50021/version`.
   - On a GPU machine, use the DirectML or NVIDIA build instead; the script doesn't change.
2. **Install ffmpeg (once).** Any ffmpeg with the native `aac` encoder works: on PATH, `$FFMPEG`, or a static build unpacked under `tools/.cache/ffmpeg/`. This machine uses BtbN's `winarm64-lgpl` build.
3. **Render.** From `tools/`:

```bash
uv run python packs/render_audio.py all --dry-run            # clip counts per set
uv run python packs/render_audio.py pitch minimal-pairs dialogues exam grammar
uv run python packs/render_audio.py grammar --grammar-all    # every grammar example (7,603)
uv run python packs/render_audio.py exam --endpoint http://<lan-ip>:50021   # engine on another PC
```

**Re-running is safe and cheap.** Clips are cached in `tools/.cache/audio/clips/`, keyed by a hash of engine version, voice, text or kana, accent, speed/pitch/intonation and encoder settings. A re-run renders only what's new, for example after the banks grow. An interrupted run resumes where it stopped. Per-key speed/pitch/intonation overrides go in `tools/packs/audio/overrides.json`. To publish, upload `audio-manifest.json` and the zips to the same folder.

### Voices

| Character (style ノーマル) | Id | Used for |
|---|---|---|
| 春日部つむぎ | 8 | female speakers, pitch and minimal-pair words, even grammar examples |
| 四国めたん | 2 | second female speaker, narrators and announcements |
| 玄野武宏 | 11 | male speakers, odd grammar examples |
| 玄野武宏, pitchScale −0.05, speedScale −0.05 | 11 | second male speaker in a script, or a speaker marked `age: "senior"` |

玄野武宏 is the only male character (D-170). 青山龍星 was dropped on 2026-09-19 because his terms require companies and sole proprietors to apply to ななはぴ before publishing. 玄野武宏's other styles (喜び, ツンギレ, 悲しみ) are emotional, so a second male voice is the same ノーマル style, slightly lower and slower. The offsets are added to the level speed (e.g. 0.95 → 0.9) and are part of the clip hash.

Credit lines are in `docs/LICENSES.md`, which the Licenses screen renders, and in each pack's `index.json` (D-098).

### Build (2026-09-18, VOICEVOX Engine 0.25.2 CPU, x64 emulation on Snapdragon X Plus)

| Set | Clips | Zip size | Audio | Rendered / cached | Seconds per clip | Wall time | Notes |
|---|---|---|---|---|---|---|---|
| `pitch` | 300 | 2.1 MB | 4.1 min | 300 / 0 | 1.58 | 8 min | 165 contours stylized (D-093); cells 平板 28/28/28, 頭高 28/28/28, 中高 –/28/28, 尾高 28/28/20 |
| `minimal-pairs` | 1,260 (630 pairs) | 6.6 MB | 15.4 min | 975 / 285 | 1.38 | 22 min | 350 contours stylized; words without a Kanjium accent use the engine's accent |
| `dialogues` | 290 lines (45 dialogues) | 5.2 MB | 12.9 min | 290 / 0 | 2.98 | 14 min | |
| `exam` | 1,032 lines (166 passage scripts + 71 item scripts) | 47.1 MB | 120.9 min | 1,022 / 10 | 4.81 | 82 min | JLPT + DLPT listening |
| `grammar` | 829 of 7,603 (1 per point) | 14.4 MB | 35.5 min | 822 / 7 | 2.37 | 32 min | **partial**: default is 2 per point (1,658), `--grammar-all` 7,603 |

Total: 3,711 clips, 75 MB, about 3.2 hours of audio. Rendering took 2 h 39 min of wall time on the CPU while other work shared the machine; an idle machine was about twice as fast in the smoke tests. Rendering scales with audio length, roughly 0.65 s per second of speech here.

**Voice change (2026-09-19, D-170):** re-rendered the 40 clips that used 青山龍星 with the lower 玄野武宏 variant: 20 exam lines in 6 two-male scripts and 20 dialogue lines in 6 dialogues (4 with a senior male, 2 with two males). Every other clip came from the cache: exam 20 rendered / 1,012 cached (3.08 s per clip, 73 s wall), dialogues 20 / 270 (1.09 s per clip, 24 s wall), about 100 s in all. Pitch, minimal pairs and grammar never used him (つむぎ only; grammar is 829 clips, first example per point, all even ords), so they weren't rebuilt and their zips are byte-identical. New sizes: exam 47.1 MB, dialogues 5.2 MB.

**Left to render (Phase 12, not run here; the coordinator renders):**
- `dialogues`: 1,214 lines now (was 290). The 80 new dialogues add 924 lines, and the question rework edited lines in
  16 original dialogues (n5-morning, n5-shop-apples, n5-weekend, n5-station, n5-restaurant, n5-birthday, n5-library,
  n5-weather, n5-phone-number, n5-bus, n5-hobby, n4-lost-wallet, n4-gift, n4-recycling, n4-homestay, n3-environment),
  so those lines re-render too; everything else comes from the cache. Natural dialogues render like any other
  dialogue: the fillers are in the text and are spoken; the app overlaps `overlap` lines at playback.
- `exam`: the upper-range listening bank (20 scripts) and the liaison bank (6 scripts) add 191 lines, long ones
  (3+/4 scripts are 800–1,100 characters).
- Drill sets need no set of their own: every answer is a `grammar/<point>/0` or `dialogue/<id>/<ord>` clip (all 220
  grammar answers use ord 0, already in the published grammar pack) and the English cues use system TTS.

**Also left to render:** grammar examples 2..n. `render_audio.py grammar` renders the second example per point (+829 clips, ~35 min here); `--grammar-all` renders the other 6,774 (~4.5 h here, minutes with a VOICEVOX GPU build). Both reuse the cache.

### Moving rendered audio between machines

`tools/packs/audio_release.py publish` uploads `content/packs/audio-*.zip` and `audio-manifest.json` to a pre-release of this repo, tagged `audio-packs-<date>`, and pins their hashes in `tools/packs/audio.lock`. `… fetch` downloads and verifies them on another machine, e.g. the Mac. The current release is `audio-packs-2026-09-19` ("Audio packs (VOICEVOX) 2026-09-19", republished the same day after the voice change; the earlier release under that tag was deleted): pitch 300, minimal pairs 1,260, dialogues 290, exam 1,032, grammar 829 clips (the first example per point). Its notes carry the VOICEVOX credits. `publish` compares GitHub's asset digest, not just the size, so a same-day re-render replaces the changed files. This is for the owner's machines only. Learners install packs from Files or from a URL they type in Settings → Audio packs; there is no default download URL (D-096).
