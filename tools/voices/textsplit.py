"""Splits text into pieces a TTS model can say in one call (no model imports, so it is testable anywhere).

Every piece is an exact substring of the input (QC 2026-10-30 V1: the old splitter turned `3.5` into `3. 5`); only
whitespace at a split point is dropped. Numbers (`3.5`, `8.000`, `1,234.5`, `12:30`), abbreviations, initials,
ellipses inside a sentence and closing quotes never end a sentence. Spoken normalization (reading numbers aloud,
Chinese word spacing in ChatterboxJobs) stays a separate step before this one and never touches the shown transcript.
The cases are shared with the JVM sentence path in fixtures/segmentation_cases.json.
"""
from __future__ import annotations

import re

SEGMENTER_VERSION = "2"

TERMINATORS = ".!?…。！？؟"
NO_SPACE_NEEDED = "。！？؟"  # full-width and Arabic-script ends need no space after them
CLOSERS = "\"'”’」』）)]»›"
QUOTATIVE = "とっ"
CLAUSE_MARKS = ",;:—，、；：،؛"
# Digits joined by . , : ' (decimals, grouping, times, coordinates): never split inside.
NUMBER = re.compile(r"\d+(?:[.,:'’]\d+)+")
# Abbreviations that are usually followed by a capitalized name or word (lowercase followers are handled generally).
ABBREVIATIONS = {
    "mr", "mrs", "ms", "dr", "prof", "st", "sr", "sra", "srta", "dra", "lic", "ing", "m", "mm", "mme", "mlle", "hr", "fr",
    "z", "b", "nr", "vs", "etc", "gen", "col", "lt", "sgt", "capt", "cpt", "maj", "adm", "no", "tel",
    "г", "гг", "ул", "д", "т", "е", "им", "проф", "св",
}
LIMIT = 220


def _weight(ch: str) -> float:
    """Rough speaking time of one character relative to a Latin letter: a kana or Hangul syllable is a mora or more, a
    Han character usually two. Measures chunk length per script instead of one character count for every language."""
    o = ord(ch)
    if 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or 0xF900 <= o <= 0xFAFF:
        return 3.0
    if 0x3040 <= o <= 0x30FF or 0xAC00 <= o <= 0xD7AF or 0x1100 <= o <= 0x11FF:
        return 2.0
    return 1.0


def speech_length(text: str) -> float:
    return sum(_weight(c) for c in text)


def _protected(text: str) -> list[bool]:
    """True at every index where no split may happen (inside a number)."""
    mask = [False] * (len(text) + 1)
    for m in NUMBER.finditer(text):
        for i in range(m.start() + 1, m.end()):
            mask[i] = True
    return mask


def _word_before(text: str, i: int) -> str:
    j = i
    while j > 0 and (text[j - 1].isalpha()):
        j -= 1
    return text[j:i]


def sentence_ends(text: str) -> list[int]:
    """End offsets (exclusive) of the sentences in [text]; the last one is len(text) when text remains."""
    mask = _protected(text)
    out: list[int] = []
    start = 0
    i = 0
    n = len(text)
    while i < n:
        if text[i] not in TERMINATORS:
            i += 1
            continue
        j = i
        while j < n and text[j] in TERMINATORS:
            j += 1
        run = text[i:j]
        while j < n and text[j] in CLOSERS:
            j += 1
        closed = j > i + len(run)
        k = j
        while k < n and text[k].isspace():
            k += 1
        nxt = text[k] if k < n else ""
        if mask[i] or j >= n:
            boundary = False
        elif any(c in NO_SPACE_NEEDED for c in run):
            # 「はい。」と言った: a quotative particle continues the quoting sentence (「はい。」そう… still splits).
            boundary = not (closed and k == j and nxt != "" and nxt in QUOTATIVE)
        elif k == j:
            boundary = False  # no space after: 3.5, e.g., example.com
        elif nxt.islower():
            boundary = False  # "Bueno... no sé", "5 p.m. today", '"Ready?" he asked'
        elif run == "." and not closed and _word_before(text, i).lower() in ABBREVIATIONS:
            boundary = False
        elif run == "." and not closed and len(_word_before(text, i)) == 1 and _word_before(text, i).isupper():
            boundary = False  # initials: J. R. R. Tolkien
        else:
            boundary = True
        if boundary and any(c.isalnum() for c in text[start:j]):
            out.append(j)
            start = j
        i = j
    if start < n and text[start:].strip():
        out.append(n)
    return out


def _spans(text: str, ends: list[int]) -> list[tuple[int, int]]:
    """Ends to stripped (start, end) spans of [text]."""
    out = []
    start = 0
    for e in ends:
        a, b = start, e
        while a < b and text[a].isspace():
            a += 1
        while b > a and text[b - 1].isspace():
            b -= 1
        if a < b:
            out.append((a, b))
        start = e
    return out


def _split_long(text: str, a: int, b: int, limit: float, mask: list[bool]) -> list[tuple[int, int]]:
    """One sentence over [limit]: split at clause punctuation, then at spaces, then (scripts without spaces) by length."""
    if speech_length(text[a:b]) <= limit:
        return [(a, b)]
    for is_break in (lambda c: c in CLAUSE_MARKS, str.isspace):
        cuts = [i + 1 for i in range(a, b - 1) if is_break(text[i]) and not mask[i + 1]]
        if cuts:
            parts = _pack(text, _between(text, a, [*cuts, b]), limit)
            return [q for p in parts for q in _split_long(text, p[0], p[1], limit, mask)]
    out = []
    cur, w = a, 0.0
    for i in range(a, b):
        if i > cur and w + _weight(text[i]) > limit and not mask[i]:
            out.append((cur, i))
            cur, w = i, 0.0
        w += _weight(text[i])
    out.append((cur, b))
    return out


def _between(text: str, a: int, ends: list[int]) -> list[tuple[int, int]]:
    """Stripped spans from [a] to each of [ends] in turn."""
    return [(x + a, y + a) for x, y in _spans(text[a:], [e - a for e in ends])]


def _pack(text: str, spans: list[tuple[int, int]], limit: float) -> list[tuple[int, int]]:
    """Joins neighbouring spans while the joined text stays within [limit]."""
    out: list[tuple[int, int]] = []
    for a, b in spans:
        if out and speech_length(text[out[-1][0]:b]) <= limit:
            out[-1] = (out[-1][0], b)
        else:
            out.append((a, b))
    return out


def chunks(text: str, limit: int = LIMIT, lang: str | None = None) -> list[str]:
    """Pieces of at most [limit] speech units (see [speech_length]; a Latin letter is one): Chatterbox stops after ~1000
    speech tokens (~40 s), so a long answer generated in one call was cut off. Sentences are kept whole and grouped; a
    single sentence over the limit is split at clause punctuation, then between words, and only as a last resort
    mid-word (QC 2026-10-19). [lang] is accepted for callers and future per-language rules; the measure is per script."""
    del lang
    if not text.strip():
        return [text]
    mask = _protected(text)
    pieces = [s for a, b in _spans(text, sentence_ends(text)) for s in _split_long(text, a, b, limit, mask)]
    return [text[a:b] for a, b in _pack(text, pieces, limit)]
