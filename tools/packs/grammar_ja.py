"""Japanese explanations (meaning_ja / nuance_ja) for the grammar sources, for monolingual mode (BRIEF_V2 §6.6, D-233).

The fields live next to the English ones in tools/packs/grammar/n*.json, with their own provenance field `ja_source`
("llm" until reviewed; items/review.py only flips `source`, which covers the English text). build_grammar.py puts them
in the pack's grammar_point_ja table.

    uv run python packs/grammar_ja.py status                     # counts per level
    uv run python packs/grammar_ja.py merge drafts.json [...]    # {"<point id>": {"meaning_ja", "nuance_ja"}} files
    uv run python packs/grammar_ja.py draft --endpoint http://localhost:11434/v1 --model qwen3:8b [--level N3]
    uv run python packs/grammar_ja.py check                      # validate every filled point

Re-runs only fill points that have no Japanese yet (merge --overwrite replaces), so ids never duplicate.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from common import log, nfc
from llm_draft import add_endpoint_args, chat_json

SOURCES = Path(__file__).resolve().parent / "grammar"
MAX_MEANING, MAX_NUANCE = 45, 150
LATIN = re.compile(r"[A-Za-z]")


def load() -> dict[Path, dict]:
    return {f: json.loads(f.read_text(encoding="utf-8")) for f in sorted(SOURCES.glob("n[1-5].json"))}


def save(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def problems(meaning: str, nuance: str) -> list[str]:
    out = []
    for name, text, limit in (("meaning_ja", meaning, MAX_MEANING), ("nuance_ja", nuance, MAX_NUANCE)):
        if not text.strip():
            out.append(f"{name} is empty")
        elif LATIN.search(text):
            out.append(f"{name} contains Latin letters")
        elif len(text) > limit:
            out.append(f"{name} is longer than {limit}")
    return out


def apply(point: dict, meaning: str, nuance: str, source: str = "llm") -> None:
    point["meaning_ja"] = nfc(meaning.strip())
    point["nuance_ja"] = nfc(nuance.strip())
    point["ja_source"] = source
    if source != "verified":  # new text needs a new review (items/review.py --kind grammar_ja)
        point.pop("ja_reviewed", None)


def cmd_status(_args) -> None:
    for path, data in load().items():
        points = data["points"]
        filled = sum(1 for p in points if p.get("meaning_ja"))
        log(f"{path.stem}: {filled}/{len(points)} with Japanese")


def cmd_merge(args) -> None:
    drafts: dict[str, dict] = {}
    for f in args.files:
        drafts.update(json.loads(Path(f).read_text(encoding="utf-8")))
    added = skipped = bad = 0
    seen = set()
    for path, data in load().items():
        changed = False
        for p in data["points"]:
            d = drafts.get(p["id"])
            if not d:
                continue
            seen.add(p["id"])
            if p.get("meaning_ja") and not args.overwrite:
                skipped += 1
                continue
            issues = problems(d.get("meaning_ja", ""), d.get("nuance_ja", ""))
            if issues:
                log(f"{p['id']}: {'; '.join(issues)}")
                bad += 1
                continue
            apply(p, d["meaning_ja"], d["nuance_ja"])
            added += 1
            changed = True
        if changed:
            save(path, data)
    unknown = sorted(set(drafts) - seen)
    if unknown:
        log(f"unknown point ids ignored: {unknown[:10]}{' …' if len(unknown) > 10 else ''}")
    log(f"merged {added}, kept {skipped} existing, rejected {bad}")


def cmd_check(_args) -> None:
    failures = 0
    for path, data in load().items():
        for p in data["points"]:
            if "meaning_ja" in p:
                for issue in problems(p["meaning_ja"], p.get("nuance_ja", "")):
                    log(f"{path.stem} {p['id']}: {issue}")
                    failures += 1
    if failures:
        sys.exit(1)
    log("ok")


SYSTEM = (
    "You write Japanese-only explanations of Japanese grammar points for a monolingual learner dictionary. "
    "Write in your own words; never copy textbooks, websites or grammar dictionaries. No English and no romaji. "
    f"meaning_ja: a concise 文法辞典-style definition, at most {MAX_MEANING} characters. "
    f"nuance_ja: 1–3 sentences in だ・である style, at most {MAX_NUANCE} characters, on usage, register and nuance, "
    "contrasting a near synonym where useful. Use easy Japanese for N5–N3 points. Reply with JSON only."
)


def cmd_draft(args) -> None:
    if not args.endpoint or not args.model:
        raise SystemExit("draft needs --endpoint and --model")
    budget = args.limit
    for path, data in load().items():
        if args.level and path.stem != args.level.lower():
            continue
        for p in data["points"]:
            if budget <= 0:
                return
            if p.get("meaning_ja"):
                continue
            user = (
                f"Point: {p['title']} (N{p['jlpt']})\nStructure: {p['structure']}\nEnglish meaning: {p['meaning']}\n"
                f"English nuance: {p['nuance']}\n"
                'Reply as {"meaning_ja": "...", "nuance_ja": "..."}'
            )
            try:
                out = chat_json(args.endpoint, args.model, SYSTEM, user, args.api_key_env)
            except Exception as e:  # noqa: BLE001 - report and continue with the next point
                log(f"{p['id']}: {e}")
                continue
            issues = problems(str(out.get("meaning_ja", "")), str(out.get("nuance_ja", "")))
            if issues:
                log(f"{p['id']}: rejected ({'; '.join(issues)})")
                continue
            apply(p, str(out["meaning_ja"]), str(out["nuance_ja"]))
            save(path, data)  # after every point, so an interrupted run keeps its work
            budget -= 1
            log(f"{p['id']}: drafted")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    sub.add_parser("check").set_defaults(fn=cmd_check)
    m = sub.add_parser("merge")
    m.add_argument("files", nargs="+")
    m.add_argument("--overwrite", action="store_true", help="replace existing Japanese text")
    m.set_defaults(fn=cmd_merge)
    d = sub.add_parser("draft")
    add_endpoint_args(d)
    d.add_argument("--level", help="only this level, e.g. N3")
    d.set_defaults(fn=cmd_draft)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
