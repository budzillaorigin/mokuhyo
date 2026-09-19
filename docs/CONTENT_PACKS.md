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
| `readers.sqlite`: graded readers, 6 levels (N6 "level 0" … N1): 120 stories with read-along lines, vocabulary lists, comprehension questions and genre tasks (AI-drafted) | `packs/readers/build_readers.py` from `packs/readers/stories/*.json` | 12 | ✅ |
| `audio-<set>.zip`: VOICEVOX audio for exam, dialogues, minimal pairs, pitch test, grammar examples, graded readers (on-demand downloads, not bundled) | `packs/render_audio.py` | 10 | ✅ (grammar partial, readers not rendered yet) |

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

## readers.sqlite (graded readers, BRIEF_V2 §6.4, DECISIONS D-200…D-209)

Graded stories at six levels with read-along audio, vocabulary lists, comprehension questions and genre-based tasks. Schema: `shared/src/commonMain/sqldelightReaders/app/tsumugi/readers/db/readers.sq` (the builder executes its CREATE statements). The app reads it through `ReaderService.graded` (`PackReaderRepository`, `GradedReaderService`).

### Pipeline (`tools/packs/readers/`)

| File | What it is |
|---|---|
| `stories/*.json` | the story sources, one file per batch (`{"batch", "level", "license", "attribution", "passages": […]}`) |
| `levels.json` | levels, length ranges, text-score bands, coverage rules, question language, skim speed, audio speed, summary length |
| `tasks.json` | genre task templates (prediction, skim/scan, close reading, output), Japanese and English |
| `readers_lib.py` | shared code: the reader's sentence split, lemma grouping and dictionary resolution on `tools/items/lattice.py`, the §6.4 score, read-along voices |
| `validate_readers.py` | the gate (below); `--fix` fills vocabulary `entryId`/`gloss` from JMdict and NFC-normalizes; `--report` prints one line of measures per story |
| `draft_readers.py` | drafts more stories through an OpenAI-compatible endpoint (D-208) |
| `build_readers.py` | validates everything, then writes `content/packs/readers.sqlite`; run by `build_all.py` after the exam pack |

```bash
cd tools
uv run python packs/readers/validate_readers.py --report          # all stories; exit 1 on any error
uv run python packs/readers/build_readers.py                      # → content/packs/readers.sqlite
uv run python packs/readers/draft_readers.py --endpoint http://localhost:11434/v1 --model qwen2.5:14b --level N4 --count 5
```

The readers scripts read `dictionary.sqlite`, `tokenizer.sqlite` and `grammar.sqlite` from `content/packs`. In a git worktree without built packs they read the main checkout's; `--packs DIR` overrides.

### Story format

```jsonc
{
  "id": "gr-n4-007",                 // gr-<level>-NNN, unique across all files, never reused
  "level": "N4",                     // N6 (level 0) | N5 | N4 | N3 | N2 | N1
  "genre": "email",                  // news | recipe | ad | manga | essay | email | notice | editorial | academic | story
  "topic": "work",                   // short English tag
  "title": "…", "titleEn": "…",
  "body": "…\n…",                    // NFC, paragraphs separated by one newline, standard orthography
  "cast": [{"name": "ケン", "voice": "male"}],   // required when the body has speech; voice female | male | male-senior
  "names": ["みどり町"],             // other proper nouns, neutral for coverage
  "vocabulary": [{"word": "傘", "reading": "かさ", "entryId": 1301940, "gloss": "umbrella"}],   // 3–15
  "questions": [{"type": "detail", "stem": "…", "choices": ["…", "…", "…", "…"], "answer": 2, "explanation": "…"}],
  "source": "llm",                   // llm | verified
  "verified": false                  // set by tools/items/review.py or an in-app verdict
}
```

Manga-style dialogue is written `名前「…」`, one line per turn. Question stems and choices are Japanese from N3 and English below; explanations are English.

### The gate (`validate_readers.py`)

