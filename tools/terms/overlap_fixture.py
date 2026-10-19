"""A tiny stand-in source corpus for tests of overlap_check.py, so they run without tools/sources/.cache (QC 2026-10-19 #6).

`with fixture_corpus() as env:` writes a SOURCES.json with one restricted (verbatim_ok = false) source and one public one,
plus their cached text, and returns the environment variables that point overlap_check.py at them. The texts are short
invented sentences around the phrases the tests check; nothing here comes from a real source.
"""
from __future__ import annotations

import json
import os
import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

RESTRICTED_EN = ("Fixture text for tests only. Planners work to attain and maintain a desired degree of air superiority over "
                 "the area by the combined action of several units, then report.")
RESTRICTED_FR = "Texte de test uniquement. Nous coopérons à l'Armée de l'air et de l'espace et avec nos partenaires."


@contextmanager
def fixture_corpus() -> Iterator[dict[str, str]]:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        cache = root / "cache"
        cache.mkdir()
        rows = [
            {"id": "fixture-restricted", "status": "acquired", "path": "fixture-restricted.pdf", "lang": ["en", "fr"],
             "verbatim_ok": False, "alignment_ok": True, "machine_extract_ok": True, "distribution": "public"},
            {"id": "fixture-public", "status": "acquired", "path": "fixture-public.pdf", "lang": ["en"],
             "verbatim_ok": True, "alignment_ok": True, "machine_extract_ok": True, "distribution": "public"},
        ]
        (root / "SOURCES.json").write_text(json.dumps({"sources": rows}), encoding="utf-8")
        import overlap_check as O  # same directory; normalizes exactly as the checker does
        (cache / "fixture-restricted.txt").write_text(O.normalize(RESTRICTED_EN + " " + RESTRICTED_FR), encoding="utf-8")
        (cache / "fixture-public.txt").write_text(O.normalize("A public fixture sentence with nothing in common."), encoding="utf-8")
        yield {**os.environ, "MOKUHYO_OVERLAP_SOURCES_JSON": str(root / "SOURCES.json"), "MOKUHYO_OVERLAP_CACHE": str(cache)}
