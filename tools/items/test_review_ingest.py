"""Tests for review.py: verdicts flip verified (with reviewer), rejects remove passages and their items, dry runs
change nothing, foreign files are refused. Run: uv run python items/test_review_ingest.py"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import review


def setup(tmp: Path) -> None:
    review.BANK = tmp / "bank"
    review.OPI = tmp / "opi"
    review.LOG = tmp / "log.jsonl"
    (review.BANK / "es").mkdir(parents=True)
    review.OPI.mkdir()
    bank = {"bank": "es-reading-core", "language": "es", "title": "t", "license": "CC BY-SA 4.0", "attribution": "a",
            "passages": [{"id": f"es-dr-2-news-00{i}", "exam": "DLPT_READING", "level": "2", "textType": "news", "title": "T", "body": "B",
                          "source": "llm", "verified": False} for i in (1, 2)],
            "items": [{"id": f"es-dr-2-news-00{i}-q1", "passageId": f"es-dr-2-news-00{i}", "exam": "DLPT_READING", "level": "2", "type": "detail",
                       "stem": "S?", "choices": ["a", "b", "c", "d"], "answer": 0, "source": "llm", "verified": False} for i in (1, 2)]}
    (review.BANK / "es" / "reading.json").write_text(json.dumps(bank), encoding="utf-8")
    opi = {"language": "es", "profile": {}, "questions": [{"id": "es-q-1", "prompt": "¿Hola?", "verified": False}],
           "rolePlays": [], "topics": [{"id": "es-topic-1", "title": "x", "verified": False}]}
    (review.OPI / "es.json").write_text(json.dumps(opi), encoding="utf-8")


def export(tmp: Path) -> Path:
    f = tmp / "export.json"
    f.write_text(json.dumps({"format": "mokuhyo-review/1", "reviewer": "Tester", "verdicts": [
        {"language": "es", "kind": "exam", "id": "es-dr-2-news-001", "verdict": "accept"},
        {"language": "es", "kind": "exam", "id": "es-dr-2-news-002", "verdict": "reject", "note": "ambiguous"},
        {"language": "es", "kind": "opi", "id": "es-q-1", "verdict": "accept", "edits": {"prompt": "¿Hola, cómo está?"}},
        {"language": "es", "kind": "opi", "id": "es-topic-1", "verdict": "reject"},
    ]}), encoding="utf-8")
    return f


def test_ingest_applies_every_verdict_kind():
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        setup(tmp)
        assert review.main(["ingest", str(export(tmp))]) == 0
        bank = json.loads((review.BANK / "es" / "reading.json").read_text(encoding="utf-8"))
        assert [p["id"] for p in bank["passages"]] == ["es-dr-2-news-001"]
        assert bank["passages"][0]["verified"] and bank["passages"][0]["reviewedBy"] == "Tester"
        assert [i["id"] for i in bank["items"]] == ["es-dr-2-news-001-q1"] and bank["items"][0]["verified"]
        opi = json.loads((review.OPI / "es.json").read_text(encoding="utf-8"))
        assert opi["questions"][0]["prompt"] == "¿Hola, cómo está?" and opi["questions"][0]["verified"]
        assert opi["topics"] == []
        assert len(review.LOG.read_text(encoding="utf-8").splitlines()) == 4


def test_dry_run_changes_nothing_and_foreign_files_are_refused():
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        setup(tmp)
        before = (review.BANK / "es" / "reading.json").read_text(encoding="utf-8")
        assert review.main(["ingest", str(export(tmp)), "--dry-run"]) == 0
        review.DRY_RUN = False
        assert (review.BANK / "es" / "reading.json").read_text(encoding="utf-8") == before
        bad = tmp / "bad.json"
        bad.write_text(json.dumps({"format": "something-else"}), encoding="utf-8")
        assert review.main(["ingest", str(bad)]) == 1


def test_suggestions_queue() -> None:
    """N-10: an app export is queued once (by id); malformed entries are skipped."""
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        review.QUEUE = tmp / "queue.jsonl"
        f = tmp / "s.json"
        f.write_text(json.dumps({"format": "mokuhyo-suggestions/1", "suggestions": [
            {"id": "a", "lang": "es", "type": "suggest_term", "targetKind": "term", "targetId": "", "text": "dron kamikaze"},
            {"id": "b", "lang": "es", "type": "flag", "targetKind": "passage", "targetId": "es-dr-2-news-001", "text": "key wrong"},
            {"id": "c", "lang": "es", "type": "nonsense", "targetKind": "x", "text": "?"},
        ]}), encoding="utf-8")
        assert review.main(["suggestions", str(f)]) == 0
        assert review.main(["suggestions", str(f)]) == 0
        ids = [json.loads(line)["id"] for line in review.QUEUE.read_text(encoding="utf-8").splitlines()]
        assert ids == ["a", "b"], ids


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"ok  {t.__name__}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL {t.__name__}: {e}")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


def test_phase8_kinds() -> None:
    """C-10: terms, culture cards, pragmatics entries and personas round-trip from an export into the source files."""
    import csv
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        setup(tmp)
        review.TOOLS = tmp
        review.ALIGN = tmp / "terms" / "term_alignment.csv"
        (tmp / "terms").mkdir()
        cols = ["seed_id", "lang", "term", "term_kind", "radio_english", "term_source_id", "term_source_page", "definition", "status", "badge",
                "approvedBy", "notes", "accuracy", "drafted_by", "checked_by"]
        with open(review.ALIGN, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, cols)
            w.writeheader()
            w.writerow({"seed_id": "cuas-001", "lang": "es", "term": "sistema", "definition": "d", "status": "checked", "badge": "unconfirmed-term"})
            w.writerow({"seed_id": "cuas-002", "lang": "es", "term": "aeronave", "term_source_id": "x", "term_source_page": "1", "definition": "d",
                        "status": "checked", "badge": "unreviewed"})
        for kind, (pattern, key) in review.JSON_KINDS.items():
            p = tmp / pattern.format(lang="es")
            p.parent.mkdir(parents=True, exist_ok=True)
            data = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {"lang": "es"}
            data[key] = [{"id": f"es-{kind}-1", "verified": False}, {"id": f"es-{kind}-2", "verified": False}]
            p.write_text(json.dumps(data), encoding="utf-8")
        f = tmp / "v.json"
        verdicts = [{"language": "es", "kind": "term", "id": "cuas-001", "verdict": "accept", "note": "standard in FAM usage"},
                    {"language": "es", "kind": "term", "id": "cuas-002", "verdict": "reject", "note": "wrong sense"}]
        for kind in review.JSON_KINDS:
            verdicts += [{"language": "es", "kind": kind, "id": f"es-{kind}-1", "verdict": "accept"},
                         {"language": "es", "kind": kind, "id": f"es-{kind}-2", "verdict": "reject"}]
        f.write_text(json.dumps({"format": "mokuhyo-review/1", "reviewer": "Tester", "verdicts": verdicts}), encoding="utf-8")
        assert review.main(["ingest", str(f)]) == 0
        with open(review.ALIGN, encoding="utf-8") as fh:
            rows = {r["seed_id"]: r for r in csv.DictReader(fh)}
        assert rows["cuas-001"]["status"] == "approved" and rows["cuas-001"]["approvedBy"] == "Tester" and rows["cuas-001"]["badge"] == ""
        assert "accepted by Tester" in rows["cuas-001"]["notes"]
        assert rows["cuas-002"]["status"] == "draft" and "wrong sense" in rows["cuas-002"]["notes"]
        for kind, (pattern, key) in review.JSON_KINDS.items():
            items = json.loads((tmp / pattern.format(lang="es")).read_text(encoding="utf-8"))[key]
            assert [x["id"] for x in items] == [f"es-{kind}-1"] and items[0]["verified"] is True, kind


if __name__ == "__main__":
    sys.exit(main())
