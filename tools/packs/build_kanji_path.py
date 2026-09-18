"""Build content/packs/kanji-path.sqlite: a 60-level radical → kanji → vocabulary path (BRIEF §5.4).

Reads the dictionary pack (run build_all.py first). Everything here is derived from EDRDG data
(KANJIDIC2, KRADFILE, JMdict) plus our own ordering rules; no third-party course content is used.

Ordering:
  - kanji: jōyō kanji plus frequent non-jōyō ones, sorted by school grade, then by a blend of stroke
    count and newspaper frequency, and spread over 60 levels (smaller early levels);
  - radicals: each KRADFILE component is introduced at the level of the first kanji that uses it, so a kanji
    is never scheduled before its components;
  - vocabulary: common words written only with path kanji, placed at the level of their last kanji.
Keywords: the KANJIDIC2 primary meaning, disambiguated so no two kanji share one.

Run: uv run python packs/build_kanji_path.py
"""

from __future__ import annotations

import json
from collections import defaultdict

import radicals
from common import (
    DICTIONARY_PACK,
    PATH_PACK,
    PATH_PACK_VERSION,
    PATH_SQ,
    dumps,
    finish_pack,
    is_kanji,
    log,
    open_pack,
    reset_tables,
    set_meta,
    to_hiragana,
)

LEVELS = 60
FIRST_LEVEL_SIZE = 15
FULL_LEVEL_SIZE = 38
RAMP_LEVELS = 10
MAX_NON_JOYO_FREQ = 2500
VOCAB_PER_KANJI = 4
MAX_VOCAB_CHARS = 4
MAX_VOCAB_MEANINGS = 8


def level_sizes(total: int) -> list[int]:
    """Kanji per level: ramps from FIRST_LEVEL_SIZE to FULL_LEVEL_SIZE, remainder spread over the rest."""
    sizes = [
        round(FIRST_LEVEL_SIZE + (FULL_LEVEL_SIZE - FIRST_LEVEL_SIZE) * min(1.0, i / RAMP_LEVELS))
        for i in range(LEVELS)
    ]
    scale = total / sum(sizes)
    sizes = [max(1, round(s * scale)) for s in sizes]
    sizes[-1] += total - sum(sizes)
    return sizes


def kanji_readings(onyomi: list[str], kunyomi: list[str]) -> list[str]:
    out = [to_hiragana(r) for r in onyomi]
    out += [k.split(".")[0].strip("-") for k in kunyomi]
    return list(dict.fromkeys(r for r in out if r))


