"""Build content/packs/readers.sqlite: graded readers with read-along lines, vocabulary, questions and genre
tasks (BRIEF_V2 §6.4, §8 Phase 12; DECISIONS D-200..D-209).

Sources: tools/packs/readers/stories/*.json (validated first with validate_readers.py; any error stops the build),
levels.json and tasks.json. Schema: shared/src/commonMain/sqldelightReaders/app/tsumugi/readers/db/readers.sq
(single source of truth; the CREATE statements are executed verbatim). Reads dictionary.sqlite and
tokenizer.sqlite for the measures (--packs, default content/packs or the main checkout's).

Run: uv run python packs/readers/build_readers.py [--packs DIR] [--out DIR]
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import readers_lib as L
from common import PACKS, REPO, dumps, finish_pack, open_pack, set_meta
from validate_readers import validate

READERS_SQ = REPO / "shared/src/commonMain/sqldelightReaders/app/tsumugi/readers/db/readers.sq"
READERS_PACK_VERSION = "1"


def skim_seconds(chars: int, cpm: int, rate: float) -> int:
    """Skim/scan timer: reading the text at the level's skim speed (scaled by the genre's rate), rounded up to 5 s,
    at least 20 s."""
    seconds = chars / (cpm * rate) * 60
    return max(20, math.ceil(seconds / 5.0) * 5)


def build(packs: Path, out_dir: Path) -> dict[str, int]:
    levels = L.load_levels()
    tasks = L.load_tasks()
    files = L.story_files()
    an = L.Analyzer(packs)
    rep, measures = validate(files, packs, levels=levels, an=an)
    for w in rep.warnings:
        L.log(f"warning: {w}")
    if rep.errors:
        for e in rep.errors:
            L.log(f"ERROR: {e}")
        raise SystemExit(f"readers: {len(rep.errors)} validation errors; fix them (validate_readers.py) and rebuild")
    missing = [g for g in levels["genres"] if g not in tasks["genres"]]
    if missing:
        raise SystemExit(f"tasks.json has no templates for {missing}")

    out = out_dir / "readers.sqlite"
    out.unlink(missing_ok=True)
    db = open_pack(out, READERS_SQ)
    for level, cfg in levels["levels"].items():
        db.execute(
            "INSERT INTO reader_level VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (level, cfg["order"], cfg["label"], cfg["appJlpt"], cfg["ilr"], cfg["textScore"][0], cfg["textScore"][1],
             cfg["summary"][0], cfg["summary"][1], cfg["questionLanguage"]),
        )
    for genre in levels["genres"]:
        t = tasks["genres"][genre]
        rows = [("PREDICTION", 0, t["prediction"], {}),
                ("SKIM", 0, t["skim"], {"rate": t["skim"].get("rate", 1.0), "find": t["skim"].get("find", [])})]
        rows += [("CLOSE", i, c, {}) for i, c in enumerate(t["close"])]
        rows.append(("OUTPUT", 0, t["output"], {"rubric": tasks["rubric"]}))
        for kind, ord_, prompt, detail in rows:
            db.execute("INSERT INTO reader_task VALUES (?, ?, ?, ?, ?, ?)",
                       (genre, kind, ord_, L.nfc(prompt["ja"]), prompt["en"], dumps(detail)))

    stories = sorted((p for _, p in L.load_stories(files)),
                     key=lambda p: (levels["levels"][p["level"]]["order"], p["id"]))
    counts: dict[str, int] = {}
    genres: dict[str, int] = {}
    sentences_total = 0
    for p in stories:
        cfg = levels["levels"][p["level"]]
        m = measures[p["id"]]
        body = L.nfc(p["body"])
        ord_ = counts.get(p["level"], 0)
        counts[p["level"]] = ord_ + 1
        genres[p["genre"]] = genres.get(p["genre"], 0) + 1
        rate = tasks["genres"][p["genre"]]["skim"].get("rate", 1.0)
        verified = bool(p.get("verified")) or p.get("source") == "verified"
        db.execute(
            "INSERT INTO reader_story VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (p["id"], ord_, p["level"], cfg["appJlpt"], p["genre"], p["topic"], L.nfc(p["title"]), p["titleEn"], body,
             m.chars, m.text_score, m.label, m.label_ilr, round(m.coverage, 4), dumps(p.get("cast", [])),
             dumps(p.get("ruby", [])), skim_seconds(m.chars, cfg["skimCpm"], rate),
             "verified" if p.get("source") == "verified" else "llm", int(verified)),
        )
        for line in L.read_along(body, p.get("cast", [])):
            db.execute("INSERT INTO reader_sentence VALUES (?, ?, ?, ?, ?, ?)",
                       (p["id"], line.index, line.start, line.end, line.speaker, line.voice))
            sentences_total += 1
        for i, v in enumerate(p["vocabulary"]):
            db.execute("INSERT INTO reader_vocab VALUES (?, ?, ?, ?, ?, ?)",
                       (p["id"], i, v["entryId"], L.nfc(v["word"]), L.nfc(v["reading"]), v["gloss"]))
        for i, q in enumerate(p["questions"]):
            db.execute("INSERT INTO reader_question VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                       (p["id"], i, q["type"], cfg["questionLanguage"], L.nfc(q["stem"]),
                        dumps([L.nfc(c) for c in q["choices"]]), q["answer"], q["explanation"]))
    set_meta(
        db,
        pack_version=READERS_PACK_VERSION,
        license=L.LICENSE,
        attribution=L.ATTRIBUTION,
        stories=str(len(stories)),
        sentences=str(sentences_total),
        levels=dumps(counts),
        genres=dumps(dict(sorted(genres.items()))),
        verified=str(sum(1 for p in stories if p.get("verified") or p.get("source") == "verified")),
    )
    finish_pack(db)
    L.log(f"readers.sqlite: {len(stories)} stories {json.dumps(counts)}, {sentences_total} read-along lines, "
          f"genres {json.dumps(dict(sorted(genres.items())))}")
    return counts


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--packs", type=Path, help="directory with dictionary.sqlite and tokenizer.sqlite")
    ap.add_argument("--out", type=Path, default=PACKS, help="where readers.sqlite is written (default content/packs)")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    build(args.packs or L.default_packs(), args.out)


if __name__ == "__main__":
    main()
