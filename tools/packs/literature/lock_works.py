"""Pin Aozora works in packs/sources.lock (BRIEF_V2 §6.14; DECISIONS D-276).

Every work named in poems/*.json, circle.json and the translation passages' Aozora excerpts gets an entry
`aozora-<work id>` whose URL is the text zip listed in the pinned catalogue (Aozora versions its files by name, so the
URL is fixed, `update.kind = "fixed"`). The catalogue itself is `aozora-catalogue`, a rolling export like Tatoeba's:
mirror it with `uv run python packs/mirror_sources.py aozora-catalogue` after re-pinning.

Run: uv run python packs/literature/lock_works.py            # pin works that aren't in the lock yet
     uv run python packs/literature/lock_works.py --catalogue # also re-pin the catalogue to the current export
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import aozora
from common import CACHE, SOURCES_LOCK, _fetch, load_lock, log, sha256

CATALOGUE_URL = "https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip"


def wanted_works() -> list[str]:
    """Work ids referenced by the poetry, circle and translation sources."""
    ids: list[str] = []
    for f in sorted((HERE / "poems").glob("*.json")):
        ids += [p["work"] for p in json.loads(f.read_text(encoding="utf-8"))["poems"]]
    ids += [c["work"] for c in json.loads((HERE / "circle.json").read_text(encoding="utf-8"))["texts"]]
    for f in sorted((HERE.parent / "translation" / "passages").glob("*.json")):
        for p in json.loads(f.read_text(encoding="utf-8"))["passages"]:
            origin = p.get("origin") or {}
            if origin.get("kind") == "aozora":
                ids.append(origin["ref"])
    return list(dict.fromkeys(ids))


def pin_catalogue(lock: dict) -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / "list_person_all_extended_utf8.zip"
    digest = _fetch(CATALOGUE_URL, target)
    old = lock["sources"].get(aozora.CATALOGUE, {})
    if old.get("sha256") == digest:
        log("aozora-catalogue: unchanged")
        return
    import datetime as dt

    lock["sources"][aozora.CATALOGUE] = {
        "url": CATALOGUE_URL, "file": target.name, "release": "rolling export",
        "date": dt.datetime.now(dt.UTC).date().isoformat(), "rolling": True, "sha256": digest,
        "license": "Aozora Bunko catalogue (public information; texts are public domain)",
        "update": {"kind": "rolling", "url": CATALOGUE_URL, "file": target.name},
    }
    log(f"aozora-catalogue: pinned {digest[:12]}; mirror it with packs/mirror_sources.py aozora-catalogue")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--catalogue", action="store_true", help="re-pin the catalogue to the current export")
    args = ap.parse_args(argv)
    lock = load_lock()
    if args.catalogue or aozora.CATALOGUE not in lock["sources"]:
        pin_catalogue(lock)
        SOURCES_LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    rows = aozora.catalogue()
    added = 0
    for wid in wanted_works():
        name = f"aozora-{wid}"
        if name in lock["sources"]:
            continue
        work = rows.get(wid)
        url = next((r.text_url for r in work or [] if r.text_url), "")
        if not url:
            raise SystemExit(f"{wid}: not in the catalogue or has no text file")
        target = CACHE / url.rsplit("/", 1)[1]
        digest = _fetch(url, target)
        lock["sources"][name] = {
            "url": url, "file": target.name, "release": url.rsplit("/", 1)[1].removesuffix(".zip"),
            "date": next((r.text_updated for r in work if r.text_url), ""), "sha256": digest, "license": "Public domain (Aozora Bunko; checked by build_literature.py)",
            "update": {"kind": "fixed"},
        }
        added += 1
        log(f"{name}: pinned {work[0].title} ({digest[:12]})")
    SOURCES_LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"{added} works pinned; {sha256(SOURCES_LOCK)[:12]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
