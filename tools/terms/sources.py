"""Guarded access to the reference sources in tools/sources/ (CLAUDE.md rule 6, docs/TERM_PIPELINE.md).

Every script that reads a source goes through `require()` with the purpose it reads for:

- `overlap`  mechanical comparison only (overlap_check.py): any acquired row except `distribution: "limited"`.
- `parse`    bulk parsing by a script (term extraction, page look-ups): `machine_extract_ok is True`.
- `llm`      text that may appear in a model prompt: `machine_extract_ok is True`.
- `align`    choosing a term / confirming meaning, by a human or a script: `alignment_ok is True`.

`distribution: "limited"` rows (tools/sources/us-limited/) are refused for every purpose, before any file is opened.
`machine_extract_ok` of `"unclear"` or `false` is refused for `parse` and `llm` (human review only).

Page text: `pages(source_id)` returns the source's text split per PDF page (pdftotext, cached under
tools/sources/.cache/pages/, git-ignored, never shipped). Only `parse`/`llm`-cleared sources get a page cache.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from functools import lru_cache
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
SOURCES_DIR = TOOLS / "sources"
SOURCES_JSON = SOURCES_DIR / "SOURCES.json"
PAGE_CACHE = SOURCES_DIR / ".cache" / "pages"
PURPOSES = ("overlap", "parse", "llm", "align")


class SourceRefused(PermissionError):
    pass


@lru_cache(maxsize=1)
def rows() -> dict[str, dict]:
    data = json.loads(SOURCES_JSON.read_text(encoding="utf-8"))
    return {r["id"]: r for r in data["sources"]}


def is_limited(row: dict) -> bool:
    return row.get("distribution") == "limited" or str(row.get("path") or "").startswith("us-limited/")


def refusal(row: dict, purpose: str) -> str | None:
    """Why [row] may not be read for [purpose], or None when it may."""
    if purpose not in PURPOSES:
        raise ValueError(f"unknown purpose {purpose}")
    if is_limited(row):
        return f"{row['id']} is distribution-limited: never read, extracted or fed to a model"
    if row.get("status") not in ("acquired", "superseded") or not row.get("path"):
        return f"{row['id']} is not acquired (status {row.get('status')})"
    if purpose in ("parse", "llm") and row.get("machine_extract_ok") is not True:
        return f"{row['id']} has machine_extract_ok={row.get('machine_extract_ok')!r}: human review only"
    if purpose == "align" and row.get("alignment_ok") is not True:
        return f"{row['id']} is not alignment_ok"
    return None


def require(source_id: str, purpose: str) -> dict:
    row = rows().get(source_id)
    if row is None:
        raise SourceRefused(f"{source_id} is not in SOURCES.json")
    why = refusal(row, purpose)
    if why:
        raise SourceRefused(why)
    return row


def allowed(purpose: str, lang: str | None = None) -> list[dict]:
    """Rows readable for [purpose], optionally covering [lang] (zh-* share a family)."""
    def fam(x: str) -> str:
        return "zh" if x.startswith("zh") else x
    out = []
    for r in rows().values():
        if refusal(r, purpose):
            continue
        if lang and fam(lang) not in {fam(x) for x in r.get("lang") or []}:
            continue
        out.append(r)
    return out


def path(row: dict) -> Path:
    if is_limited(row):  # belt and braces: never even build the path
        raise SourceRefused(f"{row['id']} is distribution-limited")
    return SOURCES_DIR / row["path"]


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pages(source_id: str, purpose: str = "parse") -> list[str]:
    """The source's text per PDF page (index 0 = page 1). Cached by sha256."""
    row = require(source_id, purpose)
    PAGE_CACHE.mkdir(parents=True, exist_ok=True)
    txt = PAGE_CACHE / f"{source_id}.txt"
    meta = PAGE_CACHE / f"{source_id}.meta.json"
    if txt.exists() and meta.exists() and json.loads(meta.read_text()).get("sha256") == row.get("sha256"):
        return txt.read_text(encoding="utf-8").split("\f")
    src = path(row)
    if not src.exists():
        raise FileNotFoundError(f"{src} is missing; run: uv run python sources/fetch_sources.py")
    if not shutil.which("pdftotext"):
        raise RuntimeError("pdftotext (poppler) is required: brew install poppler / apt install poppler-utils")
    r = subprocess.run(["pdftotext", "-enc", "UTF-8", "-layout", str(src), "-"], capture_output=True, check=True)
    text = r.stdout.decode("utf-8", "replace")
    txt.write_text(text, encoding="utf-8")
    meta.write_text(json.dumps({"id": source_id, "sha256": row.get("sha256"), "pages": text.count("\f") + 1}))
    return text.split("\f")


def cite(source_id: str) -> str:
    """Human-readable short citation: title + edition."""
    r = rows()[source_id]
    return f"{r.get('title', source_id)} ({r.get('edition', '')})".replace(" ()", "")
