"""Build content/packs/tracks.sqlite: interest and domain tracks (BRIEF_V2 §6.5, DECISIONS D-210…D-219).

A track = a themed word list (JMdict ids) + kanji subset + role-play scenarios + two-speaker dialogues + drills
(keigo, email templates, fill-in, synonym/antonym pick, usage yes/no, meaning pick, memorize-and-perform) +
optional can-do situations, cultural tasks, ILR reading passages and link-only references. Sources live in
tools/packs/tracks/<track>.json (format in docs/CONTENT_PACKS.md); everything drafted there is source "llm",
verified false, and shows the AI-generated badge until reviewed.

    uv run python packs/build_tracks.py                      # validate + build content/packs/tracks.sqlite
    uv run python packs/build_tracks.py validate             # validate only, print counts per track
    uv run python packs/build_tracks.py resolve gaming       # fill in JMdict ids/glosses for words without one
    uv run python packs/build_tracks.py draft gaming --kind words --count 40 \
        --endpoint http://<lan-ip>:11434/v1 --model qwen2.5:14b   # extend a track through an LLM

Options: --dictionary PATH (default content/packs/dictionary.sqlite, or $TSUMUGI_DICTIONARY) and --out PATH.
Every JMdict id is checked against the dictionary pack; the build fails on anything that doesn't resolve.
`draft` appends only items that validate and never reuses an id, so re-runs add without duplicating.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

from build_practice import REGISTER_GUIDE, chunk, utf16_offsets
from common import (
    DICTIONARY_PACK,
    PACKS,
    REPO,
    dumps,
    finish_pack,
    is_kanji,
    log,
    nfc,
    open_pack,
    reset_tables,
    set_meta,
    to_hiragana,
)

HERE = Path(__file__).resolve().parent
TRACKS_DIR = HERE / "tracks"
TRACKS_PACK = PACKS / "tracks.sqlite"
TRACKS_SQ = REPO / "shared/src/commonMain/sqldelightTracks/app/tsumugi/tracks/db/tracks.sq"
ILR_BANDS = REPO / "tools/items/ilr_bands.json"
PRACTICE_SOURCES = [(HERE / "speaking" / "scenarios.json", "scenarios"), (HERE / "listening" / "dialogues.json", "dialogues")]
TRACKS_PACK_VERSION = "1"

# The seven launch tracks, in display order (BRIEF_V2 §6.5). A file per id in tools/packs/tracks/.
TRACK_ORDER = ["gaming", "business", "family", "daily-life", "schoolchild", "military", "performing"]

TABLES = {
    "pack_meta", "track", "track_word", "track_kanji", "track_scenario", "track_scripted_turn", "track_dialogue",
    "track_dialogue_line", "track_dialogue_question", "track_drill", "track_situation", "track_task",
    "track_reading", "track_link",
}
ILR_LEVELS = ["0+", "1", "1+", "2", "2+", "3", "3+", "4"]
REGISTERS = set(REGISTER_GUIDE)
VOICES = {"male", "female"}
AGES = {"young", "adult", "senior"}
SOURCES = {"llm", "verified", "derived", "human"}
DRILL_TYPES = {"keigo", "email", "fill_in", "synonym", "usage", "meaning", "perform"}
KEIGO_TARGETS = {"sonkeigo", "kenjogo", "teineigo"}
KEIGO_FORMS = {"dictionary", "masu", "past", "masu-past", "te"}
# Id infix per drill type, as in the launch sources (business-keigo-001, gaming-fi-001 …).
DRILL_ID_KIND = {"keigo": "keigo", "email": "email", "fill_in": "fi", "synonym": "syn", "usage": "use", "meaning": "mean", "perform": "perf"}
ID_LISTS = ("scenarios", "dialogues", "drills", "situations", "tasks", "readings")
TURNS = (3, 16)
# A derived kanji subset keeps the most-used kanji of the word list (a header "kanjiLimit" overrides).
DERIVED_KANJI_LIMIT = 150
LINES = (4, 16)
BLANK = "（　　）"
TEMPLATE_SLOT = re.compile(r"｛(\d+)｝")
JAPANESE = re.compile(r"[ぁ-ゖァ-ヺ一-鿿々〆]")
# JMdict part-of-speech code -> inflection class (mirror of app.tsumugi.jp.WordClass.fromJmdictPos).
VERB_CLASS = {
    **dict.fromkeys(["v1", "v1-s"], "ICHIDAN"),
    **dict.fromkeys(["v5k", "v5k-s", "v5r", "v5r-i", "v5aru", "v5u", "v5u-s", "v5g", "v5s", "v5t", "v5n", "v5b", "v5m"], "GODAN"),
    "vk": "KURU",
    **dict.fromkeys(["vs", "vs-i", "vs-s"], "SURU"),
}


class BuildError(Exception):
    pass


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise BuildError(msg)


def is_nfc(s: str) -> bool:
    return nfc(s) == s


def japanese(s: str, where: str) -> str:
    require(isinstance(s, str) and bool(JAPANESE.search(s)), f"{where}: expected Japanese text, got {s!r}")
    require(is_nfc(s), f"{where}: text is not NFC: {s!r}")
    return s


def text(s, where: str) -> str:
    require(isinstance(s, str) and s.strip() != "", f"{where}: expected non-empty text")
    require(is_nfc(s), f"{where}: text is not NFC: {s!r}")
    return s


def fold(s: str) -> str:
    return to_hiragana(nfc(s))


# ---------------------------------------------------------------- dictionary

class Dictionary:
    """JMdict forms, readings, parts of speech, first glosses, JLPT tags, pitch and KANJIDIC2 kanji."""

    def __init__(self, path: Path) -> None:
        import sqlite3
        require(path.exists(), f"{path} missing: run build_dictionary.py first (or pass --dictionary)")
        db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.rank: dict[int, int] = {}
        self.jlpt: dict[int, int | None] = {}
        for eid, rank, jlpt in db.execute("SELECT id, rank, jlpt FROM entry"):
            self.rank[eid] = rank
            self.jlpt[eid] = jlpt
        self.forms: dict[int, set[str]] = defaultdict(set)
        self.kana: dict[int, list[str]] = defaultdict(list)
        self.by_form: dict[str, list[int]] = defaultdict(list)
        for eid, t in db.execute("SELECT entry_id, text FROM entry_kanji ORDER BY entry_id, ord"):
            self.forms[eid].add(t)
            self.by_form[t].append(eid)
        for eid, t in db.execute("SELECT entry_id, text FROM entry_kana ORDER BY entry_id, ord"):
            self.forms[eid].add(t)
            self.kana[eid].append(t)
            self.by_form[t].append(eid)
        for ids in self.by_form.values():
            ids.sort(key=lambda e: (self.rank[e], e))
        self.pos: dict[int, list[str]] = defaultdict(list)
        self.gloss: dict[int, str] = {}
        for eid, pos, glosses in db.execute("SELECT entry_id, pos, glosses FROM sense ORDER BY entry_id, ord"):
            for p in json.loads(pos) if pos else []:
                if p not in self.pos[eid]:
                    self.pos[eid].append(p)
            if eid not in self.gloss and glosses:
                gl = json.loads(glosses)
                if gl:
                    self.gloss[eid] = "; ".join(gl[:3])
        self.pitch = {(t, fold(r)): a for t, r, a in db.execute("SELECT text, reading, accents FROM pitch")}
        self.kanji: dict[str, tuple[str, list[str]]] = {}
        for lit, meanings in db.execute("SELECT literal, meanings FROM kanji"):
            self.kanji[lit] = (json.loads(meanings)[0] if meanings and json.loads(meanings) else "", [])
        for k, r in db.execute("SELECT kanji, radical FROM kanji_component"):
            if k in self.kanji:
                self.kanji[k][1].append(r)
        self.radicals = {r for (r,) in db.execute("SELECT radical FROM radical")}
        db.close()

    def candidates(self, form: str, reading: str | None = None) -> list[int]:
        ids = self.by_form.get(nfc(form), [])
        if reading:
            r = fold(reading)
            ids = [e for e in ids if r in {fold(k) for k in self.kana[e]}]
        return ids

    def lookup(self, form: str) -> int | None:
        ids = self.by_form.get(nfc(form))
        return ids[0] if ids else None

    def matches(self, eid: int, form: str, reading: str) -> bool:
        return eid in self.rank and form in self.forms[eid] and fold(reading) in {fold(k) for k in self.kana[eid]}

    def verb_class(self, eid: int) -> str | None:
        return next((VERB_CLASS[p] for p in self.pos[eid] if p in VERB_CLASS), None)

    def accents(self, form: str, reading: str) -> str:
        return self.pitch.get((form, fold(reading))) or self.pitch.get((reading, fold(reading))) or ""


# ---------------------------------------------------------------- loading

def track_files(names: list[str] | None = None) -> list[Path]:
    """Main source file per track (<track>.json), in display order."""
    files = sorted((p for p in TRACKS_DIR.glob("*.json") if "." not in p.stem),
                   key=lambda p: (TRACK_ORDER.index(p.stem) if p.stem in TRACK_ORDER else 99, p.stem))
    if names:
        missing = [n for n in names if not (TRACKS_DIR / f"{n}.json").exists()]
        require(not missing, f"no track source for {missing} in {TRACKS_DIR}")
        files = [TRACKS_DIR / f"{n}.json" for n in names]
    return files


def part_files(main: Path) -> list[Path]:
    """Optional part files <track>.<part>.json whose lists extend the main file's (e.g. gaming.practice.json)."""
    return sorted(main.parent.glob(f"{main.stem}.*.json"))


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def load_track(main: Path) -> dict:
    doc = load(main)
    for part in part_files(main):
        for key, value in load(part).items():
            if isinstance(value, list):
                doc.setdefault(key, []).extend(value)
    return doc


