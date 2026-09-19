# Third-party licenses

Every dependency and data asset used by Tsumugi, with its license and attribution. The in-app Licenses screen renders this file. Add a row **in the same commit** that adds the dependency (CLAUDE.md).

## Code linked into the apps

| Component | Used by | License | Attribution / source |
|---|---|---|---|
| Kotlin standard library | shared, androidApp | Apache-2.0 | © JetBrains s.r.o. — https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | shared | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | shared | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx.serialization |
| Ktor client (core, OkHttp, Darwin engines) | shared | Apache-2.0 | © JetBrains s.r.o. — https://github.com/ktorio/ktor |
| OkHttp / Okio (via Ktor OkHttp engine) | shared (Android) | Apache-2.0 | © Square, Inc. — https://github.com/square/okhttp |
| SQLDelight (runtime, android/native drivers, coroutines extensions) | shared | Apache-2.0 | © Square, Inc. / Cash App — https://github.com/sqldelight/sqldelight |
| SKIE (runtime + Swift bridging) | shared (iOS) | Apache-2.0 | © Touchlab — https://github.com/touchlab/SKIE |
| FSRS-6 algorithm (ported to Kotlin in `shared/srs/Fsrs.kt`, `FsrsOptimizer.kt`) | shared | MIT | Algorithm ported from py-fsrs, © Open Spaced Repetition — https://github.com/open-spaced-repetition/py-fsrs |
| kotlinx-datetime | shared | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx-datetime |
| Okio | shared, androidApp | Apache-2.0 | © Square, Inc. — https://github.com/square/okio |
| Google ML Kit Text Recognition v2, Japanese (bundled model) | androidApp | ML Kit Terms of Service (free, on-device, no API key) | https://developers.google.com/ml-kit/terms — used for camera/photo OCR only (BRIEF §3.2) |
| AndroidX Activity, Lifecycle, Jetpack Compose (UI, Material 3) | androidApp | Apache-2.0 | © The Android Open Source Project — https://developer.android.com/jetpack/androidx |
| AndroidX WorkManager (work-runtime 2.11.2, with its transitive AndroidX Room, Startup and Concurrent libraries) | androidApp | Apache-2.0 | © The Android Open Source Project — https://developer.android.com/jetpack/androidx/releases/work — background model downloads (F-13) |
| AndroidX Media3 1.11.1 (ExoPlayer, UI, Transformer, with their media3-common, -container, -database, -datasource, -decoder, -effect, -extractor and -muxer modules) | androidApp | Apache-2.0 | © The Android Open Source Project — https://github.com/androidx/media — media player, clip cutting (G-04, G-15) |
| AndroidX Glance 1.2.0 (glance, glance-appwidget, with its appwidget-proto and repackaged protobuf-lite modules) | androidApp | Apache-2.0 (the repackaged protobuf-lite: BSD-3-Clause, © Google LLC) | © The Android Open Source Project — https://developer.android.com/jetpack/androidx/releases/glance — home-screen widget (G-15) |
| llama.cpp (b11040; prebuilt xcframework on iOS, built from source via CMake on Android) | iosApp, androidApp | MIT | © 2023-2026 The ggml authors — https://github.com/ggml-org/llama.cpp |
| whisper.cpp (b5130; prebuilt xcframework on iOS, built from source via CMake on Android) | iosApp, androidApp | MIT | © 2023-2026 The ggml authors — https://github.com/ggml-org/whisper.cpp |
| ggml (tensor library bundled inside llama.cpp and whisper.cpp) | iosApp, androidApp | MIT | © 2023-2026 The ggml authors (originally Georgi Gerganov) — https://github.com/ggml-org/ggml |

## Sync server (`server/`, runs on the server; not linked into the apps)

