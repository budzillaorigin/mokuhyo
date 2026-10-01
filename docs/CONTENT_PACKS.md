# Content packs

## Dictionary packs

One read-only SQLite pack per language at `content/packs/<lang>/dictionary.sqlite` (git-ignored, reproducible),
with a `content/packs/<lang>/dictionary.json` manifest (counts, sources with URL/date/SHA-256, license,
attribution, pack SHA-256, build time). Schema: `shared/src/commonMain/sqldelightDictionary/app/mokuhyo/dictionary/db/dictionary.sq`
(the builder executes its CREATE statements verbatim), the language-neutral schema of BRIEF §5.2:

| Table | Columns | Notes |
|---|---|---|
| `entry` | `id, lang, headword, reading?, pos, frequencyRank?` | `id` order = rank order; indexes on `(headword, frequencyRank)` and `(reading, frequencyRank)` |
| `sense` | `entryId, ord, gloss_en, domain?, register?` | English glosses; domain = Wiktionary topics / JMdict field; register = usage and regional tags |
| `form` | `surface, entryId, tags` | inflected and variant spellings → lemma (conjugation/declension tables, form-of entries, kana/kanji variants, traditional hanzi) |
| `example` | `entryId, ord, text, translation?, source` | up to 2 per entry (Wiktionary usage examples, constructed before quotations) |
| `fold` | `key, entryId, kind, frequencyRank` | fuzzy keys, only where the folded key differs from its text; kind 0 headword/reading, 1 form |
| `meta` | `key, value` | `language, source, license, attribution, entry_count, form_count, build_date, pack_version, schema_version, frequency` |

Text is stored NFC, never NFKC. Russian stress marks and Arabic/Persian vowel marks are removed from `form`
surfaces (they don't appear in running text); the Russian `reading` keeps the stress-marked form.

**Fold** (`DictionaryFold.kt` ⇄ `tools/packs/dict_fold.py`, both tested against
`shared/src/commonTest/resources/dictionary/fold_vectors.json`): NFC; full-width ASCII and half-width katakana
folded (only those characters); lowercase, ß→ss, curly apostrophes → `'`; non-spacing marks stripped except kana
voicing marks and the breve of й (accents, stress, ё→е, tashkeel, hamza seats, pinyin tones); ja katakana →
hiragana; ar/fa tatweel and ZWNJ removed, ى/ی→ي, ک→ك; zh whitespace removed; whitespace collapsed.

**Lookup** (`SqlDictionaryPack`): exact headword/reading → form index (LEMMA) → folded key against
headword/reading/form/fold table (FUZZY) → prefix of the folded key (PREFIX); each stage ranked by
`frequencyRank` (unranked last). `DictionaryPacks.open(file)` opens a pack read-only over JDBC.

### Sources and builder

`uv run --group content python packs/build_dictionary.py --language <xx|all> [--max-entries N]` (from `tools/`).
Inputs are pinned in `tools/packs/sources.lock` (URL, SHA-256, date) and downloaded to `tools/.cache/`.

| Language | Source (adapter) | Reading |
|---|---|---|
| `ja` | JMdict via jmdict-simplified 3.6.2 (`dict_jmdict.py`); headword = first kanji form else kana; other spellings as forms; inflection left to `lang/ja` | kana |
| `zh-Hans` | CC-CEDICT 2026-09-30 (`dict_cedict.py`); simplified headword, traditional as form, same simplified+pinyin merged | pinyin with tone marks |
| `es fr de pt-BR ru ko ar fa id` | English Wiktionary via kaikki.org 2026-09-28 (`dict_kaikki.py`); lemmas = entries with a non-form-of sense; forms from `forms[]` and form-of/alt-of entries | ko RR, ru stressed form, ar/fa romanization |

Ranking: wordfreq Zipf frequency of the headword (pt-BR→pt, zh-Hans→zh); affixes and headwords with stray
punctuation get no frequency; a capitalized headword sorts after its lower-case twin; JMdict entries not marked
common lose 1.5 Zipf. Kaikki languages keep the top 40,000 lemmas (`--max-entries`), JMdict and CC-CEDICT are
kept whole. pt-BR: when a lemma lists a Brazilian alternative spelling, the Brazilian spelling is the headword and
the European one a form tagged `Portugal`; Portugal-only senses sort last.

### Built packs (2026-10-01, Apple Silicon laptop)

| Pack | Entries | Senses | Forms | Examples | Fold keys | Size | Build |
|---|---:|---:|---:|---:|---:|---:|---:|
| ja | 218,776 | 253,483 | 102,803 | 0 | 113,475 | 40.0 MB | 18 s |
| zh-Hans | 123,910 | 199,398 | 77,861 | 0 | 124,824 | 23.5 MB | 6 s |
| es | 40,000 | 59,485 | 289,871 | 5,931 | 151,083 | 32.8 MB | 28 s |
| fr | 40,000 | 56,622 | 107,079 | 9,641 | 53,197 | 12.6 MB | 14 s |
| de | 40,000 | 58,511 | 174,008 | 11,275 | 110,615 | 18.6 MB | 23 s |
| pt-BR | 40,000 | 60,334 | 174,114 | 5,221 | 62,173 | 16.3 MB | 13 s |
| ru | 40,000 | 64,399 | 384,431 | 14,495 | 67,746 | 30.6 MB | 24 s |
| ko | 38,441 | 49,751 | 358,384 | 7,496 | 2,726 | 22.7 MB | 7 s |
| ar | 27,132 | 49,899 | 377,574 | 5,584 | 82,234 | 35.0 MB | 17 s |
| fa | 16,894 | 24,744 | 29,267 | 4,790 | 38,370 | 5.4 MB | 3 s |
| id | 35,695 | 51,452 | 30,064 | 2,055 | 9,354 | 6.0 MB | 2 s |

ko, ar, fa and id have fewer lemmas than the 40,000 cap in English Wiktionary. Lookup timing (`RealPacksTest`,
JVM, 61 lookups per pack including 1- and 2-character prefixes): mean 1.6–2.0 ms, p95 2.5–3.2 ms, max ≤ 5.1 ms.

### Tests

- `shared/src/commonTest/.../dictionary/SqlDictionaryPackTest` (fixture: every lookup path, ranking, lemmasOf),
  `DictionaryFoldTest` (shared vectors, no NFKC).
- `shared/src/jvmTest/.../dictionary/RealPacksTest`: 20 known words per language
  (`jvmTest/resources/dictionary/known_words.json`, inflected forms for the form index) and the 5 ms budget;
  languages without a built pack are skipped.
- `tools/packs/test_build_dictionary.py`: adapters on inline fixtures, ranking, pack writing, fold vectors.
