"""Build the dictionary tables of content/packs/dictionary.sqlite.

Sources (licenses in docs/LICENSES.md):
  JMdict + KANJIDIC2 + KRADFILE + RADKFILE  EDRDG, CC BY-SA 4.0, via scriptin/jmdict-simplified
  JmdictFurigana                             Doublevil, MIT
  Kanjium accents.txt                        mifunetoshiro/kanjium, CC BY-SA 4.0
  JLPT vocab levels (unofficial)             Jonathan Waller (CC BY) via stephenmk/yomitan-jlpt-vocab, CC BY-SA 4.0
  JLPT kanji levels (unofficial)             davidluzgouveia/kanji-data, MIT (jlpt_new field only)

Run: uv run packs/build_dictionary.py   (then build_kanjivg.py and build_sentences.py)
"""

from __future__ import annotations

import csv
import json
import re

import radicals
from common import (
    DICTIONARY_PACK_VERSION,
    dumps,
    finish_pack,
    is_kanji,
    log,
    nfc,
    open_pack,
    read_zip_json,
    reset_tables,
    set_meta,
    source,
    to_hiragana,
)

TABLES = {
    "entry", "entry_kanji", "entry_kana", "sense", "gloss_index",
    "kanji", "radical", "kanji_component", "kanji_word", "furigana", "pitch",
}

# Sources (URLs, releases, hashes) are pinned in sources.lock: jmdict-eng, kanjidic2-en, kradfile, radkfile,
# jmdict-furigana, kanjium-accents, jlpt-vocab-n1..n5, kanji-data.

# English words too common to be useful as gloss search terms on their own.
STOPWORDS = {"a", "an", "the", "to", "of", "be", "or", "and", "in", "on", "at", "for", "with", "as", "by", "one's"}
WORD_RE = re.compile(r"[a-z0-9']+")
# Whole-gloss "exact" terms are only indexed for short glosses; long ones are never typed verbatim.
MAX_EXACT_GLOSS_WORDS = 4

# Rank bands (lower = more common). Tatoeba frequency refines within a band in build_sentences.py.
RANK_UNCOMMON = 2_000_000
RANK_NO_JLPT = 1_000_000
RANK_FREQ_SPAN = 999_999


def arr(values: list) -> str:
    """JSON array, or "" for the common cases [] and ["*"] (see dictionary.sq header)."""
    return "" if not values or values == ["*"] else dumps(values)


def base_rank(common: bool, jlpt: int | None) -> int:
    return (0 if common else RANK_UNCOMMON) + (0 if jlpt else RANK_NO_JLPT) + RANK_FREQ_SPAN