def save(path: Path, doc: dict) -> None:
    """Pretty JSON with one word or kanji per line, so word-list diffs stay readable."""
    parts = []
    for k, v in doc.items():
        if k in ("words", "kanji") and isinstance(v, list) and v:
            body = "[\n" + ",\n".join("  " + json.dumps(x, ensure_ascii=False) for x in v) + "\n ]"
        else:
            body = json.dumps(v, ensure_ascii=False, indent=1).replace("\n", "\n ")
        parts.append(f" {json.dumps(k)}: {body}")
    path.write_text("{\n" + ",\n".join(parts) + "\n}\n", encoding="utf-8")


def practice_ids() -> set[str]:
    """Scenario/dialogue ids in the practice pack sources (read only), so audio keys never collide."""
    ids: set[str] = set()
    for path, key in PRACTICE_SOURCES:
        if path.exists():
            ids |= {x["id"] for x in load(path).get(key, [])}
    return ids


# ---------------------------------------------------------------- validation (pure: returns rows to insert)

class Track:
    """A validated track: rows per table plus counts. Raises BuildError on the first problem."""

    def __init__(self, doc: dict, dic: Dictionary, ord_: int, taken_ids: set[str], bands: dict) -> None:
        self.dic = dic
        self.bands = bands
        tid = doc.get("track")
        require(isinstance(tid, str) and re.fullmatch(r"[a-z][a-z-]*", tid or ""), f"bad track id {tid!r}")
        self.id = tid
        self.where = f"track {tid}"
        self.default_source = doc.get("source", "llm")
        require(self.default_source in SOURCES, f"{self.where}: bad source {self.default_source}")
        self.taken = taken_ids
        self.rows: dict[str, list[tuple]] = defaultdict(list)
        jmin, jmax = doc.get("jlpt", [5, 1])
        require(1 <= jmax <= jmin <= 5, f"{self.where}: jlpt must be [easiest, hardest] within 5..1")
        self.jlpt_range = (jmin, jmax)
        self.ilr = doc.get("ilr", "")
        if self.ilr:
            lo, hi = self.ilr.split("-")
            require(lo in ILR_LEVELS and hi in ILR_LEVELS, f"{self.where}: bad ilr range {self.ilr}")
            self.ilr_range = (ILR_LEVELS.index(lo), ILR_LEVELS.index(hi))
        else:
            self.ilr_range = None
        self.word_ids: dict[int, int] = {}
        self.errors: list[str] = []
        self.each("word", doc.get("words", []), self.word)
        if not self.errors:
            self.guard("kanji", lambda: self.kanji(doc.get("kanji"), doc.get("kanjiLimit", DERIVED_KANJI_LIMIT)))
        self.each("scenario", doc.get("scenarios", []), self.scenario)
        self.each("dialogue", doc.get("dialogues", []), self.dialogue)
        self.each("drill", doc.get("drills", []), self.drill)
        self.each("situation", doc.get("situations", []), self.situation)
        self.each("task", doc.get("tasks", []), self.task)
        self.each("reading", doc.get("readings", []), self.reading)
        self.each("link", doc.get("links", []), self.link)
        if self.errors:
            shown = self.errors[:40]
            more = f"\n  … and {len(self.errors) - len(shown)} more" if len(self.errors) > len(shown) else ""
            raise BuildError(f"{len(self.errors)} problem(s):\n  " + "\n  ".join(shown) + more)
        drill_types: dict[str, int] = defaultdict(int)
        for r in self.rows["track_drill"]:
            drill_types[r[3]] += 1
        self.counts = {
            "words": len(self.rows["track_word"]),
            "kanji": len(self.rows["track_kanji"]),
            "scenarios": len(self.rows["track_scenario"]),
            "turns": len(self.rows["track_scripted_turn"]),
            "dialogues": len(self.rows["track_dialogue"]),
            "drills": len(self.rows["track_drill"]),
            **{f"drills.{k}": v for k, v in sorted(drill_types.items())},
            "situations": len(self.rows["track_situation"]),
            "canDo": sum(len(json.loads(r[5])) for r in self.rows["track_situation"]),
            "tasks": len(self.rows["track_task"]),
            "readings": len(self.rows["track_reading"]),
            "links": len(self.rows["track_link"]),
        }
        self.counts = {k: v for k, v in self.counts.items() if v or k in ("words", "kanji")}
        require(self.counts["words"] > 0, f"{self.where}: has no words")
        self.rows["track"].append((
            tid, ord_, text(doc["titleEn"], f"{self.where} titleEn"), japanese(doc["titleJa"], f"{self.where} titleJa"),
            text(doc["description"], f"{self.where} description"), doc.get("inspiredBy", ""), jmin, jmax, self.ilr,
            dumps(self.counts), doc.get("license", "CC BY-SA 4.0"), doc.get("attribution", "Tsumugi contributors"),
            self.default_source,
        ))

    # -- helpers
    def guard(self, what: str, fn) -> None:
        """Runs one item's validation, collecting its problem instead of stopping at the first one."""
        try:
            fn()
        except BuildError as e:
            self.errors.append(str(e))
        except (KeyError, TypeError, ValueError, AttributeError, IndexError) as e:
            self.errors.append(f"{self.where} {what}: malformed ({type(e).__name__}: {e})")

    def each(self, what: str, items, fn) -> None:
        if not isinstance(items, list):
            self.errors.append(f"{self.where}: {what}s must be a list")
            return
        for i, item in enumerate(items):
            self.guard(f"{what} {i}", lambda item=item, i=i: fn(item, i))

    def link(self, lk: dict, i: int) -> None:
        require(str(lk.get("url", "")).startswith("https://"), f"{self.where} link {i}: url must be https")
        self.rows["track_link"].append((self.id, i, text(lk.get("title"), f"{self.where} link {i}"), lk["url"], lk.get("note", "")))

    def new_id(self, item_id, where: str) -> str:
        require(isinstance(item_id, str) and item_id.startswith(f"{self.id}-"), f"{where}: id {item_id!r} must start with '{self.id}-'")
        require(re.fullmatch(r"[a-z0-9-]+", item_id) is not None, f"{where}: id {item_id!r} may use a-z, 0-9 and '-' only")
        require(item_id not in self.taken, f"{where}: duplicate id {item_id}")
        self.taken.add(item_id)
        return item_id

    def source(self, obj: dict, where: str) -> str:
        s = obj.get("source", self.default_source)
        require(s in SOURCES, f"{where}: bad source {s}")
        if obj.get("verified") is True:
            require(s == "verified", f"{where}: verified items must have source 'verified' (set by the review tool)")
        return s

    def jlpt(self, value, where: str) -> int:
        require(value in (1, 2, 3, 4, 5), f"{where}: bad jlpt {value}")
        jmin, jmax = self.jlpt_range
        require(jmax <= value <= jmin, f"{where}: N{value} is outside the track's N{jmin}–N{jmax} range")
        return value

    def ilr_level(self, value, where: str, required: bool = False) -> str:
        if not value and not required:
            return ""
        require(value in ILR_LEVELS, f"{where}: bad ilr {value!r}")
        if self.ilr_range:
            lo, hi = self.ilr_range
            require(lo <= ILR_LEVELS.index(value) <= hi, f"{where}: ILR {value} is outside the track's {self.ilr} range")
        return value

    def entry(self, form: str, where: str) -> int:
        eid = self.dic.lookup(form)
        require(eid is not None, f"{where}: {form!r} is not a JMdict word")
        return eid

    def choices(self, q: dict, where: str, n: tuple[int, int] = (2, 6)) -> None:
        ch = q.get("choices")
        require(isinstance(ch, list) and n[0] <= len(ch) <= n[1], f"{where}: needs {n[0]}-{n[1]} choices")
        require(all(isinstance(c, str) and c.strip() and is_nfc(c) for c in ch), f"{where}: empty or non-NFC choice")
        require(len(set(ch)) == len(ch), f"{where}: duplicate choices")
        require(isinstance(q.get("answer"), int) and 0 <= q["answer"] < len(ch), f"{where}: answer index out of range")

    # -- words and kanji
    def word(self, w: dict, i: int) -> None:
        where = f"{self.where} word {i} ({w.get('text')})"
        t, r, eid = w.get("text"), w.get("reading"), w.get("id")
        require(isinstance(eid, int), f"{where}: no JMdict id (run `build_tracks.py resolve {self.id}`)")
        japanese(t, where)
        japanese(r, where)
        require(self.dic.matches(eid, t, r), f"{where}: JMdict {eid} has no form {t!r} with reading {r!r}")
        require(eid not in self.word_ids, f"{where}: JMdict {eid} is already word {self.word_ids.get(eid)}")
        self.word_ids[eid] = i
        gloss = w.get("gloss") or self.dic.gloss.get(eid, "")
        require(bool(gloss), f"{where}: no gloss")
        self.rows["track_word"].append((
            self.id, len(self.rows["track_word"]), eid, t, r, gloss, w.get("topic", ""), w.get("category", ""),
            self.dic.jlpt.get(eid), self.dic.accents(t, r), w.get("note", ""), self.source(w, where),
        ))

    def kanji(self, explicit: list | None, limit: int | None) -> None:
        words = [(r[2], r[3]) for r in self.rows["track_word"]]
        used: dict[str, list[int]] = defaultdict(list)
        for eid, t in words:
            for c in t:
                if is_kanji(c) and eid not in used[c]:
                    used[c].append(eid)
        if explicit is not None:
            seen = set()
            for i, k in enumerate(explicit):
                lit = k.get("kanji")
                where = f"{self.where} kanji {i} ({lit})"
                require(isinstance(lit, str) and len(lit) == 1 and is_kanji(lit), f"{where}: not a single kanji")
                require(lit in self.dic.kanji, f"{where}: not in KANJIDIC2")
                require(lit not in seen, f"{where}: duplicate")
                seen.add(lit)
                require(lit in used, f"{where}: no word in this track uses it")
                comps = k.get("components", [])
                require(isinstance(comps, list) and comps, f"{where}: needs components")
                for c in comps:
                    require(isinstance(c, str) and len(c) >= 1, f"{where}: bad component {c!r}")
                    require(all(ch in self.dic.kanji or ch in self.dic.radicals or not is_kanji(ch) for ch in c), f"{where}: unknown component {c!r}")
                src = self.source(k, where)
                self.rows["track_kanji"].append((
                    self.id, i, lit, text(k.get("keyword") or self.dic.kanji[lit][0], where), dumps(comps),
                    text(k.get("breakdown", ""), f"{where} breakdown"), text(k.get("hint", ""), f"{where} hint"),
                    dumps(used[lit]), src,
                ))
            return
        # Derived subset: kanji in the track's words, most-used first, then by word order.
        order = sorted(used, key=lambda c: (-len(used[c]), min(self.word_ids[e] for e in used[c])))
        for i, lit in enumerate(order[:limit] if limit else order):
            if lit not in self.dic.kanji:
                continue
            keyword, comps = self.dic.kanji[lit]
            self.rows["track_kanji"].append((self.id, len(self.rows["track_kanji"]), lit, keyword, dumps(comps), "", "", dumps(used[lit]), "derived"))

    # -- scenarios and dialogues (practice formats)
    def scenario(self, s: dict, i: int) -> None:
        where = f"{self.where} scenario {i} ({s.get('id')})"
        sid = self.new_id(s.get("id"), where)
        jlpt = self.jlpt(s.get("jlpt"), where)
        ilr = self.ilr_level(s.get("ilr"), where, required=True)
        require(s.get("register") in REGISTERS, f"{where}: bad register {s.get('register')}")
        goals = s.get("goals", [])
        require(2 <= len(goals) <= 5 and all(isinstance(g, str) and g for g in goals), f"{where}: needs 2-5 goals")
        turns = s.get("turns", [])
        require(TURNS[0] <= len(turns) <= TURNS[1], f"{where}: needs {TURNS[0]}-{TURNS[1]} scripted turns, has {len(turns)}")
        vocab = []
        for w in s.get("vocabulary", []):
            eid = self.entry(w, f"{where} vocabulary")
            vocab.append({"text": nfc(w), "reading": self.dic.kana[eid][0], "entryId": eid})
        require(vocab, f"{where}: needs vocabulary")
        phrases = [japanese(p, f"{where} phrase") for p in s.get("phrases", [])]
        require(phrases, f"{where}: needs phrases")
        for k in ("titleEn", "setting", "learnerRole", "partnerRole", "category"):
            text(s.get(k), f"{where} {k}")
        japanese(s.get("titleJa"), f"{where} titleJa")
        prompt = (
            f"You are role-playing a conversation with a Japanese learner. Your role: {s['partnerRole']}. "
            f"The learner's role: {s['learnerRole']}. Situation: {s['setting']}\n"
            f"Rules: Stay in character at all times and never mention that you are an AI. "
            f"Speak only in {REGISTER_GUIDE[s['register']]}. Keep every reply to one or two short sentences. "
            f"Adapt your vocabulary and grammar to about JLPT N{jlpt} (ILR {ilr}); if the learner seems "
            f"lost, rephrase more simply instead of switching language. Do not use English unless the learner "
            f"explicitly asks for it. Keep all content PG and respectful. "
            f"Steer the conversation so the learner gets a chance to: {'; '.join(goals)}. "
            f"Where natural, use these words: {'、'.join(w['text'] for w in vocab)}. "
            f"Start the conversation with your first line in character."
        )
        self.rows["track_scenario"].append((
            sid, self.id, i, s["titleEn"], s["titleJa"], jlpt, ilr, s["category"], s["setting"], s["learnerRole"],
            s["partnerRole"], s["register"], dumps(goals), dumps(vocab), dumps(phrases), prompt, self.source(s, where),
        ))
        for n, t in enumerate(turns):
            tw = f"{where} turn {n}"
            self.rows["track_scripted_turn"].append((
                sid, n, japanese(t.get("partnerJa"), tw), text(t.get("partnerEn"), tw), text(t.get("intent"), tw),
                japanese(t.get("sample"), f"{tw} sample"),
            ))

    def speakers(self, speakers: list, where: str) -> set[str]:
        ids = {sp.get("id") for sp in speakers}
        require(len(speakers) == 2 and len(ids) == 2, f"{where}: needs exactly two distinct speakers")
        for sp in speakers:
            require(sp.get("voice") in VOICES and sp.get("age") in AGES, f"{where}: bad voice hint {sp}")
            japanese(sp.get("name"), f"{where} speaker name")
        return ids

    def dialogue(self, d: dict, i: int) -> None:
        where = f"{self.where} dialogue {i} ({d.get('id')})"
        did = self.new_id(d.get("id"), where)
        jlpt = self.jlpt(d.get("jlpt"), where)
        ilr = self.ilr_level(d.get("ilr"), where, required=self.ilr_range is not None)
        ids = self.speakers(d.get("speakers", []), where)
        lines = d.get("lines", [])
        require(LINES[0] <= len(lines) <= LINES[1], f"{where}: needs {LINES[0]}-{LINES[1]} lines, has {len(lines)}")
        require(len({ln.get("speaker") for ln in lines}) == 2, f"{where}: both speakers must talk")
        questions = d.get("questions", [])
        require(questions, f"{where}: needs a comprehension question")
        self.rows["track_dialogue"].append((
            did, self.id, i, text(d.get("title"), where), jlpt, ilr, text(d.get("topic"), where),
            dumps(d["speakers"]), self.source(d, where),
        ))
        for n, ln in enumerate(lines):
            lw = f"{where} line {n}"
            require(ln.get("speaker") in ids, f"{lw}: unknown speaker {ln.get('speaker')}")
            ja = japanese(ln.get("ja"), lw)
            gaps = []
            for g in ln.get("gaps", []):
                pos = ja.find(g)
                require(pos >= 0, f"{lw}: gap {g!r} is not in {ja!r}")
                eid = self.entry(g, f"{lw} gap")
                start, end = utf16_offsets(ja, pos, pos + len(g))
                gaps.append({"text": g, "start": start, "end": end, "entryId": eid})
            gaps.sort(key=lambda x: x["start"])
            self.rows["track_dialogue_line"].append((did, n, ln["speaker"], ja, text(ln.get("en"), lw), dumps(gaps), dumps(chunk(ja))))
        for n, q in enumerate(questions):
            qw = f"{where} question {n}"
            self.choices(q, qw)
            self.rows["track_dialogue_question"].append((did, n, text(q.get("question"), qw), dumps(q["choices"]), q["answer"]))

    # -- drills
    def drill(self, d: dict, i: int) -> None:
        kind = d.get("type")
        where = f"{self.where} drill {i} ({d.get('id')}, {kind})"
        require(kind in DRILL_TYPES, f"{where}: unknown drill type")
        did = self.new_id(d.get("id"), where)
        jlpt = self.jlpt(d["jlpt"], where) if d.get("jlpt") is not None else None
        payload = {k: v for k, v in d.items() if k not in ("id", "type", "topic", "jlpt", "source", "verified", "reviewed")}
        getattr(self, f"drill_{kind}")(payload, where)
        self.rows["track_drill"].append((did, self.id, i, kind, d.get("topic", ""), jlpt, dumps(payload), self.source(d, where)))

    def answers(self, values, where: str) -> list[str]:
        require(isinstance(values, list) and values, f"{where}: needs answers")
        for a in values:
            japanese(a, f"{where} answer")
        require(len(set(values)) == len(values), f"{where}: duplicate answers")
        return values

    def drill_keigo(self, p: dict, where: str) -> None:
        japanese(p.get("plain"), f"{where} plain")
        require(p.get("target") in KEIGO_TARGETS, f"{where}: target must be one of {sorted(KEIGO_TARGETS)}")
        p.setdefault("form", "masu")
        require(p["form"] in KEIGO_FORMS, f"{where}: form must be one of {sorted(KEIGO_FORMS)}")
        self.answers(p.get("answers"), where)
        if p.get("sentence"):
            japanese(p["sentence"], where)
            require(p["sentence"].count(BLANK) == 1, f"{where}: sentence needs exactly one {BLANK}")
        text(p.get("explanation"), f"{where} explanation")
        # The verb the rules conjugate: `verb` if given (e.g. 知る for 知っている), else the plain form itself.
        verb = p.get("verb", p["plain"])
        eid = self.dic.lookup(verb)
        cls = self.dic.verb_class(eid) if eid is not None else None
        if cls:
            p["verb"] = verb
            p["verbReading"] = self.dic.kana[eid][0]
            p["verbClass"] = cls
            p["entryId"] = eid
        else:
            require("verb" not in p, f"{where}: verb {verb!r} is not a JMdict verb")

    def drill_email(self, p: dict, where: str) -> None:
        text(p.get("title"), where)
        text(p.get("situation"), f"{where} situation")
        japanese(p.get("subject"), f"{where} subject")
        body = japanese(p.get("body"), f"{where} body")
        blanks = p.get("blanks", [])
        slots = [int(m) for m in TEMPLATE_SLOT.findall(body)]
        require(slots == list(range(1, len(blanks) + 1)), f"{where}: body slots {slots} must be ｛1｝…｛{len(blanks)}｝ in order, once each")
        for n, b in enumerate(blanks, start=1):
            bw = f"{where} blank {n}"
            self.answers(b.get("answers"), bw)
            if b.get("choices"):
                require(len(b["choices"]) >= 2 and all(a in b["choices"] for a in b["answers"][:1]), f"{bw}: first answer must be a choice")
            text(b.get("hint"), f"{bw} hint")

    def drill_fill_in(self, p: dict, where: str) -> None:
        s = japanese(p.get("sentence"), where)
        require(s.count(BLANK) == 1, f"{where}: sentence needs exactly one {BLANK}")
        self.answers(p.get("answers"), where)
        if p.get("choices") is not None:
            ch = p["choices"]
            require(isinstance(ch, list) and 2 <= len(ch) <= 6 and len(set(ch)) == len(ch), f"{where}: 2-6 distinct choices")
            require(sum(a in ch for a in p["answers"]) == 1, f"{where}: exactly one answer must be among the choices")
        if p.get("word"):
            p["entryId"] = self.entry(p["word"], f"{where} word")
        text(p.get("en"), f"{where} en")
        text(p.get("explanation"), f"{where} explanation")

    def drill_synonym(self, p: dict, where: str) -> None:
        require(p.get("relation", "synonym") in ("synonym", "antonym"), f"{where}: relation is synonym or antonym")
        p.setdefault("relation", "synonym")
        p["entryId"] = self.entry(japanese(p.get("word"), where), f"{where} word")
        self.choices(p, where, (3, 5))
        for c in p["choices"]:
            japanese(c, f"{where} choice")
        text(p.get("explanation"), f"{where} explanation")

    def drill_usage(self, p: dict, where: str) -> None:
        p["entryId"] = self.entry(japanese(p.get("word"), where), f"{where} word")
        japanese(p.get("sentence"), f"{where} sentence")
        require(isinstance(p.get("correct"), bool), f"{where}: correct must be true or false")
        text(p.get("explanation"), f"{where} explanation")

    def drill_meaning(self, p: dict, where: str) -> None:
        p["entryId"] = self.entry(japanese(p.get("word"), where), f"{where} word")
        self.choices(p, where, (3, 5))
        text(p.get("explanation"), f"{where} explanation")

    def drill_perform(self, p: dict, where: str) -> None:
        text(p.get("title"), where)
        japanese(p.get("titleJa"), f"{where} titleJa")
        text(p.get("setting"), f"{where} setting")
        require(p.get("register") in REGISTERS, f"{where}: bad register")
        ids = self.speakers(p.get("speakers", []), where)
        require(p.get("learner") in ids, f"{where}: learner must be one of the speaker ids")
        staging = p.get("staging", [])
        require(staging and all(isinstance(x, str) and x for x in staging), f"{where}: needs staging notes")
        lines = p.get("lines", [])
        require(LINES[0] <= len(lines) <= LINES[1], f"{where}: needs {LINES[0]}-{LINES[1]} lines")
        require(sum(ln.get("speaker") == p["learner"] for ln in lines) >= 2, f"{where}: the learner needs 2+ lines")
        for n, ln in enumerate(lines):
            require(ln.get("speaker") in ids, f"{where} line {n}: unknown speaker")
            japanese(ln.get("ja"), f"{where} line {n}")
            text(ln.get("en"), f"{where} line {n} en")
            if "stage" in ln:
                text(ln["stage"], f"{where} line {n} stage")

    # -- daily-life extras and readings
    def situation(self, s: dict, i: int) -> None:
        where = f"{self.where} situation {i} ({s.get('id')})"
        sid = self.new_id(s.get("id"), where)
        can_do = s.get("canDo", [])
        require(2 <= len(can_do) <= 8, f"{where}: needs 2-8 can-do statements")
        for n, c in enumerate(can_do):
            text(c.get("en"), f"{where} can-do {n}")
            japanese(c.get("ja"), f"{where} can-do {n}")
        self.rows["track_situation"].append((
            sid, self.id, i, text(s.get("titleEn"), where), japanese(s.get("titleJa"), where), dumps(can_do), self.source(s, where),
        ))

    def task(self, t: dict, i: int) -> None:
        where = f"{self.where} task {i} ({t.get('id')})"
        tid = self.new_id(t.get("id"), where)
        payload = {k: t.get(k, []) for k in ("before", "during", "after", "phrases", "etiquette")}
        payload["place"] = text(t.get("place"), f"{where} place")
        for k in ("before", "during", "after"):
            require(payload[k] and all(isinstance(x, str) and x for x in payload[k]), f"{where}: needs {k} steps")
        require(payload["phrases"], f"{where}: needs phrases")
        for ph in payload["phrases"]:
            japanese(ph, f"{where} phrase")
        self.rows["track_task"].append((
            tid, self.id, i, text(t.get("titleEn"), where), japanese(t.get("titleJa"), where), dumps(payload), self.source(t, where),
        ))

    def reading(self, r: dict, i: int) -> None:
        where = f"{self.where} reading {i} ({r.get('id')})"
        rid = self.new_id(r.get("id"), where)
        ilr = self.ilr_level(r.get("ilr"), where, required=True)
        body = japanese(r.get("body"), where)
        band = self.bands.get(ilr)
        if band:
            chars = [c for c in body if not c.isspace()]
            density = sum(is_kanji(c) for c in chars) / len(chars)
            require(band["minChars"] <= len(chars) <= band["maxChars"], f"{where}: {len(chars)} chars outside ILR {ilr} band {band['minChars']}-{band['maxChars']}")
            lo, hi = band["kanjiDensity"]
            require(lo <= density <= hi, f"{where}: kanji density {density:.2f} outside ILR {ilr} band {lo}-{hi}")
        questions = r.get("questions", [])
        require(questions, f"{where}: needs questions")
        for n, q in enumerate(questions):
            self.choices(q, f"{where} question {n}", (3, 5))
            text(q.get("question"), f"{where} question {n}")
        self.rows["track_reading"].append((
            rid, self.id, i, text(r.get("title"), where), ilr, text(r.get("genre"), where), body, dumps(questions), self.source(r, where),
        ))


