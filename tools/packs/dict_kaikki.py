"""es fr de pt-BR ru ko ar fa id adapter: English-Wiktionary extracts from kaikki.org (wiktextract JSONL; Wiktionary
text CC BY-SA 3.0 / GFDL, used under CC BY-SA) → language-neutral Lemma records and form-of links.

The files are large (up to ~1 GB), so build_dictionary.py streams them twice: `candidate()` on every line to rank
lemmas by frequency, then `lemma()` on the kept lines and `links()` on every line.

- A lemma is an entry with at least one sense that is not form-of/alt-of. Pure form-of/alt-of entries ("hablamos",
  inflection of hablar) only contribute `form` rows pointing at their lemma.
- `forms` lists (conjugation/declension tables, plurals…) become `form` rows; table metadata, romanizations,
  multi-word periphrases, other scripts and derived words (aspect partners, verbal nouns, diminutives) are skipped.
- reading: ko romanization (Revised Romanization), ru stress-marked canonical form, ar/fa romanization.
- Surfaces are stored as they appear in running text: Russian stress marks and Arabic/Persian vowel marks removed.
- pt-BR: a lemma whose Brazilian spelling is listed as an alternative form is headed by the Brazilian spelling,
  the European one becomes a form tagged Portugal, and senses tagged only Portugal sort last.
"""

from __future__ import annotations

import re
import unicodedata

from dict_model import (
    MAX_EXAMPLE_CHARS,
    MAX_EXAMPLES,
    MAX_SENSES,
    FormLink,
    Lemma,
    clip,
    nfc,
    scripts,
    surface_key,
)

# Language code → kaikki.org language name (file kaikki.org-dictionary-<Name>.jsonl, sources.lock kaikki-<name>).
KAIKKI_NAMES = {
    "es": "Spanish", "fr": "French", "de": "German", "pt-BR": "Portuguese", "ru": "Russian",
    "ko": "Korean", "ar": "Arabic", "fa": "Persian", "id": "Indonesian",
}

SKIP_POS = {"romanization", "character", "symbol", "punct", "punctuation mark", "soft-redirect"}
# forms[] entries that are table metadata or not a surface of this lemma.
SKIP_FORM_TAGS = {
    "table-tags", "inflection-template", "class", "romanization", "error-unrecognized-form", "transliteration",
}
# Head-line forms (no inflection-table source) that name a different lexeme rather than an inflection.
SKIP_HEAD_TAGS = {
    "perfective", "imperfective", "noun-from-verb", "diminutive", "augmentative", "endearing", "abbreviation",
    "verbal-noun", "agent", "causative", "synonym",
}
DROP_TAGS = {"form-of", "alt-of", "canonical", "multiword-construction"}
REGISTER_TAGS = {
    "formal", "informal", "colloquial", "slang", "vulgar", "archaic", "obsolete", "dated", "literary", "rare",
    "derogatory", "offensive", "humorous", "poetic", "dialectal", "euphemistic", "childish", "familiar",
    "pejorative", "regional", "honorific", "humble", "polite", "Internet", "ironic", "nonstandard", "uncommon",
    "Classical", "historical",
}
_PAREN = re.compile(r"\s*\([^)]*\)")
_SPLIT = re.compile(r"\s*[,،;]\s*")
_MARKERS = re.compile(r"[△^*¹²³⁴]+")


def _senses(d: dict) -> list[dict]:
    return [s for s in d.get("senses", []) if s.get("glosses")]


def _is_form_sense(s: dict) -> bool:
    tags = s.get("tags", [])
    return bool(s.get("form_of") or s.get("alt_of") or "form-of" in tags or "alt-of" in tags)


def is_lemma(d: dict) -> bool:
    if d.get("pos") in SKIP_POS:
        return False
    return any(not _is_form_sense(s) for s in _senses(d))


def _brazilian_spelling(d: dict) -> str | None:
    for f in d.get("forms", []):
        tags = f.get("tags", [])
        if "Brazil" in tags and "Portugal" not in tags and ("alternative" in tags or "standard" in tags):
            return nfc(f.get("form", "")).strip() or None
    return None


def headword(d: dict, language: str) -> str:
    word = surface_key(d["word"], language)
    if language == "pt-BR":
        return _brazilian_spelling(d) or word
    return word


def candidate(d: dict, language: str) -> tuple[str, float] | None:
    """(headword, weight) when line d is a lemma; pass 1 of the build ranks these."""
    if not is_lemma(d):
        return None
    return headword(d, language), _weight(d)


