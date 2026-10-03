#!/usr/bin/env python3
"""Builds tools/terms/seed_terms.csv (BRIEF_PHASE8 C-01a, owner-delegated in D-028).

For every catalog row (seed_catalog.py) the doctrinal sources are searched in the D-028 order — DoD Dictionary
(Aug 2026), ATP 3-01.81, JP 3-10 (2019 official), AFDP 3-10 (no glossary), JP 3-01 (2017 public), ATP 1-02.1
(brevity rows) — and the first hit's definition is copied verbatim (public domain) with source and printed page.
Rows no source defines keep the catalog's original one-sentence definition (`definition_source = original`).

  uv run python terms/build_seed_terms.py            # writes seed_terms.csv and prints coverage
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import doctrine as D
import verbatim
from seed_catalog import CATALOG, EXTENSION

import sources as S

OUT = HERE / "seed_terms.csv"
QUOTES = HERE / "seed_quotes.json"  # the model's sentence choices, kept so the build is reproducible offline
# Public US doctrine whose body text may supply a defining sentence (D-030), in citation preference order.
BODY = ["us-atp-3-01-81", "us-afdp-3-10", "us-af-da-point-defense", "us-af-da-below-ca", "us-dod-csuas-strategy", "us-jp-3-10",
        "us-jp-3-01", "us-atp-1-02-1"]
CUE = r"(?:is|are|refers to|means|consists? of|is defined as)"
APPROVED_BY = "owner (delegated 2026-10-03)"
COLUMNS = ["id", "term_en", "acronym", "domain", "priority", "definition_en", "definition_source", "source_id", "source_doc",
           "source_page", "notes", "approvedBy"]
# D-028 order, then the two earlier public DoD Dictionary editions (D-030: the Aug 2026 edition dropped many terms).
ORDER = ["us-dod-dict-2026-08", "us-atp-3-01-81", "us-jp-3-10", "us-jp-3-01", "us-dod-dict-2025-06", "us-dod-dict-2021-11"]
SHORT = {"us-dod-dict-2026-08": "DoD Dictionary (Aug 2026)", "us-atp-3-01-81": "ATP 3-01.81", "us-jp-3-10": "JP 3-10 (2019)",
         "us-jp-3-01": "JP 3-01 (2017)", "us-atp-1-02-1": "ATP 1-02.1 (2024)", "us-dod-dict-2025-06": "DoD Dictionary (Jun 2025)",
         "us-dod-dict-2021-11": "DoD Dictionary (Nov 2021)", "us-afdp-3-10": "AFDP 3-10", "us-af-da-point-defense":
         "AF Doctrine Advisory, Point Defense of Air Bases (2025)", "us-af-da-below-ca": "AF Doctrine Advisory, Control Below the Coordinating Altitude",
         "us-dod-csuas-strategy": "DoD Counter-sUAS Strategy (2021)"}
PREFIX = {"cuas": "cuas", "base-defense": "bd", "airspace": "air", "ew": "ew", "roe": "roe", "c2": "c2", "logistics": "log",
          "medical": "med", "hadr": "hadr", "brevity": "brev"}


def clean(definition: str) -> str:
    """Drops the trailing doctrinal reference and 'See also …' cross-references; keeps the definition words as printed."""
    d = definition.strip()
    d = re.sub(r"\s*\((?:DOD Dictionary\.? ?(?:Source|SOURCE): )?(?:JP|ATP|ADP|FM|AFDP|ADRP|DODD|DODI|DoDD|DoDI|AR|AFI)[^()]*\)?\s*$", "", d)
    d = re.sub(r"\s*\((?:Approved for[^()]*|DOD Dictionary[^()]*)\)\s*$", "", d)
    d = re.sub(r"\s*See also [^.]*\.\s*$", "", d)
    return d.strip()


def brevity_key(term: str) -> str:
    t = re.sub(r"\s*\[.*?\]", "", term)
    t = re.sub(r"\(.*?\)", "", t)
    return " ".join(t.upper().replace("’", "'").split())


def build() -> list[dict]:
    parsed = D.all_doctrine()
    index = {sid: {} for sid in ORDER}
    for sid in ORDER:
        for e in parsed[sid]:
            index[sid].setdefault(D.key(e.term), e)
    brev: dict[str, D.Entry] = {}
    for e in parsed["us-atp-1-02-1"]:
        brev.setdefault(brevity_key(e.term), e)

    def lookup(names: list[str], domain: str) -> D.Entry | None:
        for n in names:
            if domain == "brevity" and n.split("(")[0].isupper():
                hit = brev.get(brevity_key(n))
                if hit:
                    return hit
            for sid in ORDER:
                hit = index[sid].get(D.key(n))
                if hit:
                    return hit
        return None

    rows: list[dict] = []
    seen: set[str] = set()
    counters: dict[str, int] = {}

    def add(term: str, domain: str, priority: int, names: list[str], original: str | None, note: str) -> None:
        k = term.lower()
        if k in seen:
            return
        hit = lookup(names, domain)
        if hit is not None:  # keep only the sentences that are verbatim in the source (parser bleed at page/column breaks)
            kept = verbatim.longest_verbatim_prefix(re.sub(r"^\*+\s*", "", clean(hit.definition)), hit.source_id)
            hit = D.Entry(**{**hit.__dict__, "definition": kept}) if kept else None
        if hit is None and original is None:
            print(f"  unresolved (dropped): {term} [{domain}]")
            return
        seen.add(k)
        counters[domain] = counters.get(domain, 0) + 1
        row = {"id": f"{PREFIX[domain]}-{counters[domain]:03d}", "domain": domain, "priority": priority, "notes": note,
               "approvedBy": APPROVED_BY, "acronym": ""}
        if hit is not None:
            acr = hit.acronym if hit.acronym and len(hit.acronym) <= 10 else ""
            shown = term if "(" in term or not acr else f"{term} ({acr})"
            row.update(term_en=shown, acronym=acr, definition_en=clean(hit.definition), definition_source="doctrine",
                       source_id=hit.source_id, source_doc=SHORT[hit.source_id], source_page=hit.page_label)
            if hit.ref and hit.source_id != "us-atp-1-02-1":
                row["notes"] = (note + " " if note else "") + f"Doctrinal reference: {hit.ref.replace('DOD Dictionary. Source: ', '')}."
        else:
            row.update(term_en=term, definition_en=original, definition_source="original", source_id="", source_doc="authored",
                       source_page="")
        row["_names"] = names
        rows.append(row)

    for term, domain, priority, names, original, note in CATALOG:
        add(term, domain, priority, names, original, note)
    for domain, items in EXTENSION.items():
        for term, priority in items:
            base = re.sub(r"\s*\(brevity\)$", "", term)
            add(term, domain, priority, [base], None, "")
    return rows


def sentences(sid: str) -> list[tuple[str, str]]:
    """(sentence, printed page) from a source's reading-order text."""
    out = []
    flow = S.pages(sid, layout=False)
    labelled = S.pages(sid)
    for i, page in enumerate(flow):
        label = D._label(labelled[i], i) if i < len(labelled) else str(i + 1)
        text = re.sub(r"-\n(?=[a-z])", "-", page)
        text = " ".join(text.split())
        for sent in re.split(r"(?<=[.!?])\s+(?=[A-Z(])", text):
            if 40 <= len(sent) <= 420 and sent.endswith("."):
                out.append((sent, label))
    return out


