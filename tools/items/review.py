"""Human review of AI-drafted content, per language and kind (CLAUDE.md rule 7, BRIEF §7).

Everything drafted is source="llm", verified=false and shows the "AI-generated" badge. Only this tool flips
verified=true (recording reviewer and date); rejected items are removed from the bank and logged.

  status   counts of reviewed / unreviewed per language and kind
  next     print the next unreviewed item(s) as JSON (for scripted or editor-based review)
  tui      review interactively in the terminal: [a]ccept, [r]eject, [s]kip, [q]uit
  ingest   apply a verdict file exported by the app's Content Review screen (Settings → About → developer toggle)

Kinds: exam (items/bank/<lang>/{reading,listening}.json, one verdict per passage with its questions),
       opi (opi/<lang>.json: questions, rolePlays, topics).

    uv run python items/review.py status
    uv run python items/review.py tui --language es --kind exam --reviewer "A. Reviewer"
    uv run python items/review.py ingest ~/Downloads/mokuhyo-review-es.json --reviewer "A. Reviewer"
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
BANK = TOOLS / "items" / "bank"
OPI = TOOLS / "opi"
ALIGN = TOOLS / "terms" / "term_alignment.csv"
# Phase 8 content kinds (BRIEF_PHASE8 C-10): kind → (file pattern, list key)
JSON_KINDS = {
    "card": ("culture/{lang}.cards.json", "cards"),
    "pragmatics": ("pragmatics/{lang}.json", "entries"),
    "persona": ("personas/{lang}.json", "personas"),
    "scenario": ("tracks/cuas-base-defense.{lang}.json", "scenarios"),
    "dialogue": ("tracks/cuas-base-defense.{lang}.json", "dialogues"),
    "exemplar": ("exemplars/{lang}.json", "exemplars"),
}
LOG = TOOLS / "items" / "review-log.jsonl"
LANGS = ("ja", "es", "fr", "de", "pt-BR", "ru", "zh-Hans", "ko", "ar", "fa", "id")


DRY_RUN = False


def _write(path: Path, data: dict) -> None:
    if DRY_RUN:
        return
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    os.replace(tmp, path)


def _log(entry: dict) -> None:
    if DRY_RUN:
        return
    with LOG.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def exam_units(lang: str):
    """(file, bank, passage, items) for every passage in the language's banks."""
    for skill in ("reading", "listening"):
        path = BANK / lang / f"{skill}.json"
        if not path.exists():
            continue
        bank = json.loads(path.read_text(encoding="utf-8"))
        by = {}
        for it in bank["items"]:
            by.setdefault(it["passageId"], []).append(it)
        for p in bank["passages"]:
            yield path, bank, p, by.get(p["id"], [])


def opi_units(lang: str):
    path = OPI / f"{lang}.json"
    if not path.exists():
        return
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("questions", "rolePlays", "topics"):
        for x in data[key]:
            yield path, data, key, x


def mark(obj: dict, reviewer: str) -> None:
    obj["verified"] = True
    obj["reviewedBy"] = reviewer
    obj["reviewed"] = dt.datetime.now(dt.UTC).date().isoformat()


def apply_exam(lang: str, verdicts: dict[str, dict], reviewer: str) -> tuple[int, int]:
    accepted = rejected = 0
    for skill in ("reading", "listening"):
        path = BANK / lang / f"{skill}.json"
        if not path.exists():
            continue
        bank = json.loads(path.read_text(encoding="utf-8"))
        drop = set()
        for p in bank["passages"]:
            v = verdicts.get(p["id"])
            if not v:
                continue
            if v["verdict"] == "accept":
                for field in ("title", "body"):
                    if field in v.get("edits", {}):
                        p[field] = v["edits"][field]
                mark(p, reviewer)
                for it in bank["items"]:
                    if it["passageId"] == p["id"]:
                        mark(it, reviewer)
                accepted += 1
            elif v["verdict"] == "reject":
                drop.add(p["id"])
                rejected += 1
            _log({"kind": "exam", "language": lang, "id": p["id"], "verdict": v["verdict"], "note": v.get("note", ""), "reviewer": reviewer,
                  "at": dt.datetime.now(dt.UTC).isoformat()})
        if drop:
            bank["passages"] = [p for p in bank["passages"] if p["id"] not in drop]
            bank["items"] = [it for it in bank["items"] if it["passageId"] not in drop]
        _write(path, bank)
    return accepted, rejected


def apply_opi(lang: str, verdicts: dict[str, dict], reviewer: str) -> tuple[int, int]:
    path = OPI / f"{lang}.json"
    if not path.exists():
        return 0, 0
    data = json.loads(path.read_text(encoding="utf-8"))
    accepted = rejected = 0
    for key in ("questions", "rolePlays", "topics"):
        keep = []
        for x in data[key]:
            v = verdicts.get(x["id"])
            if v and v["verdict"] == "reject":
                rejected += 1
                _log({"kind": "opi", "language": lang, "id": x["id"], "verdict": "reject", "note": v.get("note", ""), "reviewer": reviewer})
                continue
            if v and v["verdict"] == "accept":
                x.update(v.get("edits", {}))
                mark(x, reviewer)
                accepted += 1
                _log({"kind": "opi", "language": lang, "id": x["id"], "verdict": "accept", "reviewer": reviewer})
            keep.append(x)
        data[key] = keep
    _write(path, data)
    return accepted, rejected


