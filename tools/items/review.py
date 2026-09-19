"""Human review of LLM-drafted content (CLAUDE.md rule 10, v2 rule 19, BRIEF §5.5, BRIEF_V2 G-16).

Walks through the items of a source file that are still unreviewed, shows each one, and lets the reviewer accept it,
edit it in $EDITOR (then accept), reject it with a note, skip, or quit. Only this tool flips content to verified; the
apps show an "AI-generated" badge on everything else. Every reviewable content type, its source file, its stable id
and how its flag flips are listed in docs/CONTENT_PACKS.md "Reviewing content" (DECISIONS D-034, D-118, D-245…D-249, D-279):

  kind              source file(s)                              id                    accept sets
  grammar_point     packs/grammar/n*.json  points[]             point id              source "verified"
  grammar_ja        packs/grammar/n*.json  points[] (*_ja)      point id              ja_source "verified"
  exam_passage/item items/bank/*.json (JLPT and DLPT banks)     passage / item id     verified true (D-034)
  dialogue          packs/listening/dialogues.json              dialogue id           source "verified"
  scenario          packs/speaking/scenarios.json               scenario id           source "verified"
  opi_question      packs/speaking/opi.json  levels[].questions <ilr>:<text key>      source "verified"
  drill_item        grammar example / dialogue line it copies   g:<point>:<text key>  source "verified" on the
                                                                 d:<dialogue>:<line>   example or line
  reader_passage    packs/readers/stories/*.json                story id              source "verified" + verified
  onomatopoeia      packs/onomatopoeia/entries.json             JMdict entry id       source "verified"
  phonetic_series   packs/phonetics/series.json                 phonetic component    source "verified" (was
                    (derived by packs/build_phonetics.py, D-282)                      "derived", not "llm")
  track_*           packs/tracks/<track>[.<part>].json          item id, or           source "verified" + verified
                    (word, kanji, scenario, dialogue, drill,    <track>:<JMdict id>
                    situation, task, reading)                   / <track>:<kanji>
  kana_mnemonic     shared/.../kana/KanaMnemonics.kt            the kana              listed in
                                                                                      KanaMnemonicsReviewed.kt
  translation_passage packs/translation/passages/*.json        passage id            source "verified" + verified
  expression_cluster packs/thesaurus/clusters/*.json            cluster id            source "verified" + verified
  poem_annotation   packs/literature/poems/*.json               poem id               source "verified" + verified
  circle_text       packs/literature/circle.json  texts[]       text id               source "verified" + verified

<text key> is reviewKey(): FNV-1a 32 of the UTF-8 NFC text, 8 hex digits (the same function in
shared/.../review/ContentReviewSources.kt). A review adds `reviewed {by, on, notes?}` (grammar_ja: `ja_reviewed`); a
reject adds `rejected {by, on, notes}` (`ja_rejected`) and leaves the item unverified for the author to redo.

Run: uv run python items/review.py packs/grammar/n5.json [--kind grammar_ja] [--reviewer NAME]
     uv run python items/review.py items/bank/dlpt_reading.json        # a passage and its items together
     uv run python items/review.py packs/tracks/gaming.json [--kind track_word]
     uv run python items/review.py packs/listening/dialogues.json [--kind drill_item]
     uv run python items/review.py packs/speaking/opi.json | packs/onomatopoeia/entries.json | packs/readers/stories/n4.json
     uv run python items/review.py packs/translation/passages/news-legal.json | packs/thesaurus/clusters/scenes.json
     uv run python items/review.py packs/literature/poems/a.json | packs/literature/circle.json

Verdicts made on the phone (Me → Content review) are applied with:
     uv run python items/review.py --ingest verdicts.json [--reviewer NAME] [--dry-run]

The verdicts file ({"format": "tsumugi-review-verdicts", "version": 1, "reviewer", "exportedAt", "verdicts": [{kind,
id, verdict, notes, edits, decidedAt}]}) is applied to the source files: "accept" verifies, "edit" applies the changed
fields (NFC) and then verifies, "reject" records the rejection. Afterwards re-run the validators the report names and
rebuild the packs.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
VERDICTS_FORMAT = "tsumugi-review-verdicts"


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def review_key(text: str) -> str:
    """FNV-1a 32 over the UTF-8 bytes of the NFC text, as 8 hex digits (mirrors ContentReviewSources.reviewKey)."""
    h = 0x811C9DC5
    for b in nfc(text).encode("utf-8"):
        h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
    return f"{h:08x}"


def today() -> str:
    return dt.datetime.now(dt.UTC).date().isoformat()


# --- Content kinds ------------------------------------------------------------------------------------------

# One reviewable object: its stable id, the list (or dict) that holds it, and its index (or key) there.
Entry = tuple[str, list, int]


@dataclass(frozen=True)
class Kind:
    code: str
    files: Callable[[Path], list[Path]]  # candidate source files under the tools directory
    entries: Callable[[Path, dict], Iterator[Entry]]
    flip: str  # "source" | "both" (source + verified) | "verified" (banks, D-034) | "ja_source"
    editable: tuple[str, ...]
    show: Callable[[dict, dict], str]
    validator: str  # the command that re-validates an edited file
    pending_fn: Callable[[dict, dict], bool] | None = None
    prepare: Callable[[list, int], dict] | None = None  # turns the stored form into an editable dict

    def obj(self, holder: list, index: int) -> dict:
        return self.prepare(holder, index) if self.prepare else holder[index]

    def pending(self, doc: dict, obj: dict) -> bool:
        if self.pending_fn:
            return self.pending_fn(doc, obj)
        source = obj.get("source", doc.get("source", "llm"))
        if self.flip == "verified":
            return not obj.get("verified")
        if self.flip == "both":
            return source == "llm" and not obj.get("verified")
        return source == "llm"

    @property
    def review_keys(self) -> tuple[str, str]:
        return ("ja_reviewed", "ja_rejected") if self.flip == "ja_source" else ("reviewed", "rejected")


def _glob(*parts: str, pattern: str) -> Callable[[Path], list[Path]]:
    return lambda root: sorted(root.joinpath(*parts).glob(pattern))


def _one(*parts: str) -> Callable[[Path], list[Path]]:
    return lambda root: [root.joinpath(*parts)]


def _by_id(key: str) -> Callable[[Path, dict], Iterator[Entry]]:
    def it(_path: Path, doc: dict) -> Iterator[Entry]:
        for i, obj in enumerate(doc.get(key, []) or []):
            if isinstance(obj, dict) and "id" in obj:
                yield str(obj["id"]), doc[key], i
    return it


def _lines(label: str, rows: list[str]) -> str:
    return f"{label}:\n" + "\n".join(f"  {r}" for r in rows) if rows else ""


def _choices(q: dict, stem_key: str = "question") -> str:
    out = [f"Q: {q.get(stem_key) or q.get('stem', '')}"]
    for n, c in enumerate(q.get("choices", [])):
        out.append(f"  {'*' if n == q.get('answer') else ' '} {chr(65 + n)}. {c}")
    if q.get("explanation"):
        out.append(f"  Why: {q['explanation']}")
    return "\n".join(out)


def _join(*parts: str) -> str:
    return "\n".join(p for p in parts if p)


# grammar ---------------------------------------------------------------------------------------------------

def _show_grammar(p: dict, _doc: dict) -> str:
    return _join(
        f"{p['id']}  ·  N{p.get('jlpt')}  ·  {p.get('title')}",
        f"Structure: {p.get('structure')}",
        f"Meaning:   {p.get('meaning')}",
        f"\n{p.get('nuance', '')}\n",
        *(f"  ! {m}" for m in p.get("mistakes", [])),
        *(f"  • {ex['ja']}\n    {ex.get('en', '')}" for ex in p.get("examples", [])),
    )


def _show_grammar_ja(p: dict, _doc: dict) -> str:
    return _join(
        f"{p['id']}  ·  N{p.get('jlpt')}  ·  {p.get('title')}  ({p.get('structure')})",
        f"English:  {p.get('meaning')}\n          {p.get('nuance')}",
        f"意味:     {p.get('meaning_ja', '')}",
        f"ニュアンス: {p.get('nuance_ja', '')}",
    )


def _grammar_ja_entries(_path: Path, doc: dict) -> Iterator[Entry]:
    for i, p in enumerate(doc.get("points", [])):
        if p.get("meaning_ja"):
            yield p["id"], doc["points"], i


# exam banks (interactive review has its own passage-with-items loop, review_bank) --------------------------

def _show_exam(o: dict, _doc: dict) -> str:
    return _join(
        f"{o['id']}  ·  {o.get('exam')} {o.get('level')}  ·  {o.get('textType') or o.get('type', '')}",
        o.get("title", ""), o.get("body", ""),
        *(f"  {ln.get('speaker')} ({ln.get('voice')}): {ln.get('text')}" for ln in o.get("script") or []),
        _choices(o, "stem") if "choices" in o else "",
    )


# practice pack ---------------------------------------------------------------------------------------------

def _show_dialogue(d: dict, _doc: dict) -> str:
    names = {sp.get("id"): sp.get("name", sp.get("id")) for sp in d.get("speakers", [])}
    return _join(
        f"{d['id']}  ·  N{d.get('jlpt')}  ·  {d.get('style', 'scripted')}  ·  {d.get('title')} ({d.get('topic')})",
        *(f"  {names.get(ln.get('speaker'), ln.get('speaker'))}: {ln.get('ja')}\n      {ln.get('en', '')}"
          for ln in d.get("lines", [])),
        *(_choices(q) for q in d.get("questions", [])),
    )


def _show_scenario(s: dict, _doc: dict) -> str:
    return _join(
        f"{s['id']}  ·  N{s.get('jlpt')} ILR {s.get('ilr')}  ·  {s.get('register')}  ·  {s.get('titleEn')} / {s.get('titleJa')}",
        f"Setting: {s.get('setting')}",
        f"Learner: {s.get('learnerRole')}\nPartner: {s.get('partnerRole')}",
        _lines("Goals", s.get("goals", [])),
        _lines("Phrases", s.get("phrases", [])),
        _lines("Turns", [f"{t.get('partnerJa')} ({t.get('partnerEn')})\n    → {t.get('intent')}: {t.get('sample')}"
                         for t in s.get("turns", [])]),
    )


OPI_KEYS = ("phase", "ja", "en", "note", "domain")


def _opi_entries(_path: Path, doc: dict) -> Iterator[Entry]:
    for lv in doc.get("levels", []):
        for i, q in enumerate(lv.get("questions", [])):
            ja = q["ja"] if isinstance(q, dict) else q[1]
            yield f"{lv['ilr']}:{review_key(ja)}", lv["questions"], i


def _opi_prepare(holder: list, index: int) -> dict:
    """OPI questions are compact arrays [phase, ja, en, note, domain?]; a reviewed one becomes an object."""
    q = holder[index]
    if isinstance(q, list):
        q = dict(zip(OPI_KEYS, q, strict=False))
        q.setdefault("domain", "")
        holder[index] = q
    return q


def _opi_pending(doc: dict, q) -> bool:
    source = q.get("source") if isinstance(q, dict) else None
    return (source or doc.get("source", "llm")) == "llm"


def _show_opi(q, _doc: dict) -> str:
    q = dict(zip(OPI_KEYS, q, strict=False)) if isinstance(q, list) else q
    return _join(f"[{q.get('phase')}]  domain {q.get('domain') or '-'}", q.get("ja", ""), q.get("en", ""),
                 f"Note: {q.get('note', '')}")


def _drill_entries(_path: Path, doc: dict) -> Iterator[Entry]:
    """Drill-set items copy a grammar point's own example or a dialogue line; their flag lives there (D-247). Items of
    an already verified point or dialogue are verified with it and aren't listed."""
    for p in doc.get("points", []):
        if p.get("source", doc.get("source", "llm")) != "verified":
            for i, ex in enumerate(p.get("examples", [])):
                yield f"g:{p['id']}:{review_key(ex['ja'])}", p["examples"], i
    for d in doc.get("dialogues", []):
        if d.get("source", doc.get("source", "llm")) != "verified":
            for i, _ln in enumerate(d.get("lines", [])):
                yield f"d:{d['id']}:{i}", d["lines"], i