def validate_all(dic: Dictionary, names: list[str] | None = None) -> list[Track]:
    taken = practice_ids()
    bands = json.loads(ILR_BANDS.read_text(encoding="utf-8"))["levels"] if ILR_BANDS.exists() else {}
    out = []
    for ord_, path in enumerate(track_files(names)):
        doc = load_track(path)
        require(doc.get("track") == path.stem, f"{path.name}: \"track\" must be {path.stem!r}")
        out.append(Track(doc, dic, ord_, taken, bands))
    return out


# ---------------------------------------------------------------- build

def build(dic: Dictionary, out: Path, names: list[str] | None = None) -> list[Track]:
    tracks = validate_all(dic, names)
    require(tracks, f"no track sources in {TRACKS_DIR}")
    if out.exists():
        out.unlink()
    db = open_pack(out, TRACKS_SQ)
    reset_tables(db, TABLES, TRACKS_SQ)
    for t in tracks:
        for table, rows in t.rows.items():
            if rows:
                marks = ",".join("?" * len(rows[0]))
                db.executemany(f"INSERT INTO {table} VALUES ({marks})", rows)
    totals: dict[str, int] = defaultdict(int)
    for t in tracks:
        for k, v in t.counts.items():
            totals[k] += v
    set_meta(db, pack="tracks", pack_version=TRACKS_PACK_VERSION, tracks=str(len(tracks)), **{k: str(v) for k, v in totals.items()})
    finish_pack(db)
    return tracks


