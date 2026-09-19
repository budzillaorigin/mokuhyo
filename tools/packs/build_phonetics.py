"""Functional components and sound series (BRIEF_V2 §6.15, DECISIONS D-281…D-283): extra tables in dictionary.sqlite.

For each kanji, its direct parts (the depth-1 groups of its KanjiVG component tree) are marked semantic, phonetic or
form-only, and kanji sharing a phonetic part are grouped into sound series (声符 families: 青 → 清 晴 精 請 情 静).
The derivation is our own heuristic over two open datasets, not anyone's analysis:
- KanjiVG (Ulrich Apel, CC BY-SA 3.0): the component tree, and its `kvg:radical` / `kvg:phon` marks, which are
  recorded as evidence and used to break ties, never as the answer on their own.
- KANJIDIC2 (EDRDG, CC BY-SA 4.0) on'yomi, read from the dictionary pack's kanji table.

Heuristic: a part that is itself a kanji is phonetic when the kanji shares an on'yomi with it (same, or related:
equal after folding voicing and a final long vowel). A part that is not a kanji on its own is phonetic for the kanji
that share one on'yomi with at least two other kanji built on it ("shared"). With a phonetic part found, the other
direct parts are semantic; otherwise the part KanjiVG marks as the radical is semantic and the rest are form-only.

Everything is labeled "derived" until reviewed. The reviewable unit is a sound series, kept in
tools/packs/phonetics/series.json (review kind `phonetic_series`, items/review.py). A rebuild re-derives unreviewed
series and keeps reviewed ones as reviewed (their edited members win); a rejected or excluded series is left out of
the pack. The schema is shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/kanjiParts.sq.

    uv run python packs/build_phonetics.py                     # derive, merge into series.json, write the tables
    uv run python packs/build_phonetics.py status              # counts and agreement with KanjiVG's kvg:phon
    uv run python packs/build_phonetics.py --dictionary PATH --kanjivg ZIP   # other inputs (defaults: the pack, sources.lock)
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from common import (
    DICTIONARY_PACK,
    REPO,
    dumps,
    finish_pack,
    log,
    nfc,
    open_pack,
    reset_tables,
    set_meta,
    source,
)

KANJI_PARTS_SQ = REPO / "shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/kanjiParts.sq"
DATA = Path(__file__).resolve().parent / "phonetics"
SERIES_FILE = DATA / "series.json"
TABLES = {"kanji_element", "kanji_component_role", "phonetic_series"}

KVG = "{http://kanjivg.tagaini.net}"
SVG_G = "{http://www.w3.org/2000/svg}g"
FILE_RE = re.compile(r"(?:^|/)([0-9a-f]{5})\.svg$")
MIN_SHARED = 3  # "shared": a reading common to at least this many kanji on a part that isn't a kanji itself
MIN_SERIES = 2  # members besides the head
SERIES_NOTE = (
    "Sound series (声符 families) derived by tools/packs/build_phonetics.py from KanjiVG component trees and KANJIDIC2 "
    "on'yomi (BRIEF_V2 §6.15, DECISIONS D-281). source \"derived\" shows a \"derived\" label in the app until a human "
    "reviews the series with items/review.py (kind phonetic_series). members: the kanji of the family as one string, "
    "head first; readings: the shared on'yomi, space-separated. Edit either when reviewing; a rebuild keeps reviewed "
    "series as they are. CC BY-SA 4.0 (derived from KanjiVG CC BY-SA 3.0 and KANJIDIC2 CC BY-SA 4.0)."
)

VOICED = str.maketrans(
    "ガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポヴ",
    "カキクケコサシスセソタチツテトハヒフヘホハヒフヘホウ",
)


# --- Readings -----------------------------------------------------------------------------------------------------

def on_readings(raw: str) -> list[str]:
    """KANJIDIC2 on'yomi as stored in the pack (JSON array), without the okurigana/affix marks."""
    out = []
    for r in json.loads(raw or "[]"):
        r = r.replace("-", "").split(".")[0]
        if r and r not in out:
            out.append(r)
    return out


def fold(reading: str) -> str:
    """Folds voicing and a final long vowel: ジョウ → ショ, バン → ハン, コウ → コ. Used only to call readings related."""
    r = reading.translate(VOICED)
    return r[:-1] if len(r) > 1 and r[-1] in "ウイ" else r


def match(readings: list[str], of: list[str]) -> tuple[str, str] | None:
    """("same", reading) when a reading is shared, ("related", reading) when one is equal after [fold]."""
    for r in readings:
        if r in of:
            return "same", r
    folded = {fold(o): o for o in of}
    for r in readings:
        if fold(r) in folded:
            return "related", r
    return None


