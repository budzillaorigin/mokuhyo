"""Fuzzy-lookup key for dictionary packs. Mirror of app.mokuhyo.dictionary.DictionaryFold (Kotlin); both are tested
against shared/src/commonTest/resources/dictionary/fold_vectors.json so they cannot drift.

Rules, in order:
  1. NFC.
  2. Width: full-width ASCII (U+FF01..FF5E) → ASCII, ideographic space → space, half-width katakana → full-width.
     (Only these characters; never a blanket NFKC.)
  3. Lowercase (Unicode default casing), then ß → ss, ’ ‘ → '.
  4. NFD, drop non-spacing marks (Mn, BMP only) except the kana voicing marks U+3099/U+309A and the breve on a
     Cyrillic base (й stays й); then NFC. This strips Latin accents, Cyrillic stress, ё → е, Arabic tashkeel and
     hamza seats (أ إ آ → ا), and pinyin tone marks.
  5. Per language: ja katakana → hiragana; ar/fa drop tatweel and ZWNJ, unify yeh (ى ی → ي) and kaf (ک → ك);
     zh drop all whitespace (pinyin syllable spacing).
  6. Collapse whitespace, trim.
"""

from __future__ import annotations

import re
import unicodedata

# Half-width katakana U+FF61..U+FF9F → full-width (voicing marks become combining marks, recomposed by NFC).
_HALF = (
    "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨ"
    "ラリルレロワン゙゚"
)
_WS = re.compile(r"\s+")


def _width(s: str) -> str:
    out = []
    for c in s:
        o = ord(c)
        if 0xFF01 <= o <= 0xFF5E:
            out.append(chr(o - 0xFEE0))
        elif o == 0x3000:
            out.append(" ")
        elif 0xFF61 <= o <= 0xFF9F:
            out.append(_HALF[o - 0xFF61])
        else:
            out.append(c)
    return "".join(out)


def _strip_marks(s: str) -> str:
    out = []
    base = ""
    for c in unicodedata.normalize("NFD", s):
        if ord(c) <= 0xFFFF and unicodedata.category(c) == "Mn":
            if c in "゙゚" or (c == "̆" and "Ѐ" <= base <= "ӿ"):
                out.append(c)
            continue
        base = c
        out.append(c)
    return unicodedata.normalize("NFC", "".join(out))


def fold(text: str, lang: str) -> str:
    s = _width(unicodedata.normalize("NFC", text))
    s = s.lower().replace("ß", "ss").replace("’", "'").replace("‘", "'")
    s = _strip_marks(s)
    if lang == "ja":
        s = "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" or c in "ヽヾ" else c for c in s)
    elif lang in ("ar", "fa"):
        s = s.replace("ـ", "").replace("‌", "")
        s = s.replace("ى", "ي").replace("ی", "ي").replace("ک", "ك")
    elif lang.startswith("zh"):
        s = _WS.sub("", s)
    return _WS.sub(" ", s).strip()
