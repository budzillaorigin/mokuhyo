"""Tests for voices/textsplit.py. Run: uv run python voices/test_textsplit.py"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from textsplit import chunks, sentence_ends

FIXTURE = json.loads((Path(__file__).resolve().parent / "fixtures" / "segmentation_cases.json").read_text(encoding="utf-8"))
NUMERIC = re.compile(r"\d+(?:[.,:]\d+)+")


def assert_round_trip(text: str, got: list[str]) -> None:
    """Every chunk is an exact substring of [text], in order; only whitespace at split points is dropped."""
    pos = 0
    for c in got:
        while pos < len(text) and text[pos].isspace():
            pos += 1
        assert c and text.startswith(c, pos), (text, got, pos)
        pos += len(c)
    assert not text[pos:].strip(), (text, got)


def assert_spans_kept(text: str, got: list[str], spans: list[str]) -> None:
    for span in spans:
        assert span in text, span
        assert any(span in c for c in got), (span, got)


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


def test_corpus_lines_are_never_changed() -> None:
    """QC 2026-10-30 V1: `3.5` became `3. 5` in 18 shipped lines (evidence.json corpus_scan)."""
    assert len(FIXTURE["corpus"]) == 18
    for row in FIXTURE["corpus"]:
        text = row["text"]
        for limit in (220, 60):  # whole line, and forced to split inside it
            got = chunks(text, limit, lang=row["lang"])
            assert_round_trip(text, got)
            assert_spans_kept(text, got, NUMERIC.findall(text))
        assert chunks(text, lang=row["lang"]) == [text], (row["id"], chunks(text, lang=row["lang"]))


def test_case_classes_keep_protected_spans() -> None:
    """Decimals, grouping, units, abbreviations, times, initials, ellipses, quotes and CJK boundaries."""
    for case in FIXTURE["cases"]:
        text = case["text"]
        ends = sentence_ends(text)
        got = [text[a:b].strip() for a, b in zip([0, *ends], ends)]
        assert_round_trip(text, got)
        assert len(got) == case["sentences"], (case, got)
        assert_spans_kept(text, got, case["protect"])


def test_overlong_split_keeps_numbers() -> None:
    sentence = ", ".join(["la frecuencia 123.45 MHz cambia a 1,250.5 a las 08.00"] * 8) + "."
    got = chunks(sentence, 80, lang="es")
    assert all(len(c) <= 80 for c in got), [len(c) for c in got]
    assert_round_trip(sentence, got)
    assert_spans_kept(sentence, got, ["123.45", "1,250.5", "08.00"])


def test_every_shipped_line_round_trips() -> None:
    """Zero pack lines (listening scripts, track dialogues, exemplars, items) change when chunked."""
    packs = Path(__file__).resolve().parents[2] / "content" / "packs"

    def texts(o: object):
        if isinstance(o, dict):
            for k, v in o.items():
                if k in ("text", "response") and isinstance(v, str):
                    yield v
                else:
                    yield from texts(v)
        elif isinstance(o, list):
            for v in o:
                yield from texts(v)

    n = 0
    for f in sorted([*packs.glob("*/exam.json"), *packs.glob("*/track-*.json"), *packs.glob("*/exemplars.json")]):
        for t in texts(json.loads(f.read_text(encoding="utf-8"))):
            assert_round_trip(t, chunks(t, lang=f.parent.name))
            n += 1
    assert n > 9000, n


def test_limit_is_measured_per_script() -> None:
    """One character budget does not fit all: a kanji/kana line takes far longer to say than the same count of Latin
    letters, so CJK chunks come out shorter in characters for the same limit."""
    latin = "Uno dos tres cuatro. " * 30
    cjk = "基地の警備について説明します。" * 30
    assert max(len(c) for c in chunks(latin, 220, lang="es")) > 150
    assert max(len(c) for c in chunks(cjk, 220, lang="ja")) <= 110


if __name__ == "__main__":
    for t in [test_sentences_are_grouped_under_the_limit, test_one_overlong_sentence_is_split,
              test_no_punctuation_falls_back_to_words_then_characters, test_cjk_clauses_split_at_ideographic_commas,
              test_corpus_lines_are_never_changed, test_case_classes_keep_protected_spans, test_overlong_split_keeps_numbers,
              test_every_shipped_line_round_trips, test_limit_is_measured_per_script]:
        t()
        print("ok", t.__name__)
