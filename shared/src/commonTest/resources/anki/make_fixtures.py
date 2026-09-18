"""Generates test fixtures for the Anki/zstd tests as a Kotlin source file (base64 constants), so the tests run
identically on every platform without reading resource files.

Built only with Python's stdlib (sqlite3, zipfile, zlib) and the `zstandard` package — independent of the
Kotlin code under test.

    uv run --with zstandard python shared/src/commonTest/resources/anki/make_fixtures.py

Writes shared/src/commonTest/kotlin/app/tsumugi/integrations/anki/AnkiFixtures.kt.
"""

import base64
import io
import json
import os
import random
import sqlite3
import tempfile
import zipfile
import zlib
from pathlib import Path

import zstandard

OUT = Path(__file__).resolve().parents[2] / "kotlin/app/tsumugi/integrations/anki/AnkiFixtures.kt"
US = "\x1f"

SCHEMA11 = """
create table col (id integer primary key, crt integer not null, mod integer not null, scm integer not null,
  ver integer not null, dty integer not null, usn integer not null, ls integer not null, conf text not null,
  models text not null, decks text not null, dconf text not null, tags text not null);
create table notes (id integer primary key, guid text not null, mid integer not null, mod integer not null,
  usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null,
  flags integer not null, data text not null);
create table cards (id integer primary key, nid integer not null, did integer not null, ord integer not null,
  mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null,
  ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null,
  odue integer not null, odid integer not null, flags integer not null, data text not null);
create table revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null,
  ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null);
create table graves (usn integer not null, oid integer not null, type integer not null);
"""

NS_FIELDS = [
    "Kanji", "Keyword", "myStory", "Onyomi", "Kunyomi", "Stroke Order", "Stroke Count", "Heisig Number",
    "Primitive", "Components", "Example Words", "Example Readings", "Example Meanings", "JLPT", "Grade",
    "Frequency", "Radical", "Radical Meaning", "Notes", "Lesson", "Audio",
]
NS_MID = 1400000000001
BASIC_MID = 1400000000002
DECK = 1500000000000
T0 = 1_700_000_000_000  # ms


def model(mid, name, fields, templates):
    return {
        "id": mid, "name": name, "type": 0, "mod": 0, "usn": -1, "sortf": 0, "did": DECK,
        "tmpls": [{"name": n, "ord": i, "qfmt": q, "afmt": a} for i, (n, q, a) in enumerate(templates)],
        "flds": [{"name": f, "ord": i} for i, f in enumerate(fields)], "css": ".card {}",
    }


def notes_and_cards():
    """(notes, cards, revlog) shared by the legacy and modern fixtures."""
    notes, cards, revlog = [], [], []
    kanji = [
        ("語", "word", "Five <b>mouths</b> telling words.<br>Say it!", "ゴ", "かた.る、かた.らう"),
        ("日", "sun", "The sun is a box with a line.", "ニチ、ジツ", "ひ、-び、-か"),
        ("本", "book", "A tree with its roots marked: the origin of books.", "ホン", "もと"),
    ]
    for i, (k, kw, story, on, kun) in enumerate(kanji):
        nid = 1600000000000 + i
        values = {f: "" for f in NS_FIELDS}
        values.update({"Kanji": k, "Keyword": kw, "myStory": story, "Onyomi": on, "Kunyomi": kun,
                       "Stroke Order": f'<img src="stroke_{k}.png">', "Stroke Count": str(i + 5)})
        notes.append((nid, f"ns{i}", NS_MID, " nihongoshark ", US.join(values[f] for f in NS_FIELDS), k))
        cid = 1700000000000 + i
        queue = -1 if k == "本" else 2
        cards.append((cid, nid, 0, 2, queue))
        for j, ease in enumerate([3, 3, 1, 3] if k == "語" else [3, 4]):
            revlog.append((T0 + i * 10_000_000 + j * 86_400_000, cid, ease))
    basics = [("猫", "cat"), ("犬", "dog")]
    for i, (front, back) in enumerate(basics):
        nid = 1600000000100 + i
        notes.append((nid, f"b{i}", BASIC_MID, "", f"{front}{US}{back}", front))
        for ord_ in (0, 1):
            cid = 1700000000100 + i * 2 + ord_
            reviewed = i == 0
            cards.append((cid, nid, ord_, 2 if reviewed else 0, 2 if reviewed else 0))
            if reviewed:
                revlog.append((T0 + 50_000_000 + ord_ * 1000, cid, 3))
                revlog.append((T0 + 50_000_000 + 86_400_000 + ord_ * 1000, cid, 2))
    return notes, cards, revlog


