"""zh-Hans adapter: CC-CEDICT (MDBG, CC BY-SA 4.0) → language-neutral Lemma records.

headword = simplified; reading = pinyin with tone marks (converted from CEDICT's numbered tones); one sense per
"/gloss/"; the traditional spelling, when different, is a `form` row tagged "traditional". Lines with the same
simplified headword and pinyin are merged.
"""

from __future__ import annotations

import re
from collections.abc import Iterable

from dict_model import MAX_SENSES, Lemma, clip, nfc

LINE = re.compile(r"^(\S+) (\S+) \[([^\]]*)\] /(.*)/\s*$")
_SYLLABLE = re.compile(r"^([A-Za-z:üÜ]+)([1-5])$")
_MARKS = {"1": "̄", "2": "́", "3": "̌", "4": "̀", "5": ""}


def syllable(token: str) -> str:
    """One numbered-pinyin syllable → tone-marked (zhong1 → zhōng, lu:4 → lǜ, r5 → r). Other tokens unchanged."""
    m = _SYLLABLE.match(token)
    if not m:
        return token.replace("u:", "ü").replace("U:", "Ü")
    letters, tone = m.group(1).replace("u:", "ü").replace("U:", "Ü").replace("v", "ü").replace("V", "Ü"), m.group(2)
    mark = _MARKS[tone]
    if not mark:
        return letters
    lower = letters.lower()
    # Standard placement: a or e takes the mark; in "ou" the o does; otherwise the last vowel.
    if "a" in lower:
        i = lower.index("a")
    elif "e" in lower:
        i = lower.index("e")
    elif "ou" in lower:
        i = lower.index("o")
    else:
        vowels = [j for j, c in enumerate(lower) if c in "iouü"]
        if not vowels:
            return letters  # m2, n2, ng2 (interjections): leave unmarked
        i = vowels[-1]
    return nfc(letters[: i + 1] + mark + letters[i + 1:])


def pinyin(numbered: str) -> str:
    return " ".join(syllable(t) for t in numbered.split())


def parse(lines: Iterable[str]) -> Iterable[tuple[str, str, str, list[str]]]:
    """(traditional, simplified, numbered pinyin, glosses) per CEDICT line; comments and malformed lines skipped."""
    for line in lines:
        if line.startswith("#"):
            continue
        m = LINE.match(line.rstrip("\n"))
        if m:
            trad, simp, pin, body = m.groups()
            yield nfc(trad), nfc(simp), pin, [g for g in body.split("/") if g.strip()]


def read(lines: Iterable[str]) -> list[Lemma]:
    merged: dict[tuple[str, str], Lemma] = {}
    for trad, simp, pin, glosses in parse(lines):
        reading = pinyin(pin)
        key = (simp, reading)
        entry = merged.get(key)
        if entry is None:
            proper = pin[:1].isupper()
            entry = merged[key] = Lemma(
                headword=simp, reading=reading, pos="", senses=[], freq_words=[simp],
                # Proper names (capitalized pinyin: surnames, places) rank after common words of the same frequency.
                weight=0.0 if proper else 1.0,
            )
        for g in glosses:
            if len(entry.senses) < MAX_SENSES and all(s[0] != g for s in entry.senses):
                entry.senses.append((clip(g), None, None))
        if trad != simp:
            entry.add_form(trad, "traditional")
    for entry in merged.values():
        entry.weight += min(len(entry.senses), 9) / 10
    return list(merged.values())
