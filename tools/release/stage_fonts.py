"""Fetches the bundled Noto fonts pinned in release/fonts.lock (SHA-256 verified) into tools/.cache/fonts and copies
them to desktopApp/resources/common/fonts/ for the installer (BRIEF §9: CJK and Arabic never fall back to boxes).

    uv run python release/stage_fonts.py [--cache-only]
"""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import urllib.request
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
CACHE = TOOLS / ".cache" / "fonts"
DEST = REPO / "desktopApp" / "resources" / "common" / "fonts"


def ensure(entry: dict) -> Path:
    path = CACHE / entry["file"]
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"]:
        return path
    CACHE.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(entry["url"], timeout=120) as r:
        data = r.read()
    if hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise SystemExit(f"checksum mismatch for {entry['file']}")
    path.write_bytes(data)
    return path


def main() -> int:
    lock = json.loads((TOOLS / "release" / "fonts.lock").read_text(encoding="utf-8"))
    paths = [ensure(e) for e in lock["fonts"]]
    if "--cache-only" not in sys.argv:
        DEST.mkdir(parents=True, exist_ok=True)
        for p in paths:
            shutil.copy2(p, DEST / p.name)
        (DEST / "OFL.txt").write_text(
            "These fonts are licensed under the SIL Open Font License, Version 1.1 (https://openfontlicense.org).\n"
            "Copyright The Noto Project Authors (https://github.com/notofonts).\n", encoding="utf-8")
    print(f"fonts: {len(paths)} ready" + ("" if "--cache-only" in sys.argv else f" in {DEST.relative_to(REPO)}"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
