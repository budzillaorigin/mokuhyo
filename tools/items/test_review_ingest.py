"""Tests for `review.py --ingest` (verdicts exported by the app's content review, BRIEF_V2 G-16, DECISIONS D-118).

The tools project has no pytest; run with: uv run python items/test_review_ingest.py
"""

from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import review


def write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def make_tree(root: Path) -> Path:
    """A miniature repo: tools/ sources plus the Kotlin kana files under shared/."""
    tools = root / "tools"
    write(tools / "packs" / "grammar" / "n5.json", {
        "level": "N5", "source": "llm", "points": [
            {"id": "n5-wa", "title": "は", "structure": "Noun + は", "meaning": "topic marker", "nuance": "Marks the topic.",
             "mistakes": ["Using が"], "examples": []},
            {"id": "n5-ga", "title": "が", "structure": "Noun + が", "meaning": "subject", "nuance": "x", "examples": []},
        ],
    })
    write(tools / "items" / "bank" / "jlpt_generated.json", {
        "bank": "jlpt_generated",
        "passages": [{"id": "p1", "title": "お知らせ", "body": "本文", "source": "llm", "verified": False}],
        "items": [
            {"id": "p1-q1", "passageId": "p1", "stem": "何ですか。", "choices": ["a", "b", "c", "d"], "answer": 1,
             "explanation": "old", "source": "llm", "verified": False},
        ],
    })
    write(tools / "packs" / "listening" / "dialogues.json", {"source": "llm", "dialogues": [{"id": "d1", "title": "駅で", "topic": "travel"}]})
    write(tools / "packs" / "speaking" / "scenarios.json", {"source": "llm", "scenarios": [{"id": "s1", "titleEn": "Shop", "titleJa": "店", "setting": "a shop"}]})
    kana = root / "shared" / "src" / "commonMain" / "kotlin" / "app" / "tsumugi" / "kana"
    kana.mkdir(parents=True)
    (kana / "KanaMnemonics.kt").write_text(
        'val hiragana = mapOf(\n    "あ" to "A \\"pin\\" through a bow.",\n    "い" to "Two drops.",\n)\n', encoding="utf-8")
    (kana / "KanaMnemonicsReviewed.kt").write_text(
        "package app.tsumugi.kana\n\ninternal val REVIEWED_KANA_MNEMONICS: Set<String> = setOf(\n)\n", encoding="utf-8")
    return tools


def verdicts(*entries: dict) -> dict:
    return {"format": "tsumugi-review-verdicts", "version": 1, "reviewer": "owner", "exportedAt": "2026-09-18T10:00:00Z",
            "verdicts": [{"notes": "", "edits": {}, "decidedAt": "2026-09-18T09:00:00Z", **e} for e in entries]}


