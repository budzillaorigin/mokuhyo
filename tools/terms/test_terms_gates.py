"""Tests for the C-00 source and term gates: source guard, fetch_sources, validate_alignment, gate_terms flattening,
overlap_check (when the text cache exists)."""
from __future__ import annotations

import csv
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "sources"))

import fetch_sources
import gate_terms

import sources as S


def test_guard() -> None:
    limited = {"id": "x-limited", "status": "acquired", "path": "us-limited/x.pdf", "distribution": "limited",
               "verbatim_ok": False, "alignment_ok": False, "machine_extract_ok": False}
    assert S.refusal(limited, "overlap") and S.refusal(limited, "llm") and S.refusal(limited, "align")
    sneaky = dict(limited, distribution=None, machine_extract_ok=True, alignment_ok=True)  # path alone marks it limited
    assert S.refusal(sneaky, "llm")
    unclear = {"id": "u", "status": "acquired", "path": "allied/id/x.pdf", "machine_extract_ok": "unclear", "alignment_ok": True}
    assert S.refusal(unclear, "llm") and S.refusal(unclear, "parse")
    assert S.refusal(unclear, "align") is None and S.refusal(unclear, "overlap") is None
    for r in S.rows().values():  # the real manifest: every limited row is refused for everything
        if r.get("distribution") == "limited":
            assert all(S.refusal(r, p) for p in S.PURPOSES), r["id"]
    try:
        S.path(limited)
        raise AssertionError("path() must refuse a limited row")
    except S.SourceRefused:
        pass
    assert all(not S.is_limited(r) for r in S.allowed("llm"))
    assert {"us-dod-dict-2026-08", "nato-aap-06-2019"} <= {r["id"] for r in S.allowed("llm")}
    assert "de-weissbuch-2016" not in {r["id"] for r in S.allowed("llm")}
    assert "de-weissbuch-2016" in {r["id"] for r in S.allowed("align", "de")}


def test_fetch() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        origin = base / "origin.pdf"
        origin.write_bytes(b"%PDF-1.4 fixture")
        digest = hashlib.sha256(origin.read_bytes()).hexdigest()
        rows = {
            "a": {"id": "a", "status": "acquired", "path": "us/a.pdf", "url": origin.as_uri(), "sha256": digest},
            "lim": {"id": "lim", "status": "acquired", "path": "us-limited/secret.pdf", "distribution": "limited", "url": "JEL+"},
            "man": {"id": "man", "status": "acquired", "path": "us/man.pdf", "url": "JEL+ (CAC)", "sha256": "0" * 64},
        }
        assert fetch_sources.run(True, rows, base) == 1  # a and man missing
        rc = fetch_sources.run(False, rows, base)  # fetches a; man is manual
        assert (base / "us/a.pdf").read_bytes() == origin.read_bytes()
        assert rc == 1
        del rows["man"]
        assert fetch_sources.run(True, rows, base) == 0  # the limited row is never touched
        (base / "us/a.pdf").write_bytes(b"tampered")
        assert fetch_sources.run(True, rows, base) == 1


def write_csv(path: Path, rows: list[dict]) -> None:
    cols = ["seed_id", "lang", "term", "term_source_id", "term_source_page", "definition", "status", "badge", "approvedBy", "notes"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, cols)
        w.writeheader()
        for r in rows:
            w.writerow({c: r.get(c, "") for c in cols})


def validate(path: Path, seeds: Path) -> int:
    return subprocess.run([sys.executable, str(HERE / "validate_alignment.py"), str(path), "--seeds", str(seeds)],
                          capture_output=True, text=True, check=False).returncode


def test_alignment() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        seeds = Path(tmp) / "seed_terms.csv"
        seeds.write_text("id,term_en\ncuas-001,unmanned aircraft system\n", encoding="utf-8")
        good = Path(tmp) / "good.csv"
        write_csv(good, [
            {"seed_id": "cuas-001", "lang": "fr", "term": "système d'aéronef sans pilote", "term_source_id": "nato-aap-06-2019",
             "term_source_page": "137", "definition": "Un aéronef sans pilote et ses éléments.", "status": "checked", "badge": "unreviewed"},
            {"seed_id": "cuas-001", "lang": "es", "term": "sistema de aeronave no tripulada", "definition": "x", "status": "checked",
             "badge": "unconfirmed-term"},
        ])
        assert validate(good, seeds) == 0
        bad = Path(tmp) / "bad.csv"
        write_csv(bad, [
            {"seed_id": "cuas-001", "lang": "fr", "term": "t", "term_source_id": "us-jp-3-85", "term_source_page": "1",
             "definition": "d", "status": "checked", "badge": "unreviewed"},  # limited source: not alignment_ok
        ])
        assert validate(bad, seeds) == 1


def test_flatten() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        f = Path(tmp) / "ja.json"
        f.write_text(json.dumps({"lang": "ja", "id": "skip-me-because-id", "items": [
            {"text": "基地の防空について説明してください。これは長い文です。", "explanation":
             "The speaker explains the base air defense plan to the visiting officers today 防空"}]}, ensure_ascii=False), encoding="utf-8")
        items = gate_terms.flatten(f)
        langs = sorted(i["lang"] for i in items)
        assert langs == ["en", "ja"], langs  # CJK text as ja; the English sentence as en (ja part dropped as too short)
        assert all("skip-me" not in i["text"] for i in items)
        en = next(i for i in items if i["lang"] == "en")
        assert "防空" not in en["text"]