def fill(db, notes, cards, revlog, pad_revlog=0):
    for nid, guid, mid, tags, flds, sfld in notes:
        db.execute("insert into notes values (?,?,?,0,-1,?,?,?,0,0,'')", (nid, guid, mid, tags, flds, sfld))
    for cid, nid, ord_, type_, queue in cards:
        db.execute("insert into cards values (?,?,?,?,0,-1,?,?,0,1,2500,0,0,0,0,0,0,'')", (cid, nid, DECK, ord_, type_, queue))
    for rid, cid, ease in revlog:
        db.execute("insert into revlog values (?,?,-1,?,1,0,2500,5000,1)", (rid, cid, ease))
    # Padding rows on a card id that does not exist (orphan revlog), to reach a realistic file size.
    for k in range(pad_revlog):
        db.execute("insert into revlog values (?,?,-1,3,1,0,2500,5000,1)", (T0 * 2 + k, 999, ))


def sqlite_bytes(build) -> bytes:
    fd, path = tempfile.mkstemp(suffix=".sqlite")
    os.close(fd)
    os.remove(path)
    db = sqlite3.connect(path)
    build(db)
    db.commit()
    db.close()
    data = Path(path).read_bytes()
    os.remove(path)
    return data


def legacy_apkg():
    notes, cards, revlog = notes_and_cards()

    def build(db):
        db.executescript(SCHEMA11)
        models = {
            str(NS_MID): model(NS_MID, "NihongoShark.com: Kanji", NS_FIELDS, [("KeywordToKanji", "{{Keyword}}", "{{Kanji}}<br>{{myStory}}")]),
            str(BASIC_MID): model(BASIC_MID, "Basic (and reversed card)", ["Front", "Back"],
                                  [("Card 1", "{{Front}}", "{{Back}}"), ("Card 2", "{{Back}}", "{{Front}}")]),
        }
        decks = {str(DECK): {"id": DECK, "name": "NihongoShark"}}
        db.execute("insert into col values (1,0,0,0,11,0,0,0,'{}',?,?,'{}','{}')", (json.dumps(models), json.dumps(decks)))
        fill(db, notes, cards, revlog, pad_revlog=2500)

    collection = sqlite_bytes(build)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("collection.anki2", collection)
        z.writestr("media", json.dumps({"0": "stroke_語.png", "1": "stroke_日.png"}))
        z.writestr("0", b"\x89PNG fake stroke image 1" * 3)
        z.writestr("1", b"\x89PNG fake stroke image 2" * 3)
    return buf.getvalue(), collection


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def pb_bytes(field, data: bytes):
    return varint(field << 3 | 2) + varint(len(data)) + data


def pb_varint(field, v):
    return varint(field << 3) + varint(v)


def modern_apkg():
    notes, cards, revlog = notes_and_cards()
    notes = [n for n in notes if n[2] == BASIC_MID]
    note_ids = {n[0] for n in notes}
    cards = [c for c in cards if c[1] in note_ids]
    card_ids = {c[0] for c in cards}
    revlog = [r for r in revlog if r[1] in card_ids]

    def build(db):
        db.executescript(SCHEMA11.replace("create table col", "create table col_unused"))
        db.executescript("""
            create table col (id integer primary key, crt integer, mod integer, scm integer, ver integer, dty integer,
              usn integer, ls integer, conf text, models text, decks text, dconf text, tags text);
            create table notetypes (id integer primary key, name text, mtime_secs integer, usn integer, config blob);
            create table fields (ntid integer, ord integer, name text, config blob);
            create table templates (ntid integer, ord integer, name text, mtime_secs integer, usn integer, config blob);
            create table decks (id integer primary key, name text, mtime_secs integer, usn integer, common blob, kind blob);
        """)
        db.execute("insert into col values (1,0,0,0,18,0,0,0,'','','','','')")
        db.execute("insert into notetypes values (?,?,0,0,?)",
                   (BASIC_MID, "Basic (and reversed card)", pb_varint(1, 0) + pb_bytes(3, b".card { color: black; }")))
        for i, f in enumerate(["Front", "Back"]):
            db.execute("insert into fields values (?,?,?,x'')", (BASIC_MID, i, f))
        for i, (n, q, a) in enumerate([("Card 1", "{{Front}}", "{{Back}}"), ("Card 2", "{{Back}}", "{{Front}}")]):
            db.execute("insert into templates values (?,?,?,0,0,?)", (BASIC_MID, i, n, pb_bytes(1, q.encode()) + pb_bytes(2, a.encode())))
        db.execute("insert into decks values (?,?,0,0,x'',x'')", (DECK, "Japanese" + US + "Animals"))
        fill(db, notes, cards, revlog)

    collection = sqlite_bytes(build)
    cctx = zstandard.ZstdCompressor(level=3)
    audio = bytes(range(256)) * 40
    media = pb_bytes(1, pb_bytes(1, b"neko.mp3") + pb_varint(2, len(audio)) + pb_bytes(3, b"\x00" * 20))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        z.writestr("collection.anki2", b"dummy: please update Anki")
        z.writestr("collection.anki21b", cctx.compress(collection))
        z.writestr("media", cctx.compress(media))
        z.writestr("meta", pb_varint(1, 3))
        z.writestr("0", cctx.compress(audio))
    return buf.getvalue()


