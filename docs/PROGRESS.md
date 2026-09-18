# Progress

Current phase: **Phase 0 complete. Waiting for owner review before Phase 1.**

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