def candidates(term: str, names: list[str], corpus: dict[str, list[tuple[str, str]]]) -> list[tuple[str, str, str]]:
    base = re.sub(r"\s*\(.*?\)", "", term).strip()
    acr = re.findall(r"\(([^)]+)\)", term)
    variants = {v for v in [base, *names, *[a for a in acr if len(a) <= 10]] if v}
    # The term must be the sentence's subject: "[A|An|The] <term> [(ACR)] is/are/means/refers to …"
    pats = [re.compile(rf"^(?:(?:A|An|The)\s+)?{re.escape(v)}s?(?:\s*\([^)]{{1,20}}\))?(?:\s+of\s+\S+)?\s+{CUE}\b", 0 if v.isupper() else re.IGNORECASE)
            for v in variants]
    found = []
    for sid in BODY:
        for sent, label in corpus[sid]:
            if any(p.search(sent) for p in pats):
                found.append((0, sid, sent, label))
    found.sort(key=lambda x: (BODY.index(x[1]), x[0]))
    return [(sid, sent, label) for _, sid, sent, label in found[:8]]


def choose(term: str, cands: list[tuple[str, str, str]]) -> int:
    """The primary model picks the defining sentence; the checker model must agree it defines the term."""
    sys.path.insert(0, str(HERE.parent))
    import llm
    client = llm.Client.from_args()
    listing = "\n".join(f"{i + 1}. {c[1]}" for i, c in enumerate(cands))
    schema = {"type": "object", "properties": {"choice": {"type": "integer"}}, "required": ["choice"]}
    out = client.chat_json([
        {"role": "system", "content": "You pick sentences from US military doctrine for a terminology list. Answer in JSON only."},
        {"role": "user", "content": f"Term: {term}\n\nCandidate sentences:\n{listing}\n\nWhich ONE sentence is a definition of "
            f"'{term}' in the sense a military learner needs: it must say what '{term}' IS, in general, not describe one example, "
            'a program, a concern, or a different sense of the word. Reply {"choice": N}, or {"choice": 0} if none is a definition.'},
    ], schema, temperature=0.0, max_tokens=50)
    n = int(out.get("choice", 0))
    if not 1 <= n <= len(cands):
        return 0
    check = client.chat_json([
        {"role": "system", "content": "You verify terminology definitions. Answer in JSON only."},
        {"role": "user", "content": f"Term: {term}\nSentence: {cands[n - 1][1]}\n\nIs this sentence a general definition of the "
            f"term '{term}' as used in counter-UAS, base defense, airspace control or military radio procedure (not an example, "
            'a single system, a program, or another meaning)? Reply {"definition": true} or {"definition": false}.'},
    ], {"type": "object", "properties": {"definition": {"type": "boolean"}}, "required": ["definition"]}, temperature=0.0, max_tokens=400,
        model=client.fallback)
    return n if check.get("definition") is True else 0


