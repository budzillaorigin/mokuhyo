"""Aozora Bunko texts for the poetry corner and the reading circle (BRIEF_V2 §6.14, §7; DECISIONS D-276, D-277).

Every work is pinned in packs/sources.lock as `aozora-<work id>` (its text zip, which Aozora versions by file name) and
checked against the pinned catalogue (`aozora-catalogue`): the work must be 作品著作権フラグ なし, every person on the
work (author, translator, editor) must be 人物著作権フラグ なし, and each author's death date must be before 1968
(authors who died in 1967 or earlier were already in the public domain in Japan under the old life+50 rule when the
term became life+70 on 2018-12-30; nobody who died later is free before 2038). Aozora's own files may be redistributed;
the colophon (底本, 入力, 校正 and the volunteers' note) is kept with the text and shown in the app.

The parser mirrors the app's AozoraImporter (shared/.../reader/Aozora.kt): the notes block between dashed lines is
dropped, ruby 漢字《かんじ》 and ｜base《reading》 become hints {start, base, reading} over the clean text, and editor
annotations ［＃…］ are removed. Poems keep their line breaks and one blank line between stanzas.
"""

from __future__ import annotations

import csv
import io
import re
import sys
import unicodedata
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common import source

CATALOGUE = "aozora-catalogue"
PD_DEATH_BEFORE = 1968  # died 1967 or earlier: public domain in Japan (see module docstring)

ANNOTATION = re.compile(r"［＃[^］]*］")
GAIJI = re.compile(r"※［＃[^］]*］")
HEADING_INLINE = re.compile(r"［＃「([^」]*)」は(大|中|小)見出し］")
HEADING_START = re.compile(r"［＃ここから(大|中|小)見出し］")
HEADING_END = re.compile(r"［＃ここで(大|中|小)見出し終わり］")
LEVEL = {"大": 1, "中": 2, "小": 3}


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def is_ruby_base(c: str) -> bool:
    return "一" <= c <= "鿿" or "㐀" <= c <= "䶿" or c in "々〆ヶ〇"


@dataclass
class Clean:
    text: str
    ruby: list[dict] = field(default_factory=list)


def strip_line(line: str) -> Clean:
    """One source line without annotations, with its ruby as hints over the cleaned line."""
    line = ANNOTATION.sub("", line)
    out: list[str] = []
    ruby: list[dict] = []
    explicit = -1
    i = 0
    while i < len(line):
        c = line[i]
        if c == "｜":
            explicit = len(out)
            i += 1
            continue
        if c == "《":
            close = line.find("》", i)
            if close < 0:
                out.append(c)
                i += 1
                continue
            reading = line[i + 1:close]
            start = explicit
            if start < 0:
                j = len(out)
                while j > 0 and is_ruby_base(out[j - 1]):
                    j -= 1
                start = j if j < len(out) else -1
            if 0 <= start < len(out):
                ruby.append({"start": start, "base": "".join(out[start:]), "reading": reading})
            explicit = -1
            i = close + 1
            continue
        out.append(c)
        i += 1
    return Clean("".join(out), ruby)


@dataclass
class Work:
    work_id: str
    title: str
    author: str
    lines: list[str]  # the body's raw lines (notes block and colophon removed), annotations intact
    colophon: str

    def headings(self) -> list[tuple[int, int, str]]:
        """(line index, level, clean text) for every heading in the body."""
        out = []
        block: tuple[int, int, list[str]] | None = None
        for n, raw in enumerate(self.lines):
            if m := HEADING_START.search(raw):
                block = (n, LEVEL[m.group(1)], [])
                continue
            if block is not None:
                if HEADING_END.search(raw):
                    out.append((block[0], block[1], "".join(block[2]).strip()))
                    block = None
                else:
                    block[2].append(strip_line(raw).text.strip())
                continue
            if m := HEADING_INLINE.search(raw):
                out.append((n, LEVEL[m.group(2)], strip_line(raw).text.strip()))
        return out

    def section(self, title: str, occurrence: int = 1) -> list[str]:
        """The raw lines under the heading [title] (its [occurrence]-th use), up to the next heading of the same or a
        higher level."""
        heads = self.headings()
        found = [h for h in heads if norm_title(h[2]) == norm_title(title)]
        if len(found) < occurrence:
            raise ValueError(f"{self.work_id} {self.title}: no heading {title!r} (#{occurrence})")
        start, level, _ = found[occurrence - 1]
        body_start = start + 1
        if HEADING_START.search(self.lines[start]):
            body_start = next(n for n in range(start, len(self.lines)) if HEADING_END.search(self.lines[n])) + 1
        end = next((n for n, lv, _ in heads if n > start and lv <= level), len(self.lines))
        return self.lines[body_start:end]


def norm_title(s: str) -> str:
    return re.sub(r"[\s　]", "", nfc(s))


