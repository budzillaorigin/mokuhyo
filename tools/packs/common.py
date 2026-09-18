"""Shared helpers for content-pack builders: downloads with caching, schema from the .sq source of truth,
and Japanese text helpers that must match shared/src/commonMain/kotlin/app/tsumugi/jp exactly."""

from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import unicodedata
import urllib.error
import urllib.request
import zipfile
from email.utils import parsedate_to_datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CACHE = REPO / "tools" / ".cache"
PACKS = REPO / "content" / "packs"
DICTIONARY_PACK = PACKS / "dictionary.sqlite"
DICTIONARY_SQ = (
    REPO / "shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/dictionary.sq"
)
PATH_PACK = PACKS / "kanji-path.sqlite"
PATH_SQ = REPO / "shared/src/commonMain/sqldelightPath/app/tsumugi/path/db/path.sq"

# Bump when the builder output changes shape or content rules. Recorded in pack_meta.
DICTIONARY_PACK_VERSION = "1"
PATH_PACK_VERSION = "1"


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


# --- Pinned sources (tools/packs/sources.lock, F-38) ------------------------------------------------------------
#
# Every downloaded input is pinned in sources.lock: URL, release tag or commit, date, sha256. Builders call
# source(name), which downloads into tools/.cache once and refuses a file whose hash doesn't match. Nothing
# follows "latest" during a normal build. `build_all.py --update-sources` re-resolves each source (newest GitHub
# release, branch head commit, or the current rolling export), downloads it, and rewrites the lock; review the
# diff and commit it like any other change.

SOURCES_LOCK = Path(__file__).resolve().parent / "sources.lock"
GITHUB_API = "https://api.github" + ".com/repos/"


def load_lock() -> dict:
    return json.loads(SOURCES_LOCK.read_text(encoding="utf-8"))


def _fetch(url: str, target: Path, quiet: bool = False) -> str:
    """Download url to target via a .part file; return the sha256 of what was written."""
    if not quiet:
        log(f"downloading {url}")
    tmp = target.with_suffix(target.suffix + ".part")
    h = hashlib.sha256()
    req = urllib.request.Request(url, headers={"User-Agent": "tsumugi-tools"})
    try:
        with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
            while chunk := resp.read(1 << 20):
                h.update(chunk)
                out.write(chunk)
        tmp.replace(target)
    finally:
        tmp.unlink(missing_ok=True)
    return h.hexdigest()


def github_token() -> str | None:
    """Token for private-repo release downloads/uploads: $GITHUB_TOKEN (CI), $GH_TOKEN, or `gh auth token`."""
    for var in ("GITHUB_TOKEN", "GH_TOKEN"):
        if os.environ.get(var):
            return os.environ[var]
    try:
        out = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=10, check=False)
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _fetch_mirror(mirror: dict, file: str, target: Path) -> str:
    """Download a release asset from the repo's own GitHub release (works for private repos with a token).

    The asset endpoint answers with a redirect to signed storage; the Authorization header must not follow it.
    """
    token = github_token()
    headers = {"User-Agent": "tsumugi-tools", "Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{GITHUB_API}{mirror['repo']}/releases/tags/{mirror['tag']}", headers=headers)
    with urllib.request.urlopen(req, timeout=30) as resp:
        release = json.load(resp)
    asset = next((a for a in release["assets"] if a["name"] == file), None)
    if asset is None:
        raise OSError(f"{file} is not attached to release {mirror['tag']}")
    req = urllib.request.Request(asset["url"], headers={**headers, "Accept": "application/octet-stream"})
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        resp = opener.open(req, timeout=30)
        location = None
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302, 303, 307, 308):
            raise
        location = e.headers["Location"]
    if location is None:  # served directly
        log(f"downloading {file} from release {mirror['tag']}")
        tmp = target.with_suffix(target.suffix + ".part")
        h = hashlib.sha256()
        try:
            with resp, open(tmp, "wb") as out:
                while chunk := resp.read(1 << 20):
                    h.update(chunk)
                    out.write(chunk)
            tmp.replace(target)
        finally:
            tmp.unlink(missing_ok=True)
        return h.hexdigest()
    log(f"downloading {file} from release {mirror['tag']}")
    return _fetch(location, target, quiet=True)


