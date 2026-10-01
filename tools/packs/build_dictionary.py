"""Build content/packs/<lang>/dictionary.sqlite (+ dictionary.json manifest) with the language-neutral schema of
BRIEF §5.2 (shared/src/commonMain/sqldelightDictionary/.../dictionary.sq, executed verbatim).

Sources (pinned in sources.lock; licenses in docs/LICENSES.md; attribution stored in each pack's meta table):
  ja                          JMdict (EDRDG, CC BY-SA 4.0) via scriptin/jmdict-simplified        dict_jmdict.py
  zh-Hans                     CC-CEDICT (MDBG, CC BY-SA 4.0)                                    dict_cedict.py
  es fr de pt-BR ru ko ar fa id  English Wiktionary via kaikki.org (CC BY-SA 3.0)                dict_kaikki.py
  frequency ranks             wordfreq (Robyn Speer, data CC BY-SA 4.0)

Lemmas are ranked by wordfreq Zipf frequency of the headword (ties: adapter weight, then source order); the top
--max-entries are kept (default 40000 for the Wiktionary languages, all of JMdict / CC-CEDICT) and frequencyRank is
that rank (NULL when wordfreq doesn't know the word). Fold keys come from dict_fold.fold, the mirror of the Kotlin
DictionaryFold.

Run: uv run --group content python packs/build_dictionary.py --language es [--max-entries N]
     uv run --group content python packs/build_dictionary.py --language all
"""

from __future__ import annotations

import argparse
import gzip
import json
import sqlite3
import time
import unicodedata
from collections.abc import Callable, Iterable
from datetime import UTC, datetime
from pathlib import Path

import dict_cedict
import dict_jmdict
import dict_kaikki
from common import (
    DICTIONARY_PACK_VERSION,
    DICTIONARY_SQ,
    PACKS,
    log,
    read_zip_json,
    schema_statements,
    sha256,
    source,
    source_entry,
)
from dict_fold import fold
from dict_model import FormLink, Lemma

LANGUAGES = ["ja", "zh-Hans", "es", "fr", "de", "pt-BR", "ru", "ko", "ar", "fa", "id"]
DEFAULT_MAX_ENTRIES = 40_000
SCHEMA_VERSION = "1"
# Punctuation that belongs to words (aujourd'hui, kata-kata, l·l); any other makes a headword's frequency 0.
KEEP_PUNCT = {"-", "'", "’", "·"}
WIKTIONARY_ATTRIBUTION = (
    "Definitions from English Wiktionary (https://en.wiktionary.org), © Wiktionary contributors, licensed under "
    "CC BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/); extracted by wiktextract "
    "(Tatu Ylonen) and published at https://kaikki.org. Frequency ranks from wordfreq (Robyn Speer), "
    "data CC BY-SA 4.0."
)
PACK_INFO = {
    "ja": {
        "sources": ["jmdict-eng"],
        "source": "JMdict (jmdict-simplified)",
        "license": "CC BY-SA 4.0",
        "attribution": (
            "This dictionary uses the JMdict dictionary file, the property of the Electronic Dictionary Research "
            "and Development Group (EDRDG), used in conformance with the Group's licence "
            "(https://www.edrdg.org/edrdg/licence.html), CC BY-SA 4.0. JSON conversion by scriptin/jmdict-simplified. "
            "Frequency ranks from wordfreq (Robyn Speer), data CC BY-SA 4.0."
        ),
    },
    "zh-Hans": {
        "sources": ["cc-cedict"],
        "source": "CC-CEDICT (MDBG)",
        "license": "CC BY-SA 4.0",
        "attribution": (
            "CC-CEDICT, community maintained free Chinese-English dictionary, published by MDBG "
            "(https://www.mdbg.net/chinese/dictionary?page=cc-cedict), licensed under CC BY-SA 4.0 "
            "(https://creativecommons.org/licenses/by-sa/4.0/). Based on CEDICT © 1997, 1998 Paul Andrew Denisowski. "
            "Frequency ranks from wordfreq (Robyn Speer), data CC BY-SA 4.0."
        ),
    },
}
for _code, _name in dict_kaikki.KAIKKI_NAMES.items():
    PACK_INFO[_code] = {
        "sources": [f"kaikki-{_name.lower()}"],
        "source": f"English Wiktionary via kaikki.org ({_name})",
        "license": "CC BY-SA 3.0",
        "attribution": WIKTIONARY_ATTRIBUTION,
    }


def wordfreq_code(language: str) -> str:
    return {"pt-BR": "pt", "zh-Hans": "zh"}.get(language, language)


def zipf_function(language: str) -> Callable[[str], float]:
    import wordfreq  # content uv group

    code = wordfreq_code(language)
    cache: dict[str, float] = {}

    def zipf(word: str) -> float:
        if word.startswith("-") or word.endswith("-"):
            return 0.0  # affixes (di-, -nya): wordfreq would score the bare letters
        if any(unicodedata.category(c).startswith("P") and c not in KEEP_PUNCT for c in word):
            return 0.0  # "und,", "o(a)", "В.": wordfreq drops the punctuation and would score the bare word
        if word not in cache:
            try:
                cache[word] = wordfreq.zipf_frequency(word, code)
            except Exception:  # noqa: BLE001 — tokenizer edge cases (empty after tokenizing, odd scripts)
                cache[word] = 0.0
        return cache[word]

    return zipf