def apply_terms(lang: str, verdicts: dict[str, dict], reviewer: str) -> tuple[int, int]:
    """Term alignments (docs/TERM_PIPELINE.md step 5): accept → status approved, approvedBy, badge cleared (a model-proposed
    term also gets the acceptance note the validator requires); reject → back to draft with the note. Ids are seed ids."""
    import csv
    if not ALIGN.exists():
        return 0, 0
    with open(ALIGN, encoding="utf-8", newline="") as f:
        rd = csv.DictReader(f)
        cols = rd.fieldnames or []
        rows = list(rd)
    accepted = rejected = 0
    for r in rows:
        v = verdicts.get(r["seed_id"]) if r["lang"] == lang else None
        if not v:
            continue
        if v["verdict"] == "accept":
            for field in ("term", "definition"):
                if field in v.get("edits", {}):
                    r[field] = v["edits"][field]
            r.update(status="approved", approvedBy=reviewer, badge="")
            if not r["term_source_id"]:
                r["notes"] = (r["notes"] + " | " if r["notes"] else "") + f"model-proposed term accepted by {reviewer}" + \
                    (f": {v['note']}" if v.get("note") else "")
            accepted += 1
        elif v["verdict"] == "reject":
            r.update(status="draft", approvedBy="", badge=r["badge"] or ("unreviewed" if r["term_source_id"] else "unconfirmed-term"))
            r["notes"] = (r["notes"] + " | " if r["notes"] else "") + f"rejected by {reviewer}: {v.get('note', '')}"
            rejected += 1
        _log({"kind": "term", "language": lang, "id": r["seed_id"], "verdict": v["verdict"], "note": v.get("note", ""), "reviewer": reviewer,
              "at": dt.datetime.now(dt.UTC).isoformat()})
    if not DRY_RUN:
        with open(ALIGN, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, cols)
            w.writeheader()
            w.writerows(rows)
    return accepted, rejected


def apply_json(kind: str):
    """Culture cards, pragmatics entries, personas, track scenarios and dialogues: accept → verified (with edits);
    reject → removed from the source file (the log keeps the verdict)."""
    pattern, key = JSON_KINDS[kind]

    def apply(lang: str, verdicts: dict[str, dict], reviewer: str) -> tuple[int, int]:
        path = TOOLS / pattern.format(lang=lang)
        if not path.exists():
            return 0, 0
        data = json.loads(path.read_text(encoding="utf-8"))
        accepted = rejected = 0
        keep = []
        for x in data.get(key, []):
            v = verdicts.get(x["id"])
            if v and v["verdict"] == "reject":
                rejected += 1
                _log({"kind": kind, "language": lang, "id": x["id"], "verdict": "reject", "note": v.get("note", ""), "reviewer": reviewer})
                continue
            if v and v["verdict"] == "accept":
                x.update(v.get("edits", {}))
                mark(x, reviewer)
                accepted += 1
                _log({"kind": kind, "language": lang, "id": x["id"], "verdict": "accept", "reviewer": reviewer})
            keep.append(x)
        data[key] = keep
        _write(path, data)
        return accepted, rejected

    return apply


def applier(kind: str):
    if kind == "exam":
        return apply_exam
    if kind == "opi":
        return apply_opi
    if kind == "term":
        return apply_terms
    if kind in JSON_KINDS:
        return apply_json(kind)
    raise SystemExit(f"unknown review kind {kind}")


def cmd_status(_args) -> int:
    print(f"{'lang':8} {'exam passages':>22} {'opi items':>18}")
    for lang in LANGS:
        ex = list(exam_units(lang))
        op = list(opi_units(lang))
        print(f"{lang:8} {sum(1 for u in ex if u[2].get('verified')):>8} / {len(ex):<5} verified  "
              f"{sum(1 for u in op if u[3].get('verified')):>6} / {len(op):<5} verified")
    return 0


def cmd_next(args) -> int:
    out = []
    if args.kind == "exam":
        for _, _, p, items in exam_units(args.language):
            if not p.get("verified"):
                out.append({"passage": p, "items": items})
            if len(out) >= args.n:
                break
    else:
        for _, _, key, x in opi_units(args.language):
            if not x.get("verified"):
                out.append({"kind": key, **x})
            if len(out) >= args.n:
                break
    print(json.dumps(out, ensure_ascii=False, indent=1))
    return 0


