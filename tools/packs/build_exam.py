"""Build content/packs/exam.sqlite: JLPT blueprints and JLPT/DLPT item banks (BRIEF §5.11).

Inputs:
- tools/items/jlpt_blueprints.json: published JLPT structure (hand-maintained facts).
- tools/items/bank/*.json: item banks in the format documented in docs/CONTENT_PACKS.md ("Exam item banks").
- tools/items/ilr_bands.json (optional): per-ILR-level length / kanji-density / abstract-vocabulary bands for
  DLPT passages. Misses are warnings; they never fail the build.

Every structural problem fails the build (unique ids, answer in range, distinct choices, passages exist, JLPT type on
the level's blueprint, NFC text). The app runs the same structural checks on user imports (ExamBankValidator.kt).

Run: uv run python packs/build_exam.py
"""

from __future__ import annotations

import json
import sys
import unicodedata

from common import PACKS, REPO, dumps, finish_pack, log, open_pack, set_meta

EXAM_PACK = PACKS / "exam.sqlite"
EXAM_SQ = REPO / "shared/src/commonMain/sqldelightExam/app/tsumugi/exam/db/exam.sq"
ITEMS = REPO / "tools" / "items"
BLUEPRINTS = ITEMS / "jlpt_blueprints.json"
BANKS = ITEMS / "bank"
ILR_BANDS = ITEMS / "ilr_bands.json"
EXAM_PACK_VERSION = "1"

EXAMS = {"JLPT", "DLPT_READING", "DLPT_LISTENING"}
JLPT_LEVELS = {"N5", "N4", "N3", "N2", "N1"}
ILR_LEVELS = {"0+", "1", "1+", "2", "2+", "3"}
DLPT_TYPES = {"main_idea", "detail", "inference", "purpose", "vocabulary_in_context", "tone"}


def is_nfc(text: str) -> bool:
    return unicodedata.normalize("NFC", text) == text


def kanji_density(text: str) -> float:
    chars = [c for c in text if not c.isspace()]
    if not chars:
        return 0.0
    kanji = sum(1 for c in chars if "一" <= c <= "鿿" or c == "々")
    return kanji / len(chars)


def blueprint_types(blueprints: dict) -> dict[str, set[str]]:
    return {
        f"N{level['level']}": {spec["type"] for section in level["sections"] for spec in section["items"]}
        for level in blueprints["levels"]
    }


def validate_bank(bank: dict, types: dict[str, set[str]], seen_items: set[str], seen_passages: set[str]) -> list[str]:
    errors: list[str] = []
    name = bank.get("bank", "?")
    passage_ids = set()
    for p in bank.get("passages", []):
        pid = p.get("id", "")
        where = f"{name}: passage {pid}"
        if not pid or pid in seen_passages:
            errors.append(f"{where}: missing or duplicate id")
        seen_passages.add(pid)
        passage_ids.add(pid)
        exam, level = p.get("exam"), p.get("level")
        if exam not in EXAMS:
            errors.append(f"{where}: unknown exam {exam!r}")
        elif level not in (JLPT_LEVELS if exam == "JLPT" else ILR_LEVELS):
            errors.append(f"{where}: bad level {level!r} for {exam}")
        body, script = p.get("body", ""), p.get("script", [])
        if not body.strip() and not script:
            errors.append(f"{where}: needs a body or a script")
        if not is_nfc(body) or any(not is_nfc(line.get("text", "")) for line in script):
            errors.append(f"{where}: text is not NFC")
    for i in bank.get("items", []):
        iid = i.get("id", "")
        where = f"{name}: item {iid}"
        if not iid or iid in seen_items:
            errors.append(f"{where}: missing or duplicate id")
        seen_items.add(iid)
        exam, level, kind = i.get("exam"), i.get("level"), i.get("type")
        if exam not in EXAMS:
            errors.append(f"{where}: unknown exam {exam!r}")
        elif exam == "JLPT":
            if level not in JLPT_LEVELS:
                errors.append(f"{where}: bad JLPT level {level!r}")
            elif kind not in types.get(level, set()):
                errors.append(f"{where}: type {kind!r} isn't on the {level} blueprint")
        else:
            if level not in ILR_LEVELS:
                errors.append(f"{where}: bad ILR level {level!r}")
            if kind not in DLPT_TYPES:
                errors.append(f"{where}: unknown DLPT type {kind!r}")
        choices = i.get("choices", [])
        if len(choices) not in (3, 4):
            errors.append(f"{where}: needs 3 or 4 choices")
        if any(not str(c).strip() for c in choices) or len(set(choices)) != len(choices):
            errors.append(f"{where}: choices must be distinct and non-empty")
        if not isinstance(i.get("answer"), int) or not 0 <= i["answer"] < len(choices):
            errors.append(f"{where}: answer out of range")
        if not i.get("stem", "").strip() and not i.get("script"):
            errors.append(f"{where}: empty stem")
        if i.get("passageId") and i["passageId"] not in passage_ids:
            errors.append(f"{where}: unknown passage {i['passageId']}")
        if not is_nfc(i.get("stem", "")) or any(not is_nfc(str(c)) for c in choices):
            errors.append(f"{where}: text is not NFC")
    return errors


