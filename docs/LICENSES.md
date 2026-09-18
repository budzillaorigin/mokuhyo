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
| AndroidX Activity, Jetpack Compose (UI, Material 3) | androidApp | Apache-2.0 | © The Android Open Source Project — https://developer.android.com/jetpack/androidx |

## Build tooling (not shipped in binaries)

| Component | License | Source |
|---|---|---|
| Gradle + Gradle wrapper | Apache-2.0 | https://gradle.org |
| Android Gradle Plugin | Apache-2.0 | https://developer.android.com/build |
| SQLDelight Gradle plugin, SKIE Gradle plugin | Apache-2.0 | see above |
| ruff (Python lint, `tools/`) | MIT | https://github.com/astral-sh/ruff |

## Data and content

_None yet. JMdict, KANJIDIC2, KanjiVG, Tatoeba etc. arrive in Phase 1 with their attribution text (BRIEF.md §4)._
