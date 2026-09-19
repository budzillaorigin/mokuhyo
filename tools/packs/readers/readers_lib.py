"""Shared helpers for the graded-reader pipeline (BRIEF_V2 §6.4, DECISIONS D-200..D-209).

- Story sources: tools/packs/readers/stories/*.json ({"passages": [...]}, format in docs/CONTENT_PACKS.md).
- Levels, bands and coverage thresholds: tools/packs/readers/levels.json. Genre task templates: tasks.json.
- Text measures: the app's reader sentence split (app.tsumugi.reader.ReaderAnalyzer), the reader's lemma
  grouping and dictionary resolution (LatticeReaderTokenizer + DictionaryRepository.entriesForLemmas) on the
  tools' lattice port (tools/items/lattice.py), and the §6.4 difficulty formula (app.tsumugi.coverage.DifficultyScorer).

Build tool only; nothing here ships.
"""

from __future__ import annotations

import json
import math
import re
import sqlite3
import subprocess
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parents[1]
sys.path.insert(0, str(TOOLS / "packs"))
sys.path.insert(0, str(TOOLS / "items"))

from common import PACKS, REPO, is_kanji, to_hiragana
from lattice import Lattice

STORIES = HERE / "stories"
LEVELS_JSON = HERE / "levels.json"
TASKS_JSON = HERE / "tasks.json"
ILR_BANDS = TOOLS / "items" / "ilr_bands.json"

LICENSE = "CC BY-SA 4.0"
ATTRIBUTION = "Tsumugi contributors (AI-drafted, see docs/LICENSES.md)"

STORY_ID = re.compile(r"^gr-(n6|n5|n4|n3|n2|n1)-(\d{3})$")
LEVEL_ORDER = ["N6", "N5", "N4", "N3", "N2", "N1"]

# --- Mirrors of the app's constants -----------------------------------------------------------------------

TERMINATORS = "。！？!?"  # ReaderAnalyzer.TERMINATORS
CLOSERS = "」』）)】〉》\"'”’"  # ReaderAnalyzer.CLOSERS
FUNCTION_POS = {"prt", "aux", "aux-v", "aux-adj", "cop"}  # WordStats.FUNCTION_POS
W_VOCABULARY, W_SENTENCE, W_KANJI, W_ABSTRACT = 0.20, 0.45, 0.05, 0.30  # DifficultyScorer
CUTS = [15, 19, 32, 46, 55]
ILR = ["0+", "1", "1+", "2", "2+", "3"]
TOKEN_RUN = re.compile(
    r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff々]+|[\u3041-\u309f]+|[\u30a0-\u30ff\u31f0-\u31ff]+"
    r"|[A-Za-z\uff21-\uff3a\uff41-\uff5a]+|[0-9\uff10-\uff19]+"
)
SYMBOL, AUXILIARY, PARTICLE = "記号", "助動詞", "助詞"
INFLECTING = {"動詞", "形容詞", "助動詞"}
CONJUNCTIVE = {"て", "で"}

# Parts of speech (IPADIC) never counted as vocabulary for level coverage: names, numbers, suffixes, fillers, and
# 非自立 words, which are grammar (〜ている, 〜てしまう, 〜てくれる, こと, もの). Particles, auxiliaries and
# dictionary function words are grammar too.
NEUTRAL_POS2 = {"固有名詞", "数", "接尾", "非自立"}
# Request forms taught with N5 grammar (〜をください) whose verbs the JLPT lists put at N4 (くださる).
SET_PHRASES = {"ください", "くださいませ"}
# Dates, times and counted amounts written as one word (四月, 十日, 三時, 二人): numbers, not vocabulary.
NUMBER_WORD = re.compile(r"^[一二三四五六七八九十百千万何0-9０-９]+(月|日|時|分|年|人|円|回|つ|歳|才|階|本|枚|個|冊|匹|週間|か月|ヶ月)?$")
NEUTRAL_POS1 = {SYMBOL, "フィラー", "その他", "感動詞", "接頭詞"}  # + interjections/greetings, prefixes (お, ご)

# Voices for read-along audio (render_audio.py maps these hints to VOICEVOX characters, D-170/D-205).
NARRATION = "narration"
VOICE_HINTS = {"female", "male", "male-senior"}


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def default_packs() -> Path:
    """content/packs of this checkout, or of the main checkout when this is a git worktree without built packs."""
    if (PACKS / "dictionary.sqlite").exists():
        return PACKS
    try:
        common_dir = subprocess.run(
            ["git", "-C", str(REPO), "rev-parse", "--path-format=absolute", "--git-common-dir"],
            capture_output=True, text=True, timeout=10, check=True,
        ).stdout.strip()
        main = Path(common_dir).parent / "content" / "packs"
        if (main / "dictionary.sqlite").exists():
            return main
    except (OSError, subprocess.SubprocessError):
        pass
    return PACKS


