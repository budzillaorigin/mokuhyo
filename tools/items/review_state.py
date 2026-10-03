"""Keeps human review verdicts across content rebuilds (BRIEF_PHASE8 C-10): a builder that rewrites a source file calls
`carry(kind, lang, new_items, old_items)` so accepted items keep `verified`/`reviewedBy`/`reviewed` (and accepted
edits) and rejected ids — recorded in items/review-log.jsonl — are not re-added."""
from __future__ import annotations

import json
from pathlib import Path

LOG = Path(__file__).resolve().parent / "review-log.jsonl"
KEEP = ("verified", "reviewedBy", "reviewed")


def rejected(kind: str, lang: str) -> set[str]:
    if not LOG.exists():
        return set()
    out: set[str] = set()
    for line in LOG.read_text(encoding="utf-8").splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("kind") == kind and e.get("language") == lang:
            (out.add if e.get("verdict") == "reject" else out.discard)(e.get("id"))
    return out


def carry(kind: str, lang: str, new_items: list[dict], old_items: list[dict]) -> list[dict]:
    old = {x["id"]: x for x in old_items if x.get("verified")}
    drop = rejected(kind, lang)
    out = []
    for x in new_items:
        if x["id"] in drop:
            continue
        prev = old.get(x["id"])
        if prev:  # a reviewed item wins over a fresh draft
            x = dict(prev)
        out.append(x)
    return out
