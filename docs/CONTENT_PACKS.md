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
| same file, `onomatopoeia` / `onomatopoeia_theme` tables: 1,334 JMdict on-mim words in 12 themes × 3 types, 1,309 with our feel line (1,307 also in Japanese), 502 with Tatoeba examples, one SVG glyph per theme | `packs/build_onomatopoeia.py` from `packs/onomatopoeia/*.json` | 12 | ✅ |
| `IlrBandData.kt` (not a pack): ILR bands + abstract lexicon for the difficulty score, generated from `items/ilr_bands.json` | `packs/gen_ilr_bands.py` (`--check` verifies) | 11 | ✅ |
| `kanji-path.sqlite` (60 levels: 243 radicals, 2,599 kanji, 7,242 words) | `packs/build_kanji_path.py` | 2 | ✅ |
| `grammar.sqlite` (N5–N1: 829 points, 5,116 Tatoeba examples; matched in order n3, n4, n5, n2, n1 so harder points don't take easier points' sentences; 829 Japanese explanations in `grammar_point_ja` for monolingual mode) | `packs/build_grammar.py` from `packs/grammar/n*.json` | 3 / 7 / 12 | ✅ |
| `exam.sqlite`: JLPT blueprints + 4 banks (2,368 JLPT items: 1,878 rule-generated, 490 AI-drafted; DLPT 100 passages / 306 items, AI-drafted) | `packs/build_exam.py` from `items/jlpt_blueprints.json` and `items/bank/*.json` | 7 | ✅ |
| `practice.sqlite`: 30 scenarios, 62 OPI questions, 45 dialogues, 630 minimal pairs | `packs/build_practice.py` | 6 | ✅ |
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

### Onomatopoeia (`onomatopoeia`, `onomatopoeia_theme`; Phase 12, DECISIONS D-235…D-237)

Schema: `shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/onomatopoeia.sq`. `tools/packs/build_onomatopoeia.py` runs after `build_decks.py`, replaces only these two tables, and takes about 3 minutes (the example search and the VACUUM of the 127 MB pack).

- **Words.** Every JMdict entry with an `on-mim` sense, most frequent first (`ord`). Vulgar or X-rated senses are left out, as are entries marked `exclude` in `entries.json`. `text` is the first kana form, and `variants` holds the other kana and kanji forms (JSON). `gloss` holds the first on-mim sense's glosses (JMdict, CC BY-SA 4.0).
- **Our data** (`tools/packs/onomatopoeia/`):
  - `themes.json`: 12 themes (id, English and Japanese title, blurb, SVG glyph).
  - `entries.json`: per JMdict id, `theme`, `type` (giongo/gitaigo/gijougo), `feel` (ASCII, at most 100 characters, never containing the word: it's the quiz prompt), optional `feel_ja` (at most 40 characters), `source` ("llm" until reviewed), and optional `exclude`.
  - Words missing from `entries.json` get a rule-based theme and type (`source = "rule"`, no feel).
- **Examples.** Up to 3 Tatoeba sentence ids (JSON) from the pack's `sentence` table, at most 40 characters, containing one of the word's forms. They come from the word index, or from a literal search for forms of 3 or more characters.
- **Adding more.**
  - `build_onomatopoeia.py merge drafts.json` merges hand-written or agent-written drafts.
  - `build_onomatopoeia.py draft --endpoint http://localhost:11434/v1 --model qwen3:8b --limit 100` drafts the missing feel lines through any OpenAI-compatible endpoint.
  - Both validate every line and add only missing ids or feel lines.
  - `status` prints the counts.
- **Current build (2026-09-18).**
  - 1,334 words (1,340 on-mim entries, minus 3 vulg/X and 3 excluded by hand), 1,309 with a feel line and 1,307 with a Japanese one. The 720 most frequent all have both.
  - 502 words have examples; the rest show gloss and feel only.
  - Themes: sounds 320, movement 183, manner 162, appearance 140, voice 116, texture 106, state 91, feelings 88, body 46, eating 44, pain 20, weather 18.
  - `pack_meta`: `onomatopoeia`, `onomatopoeia_with_feel`, `onomatopoeia_with_feel_ja`, `onomatopoeia_with_examples`.

## grammar.sqlite: Japanese explanations (`grammar_point_ja`; Phase 12, DECISIONS D-233)

Monolingual mode shows our own Japanese explanation of a grammar point instead of the English one.
- **Sources.** `meaning_ja` (at most 45 characters, 国語辞典-style) and `nuance_ja` (at most 150 characters, だ・である) sit next to the English fields in `tools/packs/grammar/n*.json`. They have their own provenance, `ja_source` ("llm" until reviewed).
- **Pack.** `build_grammar.py` writes them to `grammar_point_ja(point_id, meaning_ja, nuance_ja, source)`, and `pack_meta.points_ja` holds the count.
- **Tooling.** `packs/grammar_ja.py` has four commands, `status`, `check`, `merge <drafts.json>` and `draft --endpoint URL --model NAME [--level N3]`. It only fills points without Japanese.
- **Current sources.** 829 of 829 points: N5 127, N4 148, N3 173, N2 192, N1 189. Claude drafted them directly (owner decision), and they carry the badge until reviewed.

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

**Left to render:** grammar examples 2..n. `render_audio.py grammar` renders the second example per point (+829 clips, ~35 min here); `--grammar-all` renders the other 6,774 (~4.5 h here, minutes with a VOICEVOX GPU build). Both reuse the cache.

### Moving rendered audio between machines

`tools/packs/audio_release.py publish` uploads `content/packs/audio-*.zip` and `audio-manifest.json` to a pre-release of this repo, tagged `audio-packs-<date>`, and pins their hashes in `tools/packs/audio.lock`. `… fetch` downloads and verifies them on another machine, e.g. the Mac. The current release is `audio-packs-2026-09-19` ("Audio packs (VOICEVOX) 2026-09-19", republished the same day after the voice change; the earlier release under that tag was deleted): pitch 300, minimal pairs 1,260, dialogues 290, exam 1,032, grammar 829 clips (the first example per point). Its notes carry the VOICEVOX credits. `publish` compares GitHub's asset digest, not just the size, so a same-day re-render replaces the changed files. This is for the owner's machines only. Learners install packs from Files or from a URL they type in Settings → Audio packs; there is no default download URL (D-096).
