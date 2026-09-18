"""Build every Phase 1 content pack in dependency order. Run: uv run packs/build_all.py"""

import build_dictionary
import build_kanjivg
import build_sentences
from common import write_manifest

if __name__ == "__main__":
    build_dictionary.main()
    build_kanjivg.main()
    build_sentences.main()
    write_manifest()
