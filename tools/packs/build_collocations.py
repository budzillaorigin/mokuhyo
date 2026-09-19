"""Build the `collocation` table of content/packs/dictionary.sqlite: PMI over the tokenized Tatoeba corpus
(BRIEF_V2 §6.13; DECISIONS D-273).

The whole Japanese Tatoeba export (pinned as tatoeba-jpn-sentences, CC BY 2.0 FR) is tokenized with the tools' port of
the app's lattice tokenizer (tools/items/lattice.py over tokenizer.sqlite), and lemmas resolve to JMdict ids with the
reader's rule (readers_lib.Dictionary.resolve). Three adjacent patterns are counted, once per sentence:

  NV  noun + case particle (を が に で と へ から) + verb        雨が降る, 電話をかける
  AN  い-adjective (attributive) + noun, or な-adjective + な + noun   強い雨, 静かな夜
  AV  adverb (+ と/に) + verb                                     ゆっくり歩く, はっきりと言う

For a pair (a, b) of one pattern (and particle): pmi = log2(c(a,b) · N / (c(a) · c(b))), with N the pattern's
instances and c(a), c(b) the marginals. Pairs need c(a,b) ≥ MIN_COUNT and pmi ≥ MIN_PMI; each word keeps its
TOP_PER_WORD strongest pairs (by pmi · min(count, 20), the app's order) as first or second member. The example is
the shortest pack sentence (the dictionary pack's Tatoeba subset, which has English) of at most 40 characters with
the pair. The Japanese Wikipedia dump is out of scope, as in D-152.

The tokenized pairs are cached in tools/.cache (keyed by the corpus and tokenizer hashes), so a re-run only recounts.

Run: uv run python packs/build_collocations.py [--packs DIR] [--limit N]
"""

from __future__ import annotations

import argparse
import bz2
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE / "readers"), str(HERE.parent / "items")]

from common import (
    CACHE,
    DICTIONARY_SQ,
    PACKS,
    REPO,
    log,
    nfc,
    open_pack,
    reset_tables,
    set_meta,
    sha256,
    source,
)
from lattice import Lattice
from readers_lib import Dictionary, base_reading

THESAURUS_SQ = REPO / "shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/thesaurus.sq"
CASE_PARTICLES = {"を", "が", "に", "で", "と", "へ", "から"}
MIN_COUNT = 3
MIN_PMI = 1.0
TOP_PER_WORD = 25
EXAMPLE_MAX = 40
NOUN_KINDS = {"一般", "サ変接続", "形容動詞語幹", "ナイ形容詞語幹"}
STOP_VERBS = {"ある", "いる", "なる", "する", "できる", "いう", "言う", "思う", "くる", "来る", "おる", "やる"}  # NV only
STOP_NOUNS = {"こと", "もの", "ところ", "ため", "よう", "の", "ん", "方", "人", "時", "中", "事", "物"}


def lemma_of(t) -> tuple[str, str]:
    """(dictionary form, its hiragana reading) of a lattice token."""
    reading = base_reading(t.surface, t.base, t.reading) or ""
    return nfc(t.base or t.surface), reading


def pairs_of(tokens) -> set[tuple[str, str, str, str, str, str]]:
    """(pattern, particle, a lemma, a reading, b lemma, b reading) for every pattern instance in one sentence."""
    out = set()
    n = len(tokens)
    for i, t in enumerate(tokens):
        p1, p2 = t.pos1, t.pos2
        # NV: noun + case particle + verb (自立)
        if p1 == "名詞" and p2 in NOUN_KINDS and i + 2 < n:
            prt, v = tokens[i + 1], tokens[i + 2]
            if prt.pos1 == "助詞" and prt.pos2 == "格助詞" and prt.surface in CASE_PARTICLES and v.pos1 == "動詞" and v.pos2 == "自立":
                a, ar = lemma_of(t)
                b, br = lemma_of(v)
                if a not in STOP_NOUNS and b not in STOP_VERBS:
                    out.add(("NV", prt.surface, a, ar, b, br))
        # AN: い-adjective attributive + noun
        if p1 == "形容詞" and p2 == "自立" and t.conj_form.startswith("基本形") and i + 1 < n:
            nn = tokens[i + 1]
            if nn.pos1 == "名詞" and nn.pos2 in NOUN_KINDS:
                a, ar = lemma_of(t)
                b, br = lemma_of(nn)
                if b not in STOP_NOUNS:
                    out.add(("AN", "", a, ar, b, br))
        # AN: な-adjective + な + noun
        if p1 == "名詞" and p2 == "形容動詞語幹" and i + 2 < n:
            na, nn = tokens[i + 1], tokens[i + 2]
            if na.surface == "な" and na.pos1 == "助動詞" and nn.pos1 == "名詞" and nn.pos2 in NOUN_KINDS:
                a, ar = lemma_of(t)
                b, br = lemma_of(nn)
                if b not in STOP_NOUNS:
                    out.add(("AN", "な", a, ar, b, br))
        # AV: adverb (+ と/に) + verb
        if p1 == "副詞" and i + 1 < n:
            j = i + 1
            if tokens[j].pos1 == "助詞" and tokens[j].surface in ("と", "に") and j + 1 < n:
                j += 1
            v = tokens[j]
            if v.pos1 == "動詞" and v.pos2 == "自立":
                a, ar = lemma_of(t)
                b, br = lemma_of(v)
                if b not in ("する",):
                    out.add(("AV", "", a, ar, b, br))
    return out