def zstd_vectors(sqlite_file: bytes):
    rng = random.Random(42)
    words = ["kanji", "漢字", "reading", "よみ", "meaning", "意味", "review", "復習", "the", "a", "of", "猫", "犬", "日本語", "study", "勉強"]
    multiblock = " ".join(rng.choice(words) for _ in range(30_000)).encode()
    text = ("吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所で"
            "ニャーニャー泣いていた事だけは記憶している。 The quick brown fox jumps over the lazy dog. " * 120).encode()
    randoms = bytes(rng.randrange(256) for _ in range(3000))
    rle = b"\x00" * 150_000 + b"abcabcabc" * 1000
    vectors = {
        "hello": zstandard.ZstdCompressor(level=3).compress(b"Hello, zstd! Hello, zstd! Hello!"),
        "text19": zstandard.ZstdCompressor(level=19).compress(text),
        "multiblock": zstandard.ZstdCompressor(level=3).compress(multiblock),
        "multiblockFast": zstandard.ZstdCompressor(level=-5).compress(multiblock),
        "random": zstandard.ZstdCompressor(level=3).compress(randoms),
        "rle": zstandard.ZstdCompressor(level=1).compress(rle),
        "noSizeChecksum": zstandard.ZstdCompressor(level=5, write_content_size=False, write_checksum=True).compress(text),
        "sqlite": zstandard.ZstdCompressor(level=3).compress(sqlite_file),
    }
    originals = {"hello": b"Hello, zstd! Hello, zstd! Hello!", "text19": text, "multiblock": multiblock,
                 "multiblockFast": multiblock, "random": randoms, "rle": rle, "noSizeChecksum": text, "sqlite": sqlite_file}
    skippable = b"\x50\x2a\x4d\x18" + (5).to_bytes(4, "little") + b"skip!"
    vectors["multiframe"] = vectors["hello"] + skippable + vectors["text19"]
    originals["multiframe"] = originals["hello"] + text
    return {k: (vectors[k], len(originals[k]), zlib.crc32(originals[k])) for k in vectors}


def kotlin_string(data: bytes) -> str:
    b64 = base64.b64encode(data).decode()
    chunks = [b64[i:i + 4000] for i in range(0, len(b64), 4000)]
    return "listOf(\n" + "".join(f'        "{c}",\n' for c in chunks) + "    ).joinToString(\"\")"


def main():
    legacy, legacy_collection = legacy_apkg()
    modern = modern_apkg()
    vectors = zstd_vectors(legacy_collection)
    lines = [
        "// GENERATED by shared/src/commonTest/resources/anki/make_fixtures.py - do not edit.",
        "// Test data only (hand-made notes, not real deck content).",
        "package app.tsumugi.integrations.anki",
        "",
        "import kotlin.io.encoding.Base64",
        "import kotlin.io.encoding.ExperimentalEncodingApi",
        "",
        "@OptIn(ExperimentalEncodingApi::class)",
        "internal object AnkiFixtures {",
        "    private fun b64(s: String): ByteArray = Base64.decode(s)",
        "",
        "    /** Legacy collection.anki2 package: 3 NihongoShark kanji notes + 2 Basic-and-reversed notes, deflated zip. */",
        f"    val legacyApkg: ByteArray by lazy {{ b64({kotlin_string(legacy)}) }}",
        "",
        "    /** Modern package: zstd collection.anki21b (schema-18 tables), zstd protobuf media map, zstd media file. */",
        f"    val modernApkg: ByteArray by lazy {{ b64({kotlin_string(modern)}) }}",
        "",
        "    /** name -> (zstd bytes, decompressed size, CRC-32 of decompressed bytes). */",
        "    val zstdVectors: Map<String, Triple<ByteArray, Int, Int>> by lazy {",
        "        mapOf(",
    ]
    for name, (data, size, crc) in vectors.items():
        lines.append(f'            "{name}" to Triple(b64({kotlin_string(data)}), {size}, {crc if crc < 2**31 else crc - 2**32}),')
    lines += ["        )", "    }", "}", ""]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB); legacy apkg {len(legacy)} B, modern {len(modern)} B")
    for name, (data, size, _) in vectors.items():
        print(f"  zstd {name}: {len(data)} -> {size}")


if __name__ == "__main__":
    main()