def _drill_pending(_doc: dict, obj: dict) -> bool:
    return obj.get("source") != "verified"


def _show_drill(obj: dict, _doc: dict) -> str:
    return _join(f"Say it: {obj.get('en', '')}", f"Answer: {obj.get('ja', '')}")


# readers and onomatopoeia ----------------------------------------------------------------------------------

def _show_reader(p: dict, _doc: dict) -> str:
    return _join(
        f"{p['id']}  ·  {p.get('level')}  ·  {p.get('genre')}  ·  {p.get('title')} ({p.get('titleEn')})",
        p.get("body", ""),
        _lines("Vocabulary", [f"{v.get('word')}【{v.get('reading')}】 {v.get('gloss')}" for v in p.get("vocabulary", [])]),
        *(_choices(q, "stem") for q in p.get("questions", [])),
    )


def _show_onomatopoeia(e: dict, _doc: dict) -> str:
    return _join(f"{e.get('id')}  {e.get('text')}  ·  {e.get('type')}  ·  {e.get('theme')}",
                 f"Feel:    {e.get('feel', '')}", f"Feel ja: {e.get('feel_ja', '')}")


def _ono_entries(_path: Path, doc: dict) -> Iterator[Entry]:
    for i, e in enumerate(doc.get("entries", [])):
        if not e.get("exclude"):
            yield str(e["id"]), doc["entries"], i