| Component | License | Source |
|---|---|---|
| Ktor server (core, Netty, auth, auth-jwt, content negotiation, status pages, rate limit) | Apache-2.0 | https://github.com/ktorio/ktor |
| Netty (via Ktor) | Apache-2.0 | https://netty.io |
| java-jwt (Auth0, via ktor-server-auth-jwt) | MIT | https://github.com/auth0/java-jwt |
| HikariCP | Apache-2.0 | https://github.com/brettwooldridge/HikariCP |
| Flyway Community (flyway-core, flyway-database-postgresql) | Apache-2.0 | https://github.com/flyway/flyway |
| PostgreSQL JDBC driver | BSD-2-Clause | https://github.com/pgjdbc/pgjdbc |
| sqlite-jdbc (xerial) — dev mode and tests | Apache-2.0 | https://github.com/xerial/sqlite-jdbc |
| Password4j (Argon2id password hashing) | Apache-2.0 | https://github.com/Password4j/password4j |
| WebAuthn4J (passkeys; `webauthn4j-test` in tests only) | Apache-2.0 | https://github.com/webauthn4j/webauthn4j |
| Jackson (via WebAuthn4J) | Apache-2.0 | https://github.com/FasterXML/jackson |
| SLF4J Simple (logging) | MIT | https://www.slf4j.org |
| Testcontainers PostgreSQL — tests only | MIT | https://github.com/testcontainers/testcontainers-java |
| Docker images: eclipse-temurin (JDK/JRE), postgres:16-alpine, caddy:2 — deployment only | GPLv2+CE (Temurin), PostgreSQL License, Apache-2.0 (Caddy) | https://adoptium.net · https://www.postgresql.org · https://caddyserver.com |

## Build tooling (not shipped in binaries)

| Component | License | Source |
|---|---|---|
| Gradle + Gradle wrapper | Apache-2.0 | https://gradle.org |
| Android Gradle Plugin | Apache-2.0 | https://developer.android.com/build |
| SQLDelight Gradle plugin, SKIE Gradle plugin | Apache-2.0 | see above |
| ruff (Python lint, `tools/`) | MIT | https://github.com/astral-sh/ruff |
| Pillow (renders the iOS app icon, `tools/assets/render_icon.py`; `assets` dependency group) | MIT-CMU (HPND) | https://github.com/python-pillow/Pillow — https://github.com/python-pillow/Pillow/blob/main/LICENSE |
| sqlite-jdbc (xerial), via SQLDelight sqlite-driver — tests only | Apache-2.0 | https://github.com/xerial/sqlite-jdbc |
| Ktor client mock engine — tests only | Apache-2.0 | https://github.com/ktorio/ktor |
| VOICEVOX Engine 0.25.2 (Windows CPU build; renders the audio packs, `tools/packs/render_audio.py`) | LGPL-3.0 (engine code); the bundled VOICEVOX CORE and voice libraries under their own terms (see "Pre-rendered audio" below) | https://github.com/VOICEVOX/voicevox_engine — runs on the build machine only; nothing of it is linked into or shipped with the apps, only the audio it renders |
| FFmpeg 8.1 (BtbN static `winarm64-lgpl` build; encodes the audio packs to AAC) | LGPL-2.1+ (this build has no GPL components) | https://ffmpeg.org · https://github.com/BtbN/FFmpeg-Builds — build machine only, run as a separate program, never linked into the apps |

## Data and content (dictionary pack)