def test_overlap_gate() -> None:
    if not (S.SOURCES_DIR / ".cache/text/nato-aap-06-2019.txt").exists():
        print("skip overlap: no text cache (run overlap_check.py index)")
        return
    cache = (S.SOURCES_DIR / ".cache/text/culture-afclc-japan.txt").read_text(encoding="utf-8").split()
    copied = " ".join(cache[5000:5014])
    with tempfile.TemporaryDirectory() as tmp:
        f = Path(tmp) / "items.jsonl"
        f.write_text(json.dumps({"id": "copy", "lang": "en", "text": copied}) + "\n" +
                     json.dumps({"id": "own", "lang": "en", "text": "Greet the senior officer first and wait to be offered a seat."}),
                     encoding="utf-8")
        r = subprocess.run([sys.executable, str(HERE / "overlap_check.py"), "check", str(f), "--fields", "text"],
                           capture_output=True, text=True, check=False)
        assert r.returncode == 1 and "copy" in r.stdout and "own" not in r.stdout.split("FAIL")[-1], r.stdout




def test_verbatim() -> None:
    import verbatim as V
    hay = V.squash("coordinating authority — A commander who has the authority to require 35 Terms and Definitions "
                   "consultation between Services. (JP 1)")
    assert V.matches("A commander who has the authority to require consultation between Services.", hay)
    assert not V.matches("A commander who has the power to require consultation between Services.", hay)
    far = V.squash("A commander who has the authority to require " + "x" * 200 + " consultation between Services.")
    assert not V.matches("A commander who has the authority to require consultation between Services.", far)


def test_seed_file() -> None:
    seeds = HERE / "seed_terms.csv"
    if not seeds.exists():
        return
    r = subprocess.run([sys.executable, str(HERE / "validate_seeds.py"), str(seeds)], capture_output=True, text=True, check=False)
    assert r.returncode == 0, r.stdout[-2000:]
    with open(seeds, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) >= 250 and all(r["approvedBy"] for r in rows)


def test_write_withholds_overlap_failures() -> None:
    """A definition that still fails overlap_check is never written to term_alignment.csv (C-02 fix)."""
    import align_terms as A

    saved = (A.WORK, A.OUT, A.seeds)
    with tempfile.TemporaryDirectory() as tmp:
        A.WORK, A.OUT = Path(tmp) / "work", Path(tmp) / "out.csv"
        A.seeds = lambda: [{"id": "x-1", "term_en": "orbit"}, {"id": "x-2", "term_en": "track"}]
        try:
            conf = {"term": "t", "kind": "native", "source_id": "", "page": "", "via": "", "radio_english": False, "excerpt": ""}
            A.save("es", "confirm", {"x-1": conf, "x-2": conf})
            A.save("es", "draft", {"x-1": {"definition": "copied words"}, "x-2": {"definition": "own words"}})
            A.save("es", "check", {"x-1": {"verdict": "pass"}, "x-2": {"verdict": "pass"}})
            A.save("es", "overlap", {"failing": ["x-1"], "phrases": {"x-1": "copied words"}})
            assert "copied words" in A.overlap_feedback("es", "x-1")
            A.write(["es"])
            with open(A.OUT, encoding="utf-8") as f:
                rows = {r["seed_id"]: r for r in csv.DictReader(f)}
            assert rows["x-1"]["definition"] == "" and rows["x-1"]["status"] == "draft" and "withheld" in rows["x-1"]["notes"]
            assert rows["x-2"]["definition"] == "own words" and rows["x-2"]["status"] == "checked"
        finally:
            A.WORK, A.OUT, A.seeds = saved


def test_overlap_empty_report() -> None:
    """overlap_check writes its JSON report even when there is no text to check (pragmatics crash fix)."""
    with tempfile.TemporaryDirectory() as tmp:
        f, rep = Path(tmp) / "e.jsonl", Path(tmp) / "r.json"
        f.write_text("", encoding="utf-8")
        subprocess.run([sys.executable, str(HERE / "overlap_check.py"), "check", str(f), "--fields", "text", "--json", str(rep)], check=True,
                       capture_output=True)
        assert json.loads(rep.read_text(encoding="utf-8"))["findings"] == []


def test_allow_list_ignores_attached_function_words() -> None:
    """An allow-listed designation still matches when the flagged run carries "l'" / "da" at an end (C-03 fix)."""
    with tempfile.TemporaryDirectory() as tmp:
        f, rep, allow = Path(tmp) / "a.jsonl", Path(tmp) / "r.json", Path(tmp) / "allow.txt"
        f.write_text(json.dumps({"id": "x", "lang": "fr", "text": "Je travaille avec l'Armée de l'air et de l'espace depuis dix ans."}), encoding="utf-8")
        allow.write_text("armée de l air et de l espace\n", encoding="utf-8")
        subprocess.run([sys.executable, str(HERE / "overlap_check.py"), "check", str(f), "--fields", "text", "--json", str(rep), "--allow", str(allow)],
                       check=False, capture_output=True)
        assert not [x for x in json.loads(rep.read_text(encoding="utf-8"))["findings"] if x["verdict"] == "FAIL"]


if __name__ == "__main__":
    for t in [test_guard, test_fetch, test_alignment, test_flatten, test_overlap_gate, test_verbatim, test_seed_file,
              test_write_withholds_overlap_failures, test_overlap_empty_report,
              test_allow_list_ignores_attached_function_words]:
        t()
        print("ok", t.__name__)
