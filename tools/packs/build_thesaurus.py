"""Build the expression-thesaurus tables of content/packs/dictionary.sqlite (BRIEF_V2 §6.13; DECISIONS D-272).

Sources: tools/packs/thesaurus/clusters/*.json, clusters by scene and emotion drafted in our own words (no hyogen.info
text; source "llm" until reviewed). Each expression is linked to its JMdict entry when the dictionary has it (text and
reading, then the text without a trailing する for kanji verbs), takes that entry's first-sense glosses, and up to two
Tatoeba examples from the pack's own `sentence` table: the word index first, then sentences containing the
expression's stem (its text without the final kana of a verb or adjective), shortest-to-ideal first, at most 45
characters. The drafted example is kept too, labeled with the cluster's source. `plain` lemmas feed the writing
studio's flags (expression_plain).

Run after build_sentences.py (the Tatoeba tables): uv run python packs/build_thesaurus.py [check]
     uv run python packs/build_thesaurus.py draft --id courage --ja 勇気 --en Courage --kind emotion --endpoint URL --model NAME
         drafts one new cluster through any OpenAI-compatible endpoint into clusters/drafted.json (validated; an id is
         never reused).
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from common import (
    DICTIONARY_SQ,
    PACKS,
    REPO,
    log,
    open_pack,
    reset_tables,
    set_meta,
    to_hiragana,
)

THESAURUS_SQ = REPO / "shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/thesaurus.sq"
CLUSTERS = HERE / "thesaurus" / "clusters"
TABLES = {"expression_cluster", "expression", "expression_example", "expression_plain"}
KINDS = {"emotion", "scene"}
REGISTERS = {"casual", "neutral", "formal", "literary"}
SLUG = re.compile(r"^[a-z][a-z0-9-]*$")
HIRAGANA = re.compile(r"^[ぁ-ゖー・]+$")
EXAMPLE_MAX = 45
IDEAL = 20
VERB_ENDINGS = "うくぐすつぬぶむるい"
HIRAGANA_CHARS = "".join(chr(c) for c in range(0x3041, 0x3097))


def cluster_files() -> list[Path]:
    return sorted(CLUSTERS.glob("*.json"))


def load_clusters() -> list[tuple[Path, dict]]:
    out = []
    for f in cluster_files():
        out += [(f, c) for c in json.loads(f.read_text(encoding="utf-8"))["clusters"]]
    return out


def check_cluster(c: dict) -> list[str]:
    cid = c.get("id", "?")
    errs = []
    if not SLUG.match(cid):
        errs.append(f"{cid}: id must be a lowercase slug")
    if c.get("kind") not in KINDS:
        errs.append(f"{cid}: kind must be emotion or scene")
    for k in ("ja", "reading", "en", "description"):
        if not isinstance(c.get(k), str) or not c[k].strip():
            errs.append(f"{cid}: missing {k}")
    plain = c.get("plain")
    if not isinstance(plain, list) or not 1 <= len(plain) <= 6 or not all(isinstance(p, str) and p for p in plain):
        errs.append(f"{cid}: plain must list 1–6 lemmas")
    exprs = c.get("expressions") or []
    if not 6 <= len(exprs) <= 16:
        errs.append(f"{cid}: {len(exprs)} expressions (6–16)")
    seen = set()
    for e in exprs:
        t = e.get("text", "")
        if t in seen:
            errs.append(f"{cid}: {t} twice")
        seen.add(t)
        if not HIRAGANA.match(e.get("reading", "")):
            errs.append(f"{cid}/{t}: reading must be hiragana")
        if e.get("register") not in REGISTERS or e.get("intensity") not in (1, 2, 3):
            errs.append(f"{cid}/{t}: register and intensity 1–3")
        ex = e.get("example") or {}
        if not ex.get("ja") or not ex.get("en") or not e.get("nuance"):
            errs.append(f"{cid}/{t}: nuance and example {{ja, en}} are required")
        elif head(t) not in ex["ja"]:
            errs.append(f"{cid}/{t}: the example doesn't use the expression")
    if unicodedata.normalize("NFC", json.dumps(c, ensure_ascii=False)) != json.dumps(c, ensure_ascii=False):
        errs.append(f"{cid}: not NFC")
    if c.get("source") not in ("llm", "verified") or (c.get("verified") and c.get("source") != "verified"):
        errs.append(f"{cid}: source llm, or verified with verified true")
    return errs


def validate() -> list[str]:
    errors, ids = [], set()
    for f, c in load_clusters():
        if c.get("id") in ids:
            errors.append(f"{c.get('id')}: duplicate id ({f.name})")
        ids.add(c.get("id"))
        errors += check_cluster(c)
    return errors


def head(text: str) -> str:
    """What every inflected use of an expression contains: its first two characters, or for a short kanji verb or
    adjective (潤む, 輝く) the part before its okurigana."""
    core = text.rstrip(HIRAGANA_CHARS)
    return core[:2] if core else stem(text)[:2]


def stem(text: str) -> str:
    """The searchable part of an expression: する dropped, and a verb's or adjective's final kana."""
    t = text.removesuffix("する") if len(text) > 3 and text.endswith("する") else text
    if len(t) >= 3 and t[-1] in VERB_ENDINGS:
        t = t[:-1]
    return t


class Resolver:
    def __init__(self, db: sqlite3.Connection):
        self.db = db
        self.kanji: dict[str, set[int]] = {}
        for eid, text in db.execute("SELECT entry_id, text FROM entry_kanji"):
            self.kanji.setdefault(text, set()).add(eid)
        self.kana: dict[str, set[int]] = {}
        for eid, text in db.execute("SELECT entry_id, text FROM entry_kana"):
            self.kana.setdefault(to_hiragana(text), set()).add(eid)
        self.rank = dict(db.execute("SELECT id, rank FROM entry"))

    def resolve(self, text: str, reading: str) -> int | None:
        for t, r in ((text, reading), (text.removesuffix("する"), reading.removesuffix("する"))):
            if not t:
                continue
            by_form = self.kanji.get(t, set()) | self.kana.get(to_hiragana(t), set())
            cands = by_form & self.kana.get(to_hiragana(r), set()) if r else by_form
            if cands:
                return min(cands, key=lambda e: self.rank.get(e, 1 << 30))
        return None

    def gloss(self, eid: int | None) -> str:
        if eid is None:
            return ""
        row = self.db.execute("SELECT glosses FROM sense WHERE entry_id = ? ORDER BY ord LIMIT 1", (eid,)).fetchone()
        return "; ".join(json.loads(row[0])[:3]) if row else ""


def examples(db: sqlite3.Connection, eid: int | None, text: str, used: set[int]) -> list[int]:
    """Up to two Tatoeba sentence ids for an expression (not reused within a cluster)."""
    cands: list[tuple[int, str]] = []
    if eid is not None:
        cands = db.execute("SELECT s.id, s.ja FROM sentence_word w JOIN sentence s ON s.id = w.sentence_id "
                           "WHERE w.entry_id = ? AND length(s.ja) <= ?", (eid, EXAMPLE_MAX)).fetchall()
        key = stem(text)
        if " " not in text and len(key) >= 2:
            cands = [c for c in cands if key in c[1]] or cands
    if len(cands) < 2:
        key = stem(text)
        if len(key) >= 2:
            cands += db.execute("SELECT id, ja FROM sentence WHERE instr(ja, ?) > 0 AND length(ja) <= ? LIMIT 200",
                                (key, EXAMPLE_MAX)).fetchall()
    cands = [c for c in dict(cands).items() if c[0] not in used]
    cands.sort(key=lambda c: (abs(len(c[1]) - IDEAL), c[0]))
    return [c[0] for c in cands[:2]]


def build(packs: Path = PACKS) -> dict[str, int]:
    errors = validate()
    if errors:
        for e in errors:
            log(f"ERROR: {e}")
        raise SystemExit(f"thesaurus: {len(errors)} validation errors")
    db = open_pack(packs / "dictionary.sqlite", DICTIONARY_SQ)
    reset_tables(db, TABLES, THESAURUS_SQ)
    resolver = Resolver(db)
    n_expr = n_linked = n_examples = 0
    for ord_, (_f, c) in enumerate(load_clusters()):
        verified = 1 if c.get("verified") else 0
        db.execute("INSERT INTO expression_cluster VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                   (c["id"], ord_, c["kind"], c["ja"], c["reading"], c["en"], c["description"],
                    "verified" if verified else c["source"], verified))
        db.executemany("INSERT OR IGNORE INTO expression_plain VALUES (?, ?)", [(p, c["id"]) for p in c["plain"]])
        used: set[int] = set()
        for i, e in enumerate(c["expressions"]):
            eid = resolver.resolve(e["text"], e["reading"])
            db.execute("INSERT INTO expression VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                       (c["id"], i, e["text"], e["reading"], eid, resolver.gloss(eid), e["nuance"], e["register"],
                        e["intensity"], e["example"]["ja"], e["example"]["en"]))
            ids = examples(db, eid, e["text"], used)
            used.update(ids)
            db.executemany("INSERT INTO expression_example VALUES (?, ?, ?)", [(c["id"], i, s) for s in ids])
            n_expr += 1
            n_linked += eid is not None
            n_examples += len(ids)
    counts = {"clusters": len(load_clusters()), "expressions": n_expr, "linked": n_linked, "tatoebaExamples": n_examples}
    set_meta(db, thesaurus=json.dumps(counts, separators=(",", ":")))
    db.commit()
    db.close()
    log(f"thesaurus: {counts}")
    return counts


DRAFT_SYSTEM = (
    "You write one expression-thesaurus cluster for Japanese learners, in your own words. Reply with one JSON object "
    "{description, plain, expressions}: description = 1–2 English sentences on what the cluster covers and how its "
    "expressions differ; plain = 1–4 plain dictionary-form words learners overuse for this meaning; expressions = 8–14 "
    "objects {text, reading, nuance, register, intensity, example: {ja, en}} ordered mild to strong, with text in "
    "citation form, reading in hiragana, register one of casual/neutral/formal/literary, intensity 1–3, and a natural "
    "15–45 character example sentence using the expression."
)


def draft(cid: str, ja: str, en: str, kind: str, endpoint: str, model: str, api_key_env: str) -> int:
    from llm_draft import chat_json

    if cid in {c["id"] for _, c in load_clusters()}:
        raise SystemExit(f"{cid} already exists; ids are never reused")
    out = chat_json(endpoint, model, DRAFT_SYSTEM, f"Cluster: {ja} ({en}), kind {kind}.", api_key_env=api_key_env)
    c = {"id": cid, "kind": kind, "ja": ja, "reading": out.get("reading", ""), "en": en,
         "description": out.get("description"), "plain": out.get("plain"), "expressions": out.get("expressions"),
         "source": "llm", "verified": False}
    c = json.loads(unicodedata.normalize("NFC", json.dumps(c, ensure_ascii=False)))
    if not c["reading"]:
        c["reading"] = "".join(ch for ch in ja if "ぁ" <= ch <= "ゖ") or ja
    errs = [e for e in check_cluster(c) if "reading" not in e.split(":")[0]]
    if errs:
        raise SystemExit("the draft failed validation: " + "; ".join(errs[:5]))
    target = CLUSTERS / "drafted.json"
    doc = json.loads(target.read_text(encoding="utf-8")) if target.exists() else {"source": "llm", "clusters": []}
    doc["clusters"].append(c)
    target.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    log(f"drafted {cid} into {target.name}; review it with items/review.py packs/thesaurus/clusters/drafted.json")
    return 0


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("command", nargs="?", default="build", choices=["build", "check", "draft"])
    ap.add_argument("--packs", type=Path, default=PACKS)
    ap.add_argument("--id")
    ap.add_argument("--ja")
    ap.add_argument("--en")
    ap.add_argument("--kind", choices=sorted(KINDS), default="emotion")
    ap.add_argument("--endpoint")
    ap.add_argument("--model")
    ap.add_argument("--api-key-env", default="LLM_API_KEY")
    args = ap.parse_args(argv)
    if args.command == "check":
        errors = validate()
        for e in errors:
            print(f"ERROR: {e}")
        print(f"{len(load_clusters())} clusters, {len(errors)} errors")
        return 1 if errors else 0
    if args.command == "draft":
        if not (args.id and args.ja and args.en and args.endpoint and args.model):
            ap.error("draft needs --id, --ja, --en, --endpoint and --model")
        return draft(args.id, args.ja, args.en, args.kind, args.endpoint, args.model, args.api_key_env)
    build(args.packs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
