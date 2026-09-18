"""Build the sentence tables of content/packs/dictionary.sqlite from Tatoeba (CC BY 2.0 FR) and refine
entry.rank with word frequency counted over Tatoeba's indexed Japanese corpus (the "B-lines").

Must run after build_dictionary.py (it maps indexed headwords to JMdict entry ids).

Run: uv run packs/build_sentences.py
"""

from __future__ import annotations

import bz2
import re
import tarfile
from collections import Counter, defaultdict

from common import finish_pack, log, nfc, open_pack, reset_tables, set_meta, source, to_hiragana

# B-line token: headword, optional (reading), optional [sense], optional {surface form}, optional ~
TOKEN_RE = re.compile(r"^([^(\[{~]+)(?:\(([^)]+)\))?")
MAX_SENTENCES_PER_WORD = 30
MAX_SENTENCE_LEN = 60
MAX_WORDS_PER_KANJI = 150


def read_tsv_bz2(name: str):
    """A Tatoeba export pinned in sources.lock (tatoeba-*)."""
    with bz2.open(source(name), "rt", encoding="utf-8") as f:
        for line in f:
            yield line.rstrip("\n").split("\t")


def main() -> None:
    db = open_pack()
    reset_tables(db, {"sentence", "sentence_word"})

    log("Tatoeba sentences…")
    jpn = {int(r[0]): nfc(r[2]) for r in read_tsv_bz2("tatoeba-jpn-sentences") if len(r) >= 3}
    links = defaultdict(list)
    for r in read_tsv_bz2("tatoeba-jpn-eng-links"):
        if len(r) >= 2:
            links[int(r[0])].append(int(r[1]))
    wanted_eng = {e for ids in links.values() for e in ids}
    eng = {
        int(r[0]): r[2]
        for r in read_tsv_bz2("tatoeba-eng-sentences")
        if len(r) >= 3 and int(r[0]) in wanted_eng
    }

    # Headword lookup: kanji form or kana reading -> entry ids (with their JLPT level).
    by_kanji, by_kana = defaultdict(set), defaultdict(set)
    for eid, text in db.execute("SELECT entry_id, text FROM entry_kanji"):
        by_kanji[text].add(eid)
    for eid, search in db.execute("SELECT entry_id, search FROM entry_kana"):
        by_kana[search].add(eid)
    jlpt = dict(db.execute("SELECT id, jlpt FROM entry"))

    def resolve(head: str, reading: str | None) -> set[int]:
        if reading:
            both = by_kanji.get(head, set()) & by_kana.get(to_hiragana(reading), set())
            if both:
                return both
        return by_kanji.get(head) or by_kana.get(to_hiragana(head)) or set()

    log("Tatoeba index…")
    freq: Counter[int] = Counter()
    sentence_words: dict[int, set[int]] = {}
    with tarfile.open(source("tatoeba-jpn-indices")) as tf:
        member = next(m for m in tf.getmembers() if m.isfile())
        for raw in tf.extractfile(member):
            parts = raw.decode("utf-8").rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            sid = int(parts[0])
            ids: set[int] = set()
            for tok in parts[2].split():
                m = TOKEN_RE.match(nfc(tok))
                if not m:
                    continue
                resolved = resolve(m.group(1), m.group(2))
                if len(resolved) == 1:  # ambiguous headwords are skipped rather than guessed
                    ids |= resolved
            freq.update(ids)
            sentence_words[sid] = ids

    # Keep sentences that have an English translation and are reasonably short.
    rows, per_word = [], defaultdict(list)
    for sid, ids in sentence_words.items():
        ja = jpn.get(sid)
        en = next((eng[e] for e in links.get(sid, []) if e in eng), None)
        if not ja or not en or len(ja) > MAX_SENTENCE_LEN:
            continue
        levels = [jlpt.get(i) for i in ids]
        level = None if not levels or any(lv is None for lv in levels) else min(levels)
        rows.append((sid, ja, en, level))
        for i in ids:
            per_word[i].append((len(ja), sid))

    words = [(eid, sid) for eid, lst in per_word.items() for _, sid in sorted(lst)[:MAX_SENTENCES_PER_WORD]]
    kept = {sid for _, sid in words}
    db.executemany("INSERT INTO sentence VALUES (?,?,?,?)", (r for r in rows if r[0] in kept))
    db.executemany("INSERT INTO sentence_word VALUES (?,?)", words)

    log("refining ranks with Tatoeba frequency…")
    db.executemany(
        "UPDATE entry SET rank = rank - ? WHERE id = ?", ((min(c, 999_999), eid) for eid, c in freq.items())
    )
    db.execute(
        "UPDATE kanji_word SET rank = (SELECT rank FROM entry WHERE entry.id = kanji_word.entry_id)"
    )
    # The kanji view lists the most common words per kanji; keep only those (rebuild beats a NOT IN delete).
    db.execute(
        """CREATE TEMP TABLE keep AS SELECT kanji, entry_id, rank FROM (
               SELECT *, ROW_NUMBER() OVER (PARTITION BY kanji ORDER BY rank) AS n FROM kanji_word
           ) WHERE n <= ?""",
        (MAX_WORDS_PER_KANJI,),
    )
    db.execute("DELETE FROM kanji_word")
    db.execute("INSERT INTO kanji_word SELECT * FROM keep ORDER BY kanji, entry_id")
    db.execute("DROP TABLE keep")
    set_meta(db, tatoeba_sentences=str(len(kept)))
    finish_pack(db)
    log(f"  {len(kept)} sentences linked to {len(per_word)} words")


if __name__ == "__main__":
    main()
