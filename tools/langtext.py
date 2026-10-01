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
