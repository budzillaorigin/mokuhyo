# Content packs

Read-only SQLite files built by `tools/packs/*.py` into `content/packs/`, versioned, and `ATTACH`ed by the shared DB at runtime (BRIEF.md §3.4). Built packs are git-ignored and never hand-edited.

| Pack | Builder | Phase | Status |
|---|---|---|---|
| `dictionary.sqlite` (JMdict, JMnedict, KANJIDIC2, KRADFILE, furigana, pitch) | `packs/build_dictionary.py` | 1 | Not started |
| `strokes.sqlite` (KanjiVG) | `packs/build_kanjivg.py` | 1 | Not started |
| `sentences.sqlite` (Tatoeba) | `packs/build_sentences.py` | 1 | Not started |
| `kanji-path.sqlite` (60 levels) | `packs/build_kanji_path.py` | 2 | Not started |
| `grammar-n5…n1.sqlite` | `packs/build_grammar.py` | 3 / 7 | Not started |
| `jlpt-blueprints.json` | hand-maintained facts (timings, pass marks) | 7 | Not started |

Each pack's schema, version scheme, and the user-importable item-bank JSON schema are documented here as they are built.