# Phase 13: translation passages, thesaurus clusters, poem annotations, reading-circle summaries (D-279) --------

def _show_translation(p: dict, _doc: dict) -> str:
    origin = p.get("origin") or {}
    return _join(
        f"{p['id']}  ·  {p.get('direction')}  ·  {p.get('genre')}  ·  {p.get('level')} / ILR {p.get('ilr')}  ·  {p.get('title')}",
        f"Origin: {origin.get('kind')} {origin.get('ref', '')}".rstrip(),
        "Text:\n" + p.get("text", ""),
        "Reference:\n" + p.get("reference", ""),
        f"Register: {p.get('register', '')}",
        _lines("Key points", p.get("keyPoints", [])),
        f"Notes: {p.get('notes', '')}",
    )


def _show_cluster(c: dict, _doc: dict) -> str:
    return _join(
        f"{c['id']}  ·  {c.get('kind')}  ·  {c.get('ja')} ({c.get('reading')})  ·  {c.get('en')}",
        c.get("description", ""),
        f"Flags: {'、'.join(c.get('plain', []))}",
        *(f"  {e.get('text')}【{e.get('reading')}】 {e.get('register')} · {e.get('intensity')}\n    {e.get('nuance')}\n"
          f"    • {(e.get('example') or {}).get('ja', '')}\n      {(e.get('example') or {}).get('en', '')}"
          for e in c.get("expressions", [])),
    )


