"""Scores a blind listening kit (BRIEF_INTERVIEW_SPEED N6; the kit itself comes from `Mokuhyo --listening-kit`).

    python voices/listening_kit.py score KIT_DIR SHEET.csv [SHEET2.csv ...] [--md OUT.md]

Joins each rater's scoring sheet to KIT_DIR/key.csv and prints, per language and source (piper, os, prerendered, or a
future candidate engine), the mean and count of intelligibility, pronunciation and naturalness, and of the delivery ratings for connected
replies (cadence, pause placement, pitch/stress, continuity, responsiveness), all kept separate, plus
the comments. Blank scores are skipped. Candidates that failed or fell back to another voice (KIT_DIR/missing.csv,
QC 2026-10-30 V5) are listed next to the scores, so a mean is never read without its gaps. Standard library only.
"""
from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

QUALITIES = ("intelligibility_1to5", "pronunciation_1to5", "naturalness_1to5", "cadence_1to5", "pause_placement_1to5",
             "pitch_stress_1to5", "continuity_1to5", "responsiveness_1to5")


def read_key(kit: Path) -> dict[str, dict[str, str]]:
    with (kit / "key.csv").open(encoding="utf-8", newline="") as f:
        return {row["file"]: row for row in csv.DictReader(f)}


def read_missing(kit: Path) -> list[dict[str, str]]:
    path = kit / "missing.csv"
    if not path.is_file():
        return []
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def score(key: dict[str, dict[str, str]], sheets: list[Path]) -> tuple[dict, dict]:
    totals: dict[tuple[str, str], dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
    comments: dict[tuple[str, str], list[str]] = defaultdict(list)
    for sheet in sheets:
        with sheet.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                k = key.get(row.get("file", ""))
                if k is None:
                    print(f"listening_kit: {sheet.name}: unknown file {row.get('file')!r}", file=sys.stderr)
                    continue
                group = (k["language"], k["source"] + (f" {k['voice']}" if k.get("voice") else ""))
                for q in QUALITIES:
                    value = (row.get(q) or "").strip()
                    if value:
                        v = float(value)
                        if not 1 <= v <= 5:
                            raise ValueError(f"{sheet.name}: {row['file']} {q}={value} is outside 1-5")
                        totals[group][q].append(v)
                if (row.get("comments") or "").strip():
                    comments[group].append(f"{row['file']}: {row['comments'].strip()}")
    return totals, comments


def markdown(totals: dict, comments: dict, missing: list[dict[str, str]] | None = None) -> str:
    out = ["| Language | Source | Intelligibility | Pronunciation | Naturalness | Cadence | Pause placement | Pitch/stress | Continuity | Responsiveness |",
           "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for (lang, source), qs in sorted(totals.items()):
        cells = [f"{sum(v) / len(v):.2f} (n={len(v)})" if (v := qs.get(q)) else "—" for q in QUALITIES]
        out.append(f"| {lang} | {source} | " + " | ".join(cells) + " |")
    for group, notes in sorted(comments.items()):
        out.append("")
        out.append(f"{group[0]} / {group[1]}:")
        out.extend(f"- {n}" for n in notes)
    if missing:
        out += ["", "Missing or failed samples (not scored, never replaced by another voice):"]
        out.extend(f"- {m['language']} / {m['source']} {m['voice']} / {m['prompt_id']}: {m['reason']}" for m in missing)
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("score")
    s.add_argument("kit", type=Path)
    s.add_argument("sheets", type=Path, nargs="+")
    s.add_argument("--md", type=Path)
    a = ap.parse_args(argv)
    totals, comments = score(read_key(a.kit), a.sheets)
    md = markdown(totals, comments, read_missing(a.kit))
    sys.stdout.reconfigure(encoding="utf-8")
    print(md, end="")
    if a.md:
        a.md.write_text(md, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
