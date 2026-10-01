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
| `org.jetbrains.compose.*:*` | Compose Multiplatform for Desktop (runtime, UI, foundation, animation, Material 3, Material icons) | Apache-2.0 | © JetBrains s.r.o. and the Android Open Source Project — https://github.com/JetBrains/compose-multiplatform |
| `org.jetbrains.skiko:*` | Skiko (Skia bindings for Kotlin; bundles Skia, BSD-3-Clause) | Apache-2.0 | © JetBrains s.r.o. — https://github.com/JetBrains/skiko · Skia © Google LLC — https://skia.org |
| `org.jetbrains.androidx.*:*`, `androidx.*:*` | AndroidX libraries used by Compose (lifecycle, savedstate, navigationevent, collection, annotation, arch core) | Apache-2.0 | © The Android Open Source Project — https://developer.android.com/jetpack/androidx |
| `org.jetbrains.runtime:jbr-api` | JetBrains Runtime API (optional window-decoration API used by Compose Desktop) | Apache-2.0 | © JetBrains s.r.o. — https://github.com/JetBrains/JetBrainsRuntimeApi |
| `org.jetbrains.kotlinx:atomicfu*` | kotlinx atomicfu | Apache-2.0 | © JetBrains s.r.o. — https://github.com/Kotlin/kotlinx-atomicfu |
| `org.jspecify:jspecify` | JSpecify annotations | Apache-2.0 | © The JSpecify Authors — https://jspecify.dev |
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
| `phi-4-mini-instruct-q4km` | Phi-4-mini-instruct 3.8B | Microsoft (United States) | MIT | https://huggingface.co/microsoft/Phi-4-mini-instruct · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF |
| `granite-3.3-2b-instruct-q4km` | IBM Granite 3.3 2B Instruct | IBM (United States) | Apache-2.0 | https://huggingface.co/ibm-granite/granite-3.3-2b-instruct · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/ibm-granite_granite-3.3-2b-instruct-GGUF |
| `eurollm-9b-instruct-q4km` | EuroLLM-9B-Instruct | UTTER consortium (Unbabel, Instituto Superior Técnico, University of Edinburgh, et al.) (European Union) | Apache-2.0 | https://huggingface.co/utter-project/EuroLLM-9B-Instruct · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/EuroLLM-9B-Instruct-GGUF |
| `granite-3.3-8b-instruct-q4km` | IBM Granite 3.3 8B Instruct | IBM (United States) | Apache-2.0 | https://huggingface.co/ibm-granite/granite-3.3-8b-instruct · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/ibm-granite_granite-3.3-8b-instruct-GGUF |
| `mistral-7b-instruct-v0.3-q4km` | Mistral-7B-Instruct v0.3 | Mistral AI (France) | Apache-2.0 | https://huggingface.co/mistralai/Mistral-7B-Instruct-v0.3 · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF |
| `mistral-nemo-instruct-2407-q4km` | Mistral-Nemo-Instruct-2407 12B | Mistral AI and NVIDIA (France / United States) | Apache-2.0 | https://huggingface.co/mistralai/Mistral-Nemo-Instruct-2407 · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF |
| `phi-4-q4km` | Phi-4 14B | Microsoft (United States) | MIT | https://huggingface.co/microsoft/phi-4 · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/phi-4-GGUF |
| `mistral-small-3.2-24b-instruct-2506-q4km` | Mistral-Small-3.2-24B-Instruct-2506 | Mistral AI (France) | Apache-2.0 | https://huggingface.co/mistralai/Mistral-Small-3.2-24B-Instruct-2506 · GGUF Q4_K_M by bartowski (llama.cpp imatrix quantization of the original weights), https://huggingface.co/bartowski/mistralai_Mistral-Small-3.2-24B-Instruct-2506-GGUF |
| `whisper-small` | Whisper small (multilingual) | OpenAI (United States) | MIT | https://github.com/openai/whisper · ggml conversion by the whisper.cpp project (ggml-org), https://huggingface.co/ggerganov/whisper.cpp |
| `whisper-large-v3-turbo` | Whisper large-v3-turbo (multilingual) | OpenAI (United States) | MIT | https://github.com/openai/whisper · ggml conversion by the whisper.cpp project (ggml-org), https://huggingface.co/ggerganov/whisper.cpp |

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
