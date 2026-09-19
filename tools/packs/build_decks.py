"""Build the freq_word table of content/packs/dictionary.sqlite: the frequency list behind the prebuilt
"Core 2k / 6k / 10k" decks and the onboarding "I know these" bands (BRIEF_V2 §6.1, DECISIONS D-152).

Must run after build_sentences.py. Frequency is the number of Tatoeba sentences (CC BY 2.0 FR, pinned in
sources.lock) whose index line resolves to the word, which build_sentences.py folds into entry.rank as
`base_rank(common, jlpt) - count` (build_dictionary.RANK_FREQ_SPAN wide). This script recovers the count
from the rank instead of reading the corpus a second time, so it needs no download.

Order: Tatoeba count (desc), then JMdict common flag, then easier JLPT level, then rank and id, so ties are
stable across rebuilds. Function words (particles, auxiliaries, copulas and kana-only grammar expressions) are
left out: they are taught by the grammar path, and coverage never counts them (coverage/TextProfiler.kt
applies the same rule). Words with no Tatoeba hit are only used to fill up to the target size when they are
JMdict-common.

The Japanese Wikipedia dump BRIEF_V2 §7 mentions is not used: it is several GB and needs a morphological pass
this pipeline can't run on the build machine (D-152). Re-run to rebuild; the counts go to pack_meta.

Run: uv run python packs/build_decks.py
"""

from __future__ import annotations

import json

from build_dictionary import RANK_FREQ_SPAN, RANK_NO_JLPT
from common import finish_pack, log, open_pack, reset_tables, set_meta

TARGET = 10_000
DECKS = {"core2k": 2_000, "core6k": 6_000, "core10k": 10_000}
# Parts of speech that make a word a "function word" (kept in sync with FUNCTION_POS in
# shared/src/commonMain/kotlin/app/tsumugi/coverage/TextProfile.kt).
FUNCTION_POS = {"prt", "aux", "aux-v", "aux-adj", "cop"}
BUCKET = RANK_NO_JLPT  # the rank buckets are multiples of 1,000,000


def is_function_word(pos: list[str], has_kanji: bool) -> bool:
    """Same rule as TextProfile.isFunctionWord: particle/auxiliary/copula-only senses, or a kana-only expression."""
    if not pos:
        return False
    if set(pos) & FUNCTION_POS and set(pos) <= FUNCTION_POS | {"exp", "conj"}:
        return True
    return set(pos) == {"exp"} and not has_kanji


def tatoeba_count(rank: int) -> int:
    rest = rank % BUCKET
    return max(0, RANK_FREQ_SPAN - rest)


def main() -> None:
    db = open_pack()
    reset_tables(db, {"freq_word"})
    rows = db.execute(
        """SELECT e.id, e.rank, e.is_common, e.jlpt,
               coalesce((SELECT pos FROM sense WHERE entry_id = e.id AND ord = 0), ''),
               EXISTS (SELECT 1 FROM entry_kanji WHERE entry_id = e.id)
           FROM entry e"""
    ).fetchall()
    candidates = []
    skipped_function = 0
    for eid, rank, common, jlpt, pos, has_kanji in rows:
        count = tatoeba_count(rank)
        if count == 0 and not common:
            continue
        if is_function_word(json.loads(pos) if pos else [], bool(has_kanji)):
            skipped_function += 1
            continue
        candidates.append((-count, -common, -(jlpt or 0), rank, eid, count))
    candidates.sort()
    chosen = candidates[:TARGET]
    db.executemany(
        "INSERT INTO freq_word VALUES (?,?,?)", ((i + 1, c[4], c[5]) for i, c in enumerate(chosen))
    )
    with_hits = sum(1 for c in chosen if c[5] > 0)
    set_meta(
        db,
        freq_words=str(len(chosen)),
        freq_words_with_tatoeba=str(with_hits),
        freq_decks=json.dumps({k: min(v, len(chosen)) for k, v in DECKS.items()}),
    )
    finish_pack(db)
    log(
        f"  freq_word: {len(chosen)} words ({with_hits} with Tatoeba hits, lowest count {chosen[-1][5] if chosen else 0}); "
        f"{skipped_function} function words left out"
    )


if __name__ == "__main__":
    main()
