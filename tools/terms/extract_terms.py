#!/usr/bin/env python3
"""Source ingestion for the English terms (BRIEF_PHASE8 C-01): tools/terms/terms_en.json.

For every row of seed_terms.csv it records each place the public US sources (verbatim_ok = true; never us-limited)
define or use the term:
  - `definition` citations: glossary entries of the term in every parsed doctrine source and edition;
  - `mention` citations: up to three pages per source where the term or its acronym appears in the text.
A seed with no citation at all is `"status": "unsourced"`.

It also lists `candidates`: glossary terms from the counter-UAS / base-defense / air-defense sources that are not yet
seeds, with their verbatim definitions, for the next curation pass (docs/LEXICON_MAINTENANCE.md).

  uv run python terms/extract_terms.py
"""
from __future__ import annotations

import csv
import datetime as dt
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import doctrine as D
from build_seed_terms import SHORT, clean

import sources as S

SEEDS = HERE / "seed_terms.csv"
OUT = HERE / "terms_en.json"
MENTION_SOURCES = ["us-dod-dict-2026-08", "us-atp-3-01-81", "us-jp-3-10", "us-jp-3-01", "us-afdp-3-10", "us-af-da-point-defense",
                   "us-af-da-below-ca", "us-dod-csuas-strategy", "us-atp-1-02-1"]
CANDIDATE_SOURCES = ["us-atp-3-01-81", "us-jp-3-10", "us-jp-3-01"]


def base_term(term: str) -> str:
    return re.sub(r"\s*\(.*?\)", "", term).strip()


def main() -> int:
    with open(SEEDS, encoding="utf-8") as f:
        seeds = list(csv.DictReader(f))
    for sid in MENTION_SOURCES:
        S.require(sid, "parse")
    parsed = D.all_doctrine()
    glossary: dict[str, list[D.Entry]] = {}
    for entries in parsed.values():
        for e in entries:
            glossary.setdefault(D.key(e.term), []).append(e)
    flows = {sid: S.pages(sid, layout=False) for sid in MENTION_SOURCES}
    labels = {sid: S.pages(sid) for sid in MENTION_SOURCES}

    terms = []
    seed_keys = set()
    for r in seeds:
        name = base_term(r["term_en"])
        seed_keys.add(D.key(name))
        cites = []
        for e in glossary.get(D.key(name), []) + glossary.get(D.key(r["term_en"]), []):
            c = {"kind": "definition", "source_id": e.source_id, "source_doc": SHORT.get(e.source_id, e.source_id),
                 "page": e.page_label, "text": clean(e.definition)}
            if c not in cites:
                cites.append(c)
        variants = [name] + ([r["acronym"]] if r["acronym"] and len(r["acronym"]) >= 2 else [])
        pats = [re.compile(rf"(?<![\w-]){re.escape(v)}(?![\w-])", 0 if v.isupper() else re.IGNORECASE) for v in variants]
        for sid in MENTION_SOURCES:
            pages = [i for i, t in enumerate(flows[sid]) if any(p.search(t) for p in pats)][:3]
            for i in pages:
                cites.append({"kind": "mention", "source_id": sid, "source_doc": SHORT.get(sid, sid),
                              "page": D._label(labels[sid][i], i) if i < len(labels[sid]) else str(i + 1)})
        terms.append({
            "id": r["id"], "term_en": r["term_en"], "acronym": r["acronym"], "domain": r["domain"], "priority": int(r["priority"]),
            "definition_en": r["definition_en"], "definition_source": r["definition_source"],
            "definition_cite": {"source_id": r["source_id"], "source_doc": r["source_doc"], "page": r["source_page"]}
            if r["definition_source"] == "doctrine" else {"source_doc": "authored"},
            "citations": cites, "status": "sourced" if cites else "unsourced", "notes": r["notes"],
        })
    candidates = []
    for sid in CANDIDATE_SOURCES:
        for e in parsed[sid]:
            k = D.key(e.term)
            if k in seed_keys or any(c["key"] == k for c in candidates) or len(e.definition) < 20:
                continue
            candidates.append({"key": k, "term_en": e.term, "definition_en": clean(e.definition), "source_id": sid,
                               "source_doc": SHORT[sid], "page": e.page_label})
    for c in candidates:
        c.pop("key")
    out = {"format": "mokuhyo-terms-en/1", "built": dt.datetime.now(dt.UTC).strftime("%Y-%m-%d"),
           "sources": {sid: S.cite(sid) for sid in MENTION_SOURCES}, "terms": terms, "candidates": candidates}
    OUT.write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
    unsourced = [t["term_en"] for t in terms if t["status"] == "unsourced"]
    print(f"terms_en.json: {len(terms)} terms, {len(terms) - len(unsourced)} with ≥ 1 citation, {len(unsourced)} unsourced, "
          f"{len(candidates)} candidates")
    if unsourced:
        print("unsourced: " + "; ".join(unsourced))
    return 0


if __name__ == "__main__":
    sys.exit(main())
