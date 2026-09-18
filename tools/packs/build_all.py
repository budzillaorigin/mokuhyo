"""Build every content pack in dependency order. Run: uv run python packs/build_all.py"""

import build_dictionary
import build_grammar
import build_kanji_path
import build_kanjivg
import build_sentences
from common import write_manifest

if __name__ == "__main__":
    build_dictionary.main()
    build_kanjivg.main()
    build_sentences.main()
    build_kanji_path.main()
    build_grammar.main()
    write_manifest()
