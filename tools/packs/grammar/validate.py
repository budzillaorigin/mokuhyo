"""Validate grammar source files (tools/packs/grammar/n*.json) and report Tatoeba coverage per point.

Checks: schema, unique ids, JLPT level, regex patterns compile and match the point's own examples, related ids
exist. Coverage: how many Tatoeba sentences each point's patterns match (points with fewer than 3 fall back to
their own labelled examples in the pack).

Run: uv run python packs/grammar/validate.py [n5.json ...]
"""

from __future__ import annotations

import bz2
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE.parents[1] / ".cache"
REQUIRED = {"id", "title", "jlpt", "order", "structure", "meaning", "nuance", "patterns", "examples"}
ID_RE = re.compile(r"^n[1-5]-[a-z0-9-]+$")


def load(paths: list[Path]) -> list[dict]:
    points = []
    for p in paths:
        data = json.loads(p.read_text(encoding="utf-8"))
        for point in data["points"]:
            point["_file"] = p.name
            points.append(point)
    return points


def tatoeba() -> list[str]:
    path = CACHE / "jpn_sentences.tsv.bz2"
    if not path.exists():
        return []
    with bz2.open(path, "rt", encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t")[2] for line in f if line.count("\t") >= 2]


def main() -> int:
    files = [HERE / a for a in sys.argv[1:]] or sorted(HERE.glob("n*.json"))
    points = load(files)
    errors: list[str] = []
    ids = {p.get("id") for p in points}
    seen: set[str] = set()
    for p in points:
        where = f"{p['_file']}:{p.get('id')}"
        missing = REQUIRED - p.keys()
        if missing:
            errors.append(f"{where}: missing {sorted(missing)}")
            continue
        if not ID_RE.match(p["id"]):
            errors.append(f"{where}: bad id")
        if p["id"] in seen:
            errors.append(f"{where}: duplicate id")
        seen.add(p["id"])
        if p["jlpt"] not in (1, 2, 3, 4, 5) or not p["id"].startswith(f"n{p['jlpt']}-"):
            errors.append(f"{where}: jlpt/id mismatch")
        try:
            regexes = [re.compile(x) for x in p["patterns"]]
        except re.error as e:
            errors.append(f"{where}: bad pattern {e}")
            continue
        if not regexes:
            errors.append(f"{where}: no patterns")
        if len(p["examples"]) < 3:
            errors.append(f"{where}: needs at least 3 examples")
        for ex in p["examples"]:
            if not any(r.search(ex["ja"]) for r in regexes):
                errors.append(f"{where}: pattern doesn't match its own example {ex['ja']!r}")
        for r in p.get("related", []):
            if r not in ids:
                errors.append(f"{where}: related id {r!r} not found")

    sentences = tatoeba()
    if sentences:
        low = 0
        for p in points:
            regexes = [re.compile(x) for x in p.get("patterns", [])]
            n = sum(1 for s in sentences if any(r.search(s) for r in regexes))
            p["_tatoeba"] = n
            if n < 3:
                low += 1
        print(f"Tatoeba coverage: {len(points) - low}/{len(points)} points have ≥ 3 matching sentences")
        for p in sorted(points, key=lambda p: p.get("_tatoeba", 0))[:15]:
            print(f"  {p.get('id')}: {p.get('_tatoeba', 0)}")
    for e in errors:
        print("ERROR", e)
    print(f"{len(points)} points, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