def source(name: str) -> Path:
    """Local path of the locked source [name], downloaded into tools/.cache and verified against its sha256.

    Entries with a `mirror` (rolling upstream exports, e.g. Tatoeba) are fetched from this repo's own GitHub
    release first, because upstream only keeps the latest export; the upstream URL is the fallback.
    """
    entry = load_lock()["sources"].get(name)
    if entry is None:
        raise SystemExit(f"{name} is not in {SOURCES_LOCK.name}; add it with build_all.py --update-sources")
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / entry["file"]
    if target.exists() and sha256(target) == entry["sha256"]:
        return target
    got = None
    if entry.get("mirror"):
        try:
            got = _fetch_mirror(entry["mirror"], entry["file"], target)
        except (OSError, urllib.error.URLError, KeyError, ValueError) as e:
            log(f"{name}: mirror {entry['mirror']['tag']} unavailable ({e}); trying upstream. For a private repo set "
                "GITHUB_TOKEN / GH_TOKEN or log in with `gh auth login`.")
    if got != entry["sha256"]:
        got = _fetch(entry["url"], target)
    if got != entry["sha256"]:
        target.unlink(missing_ok=True)
        hint = (
            "It is a rolling export (only the latest is published), so upstream has moved on, and the mirror "
            "release couldn't be used."
            if entry.get("rolling") else "The pinned release changed upstream or the download was corrupted."
        )
        raise SystemExit(
            f"sha256 mismatch for {name} ({entry['url']}):\n  locked {entry['sha256']}\n  got    {got}\n"
            f"{hint} Run `uv run python packs/build_all.py --update-sources` to re-pin deliberately "
            "(then `uv run python packs/mirror_sources.py` to mirror rolling exports)."
        )
    return target


def source_entry(name: str) -> dict:
    return load_lock()["sources"][name]


