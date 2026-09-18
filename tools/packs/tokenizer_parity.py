"""Write the TokenizerParityTest golden file with an independent reference analyzer (MeCab via fugashi, same
mecab-ipadic 2.7.0 dictionary). Used only at development time; nothing from MeCab ships in the app.

Run: uv run --with fugashi --with ipadic python packs/tokenizer_parity.py
Output: shared/src/androidHostTest/resources/tokenizer/parity.tsv
  one line per sentence: sentence, then one field per token: surface U+001F pos1 U+001F base form
"""

from __future__ import annotations

import bz2
import random

import fugashi
import ipadic
from common import CACHE, REPO, nfc

OUT = REPO / "shared/src/androidHostTest/resources/tokenizer/parity.tsv"
SAMPLE = 1000
SEP = "\x1f"


def main() -> None:
    with bz2.open(CACHE / "jpn_sentences.tsv.bz2", "rt", encoding="utf-8") as f:
        sentences = [
            nfc(parts[2]) for parts in (line.rstrip("\n").split("\t") for line in f)
            if len(parts) >= 3 and 5 <= len(parts[2]) <= 60
        ]
    sample = random.Random(42).sample(sentences, SAMPLE)
    tagger = fugashi.GenericTagger(ipadic.MECAB_ARGS)
    lines = []
    for s in sample:
        tokens = []
        for w in tagger(s):
            base = w.feature[6] if len(w.feature) > 6 and w.feature[6] != "*" else w.surface
            tokens.append(SEP.join((w.surface, w.feature[0], base)))
        lines.append("\t".join([s, *tokens]))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {len(lines)} sentences to {OUT}")


if __name__ == "__main__":
    main()
