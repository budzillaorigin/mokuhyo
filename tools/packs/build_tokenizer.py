"""Build content/packs/tokenizer.sqlite from mecab-ipadic 2.7.0 (BRIEF §5.2).

The pack feeds app.tsumugi.jp.tokenizer.LatticeTokenizer, a pure-Kotlin Viterbi analyzer, so iOS and Android
segment text identically. Only dictionary data is used (no MeCab code).

License: mecab-ipadic, © NAIST, with entries from ICOT Free Software — free use, modification and
redistribution provided the copyright notice and the NO WARRANTY section accompany it (see docs/LICENSES.md).

Run: uv run python packs/build_tokenizer.py
"""

from __future__ import annotations

import csv
import io
import struct
import tarfile

from common import PACKS, REPO, download, finish_pack, log, nfc, open_pack, reset_tables, set_meta

TOKENIZER_PACK = PACKS / "tokenizer.sqlite"
TOKENIZER_SQ = REPO / "shared/src/commonMain/sqldelightTokenizer/app/tsumugi/tokenizer/db/tokenizer.sq"
TOKENIZER_PACK_VERSION = "1"
IPADIC_URL = "https://deb.debian.org/debian/pool/main/m/mecab-ipadic/mecab-ipadic_2.7.0-20070801+main.orig.tar.gz"
TABLES = {"pos", "morpheme", "connection", "char_category", "char_range", "unknown"}


def star(value: str) -> str:
    return "" if value == "*" else value


def main() -> None:
    with tarfile.open(download(IPADIC_URL, "mecab-ipadic-2.7.0-20070801.tar.gz")) as archive:
        contents = {
            m.name.split("/")[-1]: archive.extractfile(m).read()
            for m in archive.getmembers()
            if m.isfile() and m.name.endswith((".csv", ".def"))
        }
    files = contents.keys()

    def text(name: str) -> str:
        return contents[name].decode("euc_jp")

    pos_ids: dict[str, int] = {}

    def pos_id(fields: list[str]) -> int:
        key = ",".join(fields)
        return pos_ids.setdefault(key, len(pos_ids))

    rows: dict[str, list[tuple]] = {}
    for name in sorted(n for n in files if n.endswith(".csv")):
        for r in csv.reader(io.StringIO(text(name))):
            if len(r) < 11:
                continue
            surface = nfc(r[0])
            features = r[4:10]
            base = nfc(star(r[10])) if len(r) > 10 else ""
            reading = star(r[11]) if len(r) > 11 else ""
            pron = star(r[12]) if len(r) > 12 else ""
            rows.setdefault(surface, []).append((
                int(r[1]), int(r[2]), int(r[3]), pos_id(features),
                "" if base == surface else base, reading, "" if pron == reading else pron,
            ))
    lexicon = [(s, i, *e) for s, entries in rows.items() for i, e in enumerate(entries)]
    log(f"lexicon: {len(lexicon)} entries, {len(rows)} surfaces, max length {max(map(len, rows))}")

    lines = text("matrix.def").split("\n")
    forward, backward = map(int, lines[0].split())
    costs = [0] * (forward * backward)
    for line in lines[1:]:
        parts = line.split()
        if len(parts) == 3:
            f, b, c = map(int, parts)
            costs[f * backward + b] = c
    blob = struct.pack(f"<{len(costs)}h", *costs)

    categories, ranges = [], []
    for line in text("char.def").split("\n"):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if parts[0].startswith("0x"):
            first, _, last = parts[0].partition("..")
            ranges.append((len(ranges), int(first, 16), int(last or first, 16), " ".join(parts[1:])))
        else:
            categories.append((parts[0], int(parts[1]), int(parts[2]), int(parts[3])))

    unknowns, seqs = [], {}
    for r in csv.reader(io.StringIO(text("unk.def"))):
        if len(r) < 10:
            continue
        seq = seqs[r[0]] = seqs.get(r[0], -1) + 1
        unknowns.append((r[0], seq, int(r[1]), int(r[2]), int(r[3]), pos_id(r[4:10])))

    db = open_pack(TOKENIZER_PACK, TOKENIZER_SQ)
    reset_tables(db, TABLES, TOKENIZER_SQ)
    db.executemany("INSERT INTO pos VALUES (?,?)", ((i, k) for k, i in pos_ids.items()))
    db.executemany("INSERT INTO morpheme VALUES (?,?,?,?,?,?,?,?,?)", sorted(lexicon))
    db.execute("INSERT INTO connection VALUES (0,?,?,?)", (forward, backward, blob))
    db.executemany("INSERT INTO char_category VALUES (?,?,?,?)", categories)
    db.executemany("INSERT INTO char_range VALUES (?,?,?,?)", ranges)
    db.executemany("INSERT INTO unknown VALUES (?,?,?,?,?,?)", unknowns)
    set_meta(db, pack="tokenizer", pack_version=TOKENIZER_PACK_VERSION, source="mecab-ipadic-2.7.0-20070801",
             max_surface_length=str(max(map(len, rows))))
    finish_pack(db)
    log(f"tokenizer: {forward}x{backward} matrix, {len(categories)} char categories, {len(unknowns)} unknown entries")


if __name__ == "__main__":
    main()
