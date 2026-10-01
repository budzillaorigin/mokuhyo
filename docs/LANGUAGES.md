# Languages

Per-language implementation notes for the `LanguageModule` contract (BRIEF §4). Everything language-specific is
data or a module registered in `shared/src/jvmMain/kotlin/app/mokuhyo/lang/LanguageRegistry.kt`; contract tests in
`shared/src/jvmTest/kotlin/app/mokuhyo/lang/LanguageModuleContractTest.kt` run for all 11.

| Code | Segmentation / lemma | Dictionary (pack `dictionary.sqlite`) | Voice (run time) | Reading aids | Notes |
|---|---|---|---|---|---|
| ja | Lattice tokenizer over mecab-ipadic (`tokenizer.sqlite`) → dictionary form + reading; ICU fallback | JMdict, all 218k entries | OS voice (macOS Kyoko; Windows needs ja speech pack) | Furigana (lattice readings), romaji | No redistributable Japanese Piper voice (D-013) |
| zh-Hans | ICU dictionary-based word break | CC-CEDICT, 124k entries, pinyin with tone marks | OS voice (macOS Tingting) | Pinyin (ICU Han-Latin), Traditional toggle (ICU Simplified-Traditional) | Piper zh voices excluded (licence lineage) |
| ko | ICU word break + particle/ending stripping for lemma candidates | Wiktionary (kaikki), 38k lemmas, RR readings | OS voice (macOS Yuna) — open decision 4 default (a) | Romanization (ICU Hangul-Latin) | KSS voice is NC |
| es, fr, de, pt-BR, id | ICU word break; form index from Wiktionary inflection tables | Wiktionary (kaikki), top 40k lemmas by frequency (id 36k) | Piper: es davefx/sharvard, fr siwis/upmc, de thorsten, pt-BR faber/cadu; id: OS voice (macOS Damayanti) | — | pt-BR prefers Brazilian spellings |
| ru | ICU; Wiktionary forms (stress marks stripped from surfaces) | Wiktionary, 40k lemmas, stress-marked readings | Piper dmitri/denis | Romanization (BGN), stress shown in readings | ё/е folded for comparison |
| ar | ICU; tashkeel-insensitive fold | Wiktionary, 27k lemmas | OS voice (macOS Majed) | Short-vowel toggle, romanization | RTL throughout passages; MSA only (open decision 7) |
| fa | ICU (ZWNJ kept inside words) | Wiktionary, 17k lemmas, Iranian romanization | Piper amir/ganji | Romanization (BGN) | Piper Persian pronunciation is poor (espeak-ng phonemes); gate_lang known gap |

Fonts: Noto Sans (Latin, Cyrillic), Noto Sans JP/SC/KR, Noto Naskh Arabic — bundled, pinned in
`tools/release/fonts.lock`. Comparison folding (`Fold.forCompare`) and dictionary key folding (`DictionaryFold`)
share the rule "fold only listed characters, never blanket NFKC" (CLAUDE.md, Tsumugi rule 7).

## Adding a 12th language
1. Add a `LanguageInfo` row in `shared/.../lang/Languages.kt` (code, names, direction, whisper code) and a
   `Scripts.of` case.
2. Dictionary: a source adapter in `tools/packs/` (kaikki covers most languages) and `build_dictionary.py --language xx`.
3. Content: `tools/items/gen_dlpt.py fill --language xx`, `tools/opi/draft_opi.py --language xx` (add a register
   profile to `tools/opi/profiles.json` first), `tools/packs/build_packs.py --language xx`.
4. Voice: a redistributable Piper voice in `tools/voices/manifest.py`, or the OS voice.
5. Samples for the contract test (`shared/src/jvmTest/resources/lang/samples.json`), 20 known words
   (`.../dictionary/known_words.json`), a smoke sentence in `LangSmoke.sentences`.
