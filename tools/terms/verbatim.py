"""Verbatim checks for definitions copied from public-domain sources (seed_terms.csv, terms_en.json).

Text is compared "squashed": NFKC, lower case, letters and digits only, so line breaks, hyphenation and punctuation
from PDF extraction don't matter. A definition that runs across a page break is still verbatim when its pieces appear
in order with only page furniture (running header, page number: at most MAX_GAP characters) between them.
"""
from __future__ import annotations

import re
import unicodedata
from pathlib import Path

CACHE = Path(__file__).resolve().parents[1] / "sources" / ".cache" / "text"
MAX_GAP = 60
_texts: dict[str, str] = {}


def squash(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    return "".join(ch for ch in text if unicodedata.category(ch)[0] in "LN")


def source_text(source_id: str) -> str | None:
    if source_id not in _texts:
        p = CACHE / f"{source_id}.txt"
        if not p.exists():
            return None
        _texts[source_id] = squash(p.read_text(encoding="utf-8"))
    return _texts[source_id]


def matches(definition: str, haystack: str, max_pieces: int = 3) -> bool:
    need = squash(definition)
    if not need:
        return False
    if need in haystack:
        return True
    start = 0
    for _ in range(max_pieces):
        # longest prefix of `need` found at or after `start`
        lo, hi, best, at = 12, len(need), 0, -1
        while lo <= hi:
            mid = (lo + hi) // 2
            pos = haystack.find(need[:mid], start)
            if pos >= 0:
                best, at, lo = mid, pos, mid + 1
            else:
                hi = mid - 1
        if best == 0 or (start and at - start > MAX_GAP):
            return False
        need = need[best:]
        if not need:
            return True
        start = at + best
    return False


def verbatim_in(definition: str, source_id: str) -> bool | None:
    """True/False, or None when the source's text cache is missing."""
    text = source_text(source_id)
    return None if text is None else matches(definition, text)


def longest_verbatim_prefix(definition: str, source_id: str) -> str | None:
    """The longest run of whole sentences from the start of [definition] that is verbatim in the source."""
    sents = re.split(r"(?<=[.!?])\s+", definition.strip())
    for k in range(len(sents), 0, -1):
        cand = " ".join(sents[:k])
        if verbatim_in(cand, source_id):
            return cand
    return None