# --- Ranking ----------------------------------------------------------------------------------------------------


def rank_order(
    items: list[tuple[list[str], float, float]], zipf: Callable[[str], float],
) -> list[tuple[int, float]]:
    """(index, zipf) for items [(freq_words, weight, penalty)]: most frequent (Zipf minus penalty) first, ties by
    weight, then input order.

    A headword whose lower-case spelling is also a headword ("A"/"a", "Es"/"es") sorts after it: wordfreq folds case,
    so both get the same frequency.
    """
    heads = {words[0] for words, _, _ in items if words}
    scored = []
    for i, (words, weight, penalty) in enumerate(items):
        z = max((zipf(w) for w in words if w), default=0.0)
        if words and words[0] != words[0].lower() and words[0].lower() in heads:
            weight -= 1.0
        scored.append((z, max(z - penalty, 0.0) if z > 0 else -1.0, weight, i))
    scored.sort(key=lambda t: (-t[1], -t[2], t[3]))
    return [(i, z) for z, _, _, i in scored]


def select(lemmas: list[Lemma], zipf: Callable[[str], float], max_entries: int | None) -> list[tuple[Lemma, int | None]]:
    """Lemmas in rank order, capped, each with its frequencyRank (None when wordfreq gives 0)."""
    order = rank_order([(lm.freq_words or [lm.headword], lm.weight, lm.penalty) for lm in lemmas], zipf)
    if max_entries is not None:
        order = order[:max_entries]
    return [(lemmas[i], n + 1 if z > 0 else None) for n, (i, z) in enumerate(order)]


# --- Adapters → lemma lists -------------------------------------------------------------------------------------


def jsonl(path: Path) -> Iterable[tuple[int, dict]]:
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f):
            if line.strip():
                yield n, json.loads(line)


def kaikki_lemmas(
    lines: Callable[[], Iterable[tuple[int, dict]]], language: str, zipf: Callable[[str], float],
    max_entries: int | None,
) -> tuple[list[tuple[Lemma, int | None]], list[FormLink]]:
    """Two streaming passes over a kaikki JSONL file: rank lemma lines, then build the kept ones and all links."""
    cands = []  # (line number, headword, weight)
    for n, d in lines():
        c = dict_kaikki.candidate(d, language)
        if c:
            cands.append((n, c[0], c[1]))
    log(f"  pass 1: {len(cands)} lemma lines")
    order = rank_order([([h], w, 0.0) for _, h, w in cands], zipf)
    if max_entries is not None:
        order = order[:max_entries]
    keep = {cands[i][0]: (pos, z) for pos, (i, z) in enumerate(order)}
    built: list[tuple[int, Lemma, int | None]] = []
    links: list[FormLink] = []
    for n, d in lines():
        links.extend(dict_kaikki.links(d, language))
        if n in keep:
            lm = dict_kaikki.lemma(d, language)
            if lm is not None:
                pos, z = keep[n]
                built.append((pos, lm, pos + 1 if z > 0 else None))
    built.sort(key=lambda t: t[0])
    log(f"  pass 2: {len(built)} lemmas, {len(links)} form-of links")
    return [(lm, r) for _, lm, r in built], links


def load(language: str, zipf: Callable[[str], float], max_entries: int | None):
    if language == "ja":
        data = read_zip_json(source("jmdict-eng"))
        log(f"  JMdict {data['version']} ({data['dictDate']}): {len(data['words'])} words")
        return select(list(dict_jmdict.read(data)), zipf, max_entries), [], {"jmdict_version": data["version"],
                                                                              "jmdict_date": data["dictDate"]}
    if language == "zh-Hans":
        with gzip.open(source("cc-cedict"), "rt", encoding="utf-8") as f:
            lemmas = dict_cedict.read(f)
        log(f"  CC-CEDICT: {len(lemmas)} merged entries")
        return select(lemmas, zipf, max_entries), [], {}
    name = dict_kaikki.KAIKKI_NAMES[language]
    path = source(f"kaikki-{name.lower()}")
    ranked, links = kaikki_lemmas(lambda: jsonl(path), language, zipf, max_entries)
    return ranked, links, {}


# --- Writing ----------------------------------------------------------------------------------------------------