def _show_poem(p: dict, _doc: dict) -> str:
    return _join(
        f"{p['id']}  ·  {p.get('author')}  ·  {p.get('title')} ({p.get('titleEn')})  ·  Aozora {p.get('work')}",
        "(the poem text is fetched from Aozora at build time)",
        _lines("Vocabulary", [f"{v.get('word')}【{v.get('reading')}】 {v.get('gloss')}" for v in p.get("vocabulary", [])]),
        "Paraphrase:\n" + p.get("paraphrase", ""),
        "Gloss:\n" + p.get("gloss", ""),
        f"Note: {p.get('note', '')}",
    )


def _show_circle(t: dict, _doc: dict) -> str:
    return _join(f"{t['id']}  ·  {t.get('author')}  ·  {t.get('title')} ({t.get('titleEn')})  ·  {t.get('level')}",
                 t.get("summaryEn", ""))
def _show_series(e: dict, _doc: dict) -> str:
    return _join(f"{e.get('id')}  ·  {e.get('readings', '')}", f"Members: {' '.join(e.get('members', ''))}",
                 "Derived from KanjiVG component trees and KANJIDIC2 on'yomi (build_phonetics.py).")


# tracks ----------------------------------------------------------------------------------------------------

def _track_name(path: Path, doc: dict) -> str:
    return doc.get("track") or path.name.split(".")[0]


def _track_words(path: Path, doc: dict) -> Iterator[Entry]:
    for i, w in enumerate(doc.get("words", [])):
        if isinstance(w.get("id"), int):
            yield f"{_track_name(path, doc)}:{w['id']}", doc["words"], i


def _track_kanji(path: Path, doc: dict) -> Iterator[Entry]:
    for i, k in enumerate(doc.get("kanji", []) or []):
        yield f"{_track_name(path, doc)}:{k.get('kanji')}", doc["kanji"], i


def _show_json(o: dict, _doc: dict) -> str:
    return json.dumps({k: v for k, v in o.items() if k not in ("reviewed", "rejected")}, ensure_ascii=False, indent=1)


TRACK_FILES = _glob("packs", "tracks", pattern="*.json")
TRACK_VALIDATOR = "uv run python packs/build_tracks.py validate"
TRACK_DRILL_FIELDS = ("explanation", "en", "title", "titleJa", "situation", "subject", "body", "sentence", "plain", "setting")


def _track(code: str, entries, editable: tuple[str, ...], show=_show_json) -> Kind:
    return Kind(code, TRACK_FILES, entries, "both", editable, show, TRACK_VALIDATOR)


GRAMMAR_FILES = _glob("packs", "grammar", pattern="n[1-5].json")
BANK_FILES = _glob("items", "bank", pattern="*.json")
BANK_VALIDATOR = "uv run python items/gen_dlpt.py validate items/bank/*.json"