def tokenized_pairs(packs: Path, limit: int | None) -> list[tuple[int, str, list[list[str]]]]:
    """(sentence id, text, pairs) for every Tatoeba sentence with at least one pattern, cached."""
    corpus = source("tatoeba-jpn-sentences")
    key = f"{sha256(corpus)[:12]}-{sha256(packs / 'tokenizer.sqlite')[:12]}{'-' + str(limit) if limit else ''}"
    cache = CACHE / f"collocation-pairs-{key}.jsonl"
    if cache.exists():
        log(f"collocations: cached pairs {cache.name}")
        out = []
        with open(cache, encoding="utf-8") as f:
            for line in f:
                sid, text, pairs = json.loads(line)
                out.append((sid, text, pairs))
        return out
    lat = Lattice(packs / "tokenizer.sqlite")
    out = []
    seen: set[str] = set()
    with bz2.open(corpus, "rt", encoding="utf-8") as f:
        for n, line in enumerate(f):
            if limit and n >= limit:
                break
            row = line.rstrip("\n").split("\t")
            if len(row) < 3:
                continue
            text = nfc(row[2]).strip()
            if not text or text in seen or len(text) > 120:
                continue
            seen.add(text)
            pairs = pairs_of(lat.tokenize(text))
            if pairs:
                out.append((int(row[0]), text, [list(p) for p in sorted(pairs)]))
            if n % 20000 == 0:
                log(f"collocations: tokenized {n} sentences")
    tmp = cache.with_suffix(".part")
    with open(tmp, "w", encoding="utf-8") as f:
        f.writelines(json.dumps(item, ensure_ascii=False) + "\n" for item in out)
    tmp.replace(cache)
    return out


def build(packs: Path = PACKS, limit: int | None = None) -> dict[str, int]:
    dictionary = Dictionary(packs / "dictionary.sqlite")
    data = tokenized_pairs(packs, limit)
    resolved: dict[tuple[str, str], int | None] = {}

    def rid(lemma: str, reading: str) -> int | None:
        k = (lemma, reading)
        if k not in resolved:
            resolved[k] = dictionary.resolve(lemma, reading or None)
        return resolved[k]

    pair_count: Counter = Counter()
    first: Counter = Counter()
    second: Counter = Counter()
    total: Counter = Counter()
    texts: dict[int, str] = {}
    sentences_of: dict[tuple, list[int]] = defaultdict(list)
    for sid, text, pairs in data:
        for pattern, particle, a, ar, b, br in pairs:
            ai, bi = rid(a, ar), rid(b, br)
            if ai is None or bi is None or ai == bi:
                continue
            texts[ai], texts[bi] = a, b
            k = (pattern, particle, ai, bi)
            pair_count[k] += 1
            first[(pattern, ai)] += 1
            second[(pattern, bi)] += 1
            total[pattern] += 1
            if len(text) <= EXAMPLE_MAX and len(sentences_of[k]) < 50:
                sentences_of[k].append(sid)

    scored = []
    for (pattern, particle, ai, bi), c in pair_count.items():
        if c < MIN_COUNT:
            continue
        pmi = math.log2(c * total[pattern] / (first[(pattern, ai)] * second[(pattern, bi)]))
        if pmi >= MIN_PMI:
            scored.append((pattern, particle, ai, bi, c, round(pmi, 3)))
    # Keep each word's strongest pairs, as first or second member.
    keep: set[tuple] = set()
    by_word: dict[int, list] = defaultdict(list)
    for row in scored:
        by_word[row[2]].append(row)
        by_word[row[3]].append(row)
    for rows in by_word.values():
        rows.sort(key=lambda r: -(r[5] * min(r[4], 20)))
        keep.update(r[:4] for r in rows[:TOP_PER_WORD])

    db = open_pack(packs / "dictionary.sqlite", DICTIONARY_SQ)
    pack_sentences = {sid: len(ja) for sid, ja in db.execute("SELECT id, ja FROM sentence")}
    reset_tables(db, {"collocation"}, THESAURUS_SQ)
    written = 0
    for pattern, particle, ai, bi, c, pmi in scored:
        if (pattern, particle, ai, bi) not in keep:
            continue
        cands = [s for s in sentences_of[(pattern, particle, ai, bi)] if s in pack_sentences]
        example = min(cands, key=lambda s: (pack_sentences[s], s)) if cands else None
        db.execute("INSERT INTO collocation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                   (ai, bi, pattern, particle, texts[ai], texts[bi], c, pmi, example))
        written += 1
    counts = dict(db.execute("SELECT pattern, count(*) FROM collocation GROUP BY pattern").fetchall())
    set_meta(db, collocations=str(written), collocation_patterns=json.dumps(counts, separators=(",", ":")),
             collocation_sentences=str(len(data)))
    db.commit()
    db.execute("VACUUM")
    db.close()
    log(f"collocations: {written} pairs from {len(data)} sentences with a pattern ({counts})")
    return counts


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--packs", type=Path, default=PACKS)
    ap.add_argument("--limit", type=int, help="tokenize only the first N sentences (testing)")
    args = ap.parse_args(argv)
    build(args.packs, args.limit)
    return 0


if __name__ == "__main__":
    sys.exit(main())

