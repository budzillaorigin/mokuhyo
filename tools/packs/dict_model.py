"""Intermediate records the dictionary adapters (JMdict, CC-CEDICT, kaikki/Wiktionary) produce for
build_dictionary.py, plus small text helpers they share. Everything here is NFC; nothing is NFKC-normalized."""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

MAX_GLOSS_CHARS = 300
MAX_SENSES = 16
MAX_EXAMPLES = 2
MAX_EXAMPLE_CHARS = 200

# Arabic-script short vowels, tanwin, shadda, sukun, superscript alef, tatweel: absent from ordinary running text.
_TASHKEEL = re.compile("[ً-ْٰـ]")
# Combining acute/grave used by Wiktionary to mark Russian stress (not part of the spelling).
_STRESS = re.compile("[́̀]")


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def strip_stress(s: str) -> str:
    """говори́ть → говорить; keeps ё and й (decomposes only to remove the stress marks, then recomposes)."""
    return nfc(_STRESS.sub("", unicodedata.normalize("NFD", s)))


def strip_tashkeel(s: str) -> str:
    """كِتَابٌ → كتاب; hamza seats (أ إ آ ؤ ئ) are precomposed letters and stay."""
    return _TASHKEEL.sub("", nfc(s))


def surface_key(s: str, language: str) -> str:
    """How a word appears in ordinary text in [language]: NFC, Russian stress and Arabic/Persian vowel marks removed."""
    s = nfc(s).strip()
    if language == "ru":
        s = strip_stress(s)
    elif language in ("ar", "fa"):
        s = strip_tashkeel(s)
    return s


def scripts(s: str) -> set[str]:
    """Script names (first word of the Unicode name: LATIN, CYRILLIC, ARABIC, HANGUL, CJK…) of the letters in s."""
    out = set()
    for c in s:
        if unicodedata.category(c).startswith("L"):
            name = unicodedata.name(c, "")
            if name:
                out.add(name.split()[0])
    return out


@dataclass
class Lemma:
    headword: str
    reading: str | None
    pos: str
    # (gloss_en, domain, register)
    senses: list[tuple[str, str | None, str | None]]
    # surface → comma-separated tags; the builder drops surfaces equal to the headword.
    forms: dict[str, str] = field(default_factory=dict)
    # (text, translation, source)
    examples: list[tuple[str, str | None, str]] = field(default_factory=list)
    # Words whose wordfreq frequency ranks this lemma (default: the headword).
    freq_words: list[str] = field(default_factory=list)
    # Tie-break among equal frequencies; higher sorts first (JMdict common flag, sense count…).
    weight: float = 0.0
    # Subtracted from the Zipf frequency before ranking (JMdict: entries not marked common).
    penalty: float = 0.0
    # Other spellings form-of links may name as their target (e.g. the European spelling of a pt-BR headword).
    aliases: list[str] = field(default_factory=list)

    def add_form(self, surface: str, tags: list[str] | str) -> None:
        surface = nfc(surface).strip()
        if not surface or surface == self.headword or surface in self.forms:
            return
        self.forms[surface] = tags if isinstance(tags, str) else ",".join(dict.fromkeys(tags))


@dataclass
class FormLink:
    """An inflected/variant surface that a form-of or alt-of entry points at a lemma headword."""
    surface: str
    target: str
    pos: str
    tags: str


def clip(text: str, limit: int = MAX_GLOSS_CHARS) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"