def write_pack(
    path: Path, language: str, ranked: list[tuple[Lemma, int | None]], links: list[FormLink], meta: dict[str, str],
) -> dict[str, int]:
    """Write a fresh pack at path; returns row counts."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".sqlite.part")
    tmp.unlink(missing_ok=True)
    db = sqlite3.connect(tmp)
    db.execute("PRAGMA journal_mode = OFF")
    db.execute("PRAGMA synchronous = OFF")
    for stmt in schema_statements(DICTIONARY_SQ):
        db.execute(stmt)
    db.execute("PRAGMA user_version = 1")

    entries, senses, examples, forms, folds = [], [], [], {}, {}
    by_head: dict[str, list[tuple[int, str]]] = {}
    for eid, (lm, rank) in enumerate(ranked, start=1):
        entries.append((eid, language, lm.headword, lm.reading, lm.pos, rank))
        senses.extend((eid, i, g, dom, reg) for i, (g, dom, reg) in enumerate(lm.senses))
        examples.extend((eid, i, t, tr, src) for i, (t, tr, src) in enumerate(lm.examples))
        for name in [lm.headword, *lm.aliases]:
            by_head.setdefault(name, []).append((eid, lm.pos))
        for text in filter(None, (lm.headword, lm.reading)):
            key = fold(text, language)
            if key and key != text:  # equal keys are found through the entry indexes
                folds[(key, eid)] = 0
        for surface, tags in lm.forms.items():
            forms.setdefault((surface, eid), tags)

    resolved = 0
    for link in links:
        targets = by_head.get(link.target, [])
        same_pos = [eid for eid, pos in targets if pos == link.pos]
        for eid in same_pos or [eid for eid, _ in targets]:
            if (link.surface, eid) not in forms and entries[eid - 1][2] != link.surface:
                forms[(link.surface, eid)] = link.tags
                resolved += 1
    for surface, eid in forms:
        key = fold(surface, language)
        if key and key != surface:  # equal keys are found through the form index
            folds.setdefault((key, eid), 1)

    db.executemany("INSERT INTO entry VALUES (?,?,?,?,?,?)", entries)
    db.executemany("INSERT INTO sense VALUES (?,?,?,?,?)", senses)
    db.executemany("INSERT INTO example VALUES (?,?,?,?,?)", examples)
    db.executemany("INSERT INTO form VALUES (?,?,?)", ((s, e, t) for (s, e), t in sorted(forms.items())))
    db.executemany("INSERT INTO fold VALUES (?,?,?,?)",
                   ((k, e, kind, entries[e - 1][5]) for (k, e), kind in sorted(folds.items())))
    counts = {"entries": len(entries), "senses": len(senses), "forms": len(forms), "examples": len(examples),
              "folds": len(folds), "links_resolved": resolved}
    meta = {**meta, "entry_count": str(len(entries)), "form_count": str(len(forms))}
    db.executemany("INSERT INTO meta VALUES (?,?)", sorted(meta.items()))
    db.commit()
    db.execute("ANALYZE")
    db.commit()
    db.execute("VACUUM")
    db.close()
    tmp.replace(path)
    return counts


def build(language: str, max_entries: int | None, out_dir: Path = PACKS) -> dict:
    started = time.monotonic()
    info = PACK_INFO[language]
    if max_entries is None and language not in ("ja", "zh-Hans"):
        max_entries = DEFAULT_MAX_ENTRIES
    log(f"{language}: building (max entries {max_entries or 'all'})")
    zipf = zipf_function(language)
    ranked, links, extra = load(language, zipf, max_entries)
    built = datetime.now(UTC).date().isoformat()
    meta = {
        "language": language,
        "source": info["source"],
        "license": info["license"],
        "attribution": info["attribution"],
        "build_date": built,
        "pack_version": DICTIONARY_PACK_VERSION,
        "schema_version": SCHEMA_VERSION,
        "frequency": "wordfreq Zipf (CC BY-SA 4.0, Robyn Speer)",
        **extra,
    }
    path = out_dir / language / "dictionary.sqlite"
    counts = write_pack(path, language, ranked, links, meta)
    seconds = round(time.monotonic() - started, 1)
    manifest = {
        "language": language,
        "file": "dictionary.sqlite",
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
        "pack_version": DICTIONARY_PACK_VERSION,
        "schema_version": SCHEMA_VERSION,
        "built": built,
        "build_seconds": seconds,
        "max_entries": max_entries,
        "counts": counts,
        "license": info["license"],
        "attribution": info["attribution"],
        "sources": [
            {k: source_entry(name).get(k) for k in ("url", "file", "release", "date", "sha256", "license")}
            | {"name": name}
            for name in info["sources"]
        ],
    }
    (path.parent / "dictionary.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                                                 encoding="utf-8")
    log(f"{language}: {counts} → {path} ({manifest['bytes'] / 1e6:.1f} MB) in {seconds}s")
    return manifest


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--language", required=True, help="BCP-47 code, or 'all': " + " ".join(LANGUAGES))
    ap.add_argument("--max-entries", type=int, default=None,
                    help=f"keep the N most frequent lemmas (default {DEFAULT_MAX_ENTRIES}; ja/zh-Hans: all)")
    args = ap.parse_args()
    languages = LANGUAGES if args.language == "all" else [args.language]
    for lang in languages:
        if lang not in PACK_INFO:
            raise SystemExit(f"unknown language {lang}; one of {' '.join(LANGUAGES)}")
    for lang in languages:
        build(lang, args.max_entries)


if __name__ == "__main__":
    main()
