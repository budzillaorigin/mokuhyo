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
| `llama.cpp` | llama.cpp b11040 — on-device LLM inference, statically linked into `mokuhyo_native` | MIT | © The ggml authors — https://github.com/ggml-org/llama.cpp (tarball SHA-256 in `native/lock.json`) |
| `whisper.cpp` | whisper.cpp b5130 — on-device speech-to-text, statically linked into `mokuhyo_native` | MIT | © The ggml authors — https://github.com/ggml-org/whisper.cpp (tarball SHA-256 in `native/lock.json`) |
| `ggml` | ggml tensor library (CPU, Metal, Vulkan backends), the copy vendored in llama.cpp b11040, shared by both engines | MIT | © The ggml authors — https://github.com/ggml-org/ggml |

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
| `es_ES-davefx-medium` | Piper davefx (medium) — Spanish (Spain), male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/es/es_ES/davefx/medium · dataset: davefx (OHF-Voice voice-datasets), https://github.com/OHF-Voice/voice-datasets · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `es_ES-sharvard-medium` | Piper sharvard (medium, speaker 1) — Spanish (Spain), female | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC BY 3.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/es/es_ES/sharvard/medium · Sharvard corpus © V. Aubanel, M. L. García Lecumberri, M. Cooke, CC BY 3.0, https://datashare.ed.ac.uk/handle/10283/574 · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `fr_FR-siwis-medium` | Piper siwis (medium) — French (France), female | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC BY 4.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/fr/fr_FR/siwis/medium · SIWIS French Speech Synthesis Database © Idiap/University of Edinburgh et al., CC BY 4.0, https://datashare.is.ed.ac.uk/handle/10283/2353 · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `fr_FR-upmc-medium` | Piper upmc (medium, speaker 1) — French (France), male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC BY-SA 4.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/fr/fr_FR/upmc/medium · upmc-pierre-data © Université Pierre et Marie Curie / MaryTTS, CC BY-SA 4.0, https://github.com/marytts/upmc-pierre-data · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `de_DE-thorsten-medium` | Piper thorsten (medium) — German, male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/de/de_DE/thorsten/medium · dataset: Thorsten-Voice (Thorsten Müller), https://github.com/thorstenMueller/Thorsten-Voice · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `de_DE-kerstin-low` | Piper kerstin (low) — German, female | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/de/de_DE/kerstin/low · dataset: dataset-voice-kerstin, https://github.com/rhasspy/dataset-voice-kerstin · fine-tuned from en_US-ryan-low (RyanSpeech, CC BY-NC-SA 4.0) |
| `pt_BR-faber-medium` | Piper faber (medium) — Portuguese (Brazil), male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/pt/pt_BR/faber/medium · dataset: faber (OHF-Voice voice-datasets), https://github.com/OHF-Voice/voice-datasets · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `pt_BR-cadu-medium` | Piper cadu (medium) — Portuguese (Brazil), male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/pt/pt_BR/cadu/medium · dataset: cadu (OHF-Voice voice-datasets), https://github.com/OHF-Voice/voice-datasets · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `ru_RU-dmitri-medium` | Piper dmitri (medium) — Russian, male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/ru/ru_RU/dmitri/medium · dataset: dmitri (OHF-Voice voice-datasets), https://github.com/OHF-Voice/voice-datasets · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `ru_RU-denis-medium` | Piper denis (medium) — Russian, male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/ru/ru_RU/denis/medium · dataset: denis (OHF-Voice voice-datasets), https://github.com/OHF-Voice/voice-datasets · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `fa_IR-amir-medium` | Piper amir (medium) — Persian, male | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/fa/fa_IR/amir/medium · dataset: Amir (Datacula Persian TTS databases), https://datacula.com/tts-databases · fine-tuned from en_US-lessac-medium (Blizzard 2013 Lessac data, research license) |
| `fa_IR-ganji-medium` | Piper ganji (medium) — Persian, unknown | Rhasspy / Open Home Foundation (Michael Hansen), published in rhasspy/piper-voices (United States / Switzerland) | training data CC0 1.0; voice files MIT (rhasspy/piper-voices) | https://huggingface.co/rhasspy/piper-voices/tree/c10ece1aade47bb51c153c893d14e5bf8e5b7117/fa/fa_IR/ganji/medium · dataset: Ganji (Datacula Persian TTS databases), https://tts.datacula.com/ · fine-tuned from fa_IR-amir-medium |

## Voice service executables (separate processes, not linked)

Piper runs as a child process (`voices/build/<os>-<arch>/piper/`, staged to `<resources>/voices/piper/`); the app
talks to it over stdin/stdout and never links it. Because piper loads espeak-ng, that folder as a whole is
distributed under GPL-3.0-or-later; its `LICENSES/` holds every license text and `LICENSES/SOURCE.txt` the exact
source tarballs (pinned with SHA-256 in `voices/lock.json`, built by `voices/build.sh|ps1`).

| id | Component | License | Attribution / source |
|---|---|---|---|
| `piper` | Piper 2023.11.14-2 — neural text-to-speech program, patched for per-line speed (`voices/patch_piper.cmake`) | MIT | © 2022 Michael Hansen — https://github.com/rhasspy/piper |
| `piper-phonemize` | piper-phonemize (commit bfc2e75) — text to phonemes for Piper, shared library | MIT | © 2023 Michael Hansen — https://github.com/rhasspy/piper-phonemize |
| `espeak-ng` | espeak-ng 1.52-dev, rhasspy fork (commit 0f65aa3) — phonemizer library and `espeak-ng-data` | GPL-3.0-or-later (parts Apache-2.0, BSD-2-Clause, Unicode) | © Reece H. Dunn, Jonathan Duddington and contributors — https://github.com/rhasspy/espeak-ng · source: https://github.com/rhasspy/espeak-ng/archive/0f65aa301e0d6bae5e172cc74197d32a6182200f.tar.gz |
| `onnxruntime` | ONNX Runtime 1.14.1 — neural network inference, Microsoft's prebuilt shared library | MIT | © Microsoft Corporation — https://github.com/microsoft/onnxruntime (third-party notices in `LICENSES/onnxruntime-ThirdPartyNotices.txt`) |
| `fmt` | {fmt} 10.0.0 — formatting library, statically linked into piper | MIT | © Victor Zverovich and contributors — https://github.com/fmtlib/fmt |
| `spdlog` | spdlog 1.12.0 — logging library, statically linked into piper | MIT | © Gabi Melman and contributors — https://github.com/gabime/spdlog |

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
