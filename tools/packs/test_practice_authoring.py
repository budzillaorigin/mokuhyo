"""Tests for the practice-pack authoring plumbing: filler markup (build_practice.strip_fillers), the author scripts'
merge (practice_authoring.merge) and endpoint drafting (practice_authoring.draft) against a fake endpoint.

The tools project has no pytest; run with: uv run python packs/test_practice_authoring.py
Drafting needs content/packs/dictionary.sqlite (JMdict resolution); that test is skipped without it.
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_practice as bp
import practice_authoring as pa


def test_fillers_are_stripped_and_located():
    text, spans = bp.strip_fillers("{えっと、}ラーメン、{あ、}うどんにする。", "t")
    assert text == "えっと、ラーメン、あ、うどんにする。"
    assert spans == [(0, 4), (9, 11)]
    assert [text[s:e] for s, e in spans] == ["えっと、", "あ、"]
    assert bp.strip_fillers("はい。", "t") == ("はい。", [])


def test_unbalanced_braces_fail():
    for bad in ("{えっと、明日", "えっと}、明日", "{{えっと}}"):
        try:
            bp.strip_fillers(bad, "t")
        except bp.BuildError:
            continue
        raise AssertionError(f"accepted {bad!r}")


def test_utf16_offsets_for_astral_characters():
    # 𠮟 is outside the BMP: two UTF-16 units, which is what Kotlin String indices count.
    assert bp.utf16_offsets("𠮟る", 1, 2) == (2, 3)


def entry(i: str, **kw) -> dict:
    return {"id": i, "title": i, **kw}


def test_merge_never_duplicates_and_keeps_reviewed_copies():
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "x.json"
        pa.merge(out, "dialogues", [("a", entry("d1")), ("a", entry("d2"))], "note")
        # A reviewer verified d1 and edited its title; a draft run left d3 only in the JSON.
        doc = json.loads(out.read_text(encoding="utf-8"))
        doc["dialogues"][0].update(source="verified", title="edited")
        doc["dialogues"].append(entry("d3", source="llm"))
        out.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
        counts = pa.merge(out, "dialogues", [("a", entry("d1")), ("a", entry("d2", title="new"))], "note")
        doc = json.loads(out.read_text(encoding="utf-8"))
        assert [d["id"] for d in doc["dialogues"]] == ["d1", "d2", "d3"]
        assert doc["dialogues"][0]["title"] == "edited" and doc["dialogues"][0]["source"] == "verified"
        assert doc["dialogues"][1]["title"] == "new"
        assert counts == {"authored": 2, "keptReviewed": 1, "keptJsonOnly": 1, "total": 3}
        # --force replaces the reviewed copy; re-running is idempotent.
        pa.merge(out, "dialogues", [("a", entry("d1")), ("a", entry("d2"))], "note", force=True)
        first = out.read_text(encoding="utf-8")
        pa.merge(out, "dialogues", [("a", entry("d1")), ("a", entry("d2"))], "note", force=True)
        assert out.read_text(encoding="utf-8") == first
        assert json.loads(first)["dialogues"][0]["title"] == "d1"


def test_same_id_in_two_sources_is_an_error():
    with tempfile.TemporaryDirectory() as tmp:
        try:
            pa.merge(Path(tmp) / "x.json", "scenarios", [("a.json", entry("s")), ("b.json", entry("s"))], "n")
        except SystemExit as e:
            assert "a.json" in str(e) and "b.json" in str(e)
            return
        raise AssertionError("duplicate id accepted")


def test_unique_ids_and_slugs():
    assert pa.slug("Renewing the lease!") == "renewing-the-lease"
    assert pa.unique_id("n3-x", {"n3-x", "n3-x-2"}) == "n3-x-3"
    assert pa.unique_id("n3-y", set()) == "n3-y"


REPLY = {
    "title": "Choosing a bento", "topic": "food",
    "speakers": [
        {"id": "A", "name": "店員", "voice": "female", "age": "adult", "hint": "bright"},
        {"id": "B", "name": "客", "voice": "male", "age": "young"},
    ],
    "lines": [
        {"speaker": "A", "ja": "いらっしゃいませ。", "en": "Welcome.", "gaps": []},
        {"speaker": "B", "ja": "{えっと、}弁当をください。", "en": "Um, a bento, please.", "gaps": ["弁当"]},
        {"speaker": "A", "ja": "{はい、}温めますか。", "en": "Sure, shall I heat it?", "gaps": []},
        {"speaker": "B", "ja": "{あ、}はい。", "en": "Oh, yes.", "gaps": []},
        {"speaker": "A", "ja": "袋はいりますか。", "en": "Do you need a bag?", "gaps": ["袋"]},
        {"speaker": "B", "ja": "いいえ。", "en": "No.", "gaps": []},
        {"speaker": "A", "ja": "五百円です。", "en": "That's 500 yen.", "gaps": []},
        {"speaker": "B", "ja": "はい。", "en": "Here.", "gaps": [], "overlap": True},
    ],
    "questions": [{"question": "What does he decline?", "choices": ["A bag", "Heating", "Chopsticks", "A receipt"], "answer": 0}],
}


class FakeEndpoint(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers["Content-Length"]))
        body = json.dumps({"choices": [{"message": {"content": json.dumps(REPLY, ensure_ascii=False)}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def test_draft_appends_valid_entries_with_fresh_ids():
    if not bp.DICTIONARY_PACK.exists():
        print("  (skipped: no dictionary pack)")
        return
    sys.path.insert(0, str(Path(__file__).resolve().parent / "listening"))
    import author_dialogues as ad

    server = HTTPServer(("127.0.0.1", 0), FakeEndpoint)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory() as tmp:
            out, batches = Path(tmp) / "dialogues.json", Path(tmp) / "batches"
            args = argparse.Namespace(
                endpoint=f"http://127.0.0.1:{server.server_port}/v1", model="fake", count=2, temperature=0.5,
                timeout=10, retries=0, level=5, style="natural", topic=None,
            )
            code = pa.draft(
                args=args, key="dialogues", out=out, batch_dir=batches,
                messages=lambda avoid: ad.draft_messages(args, avoid),
                normalize=lambda raw: ad.normalize(args, raw),
                check=bp.check_dialogue,
                id_base=lambda e: f"nat-n5-{pa.slug(e['title'])}",
            )
            assert code == 0
            drafts = json.loads((batches / "llm-drafts.json").read_text(encoding="utf-8"))["dialogues"]
            assert [d["id"] for d in drafts] == ["nat-n5-choosing-a-bento", "nat-n5-choosing-a-bento-2"]
            assert all(d["source"] == "llm" and d["style"] == "natural" and d["jlpt"] == 5 for d in drafts)
            assert drafts[0]["speakers"][0]["hint"] == "bright" and drafts[0]["lines"][7]["overlap"] is True
    finally:
        server.shutdown()


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
