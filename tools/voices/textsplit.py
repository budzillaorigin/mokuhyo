"""Splits text into pieces a TTS model can say in one call (no model imports, so it is testable anywhere)."""
from __future__ import annotations

import re

SENTENCE_END = re.compile(r"(?<=[.!?。！？؟])\s*")
# Clause breaks: Latin/Cyrillic comma, semicolon, colon, dash; CJK and Arabic-script commas and semicolons.
CLAUSE_END = re.compile(r"(?<=[,;:—，、；：،؛])\s*")


def _split_long(piece: str, limit: int) -> list[str]:
    """One sentence over [limit]: split at clause punctuation, then at spaces, then (scripts without spaces) by length."""
    if len(piece) <= limit:
        return [piece]
    for pattern in (CLAUSE_END, re.compile(r"\s+")):
        parts = [p.strip() for p in pattern.split(piece) if p.strip()]
        if len(parts) > 1:
            return [q for p in _pack(parts, limit, " " if " " in piece else "") for q in _split_long(p, limit)]
    return [piece[i:i + limit] for i in range(0, len(piece), limit)]


def _pack(parts: list[str], limit: int, sep: str) -> list[str]:
    out: list[str] = []
    cur = ""
    for p in parts:
        if cur and len(cur) + len(sep) + len(p) > limit:
            out.append(cur)
            cur = p
        else:
            cur = f"{cur}{sep}{p}" if cur else p
    if cur:
        out.append(cur)
    return out


def chunks(text: str, limit: int = 220) -> list[str]:
    """Pieces of at most [limit] characters: Chatterbox stops after ~1000 speech tokens (~40 s), so a long answer generated
    in one call was cut off. Sentences are kept whole and grouped; a single sentence over the limit is split at clause
    punctuation, then between words, and only as a last resort mid-word (QC 2026-10-19: one 599-character piece used to
    go through whole)."""
    sentences = [x.strip() for x in SENTENCE_END.split(text) if x.strip()]
    pieces = [p for s in sentences for p in _split_long(s, limit)]
    sep = "" if text and " " not in text.strip() else " "
    return _pack(pieces, limit, sep) or [text]
