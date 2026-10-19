"""Tests for voices/textsplit.py. Run: uv run python voices/test_textsplit.py"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from textsplit import chunks


def test_sentences_are_grouped_under_the_limit() -> None:
    text = "Uno dos tres. " * 40
    got = chunks(text, 220)
    assert all(len(c) <= 220 for c in got), [len(c) for c in got]
    assert " ".join(got).split() == text.split()


def test_one_overlong_sentence_is_split() -> None:
    """QC 2026-10-19: a single 599-character sentence came back as one piece."""
    sentence = ", ".join(["el equipo revisó el perímetro norte y confirmó la posición"] * 10) + "."
    assert len(sentence) > 500
    got = chunks(sentence, 220)
    assert len(got) > 1 and all(len(c) <= 220 for c in got), [len(c) for c in got]
    assert " ".join(got).replace(" ", "") == sentence.replace(" ", "")


def test_no_punctuation_falls_back_to_words_then_characters() -> None:
    words = " ".join(["palabra"] * 80)
    assert all(len(c) <= 220 for c in chunks(words, 220))
    cjk = "基地の警備について説明します" * 30  # no spaces, no clause marks
    got = chunks(cjk, 220)
    assert all(len(c) <= 220 for c in got) and "".join(got) == cjk


def test_cjk_clauses_split_at_ideographic_commas() -> None:
    text = "、".join(["北側の監視所から報告がありました"] * 20) + "。"
    got = chunks(text, 100)
    assert all(len(c) <= 100 for c in got) and "".join(got) == text


if __name__ == "__main__":
    for t in [test_sentences_are_grouped_under_the_limit, test_one_overlong_sentence_is_split,
              test_no_punctuation_falls_back_to_words_then_characters, test_cjk_clauses_split_at_ideographic_commas]:
        t()
        print("ok", t.__name__)
