"""Build the translation-workbench tables of content/packs/linguist.sqlite (BRIEF_V2 §6.12; DECISIONS D-270, D-271).

Sources:
  translation/passages/*.json   passages with reference translations (Claude-drafted launch set, and batches added by
                                `draft`); excerpts from graded readers (origin reader) and Aozora (origin aozora) must
                                be exact substrings of their source, which the build re-checks
  dictionary.sqlite             Tatoeba pairs (human translations, CC BY 2.0 FR) grouped into short passages per genre
                                by keyword, both directions (origin tatoeba, no AI badge)

The sight-translation limit is computed like SightTimer in shared/.../translation/TranslationModels.kt.

Run: uv run python packs/build_translation.py [check]
     uv run python packs/build_translation.py draft --genre news --direction je --count 5 --endpoint URL --model NAME
         asks any OpenAI-compatible endpoint for more passages; replies that pass the same validation are written to a
         new batch file with the next free ids (ids are never reused); everything drafted is source "llm".
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE / "literature"), str(HERE / "readers")]

from common import PACKS, REPO, dumps, log, open_pack, reset_tables, set_meta

LINGUIST_SQ = REPO / "shared/src/commonMain/sqldelightLinguist/app/tsumugi/linguist/db/linguist.sq"
PASSAGES = HERE / "translation" / "passages"
GENRES = ["news", "technical", "legal", "literary", "dialogue", "military"]
LEVELS = {"N5": 5, "N4": 4, "N3": 3, "N2": 2, "N1": 1}
ILR = {"0+", "1", "1+", "2", "2+", "3", "3+", "4"}
ID = re.compile(r"^tr-(news|technical|legal|literary|dialogue|military)-(\d{3})$")
JA_CHARS = (60, 420)
EN_WORDS = (30, 170)

# Tatoeba passages: keywords per genre, sentences of 10–60 characters with an English translation.
TATOEBA_KEYWORDS = {
    "news": ["ニュース", "新聞", "政府", "首相", "大統領", "選挙", "経済", "事故"],
    "technical": ["コンピューター", "パソコン", "ソフト", "機械", "技術", "データ", "プログラム", "電池"],
    "legal": ["法律", "契約", "裁判", "弁護士", "規則", "違反", "権利", "警察"],
    "literary": ["夕日", "月が", "静かな", "心の", "夢の", "花が", "空が", "涙"],
    "dialogue": ["ですか？", "ましょうか", "ませんか", "かい？", "だよね", "でしょう？"],
    "military": ["軍", "兵士", "基地", "平和", "訓練", "救助", "災害", "自衛隊"],
}
TATOEBA_PER_PASSAGE = 4
TATOEBA_PASSAGES = 2  # per genre and direction


def sight_seconds(text: str, direction: str) -> int:
    """SightTimer.seconds: J→E 20 s + 1 s per 2.5 characters, E→J 20 s + 1.2 s per word; up to 5 s, 30–300 s."""
    if direction == "JE":
        raw = 20 + sum(1 for c in text if not c.isspace()) / 2.5
    else:
        raw = 20 + sum(1 for w in text.split() if any(ch.isalnum() for ch in w)) * 1.2
    return min(300, max(30, math.ceil(raw / 5.0) * 5))


def is_japanese(text: str) -> bool:
    return any("぀" <= c <= "ヿ" or "一" <= c <= "鿿" for c in text)


def batch_files() -> list[Path]:
    return sorted(PASSAGES.glob("*.json"))


def load_passages() -> list[tuple[Path, dict]]:
    out = []
    for f in batch_files():
        out += [(f, p) for p in json.loads(f.read_text(encoding="utf-8"))["passages"]]
    return out


def reader_bodies() -> dict[str, str]:
    out = {}
    for f in sorted((HERE / "readers" / "stories").glob("*.json")):
        for s in json.loads(f.read_text(encoding="utf-8"))["passages"]:
            out[s["id"]] = s["body"]
    return out


def aozora_texts(work_ids: set[str]) -> dict[str, str]:
    """Clean prose of each work (the reading-circle entry's section/replace when it has one)."""
    import aozora

    circle = {t["work"]: t for t in json.loads((HERE / "literature" / "circle.json").read_text(encoding="utf-8"))["texts"]}
    out = {}
    for wid in work_ids:
        w = aozora.fetch(wid)
        entry = circle.get(wid, {})
        lines = w.section(entry["section"], entry.get("occurrence", 1)) if entry.get("section") else w.lines
        out[wid] = aozora.body(lines, entry.get("replace"), poem=False).text
    return out


def check_passage(p: dict, readers: dict[str, str] | None, aozora: dict[str, str] | None) -> list[str]:
    pid = p.get("id", "?")
    errs = []
    if not ID.match(pid) or pid.split("-")[1] != p.get("genre"):
        errs.append(f"{pid}: id must be tr-<genre>-NNN matching its genre")
    if p.get("direction") not in ("je", "ej"):
        errs.append(f"{pid}: direction must be je or ej")
    if p.get("level") not in LEVELS or p.get("ilr") not in ILR:
        errs.append(f"{pid}: level N5…N1 and a valid ILR band")
    for k in ("title", "text", "reference", "register", "notes"):
        if not isinstance(p.get(k), str) or not p[k].strip():
            errs.append(f"{pid}: missing {k}")
    kp = p.get("keyPoints")
    if not isinstance(kp, list) or not 3 <= len(kp) <= 5:
        errs.append(f"{pid}: 3–5 keyPoints")
    ja, en = (p.get("text", ""), p.get("reference", "")) if p.get("direction") == "je" else (p.get("reference", ""), p.get("text", ""))
    if not is_japanese(ja) or is_japanese(en):
        errs.append(f"{pid}: the Japanese and English sides are the wrong way round")
    if not JA_CHARS[0] <= len(ja) <= JA_CHARS[1]:
        errs.append(f"{pid}: Japanese side is {len(ja)} characters ({JA_CHARS[0]}–{JA_CHARS[1]})")
    if p.get("direction") == "ej" and not EN_WORDS[0] <= len(en.split()) <= EN_WORDS[1]:
        errs.append(f"{pid}: English source is {len(en.split())} words ({EN_WORDS[0]}–{EN_WORDS[1]})")
    if unicodedata.normalize("NFC", json.dumps(p, ensure_ascii=False)) != json.dumps(p, ensure_ascii=False):
        errs.append(f"{pid}: not NFC")
    if p.get("source") not in ("llm", "verified") or (p.get("verified") and p.get("source") != "verified"):
        errs.append(f"{pid}: source llm, or verified with verified true")
    origin = p.get("origin") or {}
    kind = origin.get("kind")
    if kind not in ("original", "reader", "aozora"):
        errs.append(f"{pid}: origin kind original, reader or aozora")
    elif kind != "original":
        if p.get("direction") != "je":
            errs.append(f"{pid}: excerpts are J→E only")
        src = (readers if kind == "reader" else aozora) or {}
        body = src.get(origin.get("ref", ""))
        if body is not None and p.get("text", "") not in body:
            errs.append(f"{pid}: the excerpt is not an exact substring of {kind} {origin.get('ref')}")
        elif body is None and src:
            errs.append(f"{pid}: unknown {kind} {origin.get('ref')}")
    return errs


def validate(fetch: bool = True) -> list[str]:
    items = load_passages()
    errors, seen = [], set()
    readers = reader_bodies()
    works = {p["origin"]["ref"] for _, p in items if (p.get("origin") or {}).get("kind") == "aozora"}
    texts = aozora_texts(works) if fetch and works else None
    for f, p in items:
        if p.get("id") in seen:
            errors.append(f"{p.get('id')}: duplicate id ({f.name})")
        seen.add(p.get("id"))
        errors += check_passage(p, readers, texts)
    return errors


def tatoeba_passages(dictionary: Path) -> list[dict]:
    """Deterministic keyword groups of Tatoeba pairs (lowest ids first, each sentence used once)."""
    db = sqlite3.connect(f"file:{dictionary}?mode=ro", uri=True)
    rows = db.execute("SELECT id, ja, en, jlpt FROM sentence WHERE length(ja) BETWEEN 10 AND 60 ORDER BY id").fetchall()
    db.close()
    used: set[int] = set()
    out = []
    for genre in GENRES:
        picked = []
        for sid, ja, en, jlpt in rows:
            if sid in used or not any(k in ja for k in TATOEBA_KEYWORDS[genre]):
                continue
            picked.append((sid, ja, en, jlpt))
            used.add(sid)
            if len(picked) >= TATOEBA_PER_PASSAGE * TATOEBA_PASSAGES * 2:
                break
        groups = [picked[i:i + TATOEBA_PER_PASSAGE] for i in range(0, len(picked), TATOEBA_PER_PASSAGE)]
        for n, group in enumerate(groups):
            if len(group) < TATOEBA_PER_PASSAGE:
                continue
            direction = "JE" if n % 2 == 0 else "EJ"
            ja = "\n".join(g[1] for g in group)
            en = "\n".join(g[2] for g in group)
            levels = [g[3] for g in group if g[3]]
            jlpt = min(levels) if levels else 3
            out.append({
                "id": f"tatoeba-{genre}-{n + 1:02d}", "direction": direction, "genre": genre,
                "level": f"N{jlpt}", "jlpt": jlpt, "ilr": {5: "1", 4: "1", 3: "1+", 2: "2", 1: "2+"}.get(jlpt, "1+"),
                "title": f"Tatoeba sentences: {genre}", "text": ja if direction == "JE" else en,
                "reference": en if direction == "JE" else ja, "register": "as in the original sentences",
                "keyPoints": [], "notes": "Separate sentences with human translations from Tatoeba (CC BY 2.0 FR). "
                                          "The reference is one good translation, not the only one.",
                "origin": {"kind": "tatoeba", "ref": dumps([g[0] for g in group])}, "source": "tatoeba", "verified": False,
            })
    return out


def build(packs: Path = PACKS) -> dict[str, int]:
    errors = validate()
    if errors:
        for e in errors:
            log(f"ERROR: {e}")
        raise SystemExit(f"translation: {len(errors)} validation errors")
    items = [p for _, p in load_passages()]
    items.sort(key=lambda p: (GENRES.index(p["genre"]), p["direction"], -LEVELS[p["level"]], p["id"]))
    extra = tatoeba_passages(packs / "dictionary.sqlite")
    db = open_pack(packs / "linguist.sqlite", LINGUIST_SQ)
    reset_tables(db, {"translation_passage"}, LINGUIST_SQ)
    for ord_, p in enumerate(items):
        direction = p["direction"].upper()
        db.execute("INSERT INTO translation_passage VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (
            p["id"], ord_, direction, p["genre"], p["level"], LEVELS[p["level"]], p["ilr"], p["title"], p["text"],
            p["reference"], p["register"], dumps(p["keyPoints"]), p["notes"], p["origin"]["kind"], p["origin"].get("ref"),
            sight_seconds(p["text"], direction), p["source"], 1 if p.get("verified") else 0,
        ))
    base = len(items)
    for i, p in enumerate(extra):
        db.execute("INSERT INTO translation_passage VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (
            p["id"], base + i, p["direction"], p["genre"], p["level"], p["jlpt"], p["ilr"], p["title"], p["text"],
            p["reference"], p["register"], "[]", p["notes"], "tatoeba", p["origin"]["ref"],
            sight_seconds(p["text"], p["direction"]), "tatoeba", 0,
        ))
    counts = {"drafted": len(items), "tatoeba": len(extra)}
    for g in GENRES:
        counts[g] = sum(1 for p in items if p["genre"] == g)
    set_meta(db, pack="linguist", pack_version="1", translation=json.dumps(counts, separators=(",", ":")))
    db.commit()
    db.close()
    log(f"translation: {counts}")
    return counts