def test_ingest_applies_every_verdict_kind() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        tools = make_tree(root)
        vfile = root / "verdicts.json"
        write(vfile, verdicts(
            {"kind": "grammar_point", "id": "n5-wa", "verdict": "edit", "edits": {"meaning": "topic (as for …)", "examples": "[]"}},
            {"kind": "exam_passage", "id": "p1", "verdict": "accept"},
            {"kind": "exam_item", "id": "p1-q1", "verdict": "edit", "edits": {"explanation": "Line 1 says b."}, "notes": "clearer"},
            {"kind": "dialogue", "id": "d1", "verdict": "reject", "notes": "unnatural"},
            {"kind": "scenario", "id": "s1", "verdict": "accept"},
            {"kind": "scenario", "id": "missing", "verdict": "accept"},
            {"kind": "kana_mnemonic", "id": "あ", "verdict": "edit", "edits": {"mnemonic": 'A "new" $one'}},
            {"kind": "kana_mnemonic", "id": "い", "verdict": "accept"},
        ))
        report = review.ingest(vfile, root=tools)

        grammar = read(tools / "packs" / "grammar" / "n5.json")["points"]
        wa = grammar[0]
        assert wa["source"] == "verified", wa
        assert wa["meaning"] == "topic (as for …)"
        assert wa["reviewed"] == {"by": "owner", "on": "2026-09-18"}
        assert "source" not in grammar[1], "untouched points stay as they were"
        assert any("fields not applied: examples" in line for line in report), report

        bank = read(tools / "items" / "bank" / "jlpt_generated.json")
        assert bank["passages"][0]["verified"] is True and bank["passages"][0]["source"] == "llm", "D-034: banks flip verified"
        item = bank["items"][0]
        assert item["verified"] is True and item["explanation"] == "Line 1 says b."
        assert item["reviewed"]["notes"] == "clearer"

        d1 = read(tools / "packs" / "listening" / "dialogues.json")["dialogues"][0]
        assert d1["rejected"] == {"by": "owner", "on": "2026-09-18", "notes": "unnatural"}
        assert "source" not in d1 or d1["source"] != "verified"
        s1 = read(tools / "packs" / "speaking" / "scenarios.json")["scenarios"][0]
        assert s1["source"] == "verified"
        assert any("missing: not found" in line for line in report), report

        kana = root / "shared" / "src" / "commonMain" / "kotlin" / "app" / "tsumugi" / "kana"
        kt = (kana / "KanaMnemonics.kt").read_text(encoding="utf-8")
        assert '"あ" to "A \\"new\\" \\$one",' in kt, kt
        assert '"い" to "Two drops.",' in kt
        reviewed = (kana / "KanaMnemonicsReviewed.kt").read_text(encoding="utf-8")
        assert '    "あ",\n    "い",\n' in reviewed, reviewed

        # Ingesting again is harmless (idempotent) and a later accept clears a rejection.
        write(vfile, verdicts({"kind": "dialogue", "id": "d1", "verdict": "accept"}))
        review.ingest(vfile, root=tools)
        d1 = read(tools / "packs" / "listening" / "dialogues.json")["dialogues"][0]
        assert d1["source"] == "verified" and "rejected" not in d1


def test_dry_run_changes_nothing_and_foreign_files_are_refused() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        tools = make_tree(root)
        before = (tools / "packs" / "grammar" / "n5.json").read_text(encoding="utf-8")
        vfile = root / "v.json"
        write(vfile, verdicts({"kind": "grammar_point", "id": "n5-wa", "verdict": "accept"}))
        report = review.ingest(vfile, root=tools, dry_run=True)
        assert report[0] == "grammar_point n5-wa: accepted", report
        assert report[1:] == ["re-validate: uv run python packs/grammar/validate.py"], report
        assert (tools / "packs" / "grammar" / "n5.json").read_text(encoding="utf-8") == before

        write(vfile, {"format": "something-else", "verdicts": []})
        try:
            review.ingest(vfile, root=tools)
        except SystemExit as e:
            assert "not a Tsumugi verdicts file" in str(e)
        else:
            raise AssertionError("a foreign file must be refused")


def test_the_app_export_shape_is_accepted() -> None:
    """The JSON ContentReviewService.exportJson writes (pretty-printed, all fields present)."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        tools = make_tree(root)
        vfile = root / "app.json"
        vfile.write_text("""{
    "format": "tsumugi-review-verdicts",
    "version": 1,
    "reviewer": "owner",
    "exportedAt": "2026-09-18T10:00:00Z",
    "verdicts": [
        {
            "kind": "grammar_point",
            "id": "n5-ga",
            "verdict": "accept",
            "notes": "",
            "edits": {},
            "decidedAt": "2026-09-17T08:00:00Z"
        }
    ]
}""", encoding="utf-8")
        review.ingest(vfile, reviewer="someone", root=tools)
        ga = read(tools / "packs" / "grammar" / "n5.json")["points"][1]
        assert ga["source"] == "verified" and ga["reviewed"] == {"by": "someone", "on": "2026-09-17"}


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    tests = [v for k, v in dict(globals()).items() if k.startswith("test_")]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} passed")
