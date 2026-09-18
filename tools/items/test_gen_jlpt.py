"""Tests for items/gen_jlpt.py. Plain asserts: run with `uv run python items/test_gen_jlpt.py` (pytest also
collects the test_* functions). Needs no content packs; the tokenizer test runs only when
content/packs/tokenizer.sqlite exists."""

from __future__ import annotations

import copy
import http.server
import json
import sys
import threading
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import gen_jlpt as G
from lattice import Token

BLUEPRINT = G.load_blueprint()
BANK_DIR = G.HERE / "bank"


def good_bank() -> dict:
    return {
        "bank": "test", "title": "t", "license": "CC BY-SA 4.0", "attribution": "x",
        "passages": [
            {"id": "p1", "exam": "JLPT", "level": "N3", "textType": "essay", "title": "T",
             "body": "今日は［1］雨だった。", "source": "llm", "verified": False},
            {"id": "p2", "exam": "JLPT", "level": "N3", "textType": "dialogue", "title": "T", "body": "",
             "script": [{"speaker": "女", "voice": "female", "text": "こんにちは。"}], "source": "llm",
             "verified": False},
        ],
        "items": [
            {"id": "i1", "exam": "JLPT", "level": "N3", "type": "text_grammar", "passageId": "p1", "stem": "［1］",
             "choices": ["a", "b", "c", "d"], "answer": 0, "explanation": "e", "refs": [], "source": "llm",
             "verified": False},
            {"id": "i2", "exam": "JLPT", "level": "N3", "type": "task", "passageId": "p2",
             "stem": "何をしますか。", "choices": ["a", "b", "c", "d"], "answer": 3, "explanation": "e",
             "source": "llm", "verified": False},
            {"id": "i3", "exam": "JLPT", "level": "N3", "type": "quick_response", "stem": "最もよい返事を選んでください。",
             "script": [{"speaker": "男", "voice": "male", "text": "お先に失礼します。"}],
             "choices": ["x", "y", "z"], "answer": 1, "explanation": "e", "source": "llm", "verified": False},
            {"id": "i4", "exam": "JLPT", "level": "N3", "type": "kanji_reading", "stem": "<u>学校</u>へ行く。",
             "choices": ["がっこう", "がくこう", "かっこう", "がっこ"], "answer": 0, "explanation": "e",
             "refs": ["v:1206600", "t:1"], "source": "generated", "verified": False},
            {"id": "i5", "exam": "JLPT", "level": "N3", "type": "sentence_assembly",
             "stem": "彼は　＿＿＿　＿＿＿　＿★＿　＿＿＿。", "choices": ["a", "b", "c", "d"], "answer": 2,
             "explanation": "e", "refs": ["g:n3-x"], "source": "generated", "verified": False},
        ],
    }


def errors_of(bank: dict) -> list[str]:
    return G.validate_banks([("t", bank)], BLUEPRINT).errors


def test_blueprint() -> None:
    assert set(BLUEPRINT) == set(G.LEVELS)
    assert "orthography" not in BLUEPRINT["N1"] and "orthography" in BLUEPRINT["N2"]
    assert "word_formation" in BLUEPRINT["N2"] and "usage" not in BLUEPRINT["N5"]
    assert BLUEPRINT["N5"]["kanji_reading"] == 12


def test_valid_bank_passes() -> None:
    assert errors_of(good_bank()) == []


def test_validator_catches_problems() -> None:
    cases = {
        "choices": lambda b: b["items"][1]["choices"].pop(),
        "3 choices": lambda b: b["items"][2]["choices"].append("w"),
        "out of range": lambda b: b["items"][1].__setitem__("answer", 4),
        "not distinct": lambda b: b["items"][1].__setitem__("choices", ["a", "a", "c", "d"]),
        "NFC": lambda b: b["items"][1].__setitem__("stem", unicodedata.normalize("NFD", "がくせい")),
        "blueprint": lambda b: b["items"][1].__setitem__("type", "word_formation"),
        "duplicate stem": lambda b: b["items"].append({**b["items"][3], "id": "i9"}),
        "duplicate item id": lambda b: b["items"].append({**b["items"][3], "id": "i1", "stem": "<u>別</u>"}),
        "not found": lambda b: b["items"][1].__setitem__("passageId", "nope"),
        "verified=false": lambda b: b["items"][1].__setitem__("verified", True),
        "［n］ marker": lambda b: b["items"][0].__setitem__("stem", "［2］"),
        "slots": lambda b: b["items"][4].__setitem__("stem", "彼は＿＿＿です。"),
        "<u>": lambda b: b["items"][3].__setitem__("stem", "学校へ行く。"),
        "voice": lambda b: b["passages"][1]["script"][0].__setitem__("voice", "robot"),
        "level": lambda b: b["items"][1].__setitem__("level", "N6"),
    }
    for needle, mutate in cases.items():
        bank = copy.deepcopy(good_bank())
        mutate(bank)
        errs = errors_of(bank)
        assert any(needle in e for e in errs), (needle, errs)


def test_duplicate_assembly_stems_differ_by_chunks() -> None:
    bank = good_bank()
    bank["items"].append({**bank["items"][4], "id": "i6", "choices": ["e", "f", "g", "h"]})
    assert errors_of(bank) == []


def test_coverage_table() -> None:
    rep = G.validate_banks([("t", good_bank())], BLUEPRINT)
    table, short = G.coverage_table(rep, BLUEPRINT)
    assert "kanji_reading" in table
    assert "N3 task (課題理解): 1/6" in short


