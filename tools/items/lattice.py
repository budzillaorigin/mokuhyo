"""Python port of app.tsumugi.jp.tokenizer.LatticeTokenizer over content/packs/tokenizer.sqlite (mecab-ipadic).

Used by gen_jlpt.py to get readings of inflected words, parts of speech and bunsetsu boundaries. Same algorithm and
data as the app's analyzer (Viterbi over dictionary + char.def/unk.def unknown-word candidates), so generated
items segment the way the app does. Build tool only; nothing here ships.
"""

from __future__ import annotations

import sqlite3
import struct
from dataclasses import dataclass
from pathlib import Path

MAX_GROUP = 24
SENTENCE_ENDS = "。！？\n"


@dataclass(frozen=True)
class Token:
    surface: str
    start: int
    end: int
    pos: tuple[str, ...]  # up to 4 levels, "*" dropped
    conj_type: str
    conj_form: str
    base: str
    reading: str  # katakana, "" when unknown

    @property
    def pos1(self) -> str:
        return self.pos[0] if self.pos else ""

    @property
    def pos2(self) -> str:
        return self.pos[1] if len(self.pos) > 1 else ""


@dataclass(frozen=True)
class _Entry:
    length: int
    left: int
    right: int
    cost: int
    features: str
    base: str
    reading: str
    unknown: bool


class Lattice:
    def __init__(self, pack: Path):
        db = sqlite3.connect(f"file:{pack}?mode=ro", uri=True)
        pos = dict(db.execute("SELECT id, features FROM pos"))
        fwd, bwd, blob = db.execute("SELECT forward_size, backward_size, costs FROM connection").fetchone()
        self.bwd = bwd
        self.costs = struct.unpack(f"<{fwd * bwd}h", blob)
        cats = db.execute("SELECT name, invoke, grp, length FROM char_category").fetchall()
        index = {c[0]: i for i, c in enumerate(cats)}
        self.cats = [(bool(c[1]), bool(c[2]), c[3]) for c in cats]
        default = index["DEFAULT"]
        self.primary = bytearray([default]) * 0x10000
        self.masks = [1 << default] * 0x10000
        for _, first, last, names in db.execute("SELECT ord, first, last, categories FROM char_range ORDER BY ord"):
            ns = [n for n in names.split(" ") if n in index]
            if not ns:
                continue
            mask = 0
            for n in ns:
                mask |= 1 << index[n]
            for c in range(first, min(last, 0xFFFF) + 1):
                self.primary[c] = index[ns[0]]
                self.masks[c] = mask
        self.space = index.get("SPACE", -1)
        self.unknowns: list[list[_Entry]] = [[] for _ in cats]
        for cat, left, right, cost, pid in db.execute(
            "SELECT category, left_id, right_id, cost, pos_id FROM unknown ORDER BY category, seq"
        ):
            if cat in index:
                self.unknowns[index[cat]].append(_Entry(0, left, right, cost, pos[pid], "", "", True))
        self.lex: dict[str, list[_Entry]] = {}
        for surface, left, right, cost, pid, base, reading in db.execute(
            "SELECT surface, left_id, right_id, cost, pos_id, base, reading FROM morpheme ORDER BY surface, seq"
        ):
            self.lex.setdefault(surface, []).append(
                _Entry(len(surface), left, right, cost, pos[pid], base, reading, False)
            )
        self.max_len = max(map(len, self.lex))
        db.close()

    def tokenize(self, text: str) -> list[Token]:
        out: list[Token] = []
        start = 0
        for i, ch in enumerate(text):
            if ch in SENTENCE_ENDS:
                self._sentence(text, start, i + 1, out)
                start = i + 1
        if start < len(text):
            self._sentence(text, start, len(text), out)
        return out

    def _conn(self, right: int, left: int) -> int:
        return self.costs[right * self.bwd + left]

    def _candidates(self, s: str, q: int) -> list[_Entry]:
        out: list[_Entry] = []
        for ln in range(1, min(self.max_len, len(s) - q) + 1):
            out.extend(self.lex.get(s[q:q + ln], ()))
        code = ord(s[q])
        cat = self.primary[code] if code < 0x10000 else 0
        invoke, group, length = self.cats[cat]
        if invoke or not out:
            bit = 1 << cat
            run = 1
            while q + run < len(s) and run < MAX_GROUP and ord(s[q + run]) < 0x10000 and self.masks[ord(s[q + run])] & bit:
                run += 1
            lengths = []
            if group:
                lengths.append(run)
            lengths += [n for n in range(1, length + 1) if n <= run and n not in lengths]
            for n in lengths:
                out.extend(_Entry(n, u.left, u.right, u.cost, u.features, "", "", True) for u in self.unknowns[cat])
        return out

    def _sentence(self, text: str, frm: int, to: int, out: list[Token]) -> None:
        s = text[frm:to]
        n = len(s)
        # node = (total, start, end, entry, prev)
        ends: list[list | None] = [None] * (n + 1)
        ends[0] = [(0, 0, 0, None, None)]
        eos = None
        for p in range(n + 1):
            prevs = ends[p]
            if not prevs:
                continue
            q = p
            while q < n and ord(s[q]) < 0x10000 and self.primary[ord(s[q])] == self.space:
                q += 1
            if q == n:
                best = min(prevs, key=lambda nd: nd[0] + self._conn(nd[3].right if nd[3] else 0, 0))
                total = best[0] + self._conn(best[3].right if best[3] else 0, 0)
                if eos is None or total < eos[0]:
                    eos = (total, n, n, None, best)
                continue
            for e in self._candidates(s, q):
                best, cost = None, 1 << 60
                for nd in prevs:
                    c = nd[0] + self._conn(nd[3].right if nd[3] else 0, e.left)
                    if c < cost:
                        best, cost = nd, c
                node = (cost + e.cost, q, q + e.length, e, best)
                slot = ends[q + e.length]
                if slot is None:
                    ends[q + e.length] = [node]
                else:
                    slot.append(node)
        path = []
        node = eos[4] if eos else None
        while node is not None and node[3] is not None:
            path.append(node)
            node = node[4]
        for _, a, b, e, _ in reversed(path):
            f = [x for x in e.features.split(",")]
            f += ["*"] * (6 - len(f))
            surface = s[a:b]
            out.append(Token(
                surface=surface, start=frm + a, end=frm + b,
                pos=tuple(x for x in f[:4] if x and x != "*"),
                conj_type="" if f[4] == "*" else f[4], conj_form="" if f[5] == "*" else f[5],
                base=e.base or surface, reading=e.reading,
            ))

    def forms_of(self, base: str) -> dict[tuple[str, str], list[tuple[str, str]]]:
        """(conj_type, conj_form) -> [(surface, reading)] for every lexicon row whose base form is `base`.
        Built lazily (one pass over the lexicon) the first time it is needed."""
        if not hasattr(self, "_by_base"):
            by: dict[str, dict[tuple[str, str], list[tuple[str, str]]]] = {}
            for surface, entries in self.lex.items():
                for e in entries:
                    f = e.features.split(",")
                    if len(f) < 6 or f[4] == "*":
                        continue
                    b = e.base or surface
                    by.setdefault(b, {}).setdefault((f[4], f[5]), []).append((surface, e.reading))
            self._by_base = by
        return self._by_base.get(base, {})