# --- Drafting through the owner's endpoint -----------------------------------------------------------------------

DRAFT_SYSTEM = (
    "You write practice passages for a Japanese-English translation workbench. Reply with one JSON object "
    "{\"passages\": [...]}; each passage has title (English), text (the source passage), reference (a careful natural "
    "translation), register (one English line naming the target register), keyPoints (3 to 5 English items a good "
    "translation must get right), notes (1 to 3 English sentences of translator's notes), level (N5 to N1 for the "
    "Japanese side) and ilr (\"1\", \"1+\", \"2\", \"2+\", \"3\" or \"3+\"). Japanese: 80 to 400 characters, standard "
    "orthography. English: 40 to 150 words. Invent everything (fictional people, places and organizations)."
)


def next_ids(genre: str, n: int) -> list[str]:
    used = {int(ID.match(p["id"]).group(2)) for _, p in load_passages() if ID.match(p.get("id", "")) and p["genre"] == genre}
    start = max(used, default=0) + 1
    return [f"tr-{genre}-{i:03d}" for i in range(start, start + n)]


def draft(genre: str, direction: str, count: int, endpoint: str, model: str, api_key_env: str) -> int:
    from llm_draft import chat_json

    src, tgt = ("Japanese", "English") if direction == "je" else ("English", "Japanese")
    user = f"Write {count} {genre} passages. text is in {src}; reference is the {tgt} translation."
    try:
        out = chat_json(endpoint, model, DRAFT_SYSTEM, user, api_key_env=api_key_env)
    except Exception as e:
        raise SystemExit(f"the endpoint failed: {e}") from e
    good = []
    ids = next_ids(genre, count)
    for raw, pid in zip(out.get("passages", [])[:count], ids, strict=False):
        if not isinstance(raw, dict):
            continue
        p = {"id": pid, "direction": direction, "genre": genre, "level": raw.get("level"), "ilr": str(raw.get("ilr")),
             "title": raw.get("title"), "text": raw.get("text"), "reference": raw.get("reference"),
             "register": raw.get("register"), "keyPoints": raw.get("keyPoints"), "notes": raw.get("notes"),
             "origin": {"kind": "original"}, "source": "llm", "verified": False}
        p = json.loads(unicodedata.normalize("NFC", json.dumps(p, ensure_ascii=False)))
        errs = check_passage(p, None, None)
        if errs:
            log(f"rejected: {errs[:3]}")
            continue
        good.append(p)
    if not good:
        log("nothing passed validation; nothing written")
        return 1
    target = PASSAGES / f"drafted-{dt.datetime.now(dt.UTC).date().isoformat()}-{genre}-{direction}.json"
    doc = json.loads(target.read_text(encoding="utf-8")) if target.exists() else {"source": "llm", "passages": []}
    doc["passages"] += good
    target.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    log(f"wrote {len(good)} passages to {target.name}; review them with items/review.py {target.relative_to(HERE.parent)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("command", nargs="?", default="build", choices=["build", "check", "draft"])
    ap.add_argument("--packs", type=Path, default=PACKS)
    ap.add_argument("--genre", choices=GENRES)
    ap.add_argument("--direction", choices=["je", "ej"], default="je")
    ap.add_argument("--count", type=int, default=5)
    ap.add_argument("--endpoint")
    ap.add_argument("--model")
    ap.add_argument("--api-key-env", default="LLM_API_KEY")
    args = ap.parse_args(argv)
    if args.command == "check":
        errors = validate()
        for e in errors:
            print(f"ERROR: {e}")
        print(f"{len(load_passages())} passages, {len(errors)} errors")
        return 1 if errors else 0
    if args.command == "draft":
        if not (args.genre and args.endpoint and args.model):
            ap.error("draft needs --genre, --endpoint and --model")
        return draft(args.genre, args.direction, args.count, args.endpoint, args.model, args.api_key_env)
    build(args.packs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
