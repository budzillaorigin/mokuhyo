"""Validate graded-reader stories (BRIEF_V2 §6.4, DECISIONS D-201..D-203).

    uv run python packs/readers/validate_readers.py                     # every file in packs/readers/stories
    uv run python packs/readers/validate_readers.py stories/n5-a.json   # some files (ids are checked against all)
    uv run python packs/readers/validate_readers.py --fix               # also fill vocabulary entryId/gloss, NFC
    uv run python packs/readers/validate_readers.py --report            # one line of measures per story
    uv run python packs/readers/validate_readers.py --packs DIR         # dictionary/tokenizer packs to read

Checks, per story (errors fail the run, warnings don't):
- schema: id gr-<level>-NNN unique across all files and matching the level; genre, topic, titles, body, cast;
  source "llm" or "verified"; verified a boolean;
- text is NFC; body length (non-whitespace characters) inside the level's range in levels.json;
- coverage: at least 95% of words within the level, glossed words included, and at least 90% without them
  (tokenized with tools/items/lattice.py and resolved against dictionary.sqlite the way the app's reader does);
- difficulty: the §6.4 text score (reimplemented from app.tsumugi.coverage.DifficultyScorer) inside the level's band;
- vocabulary: 3–15 entries, each a JMdict id that exists, with the word, reading and an English gloss;
- questions: count per level, stem language (Japanese at N3 and up, English below), 3–4 distinct choices,
  answer key in range, an English explanation, a known type.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

import readers_lib as L

JAPANESE = re.compile(r"[ぁ-ヿ一-鿿]")
LATIN = re.compile(r"[A-Za-z]")


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.rows: list[str] = []


def texts(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from texts(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from texts(v)


def is_english(s: str) -> bool:
    letters = len(LATIN.findall(s))
    return letters >= 3 and len(JAPANESE.findall(s)) <= max(12, letters * 2)


def check_story(p: dict, levels: dict, an: L.Analyzer | None, rep: Report) -> L.Measures | None:
    sid = p.get("id", "?")
    err = lambda m: rep.errors.append(f"{sid}: {m}")
    warn = lambda m: rep.warnings.append(f"{sid}: {m}")
    level = p.get("level")
    cfg = levels["levels"].get(level)
    if cfg is None:
        err(f"unknown level {level!r}")
        return None
    m = L.STORY_ID.match(sid)
    if not m or m.group(1) != level.lower():
        err(f"id must be gr-{level.lower()}-NNN")
    for k in ("genre", "topic", "title", "titleEn", "body"):
        if not isinstance(p.get(k), str) or not p[k].strip():
            err(f"missing or empty {k!r}")
    if p.get("genre") not in levels["genres"]:
        err(f"genre {p.get('genre')!r} not in {levels['genres']}")
    if p.get("source") not in ("llm", "verified"):
        err("source must be 'llm' or 'verified'")
    if not isinstance(p.get("verified"), bool):
        err("verified must be true or false")
    for s in texts(p):
        if s != L.nfc(s):
            err(f"text is not NFC: {s[:20]!r}")
            break
    body = p.get("body") or ""
    if not JAPANESE.search(p.get("title", "")):
        err("title must be Japanese")
    if not is_english(p.get("titleEn", "")):
        err("titleEn must be English")
    lo, hi = cfg["chars"]
    n = L.non_space_len(body)
    if not lo <= n <= hi:
        err(f"body is {n} characters; {level} needs {lo}–{hi}")
    if "\n\n" in body:
        warn("blank lines in body (paragraphs are single newlines)")

    cast = p.get("cast", [])
    if not isinstance(cast, list) or any(
        not isinstance(c, dict) or not c.get("name") or c.get("voice") not in L.VOICE_HINTS for c in cast
    ):
        err(f"cast must be [{{name, voice}}] with voice in {sorted(L.VOICE_HINTS)}")
        cast = []
    if ("「" in body) and not cast:
        warn("quoted speech but no cast: speech is read with the second female voice")

    vocab = p.get("vocabulary", [])
    vmin, vmax = levels["vocabulary"]["min"], levels["vocabulary"]["max"]
    if not isinstance(vocab, list) or not vmin <= len(vocab) <= vmax:
        err(f"vocabulary needs {vmin}–{vmax} entries")
        vocab = vocab if isinstance(vocab, list) else []
    glossed: set[int] = set()
    seen_vocab: set[int] = set()
    for i, v in enumerate(vocab):
        eid = v.get("entryId")
        if not isinstance(v.get("word"), str) or not v["word"]:
            err(f"vocabulary[{i}] needs word")
        if not isinstance(v.get("reading"), str) or not v["reading"]:
            err(f"vocabulary[{i}] ({v.get('word')}) needs reading")
        if not isinstance(eid, int) or (an is not None and not an.dictionary.exists(eid)):
            err(f"vocabulary[{i}] ({v.get('word')}) has no valid JMdict entryId (run with --fix)")
        else:
            if eid in seen_vocab:
                err(f"vocabulary[{i}] ({v.get('word')}) repeats entry {eid}")
            seen_vocab.add(eid)
            glossed.add(eid)
        if not isinstance(v.get("gloss"), str) or not is_english(v["gloss"]):
            err(f"vocabulary[{i}] ({v.get('word')}) needs an English gloss")

    questions = p.get("questions", [])
    qmin, qmax = cfg["questions"]
    if not isinstance(questions, list) or not qmin <= len(questions) <= qmax:
        err(f"{level} needs {qmin}–{qmax} questions")
        questions = questions if isinstance(questions, list) else []
    lang = cfg["questionLanguage"]
    for i, q in enumerate(questions):
        where = f"questions[{i}]"
        stem, choices, answer = q.get("stem", ""), q.get("choices"), q.get("answer")
        if q.get("type") not in levels["questionTypes"]:
            err(f"{where}.type {q.get('type')!r} not in {levels['questionTypes']}")
        if not isinstance(stem, str) or not stem.strip():
            err(f"{where} needs a stem")
        elif lang == "ja" and not JAPANESE.search(stem):
            err(f"{where}.stem must be Japanese at {level}")
        elif lang == "en" and not is_english(stem):
            err(f"{where}.stem must be English at {level}")
        if not isinstance(choices, list) or not 3 <= len(choices) <= 4:
            err(f"{where} needs 3–4 choices")
            continue
        if any(not isinstance(c, str) or not c.strip() for c in choices):
            err(f"{where} has an empty choice")
        if len({c.strip() for c in choices if isinstance(c, str)}) != len(choices):
            err(f"{where} has duplicate choices")
        if not isinstance(answer, int) or isinstance(answer, bool) or not 0 <= answer < len(choices):
            err(f"{where}.answer {answer!r} is out of range")
        if not isinstance(q.get("explanation"), str) or not is_english(q["explanation"]):
            err(f"{where} needs an English explanation")

    if an is None or not body:
        return None
    names = p.get("names", [])
    if not isinstance(names, list) or any(not isinstance(n, str) or not n.strip() for n in names):
        err("names must be a list of strings (proper nouns in the body)")
        names = []
    meas = an.measure(body, level, levels, glossed, {c["name"] for c in cast} | set(names))
    for i, v in enumerate(vocab):
        if v.get("word") and v["word"] not in body and v["word"] not in meas.lemmas:
            err(f"vocabulary[{i}] {v['word']} is not in the story (as written or as a dictionary form)")
    cov = levels["coverage"]
    if meas.coverage < cov["min"]:
        err(f"coverage {meas.coverage:.1%} < {cov['min']:.0%} ({meas.words} words); out of level: "
            + ", ".join(meas.out_of_level[:25]))
    if meas.unglossed_coverage < cov["minUnglossed"]:
        err(f"coverage without glossed words {meas.unglossed_coverage:.1%} < {cov['minUnglossed']:.0%}; glossed "
            f"hits: {', '.join(sorted(set(meas.glossed_hits)))}; out of level: {', '.join(meas.out_of_level[:25])}")
    slo, shi = cfg["textScore"]
    if not slo <= meas.text_score <= shi:
        err(f"text score {meas.text_score} ({meas.label}) outside {level} band {slo}–{shi}: mean sentence "
            f"{meas.avg_sentence:.1f} chars (target {cfg['sentence'][0]}–{cfg['sentence'][1]}), vocabulary "
            f"{meas.vocabulary:.2f}, kanji {meas.kanji_density:.2f}, abstract {meas.abstract_hits}/{meas.approx_tokens}")
    return meas


def validate(files: list[Path] | None, packs: Path | None, levels: dict | None = None,
             an: L.Analyzer | None = None, report_rows: bool = False) -> tuple[Report, dict[str, L.Measures]]:
    levels = levels or L.load_levels()
    rep = Report()
    all_stories = L.load_stories()
    ids = Counter(p.get("id") for _, p in all_stories)
    for sid, n in ids.items():
        if n > 1:
            rep.errors.append(f"{sid}: id used {n} times across the story files")
    if an is None and packs is not None:
        an = L.Analyzer(packs)
    targets = L.load_stories(files) if files else all_stories
    measures: dict[str, L.Measures] = {}
    for f, p in targets:
        m = check_story(p, levels, an, rep)
        if m is not None:
            measures[p["id"]] = m
            if report_rows:
                rep.rows.append(
                    f"{p['id']:<11} {p['genre']:<9} {m.chars:>5} ch {m.sentences:>3} s {m.avg_sentence:5.1f} "
                    f"score {m.text_score:>3} {m.label:<16} cov {m.coverage:6.1%} raw {m.unglossed_coverage:6.1%}"
                    f"  {f.name}")
    # Answer-key balance per level (warning): no single position should hold most keys.
    by_level: dict[str, Counter] = defaultdict(Counter)
    for _, p in targets:
        for q in p.get("questions", []):
            if isinstance(q.get("answer"), int):
                by_level[p.get("level", "?")][q["answer"]] += 1
    for level, c in by_level.items():
        total = sum(c.values())
        if total >= 12 and max(c.values()) > 0.45 * total:
            rep.warnings.append(f"{level}: answer keys unbalanced {dict(sorted(c.items()))}")
    return rep, measures


def fix(files: list[Path], an: L.Analyzer) -> int:
    """NFC-normalize every string and fill missing vocabulary entryId/gloss/reading from the dictionary."""
    changed = 0
    for f in files:
        doc = json.loads(f.read_text(encoding="utf-8"))

        def norm(o):
            if isinstance(o, str):
                return L.nfc(o)
            if isinstance(o, list):
                return [norm(x) for x in o]
            if isinstance(o, dict):
                return {k: norm(v) for k, v in o.items()}
            return o

        new = norm(doc)
        for p in new.get("passages", []):
            for v in p.get("vocabulary", []):
                eid = v.get("entryId")
                if not isinstance(eid, int) or not an.dictionary.exists(eid):
                    eid = an.dictionary.lookup_word(v.get("word", ""), v.get("reading"))
                    if eid is None:
                        L.log(f"{p.get('id')}: can't resolve vocabulary word {v.get('word')!r}")
                        continue
                    v["entryId"] = eid
                if not v.get("reading"):
                    _kanji, kana = an.dictionary.forms(eid)
                    if kana:
                        v["reading"] = kana[0]
                if not v.get("gloss"):
                    v["gloss"] = an.dictionary.gloss(eid)
        if new != doc:
            f.write_text(json.dumps(new, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            changed += 1
    return changed


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", type=Path)
    ap.add_argument("--packs", type=Path, help="directory with dictionary.sqlite and tokenizer.sqlite")
    ap.add_argument("--fix", action="store_true", help="fill vocabulary ids/glosses and NFC-normalize first")
    ap.add_argument("--report", action="store_true", help="print one line of measures per story")
    args = ap.parse_args()
    packs = args.packs or L.default_packs()
    files = [f if f.is_absolute() or f.exists() else L.HERE / f for f in args.files] or None
    an = L.Analyzer(packs)
    if args.fix:
        n = fix(files or L.story_files(), an)
        L.log(f"--fix: rewrote {n} file(s)")
    rep, _measures = validate(files, packs, an=an, report_rows=args.report)
    for row in rep.rows:
        print(row)
    for w in rep.warnings:
        print(f"warning: {w}")
    for e in rep.errors:
        print(f"ERROR: {e}")
    stories = L.load_stories(files) if files else L.load_stories()
    per_level = Counter(p.get("level") for _, p in stories)
    print(f"{len(stories)} stories ({', '.join(f'{k} {per_level[k]}' for k in L.LEVEL_ORDER if per_level[k])}); "
          f"{len(rep.errors)} errors, {len(rep.warnings)} warnings")
    return 1 if rep.errors else 0


if __name__ == "__main__":
    sys.exit(main())
