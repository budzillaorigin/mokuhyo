# Security profile

What Mokuhyo does on a computer and on the network, for people who approve software for a unit, a lab or a classroom
(BRIEF_PHASE8 N-11). The app is free, open source (Apache-2.0) and has no accounts, telemetry, analytics or ads.

## Network behaviour
Mokuhyo works with no network. It makes a connection **only** in these cases, each started by the learner:

| What | When | Where |
|---|---|---|
| Model download | the learner picks a model (first run or Settings → AI) | huggingface.co (HTTPS) |
| Update check | only if the learner turns it on (off by default); once a day | api.github.com (HTTPS) |
| Ollama detection | the learner presses "Look for Ollama" | localhost:11434 only |
| Lexicon update by URL | the learner types a URL and presses Download | that URL (HTTPS) |
| "Open" in Reading → This month | the learner presses it | opens the learner's own browser; the app makes no request |

Every request goes through one HTTP client with connect, request and idle timeouts and a cancel path.

**`--no-network`** (or the environment variable `MOKUHYO_NO_NETWORK=1`) turns every one of these off for the session:
the shared HTTP client refuses each request before it reaches the network (fail-closed; tested in
`shared/src/commonTest/.../net/NoNetworkTest.kt`), and the browser links are disabled. Launch:
- macOS: `open -a Mokuhyo --args --no-network`
- Windows: `"%LOCALAPPDATA%\Mokuhyo\Mokuhyo.exe" --no-network` (or a shortcut with that argument)

## Air-gapped install
1. Install the app from the installer (it contains everything except the language model).
2. On a connected computer, download the model file named in `docs/MODELS.md` for the tier you want, check its SHA-256
   against `content/models/manifest.json`, and copy it to removable media.
3. On the air-gapped computer: Settings → AI → *Install a model from a file (no network)* → choose the file. The app
   checks the size and SHA-256 against its own catalogue and installs it; a wrong or damaged file is refused.
4. Optionally run with `--no-network` so not even the optional calls can be attempted.

Speech recognition (Whisper small) and the bundled voices ship inside the installer; nothing else is needed.

## Data on the computer
- Windows `%APPDATA%\Mokuhyo`, macOS `~/Library/Application Support/Mokuhyo`.
- `db/` (SQLite: history, reviews, settings, imported lexicon updates), `recordings/` (WAV of the learner's spoken
  turns; can be turned off in Settings → Speech & audio), `models/`, `suggestions.json` (the learner's notes), `logs/`.
- Data leaves the computer only when the learner exports a `.mokuhyo` backup (optionally encrypted with Argon2id +
  XChaCha20-Poly1305) or a PDF report, or exports suggestions — all to a place they choose.

## Code and content integrity
- **Installers** are not code-signed yet (known gap; macOS Gatekeeper and Windows SmartScreen warn). Each release
  publishes SHA-256 checksums (`SHA256SUMS`) and a CycloneDX SBOM (`mokuhyo-<version>-sbom.cdx.json`) of the runtime
  libraries.
- **Lexicon updates** are Ed25519-signed by the project; the app verifies them against the key it ships and labels
  anything else "unverified publisher" (docs/LEXICON_FORMAT.md).
- **Model files** are verified by SHA-256 on download and on side-load.

## Provenance rules
- No models from organizations based in the People's Republic of China (CLAUDE.md rule 13); every model and voice has
  developer, country, license and source in `docs/MODELS.md` and `docs/LICENSES.md`.
- Third-party code linked into the app: MIT / Apache-2.0 / BSD / Unicode only; GPL programs (the Piper voice service
  with espeak-ng) run as separate processes (CLAUDE.md rule 6).
- No official DLPT, OPI, DLI, ACTFL or LEAP material. Reference sources marked distribution-limited are never read,
  processed, committed or shipped.
- AI-drafted content is labelled until a human reviews it.

## Generating the SBOM
`./gradlew :desktopApp:cyclonedxDirectBom` → `desktopApp/build/reports/cyclonedx-direct/bom.json` (CycloneDX 1.6, the
shipping runtime classpath). The release scripts copy it next to the installers.