# --- KanjiVG --------------------------------------------------------------------------------------------------------

@dataclass
class Part:
    element: str
    position: str
    radical: bool
    phon: bool


@dataclass
class Tree:
    kanji: str
    parts: list[Part] = field(default_factory=list)
    elements: dict[str, tuple[int, str]] = field(default_factory=dict)  # element -> (depth, position)


def _named_children(g: ET.Element) -> list[ET.Element]:
    """The next named groups under [g]: direct children with kvg:element, looking through unnamed wrappers."""
    out = []
    for child in g.findall(SVG_G):
        if child.get(f"{KVG}element"):
            out.append(child)
        else:
            out.extend(_named_children(child))
    return out


def parse_tree(char: str, svg: bytes) -> Tree | None:
    root = ET.fromstring(svg)
    top = next((g for g in root.iter(SVG_G) if g.get(f"{KVG}element") == char), None)
    if top is None:
        return None
    tree = Tree(char)

    def walk(g: ET.Element, depth: int, position: str) -> None:
        for child in _named_children(g):
            el = nfc(child.get(f"{KVG}element"))
            pos = position or child.get(f"{KVG}position", "")
            if el != char and (el not in tree.elements or tree.elements[el][0] > depth):
                tree.elements[el] = (depth, pos)
            walk(child, depth + 1, pos)

    walk(top, 1, "")
    seen = set()
    for child in _named_children(top):
        el = nfc(child.get(f"{KVG}element"))
        if el == char or el in seen:  # kvg:part splits one element into several groups (衣 in 裏)
            continue
        seen.add(el)
        tree.parts.append(Part(
            el, child.get(f"{KVG}position", ""), bool(child.get(f"{KVG}radical")), child.get(f"{KVG}phon") is not None,
        ))
    return tree


def load_trees(zip_path: Path) -> dict[str, Tree]:
    trees = {}
    with zipfile.ZipFile(zip_path) as zf:
        for name in zf.namelist():
            m = FILE_RE.search(name)
            if not m:
                continue  # variant files (e.g. 04e00-Kaisho.svg) are skipped, as in build_kanjivg.py
            char = chr(int(m.group(1), 16))
            tree = parse_tree(char, zf.read(name))
            if tree:
                trees[char] = tree
    return trees


# --- Derivation -----------------------------------------------------------------------------------------------------

@dataclass
class KanjiRow:
    literal: str
    on: list[str]
    freq: int | None
    grade: int | None
    jlpt: int | None
    strokes: int

    @property
    def studied(self) -> bool:
        """Kanji a learner meets (jōyō/jinmeiyō grades, the frequency list, or a JLPT level): series members."""
        return self.grade is not None or self.freq is not None or self.jlpt is not None

    @property
    def order(self) -> tuple:
        return (self.freq or 99999, self.strokes, self.literal)


def load_kanji(db: sqlite3.Connection) -> dict[str, KanjiRow]:
    return {
        lit: KanjiRow(lit, on_readings(on), freq, grade, jlpt, strokes)
        for lit, on, freq, grade, jlpt, strokes in db.execute(
            "SELECT literal, onyomi, freq, grade, jlpt_new, stroke_count FROM kanji"
        )
    }


@dataclass
class Phonetic:
    component: str
    match: str
    reading: str
    kvg_phon: bool


def choose_phonetics(trees: dict[str, Tree], kanji: dict[str, KanjiRow]) -> dict[str, Phonetic]:
    """The phonetic part of each studied kanji, if the heuristic finds one."""
    chosen: dict[str, Phonetic] = {}
    by_part: dict[str, list[str]] = defaultdict(list)
    for lit, tree in trees.items():
        row = kanji.get(lit)
        if row is None or not row.studied or not row.on:
            continue
        candidates = []
        for i, p in enumerate(tree.parts):
            by_part[p.element].append(lit)
            head = kanji.get(p.element)
            if head is None or not head.on or len(tree.parts) < 2 or (p.radical and not p.phon):
                continue  # the part KanjiVG marks as the radical is the semantic side unless it also marks it phonetic
            m = match(row.on, head.on)
            if m:
                # Prefer a shared reading, then KanjiVG's own mark, then the part that isn't the radical, then the
                # later (right/bottom) part, where phonetics usually sit.
                rank = (m[0] == "same", p.phon, not p.radical, i)
                candidates.append((rank, Phonetic(p.element, m[0], m[1], p.phon)))
        if candidates:
            chosen[lit] = max(candidates, key=lambda c: c[0])[1]
    # Parts that aren't kanji on their own: a reading shared by MIN_SHARED+ kanji built on the part.
    # Only the most shared reading counts, it must cover a good part of the family, and parts that are mostly the
    # radical (氵, 亻, 艹…) never qualify.
    for part, members in by_part.items():
        if part in kanji and kanji[part].on:
            continue
        uses = [next(p for p in trees[m].parts if p.element == part) for m in members]
        if sum(p.radical and not p.phon for p in uses) * 2 >= len(uses):
            continue
        free = [m for m in members if m not in chosen and len(trees[m].parts) >= 2]
        counts = Counter(r for m in free for r in {fold(x) for x in kanji[m].on})
        if not counts:
            continue
        folded, n = counts.most_common(1)[0]
        if n < MIN_SHARED or n * 5 < len(free) * 2:
            continue
        for m in free:
            hit = next((r for r in kanji[m].on if fold(r) == folded), None)
            if hit:
                kvg = next(p.phon for p in trees[m].parts if p.element == part)
                chosen[m] = Phonetic(part, "shared", hit, kvg)
    return chosen


