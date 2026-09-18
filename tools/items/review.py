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
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
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


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("file", type=Path)
    parser.add_argument("--reviewer", default=os.environ.get("USER") or os.environ.get("USERNAME") or "reviewer")
    args = parser.parse_args()

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
