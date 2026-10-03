#!/usr/bin/env python3
"""Term and source gate (BRIEF_PHASE8 C-00; CLAUDE.md rule 6). Run by tools/gates/gate_terms.sh (part of gate_core).

1. The local source files match SOURCES.json (fetch_sources.py --check); limited rows are never touched.
2. The overlap text cache is current (overlap_check.py index; cached by sha256).
3. tools/terms/seed_terms.csv and term_alignment.csv validate (validate_alignment.py, validate_seeds.py when present).
4. Every shipped text file built from the term and culture pipeline passes overlap_check.py: term_alignment.csv
   directly, every JSON file in TARGETS flattened to one item per string (Latin-script strings are checked both as
   English and as the file's language, so English notes are compared with the English-language restricted sources).

Exit 0 = pass. --no-sources skips steps 1, 2 and the overlap check (for a machine without the PDFs; prints why).
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
REPO = TOOLS.parent
PY = sys.executable

# Source-of-truth JSON that the pack builders copy into content/packs/ (so this is the shipped text). Every string of at least MIN_CHARS characters is checked.
TARGETS = [
    "tools/terms/terms_*.json",
    "tools/tracks/*.json",
    "tools/culture/*.json",
    "tools/pragmatics/*.json",
    "tools/personas/*.json",
    "tools/feeds/*.json",
    "tools/items/bank/*/*.json",
    "tools/opi/??.json",
    "tools/opi/??-??.json",
    "tools/opi/??-????.json",
    "tools/exemplars/*.json",
    "tools/storyline/*.json",
    "tools/interpret/*.json",
]
MIN_CHARS = 12
CJK = {"ja", "zh", "ko"}
SKIP_KEYS = {"id", "ids", "lang", "tags", "source", "sourceId", "source_id", "section", "url", "audio", "voice", "sha256",
             "kind", "status", "badge", "domain", "level", "band", "format", "seed_id", "term_source_id", "created", "version"}


def latin(s: str) -> bool:
    letters = [c for c in s if c.isalpha()]
    return bool(letters) and sum("LATIN" in unicodedata.name(c, "") for c in letters) / len(letters) > 0.8


def flatten(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    lang = (data.get("lang") or data.get("language") or "en") if isinstance(data, dict) else "en"
    out: list[dict] = []

    def walk(node, where: str, key: str | None) -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                if k not in SKIP_KEYS:
                    walk(v, f"{where}.{k}", k)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, f"{where}[{i}]", key)
        elif isinstance(node, str) and len(node) >= MIN_CHARS:
            if lang.split("-")[0] in CJK:  # mixed strings: CJK runs as the language, Latin runs as English
                parts = {lang: re.sub(r"[A-Za-z]+", " ", node), "en": re.sub(r"[^A-Za-z0-9' ]+", " ", node)}
                parts = {lg: t for lg, t in parts.items() if (len(t.split()) >= 8 if lg == "en" else len(t.strip()) >= MIN_CHARS)}
            elif latin(node):  # English notes are checked as English; Latin-script languages also as themselves
                parts = {"en": node, lang: node}
            else:
                parts = {lang: node}
            for lg, text in sorted(parts.items()):
                out.append({"id": f"{path.name}:{where}", "lang": lg, "text": text})

    walk(data, "$", None)
    return out


def run(cmd: list[str]) -> int:
    print("$ " + " ".join(cmd), flush=True)
    return subprocess.run(cmd, cwd=REPO, check=False).returncode


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-sources", action="store_true", help="skip the source-file and overlap checks")
    a = ap.parse_args()
    failed = []
    if not a.no_sources:
        if run([PY, str(TOOLS / "sources/fetch_sources.py"), "--check"]):
            failed.append("fetch_sources --check")
        if run([PY, str(HERE / "overlap_check.py"), "index"]):
            failed.append("overlap index")
    if (HERE / "validate_seeds.py").exists() and run([PY, str(HERE / "validate_seeds.py")]):
        failed.append("validate_seeds")
    if run([PY, str(HERE / "validate_alignment.py")]):
        failed.append("validate_alignment")
    if run([PY, str(HERE / "validate_feeds.py"), "--offline"]):
        failed.append("validate_feeds")
    if a.no_sources:
        print("gate_terms: overlap check SKIPPED (--no-sources): this machine has no tools/sources PDFs")
    else:
        files = [str(HERE / "term_alignment.csv"), *([str(HERE / "seed_terms.csv")] if (HERE / "seed_terms.csv").exists() else [])]
        items: list[dict] = []
        for pattern in TARGETS:
            for f in sorted(glob.glob(str(REPO / pattern))):
                items += flatten(Path(f))
        with tempfile.TemporaryDirectory() as tmp:
            if items:
                flat = Path(tmp) / "shipped.jsonl"
                flat.write_text("\n".join(json.dumps(i, ensure_ascii=False) for i in items), encoding="utf-8")
                files.append(str(flat))
                print(f"gate_terms: {len(items)} shipped strings from {len(TARGETS)} content globs")
            report = Path(tmp) / "report.json"
            if run([PY, str(HERE / "overlap_check.py"), "check", *files, "--fields", "definition,definition_en,example,text",
                    "--json", str(report)]):
                failed.append("overlap_check")
    print("gate_terms: " + ("FAIL " + ", ".join(failed) if failed else "PASS"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