def derive_series(chosen: dict[str, Phonetic], kanji: dict[str, KanjiRow]) -> dict[str, dict]:
    """Sound series keyed by phonetic part: the head (when a kanji) and every kanji whose phonetic it is."""
    families: dict[str, list[str]] = defaultdict(list)
    for lit, ph in chosen.items():
        families[ph.component].append(lit)
    series = {}
    for part, members in families.items():
        if len(members) < MIN_SERIES:
            continue
        members.sort(key=lambda k: kanji[k].order)
        head = [part] if part in kanji and kanji[part].on else []
        readings = Counter(chosen[m].reading for m in members)
        series[part] = {
            "id": part,
            "members": "".join(head + members),
            "readings": " ".join(r for r, _ in readings.most_common()),
            "source": "derived",
        }
    return series


# --- series.json ----------------------------------------------------------------------------------------------------

def load_series_file() -> dict:
    if SERIES_FILE.exists():
        return json.loads(SERIES_FILE.read_text(encoding="utf-8"))
    return {"note": SERIES_NOTE, "series": []}


def save_series_file(data: dict) -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    SERIES_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def reviewed(entry: dict) -> bool:
    return entry.get("source") == "verified" or "reviewed" in entry or "rejected" in entry or bool(entry.get("exclude"))


def merge_series(data: dict, derived: dict[str, dict]) -> tuple[int, int, int]:
    """Re-derived series replace unreviewed ones; reviewed, rejected and excluded series stay exactly as they are."""
    old = {e["id"]: e for e in data.get("series", [])}
    out, kept, added, dropped = [], 0, 0, 0
    for sid, entry in derived.items():
        prev = old.pop(sid, None)
        if prev is not None and reviewed(prev):
            out.append(prev)
            kept += 1
        else:
            out.append(entry)
            added += prev is None
    for prev in old.values():  # no longer derived: keep only what a human looked at
        if reviewed(prev):
            out.append(prev)
            kept += 1
        else:
            dropped += 1
    out.sort(key=lambda e: (-len(e["members"]), e["id"]))
    data["note"] = data.get("note") or SERIES_NOTE
    data["series"] = out
    return kept, added, dropped


def pack_series(data: dict) -> list[dict]:
    """The series that go into the pack: not rejected or excluded, members/readings as reviewed."""
    return [e for e in data["series"] if not e.get("exclude") and "rejected" not in e]


# --- Build ----------------------------------------------------------------------------------------------------------

def build_rows(trees: dict[str, Tree], kanji: dict[str, KanjiRow], chosen: dict[str, Phonetic], series: list[dict]):
    """Rows for kanji_element, kanji_component_role and phonetic_series."""
    elements = [
        (k, el, depth, pos) for k, t in sorted(trees.items()) if k in kanji for el, (depth, pos) in sorted(t.elements.items())
    ]
    member_of: dict[str, dict] = {}
    series_rows = []
    for e in series:
        head, members = e["id"], list(e["members"])
        readings = e["readings"].split()
        detail = []
        for k in members:
            on = kanji[k].on if k in kanji else []
            if k == head:
                how = "self"
            elif k in chosen and chosen[k].component == head:
                how = chosen[k].match
            else:  # added by a reviewer
                how = (match(on, readings) or ("related", ""))[0]
            detail.append({"k": k, "on": on, "match": how})
            if k != head:
                member_of[k] = e
        series_rows.append((head, dumps(readings), dumps(detail), len(members), e["source"]))

    roles = []
    for k, t in sorted(trees.items()):
        if k not in kanji or not t.parts:
            continue
        fam = member_of.get(k)
        phonetic = fam["id"] if fam else None
        ph = chosen.get(k)
        src = fam["source"] if fam else "derived"
        for i, p in enumerate(t.parts):
            if p.element == phonetic:
                evidence = {
                    "match": ph.match if ph and ph.component == phonetic else "related",
                    "reading": ph.reading if ph and ph.component == phonetic else "",
                    "kvgPhon": p.phon, "kvgRadical": p.radical,
                }
                roles.append((k, p.element, i, "PHONETIC", dumps(evidence), phonetic, src))
            elif phonetic is not None or p.radical:
                roles.append((k, p.element, i, "SEMANTIC", dumps({"kvgPhon": p.phon, "kvgRadical": p.radical}), "", src))
            else:
                roles.append((k, p.element, i, "FORM", "{}", "", src))
    return elements, roles, series_rows