def band_warnings(bank: dict, bands: dict) -> list[str]:
    levels = bands.get("levels", {})
    lexicon = bands.get("abstractLexicon", [])
    out = []
    for p in bank.get("passages", []):
        band = levels.get(p.get("level"))
        if not band or p.get("exam") == "JLPT":
            continue
        text = p.get("body") or "".join(line.get("text", "") for line in p.get("script", []))
        length = len([c for c in text if not c.isspace()])
        density = kanji_density(text)
        abstract = sum(text.count(w) for w in lexicon) / max(1, length / 10)
        problems = []
        if not band["minChars"] <= length <= band["maxChars"]:
            problems.append(f"length {length} outside {band['minChars']}–{band['maxChars']}")
        lo, hi = band["kanjiDensity"]
        if not lo <= density <= hi:
            problems.append(f"kanji density {density:.2f} outside {lo}–{hi}")
        lo, hi = band.get("abstractRatio", [0, 1])
        if not lo <= abstract <= hi:
            problems.append(f"abstract ratio {abstract:.2f} outside {lo}–{hi}")
        if problems:
            out.append(f"{bank['bank']}: passage {p['id']} (ILR {p['level']}): " + "; ".join(problems))
    return out


def main() -> None:
    blueprints = json.loads(BLUEPRINTS.read_text(encoding="utf-8"))
    types = blueprint_types(blueprints)
    bands = json.loads(ILR_BANDS.read_text(encoding="utf-8")) if ILR_BANDS.exists() else None
    banks = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(BANKS.glob("*.json"))] if BANKS.exists() else []

    errors: list[str] = []
    warnings: list[str] = []
    seen_items: set[str] = set()
    seen_passages: set[str] = set()
    for bank in banks:
        errors += validate_bank(bank, types, seen_items, seen_passages)
        if bands:
            warnings += band_warnings(bank, bands)
    for w in warnings[:40]:
        log(f"warning: {w}")
    if len(warnings) > 40:
        log(f"warning: … {len(warnings) - 40} more band warnings")
    if errors:
        for e in errors[:100]:
            log(f"error: {e}")
        sys.exit(f"build_exam: {len(errors)} errors")

    if EXAM_PACK.exists():
        EXAM_PACK.unlink()
    db = open_pack(EXAM_PACK, EXAM_SQ)
    passages = items = 0
    for bank in banks:
        for p in bank.get("passages", []):
            db.execute(
                "INSERT INTO exam_passage VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    p["id"], bank["bank"], p["exam"], p["level"], p.get("textType", ""), p.get("title", ""),
                    p.get("body", ""), dumps(p["script"]) if p.get("script") else "", p.get("source", "human"),
                    1 if p.get("verified") else 0,
                ),
            )
            passages += 1
        for n, i in enumerate(bank.get("items", [])):
            db.execute(
                "INSERT INTO exam_item VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    i["id"], bank["bank"], i["exam"], i["level"], i["type"], i.get("passageId"), i.get("ord", n),
                    i.get("stem", ""), dumps(i["choices"]), i["answer"], i.get("explanation", ""),
                    dumps(i["script"]) if i.get("script") else "", dumps(i.get("refs", [])), i.get("source", "human"),
                    1 if i.get("verified") else 0,
                ),
            )
            items += 1
    bank_meta = [
        {"bank": b["bank"], "title": b.get("title", b["bank"]), "license": b.get("license", ""), "attribution": b.get("attribution", "")}
        for b in banks
    ]
    set_meta(
        db,
        pack_version=EXAM_PACK_VERSION,
        jlpt_blueprints=json.dumps(blueprints, ensure_ascii=False, separators=(",", ":")),
        banks=json.dumps(bank_meta, ensure_ascii=False, separators=(",", ":")),
    )
    finish_pack(db)
    log(f"exam.sqlite: {len(banks)} banks, {passages} passages, {items} items, {len(warnings)} band warnings")


if __name__ == "__main__":
    main()