| Data | Source | License | Attribution text |
|---|---|---|---|
| JMdict (English) | EDRDG, via scriptin/jmdict-simplified JSON releases | CC BY-SA 4.0 | This application uses the JMdict/EDICT and KANJIDIC dictionary files. These files are the property of the Electronic Dictionary Research and Development Group, and are used in conformance with the Group's licence. https://www.edrdg.org/edrdg/licence.html |
| KANJIDIC2 | EDRDG, via jmdict-simplified | CC BY-SA 4.0 | (as above) |
| KRADFILE / RADKFILE | EDRDG (Michael Raine, Jim Breen), via jmdict-simplified | CC BY-SA 4.0 | (as above) |
| KanjiVG | Ulrich Apel, https://kanjivg.tagaini.net | CC BY-SA 3.0 | Stroke order data © Ulrich Apel, KanjiVG project. |
| JmdictFurigana | Doublevil, https://github.com/Doublevil/JmdictFurigana (release `2.3.1+2026-08-25`) | Data: CC BY-SA 4.0, "the same licence as JMdict" (README, "Licence" section: https://github.com/Doublevil/JmdictFurigana#licence). Generator code: MIT (https://github.com/Doublevil/JmdictFurigana/blob/master/LICENSE) | Furigana alignments from JmdictFurigana by Doublevil, derived from JMdict (EDRDG). |
| Pitch accent (`data/source_files/raw/accents.txt`) | mifunetoshiro/kanjium at commit `8a0cdaa` | CC BY-SA 4.0. License file: https://github.com/mifunetoshiro/kanjium/blob/8a0cdaa16d64a281a2048de2eee2ec5e3a440fa6/LICENSE.txt. The README states "Everything in this package is licensed under the Creative Commons Attribution-ShareAlike 4.0 International license" (https://github.com/mifunetoshiro/kanjium/blob/8a0cdaa16d64a281a2048de2eee2ec5e3a440fa6/README.md) | Pitch accent data from Kanjium by mifunetoshiro (https://github.com/mifunetoshiro/kanjium), CC BY-SA 4.0. As Kanjium's README asks: "The pitch accent notation, verb particle data, phonetics, homonyms and other additions or modifications to EDICT, KANJIDIC or KRADFILE were provided by Uros O. through his free database." |
| JLPT vocabulary levels (unofficial) | Jonathan Waller's JLPT Resources (http://www.tanos.co.uk/jlpt/), mapped to JMdict ids by stephenmk/yomitan-jlpt-vocab at commit `b062d4e` (`original_data/n1..n5.csv`) | Mapping: CC BY-SA 4.0 (https://github.com/stephenmk/yomitan-jlpt-vocab/blob/b062d4e38c4bdd0950ae1d4ec55f04b176182e03/LICENSE.txt). Lists: CC BY, per the repository README's Attribution section (https://github.com/stephenmk/yomitan-jlpt-vocab/blob/b062d4e38c4bdd0950ae1d4ec55f04b176182e03/README.md#attribution) and tanos.co.uk | JLPT lists by Jonathan Waller (tanos.co.uk), CC BY; JMdict mapping by stephenmk (yomitan-jlpt-vocab), CC BY-SA 4.0. There has been no official JLPT vocabulary list since 2010. |
| JLPT kanji levels (unofficial) | davidluzgouveia/kanji-data at commit `00fd707` (`jlpt_new` field only; no WaniKani fields are used) | MIT (https://github.com/davidluzgouveia/kanji-data/blob/00fd7079c3890f430759536f91aa5e854ec0ca4f/LICENSE) | © 2019 David Gouveia. |
| Example sentences + word index | Tatoeba, https://tatoeba.org | CC BY 2.0 FR | Example sentences from the Tatoeba project. |
| Tokenizer lexicon (`tokenizer.sqlite`) | mecab-ipadic 2.7.0-20070801 | NAIST/ICOT free license (BSD-style) | © 2000–2003 Nara Institute of Science and Technology; entries from ICOT Free Software. Use, reproduction and distribution permitted; the copyright notice and the NO WARRANTY conditions must accompany copies. |

Word frequency ranks are computed by `tools/packs/build_sentences.py` from Tatoeba's indexed corpus. The Core 2k/6k/10k frequency list (`freq_word`, `tools/packs/build_decks.py`) is derived from those Tatoeba counts and JMdict's common flags, so it carries the Tatoeba (CC BY 2.0 FR) and EDRDG (CC BY-SA 4.0) attributions above. The abstract-vocabulary lexicon in `IlrBandData.kt` is generated from `tools/items/ilr_bands.json` (Tsumugi contributors, CC BY-SA 4.0).

Exact versions (URL, release or commit, date, sha256) of every source above are pinned in `tools/packs/sources.lock`.

## On-device models (downloaded on request, never bundled)

Listed in `content/models/manifest.json`; the in-app model manager shows each model's license before download. Only permissively licensed models are offered.

| Model | License | Source |
|---|---|---|
| Qwen2.5-1.5B-Instruct (GGUF Q4_K_M) | Apache-2.0 | © Alibaba Cloud (Qwen team) — https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF |
| Qwen2.5-7B-Instruct (GGUF Q4_K_M) | Apache-2.0 | © Alibaba Cloud (Qwen team) — https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF |
| Whisper base / small (ggml conversions) | MIT | © OpenAI; ggml weights via https://huggingface.co/ggerganov/whisper.cpp |

## Practice and exam content

| Content | License | Notes |
|---|---|---|
| Role-play scenarios, listening dialogues (`tools/packs/speaking`, `tools/packs/listening`) | CC BY-SA 4.0 | Tsumugi contributors; AI-drafted, shown with the "AI-generated" badge until human-reviewed |
| OPI practice questions and self-rating statements | CC BY-SA 4.0 | Statements paraphrase the public-domain ILR skill level descriptions (https://www.govtilr.org) |
| Minimal pairs | derived from JMdict (CC BY-SA 4.0, EDRDG) and Kanjium pitch data | computed by `build_practice.py`, no new text |
| JLPT blueprints (`tools/items/jlpt_blueprints.json`) | facts | Published test structure from https://www.jlpt.jp; no official items are used |
| Interest and domain tracks (`tools/packs/tracks/*.json` → `tracks.sqlite`): word lists, gaming kanji breakdowns and memory hints, scenarios, dialogues, drills, can-do statements, cultural tasks, ILR readings | CC BY-SA 4.0 | Tsumugi contributors; original, AI-drafted (`source: "llm"`), badge on until reviewed. Word ids, readings, JLPT tags, pitch and derived kanji subsets come from JMdict/KANJIDIC2/KRADFILE (CC BY-SA 4.0, EDRDG) and Kanjium through the dictionary pack. The military track's people, units and events are fictional |
| Official defence pages (JMSDF https://www.mod.go.jp/msdf/, JASDF https://www.mod.go.jp/asdf/, JGSDF https://www.mod.go.jp/gsdf/, MOD press releases https://www.mod.go.jp/j/press/) | link only | Listed as outbound links in the military track; no text is fetched, stored or copied |
| Graded readers: stories, questions, genre task templates (`tools/packs/readers/`, `readers.sqlite`) | CC BY-SA 4.0 | Tsumugi contributors; AI-drafted (launch set drafted by Claude, later batches by the owner's model), shown with the "AI-generated" badge until human-reviewed. Vocabulary glosses from JMdict (EDRDG, CC BY-SA 4.0). Genre structure inspired by the Routledge genre-based reader; no content from it |
| JLPT / DLPT item banks (`tools/items/bank/`) | CC BY-SA 4.0 | Tsumugi contributors; rule-generated items from JMdict/Tatoeba/grammar packs keep their sources' attribution; AI-drafted items carry the badge until reviewed. Not affiliated with JLPT, DLI or ACTFL |

## Courses, monolingual mode and onomatopoeia (Phase 12)

| Content | License | Notes |
|---|---|---|
| Japanese grammar explanations (`meaning_ja` / `nuance_ja` in `tools/packs/grammar/n*.json`, `grammar_point_ja` in `grammar.sqlite`) | CC BY-SA 4.0 | Tsumugi contributors. Written for Tsumugi by an LLM (Claude, owner decision) in its own words from our English explanations. No textbook, grammar dictionary or website text is used. They show the "AI-generated" badge until reviewed (`ja_source`) |
| Onomatopoeia word list and glosses (`onomatopoeia` table in `dictionary.sqlite`) | CC BY-SA 4.0 | JMdict entries tagged `on-mim` (EDRDG; attribution as for JMdict above) |
| Onomatopoeia example sentences | CC BY 2.0 FR | The Tatoeba sentences already in the dictionary pack (attribution as above) |
| Onomatopoeia themes, types and "feel" lines (`tools/packs/onomatopoeia/entries.json`) | CC BY-SA 4.0 | Tsumugi contributors. Written for Tsumugi by an LLM (Claude) in its own words; badge until reviewed. Nothing from the Onomato Project is used |
| Onomatopoeia theme glyphs (12 SVGs in `tools/packs/onomatopoeia/themes.json`) | CC BY-SA 4.0 | Original drawings for Tsumugi, one per theme (not per word) |
| Monolingual word paraphrases (`paraphrase_word_ja`) | the learner's own | Generated on the learner's device (or their own endpoint) on demand, cached locally, labeled AI-generated; nothing is shipped |

## Pre-rendered audio (VOICEVOX)

The audio packs (`audio-<set>.zip`: exam listening, dialogues, minimal pairs, pitch-accent test, grammar examples, graded-reader read-along) are synthesized at build time with the VOICEVOX engine (BRIEF_V2 §5.6, DECISIONS D-090). They are optional downloads. The voices' terms allow commercial and non-commercial use of the generated audio **with a credit line**, and forbid use that damages the characters' or voice providers' image, political or religious use, deception, and training new voice models on the audio. The credit lines below are required and are shown here, on the in-app Licenses screen.

| Voice (style ノーマル, engine speaker id) | Used for | Required credit | Terms |
|---|---|---|---|
| VOICEVOX (software) | all audio packs | VOICEVOX | Engine terms: "ご利用の際は VOICEVOX を利用したことがわかるクレジット表記が必要です" — https://voicevox.hiroshiba.jp/term/ |
| 春日部つむぎ (8) | female speakers, pitch-accent and minimal-pair words, grammar examples, graded-reader narration | **VOICEVOX:春日部つむぎ** | https://tsumugi-official.studio.site/rule (character page: https://voicevox.hiroshiba.jp/product/kasukabe_tsumugi/) |
| 四国めたん (2) | second female speaker, narrators and announcements, female speech in graded readers | **VOICEVOX:四国めたん** | https://zunko.jp/con_ongen_kiyaku.html ("アプリなどでの利用の場合は、アプリの紹介画面などに記載をお願いします") |
| 玄野武宏 (11) | male speakers (a second male speaker is the same voice, slightly lower and slower), grammar examples, male speech in graded readers | **VOICEVOX:玄野武宏** | https://www.virvoxproject.com/voicevoxの利用規約 (VirVox Project; example credit "VOICEVOX:玄野武宏(CV:ガロ)") |

Credits: VOICEVOX:春日部つむぎ, VOICEVOX:四国めたん, VOICEVOX:玄野武宏

Each pack's `index.json` also lists the credit lines for the voices it actually uses. Text spoken in the packs comes from the exam, practice, grammar and graded-reader content above, under those licenses.

## Inspiration, no content used

These resources shaped Tsumugi's structure, pedagogy or feature ideas (BRIEF_V2 §7, Appendix B). **No text, audio, images, item lists, mnemonics, exercises or other content from them is copied into the app, its packs or its generators.** Where a feature follows one of them, it is built from the licensed data above or from original, labeled content.

| Resource | Kind | What it informed |
|---|---|---|
| にほんごで働く！ビジネス日本語30時間 | textbook | Business & keigo track structure |
| Japanese In-Law: Words and Phrases for Day-to-Day Living | phrasebook | Family & household track topics |
| 小学生の語彙力アップ 実践練習ドリル1100 | drill book | Native schoolchild vocabulary track, drill formats |
| Routledge Intermediate to Advanced Japanese Reader (genre-based) | textbook | Reader genre tags and task types |
| Japanese–English Translation: An Advanced Guide (Wakabayashi) | book | Translation workbench workflow |
| Kanji Adventures for Gamers and VTuber Fans (Nihongo Picnic) | course | Gaming & VTuber track, component hints |
| Chika Sensei's Japanese Academy | courses, free grammar lists | Mastery checklist, Japanese-only N1 mode |
| 日本語の森 JLPT この一冊で合格する | book series, videos | "One book to pass" course view |
| Outlier Linguistics' Kanji Masterclass | course | Functional components and sound series (our data is derived from KanjiVG + KANJIDIC2) |
| 日本語表現インフォ (hyogen.info) | website | Expression thesaurus, collocations, writing studio |
| まほろばことば (mahoblog) | blog | Poetry corner (texts come only from public-domain sources) |
| YOMUJP | website | Graded readers with audio, level scheme |
| Japan Reader | app | Annotations, screenshot import, context drills |
| Tofugu | website | Guides library (outbound links only), kana course method |
| Tae Kim's Guide, Imabi, Wasabi, Sakubi, Kanshudo, JLPT Sensei, Japanese with Anime, NHK Easy Japanese / News Web Easy | websites | Guides library: outbound links with our own titles and tags (D-166); nothing is fetched, stored or copied |
| Nihongo Alive: Listen & Learn from Real-Life Conversations | textbook + audio | Natural dialogues with fillers |
| 日本語NOW! Nihongo Now!: Performing Japanese Culture | textbook | Performing-culture track, memorize-and-perform mode |
| 生活者としての外国人向け 私らしく暮らすための日本語ワークブック and the 「生活者としての外国人」 curriculum / 教材例集 (Bunka-cho) | workbook, curriculum | Daily-life admin track: situation list and can-do statements as a format. Not CC BY (see "Bunka-cho license check" below), so nothing is ingested |
| つながるひろがる にほんごでのくらし (Bunka-cho / MEXT learning site) | website | Daily-life situations. Its terms forbid reproduction beyond private use (see below), so nothing is ingested |
| にほんごで文化体験 | textbook | Cultural experience tasks |
| Yomimono | web textbook | Module structure: kanji → vocab → grammar → quiz |
| JPDB.io | web app | Media decks, coverage, known-words import, frequency decks |
| Immersion Kit | web app / API | Sentence bank from the learner's own media; optional online source queried live, nothing cached into packs |
| AxTongue | web app | Synced lyrics, cloze-in-lyrics |
| Japanese Graph | web app | Kanji graph view |
| コツ Pitch Accent Minimal Pairs Test (kotu.io) | web app | Pitch-accent perception test format |
| Onomato Project | web app | Onomatopoeia module (our data comes from JMdict's `on-mim` tag) |
| Ulangi | open-source app | Reflex and Atom game mechanics (no code used) |
| Japanese Swotter | podcast | Drill-set format (prompt → pause → answer) |
| Refold | methodology | Immersion roadmap and log, 1T mining |
| USJETAA Japanese Reading Group | community | Reading-circle mode |
| Lorenzi's Jisho | web dictionary | Inflection breakdown, component shortcuts |
| WaniKani, Bunpro, NativShark, Skritter, Lingopie | apps (BRIEF v1) | Feature ideas only; imports talk to the learner's own account, and no mnemonics or write-ups are copied |

### Bunka-cho license check (BRIEF_V2 §6.5 and §7, checked 2026-09-19, DECISIONS D-219)

- **つながるひろがる にほんごでのくらし** (https://tsunagarujp.mext.go.jp/terms-of-use, 利用規約 dated 令和2年6月1日):
  - 第4条: the site's content (videos, illustrations, scripts, vocabulary and grammar lists) is copyright 文部科学省.
  - 第6条: 「本サイト上のコンテンツの全部または一部について、私的使用以外の目的で転載・複製することは認めない」. Use for education, and quotation within what the law allows, needs 「出典 文部科学省」.
  - This is **not CC BY**. Because the terms state their own conditions, MEXT's general CC BY 4.0-compatible website terms (https://www.mext.go.jp/b_menu/1351168.htm) don't apply.
  - **Result: inspiration only.** No text, scripts or lists are used.
- **「生活者としての外国人」 curriculum, 教材例集 and workbook** (https://www.bunka.go.jp/seisaku/kokugo_nihongo/kyoiku/nihongo_curriculum/toriatsukai.html):
  - Copyright belongs to 文化庁 except where another holder is named.
  - Illustrations and photos may be reused in Japanese-teaching materials with attribution. Commercial reuse needs each rights holder's consent, and modified illustrations are not allowed.
  - This is **not CC BY**. bunka.go.jp's general terms defer to MEXT's CC BY 4.0-compatible terms (https://www.bunka.go.jp/bunkacho_homepage/), but those terms exclude content with separately stated conditions and third-party material.
  - **Result: inspiration only.** The daily-life track is entirely original. Ingesting any of it later needs written permission, or a CC BY notice on that specific material.