def derive(dictionary: Path, kanjivg: Path):
    db = sqlite3.connect(f"file:{dictionary}?mode=ro", uri=True)
    kanji = load_kanji(db)
    db.close()
    trees = load_trees(kanjivg)
    chosen = choose_phonetics(trees, kanji)
    return kanji, trees, chosen, derive_series(chosen, kanji)


def agreement(trees: dict[str, Tree], kanji: dict[str, KanjiRow], chosen: dict[str, Phonetic]) -> dict[str, int]:
    """How the heuristic compares with KanjiVG's own kvg:phon marks on studied kanji (reported, not enforced)."""
    marked = {k: next(p.element for p in t.parts if p.phon) for k, t in trees.items()
              if k in kanji and kanji[k].studied and any(p.phon for p in t.parts)}
    both = sum(1 for k, el in marked.items() if k in chosen and chosen[k].component == el)
    return {"kvg_marked": len(marked), "derived": len(chosen), "agree": both,
            "derived_unmarked": sum(1 for k in chosen if k not in marked)}


def build(args) -> None:
    if not args.dictionary.exists():
        raise SystemExit("dictionary pack not built yet: run build_dictionary.py first")
    kanji, trees, chosen, derived = derive(args.dictionary, args.kanjivg)
    data = load_series_file()
    kept, added, dropped = merge_series(data, derived)
    save_series_file(data)
    series = pack_series(data)
    elements, roles, series_rows = build_rows(trees, kanji, chosen, series)

    db = open_pack(args.dictionary)
    reset_tables(db, TABLES, KANJI_PARTS_SQ)
    db.executemany("INSERT INTO kanji_element VALUES (?,?,?,?)", elements)
    db.executemany("INSERT INTO kanji_component_role VALUES (?,?,?,?,?,?,?)", roles)
    db.executemany("INSERT INTO phonetic_series VALUES (?,?,?,?,?)", series_rows)
    by_role = Counter(r[3] for r in roles)
    agree = agreement(trees, kanji, chosen)
    set_meta(
        db, phonetic_series=str(len(series_rows)), phonetic_series_verified=str(sum(1 for s in series if s["source"] == "verified")),
        component_roles=dumps(dict(by_role)), kanji_elements=str(len(elements)),
    )
    finish_pack(db)
    log(
        f"phonetics: {len(elements)} kanji elements; roles {dict(by_role)}; {len(series_rows)} sound series "
        f"({sum(len(s['members']) for s in series)} kanji; {kept} reviewed kept, {added} new, {dropped} dropped); "
        f"kvg:phon agreement {agree}"
    )


def cmd_status(args) -> None:
    data = load_series_file()
    s = data.get("series", [])
    log(f"series.json: {len(s)} series, {sum(1 for e in s if e.get('source') == 'verified')} verified, "
        f"{sum(1 for e in s if 'rejected' in e or e.get('exclude'))} rejected/excluded")
    if args.dictionary.exists():
        kanji, trees, chosen, _ = derive(args.dictionary, args.kanjivg)
        log(f"kvg:phon agreement: {agreement(trees, kanji, chosen)}")


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", nargs="?", choices=["build", "status"], default="build")
    ap.add_argument("--dictionary", type=Path, default=DICTIONARY_PACK)
    ap.add_argument("--kanjivg", type=Path, default=None, help="KanjiVG release zip (default: sources.lock kanjivg)")
    args = ap.parse_args(argv)
    if args.kanjivg is None:
        args.kanjivg = source("kanjivg")
    (cmd_status if args.cmd == "status" else build)(args)


if __name__ == "__main__":
    main()
