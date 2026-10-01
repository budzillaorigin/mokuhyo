"""Downloads the content-pack archive pinned in release/packs.lock (SHA-256 verified) and unpacks it into
content/packs/ (release.yml). Uses `gh` so it works for this private repository's release assets.

    uv run python release/fetch_packs.py
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent


def main() -> int:
    lock = json.loads((TOOLS / "release" / "packs.lock").read_text(encoding="utf-8"))
    dest = REPO / "build"
    dest.mkdir(exist_ok=True)
    archive = dest / lock["asset"]
    if not (archive.exists() and hashlib.sha256(archive.read_bytes()).hexdigest() == lock["sha256"]):
        subprocess.run(["gh", "release", "download", lock["tag"], "--repo", lock["repo"], "--pattern", lock["asset"], "--dir", str(dest), "--clobber"], check=True)
    got = hashlib.sha256(archive.read_bytes()).hexdigest()
    if got != lock["sha256"]:
        print(f"checksum mismatch for {archive.name}: {got}", file=sys.stderr)
        return 1
    with zipfile.ZipFile(archive) as z:
        z.extractall(REPO / "content" / "packs")
    print(f"content packs {lock['tag']}: {', '.join(sorted(lock['languages']))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
