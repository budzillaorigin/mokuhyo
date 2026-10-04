"""Language-aware text measures for content validation (build tools only; the app uses ICU and its packs).

Tokens and word frequencies come from wordfreq (Apache-2.0 code, CC BY-SA 4.0 data; MeCab/jieba for CJK).
Zipf frequency: 3 ≈ once per million words; below RARE_ZIPF a word counts as rare.
"""
from __future__ import annotations

import re
import unicodedata
import warnings
from dataclasses import dataclass

warnings.filterwarnings("ignore", category=SyntaxWarning)

import wordfreq

RARE_ZIPF = 3.0

# Our BCP-47 codes → wordfreq codes.
WF = {"ja": "ja", "es": "es", "fr": "fr", "de": "de", "pt-BR": "pt", "ru": "ru", "zh-Hans": "zh", "ko": "ko",
      "ar": "ar", "fa": "fa", "id": "id"}
LANGS = tuple(WF)

# Roughly how many wordfreq tokens one English-style word becomes (ja/ko split off particles and endings, zh is
# segmented into short words, ar/ru fuse clitics). Scales the ILR length bands per language.
TOKEN_FACTOR = {"ja": 1.5, "ko": 1.5, "zh-Hans": 1.2, "ar": 0.85, "ru": 0.9, "fa": 1.0, "id": 0.95, "de": 0.95,
                "es": 1.05, "fr": 1.1, "pt-BR": 1.05}

# Characters that belong to each language's script (for the purity check: drafts must be in the target language).
SCRIPT = {
    "ja": re.compile(r"[぀-ヿ㐀-䶿一-鿿々ー]"),
    "zh-Hans": re.compile(r"[㐀-䶿一-鿿]"),
    "ko": re.compile(r"[가-힯ᄀ-ᇿ㄰-㆏]"),
    "ru": re.compile(r"[Ѐ-ӿ]"),
    "ar": re.compile(r"[؀-ۿݐ-ݿ]"),
    "fa": re.compile(r"[؀-ۿݐ-ݿﭐ-﷿]"),
}
LATIN = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿĀ-ž]")
SENTENCE_END = re.compile(r"(?<=[.!?。！？؟])\s*|\n+")


HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
KANA = re.compile(r"[\u3040-\u30ff]")
HANGUL = re.compile(r"[\uac00-\ud7af\u1100-\u11ff\u3130-\u318f]")
CYRILLIC = re.compile(r"[\u0400-\u04ff]")
ARABIC = re.compile(r"[\u0600-\u06ff\u0750-\u077f\ufb50-\ufdff\ufe70-\ufeff]")
LATIN_WORD = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿĀ-ž]{3,}")
LATIN_LANGS = {"es", "fr", "de", "pt-BR", "id"}


def foreign_script(text: str, lang: str) -> list[str]:
    """Words or characters from another script that don't belong in a text written in [lang] (drafting models
    leak English words into Arabic, hanja into Korean, …). Upper-case acronyms (NATO, OBS) are allowed."""
    found: list[str] = []
    if lang not in LATIN_LANGS:
        found += [w for w in LATIN_WORD.findall(text) if not w.isupper()]
    if lang == "ko":
        found += HAN.findall(text) + KANA.findall(text)
    if lang == "zh-Hans":
        found += KANA.findall(text) + HANGUL.findall(text)
    if lang == "ja":
        found += HANGUL.findall(text)
    if lang in LATIN_LANGS or lang in ("ru", "id"):
        found += HAN.findall(text) + KANA.findall(text) + HANGUL.findall(text) + ARABIC.findall(text)
        if lang != "ru":
            found += CYRILLIC.findall(text)
    if lang == "ru":
        found += [w for w in LATIN_WORD.findall(text) if not w.isupper()]
    if lang in ("ar", "fa"):
        found += HAN.findall(text) + CYRILLIC.findall(text)
    return found