def report(tracks: list[Track]) -> None:
    for t in tracks:
        log(f"tracks: {t.id}: " + ", ".join(f"{k} {v}" for k, v in t.counts.items()))


# ---------------------------------------------------------------- resolve

def resolve(dic: Dictionary, names: list[str]) -> int:
    """Fill `id` (and an empty `gloss`) for words that lack one; report ambiguous and unresolved words."""
    problems = 0
    for main in track_files(names):
        seen: set[int] = {w["id"] for w in load_track(main).get("words", []) if isinstance(w.get("id"), int)}
        for path in [main, *part_files(main)]:
            problems += resolve_file(dic, path, seen)
    return problems


def resolve_file(dic: Dictionary, path: Path, seen: set[int]) -> int:
    problems = changed = 0
    doc = load(path)
    for i, w in enumerate(doc.get("words", [])):
        label = f"{path.name} word {i}: {w.get('text')} 【{w.get('reading')}】"
        if not isinstance(w.get("id"), int):
            all_cands = dic.candidates(w.get("text", ""), w.get("reading"))
            cands = [c for c in all_cands if c not in seen]
            if not cands:
                log(f"{label} {'is a duplicate' if all_cands else 'not found in JMdict'}")
                problems += 1
                continue
            if len(cands) > 1:
                log(f"{label} ambiguous {cands[:4]}; took {cands[0]} ({dic.gloss.get(cands[0], '')})")
            w["id"] = cands[0]
            seen.add(cands[0])
            changed += 1
        if not w.get("gloss"):
            w["gloss"] = dic.gloss.get(w["id"], "")
            changed += 1
    if changed:
        save(path, doc)
    log(f"{path.name}: {changed} word fields filled, {problems} problems")
    return problems