def _weight(d: dict) -> float:
    lemma_senses = sum(1 for s in _senses(d) if not _is_form_sense(s))
    return min(lemma_senses, 9) / 10


def _gloss(s: dict) -> str:
    glosses = [g for g in s["glosses"] if g.strip()]
    if len(glosses) > 1 and glosses[-2].rstrip().endswith(":"):
        return clip(glosses[-2].rstrip() + " " + glosses[-1])
    return clip(glosses[-1])


def _register(tags: list[str]) -> str | None:
    picked = [t for t in tags if t in REGISTER_TAGS or (t[:1].isupper() and t not in {"Cyrillic", "Latin"})]
    return ",".join(picked) or None


def _reading(d: dict, language: str) -> str | None:
    forms = d.get("forms", [])
    if language == "ru":
        for f in forms:
            if "canonical" in f.get("tags", []):
                text = nfc(f.get("form", "")).strip()
                if text and text != nfc(d["word"]) and "́" in unicodedata.normalize("NFD", text):
                    return text
        return None
    if language in ("ko", "ar", "fa"):
        romans = [nfc(f["form"]).strip() for f in forms if "romanization" in f.get("tags", []) and f.get("form")]
        if not romans:
            return None
        # Persian lists the classical romanization first and the Iranian one (ketâb) last.
        return romans[-1] if language == "fa" else romans[0]
    return None


def _form_surfaces(text: str, language: str) -> list[str]:
    text = _MARKERS.sub("", _PAREN.sub("", text))
    out = []
    for piece in _SPLIT.split(text):
        piece = surface_key(piece, language)
        if piece and piece not in ("-", "—", "–") and " " not in piece:
            out.append(piece)
    return out


def lemma(d: dict, language: str) -> Lemma | None:
    if not is_lemma(d):
        return None
    head = headword(d, language)
    head_scripts = scripts(head)
    senses = []
    for s in _senses(d):
        tags = s.get("tags", [])
        topics = s.get("topics", [])
        senses.append((_gloss(s), ",".join(topics[:2]) or None, _register(tags)))
    if language == "pt-BR":
        senses.sort(key=lambda s: bool(s[2]) and "Portugal" in s[2].split(",") and "Brazil" not in s[2].split(","))

    examples = []
    for s in _senses(d):
        for ex in s.get("examples", []):
            text = (ex.get("text") or "").strip()
            if text and len(text) <= MAX_EXAMPLE_CHARS:
                translation = (ex.get("english") or ex.get("translation") or "").strip() or None
                examples.append((ex.get("type") != "example", nfc(text), translation))
    examples.sort(key=lambda e: e[0])  # constructed examples before quotations
    out = Lemma(
        headword=head,
        reading=_reading(d, language),
        pos=d.get("pos", ""),
        senses=senses[:MAX_SENSES],
        examples=[(t, tr, "wiktionary") for _, t, tr in examples[:MAX_EXAMPLES]],
        freq_words=[head],
        weight=_weight(d),
    )
    word = surface_key(d["word"], language)
    if word != head:
        out.aliases.append(word)
        out.add_form(word, "Portugal")
    for f in d.get("forms", []):
        tags = f.get("tags", [])
        if not f.get("form") or SKIP_FORM_TAGS.intersection(tags):
            continue
        if not f.get("source") and SKIP_HEAD_TAGS.intersection(tags):
            continue
        for surface in _form_surfaces(f["form"], language):
            if head_scripts and not scripts(surface) <= head_scripts:
                continue  # Tajik Cyrillic spellings of Persian words, Latin transliterations…
            out.add_form(surface, [t for t in tags if t not in DROP_TAGS])
    return out


def links(d: dict, language: str) -> list[FormLink]:
    """Form-of/alt-of senses of line d as (surface → lemma headword) links."""
    if d.get("pos") in SKIP_POS:
        return []
    surface = surface_key(d["word"], language)
    out = []
    for s in _senses(d):
        targets = (s.get("form_of") or []) + (s.get("alt_of") or [])
        if not targets:
            continue
        tags = [t for t in s.get("tags", []) if t not in DROP_TAGS]
        if s.get("alt_of"):
            tags = ["alternative", *tags]
        for t in targets:
            target = surface_key(t.get("word", ""), language)
            if target and target != surface and " " not in surface:
                out.append(FormLink(surface, target, d.get("pos", ""), ",".join(dict.fromkeys(tags))))
    return out
