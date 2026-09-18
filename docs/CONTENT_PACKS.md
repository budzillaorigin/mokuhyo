# Content packs

Read-only SQLite files built by `tools/packs/*.py` into `content/packs/` (git-ignored, never hand-edited), plus `manifest.json` listing each pack's file, version (`<pack_version>-<sha256 prefix>`), hash and size. The Android build (`bundlePacks` task) and the Xcode "Bundle Content Packs" phase copy them into the app. `PackInstaller` copies them into app storage on first launch and whenever the manifest version changes (DECISIONS D-013).

```bash
cd tools && uv sync
uv run python packs/build_all.py      # ~1 min after the first download (~100 MB of sources, cached in tools/.cache)
```

| Pack | Builder | Phase | Status |
|---|---|---|---|
| `dictionary.sqlite`: JMdict, KANJIDIC2, KRADFILE/RADKFILE, furigana, pitch, JLPT tags | `packs/build_dictionary.py` | 1 | ✅ |
| same file, `stroke` table (KanjiVG) | `packs/build_kanjivg.py` | 1 | ✅ |
| same file, `sentence`/`sentence_word` tables (Tatoeba) + frequency ranks | `packs/build_sentences.py` | 1 | ✅ |
| `kanji-path.sqlite` (60 levels: 243 radicals, 2,599 kanji, 7,242 words) | `packs/build_kanji_path.py` | 2 | ✅ |
| `grammar.sqlite` (N5–N3: 448 points, 3,486 Tatoeba examples; N2/N1 in Phase 7) | `packs/build_grammar.py` from `packs/grammar/n*.json` | 3 / 7 | ✅ N5–N3 |
| `exam.sqlite`: JLPT blueprints + JLPT/DLPT item banks | `packs/build_exam.py` from `items/jlpt_blueprints.json` and `items/bank/*.json` | 7 | In progress |

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