# ---------------------------------------------------------------- draft (LLM, optional)

DRAFT_SHAPES = {
    "words": ('{"words": [{"text": "攻略", "reading": "こうりゃく", "gloss": "walkthrough; strategy guide", "topic": "gameplay"}]}',
              "useful words for this track that are real JMdict dictionary entries (dictionary form)"),
    "scenarios": ('{"scenarios": [<objects exactly like the example scenario>]}', "role-play scenarios"),
    "dialogues": ('{"dialogues": [<objects exactly like the example dialogue>]}', "two-speaker dialogues"),
    "drills": ('{"drills": [<objects exactly like the example drill of that type>]}', "drills"),
}


def chat(endpoint: str, model: str, prompt: str, api_key: str | None, timeout: float) -> str:
    body = json.dumps({"model": model, "temperature": 0.7, "messages": [
        {"role": "system", "content": "You write Japanese-learning content. Reply with one JSON object only."},
        {"role": "user", "content": prompt},
    ]}).encode()
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    req = urllib.request.Request(endpoint.rstrip("/") + "/chat/completions", data=body, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)["choices"][0]["message"]["content"]


def next_id(prefix: str, taken: set[str]) -> str:
    n = 1
    while f"{prefix}{n:03d}" in taken:
        n += 1
    taken.add(f"{prefix}{n:03d}")
    return f"{prefix}{n:03d}"