- Schema, NFC, unique ids, answer keys in range, 3–5 questions depending on the level, and 3–15 vocabulary items. Every vocabulary item must be a real JMdict id and must occur in the body.
- **Length** (non-whitespace characters): N6 300–500 · N5 300–650 · N4 400–850 · N3 550–1,100 · N2 700–1,400 · N1 850–1,500.
- **Coverage (D-202):** at least 95% of the words are within the level, counting glossed words, and at least 90% without them.
  - A word is within the level when its JLPT tag is at the level or easier. An untagged word also counts when it is within the level's slice of the frequency list (N6 800, N5 1,000, N4 2,000, N3 3,500, N2 6,000), when it is JMdict-common (N2, N1), or at N1 when it is any word the dictionary resolves.
  - Particles, auxiliaries, grammar words, interjections, affixes, numbers and names don't count.
- **Difficulty (D-203):** the §6.4 text score, reimplemented in Python, must fall in the level's band: N6 0–14 · N5 0–18 · N4 6–26 · N3 14–38 · N2 24–52 · N1 34–100. `RealReadersPackTest` checks the build's scores against the app's `DifficultyScorer`.

### Genre tasks (D-204)

Each story gets its genre's four tasks from `reader_task`, with `{title}`, `{seconds}`, `{min}` and `{max}` filled in.
- **Prediction:** a question to answer from the title, before reading.
- **Skim/scan:** a task with a timer. The time is characters ÷ (level `skimCpm` × genre rate) × 60, rounded up to 5 s, at least 20 s.
- **Close reading:** two prompts, followed by the story's comprehension questions.
- **Output:** a summary in Japanese, or a reply or opinion. The learner's model grades it with `grade_reading_summary`; the grade is labeled AI-generated, and without a model it is unavailable.

Quiz results are `exam_attempt` rows (`GRADED_READER`). They feed the roadmap's graded-reader milestone (D-206).

### Counts (build of 2026-09-18)

- **Stories:** 120, 20 per level (N6, N5, N4, N3, N2, N1). All are `source: "llm"` and unreviewed.
- **Read-along lines:** 2,393, one audio clip each.
- **Stories per genre:** story 15, manga 13, news 13, editorial 12, email 12, essay 12, notice 12, academic 11, ad 10, recipe 10.
- **Government/military (DLPT):** N2 `gr-n2-001`, `002`, `003`; N1 `gr-n1-002`, `003`, `011`, `014`.
- **Mean app text score per level:** N6 8.0 · N5 10.8 · N4 18.4 · N3 24.5 · N2 36.1 · N1 58.8. All 120 are within 3 points of the build's score; the mean difference is −0.01 (`RealReadersPackTest`).
- **Validator:** 0 errors, 0 warnings.

### Adding more stories

1. **Draft:** run `draft_readers.py --endpoint … --model … --level N3 --count 10 [--genre news] [--topic …]`. Passing stories land in a new `stories/<level>-draft-<date>.json` with the next free ids. Failures go to `tools/.cache/readers-rejected/`. You can also write a batch file by hand.
2. **Validate:** run `validate_readers.py --fix --report stories/<file>.json` until it is clean.
3. **Review:** run `uv run python items/review.py packs/readers/stories/<file>.json`, or use the in-app Content review and then `review.py --ingest`. Unreviewed stories keep the AI-generated badge.
4. **Rebuild:** run `build_readers.py` (or `build_all.py`), then render the new audio with `render_audio.py readers`. It renders only the new lines.

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
| `readers` | `reader/<story id>/<sentence index>` | `readers.sqlite` `reader_sentence`, one clip per reader sentence. 春日部つむぎ narrates; speech (a line opening with 「, optionally after the speaker's name) goes to the cast voice: female 四国めたん, male 玄野武宏, a second or older man the lower 玄野武宏 (D-205). The speaker-name prefix isn't spoken. Speed: level 0 0.85, N5 0.9, N4 0.95 |

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
uv run python packs/render_audio.py readers                 # graded readers (needs content/packs/readers.sqlite)
uv run python packs/render_audio.py readers --dry-run       # count the read-along lines
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
