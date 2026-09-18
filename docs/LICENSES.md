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
| Okio | shared | Apache-2.0 | © Square, Inc. — https://github.com/square/okio |
| Google ML Kit Text Recognition v2, Japanese (bundled model) | androidApp | ML Kit Terms of Service (free, on-device, no API key) | https://developers.google.com/ml-kit/terms — used for camera/photo OCR only (BRIEF §3.2) |
| AndroidX Activity, Lifecycle, Jetpack Compose (UI, Material 3) | androidApp | Apache-2.0 | © The Android Open Source Project — https://developer.android.com/jetpack/androidx |
| AndroidX WorkManager (work-runtime 2.11.2, with its transitive AndroidX Room, Startup and Concurrent libraries) | androidApp | Apache-2.0 | © The Android Open Source Project — https://developer.android.com/jetpack/androidx/releases/work — background model downloads (F-13) |
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

Word frequency ranks are computed by `tools/packs/build_sentences.py` from Tatoeba's indexed corpus.

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
| JLPT / DLPT item banks (`tools/items/bank/`) | CC BY-SA 4.0 | Tsumugi contributors; rule-generated items from JMdict/Tatoeba/grammar packs keep their sources' attribution; AI-drafted items carry the badge until reviewed. Not affiliated with JLPT, DLI or ACTFL |

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
| Nihongo Alive: Listen & Learn from Real-Life Conversations | textbook + audio | Natural dialogues with fillers |
| 日本語NOW! Nihongo Now!: Performing Japanese Culture | textbook | Performing-culture track, memorize-and-perform mode |
| 生活者としての外国人向け 私らしく暮らすための日本語ワークブック (Bunka-cho) | workbook | Daily-life admin track, can-do statements (content only if its license is confirmed CC BY; not ingested today) |
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
