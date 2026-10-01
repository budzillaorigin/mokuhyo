"""Tests for items/gen_dlpt.py (validator, band measures, draft plumbing) and the bank flow in items/review.py.

The tools project has no pytest; run with: uv run python items/test_gen_dlpt.py
"""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import gen_dlpt as g
import review

BANDS = g.load_bands()
BANK_DIR = Path(__file__).resolve().parent / "bank"


def sample_bank() -> dict:
    body = (
        "市は十五日、台風の接近に備えて、沿岸部の住民に避難を呼びかけた。避難所は市内の小中学校など十二か所で、"
        "午後三時から開設される。市によると、今夜から明日の朝にかけて風と雨が強くなる見込みだ。"
        "市の担当者は「早めに安全な場所へ移動してほしい」と話している。また、明日の市内のバスは始発から運休する。"
        "運転再開の時間は、明日の午前十時ごろに市のホームページで知らせるという。"
        "高齢者や体の不自由な人の避難については、各地区の民生委員が手伝う。"
        "市は、避難する際には飲み水や常備薬、懐中電灯などを持って行くよう求めている。"
    )
    return {
        "bank": "dlpt-reading-test",
        "title": "Test bank",
        "license": "CC BY-SA 4.0",
        "attribution": "Tsumugi contributors",
        "passages": [
            {
                "id": "dr-2-news-001",
                "exam": "DLPT_READING",
                "level": "2",
                "textType": "news",
                "title": "Typhoon evacuation",
                "body": body,
                "source": "llm",
                "verified": False,
            }
        ],
        "items": [
            {
                "id": "dr-2-news-001-q1",
                "exam": "DLPT_READING",
                "level": "2",
                "type": "main_idea",
                "passageId": "dr-2-news-001",
                "stem": "What is the article mainly about?",
                "choices": ["An evacuation call", "A school closure", "A bus fare rise", "A new shelter"],
                "answer": 0,
                "explanation": "The city asked coastal residents to evacuate; the others are not the focus.",
                "source": "llm",
                "verified": False,
            }
        ],
    }


def run(bank: dict) -> g.Report:
    report = g.Report()
    g.validate_bank(bank, report, BANDS, set(), name="t", types=g.jlpt_types())
    return report


def has(msgs: list[str], needle: str) -> bool:
    return any(needle in m for m in msgs)


# --- measures ------------------------------------------------------------------------------------------


def test_measure_counts_non_space_chars_and_kanji():
    m = g.measure("立入禁止 \nです", [])
    assert m["chars"] == 6
    assert abs(m["kanjiDensity"] - 4 / 6) < 1e-9


def test_measure_abstract_is_longest_first_and_non_overlapping():
    lexicon = ["価値", "価値観", "前提"]
    m = g.measure("価値観の前提", lexicon)
    assert m["abstractHits"] == 2  # 価値観 once (not also 価値), 前提 once
    assert m["tokens"] == 3  # script runs: 価値観 · の · 前提
    assert abs(m["abstractRatio"] - 2 / 3) < 1e-9


def test_token_runs():
    assert len(g.TOKEN_RUN.findall("価値観の前提")) == 3
    assert len(g.TOKEN_RUN.findall("ニュースを見た。ABC123")) == 6  # ニュース/を/見/た/ABC/123


def test_bands_file_shape():
    assert set(BANDS["levels"]) == set(g.DLPT_LEVELS)
    for band in BANDS["levels"].values():
        assert band["minChars"] < band["maxChars"]
        assert band["kanjiDensity"][0] < band["kanjiDensity"][1]
        assert band["abstractRatio"][0] <= band["abstractRatio"][1]
    lex = BANDS["abstractLexicon"]
    assert 280 <= len(lex) <= 400
    assert len(set(lex)) == len(lex)
    assert all(unicodedata.normalize("NFC", w) == w for w in lex)
    # Upper levels allow longer and more abstract texts.
    maxes = [BANDS["levels"][lv]["maxChars"] for lv in g.DLPT_LEVELS]
    assert maxes == sorted(maxes)
    abstract_hi = [BANDS["levels"][lv]["abstractRatio"][1] for lv in g.DLPT_LEVELS]
    assert abstract_hi == sorted(abstract_hi)


# --- validator -----------------------------------------------------------------------------------------


def test_valid_bank_passes():
    r = run(sample_bank())
    assert r.errors == [], r.errors
    assert r.warnings == [], r.warnings


def test_duplicate_ids_across_banks():
    report = g.Report()
    seen: set[str] = set()
    g.validate_bank(sample_bank(), report, BANDS, seen, name="a", types={})
    g.validate_bank(sample_bank(), report, BANDS, seen, name="b", types={})
    assert has(report.errors, "b dr-2-news-001: duplicate id")


