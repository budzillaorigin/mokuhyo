"""Build every content pack in dependency order.

    uv run python packs/build_all.py                        # build from the sources pinned in packs/sources.lock
    uv run python packs/build_all.py --update-sources       # re-pin every source to its newest upstream, then build
    uv run python packs/build_all.py --update-sources jmdict-eng kanjidic2-en   # re-pin only these
    uv run python packs/build_all.py --verify-sources       # download + hash-check the lock, build nothing
"""

import argparse
import sys
from pathlib import Path

import build_collocations
import build_decks
import build_dictionary
import build_exam
import build_grammar
import build_kanji_path
import build_kanjivg
import build_onomatopoeia
import build_phonetics
import build_practice
import build_sentences
import build_thesaurus
import build_tokenizer
import build_tracks
import build_translation
from common import PACKS, load_lock, log, source, source_entry, update_sources, write_manifest

sys.path.insert(0, str(Path(__file__).resolve().parent / "readers"))
sys.path.insert(0, str(Path(__file__).resolve().parent / "literature"))
import build_literature
import build_readers


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--update-sources", nargs="*", metavar="NAME",
                    help="re-resolve sources (all, or the named ones) and rewrite sources.lock before building")
    ap.add_argument("--verify-sources", action="store_true", help="only fetch and verify every locked source")
    args = ap.parse_args()

    if args.update_sources is not None:
        update_sources(args.update_sources or None)
    if args.verify_sources:
        for name in load_lock()["sources"]:
            source(name)
            log(f"{name}: ok ({source_entry(name)['release']})")
        return

    build_dictionary.main()
    build_kanjivg.main()
    build_sentences.main()
    build_decks.main()
    build_onomatopoeia.main([])  # needs the dictionary and its Tatoeba sentences
    build_thesaurus.build()  # expression clusters (dictionary pack tables), after the Tatoeba sentences
    build_phonetics.main([])  # needs the dictionary's kanji table and KanjiVG
    build_kanji_path.main()
    build_grammar.main()
    build_practice.main()
    build_tracks.main([])
    build_tokenizer.main()
    build_collocations.build()  # PMI over tokenized Tatoeba; needs the tokenizer pack
    build_exam.main()
    build_readers.build(PACKS, PACKS)  # graded readers; needs the dictionary and tokenizer packs above
    build_translation.build()  # linguist.sqlite: translation passages (+ Tatoeba pairs from the dictionary pack)
    build_literature.build()  # linguist.sqlite: poems and reading-circle texts (Aozora, pinned)
    write_manifest()


if __name__ == "__main__":
    main()
