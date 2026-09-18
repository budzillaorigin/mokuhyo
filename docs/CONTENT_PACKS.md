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
| `jlpt-blueprints.json` | hand-maintained facts (timings, pass marks) | 7 | Not started |

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