def test_answer_and_choices():
    bank = sample_bank()
    bank["items"][0]["answer"] = 4
    assert has(run(bank).errors, "answer must be")
    bank = sample_bank()
    bank["items"][0]["answer"] = True
    assert has(run(bank).errors, "answer must be")
    bank = sample_bank()
    bank["items"][0]["choices"] = ["a", "b", "c"]
    assert has(run(bank).errors, "expected 4 choices")
    bank = sample_bank()
    bank["items"][0]["choices"] = ["a", "b", "c", "a "]
    assert has(run(bank).errors, "not distinct")
    bank = sample_bank()
    bank["items"][0]["choices"] = ["a", "b", "", "d"]
    assert has(run(bank).errors, "non-empty strings")


def test_passage_reference_and_level_consistency():
    bank = sample_bank()
    bank["items"][0]["passageId"] = "dr-2-news-999"
    assert has(run(bank).errors, "not found")
    bank = sample_bank()
    bank["items"][0]["level"] = "3"
    assert has(run(bank).errors, "differ from its passage")
    bank = sample_bank()
    del bank["items"][0]["passageId"]
    assert has(run(bank).errors, "need a passageId")


def test_enums_and_provenance():
    bank = sample_bank()
    bank["items"][0]["type"] = "kanji_reading"
    assert has(run(bank).errors, "type must be one of")
    bank = sample_bank()
    bank["passages"][0]["level"] = "5"  # 3+ and 4 are valid since G-08
    assert has(run(bank).errors, "level must be one of")
    bank = sample_bank()
    bank["passages"][0]["verified"] = "no"
    assert has(run(bank).errors, "verified must be")
    bank = sample_bank()
    bank["items"][0]["explanation"] = ""
    assert has(run(bank).errors, "explanation")
    bank = sample_bank()
    bank["passages"][0]["verified"] = True
    assert has(run(bank).warnings, "reviewed")


def test_nfc_required():
    bank = sample_bank()
    bank["passages"][0]["title"] = unicodedata.normalize("NFD", "ガイド")
    assert has(run(bank).errors, "not NFC")


def test_listening_shape():
    bank = sample_bank()
    p = bank["passages"][0]
    for obj in [p, bank["items"][0]]:
        obj["exam"] = "DLPT_LISTENING"
    p["id"] = "dl-2-news-001"
    bank["items"][0]["id"] = "dl-2-news-001-q1"
    bank["items"][0]["passageId"] = "dl-2-news-001"
    assert has(run(bank).errors, "leave body empty")
    text = p["body"]
    p["body"] = ""
    p["script"] = [{"speaker": "A", "voice": "robot", "text": text}]
    assert has(run(bank).errors, "voice must be")
    p["script"][0]["voice"] = "female"
    r = run(bank)
    assert r.errors == [] and r.warnings == [], (r.errors, r.warnings)


def test_id_pattern_warnings():
    bank = sample_bank()
    bank["passages"][0]["textType"] = "report"
    assert has(run(bank).warnings, "id text type")
    bank = sample_bank()
    bank["items"][0]["id"] = "something-else"
    assert has(run(bank).warnings, "<passageId>-qN")


def test_band_misses_are_warnings():
    bank = sample_bank()
    bank["passages"][0]["body"] = "駅は右です。"
    r = run(bank)
    assert r.errors == []
    assert has(r.warnings, "ILR 2 band: length")
    bank = sample_bank()
    bank["passages"][0]["body"] = "あいうえお" * 80
    assert has(run(bank).warnings, "kanji density")


def test_longest_key_bias_warning():
    bank = sample_bank()
    base = bank["items"][0]
    bank["items"] = [
        {
            **base,
            "id": f"dr-2-news-001-q{n}",
            "choices": ["The correct and much longer choice", "b", "c", "d"],
        }
        for n in range(1, 11)
    ]
    r = run(bank)
    assert has(r.warnings, "longest choice in 10/10"), r.warnings
    for n, it in enumerate(bank["items"]):
        if n % 3:  # keys in 4 of 10 stay longest: 40% is at the limit, not over it
            it["choices"] = ["short key", "a much longer distractor", "c", "d"]
    assert not has(run(bank).warnings, "longest choice")


def test_cli_strict_exit_codes():
    with tempfile.TemporaryDirectory() as d:
        ok = Path(d) / "ok.json"
        ok.write_text(json.dumps(sample_bank(), ensure_ascii=False), encoding="utf-8")
        assert g.main(["validate", "--quiet", str(ok)]) == 0
        short = sample_bank()
        short["passages"][0]["body"] = "駅は右です。"
        warn = Path(d) / "warn.json"
        warn.write_text(json.dumps(short, ensure_ascii=False), encoding="utf-8")
        assert g.main(["validate", "--quiet", str(warn)]) == 0
        assert g.main(["validate", "--quiet", "--strict", str(warn)]) == 1
        bad = sample_bank()
        bad["items"][0]["answer"] = 9
        err = Path(d) / "err.json"
        err.write_text(json.dumps(bad, ensure_ascii=False), encoding="utf-8")
        assert g.main(["validate", "--quiet", str(err)]) == 1