def tokens(text: str, lang: str) -> list[str]:
    return [t for t in wordfreq.tokenize(text, WF[lang]) if any(c.isalpha() for c in t)]


def zipf(word: str, lang: str) -> float:
    return wordfreq.zipf_frequency(word, WF[lang])


def sentences(text: str) -> list[str]:
    return [s for s in (p.strip() for p in SENTENCE_END.split(text)) if s]


@dataclass
class Measures:
    tokens: int
    sentences: int
    mean_sentence: float
    rare_ratio: float
    purity: float  # share of letters in the target script

    def scaled_tokens(self, lang: str) -> float:
        return self.tokens / TOKEN_FACTOR.get(lang, 1.0)


def measure(text: str, lang: str) -> Measures:
    toks = tokens(text, lang)
    sents = sentences(text)
    rare = sum(1 for t in toks if zipf(t, lang) < RARE_ZIPF)
    letters = [c for c in text if c.isalpha()]
    if lang in SCRIPT:
        own = sum(1 for c in letters if SCRIPT[lang].match(c))
    else:
        own = sum(1 for c in letters if LATIN.match(c))
    return Measures(
        tokens=len(toks),
        sentences=len(sents),
        mean_sentence=(len(toks) / len(sents)) if sents else float(len(toks)),
        rare_ratio=(rare / len(toks)) if toks else 0.0,
        purity=(own / len(letters)) if letters else 0.0,
    )


def nfc(text: str) -> str:
    return unicodedata.normalize("NFC", text)


# Mirror of ScriptCheck.latinLanguageOk in the app (shared/.../lang/ScriptCheck.kt): a Latin-script sentence must not
# read as English (more English function words than the language's own).
STOPWORDS = {
    "en": {
        "the", "and", "of", "to", "is", "are", "was", "were", "you", "your", "what", "did", "do", "does", "in", "on", "at",
        "for", "with", "this", "that", "it", "be", "have", "has", "will", "would", "not", "a", "an", "about", "from", "by", "as",
        "or", "but", "into", "their", "they", "he", "she", "his", "her", "its", "which", "more", "some", "little", "than",
        "then", "there", "these", "those", "also", "only", "very", "can", "could", "should", "may", "might", "when", "where",
        "how", "why", "who",
    },
    "es": {
        "el", "la", "los", "las", "de", "del", "que", "y", "en", "un", "una", "es", "por", "con", "para", "no", "se", "su", "al",
        "lo", "como", "más", "pero", "sus", "le", "ya", "o", "fue", "muy", "qué", "usted", "está", "a",
    },
    "fr": {
        "le", "la", "les", "de", "des", "du", "et", "en", "un", "une", "est", "que", "qui", "pour", "pas", "dans", "sur", "au",
        "avec", "ce", "il", "elle", "nous", "vous", "je", "ne", "se", "son", "sa",
    },
    "de": {
        "der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "den", "von", "mit", "sich", "des", "auf", "für", "im",
        "dem", "auch", "es", "an", "als", "wir", "sie", "ich", "haben", "wird",
    },
    "pt-BR": {
        "o", "a", "os", "as", "de", "do", "da", "dos", "das", "que", "e", "em", "um", "uma", "é", "para", "com", "não", "no",
        "na", "por", "mais", "se", "você", "foi", "está", "ao",
    },
    "id": {
        "yang", "dan", "di", "ini", "itu", "dengan", "untuk", "tidak", "dari", "dalam", "akan", "pada", "ke", "juga", "ada",
        "saya", "anda", "kami", "mereka", "adalah", "atau", "sudah",
    },
}


def reads_as_english(text: str, lang: str) -> bool:
    own = STOPWORDS.get(lang)
    words = re.findall(r"[^\W\d_]+(?:'[^\W\d_]+)?", text.lower())
    if own is None or len(words) < 4:
        return False
    return sum(w in own for w in words) < sum(w in STOPWORDS["en"] for w in words)
