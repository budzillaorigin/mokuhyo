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
| `exam.sqlite`: JLPT blueprints + 4 banks (2,368 JLPT items: 1,878 rule-generated, 490 AI-drafted; DLPT 100 passages / 306 items, AI-drafted) | `packs/build_exam.py` from `items/jlpt_blueprints.json` and `items/bank/*.json` | 7 | ✅ |
| `practice.sqlite`: 30 scenarios, 62 OPI questions, 45 dialogues, 630 minimal pairs | `packs/build_practice.py` | 6 | ✅ |

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
      "level": "2",                      // JLPT: "N5".."N1"; DLPT: "0+","1","1+","2","2+","3"
      "textType": "news",                // free tag used in reports: sign, notice, email, narrative, news, editorial, dialogue, announcement, ...
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
- `items/gen_dlpt.py validate [--strict]`: checks the ILR bands in `items/ilr_bands.json` and warns when the key is the longest choice in more than 40% of a level's items.
- `items/review.py`: human review. It sets `verified: true` and adds `reviewed {by, on}` (DECISIONS D-034).
- **Measures used by the band checks:**
  - Length is non-space characters.
  - Kanji density is kanji / characters.
  - The abstract ratio is abstract-lexicon hits per token run.
