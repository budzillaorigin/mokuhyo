"""Build every content pack in dependency order. Run: uv run python packs/build_all.py"""

import build_dictionary
import build_grammar
import build_kanji_path
import build_kanjivg
import build_practice
import build_sentences
import build_tokenizer
from common import write_manifest

if __name__ == "__main__":
    build_dictionary.main()
    build_kanjivg.main()
    build_sentences.main()
    build_kanji_path.main()
    build_grammar.main()
    build_practice.main()
    build_tokenizer.main()
    write_manifest()
