# Installing Mokuhyo

Mokuhyo 0.1 is unsigned (a deliberate choice for an open-source pre-release; signing later is a CI setting, not a code
change). Your operating system will warn you the first time. Download the file for your computer from the project's
Releases page and check its SHA-256 against `SHA256SUMS` if you want to be sure it's the published build.

| Computer | File |
|---|---|
| Windows 10/11 (64-bit) | `Mokuhyo-<version>-windows-x64.msi` (or the `-portable.zip`) |
| Mac with Apple silicon (M1–M4) | `Mokuhyo-<version>-macos-arm64.dmg` |
| Mac with an Intel processor (2014 or later, AVX2) | `Mokuhyo-<version>-macos-x64.dmg` |

The installer is large (about 1–2 GB) because everything is inside: the program, its own Java runtime, the speech
recognizer, voices, dictionaries and practice content for 11 languages. After installing, the app downloads one AI
model (1.5–14 GB, you choose the size in the app) and then never needs the internet again.

## Windows
1. Double-click the `.msi`. If **"Windows protected your PC"** (SmartScreen) appears, click **More info**, then
   **Run anyway**.
2. Follow the installer. Mokuhyo installs for your user only (no administrator rights needed) and adds a Start menu entry.
3. To check the download first: `Get-FileHash .\Mokuhyo-<version>-windows-x64.msi -Algorithm SHA256` in PowerShell.

GPU acceleration uses Vulkan, which current NVIDIA, AMD and Intel drivers include. Without it, Mokuhyo uses the
processor and says so in Settings → AI.

## macOS
1. Open the `.dmg` and drag **Mokuhyo** to **Applications**.
2. The first time, macOS says it "can't be opened because Apple cannot check it for malicious software". Open
   **System Settings → Privacy & Security**, scroll to the message about Mokuhyo, click **Open Anyway**, and confirm.
   Alternatively, in Terminal: `xattr -d com.apple.quarantine /Applications/Mokuhyo.app`
3. Allow microphone access when asked (needed for speaking practice).

## First run
Welcome → choose your languages → Mokuhyo checks memory, graphics and disk → pick an AI tier (the recommended one is
marked; "Reading/Listening only for now" skips the download) → test your microphone and speakers → Home.

## Uninstalling
Windows: Settings → Apps → Mokuhyo → Uninstall. macOS: drag Mokuhyo from Applications to the Bin. Your data folder (see docs/PRIVACY.md) is kept; delete it to remove your history too.

Linux builds are not provided. The code builds on Linux (`tools/release/build_linux.sh`), but no installer is published (D-025).
