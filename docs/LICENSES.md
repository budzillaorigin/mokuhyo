# Third-party licenses

Every dependency, native library, model, voice and data asset Mokuhyo uses, with its license and attribution. The in-app Licenses screen renders this file. Add the row **in the same commit** that adds the dependency (CLAUDE.md rule 6).

`tools/gates/check_licenses.py` (run by `tools/gates/gate_core.sh`) enforces it:

- every artifact in the Gradle lockfiles (`*/gradle.lockfile`, the runtime classpaths that ship) must match a pattern in the first column of the **App classpath** table;
- no row in that table may carry a GPL/AGPL/LGPL license;
- every `id` in `content/models/manifest.json`, `voices/manifest.json` and `native/lock.json` must appear in the first column of the **Models**, **Voices** or **Native libraries** table.

Mokuhyo's own code is Apache-2.0 (`LICENSE`). Mokuhyo's original content (blueprints, topic catalogs, AI-drafted and reviewed items) is CC BY-SA 4.0, © Mokuhyo contributors.

## App classpath (linked into the app; Gradle lockfile patterns)

| Maven coordinates | Component | License | Attribution / source |
|---|---|---|---|
| `org.jetbrains.kotlin:*` | Kotlin standard library | Apache-2.0 | © JetBrains s.r.o. — https://github.com/JetBrains/kotlin |
| `org.jetbrains:annotations` | JetBrains annotations | Apache-2.0 | © JetBrains s.r.o. — https://github.com/JetBrains/java-annotations |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | kotlinx.coroutines | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx.coroutines |
| `org.jetbrains.kotlinx:kotlinx-serialization-*` | kotlinx.serialization | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx.serialization |
| `org.jetbrains.kotlinx:kotlinx-datetime*` | kotlinx-datetime | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx-datetime |
| `org.jetbrains.kotlinx:kotlinx-io-*` | kotlinx-io | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx-io |
| `io.ktor:*` | Ktor client (core, Java engine) — model and pack downloads only | Apache-2.0 | © JetBrains s.r.o. — https://github.com/ktorio/ktor |
| `com.squareup.okio:*` | Okio | Apache-2.0 | © Square, Inc. — https://github.com/square/okio |
| `app.cash.sqldelight:*` | SQLDelight runtime, JDBC/SQLite driver, coroutines extensions | Apache-2.0 | © Square, Inc. / Cash App — https://github.com/sqldelight/sqldelight |
| `org.xerial:sqlite-jdbc` | sqlite-jdbc (bundles SQLite, public domain) | Apache-2.0 | © Taro L. Saito — https://github.com/xerial/sqlite-jdbc |
| `com.ibm.icu:icu4j` | ICU4J — word/sentence segmentation, transliteration | Unicode-3.0 (ICU License) | © Unicode, Inc. and others — https://github.com/unicode-org/icu |
| `org.apache.pdfbox:*` | Apache PDFBox (pdfbox, fontbox, pdfbox-io) — PDF progress report | Apache-2.0 | © The Apache Software Foundation — https://pdfbox.apache.org |
| `commons-logging:commons-logging` | Apache Commons Logging (via PDFBox) | Apache-2.0 | © The Apache Software Foundation — https://commons.apache.org/logging |
| `org.slf4j:slf4j-api` | SLF4J API (via Ktor) | MIT | © QOS.ch — https://www.slf4j.org |
| `empty` | Gradle lockfile placeholder line, not an artifact | — | — |

Ported algorithms (code in this repo, original license kept):

| Component | License | Attribution / source |
|---|---|---|
| FSRS-6 (`shared/.../srs/Fsrs.kt`, `FsrsOptimizer.kt`) | MIT | Ported from py-fsrs, © Open Spaced Repetition — https://github.com/open-spaced-repetition/py-fsrs |
| Argon2id, BLAKE2b, XChaCha20-Poly1305 (`shared/.../backup/crypto/`) | Apache-2.0 (this repo) | Pure-Kotlin implementations of RFC 9106, RFC 7693, RFC 8439 + draft-irtf-cfrg-xchacha, from Tsumugi |
| Japanese lattice tokenizer, deinflector, conjugator, kana utilities (`shared/.../lang/ja/`) | Apache-2.0 (this repo) | From Tsumugi |

## Native libraries (JNI, linked into the app process)

| id | Component | License | Attribution / source |
|---|---|---|---|

## Models (downloaded on first run or bundled)

| id | Model | Developer (country) | License | Source |
|---|---|---|---|---|

## Voices (TTS; run out-of-process)

| id | Voice | Developer (country) | License | Source |
|---|---|---|---|---|

## Data (content packs)

| Data | License | Attribution / source |
|---|---|---|
| JMdict / EDICT (Japanese dictionary) | CC BY-SA 4.0 | © Electronic Dictionary Research and Development Group — https://www.edrdg.org/edrdg/licence.html |
| KANJIDIC2 | CC BY-SA 4.0 | © Electronic Dictionary Research and Development Group — https://www.edrdg.org/edrdg/licence.html |
| mecab-ipadic (Japanese tokenizer dictionary) | BSD-style (IPA dictionary license) | © Nara Institute of Science and Technology / Information-technology Promotion Agency — https://github.com/taku910/mecab |
| ILR Skill Level Descriptions | Public domain (US Government work) | Interagency Language Roundtable — https://www.govtilr.org |
| Tsumugi DLPT-style Japanese practice banks (`tools/items/bank/dlpt_*.json`) | CC BY-SA 4.0 | © Tsumugi / Mokuhyo contributors; AI-drafted items carry `source = "llm"` |

## Build tooling (not shipped in binaries)

| Component | License | Source |
|---|---|---|
| Gradle + Gradle wrapper | Apache-2.0 | https://gradle.org |
| Kotlin Gradle plugins, Compose Multiplatform Gradle plugin, SQLDelight Gradle plugin | Apache-2.0 | https://github.com/JetBrains/kotlin · https://github.com/JetBrains/compose-multiplatform · https://github.com/sqldelight/sqldelight |
| uv, Ruff (Python tools) | MIT / Apache-2.0 | https://github.com/astral-sh/uv · https://github.com/astral-sh/ruff |

## Inspiration only — no content used

Tsumugi's reference apps, the public DLPT familiarization guides, ACTFL/ILR OPI manuals. No official DLPT, OPI, DLI, ACTFL or LEAP material is included, ever.