KINDS: dict[str, Kind] = {k.code: k for k in [
    Kind("grammar_point", GRAMMAR_FILES, _by_id("points"), "source", ("title", "structure", "meaning", "nuance"),
         _show_grammar, "uv run python packs/grammar/validate.py"),
    Kind("grammar_ja", GRAMMAR_FILES, _grammar_ja_entries, "ja_source", ("meaning_ja", "nuance_ja"), _show_grammar_ja,
         "uv run python packs/grammar_ja.py check",
         pending_fn=lambda _d, p: bool(p.get("meaning_ja")) and p.get("ja_source", "llm") == "llm"),
    Kind("exam_passage", BANK_FILES, _by_id("passages"), "verified", ("title", "body"), _show_exam, BANK_VALIDATOR),
    Kind("exam_item", BANK_FILES, _by_id("items"), "verified", ("stem", "explanation"), _show_exam, BANK_VALIDATOR),
    Kind("dialogue", _one("packs", "listening", "dialogues.json"), _by_id("dialogues"), "source", ("title", "topic"),
         _show_dialogue, "uv run python packs/build_practice.py --check packs/listening/dialogues.json"),
    Kind("scenario", _one("packs", "speaking", "scenarios.json"), _by_id("scenarios"), "source",
         ("titleEn", "titleJa", "setting", "learnerRole", "partnerRole"), _show_scenario,
         "uv run python packs/build_practice.py --check packs/speaking/scenarios.json"),
    Kind("opi_question", _one("packs", "speaking", "opi.json"), _opi_entries, "source", ("ja", "en", "note"), _show_opi,
         "uv run python packs/build_practice.py", pending_fn=_opi_pending, prepare=_opi_prepare),
    Kind("drill_item", lambda root: [*GRAMMAR_FILES(root), root / "packs" / "listening" / "dialogues.json"],
         _drill_entries, "source", ("en",), _show_drill, "uv run python packs/build_practice.py",
         pending_fn=_drill_pending),
    Kind("reader_passage", _glob("packs", "readers", "stories", pattern="*.json"), _by_id("passages"), "both",
         ("title", "body"), _show_reader, "uv run python packs/readers/validate_readers.py"),
    Kind("onomatopoeia", _one("packs", "onomatopoeia", "entries.json"), _ono_entries, "source", ("feel", "feel_ja"),
         _show_onomatopoeia, "uv run python packs/build_onomatopoeia.py"),
    Kind("phonetic_series", _one("packs", "phonetics", "series.json"), _by_id("series"), "source", ("members", "readings"),
         _show_series, "uv run python packs/build_phonetics.py",
         pending_fn=lambda _d, e: e.get("source") == "derived" and not e.get("exclude") and "rejected" not in e),
    _track("track_word", _track_words, ("gloss", "note")),
    _track("track_kanji", _track_kanji, ("keyword", "breakdown", "hint")),
    _track("track_scenario", _by_id("scenarios"), ("titleEn", "titleJa", "setting", "learnerRole", "partnerRole"),
           _show_scenario),
    _track("track_dialogue", _by_id("dialogues"), ("title", "topic"), _show_dialogue),
    _track("track_drill", _by_id("drills"), TRACK_DRILL_FIELDS),
    _track("track_situation", _by_id("situations"), ("titleEn", "titleJa")),
    _track("track_task", _by_id("tasks"), ("titleEn", "titleJa", "place")),
    _track("track_reading", _by_id("readings"), ("title", "body")),
    Kind("translation_passage", _glob("packs", "translation", "passages", pattern="*.json"), _by_id("passages"), "both",
         ("title", "text", "reference", "notes"), _show_translation, "uv run python packs/build_translation.py check"),
    Kind("expression_cluster", _glob("packs", "thesaurus", "clusters", pattern="*.json"), _by_id("clusters"), "both",
         ("en", "description"), _show_cluster, "uv run python packs/build_thesaurus.py check"),
    Kind("poem_annotation", _glob("packs", "literature", "poems", pattern="*.json"), _by_id("poems"), "both",
         ("titleEn", "paraphrase", "gloss", "note"), _show_poem, "uv run python packs/literature/build_literature.py check"),
    Kind("circle_text", _one("packs", "literature", "circle.json"), _by_id("texts"), "both", ("titleEn", "summaryEn"),
         _show_circle, "uv run python packs/literature/build_literature.py check"),
]}

# Kept for callers of the Phase 10 API: the fields an in-app edit may change, per kind.
EDITABLE_BY_KIND = {code: k.editable for code, k in KINDS.items()} | {"kana_mnemonic": ("mnemonic",)}


def verify(kind: Kind, obj: dict, by: str, on: str, notes: str = "") -> None:
    """Accept: flip the kind's flag (D-034, D-245) and record who reviewed it."""
    rev, rej = kind.review_keys
    obj.pop(rej, None)
    if kind.flip == "verified":
        obj["verified"] = True  # banks keep `source` as provenance
    elif kind.flip == "ja_source":
        obj["ja_source"] = "verified"
    else:
        obj["source"] = "verified"
        if kind.flip == "both":
            obj["verified"] = True
    obj[rev] = {"by": nfc(by), "on": on, **({"notes": nfc(notes)} if notes else {})}  # validators check NFC


def reject(kind: Kind, obj: dict, by: str, on: str, notes: str) -> None:
    obj[kind.review_keys[1]] = {"by": nfc(by), "on": on, "notes": nfc(notes)}


def apply_edits(obj: dict, edits: dict, allowed: tuple[str, ...]) -> list[str]:
    """Applies string edits (NFC) to [obj]; list/dict fields take JSON text. Returns the fields it could not apply."""
    skipped = []
    for field, value in edits.items():
        if field not in allowed or not isinstance(value, str):
            skipped.append(field)
            continue
        current = obj.get(field)
        if isinstance(current, (list, dict)):
            try:
                obj[field] = json.loads(nfc(value))
            except json.JSONDecodeError:
                skipped.append(field)
        else:
            obj[field] = nfc(value)
    return skipped


_apply_edits = apply_edits  # Phase 10 name