def main() -> None:
    src = open_pack(DICTIONARY_PACK)
    kanji_rows = src.execute(
        "SELECT literal, grade, stroke_count, freq, jlpt_new, heisig6, meanings, onyomi, kunyomi FROM kanji"
    ).fetchall()
    meanings_by_kanji = {r[0]: json.loads(r[6]) for r in kanji_rows}
    components = defaultdict(list)
    for k, r in src.execute("SELECT kanji, radical FROM kanji_component"):
        components[k].append(r)
    radical_strokes = dict(src.execute("SELECT radical, stroke_count FROM radical"))

    # --- Kanji selection and order -----------------------------------------------------------------------
    def grade_bucket(grade: int | None) -> int:
        return grade if grade and grade <= 6 else 7 if grade == 8 else 8

    chosen = [
        r for r in kanji_rows
        if (r[1] in (1, 2, 3, 4, 5, 6, 8)) or (r[3] is not None and r[3] <= MAX_NON_JOYO_FREQ)
    ]
    chosen.sort(key=lambda r: (grade_bucket(r[1]), r[2] + (r[3] or 2600) / 150, r[0]))
    sizes = level_sizes(len(chosen))
    level_of: dict[str, int] = {}
    i = 0
    for lvl, size in enumerate(sizes, start=1):
        for r in chosen[i:i + size]:
            level_of[r[0]] = lvl
        i += size

    # --- Items ------------------------------------------------------------------------------------------
    items: list[tuple] = []
    prereqs: list[tuple[str, str]] = []
    order: defaultdict[int, int] = defaultdict(int)

    def add(item_id, kind, level, text, display, keyword, meanings, readings, other, entry_id, heisig, jlpt):
        order[level] += 1
        items.append((item_id, kind, level, order[level], text, display, keyword, dumps(meanings),
                      dumps(readings), dumps(other), entry_id, heisig, jlpt))

    radical_level: dict[str, int] = {}
    for r in chosen:
        for rad in components.get(r[0], []):
            if rad in radical_strokes:
                radical_level.setdefault(rad, level_of[r[0]])
    # Radicals first within each level, simplest first.
    for rad, lvl in sorted(radical_level.items(), key=lambda kv: (kv[1], radical_strokes[kv[0]], kv[0])):
        name = radicals.name(rad, meanings_by_kanji)
        accepted = list(dict.fromkeys([name] + [m.lower() for m in meanings_by_kanji.get(rad, [])[:2]]))
        add(f"r:{rad}", "RADICAL", lvl, rad, radicals.display(rad), name, accepted, [], [], None, None, None)

    used_keywords: set[str] = set()
    for r in chosen:
        literal, _, _, _, jlpt, heisig6, meanings_json, on_json, kun_json = r
        meanings = json.loads(meanings_json) or [literal]
        keyword = next((m for m in meanings if m.lower() not in used_keywords), None)
        if keyword is None:
            keyword = f"{meanings[0]} ({literal})"
        used_keywords.add(keyword.lower())
        readings = kanji_readings(json.loads(on_json), json.loads(kun_json))
        add(f"k:{literal}", "KANJI", level_of[literal], literal, literal, keyword, meanings, readings, [], None,
            heisig6, jlpt)
        for rad in components.get(literal, []):
            if rad in radical_strokes:
                prereqs.append((f"k:{literal}", f"r:{rad}"))

    # --- Vocabulary -------------------------------------------------------------------------------------
    per_kanji: defaultdict[str, int] = defaultdict(int)
    vocab_count = 0
    rows = src.execute(
        """SELECT e.id, e.jlpt, k.text,
                  (SELECT misc FROM sense WHERE entry_id = e.id AND ord = 0)
           FROM entry e JOIN entry_kanji k ON k.entry_id = e.id AND k.ord = 0
           WHERE e.is_common = 1 ORDER BY e.rank"""
    ).fetchall()
    for entry_id, jlpt, text, misc in rows:
        if len(text) > MAX_VOCAB_CHARS or (misc and "uk" in json.loads(misc)):
            continue
        kanji_in_word = [c for c in text if is_kanji(c)]
        if not kanji_in_word or any(c not in level_of for c in kanji_in_word):
            continue
        if any(not (is_kanji(c) or "぀" <= c <= "ヿ") for c in text):
            continue
        last = max(kanji_in_word, key=lambda c: level_of[c])
        if per_kanji[last] >= VOCAB_PER_KANJI:
            continue
        kana = [
            reading for reading, applies in src.execute(
                "SELECT text, applies_to FROM entry_kana WHERE entry_id = ? ORDER BY ord", (entry_id,)
            )
            if not applies or text in json.loads(applies)
        ]
        glosses: list[str] = []
        for (g,) in src.execute("SELECT glosses FROM sense WHERE entry_id = ? ORDER BY ord LIMIT 3", (entry_id,)):
            glosses += json.loads(g)
        if not kana or not glosses:
            continue
        per_kanji[last] += 1
        vocab_count += 1
        add(f"v:{entry_id}", "VOCAB", level_of[last], text, text, glosses[0], glosses[:MAX_VOCAB_MEANINGS],
            kana, [], entry_id, None, jlpt)
        for c in dict.fromkeys(kanji_in_word):
            prereqs.append((f"v:{entry_id}", f"k:{c}"))

    # Items were appended radicals → kanji → vocab, so per-level ord already follows that order.
    db = open_pack(PATH_PACK, PATH_SQ)
    reset_tables(db, {"path_item", "path_prereq"}, PATH_SQ)
    db.executemany("INSERT INTO path_item VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", items)
    db.executemany("INSERT OR IGNORE INTO path_prereq VALUES (?,?)", prereqs)
    set_meta(db, pack="kanji-path", pack_version=PATH_PACK_VERSION, levels=str(LEVELS))
    finish_pack(db)
    src.close()
    log(f"kanji path: {len(radical_level)} radicals, {len(chosen)} kanji, {vocab_count} vocab over {LEVELS} levels")


if __name__ == "__main__":
    main()
