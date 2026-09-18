# Tsumugi 紡ぎ (working name)

Offline-first Japanese learning app: SwiftUI iPhone app + Android Compose app on a shared Kotlin Multiplatform core.

- Product spec: [BRIEF.md](BRIEF.md) (source of truth)
- Working rules: [CLAUDE.md](CLAUDE.md)
- Where things stand: [docs/PROGRESS.md](docs/PROGRESS.md)

```bash
./gradlew :shared:allTests :androidApp:assembleDebug   # shared tests + Android APK (JDK 21, Android SDK 37)
open iosApp/Tsumugi.xcodeproj                          # iOS (macOS, Xcode 16+)
```