def dump(path: Path, data: dict) -> None:
    """Writes a source file back in its own layout (track files via build_tracks.save, else the file's indent)."""
    if path.parent.name == "tracks":
        sys.path.insert(0, str(TOOLS / "packs"))
        from build_tracks import save

        save(path, data)
        return
    indent = 2
    if path.exists():
        lines = path.read_text(encoding="utf-8").splitlines()
        if len(lines) > 1:
            indent = max(1, len(lines[1]) - len(lines[1].lstrip(" ")))
    path.write_text(json.dumps(data, ensure_ascii=False, indent=indent) + "\n", encoding="utf-8")


def kinds_for(path: Path, data: dict) -> list[str]:
    """The kinds an interactive review of [path] walks through by default."""
    if path.parent.name == "tracks":
        keys = {"words": "track_word", "kanji": "track_kanji", "scenarios": "track_scenario",
                "dialogues": "track_dialogue", "drills": "track_drill", "situations": "track_situation",
                "tasks": "track_task", "readings": "track_reading"}
        return [kind for key, kind in keys.items() if data.get(key)]
    if path.parent.name == "passages" and path.parent.parent.name == "translation":
        return ["translation_passage"]
    if "clusters" in data:
        return ["expression_cluster"]
    if "poems" in data:
        return ["poem_annotation"]
    if "texts" in data and path.name == "circle.json":
        return ["circle_text"]
    if "points" in data:
        return ["grammar_point"]
    if "items" in data:
        return ["exam_passage", "exam_item"]
    if "passages" in data:
        return ["reader_passage"]
    if "dialogues" in data:
        return ["dialogue"]
    if "scenarios" in data:
        return ["scenario"]
    if "levels" in data and "checklistSource" in data:
        return ["opi_question"]
    if "entries" in data:
        return ["onomatopoeia"]
    if "series" in data:
        return ["phonetic_series"]
    raise SystemExit(f"{path}: not a reviewable source file (see docs/CONTENT_PACKS.md \"Reviewing content\")")


# --- Interactive review ---------------------------------------------------------------------------------------

def edit_json(value):
    """Open value as JSON in $EDITOR and return the edited value (the original if it no longer parses)."""
    editor = os.environ.get("EDITOR", "notepad" if os.name == "nt" else "vi")
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        path = f.name
    subprocess.call([editor, path])
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"Not valid JSON ({e}); keeping the original.")
        return value
    finally:
        os.unlink(path)


def review_kind(path: Path, data: dict, kind: Kind, reviewer: str, ask: Callable[[str], str] = input) -> bool:
    """Reviews the pending [kind] items of one file. Returns False when the reviewer quit."""
    pending = [(eid, holder, i) for eid, holder, i in kind.entries(path, data) if kind.pending(data, holder[i])]
    print(f"{len(pending)} {kind.code} items still need review in {path}.")
    for n, (eid, holder, i) in enumerate(pending, start=1):
        while True:
            print("\n" + "=" * 72 + f"\n[{n}/{len(pending)}] {kind.code} {eid}")
            print(kind.show(holder[i], data))
            if kind.review_keys[1] in (holder[i] if isinstance(holder[i], dict) else {}):
                print(f"  (rejected before: {holder[i][kind.review_keys[1]].get('notes', '')})")
            choice = ask("\n[a]ccept as verified · [e]dit · [r]eject · [s]kip · [q]uit > ").strip().lower()
            if choice == "e":
                obj = kind.obj(holder, i)
                fields = {k: obj[k] for k in kind.editable if k in obj}
                edited = edit_json(fields)
                if isinstance(edited, dict):
                    apply_edits(obj, {k: v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
                                      for k, v in edited.items() if v != fields.get(k)}, kind.editable)
                continue
            if choice == "a":
                verify(kind, kind.obj(holder, i), reviewer, today())
                dump(path, data)
            elif choice == "r":
                reject(kind, kind.obj(holder, i), reviewer, today(), ask("Why? > ").strip())
                dump(path, data)
            elif choice == "q":
                dump(path, data)
                return False
            if choice in ("a", "r", "s"):
                break
    dump(path, data)
    return True


def show_unit(passage: dict | None, items: list[dict], index: int, total: int) -> None:
    print("\n" + "=" * 72)
    head = passage or items[0]
    print(f"[{index}/{total}]")
    print(_show_exam(head, {}) if passage else "")
    for it in items:
        print("\n" + _show_exam(it, {}))


def bank_units(data: dict) -> list[tuple[int | None, list[int]]]:
    """(passage index or None, item indexes) for every passage or standalone item still needing review."""
    passages, items = data.get("passages", []), data.get("items", [])
    units: list[tuple[int | None, list[int]]] = []
    for pi, p in enumerate(passages):
        idx = [i for i, it in enumerate(items) if it.get("passageId") == p["id"]]
        if not p.get("verified") or any(not items[i].get("verified") for i in idx):
            units.append((pi, idx))
    units += [(None, [i]) for i, it in enumerate(items) if not it.get("passageId") and not it.get("verified")]
    return units