def parse(raw: str, work_id: str) -> Work:
    text = nfc(raw.lstrip("﻿")).replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")
    title = next((ln.strip() for ln in lines if ln.strip()), "")
    author = next((ln.strip() for ln in lines[lines.index(next(ln for ln in lines if ln.strip())) + 1:] if ln.strip()), "")
    dashed = [n for n, ln in enumerate(lines) if len(ln.strip()) >= 10 and set(ln.strip()) == {"-"}]
    start = dashed[1] + 1 if len(dashed) >= 2 else next(n for n, ln in enumerate(lines) if ln.strip()) + 2
    end = next((n for n, ln in enumerate(lines) if ln.startswith("底本：")), len(lines))
    colophon = "\n".join(ln.rstrip() for ln in lines[end:]).strip()
    return Work(work_id, title, author, lines[start:end], colophon)


def body(lines: list[str], replace: dict[str, str] | None = None, poem: bool = True, drop: list[str] | None = None) -> Clean:
    """Clean text of [lines]: gaiji replaced from [replace] (text left with ※［＃…］ is an error), annotations removed,
    trailing spaces dropped, and lines whose text is in [drop] (a section label printed after a poem) left out. Poems
    keep stanza breaks as one blank line; prose keeps one paragraph per line."""
    out: list[str] = []
    ruby: list[dict] = []
    blank = False
    for raw in lines:
        for k, v in (replace or {}).items():
            raw = raw.replace(k, v)
        if GAIJI.search(raw):
            raise ValueError(f"unmapped gaiji in {raw!r}; add it to the entry's `replace`")
        c = strip_line(raw)
        t = c.text.rstrip()
        if drop and t.strip() in drop:
            continue
        if not t.strip():
            blank = bool(out)
            continue
        if not poem:
            t = t.strip()
            shift = len(c.text) - len(c.text.lstrip())
        else:
            shift = 0
        prefix = "\n\n" if (blank and poem) else ("\n" if out else "")
        base = sum(len(x) for x in out) + len(prefix)
        for h in c.ruby:
            if h["start"] - shift >= 0:
                ruby.append({**h, "start": base + h["start"] - shift})
        out.append(prefix + t)
        blank = False
    return Clean("".join(out), ruby)


def fetch(work_id: str) -> Work:
    """The pinned text of [work_id] (downloaded into tools/.cache and hash-checked by common.source)."""
    path = source(f"aozora-{work_id}")
    with zipfile.ZipFile(path) as zf:
        name = next(n for n in zf.namelist() if n.lower().endswith(".txt"))
        raw = zf.read(name).decode("cp932")
    return parse(raw, work_id)


@dataclass
class CatalogueRow:
    work_id: str
    title: str
    person: str
    role: str
    died: str
    work_free: bool
    person_free: bool
    text_url: str
    orthography: str
    card_url: str
    text_updated: str


def catalogue() -> dict[str, list[CatalogueRow]]:
    """The pinned Aozora catalogue, rows grouped by work id."""
    path = source(CATALOGUE)
    with zipfile.ZipFile(path) as zf:
        name = next(n for n in zf.namelist() if n.endswith(".csv"))
        text = zf.read(name).decode("utf-8-sig")
    out: dict[str, list[CatalogueRow]] = {}
    for r in csv.DictReader(io.StringIO(text)):
        out.setdefault(r["作品ID"], []).append(CatalogueRow(
            r["作品ID"], r["作品名"], r["姓"] + r["名"], r["役割フラグ"], r["没年月日"], r["作品著作権フラグ"] == "なし",
            r["人物著作権フラグ"] == "なし", r["テキストファイルURL"], r["文字遣い種別"], r["図書カードURL"],
            r["テキストファイル最終更新日"],
        ))
    return out


def check_public_domain(work_id: str, rows: dict[str, list[CatalogueRow]], author: str, died: str) -> list[str]:
    """Why [work_id] may not ship (empty = public domain by every check in the module docstring)."""
    got = rows.get(work_id)
    if not got:
        return [f"{work_id}: not in the Aozora catalogue"]
    errors = []
    for r in got:
        if not r.work_free:
            errors.append(f"{work_id}: 作品著作権フラグ is not なし")
        if not r.person_free:
            errors.append(f"{work_id}: {r.person} ({r.role}) is 人物著作権フラグ あり")
        if r.role == "著者":
            year = int(r.died[:4]) if r.died[:4].isdigit() else 9999
            if year >= PD_DEATH_BEFORE:
                errors.append(f"{work_id}: author {r.person} died {r.died}, not public domain in Japan")
            if norm_title(r.person) != norm_title(author):
                errors.append(f"{work_id}: catalogue author {r.person} != {author}")
            if r.died != died:
                errors.append(f"{work_id}: catalogue death date {r.died} != recorded {died}")
    return sorted(set(errors))
