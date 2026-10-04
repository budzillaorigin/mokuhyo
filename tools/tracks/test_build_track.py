"""Tests for tracks/build_track.py (no network). Run: uv run --group content python tracks/test_build_track.py"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_track as B


def test_probe_parsing_tolerates_ilr_prefix_and_bold() -> None:
    """The model writes "**ILR 2+**" and "Level check"; every probe used to be dropped (C-03 fix)."""
    text = ("1. **1** | **level_check** | **ILR 2** | ¿Podría describir su trabajo? | Could you describe your work?\n"
            "2 | Probe | ILR 2+ | Compare los dos procedimientos. | Compare the two procedures.\n"
            "3 | probe | 3 | ¿Qué haría usted? | What would you do?\n"
            "4 | probe | ILR 4 | Pregunta fuera de rango. | Out of range.\n"
            "5 | probe | 2 | 基地について説明してください。 | Wrong script for Spanish.\n")
    got = B.parse_probes(text, "es")
    assert [(p["n"], p["phase"], p["level"]) for p in got] == [(1, "level_check", "2"), (2, "probe", "2+"), (3, "probe", "3")], got


if __name__ == "__main__":
    for t in [test_probe_parsing_tolerates_ilr_prefix_and_bold]:
        t()
        print("ok", t.__name__)