def _api(path: str):
    req = urllib.request.Request(GITHUB_API + path, headers={"User-Agent": "tsumugi-tools"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def _resolve(update: dict) -> dict:
    """Newest upstream location for a lock entry's `update` rule: url, file, release, date."""
    kind = update["kind"]
    if kind == "github-release":
        rel = _api(f"{update['repo']}/releases/latest")
        for asset in rel["assets"]:
            if re.fullmatch(update["asset"], asset["name"]):
                return {"url": asset["browser_download_url"], "file": asset["name"],
                        "release": rel["tag_name"], "date": rel["published_at"][:10]}
        raise SystemExit(f"no asset matching {update['asset']} in {update['repo']} {rel['tag_name']}")
    if kind == "github-file":
        commit = _api(f"{update['repo']}/commits/{update['branch']}")
        sha = commit["sha"]
        return {"url": f"https://raw.githubusercontent.com/{update['repo']}/{sha}/{update['path']}",
                "file": update["file"], "release": sha, "date": commit["commit"]["committer"]["date"][:10]}
    if kind == "rolling":
        req = urllib.request.Request(update["url"], method="HEAD", headers={"User-Agent": "tsumugi-tools"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            modified = resp.headers.get("Last-Modified", "")
        date = parsedate_to_datetime(modified).date().isoformat() if modified else ""
        return {"url": update["url"], "file": update["file"], "release": "rolling export", "date": date}
    if kind == "fixed":
        return {}
    raise SystemExit(f"unknown update kind {kind}")


def update_sources(names: list[str] | None = None) -> None:
    """Re-resolve, download and re-hash every source (or [names]); rewrite sources.lock."""
    lock = load_lock()
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, entry in lock["sources"].items():
        if names and name not in names:
            continue
        resolved = _resolve(entry["update"])
        new = {**entry, **resolved}
        target = CACHE / new["file"]
        unchanged = new["url"] == entry["url"] and not entry.get("rolling")
        if unchanged and target.exists() and sha256(target) == entry["sha256"]:
            log(f"{name}: unchanged ({new['release']})")
            continue
        new["sha256"] = _fetch(new["url"], target)
        if new["sha256"] != entry["sha256"]:
            new.pop("mirror", None)  # the old mirror holds the old file; re-run packs/mirror_sources.py
            log(f"{name}: {entry.get('release')} -> {new['release']} ({new['sha256'][:12]})")
        lock["sources"][name] = new
    SOURCES_LOCK.write_text(json.dumps(lock, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"wrote {SOURCES_LOCK}; review `git diff tools/packs/sources.lock` and commit it")


def read_zip_json(path: Path):
    with zipfile.ZipFile(path) as zf:
        name = next(n for n in zf.namelist() if n.endswith(".json"))
        with zf.open(name) as f:
            return json.loads(f.read().decode("utf-8-sig"))


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


# --- Schema ---------------------------------------------------------------------------------------------


def schema_statements(sq_path: Path = DICTIONARY_SQ) -> list[str]:
    """CREATE statements from a SQLDelight .sq file (everything before the first labeled query)."""
    text = sq_path.read_text(encoding="utf-8")
    text = re.sub(r"--[^\n]*", "", text)
    ddl = re.split(r"^\w+:\s*$", text, maxsplit=1, flags=re.MULTILINE)[0]
    return [s.strip() for s in ddl.split(";") if s.strip()]


def table_statements(tables: set[str], sq_path: Path = DICTIONARY_SQ) -> list[str]:
    """Subset of schema statements that create or index the given tables."""
    out = []
    for stmt in schema_statements(sq_path):
        m = re.match(r"CREATE (?:TABLE|INDEX \w+ ON) (\w+)", stmt)
        if m and m.group(1) in tables:
            out.append(stmt)
    return out


def open_pack(path: Path = DICTIONARY_PACK, sq_path: Path = DICTIONARY_SQ) -> sqlite3.Connection:
    """Open (creating if needed) a pack with the full schema. user_version=1 so SQLDelight never re-creates it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fresh = not path.exists()
    db = sqlite3.connect(path)
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    if fresh:
        for stmt in schema_statements(sq_path):
            db.execute(stmt)
        db.execute("PRAGMA user_version = 1")
    return db


def reset_tables(db: sqlite3.Connection, tables: set[str], sq_path: Path = DICTIONARY_SQ) -> None:
    for t in tables:
        db.execute(f"DROP TABLE IF EXISTS {t}")
    for stmt in table_statements(tables, sq_path):
        db.execute(stmt)


def set_meta(db: sqlite3.Connection, **values: str) -> None:
    db.executemany("INSERT OR REPLACE INTO pack_meta(key, value) VALUES (?, ?)", values.items())


def finish_pack(db: sqlite3.Connection) -> None:
    db.commit()
    db.execute("ANALYZE")
    db.execute("VACUUM")
    db.close()


# --- Japanese text (mirror of app.tsumugi.jp.Kana) ---------------------------------------------------


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def to_hiragana(s: str) -> str:
    """Fold katakana (ァ..ヶ) to hiragana. ー and other characters unchanged. Search keys only."""
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in s)


def is_kanji(c: str) -> bool:
    o = ord(c)
    return 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or 0x20000 <= o <= 0x2FFFF or c in "々〆"


def dumps(v) -> str:
    return json.dumps(v, ensure_ascii=False, separators=(",", ":"))


def write_manifest() -> None:
    """content/packs/manifest.json: what the apps bundle and install (see shared PackInstaller)."""
    packs = []
    for path in sorted(PACKS.glob("*.sqlite")):
        db = sqlite3.connect(path)
        row = db.execute("SELECT value FROM pack_meta WHERE key = 'pack_version'").fetchone()
        db.close()
        digest = sha256(path)
        packs.append({
            "file": path.name,
            "version": f"{row[0] if row else '0'}-{digest[:12]}",
            "sha256": digest,
            "bytes": path.stat().st_size,
        })
    (PACKS / "manifest.json").write_text(json.dumps({"packs": packs}, indent=2) + "\n", encoding="utf-8")