def draft(dic: Dictionary, name: str, kind: str, count: int, endpoint: str, model: str, drill_type: str | None,
          api_key: str | None, timeout: float) -> None:
    path = TRACKS_DIR / f"{name}.json"
    doc = load(path)
    items = doc.setdefault(kind, [])
    example = next((x for x in items if kind != "drills" or x.get("type") == drill_type), None)
    have = [w["text"] for w in doc.get("words", [])] if kind == "words" else [x.get("titleEn") or x.get("title") or x.get("id") for x in items]
    shape, what = DRAFT_SHAPES[kind]
    prompt = (
        f"Track: {doc['titleEn']} — {doc['description']}\nLevel: JLPT N{doc['jlpt'][0]}–N{doc['jlpt'][1]}"
        + (f", ILR {doc['ilr']}" if doc.get("ilr") else "")
        + f".\nWrite {count} new {what}{f' of type {drill_type}' if drill_type else ''}. Natural, correct Japanese in NFC; "
        "English where the example has English. Invent all people, units and events; never copy published texts.\n"
        f"Already present (do not repeat): {'、'.join(map(str, have[-150:]))}\n"
        f"Example item: {json.dumps(example, ensure_ascii=False) if example else '(none yet)'}\nReply as {shape}."
    )
    raw = chat(endpoint, model, prompt, api_key, timeout)
    m = re.search(r"\{.*\}", raw, re.DOTALL)
    require(m is not None, "the model did not return JSON")
    new = json.loads(m.group(0)).get(kind, [])
    taken = practice_ids() | {
        x["id"] for p in track_files() for key in ID_LISTS for x in load(p).get(key, []) if isinstance(x.get("id"), str)
    }
    added = rejected = 0
    known = {w.get("id") for w in doc.get("words", [])}
    for obj in new:
        obj = json.loads(nfc(json.dumps(obj, ensure_ascii=False)))
        obj["source"], obj["verified"] = "llm", False
        if kind == "words":
            cands = dic.candidates(obj.get("text", ""), obj.get("reading"))
            if not cands or cands[0] in known:
                rejected += 1
                continue
            obj["id"] = cands[0]
            known.add(cands[0])
        else:
            short = {"scenarios": "sc", "dialogues": "dl"}.get(kind) or DRILL_ID_KIND.get(drill_type or obj.get("type", ""), "dr")
            obj["id"] = next_id(f"{name}-{short}-", taken)
            if kind == "drills":
                obj["type"] = drill_type or obj.get("type")
        items.append(obj)
        try:
            Track(doc, dic, 0, practice_ids(), json.loads(ILR_BANDS.read_text(encoding="utf-8"))["levels"])
            added += 1
        except (BuildError, KeyError, TypeError, ValueError, AttributeError) as e:
            items.pop()
            rejected += 1
            log(f"rejected: {e}")
    save(path, doc)
    log(f"{name}: added {added} {kind}, rejected {rejected}; review them with the in-app review or by hand (source stays 'llm')")


