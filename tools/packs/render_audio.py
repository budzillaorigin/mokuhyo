"""Renders a language's listening passages to Ogg Opus clips in content/packs/<lang>/audio/ (BRIEF §5.3) with the
app's own voice service (bundled Piper voices), then rebuilds the pack's audio index.

Languages without a bundled voice (ja, zh-Hans, ko, ar, id; D-013) are skipped: their passages are spoken at run
time by the OS voice. Requires voices/build.sh and `python3 tools/voices/manifest.py --fetch` first.

    uv run --group content python packs/render_audio.py --language es [--force]
    uv run --group content python packs/render_audio.py --language all
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import langtext


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--language", required=True)
    ap.add_argument("--force", action="store_true", help="re-render clips that already exist")
    args = ap.parse_args()
    langs = langtext.LANGS if args.language == "all" else args.language.split(",")
    gradle = str(REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    for lang in langs:
        app_args = f"--render-audio --language {lang} --packs {REPO / 'content' / 'packs'}" + (" --force" if args.force else "")
        r = subprocess.run([gradle, ":desktopApp:run", f"--args={app_args}", "--console=plain", "-q"], cwd=REPO,
                           capture_output=True, text=True, check=False)
        lines = [ln for ln in r.stdout.splitlines() if ln.startswith("render-audio")]
        print("\n".join(lines[-3:]))
        if r.returncode != 0:
            print(r.stderr[-2000:], file=sys.stderr)
            return r.returncode
        subprocess.run([sys.executable, str(TOOLS / "packs" / "build_packs.py"), "--language", lang], check=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
