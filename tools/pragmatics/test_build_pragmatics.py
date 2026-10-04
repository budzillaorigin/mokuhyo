"""Tests for pragmatics/build_pragmatics.py (no network). Run: uv run --group content python pragmatics/test_build_pragmatics.py"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_pragmatics as P


def test_clean_strips_glosses_and_drops_english() -> None:
    """Inline English glosses are stripped; code-switched or English examples are dropped (C-07 fix)."""
    ex = lambda say, dont: {"situation": "s", "say": say, "dontSay": dont, "why": "w"}
    entries = [
        {"id": "a", "examples": [ex("Como foi seu fim de semana? (How was your weekend?)", "Você é religioso? (Are you religious?)")]},
        {"id": "b", "examples": [ex("Veo tu perspectiva, pero perhaps we should think about it differently.", "No.")]},
    ]
    out = P.clean_entries(entries, "pt-BR")
    assert [e["id"] for e in out] == ["a"] and out[0]["examples"][0]["say"] == "Como foi seu fim de semana?"
    assert P.clean_entries([entries[1]], "es") == []
    assert P.clean_entries([{"id": "c", "examples": [ex("¿Puedo ayudar a limpiar?", "Yo no limpio.")]}], "es")  # "a" is Spanish


if __name__ == "__main__":
    for t in [test_clean_strips_glosses_and_drops_english]:
        t()
        print("ok", t.__name__)
