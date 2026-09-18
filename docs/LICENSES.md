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
| AndroidX Activity, Lifecycle, Jetpack Compose (UI, Material 3) | androidApp | Apache-2.0 | © The Android Open Source Project — https://developer.android.com/jetpack/androidx |

## Build tooling (not shipped in binaries)

| Component | License | Source |
|---|---|---|
| Gradle + Gradle wrapper | Apache-2.0 | https://gradle.org |
| Android Gradle Plugin | Apache-2.0 | https://developer.android.com/build |
| SQLDelight Gradle plugin, SKIE Gradle plugin | Apache-2.0 | see above |
| ruff (Python lint, `tools/`) | MIT | https://github.com/astral-sh/ruff |
| sqlite-jdbc (xerial), via SQLDelight sqlite-driver — tests only | Apache-2.0 | https://github.com/xerial/sqlite-jdbc |
| Ktor client mock engine — tests only | Apache-2.0 | https://github.com/ktorio/ktor |

## Data and content (dictionary pack)

| Data | Source | License | Attribution text |
|---|---|---|---|
| JMdict (English) | EDRDG, via scriptin/jmdict-simplified JSON releases | CC BY-SA 4.0 | This application uses the JMdict/EDICT and KANJIDIC dictionary files. These files are the property of the Electronic Dictionary Research and Development Group, and are used in conformance with the Group's licence. https://www.edrdg.org/edrdg/licence.html |
| KANJIDIC2 | EDRDG, via jmdict-simplified | CC BY-SA 4.0 | (as above) |
| KRADFILE / RADKFILE | EDRDG (Michael Raine, Jim Breen), via jmdict-simplified | CC BY-SA 4.0 | (as above) |
| KanjiVG | Ulrich Apel, https://kanjivg.tagaini.net | CC BY-SA 3.0 | Stroke order data © Ulrich Apel, KanjiVG project. |
| JmdictFurigana | Doublevil, https://github.com/Doublevil/JmdictFurigana | MIT | Furigana alignments © Doublevil. |
| Pitch accent (accents.txt) | mifunetoshiro/kanjium | CC BY-SA 4.0 | Pitch accent data from Kanjium, © mifunetoshiro. |
| JLPT vocabulary levels (unofficial) | Jonathan Waller's JLPT Resources (tanos.co.uk), mapped to JMdict ids by stephenmk/yomitan-jlpt-vocab | CC BY (Waller), CC BY-SA 4.0 (mapping) | JLPT lists © Jonathan Waller; JMdict mapping by stephenmk. There is no official JLPT list since 2010. |
| JLPT kanji levels (unofficial) | davidluzgouveia/kanji-data (`jlpt_new` field only; no WaniKani fields are used) | MIT | © David Gouveia. |
| Example sentences + word index | Tatoeba, https://tatoeba.org | CC BY 2.0 FR | Example sentences from the Tatoeba project. |

Word frequency ranks are computed by `tools/packs/build_sentences.py` from Tatoeba's indexed corpus.
