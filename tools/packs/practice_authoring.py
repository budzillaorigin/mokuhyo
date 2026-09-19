"""Shared plumbing for the practice-pack author scripts (speaking/author_scenarios.py, listening/author_dialogues.py).

Each author script has three sources of entries, merged into its JSON file (scenarios.json / dialogues.json), which
build_practice.py reads and items/review.py writes verdicts into:

1. entries written in the script itself (compact Python, the original launch content),
2. batch files in `<dir>/batches/*.json` ({"source": "llm", "<key>": [...]}, the same entry format as the JSON file;
   Claude-drafted launch batches and endpoint drafts),
3. entries already in the JSON file.

Merging never duplicates an id: an authored entry replaces the JSON copy unless a human has reviewed that copy
(source "verified", or "reviewed"/"rejected" set by items/review.py, on the entry or on one of its dialogue lines),
which is kept unless --force. Entries found
only in the JSON file are kept, after the authored ones. The same id in two authored sources is an error.

`draft` asks any OpenAI-compatible endpoint (e.g. the owner's Ollama) for new entries, validates each with the
build's own checks (JMdict resolution included), gives it an unused id and appends it to
`<dir>/batches/llm-drafts.json`, then merges. Everything drafted is source "llm" (CLAUDE.md rule 10).
"""

from __future__ import annotations

import json
import re
import sys
import urllib.error
from collections.abc import Callable
from pathlib import Path

PACKS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(PACKS_DIR))
sys.path.insert(0, str(PACKS_DIR.parent / "items"))

import build_practice
from gen_dlpt import chat

REVIEW_KEYS = ("reviewed", "rejected")


def reviewed(entry: dict) -> bool:
    """A human touched this entry: verified, reviewed or rejected, itself or (as a drill item, D-247) one of its lines."""
    lines = entry.get("lines") if isinstance(entry.get("lines"), list) else []
    return entry.get("source") == "verified" or any(k in entry for k in REVIEW_KEYS) or any(
        isinstance(ln, dict) and (ln.get("source") == "verified" or any(k in ln for k in REVIEW_KEYS)) for ln in lines
    )


def load_batches(batch_dir: Path, key: str) -> list[tuple[str, dict]]:
    """(file name, entry) for every entry in the batch files, in file-name order."""
    out = []
    for path in sorted(batch_dir.glob("*.json")) if batch_dir.exists() else []:
        doc = json.loads(path.read_text(encoding="utf-8"))
        source = doc.get("source", "llm")
        for e in doc.get(key, []):
            out.append((path.name, {**e, "source": e.get("source", source)}))
    return out


def merge(out: Path, key: str, authored: list[tuple[str, dict]], note: str, force: bool = False) -> dict[str, int]:
    """Writes the merged JSON file; returns counts (authored, kept-reviewed, kept-only-in-json, total)."""
    seen: dict[str, str] = {}
    for origin, e in authored:
        if e["id"] in seen:
            raise SystemExit(f"{key}: id {e['id']!r} is in both {seen[e['id']]} and {origin}")
        seen[e["id"]] = origin
    existing = json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}
    doc_source = existing.get("source", "llm")
    old = {e["id"]: {**e, "source": e.get("source", doc_source)} for e in existing.get(key, [])}
    merged, kept_reviewed = [], 0
    for _, e in authored:
        prev = old.get(e["id"])
        if prev is not None and reviewed(prev) and not force:
            merged.append(prev)
            kept_reviewed += 1
        else:
            merged.append({**e, "source": e.get("source", "llm")})
    leftovers = [e for i, e in old.items() if i not in seen]
    merged += leftovers
    doc = {"source": "llm", "note": note, key: merged}
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return {"authored": len(authored), "keptReviewed": kept_reviewed, "keptJsonOnly": len(leftovers), "total": len(merged)}


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:40] or "item"


def unique_id(base: str, taken: set[str]) -> str:
    if base not in taken:
        return base
    n = 2
    while f"{base}-{n}" in taken:
        n += 1
    return f"{base}-{n}"


def draft(
    *,
    args,
    key: str,
    out: Path,
    batch_dir: Path,
    messages: Callable[[list[str]], list[dict]],
    normalize: Callable[[dict], dict],
    check: Callable[[dict, build_practice.Dictionary], object],
    id_base: Callable[[dict], str],
) -> int:
    """Drafts args.count entries through args.endpoint/args.model and appends the valid ones to llm-drafts.json."""
    drafts_path = batch_dir / "llm-drafts.json"
    drafts = json.loads(drafts_path.read_text(encoding="utf-8")) if drafts_path.exists() else {
        "source": "llm", "note": f"Drafted by {args.model} via {args.endpoint}; unreviewed.", key: [],
    }
    taken = {e["id"] for _, e in load_batches(batch_dir, key)}
    if out.exists():
        taken |= {e["id"] for e in json.loads(out.read_text(encoding="utf-8")).get(key, [])}
    titles = [e.get("title") or e.get("titleEn", "") for e in drafts[key]]
    dic = build_practice.Dictionary()
    added = 0
    try:
        for k in range(args.count):
            for attempt in range(1, args.retries + 2):
                try:
                    raw = chat(args.endpoint, args.model, messages(titles), None, args.temperature, args.timeout)
                except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, IndexError) as e:
                    print(f"[{k + 1}/{args.count}] attempt {attempt}: request failed: {e}", file=sys.stderr)
                    continue
                try:
                    entry = normalize(raw)
                    entry["id"] = unique_id(id_base(entry), taken)
                    check(entry, dic)
                except (build_practice.BuildError, KeyError, TypeError, ValueError, AttributeError) as e:
                    print(f"[{k + 1}/{args.count}] attempt {attempt}: rejected: {e}", file=sys.stderr)
                    continue
                entry["source"] = "llm"
                drafts[key].append(entry)
                taken.add(entry["id"])
                titles.append(entry.get("title") or entry.get("titleEn", ""))
                added += 1
                print(f"[{k + 1}/{args.count}] {entry['id']}")
                break
    finally:
        dic.close()
    batch_dir.mkdir(parents=True, exist_ok=True)
    drafts_path.write_text(json.dumps(drafts, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"added {added} of {args.count} to {drafts_path.name} (source=llm)")
    return 0 if added == args.count else 1


def add_draft_args(p) -> None:
    p.add_argument("--endpoint", required=True, help="OpenAI-compatible base URL, e.g. http://localhost:11434/v1")
    p.add_argument("--model", required=True)
    p.add_argument("--count", type=int, default=1)
    p.add_argument("--temperature", type=float, default=0.8)
    p.add_argument("--timeout", type=float, default=600)
    p.add_argument("--retries", type=int, default=2)