def accept_unit(data: dict, unit: tuple[int | None, list[int]], reviewer: str, on: str) -> None:
    pi, idx = unit
    targets = ([data["passages"][pi]] if pi is not None else []) + [data["items"][i] for i in idx]
    for obj in targets:
        verify(KINDS["exam_item"], obj, reviewer, on)


def review_bank(path: Path, data: dict, reviewer: str, ask: Callable[[str], str] = input) -> int:
    """A bank passage and its items are reviewed together (D-034: accepting sets verified=true on all of them)."""
    passages, items = data.get("passages", []), data.get("items", [])
    units = bank_units(data)
    print(f"{len(units)} passages/items still need review in {path}.")
    done = f"Re-validate with: uv run python items/gen_dlpt.py validate {path}"
    for n, (pi, idx) in enumerate(units, start=1):
        while True:
            show_unit(passages[pi] if pi is not None else None, [items[i] for i in idx], n, len(units))
            choice = ask("\n[a]ccept as verified · [e]dit · [r]eject · [s]kip · [q]uit > ").strip().lower()
            if choice == "e":
                unit = {"passage": passages[pi] if pi is not None else None, "items": [items[i] for i in idx]}
                edited = edit_json(unit)
                ok = isinstance(edited, dict) and isinstance(edited.get("items"), list)
                if not ok or len(edited["items"]) != len(idx):
                    print("Edits must keep the {passage, items} shape and item count; keeping the original.")
                    continue
                if pi is not None and isinstance(edited.get("passage"), dict):
                    passages[pi] = {**edited["passage"], "id": passages[pi]["id"]}
                for i, it in zip(idx, edited["items"], strict=True):
                    items[i] = {**it, "id": items[i]["id"]}
                continue
            if choice == "a":
                accept_unit(data, (pi, idx), reviewer, today())
                dump(path, data)
            elif choice == "r":
                notes = ask("Why? > ").strip()
                for obj in ([passages[pi]] if pi is not None else []) + [items[i] for i in idx]:
                    reject(KINDS["exam_item"], obj, reviewer, today(), notes)
                dump(path, data)
            elif choice == "q":
                dump(path, data)
                print(f"Saved. {done}")
                return 0
            if choice in ("a", "r", "s"):
                break
    dump(path, data)
    print(f"All done. {done}")
    return 0


def review_file(path: Path, reviewer: str, kind: str | None = None, ask: Callable[[str], str] = input) -> int:
    data = json.loads(path.read_text(encoding="utf-8"))
    codes = [kind] if kind else kinds_for(path, data)
    if codes == ["exam_passage", "exam_item"]:
        return review_bank(path, data, reviewer, ask)
    for code in codes:
        if code not in KINDS:
            raise SystemExit(f"unknown kind {code!r}; one of {', '.join(KINDS)}")
        if not review_kind(path, data, KINDS[code], reviewer, ask):
            print(f"Saved. Re-validate with: {KINDS[code].validator}")
            return 0
    print(f"All done. Re-validate with: {KINDS[codes[-1]].validator}, then rebuild the pack.")
    return 0


# --- Ingesting verdicts from the app (G-16) -----------------------------------------------------------------