def requote(rows: list[dict], refresh: bool) -> None:
    cache = json.loads(QUOTES.read_text(encoding="utf-8")) if QUOTES.exists() else {}
    corpus = None
    for r in rows:
        if r["definition_source"] != "original":
            continue
        term = r["term_en"]
        if term not in cache or refresh:
            if corpus is None:
                corpus = {sid: sentences(sid) for sid in BODY}
            cands = candidates(term, r.get("_names", []), corpus)
            pick = choose(term, cands) if cands else 0
            cache[term] = {"source_id": cands[pick - 1][0], "sentence": cands[pick - 1][1], "page": cands[pick - 1][2]} if pick else None
            print(f"  quote {'+' if pick else '-'} {term}" + (f": {cands[pick - 1][1][:90]}" if pick else ""))
        q = cache.get(term)
        if q:
            r.update(original_definition=r["definition_en"], definition_en=q["sentence"], definition_source="doctrine",
                     source_id=q["source_id"], source_doc=SHORT[q["source_id"]], source_page=q["page"])
            r["notes"] = (r["notes"] + " " if r["notes"] else "") + "Defining sentence quoted from the body text."
    QUOTES.write_text(json.dumps(cache, indent=1, ensure_ascii=False, sort_keys=True), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--requote", action="store_true", help="ask the model again for every body-text quote")
    a = ap.parse_args()
    for sid in [*ORDER, "us-atp-1-02-1", *BODY]:
        S.require(sid, "parse")  # refuses anything not machine_extract_ok (and every limited row)
    rows = build()
    requote(rows, a.requote)
    for r in rows:
        r.pop("_names", None)
        r.pop("original_definition", None)
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, COLUMNS)
        w.writeheader()
        w.writerows(rows)
    doctrinal = sum(r["definition_source"] == "doctrine" for r in rows)
    by_src: dict[str, int] = {}
    for r in rows:
        by_src[r["source_doc"]] = by_src.get(r["source_doc"], 0) + 1
    print(f"seed_terms.csv: {len(rows)} rows, {doctrinal} doctrinal ({100 * doctrinal / len(rows):.1f}%), "
          f"{sum(r['priority'] == 1 for r in rows)} priority 1 · " + ", ".join(f"{k} {v}" for k, v in sorted(by_src.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
