# Release

How to turn this repository into a TestFlight / App Store build (iOS) and a sideloadable APK (Android). Everything the owner has to do by hand is marked **Owner**.

## 0. One-time setup (Owner, on a Mac)

1. Xcode 16 or newer, and the command line tools: `xcode-select --install`.
2. JDK 21 (e.g. `brew install --cask temurin@21`). Gradle comes from the wrapper.
3. Python 3.12+ and [uv](https://docs.astral.sh/uv/) for the content packs: `brew install uv`.
4. An Apple Developer Program membership. In Xcode → Settings → Accounts, sign in with the Apple ID.
5. Decide the final name and bundle ID (open decision 1). The placeholders are:
   - `app.tsumugi.ios` (app)
   - `app.tsumugi.ios.widget` (widget)
   - `app.tsumugi.ios.share` ("Read in Tsumugi" share extension)
   - `group.app.tsumugi` (App Group)

   To change them, edit `PRODUCT_BUNDLE_IDENTIFIER` in `iosApp/Tsumugi.xcodeproj/project.pbxproj`, and the App Group in:
   - `iosApp/Tsumugi.entitlements`, `iosApp/TsumugiWidget.entitlements`, `iosApp/TsumugiShare.entitlements`
   - `iosApp/Tsumugi/Platform/WidgetSnapshot.swift`, `iosApp/Tsumugi/Platform/ShareInbox.swift`
   - `iosApp/TsumugiWidget/TsumugiWidget.swift`, `iosApp/TsumugiShare/ShareViewController.swift`
6. In App Store Connect, create the app record with that bundle ID, then register the App Group identifier under Certificates, Identifiers & Profiles. All three bundle IDs need the App Group capability.
7. In Xcode, select the project → each target (Tsumugi, TsumugiWidget, TsumugiShare) → Signing & Capabilities → choose your Team and keep "Automatically manage signing" on.

## 1. Build the content packs

```bash
cd tools
uv sync
uv run python packs/build_all.py      # dictionary, KanjiVG, sentences, kanji path, grammar, tokenizer, practice, exam
cd ..
ls -la content/packs                  # *.sqlite + manifest.json
```

Then fetch the rendered VOICEVOX audio packs (rendering needs a VOICEVOX engine, so the Mac downloads them instead):

```bash
cd tools && uv run python packs/audio_release.py fetch && cd ..   # ~75 MB from this repo's audio-packs-* release
```

Both steps need GitHub access to this private repo: `export GH_TOKEN=…` or `gh auth login`. The pitch and minimal-pairs packs (8.7 MB) are bundled into the app. The other audio sets are installed in the app from Settings → Audio packs (D-096, D-097).

The first run downloads about 100 MB of sources into `tools/.cache`, which is git-ignored. Every source is pinned in `tools/packs/sources.lock` (URL, release tag or commit, sha256) and checked on use, so two builds of the same commit use the same data (F-38). To move to newer upstream data, run `uv run python packs/build_all.py --update-sources` (or name individual sources), review the lock diff and commit it. Tatoeba only publishes its latest weekly export, so a fresh checkout whose cache is empty fails with a hash mismatch once Tatoeba has moved on. The fix is the same deliberate update. Both apps bundle whatever is in `content/packs` at build time. If a pack is missing, the app shows an honest "not installed" state rather than failing.

Size budget: the bundled packs are about 155 MB raw. The App Store compresses the IPA, and the dictionary compresses to about 46 MB with gzip. Check the result against the < 200 MB base target in step 4 below.

## 2. On-device AI frameworks (iOS)

```bash
bash tools/models/fetch_ios_frameworks.sh   # llama.xcframework + whisper.xcframework, hash-checked
```

This step is required: the app links both frameworks, so the Xcode build fails with "Undefined symbols _whisper_…" without them. Models are never bundled; the in-app model manager downloads them on request.

## 3. Test

```bash
./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
./gradlew :server:test                       # sync server (needs Docker for the Postgres variant)
xcodebuild test -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi \
  -destination "platform=iOS Simulator,name=iPhone 16" 
```

Then do a manual pass on a real device (**Owner**) using [`docs/QA.md`](QA.md). Areas the simulator and CI cannot cover:

- Microphone recording, speech recognition, and pronunciation scores with a real voice.
- Loading a model and generating with it (the pinned llama.xcframework has no simulator slice).
- Camera OCR.
- Widgets on the home screen.
- The "Read in Tsumugi" share extension. It needs a signed build with the App Group; unsigned simulator builds have no shared container, so shared items are silently dropped there.
- Notifications.
- Memory behaviour with a 1.5B model loaded; watch for memory warnings.
- VoiceOver, the largest Dynamic Type sizes, Reduce Motion and the Japanese UI (Settings → Apps → Tsumugi → Language, or the device language).

## 4. Archive and upload

CI already archives an **unsigned** Release build on every push and checks it with `tools/ci/validate_archive.py` (F-06): usage strings, export-compliance and ATS keys, the compiled app icon, the widget and share extensions, the llama/whisper frameworks, privacy manifests, `LICENSES.md` and the packs. CI can't sign, so it can't run `-exportArchive`. The signed archive is an **Owner** step on the Mac:

1. Do steps 0–2 first: signing set up, `content/packs` built, `bash tools/models/fetch_ios_frameworks.sh` run. An archive without packs or frameworks builds fine but ships without them, and the validator flags it.
2. Bump `CURRENT_PROJECT_VERSION` (build number) for every upload, and `MARKETING_VERSION` for a new version. Both are set on all three targets (Tsumugi, TsumugiWidget, TsumugiShare), and they must match across them.
3. Archive (signed with your team) and validate:

   ```bash
   xcodebuild archive \
     -project iosApp/Tsumugi.xcodeproj -scheme Tsumugi \
     -configuration Release -destination "generic/platform=iOS" \
     -archivePath build/Tsumugi.xcarchive \
     -allowProvisioningUpdates DEVELOPMENT_TEAM=<YOUR_TEAM_ID>

   python3 tools/ci/validate_archive.py build/Tsumugi.xcarchive

   # Signed builds carry entitlements: all three should list group.app.tsumugi.
   for b in build/Tsumugi.xcarchive/Products/Applications/Tsumugi.app \
            build/Tsumugi.xcarchive/Products/Applications/Tsumugi.app/PlugIns/*.appex; do
     codesign -d --entitlements - --xml "$b" | plutil -p - | grep -A2 application-groups
   done
   ```

4. Export and upload:

   ```bash
   cat > build/ExportOptions.plist <<'PLIST'
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
   <plist version="1.0"><dict>
     <key>method</key><string>app-store-connect</string>
     <key>destination</key><string>upload</string>
     <key>signingStyle</key><string>automatic</string>
     <key>teamID</key><string>YOUR_TEAM_ID</string>
   </dict></plist>
   PLIST

   xcodebuild -exportArchive -archivePath build/Tsumugi.xcarchive \
     -exportOptionsPlist build/ExportOptions.plist -exportPath build/export \
     -allowProvisioningUpdates
   ```

   To check without uploading, set `destination` to `export`: the `.ipa` lands in `build/export`. Then run `xcrun altool --validate-app -f build/export/Tsumugi.ipa -t ios --apiKey <KEY_ID> --apiIssuer <ISSUER_ID>`, with an App Store Connect API key.
5. Alternatively, use Xcode: Product → Archive, then in the Organizer: Validate App, then Distribute App → App Store Connect → Upload. Validate App runs Apple's server-side checks (icon, Info.plist, entitlements, private API use) without uploading.

Size audit: in App Store Connect → the build → App Store File Sizes, check the "Install size" per device. If it is over 200 MB, move `tokenizer.sqlite` and the sentence tables to an on-demand download (follow-up work; not needed yet).

## 5. App Store Connect answers (Owner)

- **Encryption (export compliance). Owner: confirm before the first upload; this is not legal advice.**
  - *What the app contains:*
    - HTTPS through the OS networking stack (URLSession).
    - Optional end-to-end-encrypted sync, implemented in the app's own Kotlin code (D-025): XChaCha20-Poly1305 for data confidentiality, Argon2id key derivation (RFC 9106), and BLAKE2b (RFC 7693).
    - ChaCha20-Poly1305 is RFC 8439. The XChaCha20 extended nonce is a published IETF draft (draft-irtf-cfrg-xchacha), not an RFC.
    - Nothing proprietary or unpublished, so it shouldn't count as "non-standard cryptography" under EAR §772.1 ("…not adopted or approved by a duly recognized international standards body… *and* have not otherwise been published").
  - *Apple's key.* The app target sets `INFOPLIST_KEY_ITSAppUsesNonExemptEncryption = NO` (Debug and Release). Apple says NO is for apps that use no encryption, or only forms that are exempt from export documentation requirements. Its example of exempt use is the OS's own encryption, such as HTTPS through URLSession. The E2E sync cipher is the app's own implementation, used for confidentiality, not only authentication. So NO is **not clearly right**.
    - The conservative choice is to set the key to `YES`. App Store Connect then asks its questions; answer "standard encryption algorithms instead of, or in addition to, Apple's OS".
    - App Store Connect also asks about France. France controls the import and export of encryption apps separately (ANSSI). Its main targets are secure-storage and secure-communication apps, which Tsumugi isn't, but check the answer there.
    - Decide, then keep the build setting and the answers consistent.
  - *US classification (EAR).* The app's primary function is language learning, not information security, and it's generally available to the public. That points to mass-market treatment under Note 3 to Category 5 Part 2: ECCN **5D992.c**, exported under License Exception ENC §740.17(b)(1) with no license and no BIS classification request.
  - *Annual self-classification report.* Before March 29, 2021, §740.17(b)(1) items needed a self-classification report (Supplement No. 8 to Part 742), sent to BIS and the NSA ENC coordinator by **February 1** each year. The BIS rule of 86 Fed. Reg. 16482 (March 29, 2021) removed that requirement for mass-market end items such as application software (5A992.c/5D992.c, meeting Note 3).
    - It is still required for mass-market *components*, chipsets, electronic assemblies and their executable software.
    - Items that provide "non-standard cryptography", or that are described in §740.17(b)(2), don't qualify for (b)(1) at all.
    - On that reading, Tsumugi owes **no** annual report.
  - Apple's documentation still mentions "a year-end self-classification report" for some apps, and that text predates the rule. If the owner concludes the app is *not* a mass-market end item under 5D992.c (for example, if E2E sync were ever marketed as a main feature), a report may be due by February 1 for the previous calendar year. Send it to crypt-supp8@bis.doc.gov and enc@nsa.gov (§740.17(e)(3)).
  - Confirm with the current eCFR text of 15 CFR 740.17 and the BIS encryption pages, or an export-control adviser, because the rules are amended from time to time.
- **Privacy nutrition label:**
  - *Without sync*, no data is collected: everything stays on the device.
  - *With sync enabled* (self-hosted or a server the owner runs), declare it as follows. None of it is used for tracking.
    - "User Content: Other User Content" and "Identifiers: User ID", both linked to the user and used for App Functionality.
    - Email if the sync server uses email sign-in.
  - Recordings never leave the device, unless the learner points speech recognition at their own Whisper server.
- **Privacy manifest.** `iosApp/Tsumugi/PrivacyInfo.xcprivacy` is bundled with the app. It declares no tracking, no tracking domains and no collected data types, because nothing is collected unless the learner turns on self-hosted sync. The nutrition label above covers the sync case. It lists these required-reason APIs:

  | API category | Reason | Why |
  |---|---|---|
  | User defaults | `CA92.1` | `@AppStorage` remembers the chosen JLPT level. |
  | File timestamp | `C617.1` | The Kotlin core (Okio) and SQLite read file metadata inside the app container. |
  | System boot time | `35F9.1` | Kotlin/Native's monotonic clock (timeouts, elapsed time) uses the system uptime. |

  The widget and the share extension have their own manifests (`TsumugiWidget/PrivacyInfo.xcprivacy`, `TsumugiShare/PrivacyInfo.xcprivacy`) that declare nothing: they only read and write small JSON files in the App Group container. After archiving, check the combined report with Xcode → Organizer → the archive → right-click → Generate Privacy Report.
- **Usage strings.** Present in both Debug and Release:
  - `NSCameraUsageDescription` (OCR)
  - `NSMicrophoneUsageDescription` and `NSSpeechRecognitionUsageDescription` (speaking practice; Phase 6)

  - `NSLocalNetworkUsageDescription` (F-02). On iOS 14 and later, the first connection to a device on the local network, including a URL the learner typed such as `http://<lan-ip>:11434` for Ollama, shows the system's local-network permission prompt. Without this key there is no usable prompt and LAN connections fail (see D-061). If the learner declines, re-enable it in Settings → Privacy & Security → Local Network → Tsumugi. `NSBonjourServices` is needed only if Bonjour discovery is added later.

  `NSPhotoLibraryUsageDescription` is not needed: Scan text uses `PhotosPicker`, which runs out of process and needs no permission.
- **Plain http to the learner's own servers (ATS, F-02).** `iosApp/TsumugiInfo.plist`, merged into the generated Info.plist, sets:
  - `NSAllowsLocalNetworking = YES`, which covers unqualified host names such as `http://homeserver:11434` and `.local` names. IP-address URLs aren't subject to ATS.
  - `NSExceptionDomains` allowing http for `*.ts.net` (Tailscale MagicDNS; Tailscale encrypts the traffic with WireGuard) and `*.home.arpa` (RFC 8375 home networks).

  Any other domain name needs https. The app never sets `NSAllowsArbitraryLoads`, so the review notes need no ATS justification beyond "connects to servers the user runs on their own network".
- **Document types (F-42).** `TsumugiInfo.plist` declares that the app opens EPUB, `.srt`/`.vtt`, `.apkg` and JSON item banks (`LSSupportsOpeningDocumentsInPlace = NO`, so iOS hands the app a copy). Files → Share → Tsumugi routes EPUBs to the reader and decks to the Anki importer. Subtitles and item banks show where to use them.
- **Localization.** The UI ships in English and Japanese (`iosApp/Tsumugi/Localizable.xcstrings`). In App Store Connect, add a Japanese localization for the listing (name, subtitle, description, keywords, screenshots) as well as English.
- **Review notes:**
  - Everything works without an account or API keys: the dictionary, SRS, grammar, reader, writing, exams and scripted speaking practice.
  - AI features need either a downloaded model (free, from Hugging Face, no key) or the learner's own server.
  - DLPT/OPI/JLPT screens say "unofficial practice; not affiliated with DLI, ACTFL or JLPT."
- **Age rating:** 4+. Reader imports can show arbitrary web content, so answer "Unrestricted Web Access: No". Feeds are only the learner's own RSS URLs.
- **Trademarks:** don't use WaniKani, Bunpro, NativShark, Skritter, Lingopie and similar names in the app name, screenshots or keywords. The factual "imports from WaniKani, Anki and Bunpro" sentence in the description is fine.

## 6. TestFlight

After the upload finishes processing, go to App Store Connect → TestFlight:

1. Add yourself to Internal Testing.
2. Install through the TestFlight app.
3. Run the QA pass on the TestFlight build, because Release optimizations differ from Debug.
4. Use it daily for a week (crash-free soak). Crashes appear under TestFlight → Crashes, and in Xcode → Organizer → Crashes.

## 7. Android (sideload only)

```bash
./gradlew :androidApp:assembleDebug          # native llama/whisper build takes a few minutes the first time
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

`-Ptsumugi.native=false` skips the native AI libraries for a quick UI-only build. Without adb, copy the APK to the phone and open it, allowing "Install unknown apps" for your file manager. On-device AI needs an arm64 phone with ARMv8.2 dot-product (roughly any phone from 2018 on).

**APK size.** Almost all of it is content, not code:
- The packs from `content/packs` are stored in the APK as assets: about 155 MB raw (dictionary 127 MB, tokenizer 25 MB, the rest under 3 MB each). APK zip compression brings the dictionary down to roughly 46 MB.
- The ML Kit Japanese text-recognition model, and the arm64 llama.cpp/whisper.cpp libraries when the native build is on.
- The Phase 3 debug APK measured 123 MB with the dictionary, tokenizer and ML Kit model. Expect a little more with the native libraries.
- AI models are never in the APK. They are downloaded in Settings → AI & speech and live in the app's `files/models` directory.

Check a build with `ls -lh androidApp/build/outputs/apk/debug/`. For a per-folder breakdown, open the APK in Android Studio (Build → Analyze APK).

A release build (`./gradlew :androidApp:assembleRelease`) is not minified (`proguard-rules.pro` keeps the JNI bridges in case that changes). It is unsigned unless you add a signing config, and an unsigned APK can't be installed. For sideloading, the debug APK is the one to use.

**Language.** The UI is in English and Japanese. It follows the phone's language, and on Android 13+ it can be set per app: Settings → Apps → Tsumugi → Language.

**Plain http to your own servers.** `res/xml/network_security_config.xml` allows cleartext so `http://` endpoints on your LAN or tailnet work (Ollama, Whisper, VOICEVOX, the sync server). The app's own fixed hosts (WaniKani, Hugging Face, GitHub) are pinned to https. See D-062 for the tradeoff.

**Opening files.** File managers, mail and chat apps offer "Open with Tsumugi" for EPUB (opens in the reader), `.apkg` (Anki import), JSON exam item banks (bank import) and `.srt`/`.vtt` (a hint to add them in the media player).

**Sharing into the app.** "Read in Tsumugi" appears in the share sheet for text and links. It imports the text, or fetches the page, into the reader. "Look up in Tsumugi" appears in the text-selection menu and opens the dictionary search.

**Backups.** Android backups (Google or device-to-device) include your reviews, settings and imported media. They leave out the content packs and downloaded models, which are re-created from the APK or downloaded again. They also leave out stored API tokens, so after a restore, sign in to sync and reconnect WaniKani again.

## 8. Sync server (optional)

See `server/README.md` and `docs/SYNC_PROTOCOL.md`. `docker compose -f server/docker-compose.yml up -d` runs the server with Postgres. Point the app at it in Me → Sync. Running a public hosted instance is open decision 2.
