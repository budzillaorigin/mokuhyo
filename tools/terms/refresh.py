#!/usr/bin/env python3
"""Doctrine refresh (BRIEF_PHASE8 N-09): compare a new edition of a US glossary with tools/terms/terms_en.json.

Emits a review file listing
  - changed:     seed terms whose definition in the new edition differs from `definition_en` (old and new text);
  - deprecated:  seed terms whose source was this glossary but which the new edition no longer defines;
  - candidates:  new-edition terms in the seed list's domains (by keyword) that are not seeds yet.
Nothing is changed automatically: the curator updates seed_terms.csv (docs/LEXICON_MAINTENANCE.md).

  uv run python terms/refresh.py --new us-dod-dict-2026-08 [--old-id us-dod-dict-2026-08] [--out refresh-review.json]
  uv run python terms/refresh.py --new-pages new.txt --old-id us-dod-dict-2026-08     # a text file with form-feed page breaks
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import doctrine as D
from build_seed_terms import clean

TERMS = HERE / "terms_en.json"
KEYWORDS = re.compile(r"unmanned|drone|air defense|airspace|base|security|force protection|electromagnetic|jamm|engage|hostile|weapon|"
                      r"identification|track|warning|evacuat|casualt|humanitarian|disaster|liaison|coordinat|counter", re.IGNORECASE)


def norm(s: str) -> str:
    return " ".join(re.sub(r"[^\w ]", " ", s.lower()).split())


def diff(terms: list[dict], old_id: str, new_entries: list[D.Entry]) -> dict:
    new = {D.key(e.term): e for e in new_entries}
    changed, deprecated = [], []
    seed_keys = set()
    for t in terms:
        k = D.key(re.sub(r"\s*\(.*?\)", "", t["term_en"]))
        seed_keys.add(k)
        if t.get("definition_cite", {}).get("source_id") != old_id:
            continue
        e = new.get(k)
        if e is None:
            deprecated.append({"id": t["id"], "term_en": t["term_en"], "definition_en": t["definition_en"]})
        elif norm(clean(e.definition)) != norm(t["definition_en"]) and not norm(clean(e.definition)).startswith(norm(t["definition_en"])):
            changed.append({"id": t["id"], "term_en": t["term_en"], "old": t["definition_en"], "new": clean(e.definition), "page": e.page_label})
    candidates = [{"term_en": e.term, "definition_en": clean(e.definition), "page": e.page_label}
                  for k, e in sorted(new.items()) if k not in seed_keys and KEYWORDS.search(e.term)]
    return {"format": "mokuhyo-refresh/1", "created": dt.datetime.now(dt.UTC).strftime("%Y-%m-%d"), "old": old_id,
            "changed": changed, "deprecated": deprecated, "candidates": candidates}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--new", help="SOURCES.json id of the new edition")
    ap.add_argument("--new-pages", help="text file of the new edition, pages separated by form feeds")
    ap.add_argument("--old-id", default="us-dod-dict-2026-08", help="the edition the seed definitions cite")
    ap.add_argument("--terms", default=str(TERMS))
    ap.add_argument("--out", default="refresh-review.json")
    a = ap.parse_args()
    terms = json.loads(Path(a.terms).read_text(encoding="utf-8"))["terms"]
    if a.new_pages:
        entries = D.dod_dictionary("fixture", Path(a.new_pages).read_text(encoding="utf-8").split("\f"))
    elif a.new:
        entries = D.dod_dictionary(a.new)
    else:
        ap.error("give --new or --new-pages")
    out = diff(terms, a.old_id, entries)
    Path(a.out).write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"refresh: {len(out['changed'])} changed, {len(out['deprecated'])} deprecated, {len(out['candidates'])} candidates → {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