# --- draft plumbing (no network) -----------------------------------------------------------------------


def test_draft_conversion_forces_provenance_and_ids():
    raw = {
        "textType": "News",
        "title": "Typhoon evacuation",
        "body": sample_bank()["passages"][0]["body"],
        "source": "human",
        "items": [
            {
                "type": "detail",
                "stem": "When do shelters open?",
                "choices": ["3 p.m.", "10 a.m.", "Tonight", "Tomorrow"],
                "answer": 0,
                "explanation": "The text says 午後三時.",
                "verified": True,
            }
        ],
    }
    pid = g.next_id("DLPT_READING", "2", "news", {"dr-2-news-001"})
    assert pid == "dr-2-news-002"
    passage, items = g.to_bank_entries({**raw, "textType": "news"}, "DLPT_READING", "2", pid)
    assert passage["source"] == "llm" and passage["verified"] is False
    assert items[0]["id"] == "dr-2-news-002-q1" and items[0]["passageId"] == pid
    assert items[0]["source"] == "llm" and items[0]["verified"] is False
    bank = {**sample_bank(), "passages": [passage], "items": items}
    r = run(bank)
    assert r.errors == [], r.errors
    assert g.next_id("DLPT_LISTENING", "2+", "interview", set()) == "dl-2p-interview-001"


def test_draft_schema_and_prompt():
    s = g.draft_schema("DLPT_LISTENING")
    assert "script" in s["properties"] and "body" not in s["properties"]
    assert s["properties"]["items"]["items"]["properties"]["choices"]["minItems"] == 4
    msgs = g.draft_messages("DLPT_READING", "3", "editorial", BANDS, ["Old topic"])
    assert "ILR level 3" in msgs[1]["content"] and "Old topic" in msgs[1]["content"]


def test_draft_end_to_end_with_fake_endpoint():
    """cmd_draft against a local fake OpenAI-compatible server: request shape, validation, output file."""
    import http.server
    import threading

    seen: list[dict] = []
    reply = {
        "textType": "news",
        "title": "Typhoon evacuation",
        "body": sample_bank()["passages"][0]["body"],
        "items": [
            {k: sample_bank()["items"][0][k] for k in ("type", "stem", "choices", "answer", "explanation")}
        ],
    }

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            seen.append(
                {"path": self.path, **json.loads(self.rfile.read(int(self.headers["Content-Length"])))}
            )
            content = json.dumps(reply, ensure_ascii=False)
            body = json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "drafts.json"
            endpoint = f"http://127.0.0.1:{server.server_port}/v1"
            argv = [
                "draft",
                "--endpoint",
                endpoint,
                "--model",
                "m",
                "--level",
                "2",
                "--count",
                "2",
                "--out",
                str(out),
            ]
            assert g.main(argv) == 0
            bank = json.loads(out.read_text(encoding="utf-8"))
            assert [p["id"] for p in bank["passages"]] == ["dr-2-news-001", "dr-2-news-002"]
            assert all(p["source"] == "llm" and p["verified"] is False for p in bank["passages"])
            r = g.Report()
            g.validate_bank(bank, r, BANDS, set(), name="drafts", types={})
            assert r.errors == [] and r.warnings == [], (r.errors, r.warnings)
    finally:
        server.shutdown()
    assert seen[0]["path"] == "/v1/chat/completions"
    assert seen[0]["model"] == "m" and seen[0]["response_format"]["type"] == "json_schema"
    assert "Typhoon evacuation" in seen[1]["messages"][1]["content"]  # avoids repeating topics


# --- review.py bank flow -------------------------------------------------------------------------------


def test_review_bank_units_and_accept():
    bank = sample_bank()
    bank["items"].append({**copy.deepcopy(bank["items"][0]), "id": "x-standalone"})
    del bank["items"][1]["passageId"]
    units = review.bank_units(bank)
    assert units == [(0, [0]), (None, [1])]
    review.accept_unit(bank, units[0], "tester", "2026-09-18")
    assert bank["passages"][0]["verified"] is True and bank["items"][0]["verified"] is True
    assert bank["items"][0]["reviewed"] == {"by": "tester", "on": "2026-09-18"}
    assert bank["items"][0]["source"] == "llm"
    assert review.bank_units(bank) == [(None, [1])]


# --- shipped banks -------------------------------------------------------------------------------------


def test_shipped_banks_are_clean():
    files = sorted(BANK_DIR.glob("dlpt_*.json"))
    if not files:
        return
    report = g.Report()
    seen: set[str] = set()
    for f in files:
        bank = json.loads(f.read_text(encoding="utf-8"))
        g.validate_bank(bank, report, BANDS, seen, name=f.name)
        for obj in bank["passages"] + bank["items"]:
            assert obj["source"] == "llm", obj["id"]
    assert report.errors == [], report.errors[:10]
    assert report.warnings == [], report.warnings[:10]


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print(f"ok    {name}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL  {name}: {e}")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