def _kotlin_string(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def _ingest_kana(root: Path, entries: list[dict], reviewer: str, on: str, dry_run: bool) -> list[str]:
    """Kana mnemonics are compiled into the app: accepted ones go into KanaMnemonicsReviewed.kt."""
    kana_dir = root.parent / "shared" / "src" / "commonMain" / "kotlin" / "app" / "tsumugi" / "kana"
    mnemonics = kana_dir / "KanaMnemonics.kt"
    reviewed_file = kana_dir / "KanaMnemonicsReviewed.kt"
    report: list[str] = []
    if not mnemonics.exists():
        return [f"kana_mnemonic: {mnemonics} not found; skipped {len(entries)} verdicts"]
    source = mnemonics.read_text(encoding="utf-8")
    reviewed: set[str] = set()
    if reviewed_file.exists():
        reviewed = set(re.findall(r'^\s*"([^"]+)",\s*$', reviewed_file.read_text(encoding="utf-8"), re.MULTILINE))
    for v in entries:
        kana, verdict = v["id"], v["verdict"]
        line = re.compile(r'("' + re.escape(kana) + r'" to ")((?:[^"\\]|\\.)*)(")')
        if not line.search(source):
            report.append(f"kana_mnemonic {kana}: not found in KanaMnemonics.kt")
            continue
        if verdict == "edit" and v.get("edits", {}).get("mnemonic"):
            new_text = _kotlin_string(nfc(v["edits"]["mnemonic"]))
            source = line.sub(lambda m, text=new_text: m.group(1) + text + m.group(3), source, count=1)
        if verdict in ("accept", "edit"):
            reviewed.add(kana)
            report.append(f"kana_mnemonic {kana}: {verdict}")
        else:
            reviewed.discard(kana)
            report.append(f"kana_mnemonic {kana}: rejected ({v.get('notes', '')}) - rewrite it in KanaMnemonics.kt")
    if not dry_run:
        mnemonics.write_text(source, encoding="utf-8", newline="\n")
        body = "".join(f'    "{k}",\n' for k in sorted(reviewed))
        reviewed_file.write_text(
            "package app.tsumugi.kana\n\n"
            "// Generated by tools/items/review.py --ingest (kind \"kana_mnemonic\"). Do not edit by hand.\n"
            "// Kana whose mnemonic a human reviewed: their source is \"verified\" and the AI badge is off (rule 10, D-117).\n"
            f"internal val REVIEWED_KANA_MNEMONICS: Set<String> = setOf(\n{body})\n",
            encoding="utf-8",
            newline="\n",
        )
    return report


def ingest(verdicts_path: Path, reviewer: str | None = None, root: Path = TOOLS, dry_run: bool = False) -> list[str]:
    """Applies an app verdicts file to the source files under [root] (the tools directory). Returns a report whose
    last lines name the validators to re-run for the files it changed."""
    data = json.loads(verdicts_path.read_text(encoding="utf-8"))
    if data.get("format") != VERDICTS_FORMAT:
        raise SystemExit(f"{verdicts_path} is not a Tsumugi verdicts file (format {data.get('format')!r})")
    if int(data.get("version", 0)) > 1:
        raise SystemExit(f"{verdicts_path} is version {data.get('version')}; update review.py")
    by = reviewer or data.get("reviewer") or "reviewer"
    report: list[str] = []
    grouped: dict[str, list[dict]] = {}
    for v in data.get("verdicts", []):
        if v.get("verdict") not in ("accept", "edit", "reject"):
            report.append(f"{v.get('kind')} {v.get('id')}: unknown verdict {v.get('verdict')!r}; skipped")
            continue
        grouped.setdefault(v.get("kind", ""), []).append(v)

    loaded: dict[Path, dict] = {}
    changed: set[Path] = set()
    validators: list[str] = []
    for code, entries in grouped.items():
        on_default = today()
        if code == "kana_mnemonic":
            report += _ingest_kana(root, entries, by, on_default, dry_run)
            continue
        kind = KINDS.get(code)
        if kind is None:
            report += [f"{code} {v['id']}: unknown kind; skipped" for v in entries]
            continue
        index: dict[str, tuple[Path, list, int]] = {}
        for f in kind.files(root):
            if not f.exists():
                continue
            doc = loaded.setdefault(f, json.loads(f.read_text(encoding="utf-8")))
            for eid, holder, i in kind.entries(f, doc):
                index.setdefault(eid, (f, holder, i))
        for v in entries:
            on = (v.get("decidedAt") or "")[:10] or on_default
            target = index.get(str(v["id"]))
            if target is None:
                report.append(f"{code} {v['id']}: not found in the sources; skipped")
                continue
            f, holder, i = target
            obj = kind.obj(holder, i)
            notes = v.get("notes", "")
            if v["verdict"] == "reject":
                reject(kind, obj, by, on, notes)
                report.append(f"{code} {v['id']}: rejected")
            else:
                if v["verdict"] == "edit":
                    skipped = apply_edits(obj, v.get("edits", {}), kind.editable)
                    if skipped:
                        report.append(f"{code} {v['id']}: fields not applied: {', '.join(skipped)}")
                verify(kind, obj, by, on, notes)
                report.append(f"{code} {v['id']}: {v['verdict']}ed")
            changed.add(f)
            if kind.validator not in validators:
                validators.append(kind.validator)
    if not dry_run:
        for f in sorted(changed):
            dump(f, loaded[f])
    return report + [f"re-validate: {cmd}" for cmd in validators]


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("file", type=Path, nargs="?")
    parser.add_argument("--kind", choices=sorted(KINDS), help="which content of the file to review (default: by file)")
    parser.add_argument("--reviewer", default=os.environ.get("USER") or os.environ.get("USERNAME") or "reviewer")
    parser.add_argument("--ingest", type=Path, help="apply a verdicts JSON exported from the app (Me -> Content review)")
    parser.add_argument("--dry-run", action="store_true", help="with --ingest: report only, change nothing")
    args = parser.parse_args()

    if args.ingest:
        for line in ingest(args.ingest, args.reviewer if "--reviewer" in sys.argv else None, dry_run=args.dry_run):
            print(line)
        print("Then rebuild the packs (uv run python packs/build_all.py).")
        return 0
    if args.file is None:
        parser.error("give a source file to review, or --ingest verdicts.json")
    return review_file(args.file, args.reviewer, args.kind)


if __name__ == "__main__":
    sys.exit(main())
