# Mokuhyo 目標 (working name)

Free, open-source desktop app (Windows and macOS) for practicing lower-range DLPT5-style reading and listening
(ILR 0+–3) and the Oral Proficiency Interview, in 11 languages, fully offline after one model download.

Unofficial practice. Not an official rating. Not affiliated with DLI, ACTFL, AFCLC or the LEAP program.

- Product spec: [BRIEF.md](BRIEF.md) (source of truth) · working rules: [CLAUDE.md](CLAUDE.md)
- Where things stand: [docs/PROGRESS.md](docs/PROGRESS.md) · decisions: [docs/DECISIONS.md](docs/DECISIONS.md)

```bash
./gradlew :shared:allTests :desktopApp:test    # Kotlin tests (JDK 21)
tools/gates/gate_core.sh                        # the per-phase gate: tests, lint, licenses, model provenance
```

Forked from Tsumugi (Japanese-only mobile app); its history is kept in git and its docs under `docs/history/`.
Licensed Apache-2.0 ([LICENSE](LICENSE)); third-party licenses in [docs/LICENSES.md](docs/LICENSES.md).