# ---------------------------------------------------------------- main

def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", default="build", choices=["build", "validate", "resolve", "draft"])
    ap.add_argument("tracks", nargs="*", help="track ids (resolve/draft); default all")
    ap.add_argument("--dictionary", type=Path, default=Path(os.environ.get("TSUMUGI_DICTIONARY", DICTIONARY_PACK)))
    ap.add_argument("--out", type=Path, default=TRACKS_PACK)
    ap.add_argument("--kind", choices=sorted(DRAFT_SHAPES), help="draft: what to add")
    ap.add_argument("--type", dest="drill_type", choices=sorted(DRILL_TYPES), help="draft --kind drills: the drill type")
    ap.add_argument("--count", type=int, default=10)
    ap.add_argument("--endpoint", help="OpenAI-compatible base URL, e.g. http://host:11434/v1 (Ollama)")
    ap.add_argument("--model")
    ap.add_argument("--timeout", type=float, default=300.0)
    args = ap.parse_args([] if argv is None and __name__ != "__main__" else argv)
    try:
        dic = Dictionary(args.dictionary)
        if args.command == "build":
            report(build(dic, args.out, args.tracks or None))
            log(f"tracks: wrote {args.out}")
        elif args.command == "validate":
            report(validate_all(dic, args.tracks or None))
            log("tracks: all sources valid")
        elif args.command == "resolve":
            if resolve(dic, args.tracks):
                raise SystemExit(1)
        else:
            require(len(args.tracks) == 1 and args.kind and args.endpoint and args.model,
                    "draft needs one track id, --kind, --endpoint and --model")
            draft(dic, args.tracks[0], args.kind, args.count, args.endpoint, args.model, args.drill_type,
                  os.environ.get("TSUMUGI_LLM_KEY"), args.timeout)
    except BuildError as e:
        raise SystemExit(f"tracks: {e}") from None


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    main()
