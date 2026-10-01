"""Offline checks for voices/manifest.py (run by tools/run_tests.py): the committed manifest validates, and a voice
with a non-redistributable license is refused."""
from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import manifest


def main() -> int:
    assert manifest.check() == 0, "voices/manifest.json validates"
    data = json.loads(manifest.MANIFEST.read_text(encoding="utf-8"))
    ids = {v["id"] for v in data["voices"]}
    assert ids == {v["id"] for v in manifest.VOICES}, "manifest.json matches the curated VOICES list (run write)"
    assert not ids & {e["id"] for e in data["excluded"]}, "no voice is both shipped and excluded"

    bad = json.loads(json.dumps(data))
    bad["voices"][0]["license"] = "CC-BY-NC-SA-4.0"
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "manifest.json"
        path.write_text(json.dumps(bad), encoding="utf-8")
        saved, manifest.MANIFEST = manifest.MANIFEST, path
        try:
            with contextlib.redirect_stderr(io.StringIO()) as err, contextlib.redirect_stdout(io.StringIO()):
                rc = manifest.check()
        finally:
            manifest.MANIFEST = saved
    assert rc != 0 and "not in the allowed set" in err.getvalue(), "non-commercial license refused"
    print("test_manifest: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
