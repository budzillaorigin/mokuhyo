"""Publish or fetch the rendered audio packs through a GitHub release of this repo.

Audio packs are rendered with VOICEVOX (packs/render_audio.py), which only runs on machines with the engine, so a
fresh checkout (e.g. the Mac that builds the iOS app) can't rebuild them. This script moves the rendered files
between machines through a pre-release of this private repo, pinned by sha256 in tools/packs/audio.lock.

    uv run python packs/audio_release.py publish   # after rendering: upload content/packs/audio-* and write the lock
    uv run python packs/audio_release.py fetch     # on another machine: download into content/packs, verify hashes

Needs a token: $GITHUB_TOKEN / $GH_TOKEN, or `gh auth login`. This is for the owner's own machines. How learners
download audio packs in the app is D-096: from Files or a URL they type; there is no default URL.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import urllib.parse
from pathlib import Path

from common import CACHE, PACKS, github_token, log, sha256
from common import _fetch_mirror as fetch_asset
from mirror_sources import REPO, UPLOADS, _call, ensure_release

LOCK = Path(__file__).resolve().parent / "audio.lock"
NOTES = (
    "Rendered VOICEVOX audio packs for Tsumugi (see docs/CONTENT_PACKS.md). Credits: VOICEVOX:春日部つむぎ, "
    "VOICEVOX:四国めたん, VOICEVOX:玄野武宏 — each under its character's terms (docs/LICENSES.md). "
    "Not an app release."
)


def title(tag: str) -> str:
    return f"Audio packs (VOICEVOX) {tag.removeprefix('audio-packs-')}"


def files() -> list[Path]:
    return sorted(PACKS.glob("audio-*.zip")) + [PACKS / "audio-manifest.json"]


def publish(repo: str) -> None:
    token = github_token()
    if not token:
        sys.exit("No GitHub token: set GITHUB_TOKEN / GH_TOKEN or run `gh auth login`.")
    present = [f for f in files() if f.exists()]
    if not present:
        sys.exit("No audio packs in content/packs; render them first (packs/render_audio.py).")
    tag = f"audio-packs-{dt.datetime.now(dt.UTC).date().isoformat()}"
    release = ensure_release(repo, tag, "audio", token)
    if release.get("body") != NOTES or release.get("name") != title(tag):
        _call("PATCH", f"https://api.github.com/repos/{repo}/releases/{release['id']}", token,
              json.dumps({"name": title(tag), "body": NOTES}).encode())
    attached = {a["name"]: a for a in release.get("assets", [])}
    lock = {"repo": repo, "tag": tag, "files": {}}
    for f in present:
        digest = sha256(f)
        existing = attached.get(f.name)
        # GitHub reports an asset digest ("sha256:…"); size alone misses a same-size re-render under the same tag.
        same = existing and (existing.get("digest") == f"sha256:{digest}"
                             if existing.get("digest") else existing["size"] == f.stat().st_size)
        if same:
            log(f"{f.name}: already attached")
        else:
            if existing:
                _call("DELETE", f"https://api.github.com/repos/{repo}/releases/assets/{existing['id']}", token)
            log(f"uploading {f.name} ({f.stat().st_size / 1e6:.1f} MB)")
            url = f"{UPLOADS}{repo}/releases/{release['id']}/assets?name={urllib.parse.quote(f.name)}"
            _call("POST", url, token, f.read_bytes(), "application/octet-stream")
        lock["files"][f.name] = digest
    LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"wrote {LOCK.name}; commit it")


def fetch() -> None:
    if not LOCK.exists():
        sys.exit(f"{LOCK.name} is missing: publish from the machine that rendered the audio first.")
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    PACKS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, digest in lock["files"].items():
        target = PACKS / name
        if target.exists() and sha256(target) == digest:
            log(f"{name}: up to date")
            continue
        got = fetch_asset({"repo": lock["repo"], "tag": lock["tag"]}, name, target)
        if got != digest:
            target.unlink(missing_ok=True)
            sys.exit(f"{name}: sha256 mismatch (locked {digest}, got {got})")
    log("audio packs ready in content/packs")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("action", choices=["publish", "fetch"])
    ap.add_argument("--repo", default=REPO)
    args = ap.parse_args(argv)
    publish(args.repo) if args.action == "publish" else fetch()
    return 0


if __name__ == "__main__":
    sys.exit(main())