def cmd_tui(args) -> int:
    verdicts: dict[str, dict] = {}
    units = [(p, items) for _, _, p, items in exam_units(args.language) if not p.get("verified")] if args.kind == "exam" else \
        [(x, [key]) for _, _, key, x in opi_units(args.language) if not x.get("verified")]
    for obj, extra in units:
        print("\n" + "=" * 80)
        if args.kind == "exam":
            print(f"{obj['id']}  ILR {obj['level']}  {obj['textType']}  — {obj['title']}\n")
            print(obj.get("body") or "\n".join(f"{x['speaker']} ({x['voice']}): {x['text']}" for x in obj.get("script", [])))
            for it in extra:
                print(f"\n  [{it['type']}] {it['stem']}")
                for i, c in enumerate(it["choices"]):
                    print(f"   {'*' if i == it['answer'] else ' '} {i + 1}. {c}")
        else:
            print(json.dumps(obj, ensure_ascii=False, indent=1))
        choice = input("\n[a]ccept [r]eject [s]kip [q]uit > ").strip().lower()[:1]
        if choice == "q":
            break
        if choice in ("a", "r"):
            note = input("note (optional) > ").strip() if choice == "r" else ""
            verdicts[obj["id"]] = {"verdict": "accept" if choice == "a" else "reject", "note": note}
    fn = apply_exam if args.kind == "exam" else apply_opi
    a, r = fn(args.language, verdicts, args.reviewer)
    print(f"{a} accepted, {r} rejected")
    return 0


def cmd_ingest(args) -> int:
    global DRY_RUN
    DRY_RUN = args.dry_run
    data = json.loads(Path(args.file).read_text(encoding="utf-8"))
    if data.get("format") != "mokuhyo-review/1":
        print("not a Mokuhyo review export (format mokuhyo-review/1)", file=sys.stderr)
        return 1
    by: dict[tuple[str, str], dict[str, dict]] = {}
    for v in data["verdicts"]:
        by.setdefault((v["language"], v["kind"]), {})[v["id"]] = v
    total = [0, 0]
    for (lang, kind), verdicts in by.items():
        a, r = applier(kind)(lang, verdicts, args.reviewer or data.get("reviewer", "unknown"))
        total[0] += a
        total[1] += r
        print(f"{lang} {kind}: {a} accepted, {r} rejected")
    if args.dry_run:
        print("(dry run: nothing written)")
    elif any(k in ("term", "scenario", "dialogue") for _, k in by):
        print("Rebuild the track and packs: uv run --group content python tracks/build_track.py assemble --language <lang> && "
              "uv run --group content python packs/build_packs.py --language <lang>")
    return 0


QUEUE = TOOLS / "items" / "suggestions-queue.jsonl"


def cmd_suggestions(args) -> int:
    """Learner suggestions and flags (BRIEF_PHASE8 N-10) → the curator's queue (items/suggestions-queue.jsonl), by id."""
    data = json.loads(Path(args.file).read_text(encoding="utf-8"))
    if data.get("format") != "mokuhyo-suggestions/1":
        print("not a Mokuhyo suggestions file (format mokuhyo-suggestions/1)", file=sys.stderr)
        return 1
    seen = set()
    if QUEUE.exists():
        seen = {json.loads(line)["id"] for line in QUEUE.read_text(encoding="utf-8").splitlines() if line.strip()}
    new = [x for x in data.get("suggestions", []) if x.get("id") and x["id"] not in seen]
    bad = [x for x in new if x.get("type") not in ("suggest_term", "flag") or not str(x.get("text", "")).strip()]
    if bad:
        print(f"{len(bad)} malformed suggestion(s) skipped", file=sys.stderr)
    new = [x for x in new if x not in bad]
    if not args.dry_run and new:
        with QUEUE.open("a", encoding="utf-8") as f:
            for x in new:
                f.write(json.dumps(dict(x, queued=dt.datetime.now(dt.UTC).isoformat()), ensure_ascii=False) + "\n")
    for x in new:
        print(f"  {x['lang']:8} {x['type']:13} {x['targetKind']}:{x.get('targetId', '')} — {x['text'][:80]}")
    print(f"suggestions: {len(new)} queued ({len(data.get('suggestions', [])) - len(new)} already queued or skipped)")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    for name in ("next", "tui"):
        p = sub.add_parser(name)
        p.add_argument("--language", required=True, choices=LANGS)
        p.add_argument("--kind", default="exam", choices=("exam", "opi"))
        p.add_argument("--n", type=int, default=5)
        p.add_argument("--reviewer", default=os.environ.get("USER", "reviewer"))
    p = sub.add_parser("ingest")
    p.add_argument("file")
    p.add_argument("--reviewer")
    p.add_argument("--dry-run", action="store_true")
    p = sub.add_parser("suggestions", help="queue learner suggestions/flags exported by the app")
    p.add_argument("file")
    p.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    return {"status": cmd_status, "next": cmd_next, "tui": cmd_tui, "ingest": cmd_ingest, "suggestions": cmd_suggestions}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
