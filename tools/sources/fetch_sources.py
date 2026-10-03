#!/usr/bin/env python3
"""Re-fetch and verify the reference sources listed in tools/sources/SOURCES.json (BRIEF_PHASE8 C-00).

The PDFs are git-ignored (~700 MB); SOURCES.json keeps each file's URL and SHA-256 so another machine can rebuild the
folder. `distribution: "limited"` rows (us-limited/, JEL+ pulls) are never downloaded, opened or hashed: they are the
owner's personal reference only.

  uv run python sources/fetch_sources.py            # download every acquired row that is missing or wrong, then verify
  uv run python sources/fetch_sources.py --check    # verify local files only (no network); exit 1 on any mismatch
  uv run python sources/fetch_sources.py --list     # one line per row with its rights flags

Rows whose URL is not http(s) (e.g. "JEL+ (CAC)") can't be fetched; when the file is missing they are reported as
"manual" and fail --check. Timeouts: connect 5 s, download 120 s per file (CLAUDE.md rule 9).
"""
from __future__ import annotations

import argparse
import socket
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "terms"))
import sources as S

CONNECT_S = 5
DOWNLOAD_S = 120
FETCHABLE = ("http://", "https://", "file://")  # file:// = a local or network mirror


def fetch(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "mokuhyo-fetch-sources/1"})
    old = socket.getdefaulttimeout()
    socket.setdefaulttimeout(CONNECT_S)
    try:
        with urllib.request.urlopen(req, timeout=DOWNLOAD_S) as r, open(tmp, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
    finally:
        socket.setdefaulttimeout(old)
    tmp.replace(dest)


def candidates(rows: dict[str, dict]) -> list[dict]:
    return [r for r in rows.values() if r.get("status") in ("acquired", "superseded") and r.get("path") and not S.is_limited(r)]


def run(check_only: bool, rows: dict[str, dict] | None = None, base: Path | None = None) -> int:
    rows = rows if rows is not None else S.rows()
    base = base or S.SOURCES_DIR
    bad = 0
    for r in candidates(rows):
        dest = base / r["path"]
        ok = dest.exists() and (not r.get("sha256") or S.sha256(dest) == r["sha256"])
        if ok:
            print(f"  ok       {r['id']}")
            continue
        url = str(r.get("url") or "")
        if check_only or not url.startswith(FETCHABLE):
            state = "MISSING" if not dest.exists() else "SHA-MISMATCH"
            how = "" if url.startswith(FETCHABLE) else f" (manual: {url or 'no URL'})"
            print(f"  {state:8} {r['id']}: {r['path']}{how}")
            bad += 1
            continue
        try:
            print(f"  fetching {r['id']} <- {url}")
            fetch(url, dest)
        except Exception as e:  # noqa: BLE001 - report and continue with the next row
            print(f"  FAILED   {r['id']}: {e}")
            bad += 1
            continue
        if r.get("sha256") and S.sha256(dest) != r["sha256"]:
            print(f"  SHA-MISMATCH {r['id']}: downloaded file differs from SOURCES.json (the publisher may have replaced it)")
            bad += 1
    skipped = sum(1 for x in rows.values() if S.is_limited(x))
    print(f"fetch_sources: {len(candidates(rows)) - bad} verified, {bad} problem(s), {skipped} limited row(s) never touched")
    return 1 if bad else 0


def list_rows() -> int:
    for r in S.rows().values():
        flags = f"verbatim={r.get('verbatim_ok')} align={r.get('alignment_ok')} machine={r.get('machine_extract_ok')}"
        lim = " LIMITED" if S.is_limited(r) else ""
        lic = "license" if r.get("license") else "NO-LICENSE"
        print(f"{r['id']:28} {r.get('status', ''):12} {flags} {lic}{lim}")
    missing = [r["id"] for r in S.rows().values() if r.get("status") == "acquired" and not r.get("license")]
    if missing:
        print("rows without a license field: " + ", ".join(missing))
        return 1
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="verify local files only; no network")
    ap.add_argument("--list", action="store_true", help="list rows and rights flags")
    a = ap.parse_args()
    if a.list:
        return list_rows()
    return run(a.check)


if __name__ == "__main__":
    sys.exit(main())

