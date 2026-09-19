"""Build the poetry corner and reading-circle tables of content/packs/linguist.sqlite (BRIEF_V2 §6.14; DECISIONS
D-276…D-278).

Sources (all in this directory):
  authors.json   every author with the death date the public-domain check uses (life+70 since 2018-12-30; died
                 before 1968 = public domain in Japan), re-checked against the pinned Aozora catalogue
  themes.json    the poetry themes (sky, sea, moon, the seasons, rain)
  poems/*.json   poems by Aozora work id (+ `section` heading, `occurrence`, `replace` for gaiji, `drop` for stray
                 labels) with our LLM-drafted titleEn, vocabulary, paraphrase, gloss and note
  circle.json    the reading-circle texts with our English title, level and summary

Texts are fetched at build time from the Aozora files pinned in packs/sources.lock (`lock_works.py` pins new works)
and never committed. Every work must pass aozora.check_public_domain; any failure stops the build. Each Aozora work's
colophon (底本, 入力, 校正 and the volunteers' note) is stored and shown with the text.

Run: uv run python packs/literature/build_literature.py [check]          # validate (and fetch) only
     uv run python packs/literature/build_literature.py                  # build the tables
     uv run python packs/literature/build_literature.py draft --endpoint URL --model NAME [--limit N]
         drafts the annotations of poems (and circle summaries) that don't have them yet, through any
         OpenAI-compatible endpoint (Ollama, llama-server …); every drafted item is source "llm", verified false.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parent), str(HERE.parent / "readers")]

import aozora
from common import PACKS, REPO, dumps, log, open_pack, reset_tables, set_meta
from readers_lib import sentences

LINGUIST_SQ = REPO / "shared/src/commonMain/sqldelightLinguist/app/tsumugi/linguist/db/linguist.sq"
LINGUIST_PACK_VERSION = "1"
TABLES = {"aozora_work", "poem_theme", "poem", "poem_theme_member", "circle_text", "circle_sentence"}
POEM_KEYS = ("titleEn", "vocabulary", "paraphrase", "gloss", "note")
HIRAGANA = re.compile(r"^[ぁ-ゖー　 ]+$")
LEVELS = {"N5", "N4", "N3", "N2", "N1"}


def load(name: str) -> dict:
    return json.loads((HERE / name).read_text(encoding="utf-8"))


def poem_files() -> list[Path]:
    return sorted((HERE / "poems").glob("*.json"))


def poems() -> list[tuple[Path, dict]]:
    out = []
    for f in poem_files():
        out += [(f, p) for p in json.loads(f.read_text(encoding="utf-8"))["poems"]]
    return out


def nfc_ok(v) -> bool:
    if isinstance(v, str):
        return unicodedata.normalize("NFC", v) == v
    if isinstance(v, list):
        return all(nfc_ok(x) for x in v)
    if isinstance(v, dict):
        return all(nfc_ok(x) for x in v.values())
    return True


def text_of(entry: dict, poem: bool) -> aozora.Clean:
    work = aozora.fetch(entry["work"])
    lines = work.section(entry["section"], entry.get("occurrence", 1)) if entry.get("section") else work.lines
    return aozora.body(lines, entry.get("replace"), poem=poem, drop=entry.get("drop"))


def validate() -> tuple[list[str], dict[str, aozora.Clean], dict[str, aozora.Clean]]:
    """(errors, poem texts, circle texts). Fetches every pinned work."""
    errors: list[str] = []
    authors = {a["name"]: a for a in load("authors.json")["authors"]}
    themes = {t["id"] for t in load("themes.json")["themes"]}
    rows = aozora.catalogue()
    checked: set[str] = set()
    ids: set[str] = set()
    poem_texts: dict[str, aozora.Clean] = {}
    circle_texts: dict[str, aozora.Clean] = {}

    def common_checks(kind: str, e: dict) -> None:
        eid = e.get("id", "?")
        if eid in ids:
            errors.append(f"{kind} {eid}: duplicate id")
        ids.add(eid)
        if e.get("author") not in authors:
            errors.append(f"{kind} {eid}: author {e.get('author')} is not in authors.json (add the public-domain check)")
        elif e["work"] not in checked:
            checked.add(e["work"])
            errors.extend(aozora.check_public_domain(e["work"], rows, e["author"], authors[e["author"]]["died"]))
        if not nfc_ok(e):
            errors.append(f"{kind} {eid}: not NFC")
        if e.get("source") not in ("llm", "verified"):
            errors.append(f"{kind} {eid}: source must be llm or verified")
        if e.get("verified") and e.get("source") != "verified":
            errors.append(f"{kind} {eid}: verified items need source 'verified'")

    for f, p in poems():
        pid = p.get("id", "?")
        common_checks("poem", p)
        try:
            clean = text_of(p, poem=True)
        except (ValueError, KeyError, SystemExit) as e:
            errors.append(f"poem {pid}: {e}")
            continue
        poem_texts[pid] = clean
        for key in POEM_KEYS:
            if not p.get(key):
                errors.append(f"poem {pid} ({f.name}): missing {key} (draft it: build_literature.py draft)")
        if not set(p.get("themes", [])) or not set(p["themes"]) <= themes:
            errors.append(f"poem {pid}: themes must be among {sorted(themes)}")
        for v in p.get("vocabulary", []):
            if v.get("word") not in clean.text:
                errors.append(f"poem {pid}: vocabulary word {v.get('word')!r} is not in the poem")
            if not HIRAGANA.match(v.get("reading", "")):
                errors.append(f"poem {pid}: reading of {v.get('word')} must be hiragana")
            if not v.get("gloss"):
                errors.append(f"poem {pid}: {v.get('word')} has no gloss")
        if len(clean.text) > 1500:
            errors.append(f"poem {pid}: {len(clean.text)} characters; the corner keeps to short poems")

    for t in load("circle.json")["texts"]:
        common_checks("circle", t)
        try:
            circle_texts[t["id"]] = text_of(t, poem=False)
        except (ValueError, KeyError, SystemExit) as e:
            errors.append(f"circle {t.get('id')}: {e}")
        if t.get("level") not in LEVELS:
            errors.append(f"circle {t.get('id')}: level must be N5…N1")
        for key in ("titleEn", "summaryEn"):
            if not t.get(key):
                errors.append(f"circle {t.get('id')}: missing {key}")
    return errors, poem_texts, circle_texts


def build(packs: Path = PACKS) -> dict[str, int]:
    errors, poem_texts, circle_texts = validate()
    if errors:
        for e in errors:
            log(f"ERROR: {e}")
        raise SystemExit(f"literature: {len(errors)} errors")
    authors = {a["name"]: a for a in load("authors.json")["authors"]}
    rows = aozora.catalogue()
    db = open_pack(packs / "linguist.sqlite", LINGUIST_SQ)
    reset_tables(db, TABLES, LINGUIST_SQ)
    works: set[str] = set()

    def add_work(wid: str, author: str) -> None:
        if wid in works:
            return
        works.add(wid)
        w = aozora.fetch(wid)
        row = next(r for r in rows[wid] if r.role == "著者")
        a = authors[author]
        db.execute("INSERT INTO aozora_work VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                   (wid, row.title, author, a["reading"], a["en"], a["born"], a["died"], row.orthography, row.card_url,
                    w.colophon))

    for i, t in enumerate(load("themes.json")["themes"]):
        db.execute("INSERT INTO poem_theme VALUES (?, ?, ?, ?)", (t["id"], i, t["ja"], t["en"]))
    n_poems = 0
    for ord_, (_f, p) in enumerate(poems()):
        clean = poem_texts[p["id"]]
        add_work(p["work"], p["author"])
        vocab = [{**v, "start": clean.text.find(v["word"])} for v in p["vocabulary"]]
        db.execute("INSERT INTO poem VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                   (p["id"], ord_, p["work"], p["title"], p["titleEn"], p["author"], clean.text, dumps(clean.ruby),
                    dumps(vocab), p["paraphrase"], p["gloss"], p["note"], p["source"], 1 if p.get("verified") else 0))
        db.executemany("INSERT INTO poem_theme_member VALUES (?, ?)", [(th, p["id"]) for th in p["themes"]])
        n_poems += 1
    n_sentences = 0
    for ord_, t in enumerate(load("circle.json")["texts"]):
        clean = circle_texts[t["id"]]
        add_work(t["work"], t["author"])
        spans = sentences(clean.text)
        db.execute("INSERT INTO circle_text VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                   (t["id"], ord_, t["work"], t["title"], t["titleEn"], t["author"], t["level"], clean.text,
                    dumps(clean.ruby), t["summaryEn"], len(spans), "verified" if t.get("verified") else t["source"]))
        db.executemany("INSERT INTO circle_sentence VALUES (?, ?, ?, ?, ?)",
                       [(t["id"], i, a, b, clean.text[a:b]) for i, (a, b) in enumerate(spans)])
        n_sentences += len(spans)
    counts = {"poems": n_poems, "works": len(works), "circleTexts": len(circle_texts), "circleSentences": n_sentences}
    set_meta(db, pack="linguist", pack_version=LINGUIST_PACK_VERSION, literature=json.dumps(counts, separators=(",", ":")),
             literature_license="Public domain texts (Aozora Bunko); annotations CC BY-SA 4.0 Tsumugi contributors (AI-drafted)")
    db.commit()
    db.close()
    log(f"literature: {counts}")
    return counts


# --- Drafting through the owner's endpoint -----------------------------------------------------------------------

POEM_SYSTEM = (
    "You annotate a public-domain modern Japanese poem for an intermediate learner. Return one JSON object with keys "
    "titleEn (short English title), vocabulary (4 to 10 objects {word, reading, gloss}: word copied exactly from the "
    "poem, reading in modern hiragana, gloss in short English), paraphrase (faithful plain modern Japanese, 新字新仮名, "
    "plain form), gloss (plain faithful English prose), note (one or two English sentences on imagery or historical "
    "kana). Do not quote published translations."
)


def draft(endpoint: str, model: str, limit: int, api_key_env: str) -> int:
    from llm_draft import chat_json

    done = 0
    for f in poem_files():
        doc = json.loads(f.read_text(encoding="utf-8"))
        for p in doc["poems"]:
            if done >= limit or all(p.get(k) for k in POEM_KEYS):
                continue
            clean = text_of(p, poem=True)
            try:
                out = chat_json(endpoint, model, POEM_SYSTEM, f"Title: {p['title']}\nAuthor: {p['author']}\n\n{clean.text}",
                                api_key_env=api_key_env)
            except Exception as e:  # noqa: BLE001 - network or model failure: keep going with the next poem
                log(f"{p['id']}: {e}")
                continue
            vocab = [v for v in out.get("vocabulary", []) if isinstance(v, dict) and v.get("word") in clean.text
                     and HIRAGANA.match(v.get("reading", "")) and v.get("gloss")]
            if len(vocab) < 4 or not all(isinstance(out.get(k), str) and out[k].strip() for k in ("titleEn", "paraphrase", "gloss", "note")):
                log(f"{p['id']}: the reply failed validation; skipped")
                continue
            nfc = lambda s: unicodedata.normalize("NFC", s.strip())
            p.update({"titleEn": nfc(out["titleEn"]), "vocabulary": [{k: nfc(v[k]) for k in ("word", "reading", "gloss")} for v in vocab[:10]],
                      "paraphrase": nfc(out["paraphrase"]), "gloss": nfc(out["gloss"]), "note": nfc(out["note"]),
                      "source": "llm", "verified": False})
            f.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")  # save after each
            done += 1
            log(f"{p['id']}: drafted")
    log(f"drafted {done} poems; review them with items/review.py packs/literature/poems/<file>.json")
    return 0


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("command", nargs="?", default="build", choices=["build", "check", "draft"])
    ap.add_argument("--packs", type=Path, default=PACKS)
    ap.add_argument("--endpoint")
    ap.add_argument("--model")
    ap.add_argument("--limit", type=int, default=50)
    ap.add_argument("--api-key-env", default="LLM_API_KEY")
    args = ap.parse_args(argv)
    if args.command == "check":
        errors, poem_texts, circle_texts = validate()
        for e in errors:
            print(f"ERROR: {e}")
        print(f"{len(poem_texts)} poems, {len(circle_texts)} circle texts, {len(errors)} errors")
        return 1 if errors else 0
    if args.command == "draft":
        if not (args.endpoint and args.model):
            ap.error("draft needs --endpoint and --model")
        return draft(args.endpoint, args.model, args.limit, args.api_key_env)
    build(args.packs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
