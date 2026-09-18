"""Mirror rolling upstream exports (Tatoeba) into a GitHub release of this repo and record it in sources.lock.

Tatoeba only publishes its latest weekly export, so a pinned sha256 stops being downloadable within a week. This
script uploads the exact files the lock pins (from tools/.cache, verified against the lock) as assets of a release
tagged `sources-<group>-<date>`, and writes `"mirror": {"repo", "tag"}` into each entry. `common.source()` then
downloads from that release first and falls back to upstream.

Run after re-pinning:
    uv run python packs/build_all.py --update-sources tatoeba-jpn-sentences ...   # deliberate re-pin
    uv run python packs/mirror_sources.py                                          # mirror what's now locked

Needs a token with `repo` (contents: write) scope: $GITHUB_TOKEN / $GH_TOKEN, or `gh auth login`. The release is
created as a non-latest pre-release so it never shows up as an app release. Re-running is idempotent: files already
attached with the same size are skipped. The data keeps its upstream license (Tatoeba: CC BY 2.0 FR); the release
notes carry the attribution.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict

from common import CACHE, GITHUB_API, SOURCES_LOCK, github_token, load_lock, log, sha256

REPO = "budzillaorigin/tsumugi"
UPLOADS = "https://uploads.github.com/repos/"

NOTES = {
    "tatoeba": (
        "Frozen copies of the Tatoeba per-language exports pinned in `tools/packs/sources.lock`, so content packs "
        "rebuild reproducibly after Tatoeba publishes a newer weekly export.\n\n"
        "Data: Tatoeba (https://tatoeba.org), licensed CC BY 2.0 FR "
        "(https://creativecommons.org/licenses/by/2.0/fr/). Sentences are by their Tatoeba contributors.\n\n"
        "Not an app release."
    ),
}


def _call(method: str, url: str, token: str, body: bytes | None = None, content_type: str = "application/json"):
    req = urllib.request.Request(url, data=body, method=method, headers={
        "User-Agent": "tsumugi-tools",
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "Content-Type": content_type,
    })
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)


def ensure_release(repo: str, tag: str, group: str, token: str) -> dict:
    try:
        return _call("GET", f"{GITHUB_API}{repo}/releases/tags/{tag}", token)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    log(f"creating release {tag}")
    body = {
        "tag_name": tag,
        "name": f"Pinned content sources: {tag.removeprefix('sources-')}",
        "body": NOTES.get(group, "Frozen copies of pinned content-pack sources. Not an app release."),
        "prerelease": True,
        "make_latest": "false",
    }
    return _call("POST", f"{GITHUB_API}{repo}/releases", token, json.dumps(body).encode())


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("names", nargs="*", help="lock entries to mirror (default: every rolling entry)")
    ap.add_argument("--repo", default=REPO)
    args = ap.parse_args(argv)

    token = github_token()
    if not token:
        sys.exit("No GitHub token: set GITHUB_TOKEN / GH_TOKEN or run `gh auth login`.")
    lock = load_lock()
    names = args.names or [n for n, e in lock["sources"].items() if e.get("rolling")]
    groups: dict[tuple[str, str], list[str]] = defaultdict(list)
    for name in names:
        entry = lock["sources"][name]
        group = name.split("-")[0]
        groups[(group, entry.get("date") or "undated")].append(name)

    for (group, date), members in groups.items():
        tag = f"sources-{group}-{date}"
        release = ensure_release(args.repo, tag, group, token)
        attached = {a["name"]: a for a in release.get("assets", [])}
        for name in members:
            entry = lock["sources"][name]
            path = CACHE / entry["file"]
            if not path.exists() or sha256(path) != entry["sha256"]:
                sys.exit(f"{name}: {path} is missing or doesn't match the lock; run a build first to fetch it")
            existing = attached.get(entry["file"])
            if existing and existing["size"] == path.stat().st_size:
                log(f"{name}: already attached to {tag}")
            else:
                if existing:
                    _call("DELETE", f"{GITHUB_API}{args.repo}/releases/assets/{existing['id']}", token)
                log(f"{name}: uploading {entry['file']} ({path.stat().st_size / 1e6:.1f} MB) to {tag}")
                url = f"{UPLOADS}{args.repo}/releases/{release['id']}/assets?name={urllib.parse.quote(entry['file'])}"
                _call("POST", url, token, path.read_bytes(), "application/octet-stream")
            entry["mirror"] = {"repo": args.repo, "tag": tag}

    SOURCES_LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"updated {SOURCES_LOCK.name}; commit it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
