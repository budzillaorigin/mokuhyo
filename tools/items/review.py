"""Human review of LLM-drafted content (CLAUDE.md rule 10, BRIEF §5.5).

Walks through items still marked source="llm" in a source file, shows each one, and lets the reviewer accept
it (→ "verified"), edit it in $EDITOR, skip, or quit. Only this tool flips content to "verified"; the apps show
an "AI-generated" badge on everything else.

Supports grammar sources (tools/packs/grammar/n*.json). Item banks for the exam simulators (Phase 7) use the
same flow.

Run: uv run python items/review.py packs/grammar/n5.json [--reviewer NAME]
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


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("file", type=Path)
    parser.add_argument("--reviewer", default=os.environ.get("USER") or os.environ.get("USERNAME") or "reviewer")
    args = parser.parse_args()

    data = json.loads(args.file.read_text(encoding="utf-8"))
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
