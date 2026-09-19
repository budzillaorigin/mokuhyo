"""review.py over the Phase 13 content kinds (D-279): translation passages, expression clusters, poem annotations and
reading-circle summaries. Verdicts from the app are ingested into copies of the real source files, which must still
pass their builders' checks; the interactive loop walks a cluster file.

The tools project has no pytest; run with: uv run python items/test_review_linguist.py
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path[:0] = [str(HERE), str(TOOLS / "packs"), str(TOOLS / "packs" / "literature"), str(TOOLS / "packs" / "readers")]

import build_thesaurus as bth
import build_translation as btr
import review

FIXTURES = [
    "packs/translation/passages/news-legal.json",
    "packs/translation/passages/literary-dialogue.json",
    "packs/thesaurus/clusters/emotions.json",
    "packs/literature/poems/a.json",
    "packs/literature/circle.json",
]


class Out(StringIO):
    def reconfigure(self, **_kwargs) -> None:
        pass


def make_tree(tmp: Path) -> Path:
    tools = tmp / "tools"
    for rel in FIXTURES:
        (tools / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(TOOLS / rel, tools / rel)
    return tools


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def verdicts(path: Path, *entries: dict) -> Path:
    path.write_text(json.dumps({
        "format": "tsumugi-review-verdicts", "version": 1, "reviewer": "owner", "exportedAt": "2026-09-18T10:00:00Z",
        "verdicts": [{"notes": "", "edits": {}, "decidedAt": "2026-09-18T09:00:00Z", **e} for e in entries],
    }, ensure_ascii=False), encoding="utf-8")
    return path


def test_ingest_phase13_kinds() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        tools = make_tree(Path(tmp))
        news = read(tools / FIXTURES[0])["passages"]
        lit = read(tools / FIXTURES[1])["passages"]
        cluster = read(tools / FIXTURES[2])["clusters"][0]
        poem = read(tools / FIXTURES[3])["poems"][0]
        circle = read(tools / FIXTURES[4])["texts"]
        report = review.ingest(verdicts(
            Path(tmp) / "v.json",
            {"kind": "translation_passage", "id": news[0]["id"], "verdict": "edit",
             "edits": {"reference": news[0]["reference"] + " (edited)", "origin": "x"}},
            {"kind": "translation_passage", "id": lit[0]["id"], "verdict": "reject", "notes": "too literal"},
            {"kind": "expression_cluster", "id": cluster["id"], "verdict": "edit", "edits": {"description": "Edited description."}},
            {"kind": "poem_annotation", "id": poem["id"], "verdict": "edit", "edits": {"gloss": "An edited gloss."}},
            {"kind": "circle_text", "id": circle[0]["id"], "verdict": "accept"},
            {"kind": "poem_annotation", "id": "no-such-poem", "verdict": "accept"},
        ), root=tools)
        assert any("fields not applied: origin" in r for r in report), report
        assert "poem_annotation no-such-poem: not found in the sources; skipped" in report, report
        assert sum(1 for r in report if r.startswith("re-validate: ")) == 3, report

        p = read(tools / FIXTURES[0])["passages"][0]
        assert p["source"] == "verified" and p["verified"] is True and p["reference"].endswith("(edited)")
        assert p["reviewed"] == {"by": "owner", "on": "2026-09-18"}
        assert not btr.check_passage(p, None, None), btr.check_passage(p, None, None)
        r = read(tools / FIXTURES[1])["passages"][0]
        assert r["source"] == "llm" and r["rejected"]["notes"] == "too literal"
        assert not btr.check_passage(r, None, None)

        c = read(tools / FIXTURES[2])["clusters"][0]
        assert c["source"] == "verified" and c["verified"] is True and c["description"] == "Edited description."
        assert not bth.check_cluster(c), bth.check_cluster(c)

        po = read(tools / FIXTURES[3])["poems"][0]
        assert po["source"] == "verified" and po["verified"] is True and po["gloss"] == "An edited gloss."
        ci = read(tools / FIXTURES[4])["texts"]
        assert ci[0]["source"] == "verified" and ci[0]["verified"] is True and ci[1]["source"] == "llm"

        # Nothing pending for what was accepted; the rejected passage stays pending.
        k = review.KINDS["translation_passage"]
        pending = [eid for f in k.files(tools) for doc in [read(f)] for eid, h, i in k.entries(f, doc) if k.pending(doc, h[i])]
        assert news[0]["id"] not in pending and lit[0]["id"] in pending


def test_interactive_cluster_review_and_kind_detection() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        tools = make_tree(Path(tmp))
        f = tools / FIXTURES[2]
        assert review.kinds_for(f, read(f)) == ["expression_cluster"]
        assert review.kinds_for(tools / FIXTURES[0], read(tools / FIXTURES[0])) == ["translation_passage"]
        assert review.kinds_for(tools / FIXTURES[3], read(tools / FIXTURES[3])) == ["poem_annotation"]
        assert review.kinds_for(tools / FIXTURES[4], read(tools / FIXTURES[4])) == ["circle_text"]
        answers = iter(["a", "s", "q"])
        with redirect_stdout(Out()):
            review.review_file(f, "tester", ask=lambda _p: next(answers))
        cs = read(f)["clusters"]
        assert cs[0]["source"] == "verified" and cs[0]["reviewed"]["by"] == "tester"
        assert cs[1]["source"] == "llm"
        assert f.read_text(encoding="utf-8").startswith('{\n "source"'), "the file keeps its one-space indent"


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    tests = [v for k, v in dict(globals()).items() if k.startswith("test_")]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} passed")
