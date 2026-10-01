"""Collects a platform's installers into dist/ with release names (release.yml):
Mokuhyo-<version>-<platform>.{msi,dmg,deb} plus a portable archive of the app image (zip on Windows/macOS,
tar.gz on Linux). The macOS DMG carries the 1.y.z bundle version internally (D-008); its file name uses the real version.

    uv run python release/collect.py --name macos-arm64 --version 0.1.0 --out ../dist
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tarfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BIN = REPO / "desktopApp" / "build" / "compose" / "binaries" / "main"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    base = f"Mokuhyo-{a.version}-{a.name}"
    found = 0
    for ext in ("msi", "dmg", "deb"):
        for f in (BIN / ext).glob(f"*.{ext}") if (BIN / ext).is_dir() else []:
            shutil.copy2(f, out / f"{base}.{ext}")
            found += 1
    app = BIN / "app"
    images = [p for p in app.iterdir()] if app.is_dir() else []
    if images:
        if a.name.startswith("linux"):
            with tarfile.open(out / f"{base}-portable.tar.gz", "w:gz") as t:
                t.add(images[0], arcname=images[0].name)
        else:
            shutil.make_archive(str(out / f"{base}-portable"), "zip", app)
        found += 1
    for f in sorted(out.iterdir()):
        print(f"{f.name}  {f.stat().st_size / 1e6:.0f} MB")
    return 0 if found else 1


if __name__ == "__main__":
    sys.exit(main())
