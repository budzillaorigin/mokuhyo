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

from common import (
    CACHE,
    DICTIONARY_PACK,
    PACKS,
    REPO,
    dumps,
    finish_pack,
    log,
    nfc,
    open_pack,
    reset_tables,
    set_meta,
)

GRAMMAR_PACK = PACKS / "grammar.sqlite"
GRAMMAR_SQ = REPO / "shared/src/commonMain/sqldelightGrammar/app/tsumugi/grammar/db/grammar.sq"
SOURCES = Path(__file__).resolve().parent / "grammar"
GRAMMAR_PACK_VERSION = "1"

MAX_TATOEBA_EXAMPLES = 8
MAX_EXAMPLES = 12
MIN_LEN, MAX_LEN = 6, 32
IDEAL_LEN = 16
MAX_WORD_LEN = 8


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
    # Mid-length sentences first: long enough for context, short enough for a quick review.
    out.sort(key=lambda t: (abs(len(t[1]) - IDEAL_LEN), t[0]))
    return out


def dictionary_words() -> set[str]:
    """Every JMdict written form and reading up to MAX_WORD_LEN characters."""
    db = open_pack(DICTIONARY_PACK)
    words = {t for (t,) in db.execute("SELECT text FROM entry_kanji WHERE length(text) <= ?", (MAX_WORD_LEN,))}
    words |= {t for (t,) in db.execute("SELECT text FROM entry_kana WHERE length(text) <= ?", (MAX_WORD_LEN,))}
    db.close()
    return words


def inside_larger_word(text: str, start: int, end: int, words: set[str]) -> bool:
    """True when a dictionary word strictly contains [start, end): e.g. たい inside 冷たい or みたい."""
    for a in range(max(0, end - MAX_WORD_LEN), start + 1):
        for b in range(end, min(len(text), a + MAX_WORD_LEN) + 1):
            if (a < start or b > end) and text[a:b] in words:
                return True
    return False


def normalize_title(title: str) -> str:
    """Mirror of app.tsumugi.grammar.GrammarTitles.normalize: drop 〜/~, spaces, brackets' content, fold kana."""
    t = re.sub(r"[（(][^）)]*[）)]", "", nfc(title))
    t = re.sub(r"[〜～~\s・/／、,]", "", t)
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in t).lower()


def utf16_offsets(text: str, start: int, end: int) -> tuple[int, int]:
    """Python str offsets → UTF-16 offsets (what Kotlin String indices use)."""
    def u16(s: str) -> int:
        return len(s.encode("utf-16-le")) // 2
    return u16(text[:start]), u16(text[:end])


def source_order(path: Path) -> tuple[int, int, str]:
    """Matching order of the source files. Each Tatoeba sentence goes to the first point that claims it, so N3–N5
    keep their original order (n3, n4, n5) and the harder levels come after (n2, then n1): adding N2/N1 never
    takes example sentences away from easier points."""
    level = int(path.stem[1:]) if path.stem[1:].isdigit() else 0
    return (0, 0, path.stem) if level >= 3 else (1, -level, path.stem)


def load_textbooks(ids: set[str]) -> tuple[list[dict], dict[str, dict[str, str]]]:
    """Textbook chapter mappings (textbooks.json; chapter numbers only, DECISIONS D-104). Returns the books that map
    at least one point (id/title/unit, for pack_meta "textbooks") and point id -> {book id: chapter}."""
    f = SOURCES / "textbooks.json"
    if not f.exists():
        return [], {}
    chapters: dict[str, dict[str, str]] = {}
    books = []
    for book in json.loads(f.read_text(encoding="utf-8"))["books"]:
        mapped = {pid: ch for pid, ch in book["chapters"].items() if pid in ids}
        unknown = set(book["chapters"]) - ids
        if unknown:
            log(f"textbooks: {book['id']} maps unknown points {sorted(unknown)[:5]}")
        for pid, ch in mapped.items():
            chapters.setdefault(pid, {})[book["id"]] = ch
        if mapped:
            books.append({"id": book["id"], "title": book["title"], "unit": book.get("unit", "Chapter")})
        log(f"textbooks: {book['id']} maps {len(mapped)} points")
    return books, chapters


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--level", help="only this level, e.g. N5 (default: every source file)")
    args = parser.parse_args()
    files = sorted(SOURCES.glob("n*.json"), key=source_order)
    if args.level:
        files = [f for f in files if f.stem == args.level.lower()]
    points = [p for f in files for p in json.loads(f.read_text(encoding="utf-8"))["points"]]
    if not points:
        raise SystemExit("no grammar sources found")

    log(f"matching {len(points)} grammar points against Tatoeba…")
    sentences = load_tatoeba()
    words = dictionary_words()
    db = open_pack(GRAMMAR_PACK, GRAMMAR_SQ)
    reset_tables(db, {"grammar_point", "grammar_example", "grammar_pattern", "grammar_alias", "grammar_point_ja"}, GRAMMAR_SQ)
    ids = {p["id"] for p in points}
    aliases_file = SOURCES / "aliases.json"
    aliases = json.loads(aliases_file.read_text(encoding="utf-8")) if aliases_file.exists() else {}
    books, chapters = load_textbooks(ids)
    db.executemany(
        "INSERT OR IGNORE INTO grammar_alias VALUES (?,?)",
        ((normalize_title(alias), pid) for pid, names in aliases.items() if pid in ids for alias in names),
    )

    used: set[int] = set()  # prefer a different sentence for each point
    tatoeba_total = 0
    ja_total = 0
    for p in points:
        regexes = [re.compile(x) for x in p["patterns"]]
        db.execute(
            "INSERT INTO grammar_point VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (
                p["id"], p["jlpt"], p["order"], nfc(p["title"]), p["structure"], p["meaning"], p["nuance"],
                dumps(p.get("mistakes", [])), dumps(p.get("related", [])), dumps({**p.get("textbooks", {}), **chapters.get(p["id"], {})}),
                p.get("source", "llm"),  # "verified" once reviewed with items/review.py
            ),
        )
        db.executemany(
            "INSERT INTO grammar_pattern VALUES (?,?,?)", ((p["id"], i, x) for i, x in enumerate(p["patterns"]))
        )
        if p.get("meaning_ja") and p.get("nuance_ja"):  # monolingual mode (D-233); own provenance, see grammar_ja.py
            db.execute(
                "INSERT INTO grammar_point_ja VALUES (?,?,?,?)",
                (p["id"], nfc(p["meaning_ja"]), nfc(p["nuance_ja"]), p.get("ja_source", "llm")),
            )
            ja_total += 1
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
            if inside_larger_word(ja, m.start(), m.end(), words):
                continue
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

    set_meta(
        db, pack="grammar", pack_version=GRAMMAR_PACK_VERSION, points=str(len(points)), textbooks=dumps(books),
        points_ja=str(ja_total),
    )
    finish_pack(db)
    log(f"grammar: {len(points)} points, {tatoeba_total} Tatoeba examples, {ja_total} with Japanese explanations")


if __name__ == "__main__":
    main()