def test_reading_variants() -> None:
    v = G.reading_variants(["しょう", "ねん"])
    assert "しょねん" in v and "じょうねん" in v and "しょうねん" not in v
    assert "がくこう" in G.reading_variants(["がっ", "こう"])  # っ restored
    assert "がっこう" in G.reading_variants(["がく", "こう"])  # っ at a segment boundary
    assert all(not x.startswith("っ") for x in G.reading_variants(["っ"]))
    assert "はん" in G.vowel_shifts("ほん") and "ほん" not in G.vowel_shifts("ほん")


def test_split_tail() -> None:
    assert G.split_tail("起きます", "おきます") == ("", "起", "お", "きます")
    assert G.split_tail("お茶", "おちゃ") == ("お", "茶", "ちゃ", "")
    assert G.split_tail("先生", "せんせい") == ("", "先生", "せんせい", "")
    assert G.split_tail("ください", "ください") is None


def test_answer_slots_balanced() -> None:
    slots = [G.answer_slot("N3/context", i) for i in range(40)]
    assert all(slots.count(k) == 10 for k in range(4))
    assert slots == [G.answer_slot("N3/context", i) for i in range(40)]  # deterministic
    choices, ans = G.place("正", ["a", "b", "c"], "k", 2)
    assert choices[ans] == "正" and ans == 2 and sorted(choices) == sorted(["正", "a", "b", "c"])


def tok(surface, pos1, pos2="", base=None, conj_form="", start=0):
    return Token(surface, start, start + len(surface), tuple(x for x in (pos1, pos2) if x), "", conj_form,
                 base or surface, "")


def test_bunsetsu() -> None:
    toks = [tok("彼", "名詞", "代名詞"), tok("は", "助詞", "係助詞"), tok("勉強", "名詞", "サ変接続"),
            tok("する", "動詞", "自立"), tok("こと", "名詞", "非自立"), tok("に", "助詞", "格助詞"),
            tok("し", "動詞", "自立", "する"), tok("た", "助動詞"), tok("。", "記号", "句点")]
    chunks = ["".join(t.surface for t in c) for c in G.bunsetsu(toks)]
    assert chunks == ["彼は", "勉強する", "ことに", "した。"], chunks
    assert G.is_free_chunk([tok("本", "名詞", "一般"), tok("を", "助詞", "格助詞")])
    assert G.is_free_chunk([tok("方", "名詞", "非自立"), tok("へ", "助詞", "格助詞")])
    assert G.is_free_chunk([tok("昨日", "名詞", "副詞可能")])
    assert G.is_free_chunk([tok("言いつけ", "動詞", "自立", conj_form="連用形"), tok("で", "助詞", "格助詞")])
    assert not G.is_free_chunk([tok("行か", "動詞", "自立", conj_form="未然形"), tok("ない", "助動詞"),
                                tok("と", "助詞", "接続助詞")])


def test_above_level_longest_match() -> None:
    fl = {"学校": 5, "経済": 3, "会議室": 2}
    assert G.above_level_words("<u>学校</u>で経済を学ぶ。", 5, fl) == ["経済(N3)"]
    assert G.above_level_words("会議室", 3, fl) == ["会議室(N2)"]
    assert G.above_level_words("会議室", 1, fl) == []


def test_normalize_draft_and_endpoint() -> None:
    reply = {"items": [{"stem": "彼は<u>たびたび</u>遅れる。", "choices": ["よく", "たまに", "いつも", "決して"],
                        "answer": 0, "explanation": "たびたび = often."}]}

    seen: dict = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            seen.update(path=self.path, body=body, auth=self.headers.get("Authorization"))
            out = json.dumps({"choices": [{"message": {"content": json.dumps(reply, ensure_ascii=False)}}]})
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(out.encode())

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        bank = G.draft(f"http://127.0.0.1:{server.server_port}/v1", "m", "N3", "paraphrase", 1, "k", BLUEPRINT)
    finally:
        server.shutdown()
    assert seen["path"] == "/v1/chat/completions"
    assert seen["body"]["response_format"] == {"type": "json_object"}
    assert seen["auth"] == "Bearer k"
    it = bank["items"][0]
    assert it["source"] == "llm" and it["verified"] is False and it["type"] == "paraphrase"
    assert errors_of(bank) == []


def test_committed_banks() -> None:
    """The committed JLPT banks are valid and, together, hold at least one full mock for every authored type."""
    files = sorted(BANK_DIR.glob("jlpt_*.json"))
    assert files, "no committed JLPT banks"
    banks = G.load_banks(files)
    rep = G.validate_banks(banks, BLUEPRINT)
    assert rep.errors == [], rep.errors[:10]
    for level, types in BLUEPRINT.items():
        for typ, need in types.items():
            assert rep.counts[(level, typ)] >= need, (level, typ, rep.counts[(level, typ)], need)
    for _, bank in banks:
        for it in bank["items"]:
            assert it["source"] in {"llm", "generated"}
            if it["source"] == "llm":
                assert it["verified"] is False


def test_tokenizer_when_pack_present() -> None:
    pack = G.DEFAULT_PACKS / "tokenizer.sqlite"
    if not pack.exists():
        return
    from lattice import Lattice

    toks = Lattice(pack).tokenize("手紙を書いた。")
    assert [t.surface for t in toks] == ["手紙", "を", "書い", "た", "。"]
    assert toks[2].base == "書く" and toks[2].reading == "カイ"


if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    for name, fn in tests:
        fn()
        print(f"ok  {name}")
    print(f"{len(tests)} tests passed")
