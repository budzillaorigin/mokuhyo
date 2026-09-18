# Manual QA

Run the checklist for the current phase on a real device before signing off. Every phase includes an **airplane-mode pass** through every screen.

## Phase 0
- [ ] iOS: `open iosApp/Tsumugi.xcodeproj`, run on a simulator. The app launches to Today showing **今日** and "Tsumugi shared core 0.0.1 on iOS …"
- [ ] iOS: all five tabs are reachable; the non-Today tabs show "Coming soon"
- [ ] iOS: Cmd-U runs `TodayViewModelTests` green
- [ ] Android: install the debug APK. It launches to Today showing **今日** and "… on Android …"
- [ ] Android: all five tabs are reachable
- [ ] Airplane mode on: both apps launch and behave identically
