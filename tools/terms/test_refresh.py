"""N-09 gate: refresh.py on two fixture editions produces the expected diff (changed, deprecated, candidates)."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import doctrine as D
import refresh

OLD = """                              TERMS AND DEFINITIONS
alpha drone — A small drone used for testing. (JP 3-01)

base defense — The local measures taken to protect a base. Also called BD. (JP 3-10)

gamma watch — A lookout post on the perimeter. (JP 3-10)
\f"""
NEW = """                              TERMS AND DEFINITIONS
alpha drone — A small unmanned aircraft used for testing and training. (JP 3-01)

base defense — The local measures taken to protect a base. Also called BD. (JP 3-10)

counter-drone cell — A team that detects and defeats hostile drones near a base. (JP 3-01)
\f"""


def terms(old_entries: list[D.Entry]) -> list[dict]:
    return [{"id": f"t-{i}", "term_en": e.term, "definition_en": refresh.clean(e.definition),
             "definition_cite": {"source_id": "fixture-old", "page": e.page_label}} for i, e in enumerate(old_entries)]


def test_fixture_editions() -> None:
    old = D.dod_dictionary("fixture-old", OLD.split("\f"))
    new = D.dod_dictionary("fixture-new", NEW.split("\f"))
    assert [e.term for e in old] == ["alpha drone", "base defense", "gamma watch"], [e.term for e in old]
    out = refresh.diff(terms(old), "fixture-old", new)
    assert [c["term_en"] for c in out["changed"]] == ["alpha drone"], out["changed"]
    assert "unmanned aircraft" in out["changed"][0]["new"]
    assert [d["term_en"] for d in out["deprecated"]] == ["gamma watch"]
    assert [c["term_en"] for c in out["candidates"]] == ["counter-drone cell"]


def test_cli() -> None:
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        (tmp / "new.txt").write_text(NEW, encoding="utf-8")
        old = D.dod_dictionary("fixture-old", OLD.split("\f"))
        (tmp / "terms.json").write_text(json.dumps({"terms": terms(old)}), encoding="utf-8")
        r = subprocess.run([sys.executable, str(HERE / "refresh.py"), "--new-pages", str(tmp / "new.txt"), "--old-id", "fixture-old",
                            "--terms", str(tmp / "terms.json"), "--out", str(tmp / "out.json")], capture_output=True, text=True, check=False)
        assert r.returncode == 0, r.stderr
        out = json.loads((tmp / "out.json").read_text(encoding="utf-8"))
        assert (len(out["changed"]), len(out["deprecated"]), len(out["candidates"])) == (1, 1, 1), out


if __name__ == "__main__":
    for t in (test_fixture_editions, test_cli):
        t()
        print("ok", t.__name__)
