"""Shared helpers for content-pack builders: downloads with caching, schema from the .sq source of truth,
and Japanese text helpers that must match shared/src/commonMain/kotlin/app/tsumugi/jp exactly."""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import sys
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CACHE = REPO / "tools" / ".cache"
PACKS = REPO / "content" / "packs"
DICTIONARY_PACK = PACKS / "dictionary.sqlite"
DICTIONARY_SQ = (
    REPO / "shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/dictionary.sq"
)

# Bump when the builder output changes shape or content rules. Recorded in pack_meta.
DICTIONARY_PACK_VERSION = "1"


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def download(url: str, name: str | None = None) -> Path:
    """Download url into tools/.cache (once) and return the local path."""
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / (name or url.rsplit("/", 1)[-1].replace("%2B", "+"))
    if not target.exists():
        log(f"downloading {url}")
        tmp = target.with_suffix(target.suffix + ".part")
        req = urllib.request.Request(url, headers={"User-Agent": "tsumugi-tools"})
        with urllib.request.urlopen(req) as resp, open(tmp, "wb") as out:
            while chunk := resp.read(1 << 20):
                out.write(chunk)
        tmp.replace(target)
    return target


def latest_release_asset(repo: str, pattern: str) -> str:
    """URL of the first asset in the latest GitHub release of repo whose name matches pattern."""
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/latest", headers={"User-Agent": "tsumugi-tools"}
    )
    with urllib.request.urlopen(req) as resp:
        release = json.load(resp)
    for asset in release["assets"]:
        if re.fullmatch(pattern, asset["name"]):
            return asset["browser_download_url"]
    raise SystemExit(f"no asset matching {pattern} in {repo} {release['tag_name']}")


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


def table_statements(tables: set[str]) -> list[str]:
    """Subset of schema statements that create or index the given tables."""
    out = []
    for stmt in schema_statements():
        m = re.match(r"CREATE (?:TABLE|INDEX \w+ ON) (\w+)", stmt)
        if m and m.group(1) in tables:
            out.append(stmt)
    return out


def open_pack(path: Path = DICTIONARY_PACK) -> sqlite3.Connection:
    """Open (creating if needed) a pack with the full schema. user_version=1 so SQLDelight never re-creates it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fresh = not path.exists()
    db = sqlite3.connect(path)
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    if fresh:
        for stmt in schema_statements():
            db.execute(stmt)
        db.execute("PRAGMA user_version = 1")
    return db


def reset_tables(db: sqlite3.Connection, tables: set[str]) -> None:
    for t in tables:
        db.execute(f"DROP TABLE IF EXISTS {t}")
    for stmt in table_statements(tables):
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
