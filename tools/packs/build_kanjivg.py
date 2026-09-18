"""Build the stroke table of content/packs/dictionary.sqlite from KanjiVG (Ulrich Apel, CC BY-SA 3.0).

Stores each stroke's SVG path data in KanjiVG's 109x109 coordinate space, in stroke order.
Variant files (e.g. 04e00-Kaisho.svg) are skipped; only the standard form is kept.

Run: uv run packs/build_kanjivg.py
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
import zipfile

from common import finish_pack, log, open_pack, reset_tables, set_meta, source, source_entry

KVG = "{http://kanjivg.tagaini.net}"
FILE_RE = re.compile(r"(?:^|/)([0-9a-f]{5})\.svg$")
STROKE_ID_RE = re.compile(r"-s(\d+)$")


def main() -> None:
    version = re.search(r"kanjivg-(\d+)-main", source_entry("kanjivg")["file"]).group(1)
    db = open_pack()
    reset_tables(db, {"stroke"})

    rows = []
    with zipfile.ZipFile(source("kanjivg")) as zf:
        for name in zf.namelist():
            m = FILE_RE.search(name)
            if not m:
                continue
            char = chr(int(m.group(1), 16))
            root = ET.fromstring(zf.read(name))
            strokes = []
            for path in root.iter("{http://www.w3.org/2000/svg}path"):
                sid = STROKE_ID_RE.search(path.get("id", ""))
                if sid:
                    strokes.append((int(sid.group(1)), path.get("d"), path.get(f"{KVG}type") or ""))
            for ord_, d, kind in sorted(strokes):
                rows.append((char, ord_, d, kind))

    db.executemany("INSERT INTO stroke VALUES (?,?,?,?)", rows)
    set_meta(db, kanjivg_version=version)
    finish_pack(db)
    log(f"KanjiVG {version}: {len(rows)} strokes")


if __name__ == "__main__":
    main()
