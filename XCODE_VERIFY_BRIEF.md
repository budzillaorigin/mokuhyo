# Xcode Verification Brief — Tsumugi v2 on macOS

> **Audience:** Claude Code (Opus) running on the owner's Mac, in the repo root, on the `v2` branch.
> **Goal:** prove the iOS app compiles, tests, launches, and archives with Xcode, and fix whatever breaks **without removing, stubbing, disabling, or weakening any feature**. `CLAUDE.md`, `BRIEF.md`, and `BRIEF_V2.md` remain in force.
> **Context:** every iOS build so far ran only in GitHub Actions. This is the first build on a real Mac, and the first time a human will look at the result.

---

## 0. Ground rules for this task

1. **Fix root causes, not symptoms.** A compile error is fixed by making the code correct, never by: commenting code out, wrapping it in `#if false`, deleting a file or target, removing a test, marking something `@unchecked Sendable` to silence a warning you don't understand, lowering `SWIFT_STRICT_CONCURRENCY`, turning off `-warnings-as-errors` if it is on, or replacing a real implementation with a placeholder.
2. **Feature parity is the acceptance test.** Before touching anything, write the list of user-visible features from `docs/PROGRESS.md` (the Android parity table plus each phase's "What was built") into `docs/BUILD_LOG.md`. After the build is green, walk that list and confirm every screen is still reachable and wired. Anything you had to change in behaviour is called out explicitly in the log.
3. **Kotlin problems get fixed in `shared/`,** then the framework is rebuilt, then Swift is rebuilt. Never paper over a Kotlin/Swift API mismatch on the Swift side with a cast or a dummy value.
4. **One problem at a time, one commit per fix,** message `xcode: <what was wrong> — <what changed>`. Never `git add -A` a build output.
5. **Log everything** in `docs/BUILD_LOG.md`: the command, the first error, the diagnosis, the fix, the commit hash, and how long the rebuild took. If you loop more than 3 times on the same error, stop and write up what you know for the owner instead of trying a fourth variant.
6. **Never run destructive git commands** (`reset --hard`, `checkout -- .`, `clean -fd`, force-push). If you need a clean tree, ask.
7. **Do not commit or upload to the App Store, TestFlight, or any signing service.** Archive locally, unsigned, only.

---

## 1. Preconditions (check, don't assume)

Run and record the versions in `docs/BUILD_LOG.md`:

```bash
sw_vers                                  # macOS version
xcodebuild -version                      # need Xcode 16+ (D-005: PBXFileSystemSynchronizedRootGroup)
xcode-select -p                          # must point at the full Xcode, not CommandLineTools
xcrun simctl list runtimes | grep iOS    # need an iOS 17+ or 18 simulator runtime
java -version                            # need JDK 21 (Temurin)
./gradlew --version                      # wrapper 9.7.1 per D-002
uv --version                             # tools/ use uv
git status --short && git branch --show-current   # clean tree, on v2
```

If anything is missing, install it (Homebrew: `brew install --cask temurin@21`, `brew install uv`) and log it. If Xcode's license isn't accepted, `sudo xcodebuild -license accept`. If the simulator runtime is missing, `xcodebuild -downloadPlatform iOS`.

Then make sure the inputs the Xcode build phases expect exist:

```bash
cd tools && uv sync && uv run python packs/build_all.py && cd ..      # content/packs/*.sqlite + manifest.json (≈1 min after downloads)
bash tools/models/fetch_ios_frameworks.sh                              # llama.cpp / whisper.cpp xcframeworks
ls content/packs iosApp/Frameworks 2>/dev/null                         # confirm both are populated; log the sizes
```

If `fetch_ios_frameworks.sh` fails (BRIEF_V2 F-44 flags the pinned release names as unverified), fix the script to resolve real release assets from the ggml-org GitHub releases (record the exact tags you chose in `docs/DECISIONS.md`) — do **not** remove the frameworks or the features that use them.

---

## 2. Build ladder (run in this order; stop at the first failure and fix it)

Each rung is a separate log entry. Use `xcbeautify` if installed (`brew install xcbeautify`), otherwise pipe through `tee` and grep for `error:`.

```bash
# Rung 1 — shared Kotlin compiles for iOS and its tests pass on the simulator
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64
./gradlew :shared:iosSimulatorArm64Test

# Rung 2 — the framework Xcode links (the run-script phase calls this task)
./gradlew :shared:embedAndSignAppleFrameworkForXcode -Pkotlin.native.cocoapods.archs=arm64 \
  -Pconfiguration=Debug -Psdk_name=iphonesimulator 2>&1 | tail -50
# If the task rejects those properties, read iosApp's run-script phase in project.pbxproj and reproduce
# exactly what it runs; the goal is to see the Kotlin/Native + SKIE output before Xcode does.

# Rung 3 — Xcode simulator build
xcodebuild -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi \
  -destination 'platform=iOS Simulator,name=iPhone 16' -configuration Debug \
  build 2>&1 | tee /tmp/build-sim.log | grep -E "error:|warning: .*(Sendable|actor)|BUILD (SUCCEEDED|FAILED)"

# Rung 4 — Swift tests (dictionary lookup budget, view models)
xcodebuild -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi \
  -destination 'platform=iOS Simulator,name=iPhone 16' test 2>&1 | tee /tmp/test-sim.log | tail -40

# Rung 5 — launch smoke on the simulator
xcrun simctl boot "iPhone 16" 2>/dev/null || true
APP=$(find ~/Library/Developer/Xcode/DerivedData -path "*Debug-iphonesimulator/Tsumugi.app" | head -1)
xcrun simctl install booted "$APP"
xcrun simctl launch --console-pty booted app.tsumugi.ios 2>&1 | head -80   # watch for a crash in the first 20 s
xcrun simctl spawn booted log show --last 2m --predicate 'process == "Tsumugi"' --style compact | grep -iE "fault|error|crash" | head -40
ls ~/Library/Logs/DiagnosticReports | grep -i tsumugi                       # any .ips = crash; read it

# Rung 6 — device build (real arm64 slices, links the llama/whisper xcframeworks that have no simulator slice)
xcodebuild -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi \
  -destination 'generic/platform=iOS' -configuration Release \
  CODE_SIGNING_ALLOWED=NO build 2>&1 | tee /tmp/build-dev.log | grep -E "error:|BUILD (SUCCEEDED|FAILED)"

# Rung 7 — unsigned archive (validates the widget + share extension targets, entitlements, privacy manifests, icon)
xcodebuild -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi \
  -destination 'generic/platform=iOS' -configuration Release \
  CODE_SIGNING_ALLOWED=NO archive -archivePath /tmp/Tsumugi.xcarchive 2>&1 | tee /tmp/archive.log | tail -30
plutil -p /tmp/Tsumugi.xcarchive/Products/Applications/Tsumugi.app/Info.plist | \
  grep -E "CFBundleIcon|NSLocalNetworkUsageDescription|NSMicrophone|NSCamera|NSSpeech|UIBackgroundModes|ITSAppUsesNonExemptEncryption"
ls /tmp/Tsumugi.xcarchive/Products/Applications/Tsumugi.app/*.png | head    # AppIcon must be present (BRIEF_V2 F-01)
du -sh /tmp/Tsumugi.xcarchive/Products/Applications/Tsumugi.app             # size audit vs the 200 MB base target
```

Rung 5 is a smoke test, not QA: the app must reach the Today/onboarding screen without crashing and the dictionary must return 食べる for たべる. The full manual list stays in `docs/QA.md` for the owner on a real iPhone.

---

## 3. Diagnosis playbook — the failures most likely on a first Mac build

Check these in order when a rung fails; each names the honest fix.

| Symptom | Likely cause | Fix |
|---|---|---|
| `Shared.framework` not found / `-framework Shared` link error | The run-script phase didn't run or wrote to a different `FRAMEWORK_SEARCH_PATHS` | Read the phase in `project.pbxproj`; align the Gradle output path with `$(BUILT_PRODUCTS_DIR)`/the search path; don't hard-code a DerivedData path |
| Kotlin/Native compile errors in `iosMain` only | Code that compiled on the Android host uses JVM-only APIs, or `expect/actual` is incomplete for iOS | Provide the `actual` for iOS or move to a KMP-safe API; keep behaviour identical |
| SKIE errors / Swift can't see a Kotlin type or sees `KotlinUnit` | Sealed class or `Flow` not exported, `@Throws` missing, or SKIE version vs Kotlin mismatch (D-002: Kotlin 2.4.10 ↔ SKIE 0.10.14) | Fix the export annotation or the version pin in `gradle/libs.versions.toml`; never downgrade Kotlin below what the code needs |
| Swift 6 / strict-concurrency **errors** (not warnings) | D-006 set Swift 5 mode with `complete` checking so these should be warnings; if the project was flipped to Swift 6 they become errors | Fix the isolation properly (`@MainActor` on view models, `nonisolated` where safe, `Sendable` conformance on Kotlin-bridged value types via SKIE). Only change `SWIFT_VERSION` if it was accidentally set to 6 before the code was ready, and record it |
| "Missing required icon file" at archive | Empty `AppIcon.appiconset` (F-01) | Generate the 1024×1024 PNG from the Android vector via a script in `tools/assets/` and reference it; don't remove `ASSETCATALOG_COMPILER_APPICON_NAME` |
| Widget or Share extension fails to build | Missing `Info.plist` keys, App Group entitlement mismatch (`group.app.tsumugi`), or the extension accidentally links the Kotlin framework | Fix entitlements/plist; extensions stay pure Swift per D-021/D-037 |
| `tsumugi_whisper.c` / bridging header errors | Header search path to the whisper xcframework headers missing, or the C shim signature drifted from `WhisperBridge.swift` | Fix `HEADER_SEARCH_PATHS`/module map; keep the shim |
| Linker: undefined symbols from llama/whisper on **simulator** | The prebuilt xcframeworks have no simulator slice (PROGRESS Phase 6 notes this) | Link them only for device (`EXCLUDED_SOURCE_FILE_NAMES[sdk=iphonesimulator*]` or a weak/`#if targetEnvironment(simulator)` path that keeps the feature intact on device and shows "not available in Simulator" on sim). Don't remove the on-device AI |
| Packs "not installed" on launch | `content/packs` empty when the "Bundle Content Packs" phase ran | Build packs (§1), clean, rebuild |
| Crash on launch in `PackInstaller` / SQLDelight | Read-only pack opened read-write, or the 127 MB copy failing in the sandbox | Implement BRIEF_V2 F-16 (open bundled packs in place, read-only) rather than adding retries |
| Dynamic Type / `String(localized:)` compile errors | String Catalog keys collide or `defaultValue:` misuse (D-037) | Fix the catalog entry; don't drop localization |
| `xcodebuild test` hangs | A test awaits a `Flow` that never completes or hits the network | Fix the test's expectation/timeouts; never delete the test |

---

## 4. Finish

When Rungs 1–7 are green:

1. Re-run the full shared test suite: `./gradlew :shared:allTests`.
2. Walk the feature list from §0 rule 2 in the simulator (tap into every tab and every hub screen; note anything that errors, is empty when it shouldn't be, or still shows a placeholder — e.g. the Reviews tab and the reader translation placeholder from BRIEF_V2 F-07/F-08 are **known** and should be listed as such, not fixed in this task unless already scheduled).
3. Update `docs/PROGRESS.md` with a "Mac build verification" entry: versions, rung results, fixes made (with commit hashes), size of the archived app, and a short list of what the owner should now test on a physical iPhone (in order: First run, Dictionary, SRS, then AI with his LAN server).
4. Push the branch: `git push -u origin v2`.
5. Stop and report. Do not start BRIEF_V2 Phase 9 work beyond the fixes this build required.
