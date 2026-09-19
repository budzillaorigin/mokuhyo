"""Human review of LLM-drafted content (CLAUDE.md rule 10, BRIEF §5.5).

Walks through items still marked source="llm" in a source file, shows each one, and lets the reviewer accept
it (→ "verified"), edit it in $EDITOR, skip, or quit. Only this tool flips content to "verified"; the apps show
an "AI-generated" badge on everything else.

Supports grammar sources (tools/packs/grammar/n*.json) and exam item banks (tools/items/bank/*.json, format in
docs/CONTENT_PACKS.md "Exam item banks"). In a bank, a passage and its items are reviewed together: accepting sets
verified=true and reviewed {by, on} on all of them. There `source` stays "llm" as provenance and the badge keys
off `verified`.

Run: uv run python items/review.py packs/grammar/n5.json [--reviewer NAME]
     uv run python items/review.py items/bank/dlpt_reading.json [--reviewer NAME]
     uv run python items/review.py packs/readers/stories/n4.json [--reviewer NAME]   # graded readers (bank-style)

Verdicts made on the phone (Me → Content review, BRIEF_V2 G-16, DECISIONS D-118) are applied with:
     uv run python items/review.py --ingest verdicts.json [--reviewer NAME] [--dry-run]

The verdicts file ({"format": "tsumugi-review-verdicts", "version": 1, "reviewer", "exportedAt", "verdicts": [{kind,
id, verdict, notes, edits, decidedAt}]}) is applied to the source files: "accept" verifies (grammar, dialogues,
scenarios and reader passages flip source to "verified"; exam banks set verified=true, D-034), "edit" applies the
changed fields and then verifies, "reject" records {"rejected": {by, on, notes}} and leaves the item unverified for
the author to redo. Kana mnemonics (kind "kana_mnemonic") live in Kotlin: accepts are written to
shared/src/commonMain/kotlin/app/tsumugi/kana/KanaMnemonicsReviewed.kt and edits replace the text in KanaMnemonics.kt.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

EDITABLE = ("title", "structure", "meaning", "nuance", "mistakes", "examples")


def show(point: dict, index: int, total: int) -> None:
    print("\n" + "=" * 72)
    print(f"[{index}/{total}] {point['id']}  ·  N{point['jlpt']}  ·  {point['title']}")
    print(f"Structure: {point['structure']}")
    print(f"Meaning:   {point['meaning']}")
    print(f"\n{point['nuance']}\n")
    for m in point.get("mistakes", []):
        print(f"  ! {m}")
    for ex in point.get("examples", []):
        print(f"  • {ex['ja']}\n    {ex['en']}")


def edit(point: dict) -> dict:
    editor = os.environ.get("EDITOR", "notepad" if os.name == "nt" else "vi")
    fields = {k: point[k] for k in EDITABLE if k in point}
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(fields, f, ensure_ascii=False, indent=2)
        path = f.name
    subprocess.call([editor, path])
    try:
        updated = json.loads(Path(path).read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"Not valid JSON ({e}); keeping the original.")
        return point
    finally:
        os.unlink(path)
    return {**point, **{k: v for k, v in updated.items() if k in EDITABLE}}


def show_unit(passage: dict | None, items: list[dict], index: int, total: int) -> None:
    print("\n" + "=" * 72)
    head = passage or items[0]
    kind = head.get("textType") or head.get("type", "")
    print(f"[{index}/{total}] {head['id']}  ·  {head.get('exam')} {head.get('level')}  ·  {kind}")
    if passage:
        print(f"{passage.get('title', '')}\n")
        if passage.get("body"):
            print(passage["body"])
        for line in passage.get("script") or []:
            print(f"  {line.get('speaker')} ({line.get('voice')}): {line.get('text')}")
    for it in items:
        print(f"\n  {it['id']}  [{it.get('type')}]  {it.get('stem')}")
        for n, choice in enumerate(it.get("choices", [])):
            mark = "*" if n == it.get("answer") else " "
            print(f"   {mark} {chr(65 + n)}. {choice}")
        if it.get("explanation"):
            print(f"    Why: {it['explanation']}")


def edit_json(value):
    """Open value as JSON in $EDITOR and return the edited value (the original if it no longer parses)."""
    editor = os.environ.get("EDITOR", "notepad" if os.name == "nt" else "vi")
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        path = f.name
    subprocess.call([editor, path])
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"Not valid JSON ({e}); keeping the original.")
        return value
    finally:
        os.unlink(path)


def bank_units(data: dict) -> list[tuple[int | None, list[int]]]:
    """(passage index or None, item indexes) for every passage or standalone item still needing review."""
    passages, items = data.get("passages", []), data.get("items", [])
    units: list[tuple[int | None, list[int]]] = []
    for pi, p in enumerate(passages):
        idx = [i for i, it in enumerate(items) if it.get("passageId") == p["id"]]
        if not p.get("verified") or any(not items[i].get("verified") for i in idx):
            units.append((pi, idx))
    units += [(None, [i]) for i, it in enumerate(items) if not it.get("passageId") and not it.get("verified")]
    return units


def accept_unit(data: dict, unit: tuple[int | None, list[int]], reviewer: str, on: str) -> None:
    pi, idx = unit
    targets = ([data["passages"][pi]] if pi is not None else []) + [data["items"][i] for i in idx]
    for obj in targets:
        obj["verified"] = True
        obj["reviewed"] = {"by": reviewer, "on": on}


def review_bank(path: Path, data: dict, reviewer: str) -> int:
    passages, items = data.get("passages", []), data.get("items", [])
    units = bank_units(data)
    print(f"{len(units)} passages/items still need review in {path}.")
    done = f"Re-validate with: uv run python items/gen_dlpt.py validate {path}"

    def save() -> None:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for n, (pi, idx) in enumerate(units, start=1):
        while True:
            show_unit(passages[pi] if pi is not None else None, [items[i] for i in idx], n, len(units))
            choice = input("\n[a]ccept as verified · [e]dit · [s]kip · [q]uit > ").strip().lower()
            if choice == "e":
                unit = {"passage": passages[pi] if pi is not None else None, "items": [items[i] for i in idx]}
                edited = edit_json(unit)
                ok = isinstance(edited, dict) and isinstance(edited.get("items"), list)
                if not ok or len(edited["items"]) != len(idx):
                    print("Edits must keep the {passage, items} shape and item count; keeping the original.")
                    continue
                if pi is not None and isinstance(edited.get("passage"), dict):
                    passages[pi] = {**edited["passage"], "id": passages[pi]["id"]}
                for i, it in zip(idx, edited["items"], strict=True):
                    items[i] = {**it, "id": items[i]["id"]}
                continue
            if choice == "a":
                accept_unit(data, (pi, idx), reviewer, dt.datetime.now(dt.UTC).date().isoformat())
                save()
            if choice in ("a", "s"):
                break
            if choice == "q":
                save()
                print(f"Saved. {done}")
                return 0
    save()
    print(f"All done. {done}")
    return 0


# --- Ingesting verdicts from the app (G-16) -----------------------------------------------------------------

TOOLS = Path(__file__).resolve().parent.parent
VERDICTS_FORMAT = "tsumugi-review-verdicts"

# Fields an in-app edit may change, per kind (the app offers exactly these).
EDITABLE_BY_KIND = {
    "grammar_point": ("title", "structure", "meaning", "nuance"),
    "exam_passage": ("title", "body"),
    "exam_item": ("stem", "explanation"),
    "dialogue": ("title", "topic"),
    "scenario": ("titleEn", "titleJa", "setting"),
    "reader_passage": ("title", "body"),
    "kana_mnemonic": ("mnemonic",),
}


def _sources(root: Path, kind: str) -> list[tuple[Path, str]]:
    """(file, list key) pairs that may hold items of [kind], relative to the tools directory [root]."""
    if kind == "grammar_point":
        return [(f, "points") for f in sorted((root / "packs" / "grammar").glob("n*.json"))]
    if kind in ("exam_passage", "exam_item"):
        key = "passages" if kind == "exam_passage" else "items"
        return [(f, key) for f in sorted((root / "items" / "bank").glob("*.json"))]
    if kind == "dialogue":
        return [(root / "packs" / "listening" / "dialogues.json", "dialogues")]
    if kind == "scenario":
        return [(root / "packs" / "speaking" / "scenarios.json", "scenarios")]
    if kind == "reader_passage":
        return [(f, "passages") for f in sorted((root / "packs" / "readers" / "stories").glob("*.json"))]
    return []


def _kotlin_string(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def _ingest_kana(root: Path, entries: list[dict], reviewer: str, on: str, dry_run: bool) -> list[str]:
    """Kana mnemonics are compiled into the app: accepted ones go into KanaMnemonicsReviewed.kt."""
    kana_dir = root.parent / "shared" / "src" / "commonMain" / "kotlin" / "app" / "tsumugi" / "kana"
    mnemonics = kana_dir / "KanaMnemonics.kt"
    reviewed_file = kana_dir / "KanaMnemonicsReviewed.kt"
    report: list[str] = []
    if not mnemonics.exists():
        return [f"kana_mnemonic: {mnemonics} not found; skipped {len(entries)} verdicts"]
    source = mnemonics.read_text(encoding="utf-8")
    reviewed: set[str] = set()
    if reviewed_file.exists():
        reviewed = set(re.findall(r'^\s*"([^"]+)",\s*$', reviewed_file.read_text(encoding="utf-8"), re.MULTILINE))
    for v in entries:
        kana, verdict = v["id"], v["verdict"]
        line = re.compile(r'("' + re.escape(kana) + r'" to ")((?:[^"\\]|\\.)*)(")')
        if not line.search(source):
            report.append(f"kana_mnemonic {kana}: not found in KanaMnemonics.kt")
            continue
        if verdict == "edit" and v.get("edits", {}).get("mnemonic"):
            new_text = _kotlin_string(v["edits"]["mnemonic"])
            source = line.sub(lambda m, text=new_text: m.group(1) + text + m.group(3), source, count=1)
        if verdict in ("accept", "edit"):
            reviewed.add(kana)
            report.append(f"kana_mnemonic {kana}: {verdict}")
        else:
            reviewed.discard(kana)
            report.append(f"kana_mnemonic {kana}: rejected ({v.get('notes', '')}) - rewrite it in KanaMnemonics.kt")
    if not dry_run:
        mnemonics.write_text(source, encoding="utf-8", newline="\n")
        body = "".join(f'    "{k}",\n' for k in sorted(reviewed))
        reviewed_file.write_text(
            "package app.tsumugi.kana\n\n"
            "// Generated by tools/items/review.py --ingest (kind \"kana_mnemonic\"). Do not edit by hand.\n"
            "// Kana whose mnemonic a human reviewed: their source is \"verified\" and the AI badge is off (rule 10, D-117).\n"
            f"internal val REVIEWED_KANA_MNEMONICS: Set<String> = setOf(\n{body})\n",
            encoding="utf-8",
            newline="\n",
        )
    return report


def _apply_edits(obj: dict, edits: dict, allowed: tuple[str, ...]) -> list[str]:
    """Applies string edits to [obj]; list/dict fields take JSON text. Returns the fields it could not apply."""
    skipped = []
    for field, value in edits.items():
        if field not in allowed:
            skipped.append(field)
            continue
        current = obj.get(field)
        if isinstance(current, (list, dict)):
            try:
                obj[field] = json.loads(value)
            except json.JSONDecodeError:
                skipped.append(field)
        else:
            obj[field] = value
    return skipped


def ingest(verdicts_path: Path, reviewer: str | None = None, root: Path = TOOLS, dry_run: bool = False) -> list[str]:
    """Applies an app verdicts file to the source files under [root] (the tools directory). Returns a report."""
    data = json.loads(verdicts_path.read_text(encoding="utf-8"))
    if data.get("format") != VERDICTS_FORMAT:
        raise SystemExit(f"{verdicts_path} is not a Tsumugi verdicts file (format {data.get('format')!r})")
    if int(data.get("version", 0)) > 1:
        raise SystemExit(f"{verdicts_path} is version {data.get('version')}; update review.py")
    by = reviewer or data.get("reviewer") or "reviewer"
    report: list[str] = []
    grouped: dict[str, list[dict]] = {}
    for v in data.get("verdicts", []):
        if v.get("verdict") not in ("accept", "edit", "reject"):
            report.append(f"{v.get('kind')} {v.get('id')}: unknown verdict {v.get('verdict')!r}; skipped")
            continue
        grouped.setdefault(v.get("kind", ""), []).append(v)

    for kind, entries in grouped.items():
        on_default = dt.datetime.now(dt.UTC).date().isoformat()
        if kind == "kana_mnemonic":
            report += _ingest_kana(root, entries, by, on_default, dry_run)
            continue
        files = _sources(root, kind)
        if not files:
            report += [f"{kind} {v['id']}: unknown kind; skipped" for v in entries]
            continue
        loaded = {f: json.loads(f.read_text(encoding="utf-8")) for f, _ in files if f.exists()}
        changed: set[Path] = set()
        for v in entries:
            on = (v.get("decidedAt") or "")[:10] or on_default
            target = None
            for f, key in files:
                doc = loaded.get(f)
                if doc is None:
                    continue
                for obj in doc.get(key, []):
                    if obj.get("id") == v["id"]:
                        target = (f, doc, obj)
                        break
                if target:
                    break
            if target is None:
                report.append(f"{kind} {v['id']}: not found in the sources; skipped")
                continue
            f, doc, obj = target
            notes = v.get("notes", "")
            if v["verdict"] == "reject":
                obj["rejected"] = {"by": by, "on": on, "notes": notes}
                report.append(f"{kind} {v['id']}: rejected")
            else:
                if v["verdict"] == "edit":
                    skipped = _apply_edits(obj, v.get("edits", {}), EDITABLE_BY_KIND.get(kind, ()))
                    if skipped:
                        report.append(f"{kind} {v['id']}: fields not applied: {', '.join(skipped)}")
                obj.pop("rejected", None)
                if kind in ("exam_passage", "exam_item"):
                    obj["verified"] = True  # D-034: banks keep source as provenance
                else:
                    obj["source"] = "verified"
                obj["reviewed"] = {"by": by, "on": on, **({"notes": notes} if notes else {})}
                report.append(f"{kind} {v['id']}: {v['verdict']}ed")
            changed.add(f)
        if not dry_run:
            for f in sorted(changed):
                f.write_text(json.dumps(loaded[f], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("file", type=Path, nargs="?")
    parser.add_argument("--reviewer", default=os.environ.get("USER") or os.environ.get("USERNAME") or "reviewer")
    parser.add_argument("--ingest", type=Path, help="apply a verdicts JSON exported from the app (Me -> Content review)")
    parser.add_argument("--dry-run", action="store_true", help="with --ingest: report only, change nothing")
    args = parser.parse_args()

    if args.ingest:
        for line in ingest(args.ingest, args.reviewer if "--reviewer" in sys.argv else None, dry_run=args.dry_run):
            print(line)
        print("Rebuild the packs (uv run python packs/build_all.py) and re-validate the banks.")
        return 0
    if args.file is None:
        parser.error("give a source file to review, or --ingest verdicts.json")

    data = json.loads(args.file.read_text(encoding="utf-8"))
    if "points" not in data:
        return review_bank(args.file, data, args.reviewer)
    points = data["points"]
    pending = [i for i, p in enumerate(points) if p.get("source", data.get("source", "llm")) != "verified"]
    print(f"{len(pending)} of {len(points)} items still need review in {args.file}.")

    def save() -> None:
        args.file.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for n, i in enumerate(pending, start=1):
        while True:
            show(points[i], n, len(pending))
            choice = input("\n[a]ccept as verified · [e]dit · [s]kip · [q]uit > ").strip().lower()
            if choice == "e":
                points[i] = edit(points[i])
                continue
            if choice == "a":
                points[i]["source"] = "verified"
                points[i]["reviewed"] = {"by": args.reviewer, "on": dt.datetime.now(dt.UTC).date().isoformat()}
                save()
            if choice in ("a", "s"):
                break
            if choice == "q":
                save()
                print("Saved. Rebuild the pack with: uv run python packs/build_grammar.py")
                return 0
    save()
    print("All done. Rebuild the pack with: uv run python packs/build_grammar.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