def jlpt_vocab() -> dict[int, int]:
    """JMdict id -> JLPT level (5..1). Where a word appears at several levels, the easiest wins."""
    levels: dict[int, int] = {}
    for n in (1, 2, 3, 4, 5):
        path = source(f"jlpt-vocab-n{n}")
        with open(path, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                seq = row["jmdict_seq"].strip()
                if seq.isdigit():
                    levels[int(seq)] = max(levels.get(int(seq), 0), n)
    return levels


def build_words(db, jlpt: dict[int, int]) -> None:
    data = read_zip_json(source("jmdict-eng"))
    log(f"JMdict {data['version']} ({data['dictDate']}): {len(data['words'])} entries")
    set_meta(db, jmdict_version=data["version"], jmdict_date=data["dictDate"])

    entries, kanji_rows, kana_rows, senses, glosses = [], [], [], [], []
    kanji_words: dict[tuple[str, int], int] = {}
    for w in data["words"]:
        eid = int(w["id"])
        common = any(k["common"] for k in w["kanji"]) or any(k["common"] for k in w["kana"])
        level = jlpt.get(eid)
        rank = base_rank(common, level)
        entries.append((eid, int(common), rank, level))

        for i, k in enumerate(w["kanji"]):
            text = nfc(k["text"])
            kanji_rows.append((eid, i, text, int(k["common"]), arr(k["tags"])))
            for c in set(text):
                if is_kanji(c):
                    kanji_words[(c, eid)] = rank
        for i, k in enumerate(w["kana"]):
            text = nfc(k["text"])
            kana_rows.append(
                (eid, i, text, to_hiragana(text), int(k["common"]), arr(k["tags"]), arr(k["appliesToKanji"]))
            )

        terms: dict[str, int] = {}
        for i, s in enumerate(w["sense"]):
            gl = [nfc(g["text"]) for g in s["gloss"] if g["lang"] == "eng"]
            senses.append((
                eid, i, arr(s["partOfSpeech"]), dumps(gl), arr(s["misc"]), arr(s["field"]),
                arr(s["dialect"]), arr(s["info"]), arr(s["appliesToKanji"]), arr(s["appliesToKana"]),
                arr(["・".join(str(x) for x in r) for r in s["related"]]),
            ))
            sense_weight = max(1, 10 - i)
            for g in gl:
                lower = g.lower()
                exact = re.sub(r"\s*\([^)]*\)\s*", " ", lower).strip()
                if len(exact.split()) <= MAX_EXACT_GLOSS_WORDS:
                    terms["=" + exact] = max(terms.get("=" + exact, 0), 50 + sense_weight * 5)
                for word in WORD_RE.findall(lower):
                    if word not in STOPWORDS:
                        terms[word] = max(terms.get(word, 0), sense_weight)
        glosses.extend((t, eid, score) for t, score in terms.items())

    db.executemany("INSERT INTO entry VALUES (?,?,?,?)", entries)
    db.executemany("INSERT INTO entry_kanji VALUES (?,?,?,?,?)", kanji_rows)
    db.executemany("INSERT INTO entry_kana VALUES (?,?,?,?,?,?,?)", kana_rows)
    db.executemany("INSERT INTO sense VALUES (?,?,?,?,?,?,?,?,?,?,?)", senses)
    db.executemany("INSERT INTO gloss_index VALUES (?,?,?)", glosses)
    db.executemany("INSERT INTO kanji_word VALUES (?,?,?)", ((c, e, r) for (c, e), r in kanji_words.items()))
    log(f"  {len(entries)} entries, {len(senses)} senses, {len(glosses)} gloss terms")


def build_kanji(db) -> None:
    data = read_zip_json(source("kanjidic2-en"))
    set_meta(db, kanjidic2_version=data["version"])
    new_levels = {k: v.get("jlpt_new") for k, v in json.loads(source("kanji-data").read_text(
        encoding="utf-8")).items()}

    rows = []
    for c in data["characters"]:
        misc = c["misc"]
        refs = {r["type"]: r["value"] for r in c["dictionaryReferences"]}
        on, kun, meanings = [], [], []
        rm = c.get("readingMeaning") or {"groups": [], "nanori": []}
        for g in rm["groups"]:
            on += [r["value"] for r in g["readings"] if r["type"] == "ja_on"]
            kun += [r["value"] for r in g["readings"] if r["type"] == "ja_kun"]
            meanings += [m["value"] for m in g["meanings"] if m["lang"] == "en"]
        literal = c["literal"]
        rows.append((
            literal, misc.get("grade"), misc["strokeCounts"][0], misc.get("frequency"), misc.get("jlptLevel"),
            new_levels.get(literal),
            int(refs["heisig"]) if refs.get("heisig", "").isdigit() else None,
            int(refs["heisig6"]) if refs.get("heisig6", "").isdigit() else None,
            dumps(meanings), dumps(on), dumps(kun), dumps(rm.get("nanori", [])),
        ))
    db.executemany("INSERT INTO kanji VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", rows)
    log(f"  {len(rows)} kanji")

    krad = read_zip_json(source("kradfile"))
    radk = read_zip_json(source("radkfile"))
    meanings = {r[0]: json.loads(r[8]) for r in rows}
    db.executemany(
        "INSERT INTO radical VALUES (?,?,?,?)",
        (
            (r, v["strokeCount"], radicals.display(r), radicals.name(r, meanings))
            for r, v in radk["radicals"].items()
        ),
    )
    db.executemany(
        "INSERT OR IGNORE INTO kanji_component VALUES (?,?)",
        ((k, r) for k, rads in krad["kanji"].items() for r in rads),
    )
    log(f"  {len(radk['radicals'])} radicals, {len(krad['kanji'])} decomposed kanji")


def build_furigana(db) -> None:
    items = json.loads(source("jmdict-furigana").read_text(encoding="utf-8-sig"))
    rows = {}
    for it in items:
        text = nfc(it["text"])
        if not any(is_kanji(c) for c in text):
            continue
        segs = [s["ruby"] + ("=" + s["rt"] if s.get("rt") else "") for s in it["furigana"]]
        rows[(text, nfc(it["reading"]))] = "|".join(segs)
    db.executemany("INSERT INTO furigana VALUES (?,?,?)", ((t, r, s) for (t, r), s in rows.items()))
    log(f"  {len(rows)} furigana alignments")


def build_pitch(db) -> None:
    rows = {}
    with open(source("kanjium-accents"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            text, reading, accents = (nfc(p) for p in parts)
            # Kanjium sometimes annotates accents with POS in parentheses, e.g. "(名)0,(副)1"; keep numbers.
            nums = [n for n in re.findall(r"\d+", accents)]
            if nums:
                rows[(text, reading or text)] = ",".join(dict.fromkeys(nums))
    db.executemany("INSERT INTO pitch VALUES (?,?,?)", ((t, r, a) for (t, r), a in rows.items()))
    log(f"  {len(rows)} pitch accent entries")


def main() -> None:
    db = open_pack()
    reset_tables(db, TABLES)
    log("JLPT levels…")
    levels = jlpt_vocab()
    log("words…")
    build_words(db, levels)
    log("kanji…")
    build_kanji(db)
    log("furigana…")
    build_furigana(db)
    log("pitch…")
    build_pitch(db)
    set_meta(db, pack="dictionary", pack_version=DICTIONARY_PACK_VERSION)
    finish_pack(db)
    log("done")


if __name__ == "__main__":
    main()
