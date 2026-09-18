"""Build content/packs/grammar.sqlite from tools/packs/grammar/n*.json (BRIEF §5.5).

Explanations come from the JSON sources (LLM-drafted, marked source="llm" until reviewed with
tools/items/review.py). Example sentences are preferably real, human-written Tatoeba sentences (CC BY 2.0 FR)
found with each point's patterns; the point's own examples (source="llm") fill in when Tatoeba has too few.
Each example records the span of the construction, which the apps blank out for cloze reviews.

Run: uv run python packs/build_grammar.py [--level N5]
"""

from __future__ import annotations

import argparse
import bz2
import json
import re
from collections import defaultdict
from pathlib import Path

from common import CACHE, PACKS, REPO, dumps, finish_pack, log, nfc, open_pack, reset_tables, set_meta

GRAMMAR_PACK = PACKS / "grammar.sqlite"
GRAMMAR_SQ = REPO / "shared/src/commonMain/sqldelightGrammar/app/tsumugi/grammar/db/grammar.sq"
SOURCES = Path(__file__).resolve().parent / "grammar"
GRAMMAR_PACK_VERSION = "1"

MAX_TATOEBA_EXAMPLES = 8
MAX_EXAMPLES = 12
MIN_LEN, MAX_LEN = 6, 32


def load_tatoeba() -> list[tuple[int, str, str]]:
    """(id, japanese, english) for every Japanese Tatoeba sentence with an English translation."""
    def rows(name):
        with bz2.open(CACHE / name, "rt", encoding="utf-8") as f:
            for line in f:
                yield line.rstrip("\n").split("\t")

    jpn = {int(r[0]): nfc(r[2]) for r in rows("jpn_sentences.tsv.bz2") if len(r) >= 3}
    links: dict[int, list[int]] = defaultdict(list)
    for r in rows("jpn-eng_links.tsv.bz2"):
        if len(r) >= 2:
            links[int(r[0])].append(int(r[1]))
    wanted = {e for ids in links.values() for e in ids}
    eng = {int(r[0]): r[2] for r in rows("eng_sentences.tsv.bz2") if len(r) >= 3 and int(r[0]) in wanted}
    out = []
    for sid, ja in jpn.items():
        en = next((eng[e] for e in links.get(sid, []) if e in eng), None)
        if en and MIN_LEN <= len(ja) <= MAX_LEN:
            out.append((sid, ja, en))
    out.sort(key=lambda t: (len(t[1]), t[0]))
    return out


def utf16_offsets(text: str, start: int, end: int) -> tuple[int, int]:
    """Python str offsets → UTF-16 offsets (what Kotlin String indices use)."""
    def u16(s: str) -> int:
        return len(s.encode("utf-16-le")) // 2
    return u16(text[:start]), u16(text[:end])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--level", help="only this level, e.g. N5 (default: every source file)")
    args = parser.parse_args()
    files = sorted(SOURCES.glob("n*.json"))
    if args.level:
        files = [f for f in files if f.stem == args.level.lower()]
    points = [p for f in files for p in json.loads(f.read_text(encoding="utf-8"))["points"]]
    if not points:
        raise SystemExit("no grammar sources found")

    log(f"matching {len(points)} grammar points against Tatoeba…")
    sentences = load_tatoeba()
    db = open_pack(GRAMMAR_PACK, GRAMMAR_SQ)
    reset_tables(db, {"grammar_point", "grammar_example", "grammar_pattern"}, GRAMMAR_SQ)

    used: set[int] = set()  # prefer a different sentence for each point
    tatoeba_total = 0
    for p in points:
        regexes = [re.compile(x) for x in p["patterns"]]
        db.execute(
            "INSERT INTO grammar_point VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (
                p["id"], p["jlpt"], p["order"], nfc(p["title"]), p["structure"], p["meaning"], p["nuance"],
                dumps(p.get("mistakes", [])), dumps(p.get("related", [])), dumps(p.get("textbooks", {})),
                p.get("source", "llm"),
            ),
        )
        db.executemany(
            "INSERT INTO grammar_pattern VALUES (?,?,?)", ((p["id"], i, x) for i, x in enumerate(p["patterns"]))
        )
        examples = []
        for sid, ja, en in sentences:
            if len(examples) >= MAX_TATOEBA_EXAMPLES:
                break
            if sid in used:
                continue
            matches = [m for r in regexes for m in r.finditer(ja) if m.end() > m.start()]
            if len(matches) != 1:  # ambiguous blanks make bad cloze items
                continue
            m = matches[0]
            examples.append((ja, en, *utf16_offsets(ja, m.start(), m.end()), "tatoeba", sid))
            used.add(sid)
        tatoeba_total += len(examples)
        for ex in p["examples"]:
            if len(examples) >= MAX_EXAMPLES:
                break
            ja = nfc(ex["ja"])
            m = next((m for r in regexes for m in r.finditer(ja) if m.end() > m.start()), None)
            if m:
                examples.append((ja, ex["en"], *utf16_offsets(ja, m.start(), m.end()), "llm", None))
        db.executemany(
            "INSERT INTO grammar_example VALUES (?,?,?,?,?,?,?,?)",
            ((p["id"], i, *ex) for i, ex in enumerate(examples)),
        )

    set_meta(db, pack="grammar", pack_version=GRAMMAR_PACK_VERSION, points=str(len(points)))
    finish_pack(db)
    log(f"grammar: {len(points)} points, {tatoeba_total} Tatoeba examples")


if __name__ == "__main__":
    main()