def load_levels() -> dict:
    return json.loads(LEVELS_JSON.read_text(encoding="utf-8"))


def load_tasks() -> dict:
    return json.loads(TASKS_JSON.read_text(encoding="utf-8"))


def story_files() -> list[Path]:
    return sorted(STORIES.glob("*.json"))


def load_stories(files: list[Path] | None = None) -> list[tuple[Path, dict]]:
    """(file, passage) for every story in [files] (default: all story files), in file order."""
    out = []
    for f in files if files is not None else story_files():
        try:
            doc = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            if files is not None:
                raise
            log(f"skipping {f.name}: not valid JSON ({e})")
            continue
        for p in doc.get("passages", []):
            out.append((f, p))
    return out


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def utf16_len(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


def is_japanese(c: str) -> bool:
    """TextProfiler.isJapanese (per UTF-16 unit; BMP only here)."""
    return "\u3040" <= c <= "\u30ff" or "\u4e00" <= c <= "\u9fff" or "\u3400" <= c <= "\u4dbf" or c in "々〆"


def round_half_up(x: float) -> int:
    return math.floor(x + 0.5)


def non_space_len(text: str) -> int:
    return sum(1 for c in text if not c.isspace())


# --- Structure: the reader's paragraphs and sentences --------------------------------------------------------


def paragraphs(body: str) -> list[tuple[int, int]]:
    """ReaderAnalyzer.paragraphs: (start, end) of each non-blank line, code-point offsets."""
    out = []
    start = 0
    for line in body.split("\n"):
        if line.strip():
            out.append((start, start + len(line)))
        start += len(line) + 1
    return out


def sentences(body: str) -> list[tuple[int, int]]:
    """ReaderAnalyzer.sentences over every paragraph: ends after 。！？!? plus closing brackets."""
    out = []
    for first, end_ex in paragraphs(body):
        start = i = first
        while i < end_ex:
            if body[i] in TERMINATORS:
                end = i + 1
                while end < end_ex and body[end] in CLOSERS:
                    end += 1
                out.append((start, end))
                start = i = end
            else:
                i += 1
        if start < end_ex:
            out.append((start, end_ex))
    return [(a, b) for a, b in out if body[a:b].strip()]


# --- Dictionary: the reader's lemma resolution ---------------------------------------------------------------


@dataclass
class EntryMeta:
    id: int
    jlpt: int | None
    common: bool
    rank: int
    function: bool


class Dictionary:
    """entriesForLemmasBlocking + wordStats over dictionary.sqlite, loaded into memory once."""

    def __init__(self, path: Path):
        self.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.by_text: dict[str, set[int]] = {}
        for eid, text in self.db.execute("SELECT entry_id, text FROM entry_kanji"):
            self.by_text.setdefault(text, set()).add(eid)
        for eid, search in self.db.execute("SELECT entry_id, search FROM entry_kana"):
            self.by_text.setdefault(search, set()).add(eid)
        self.meta: dict[int, tuple[int | None, bool, int]] = {
            i: (j, bool(c), r) for i, j, c, r in self.db.execute("SELECT id, jlpt, is_common, rank FROM entry")
        }
        self._function: dict[int, bool] = {}
        try:  # the frequency list behind the Core decks (build_decks.py); older packs lack it
            self.freq = dict(self.db.execute("SELECT entry_id, ord FROM freq_word"))
        except sqlite3.OperationalError:
            self.freq = {}

    def freq_ord(self, eid: int) -> int:
        """Position in the frequency list (1 = most frequent); a large number when the word isn't on it."""
        return self.freq.get(eid, 1 << 30)

    def resolve(self, form: str, reading: str | None) -> int | None:
        form = nfc(form)
        cands = self.by_text.get(form, set()) | self.by_text.get(to_hiragana(form), set())
        if not cands:
            return None
        with_reading = self.by_text.get(to_hiragana(reading), set()) if reading else set()
        best = min(
            (c for c in cands if c in self.meta),
            key=lambda c: (c not in with_reading, not self.meta[c][1], self.meta[c][2]),
            default=None,
        )
        return best

    def easiest_jlpt(self, eid: int, form: str, reading: str | None) -> int | None:
        """The easiest JLPT level of any entry spelled [form] with [reading] (the lists tag one of several
        homograph entries), falling back to [eid]'s own level."""
        form = nfc(form)
        cands = self.by_text.get(form, set()) | self.by_text.get(to_hiragana(form), set())
        if reading:
            cands &= self.by_text.get(to_hiragana(reading), set()) | ({eid} if eid in cands else set())
        levels = [lv for c in cands | {eid} if (lv := self.jlpt(c)) is not None]
        return max(levels) if levels else None

    def jlpt(self, eid: int) -> int | None:
        return self.meta.get(eid, (None, False, 0))[0]

    def common(self, eid: int) -> bool:
        return self.meta.get(eid, (None, False, 0))[1]

    def is_function(self, eid: int) -> bool:
        if eid not in self._function:
            row = self.db.execute("SELECT pos FROM sense WHERE entry_id = ? AND ord = 0", (eid,)).fetchone()
            pos = set(json.loads(row[0])) if row and row[0] else set()
            has_kanji = self.db.execute("SELECT 1 FROM entry_kanji WHERE entry_id = ? LIMIT 1", (eid,)).fetchone()
            grammar_only = bool(pos & FUNCTION_POS) and all(p in FUNCTION_POS or p in ("exp", "conj") for p in pos)
            self._function[eid] = grammar_only or (pos == {"exp"} and not has_kanji)
        return self._function[eid]

    def exists(self, eid: int) -> bool:
        return eid in self.meta

    def gloss(self, eid: int, n: int = 3) -> str:
        row = self.db.execute("SELECT glosses FROM sense WHERE entry_id = ? ORDER BY ord LIMIT 1", (eid,)).fetchone()
        if not row:
            return ""
        return "; ".join(json.loads(row[0])[:n])

    def forms(self, eid: int) -> tuple[list[str], list[str]]:
        kanji = [t for (t,) in self.db.execute("SELECT text FROM entry_kanji WHERE entry_id = ? ORDER BY ord", (eid,))]
        kana = [t for (t,) in self.db.execute("SELECT text FROM entry_kana WHERE entry_id = ? ORDER BY ord", (eid,))]
        return kanji, kana

    def lookup_word(self, word: str, reading: str | None = None) -> int | None:
        """A vocabulary-list word (dictionary form) to its JMdict id, preferring a matching reading."""
        return self.resolve(word, reading)


# --- Analysis --------------------------------------------------------------------------------------------------


@dataclass
class Group:
    """One reader token: a content morpheme plus the auxiliaries/conjunctive て that inflect it."""

    surface: str
    start: int
    end: int
    pos: tuple[str, ...]
    base: str
    reading: str  # lemma reading, hiragana, "" when unknown
    unknown: bool
    entry: int | None = None


def base_reading(surface: str, base: str, reading: str) -> str | None:
    """Morpheme.baseReading."""
    if not reading:
        return None
    read = to_hiragana(reading)
    if surface == base:
        return read
    if base in ("来る", "くる"):
        return "くる"
    if base in ("する", "為る"):
        return "する"
    p = 0
    while p < len(surface) and p < len(base) and surface[p] == base[p]:
        p += 1
    if p == 0:
        return None
    s_tail = to_hiragana(surface[p:])
    b_tail = to_hiragana(base[p:])

    def kana(s: str) -> bool:
        return all("ぁ" <= c <= "ゟ" or c == "ー" for c in s)

    if s_tail and not kana(s_tail):
        return None
    if b_tail and not kana(b_tail):
        return None
    if not read.endswith(s_tail):
        return None
    return read[: len(read) - len(s_tail)] + b_tail


class Analyzer:
    def __init__(self, packs: Path):
        self.packs = packs
        self.lattice = Lattice(packs / "tokenizer.sqlite")
        self.dictionary = Dictionary(packs / "dictionary.sqlite")
        bands = json.loads(ILR_BANDS.read_text(encoding="utf-8"))
        self.lexicon = sorted(bands["abstractLexicon"], key=len, reverse=True)

    def groups(self, sentence: str) -> list[Group]:
        out: list[Group] = []
        for t in self.lattice.tokenize(sentence):
            pos1 = t.pos1
            unknown = t.reading == ""
            cur = out[-1] if out else None
            absorb = False
            if cur is not None and cur.end == t.start and cur.pos[:1] != (SYMBOL,) and not cur.unknown:
                head = cur.pos[0] if cur.pos else ""
                if pos1 == AUXILIARY or pos1 == PARTICLE and t.pos2 == "接続助詞" and t.surface in CONJUNCTIVE:
                    absorb = head in INFLECTING
            if absorb:
                cur.surface += t.surface
                cur.end = t.end
            else:
                out.append(Group(t.surface, t.start, t.end, t.pos, t.base,
                                 base_reading(t.surface, t.base, t.reading) or "", unknown))
        for g in out:
            if not g.unknown and g.pos[:1] != (SYMBOL,):
                g.entry = self.dictionary.resolve(g.base, g.reading or None)
        return out

    def abstract(self, text: str) -> tuple[int, int]:
        rest = re.sub(r"\s+", "", text)
        tokens = len(TOKEN_RUN.findall(rest))
        hits = 0
        for word in self.lexicon:
            n = rest.count(word)
            if n:
                hits += n
                rest = rest.replace(word, "\0")
        return hits, tokens

    def measure(self, body: str, level: str, levels: dict, glossed: set[int] = frozenset(),
                names: set[str] = frozenset()) -> Measures:
        cfg = levels["levels"][level]
        rule = CoverageRule(cfg["jlpt"], cfg.get("freqOrd", 0), cfg.get("commonCounts", False),
                            cfg.get("openVocabulary", False), set(glossed), set(names))
        m = Measures()
        counts: dict[int, int] = {}
        for a, b in sentences(body):
            text = body[a:b]
            m.sentences += 1
            m.sentence_japanese += sum(1 for c in text if is_japanese(c))
            for g in self.groups(text):
                if g.entry is not None:
                    counts[g.entry] = counts.get(g.entry, 0) + 1
                self._cover(g, rule, m)
        for c in body:
            if c.isspace():
                continue
            m.chars += 1
            if is_kanji(c):
                m.kanji += 1
        m.abstract_hits, m.approx_tokens = self.abstract(body)
        d = self.dictionary
        content = {e: n for e, n in counts.items() if not d.is_function(e)}
        total = sum(content.values())
        if total:
            m.vocabulary = sum(
                1.0 - sum(n for e, n in content.items() if (d.jlpt(e) or 0) >= lv) / total for lv in (5, 4, 3, 2, 1)
            ) / 5.0
        avg = m.sentence_japanese / m.sentences if m.sentences else 0.0
        s = min(1.0, max(0.0, (avg - 10.0) / 40.0))
        k = min(1.0, max(0.0, ((m.kanji / m.chars if m.chars else 0.0) - 0.25) / 0.25))
        ab = min(1.0, max(0.0, (m.abstract_hits / m.approx_tokens if m.approx_tokens else 0.0) / 0.08))
        m.text_score = min(100, max(0, round_half_up(
            100 * (W_VOCABULARY * m.vocabulary + W_SENTENCE * s + W_KANJI * k + W_ABSTRACT * ab))))
        band = sum(1 for c in CUTS if m.text_score >= c)
        m.label_jlpt = 5 - band
        m.label_ilr = ILR[band]
        return m

    def _cover(self, g: Group, rule: CoverageRule, m: Measures) -> None:
        pos1 = g.pos[0] if g.pos else ""
        pos2 = g.pos[1] if len(g.pos) > 1 else ""
        text = g.surface.strip()
        if not text or pos1 in NEUTRAL_POS1 or pos2 in NEUTRAL_POS2 or pos1 in (PARTICLE, AUXILIARY):
            return
        if all(not is_japanese(c) for c in text):  # digits, Latin, punctuation
            return
        if any(n and n in text for n in rule.names):  # declared names (cast, places) and name + さん
            return
        if text in SET_PHRASES or NUMBER_WORD.match(text):
            return
        d = self.dictionary
        if g.entry is not None and d.is_function(g.entry):
            return
        entry = g.entry if g.entry is not None else self._potential(g)
        if entry is None:
            m.words += 1
            m.out_of_level.append(f"{g.surface}(?)")
            return
        m.words += 1
        m.lemmas.add(g.base)
        lv = d.easiest_jlpt(entry, g.base, g.reading or None)
        if (lv is not None and lv >= rule.need) or rule.open_vocab or lv is None and (d.freq_ord(entry) <= rule.freq_ord or (rule.common_ok and d.common(entry))):
            m.in_level += 1
            m.unglossed_in_level += 1
        elif entry in rule.glossed:
            m.in_level += 1
            m.glossed_hits.append(g.base)
        else:
            m.out_of_level.append(f"{g.base}({'N' + str(lv) if lv else '-'})")

    def _potential(self, g: Group) -> int | None:
        """Coverage only: a potential or negative-potential verb the analyzer left as its own lemma (聞き取れる,
        移せる) counts as its dictionary verb (聞き取る, 移す)."""
        base = g.base
        if not g.pos or g.pos[0] != "動詞" or len(base) < 2 or not base.endswith("る") or base[-2] not in POTENTIAL:
            return None
        return self.dictionary.resolve(base[:-2] + POTENTIAL[base[-2]], None)


@dataclass
class CoverageRule:
    need: int  # JLPT level a tagged word must be at or easier than (5 = N5)
    freq_ord: int  # untagged words count when within this many of the frequency list (freq_word.ord)
    common_ok: bool  # untagged JMdict-common words count (N2, N1)
    open_vocab: bool  # every resolved word counts (N1)
    glossed: set[int]
    names: set[str]


POTENTIAL = dict(zip("えけげせてねべめれ", "うくぐすつぬぶむる", strict=True))


@dataclass
class Measures:
    chars: int = 0
    kanji: int = 0
    sentences: int = 0
    sentence_japanese: int = 0
    abstract_hits: int = 0
    approx_tokens: int = 0
    vocabulary: float = 0.0
    text_score: int = 0
    label_jlpt: int = 5
    label_ilr: str = "0+"
    words: int = 0
    in_level: int = 0
    unglossed_in_level: int = 0
    glossed_hits: list[str] = field(default_factory=list)
    out_of_level: list[str] = field(default_factory=list)
    lemmas: set[str] = field(default_factory=set)

    @property
    def coverage(self) -> float:
        return self.in_level / self.words if self.words else 1.0

    @property
    def unglossed_coverage(self) -> float:
        return self.unglossed_in_level / self.words if self.words else 1.0

    @property
    def avg_sentence(self) -> float:
        return self.sentence_japanese / self.sentences if self.sentences else 0.0

    @property
    def kanji_density(self) -> float:
        return self.kanji / self.chars if self.chars else 0.0

    @property
    def label(self) -> str:
        return f"{'above N1' if self.label_jlpt == 0 else 'N' + str(self.label_jlpt)} · ILR {self.label_ilr}"


# --- Read-along lines and voices (D-205) ---------------------------------------------------------------------

SPEECH_PREFIX = re.compile(r"^\s*([^\s「『：:]{1,12})\s*[：:]?\s*(?=「)")


@dataclass
class Line:
    index: int
    start: int  # UTF-16 offsets into the body (what Kotlin String indices use)
    end: int
    text: str  # the sentence as it appears in the body
    spoken: str  # what the audio says (a "Name：" prefix dropped)
    speaker: str  # "" for narration, else the cast name
    voice: str  # "narration" | "female" | "male" | "male-senior"


def read_along(body: str, cast: list[dict]) -> list[Line]:
    """Sentences of [body] with who says them. A sentence that opens with 「 (optionally after "Name：" or
    "Name" from the cast) is speech; the speaker is the prefixed name, else the cast member named most recently
    before it, else the first cast member. Speech with no cast is read by the second (female) voice. Everything
    else, including sentences that only quote, is narration."""
    names = {c["name"]: c.get("voice", "female") for c in cast}
    lines = []
    depth, open_speaker, para_end = 0, "", -1
    for i, (a, b) in enumerate(sentences(body)):
        raw = body[a:b]
        if a > para_end:  # a new paragraph closes any quote left open
            depth, open_speaker = 0, ""
            nl = body.find("\n", a)
            para_end = len(body) if nl < 0 else nl
        text = raw.strip()
        lead = len(raw) - len(raw.lstrip())
        trail = len(raw) - len(raw.rstrip())
        a2, b2 = a + lead, b - trail
        speaker, spoken = "", text
        m = SPEECH_PREFIX.match(text)
        if depth > 0:  # the rest of a quote that spans sentences: 「ありがとう。助かります。」
            speaker = open_speaker
        elif m and m.group(1) in names:
            speaker = m.group(1)
            spoken = text[m.end():]
        elif text.startswith(("「", "『")):
            before = body[:a]
            best, at = "", -1
            for n in names:
                pos = before.rfind(n)
                if pos > at:
                    best, at = n, pos
            speaker = best or (cast[0]["name"] if cast else "?")
        depth = max(0, depth + text.count("「") + text.count("『") - text.count("」") - text.count("』"))
        open_speaker = speaker if depth > 0 else ""
        voice = NARRATION if not speaker else names.get(speaker, "female")
        lines.append(Line(i, utf16_len(body[:a2]), utf16_len(body[:b2]), text, spoken, speaker, voice))
    return lines
