# Progress

Current phase: **Phase 1 complete (iOS side awaiting its first CI run). Continuing to Phase 2.** The owner asked for all phases to run back to back, without per-phase review stops.

---

## Phase 1: Data engine (2026-09-18)

### What was built
- **Content pack builders** (`tools/packs/`):
  - `build_dictionary.py`: JMdict, KANJIDIC2, KRADFILE/RADKFILE, JmdictFurigana, Kanjium pitch, and unofficial JLPT levels.
  - `build_kanjivg.py`: stroke paths.
  - `build_sentences.py`: Tatoeba sentences, a word→sentence index, and frequency ranks.
  - `build_all.py`: runs all three plus `manifest.json`.
  - Output: `content/packs/dictionary.sqlite`, 127 MB (46 MB compressed), built in about 1 minute.
  - The schema is `shared/.../dictionary.sq` (single source; DECISIONS D-008).
- **Japanese text engine** (`shared/jp`):
  - `Deinflector`: rule table, 441 test forms including chained auxiliaries and colloquial contractions.
  - `Conjugator`: 26 rows per verb or adjective, with a round-trip test against the deinflector (~800 checks).
  - `Kana`, `Romaji` (full conversion plus an incremental IME for answer fields), `Mora`, `Pitch` (all four patterns), `Furigana` (fallback aligner).
  - `strokes/SvgPath`: flattens KanjiVG path data into polylines for both apps.
- **Dictionary** (`shared/dictionary`): `DictionaryRepository`.
  - Search: kanji/kana/katakana-folded exact matches, deinflection checked against part of speech, prefix completion, romaji→kana, English reverse index, and sentence mode.
  - Details: entry view data (furigana, pitch, kanji breakdown, conjugations, Tatoeba examples), kanji detail (strokes, components, words), radical search with live narrowing.
  - `tokenize()` does dictionary longest-match (D-011).
- **Packs at runtime**: `PackInstaller` copies bundled packs into app storage on first launch, keyed on the manifest version (D-013). `AppGraph` is the composition root both apps hold.
- **Android**:
  - Application-scoped `AppGraph`, Material 3 theme, and Japanese locale on Japanese text so shared Chinese/Japanese characters use Japanese glyph shapes.
  - Per-tab back stacks, with global search from every tab.
  - Dictionary search, entry, kanji (animated stroke order) and radical search screens.
  - The Gradle `bundlePacks` task puts the pack in the APK (67 MB debug APK).
- **iOS**: the same screens in SwiftUI.
  - `NavigationStack` per tab, a global search button, and `Font.japanese` (Hiragino) for Japanese text.
  - Furigana, pitch diagram, and animated stroke order (`TimelineView` + `Canvas`).
  - A "Bundle Content Packs" build phase, and Swift tests for search plus the < 5 ms lookup budget.
- **CI**: a `packs` job builds the real pack once and hands it to the Android and iOS jobs, so both run tests on real data.

### Verified on the dev machine
- `./gradlew :shared:allTests`: 81 tests green on the Android host, including `RealPackSmokeTest` against the real pack.
  - 食べさせられなかった→食べる, cat→猫, kanji→漢字, sentence mode, 語 has 14 strokes, 言+口 radicals find 語.
  - About 2.4 ms per lookup on the JVM (D-015).
- `./gradlew :androidApp:assembleDebug` builds, with the pack bundled.

### Not yet verified
- iOS: Kotlin/Native compile, SKIE bridging of the new API, SwiftUI build, and Swift tests. The first macOS CI run will be the first compile.
- Android UI on a device: there's no emulator for Windows on ARM (D-016).

### Deferred
- Handwriting search and camera OCR: Phase 4, as the brief schedules.
- Word lists and "Add to SRS": Phase 2, along with the user-data model.
- JMnedict (names): not bundled yet. It adds ~40 MB for names only; to be revisited with on-demand packs.

---

## Phase 0: Foundations (2026-09-18)

### What was built
- Repo layout from BRIEF.md §3.1: `shared/`, `androidApp/`, `iosApp/`, `server/`, `tools/`, `content/`, `docs/`, `.github/workflows/`.
- `CLAUDE.md` copied verbatim from BRIEF.md §1.
- **Gradle KMP build** (`gradle/libs.versions.toml`, wrapper 9.7.1): `shared` targets Android (`com.android.kotlin.multiplatform.library`), `iosArm64` and `iosSimulatorArm64`, with SKIE, SQLDelight (`TsumugiDatabase`, placeholder `app_meta` table), kotlinx-coroutines, kotlinx-serialization and Ktor client.
- **Shared API:** `HelloUseCase` exposes a `Flow<Greeting>`. `SharedGraph` is the composition root both apps call. `Platform` and `DatabaseDriverFactory` are expect/actual on both platforms.
- **Android app** (`app.tsumugi.android`): Compose shell with the five tabs (Today · Reviews · Learn · Practice · Me). Today renders the shared greeting; the other tabs show "Coming soon."
- **iOS app** (`app.tsumugi.ios`, iOS 17+): `iosApp/Tsumugi.xcodeproj` with the same five-tab `TabView`. `TodayViewModel` (`@Observable`) iterates the SKIE-bridged flow. A "Compile Kotlin Framework" run-script phase builds `Shared.framework`. There's a shared scheme and one Swift Testing test.
- **CI** (`.github/workflows/ci.yml`) has three jobs:
  - Linux: shared tests plus `:androidApp:assembleDebug`, uploading the APK.
  - macOS: shared iOS simulator tests plus `xcodebuild test`.
  - Tools: `uv sync --locked` plus `ruff`.
- **tools/**: uv project (`pyproject.toml`, `uv.lock`) with `packs/`, `items/` and `models/` folders.
- Docs seeded: DECISIONS, LICENSES, CONTENT_PACKS, SYNC_PROTOCOL (stub), QA.

### Verified on the dev machine (Windows 11 ARM64)
- `./gradlew :shared:allTests`: `HelloUseCaseTest` passes on the Android host. iOS test tasks are skipped because Kotlin/Native can't target iOS from Windows.
- `./gradlew :androidApp:assembleDebug`: builds `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- `uv sync` and `uv run ruff check .` pass.

### Not yet verified (needs a Mac or the first CI run)
- iOS: Kotlin/Native framework build, SKIE output, the hand-written Xcode project, the SwiftUI app launch and the Swift test.
- Android app **launch**: the APK builds, but no emulator or device was available here.
- CI itself: the repo has no GitHub remote yet.

### Deferred
- Empty module packages (`domain/`, `srs/`, `jp/`, …) get created with their first code (Phase 1+). Git doesn't track empty folders.
- `TsumugiUITests` target: added with the first real UI flow (Phase 2).
- `server/`: placeholder README only; the Ktor server is Phase 5.

### How to run
```bash
# Shared tests + Android APK (any OS with JDK 21 + Android SDK 37)
./gradlew :shared:allTests :androidApp:assembleDebug
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk

# iOS (macOS with Xcode 16+ and JDK 21)
open iosApp/Tsumugi.xcodeproj   # pick an iPhone simulator, Run (Cmd-R), Test (Cmd-U)

# Python tools
cd tools && uv sync && uv run ruff check .
```

---

## Android parity checklist

| Feature | iOS | Android |
|---|---|---|
| Tab shell | ✅ (unverified) | ✅ |
| Today (placeholder) | ✅ (unverified) | ✅ |
| Onboarding | — | — |
| Reviews | — | — |
| Dictionary | — | — |
| Settings / Integrations / Sync | — | — |
