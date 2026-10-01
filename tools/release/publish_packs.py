"""Zips the built content packs (content/packs/<lang>/: exam.json, opi.json, dictionary.sqlite, tokenizer.sqlite,
audio/) into one archive, uploads it to a GitHub pre-release `packs-<date>` in this (private) repository, and pins
URL + SHA-256 in release/packs.lock for release.yml (BRIEF §10: CI doesn't rebuild 7 GB of dictionary sources).

    uv run python release/publish_packs.py [--dry-run]
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
PACKS = REPO / "content" / "packs"
LOCK = TOOLS / "release" / "packs.lock"
KEEP = ("exam.json", "opi.json", "dictionary.sqlite", "tokenizer.sqlite", "exam.manifest.json", "dictionary.json")


def build_zip(out: Path) -> dict:
    counts = {}
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for lang_dir in sorted(p for p in PACKS.iterdir() if p.is_dir()):
            files = [lang_dir / f for f in KEEP if (lang_dir / f).is_file()]
            audio = sorted((lang_dir / "audio").glob("*.ogg")) if (lang_dir / "audio").is_dir() else []
            for f in files + audio:
                z.write(f, f.relative_to(PACKS).as_posix())
            counts[lang_dir.name] = {"files": len(files), "clips": len(audio)}
    return counts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    tag = "packs-" + dt.datetime.now(dt.UTC).strftime("%Y%m%d-%H%M")
    out = REPO / "build" / f"mokuhyo-{tag}.zip"
    out.parent.mkdir(parents=True, exist_ok=True)
    counts = build_zip(out)
    sha = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"{out.name}: {out.stat().st_size / 1e6:.0f} MB, sha256 {sha}")
    if args.dry_run:
        return 0
    notes = "Content packs for installer builds (not an app release). Built by tools/release/publish_packs.py."
    subprocess.run(["gh", "release", "create", tag, str(out), "--prerelease", "--title", f"Content packs {tag}", "--notes", notes], check=True)
    repo = subprocess.run(["gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"], capture_output=True, text=True, check=True).stdout.strip()
    LOCK.write_text(json.dumps({"tag": tag, "repo": repo, "asset": out.name, "sha256": sha, "bytes": out.stat().st_size, "languages": counts}, indent=1) + "\n",
                    encoding="utf-8")
    print(f"pinned in {LOCK.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
