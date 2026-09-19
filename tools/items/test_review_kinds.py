"""Tests for review.py over every reviewable content type (D-245…D-249): ingest of app verdicts, the interactive loop,
and that each edited source file still passes its builder's validation afterwards.

Fixtures are copies of the real source files (tracks, readers, banks, practice, onomatopoeia, grammar) in a temporary
tools tree, so the tests follow the formats the builders read. The dictionary pack isn't needed: validation that
resolves JMdict ids runs against a permissive stand-in, which checks structure, provenance flags and review keys.

The tools project has no pytest; run with: uv run python items/test_review_kinds.py
"""

from __future__ import annotations

import json
import shutil
import sqlite3
import sys
import tempfile
from collections import defaultdict
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path[:0] = [str(HERE), str(TOOLS / "packs"), str(TOOLS / "packs" / "readers"), str(TOOLS / "packs" / "grammar")]

import build_grammar as bg
import build_onomatopoeia as bo
import build_practice as bp
import build_tracks as bt
import gen_dlpt as g
import grammar_ja
import practice_authoring as pa
import readers_lib as rl
import review
import validate as grammar_validate
import validate_readers as vr

FIXTURES = [
    *(f"packs/grammar/n{n}.json" for n in range(1, 6)),
    "items/bank/dlpt_liaison.json",
    "items/bank/dlpt_reading_upper.json",
    "packs/listening/dialogues.json",
    "packs/speaking/scenarios.json",
    "packs/speaking/opi.json",
    "packs/readers/stories/n5-a.json",
    "packs/onomatopoeia/entries.json",
    "packs/tracks/daily-life.json",
    "packs/tracks/military.json",
    "packs/tracks/gaming.json",
    "packs/tracks/gaming.practice.json",
]


class Out(StringIO):
    """Captured stdout; the tools reconfigure stdout to UTF-8, which a StringIO needn't do."""

    def reconfigure(self, **_kwargs) -> None:
        pass


class Everything(dict):
    """A dict that holds every key (KANJIDIC2 and radicals in the stand-in dictionary)."""

    def __contains__(self, key) -> bool:
        return True

    def __getitem__(self, key):
        return ("keyword", [])


class FakeDictionary:
    """Stand-in for the JMdict-backed dictionaries of build_tracks and build_practice: every form resolves."""

    def __init__(self) -> None:
        self.ids: dict[str, int] = {}
        self.gloss: dict[int, str] = defaultdict(lambda: "gloss")
        self.jlpt: dict[int, int | None] = {}
        self.kana: dict[int, list[str]] = defaultdict(lambda: ["よみ"])
        self.reading: dict[int, str] = defaultdict(lambda: "よみ")
        self.kanji = Everything()
        self.radicals = Everything()

    def lookup(self, form: str) -> int:
        return self.ids.setdefault(form, 10_000_000 + len(self.ids))

    def matches(self, *_args) -> bool:
        return True

    def verb_class(self, _eid: int) -> None:
        return None

    def accents(self, *_args) -> str:
        return ""

    def close(self) -> None:
        pass


def make_tree(tmp: Path) -> Path:
    tools = tmp / "tools"
    for rel in FIXTURES:
        (tools / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(TOOLS / rel, tools / rel)
    return tools


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_verdicts(path: Path, *entries: dict) -> Path:
    path.write_text(json.dumps({
        "format": "tsumugi-review-verdicts", "version": 1, "reviewer": "owner", "exportedAt": "2026-09-18T10:00:00Z",
        "verdicts": [{"notes": "", "edits": {}, "decidedAt": "2026-09-18T09:00:00Z", **e} for e in entries],
    }, ensure_ascii=False), encoding="utf-8")
    return path


def entry_ids(tools: Path, kind: str) -> list[str]:
    k = review.KINDS[kind]
    out = []
    for f in k.files(tools):
        if f.exists():
            doc = read(f)
            out += [eid for eid, holder, i in k.entries(f, doc) if k.pending(doc, holder[i])]
    return out


def test_review_key_matches_the_kotlin_function() -> None:
    assert review.review_key("") == "811c9dc5"
    assert review.review_key("a") == "e40c292c"
    # The same values are asserted in shared/src/commonTest/.../review/ContentReviewSourcesTest.kt.
    assert review.review_key("こんにちは。お名前は？") == review.review_key("こんにちは。お名前は？")
    assert review.review_key("ガ") == review.review_key("ガ"), "keys are over NFC text"
    print(f"    reviewKey(こんにちは。お名前は？) = {review.review_key('こんにちは。お名前は？')}")


def test_ingest_every_kind_and_the_files_stay_valid() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        tools = make_tree(Path(tmp))
        g_file = tools / "packs/grammar/n5.json"
        point = read(g_file)["points"][0]
        dialogues = read(tools / "packs/listening/dialogues.json")["dialogues"]
        d0 = dialogues[0]
        ok_line = next(i for i, ln in enumerate(bp.check_dialogue(d0, FakeDictionary())[1])
                       if not ln["fillers"] and not ln["overlap"]
                       and bp.DIALOGUE_DRILL_CHARS[0] <= len(ln["ja"]) <= bp.DIALOGUE_DRILL_CHARS[1])
        scenario = read(tools / "packs/speaking/scenarios.json")["scenarios"][0]
        opi_id = entry_ids(tools, "opi_question")[0]
        story = read(tools / "packs/readers/stories/n5-a.json")["passages"][0]
        ono = read(tools / "packs/onomatopoeia/entries.json")["entries"][0]
        daily = read(tools / "packs/tracks/daily-life.json")
        gaming = read(tools / "packs/tracks/gaming.json")
        gaming_practice = read(tools / "packs/tracks/gaming.practice.json")
        reading = read(tools / "packs/tracks/military.json")["readings"][0]
        upper = read(tools / "items/bank/dlpt_reading_upper.json")
        liaison_item = read(tools / "items/bank/dlpt_liaison.json")["items"][0]

        track_drill = daily["drills"][0]
        vfile = write_verdicts(
            Path(tmp) / "verdicts.json",
            {"kind": "grammar_ja", "id": point["id"], "verdict": "edit", "edits": {"meaning_ja": "話題を示す。"}},
            {"kind": "grammar_point", "id": read(g_file)["points"][1]["id"], "verdict": "accept"},
            {"kind": "drill_item", "id": f"g:{point['id']}:{review.review_key(point['examples'][0]['ja'])}",
             "verdict": "accept"},
            {"kind": "drill_item", "id": f"d:{d0['id']}:{ok_line}", "verdict": "edit",
             "edits": {"en": "Excuse me (edited)."}},
            {"kind": "dialogue", "id": dialogues[1]["id"], "verdict": "accept"},
            {"kind": "scenario", "id": scenario["id"], "verdict": "edit", "edits": {"learnerRole": "A new student"}},
            {"kind": "opi_question", "id": opi_id, "verdict": "edit", "edits": {"en": "Hello. What's your name?"}},
            {"kind": "reader_passage", "id": story["id"], "verdict": "edit", "edits": {"title": story["title"]}},
            {"kind": "onomatopoeia", "id": str(ono["id"]), "verdict": "edit",
             "edits": {"feel": "A light, quick feeling", "theme": "weather"}},
            {"kind": "track_word", "id": f"daily-life:{daily['words'][0]['id']}", "verdict": "edit",
             "edits": {"gloss": "residence certificate (edited)"}},
            {"kind": "track_kanji", "id": f"gaming:{gaming['kanji'][0]['kanji']}", "verdict": "edit",
             "edits": {"hint": "A clearer hint."}},
            {"kind": "track_scenario", "id": gaming_practice["scenarios"][0]["id"], "verdict": "accept"},
            {"kind": "track_dialogue", "id": daily["dialogues"][0]["id"], "verdict": "accept"},
            {"kind": "track_drill", "id": track_drill["id"], "verdict": "edit",
             "edits": {"explanation": "Edited explanation.", "answers": "[]"}},
            {"kind": "track_drill", "id": daily["drills"][1]["id"], "verdict": "reject", "notes": "wrong key"},
            {"kind": "track_situation", "id": daily["situations"][0]["id"], "verdict": "accept"},
            {"kind": "track_task", "id": daily["tasks"][0]["id"], "verdict": "edit", "edits": {"place": "City hall"}},
            {"kind": "track_reading", "id": reading["id"], "verdict": "accept"},
            {"kind": "exam_passage", "id": upper["passages"][0]["id"], "verdict": "accept"},
            {"kind": "exam_item", "id": liaison_item["id"], "verdict": "accept", "notes": "ok"},
            {"kind": "track_word", "id": "daily-life:1", "verdict": "accept"},
        )
        report = review.ingest(vfile, root=tools)
        assert any(line == "track_word daily-life:1: not found in the sources; skipped" for line in report), report
        assert any("fields not applied: answers" in line for line in report), report
        assert sum(1 for line in report if line.startswith("re-validate: ")) >= 8, report

        # grammar: the Japanese explanation flips ja_source, the English one stays as it was.
        points = read(g_file)["points"]
        p0 = points[0]
        assert p0["ja_source"] == "verified" and p0["meaning_ja"] == "話題を示す。"
        assert p0["ja_reviewed"] == {"by": "owner", "on": "2026-09-18"}
        assert p0.get("source", "llm") == "llm", "grammar_ja must not verify the English text"
        assert points[1]["source"] == "verified"
        assert p0["examples"][0]["source"] == "verified"
        assert bg.example_source(p0, p0["examples"][0]) == "verified"
        assert bg.example_source(p0, p0["examples"][1]) == "llm"
        assert bg.example_source(points[1], points[1]["examples"][1]) == "verified", "a verified point verifies examples"
        assert not grammar_ja.problems(p0["meaning_ja"], p0["nuance_ja"])
        argv = sys.argv
        sys.argv = ["validate.py", *(str(tools / f"packs/grammar/n{n}.json") for n in range(1, 6))]
        try:
            with redirect_stdout(Out()) as out:
                assert grammar_validate.main() == 0, out.getvalue()[-2000:]
        finally:
            sys.argv = argv

        # banks (D-034): verified flips, source stays.
        bands = g.load_bands()
        for name in ("dlpt_reading_upper.json", "dlpt_liaison.json"):
            bank = read(tools / "items/bank" / name)
            rep = g.Report()
            g.validate_bank(bank, rep, bands, set(), name=name)
            assert not rep.errors, rep.errors[:5]
        p = read(tools / "items/bank/dlpt_reading_upper.json")["passages"][0]
        assert p["verified"] is True and p["source"] == "llm"
        it = read(tools / "items/bank/dlpt_liaison.json")["items"][0]
        assert it["reviewed"] == {"by": "owner", "on": "2026-09-18", "notes": "ok"}

        # practice: dialogues, scenarios, OPI, drill items.
        dic = FakeDictionary()
        doc = read(tools / "packs/listening/dialogues.json")
        for d in doc["dialogues"][:3]:
            bp.check_dialogue(d, dic)
        assert doc["dialogues"][1]["source"] == "verified"
        line = doc["dialogues"][0]["lines"][ok_line]
        assert line["source"] == "verified" and line["en"] == "Excuse me (edited)."
        assert doc["dialogues"][0]["source"] == "llm", "a drill item doesn't verify its whole dialogue"
        assert pa.reviewed(doc["dialogues"][0]), "the author scripts keep a dialogue with a reviewed line"
        bp.DIALOGUES = tools / "packs/listening/dialogues.json"
        sets = bp.dialogue_drills(dic)
        items = [i for s in sets for i in s["items"] if i["audioKey"] == f"dialogue/{d0['id']}/{ok_line}"]
        assert items and items[0]["source"] == "verified", items
        assert any(i["source"] == "llm" for s in sets for i in s["items"])
        sdoc = read(tools / "packs/speaking/scenarios.json")
        bp.check_scenario(sdoc["scenarios"][0], dic)
        assert sdoc["scenarios"][0]["source"] == "verified" and sdoc["scenarios"][0]["learnerRole"] == "A new student"
        bp.OPI = tools / "packs/speaking/opi.json"
        db = sqlite3.connect(":memory:")
        db.execute("CREATE TABLE pack_meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execute("CREATE TABLE opi_question (ilr, ord, phase, prompt_ja, prompt_en, note, source, domain)")
        db.execute("CREATE TABLE opi_checklist (ilr, ord, statement)")
        bp.build_opi(db)
        ilr = opi_id.split(":")[0]
        rows = db.execute("SELECT prompt_ja, prompt_en, source FROM opi_question WHERE ilr = ? ORDER BY ord", (ilr,)).fetchall()
        reviewed = [r for r in rows if review.review_key(r[0]) == opi_id.split(":")[1]]
        assert reviewed == [(reviewed[0][0], "Hello. What's your name?", "verified")], reviewed
        assert sum(1 for r in rows if r[2] == "llm") == len(rows) - 1

        # readers: both flags flip; the story passes the gate's schema checks.
        s = read(tools / "packs/readers/stories/n5-a.json")["passages"][0]
        assert s["source"] == "verified" and s["verified"] is True
        rep = vr.Report()
        vr.check_story(s, rl.load_levels(), None, rep)
        assert not rep.errors, rep.errors

        # onomatopoeia: source flips; theme isn't an app-editable field.
        e = read(tools / "packs/onomatopoeia/entries.json")["entries"][0]
        assert e["source"] == "verified" and e["feel"] == "A light, quick feeling" and e["theme"] == ono["theme"]
        themes = {t["id"] for t in json.loads((TOOLS / "packs/onomatopoeia/themes.json").read_text(encoding="utf-8"))["themes"]}
        assert not bo.validate(e, {"kana": [e["text"]], "kanji": []}, themes)

        # tracks: source "verified" + verified true, carried into the pack rows; review keys never reach a payload.
        bands = json.loads((TOOLS / "items/ilr_bands.json").read_text(encoding="utf-8"))["levels"]
        built = {}
        for name in ("daily-life", "military", "gaming"):
            built[name] = bt.Track(bt.load_track(tools / f"packs/tracks/{name}.json"), dic, 0, set(), bands)
        word = next(r for r in built["daily-life"].rows["track_word"] if r[2] == daily["words"][0]["id"])
        assert word[5] == "residence certificate (edited)" and word[-1] == "verified"
        assert sum(1 for r in built["daily-life"].rows["track_word"] if r[-1] == "llm") == len(daily["words"]) - 1
        kanji = built["gaming"].rows["track_kanji"][0]
        assert kanji[6] == "A clearer hint." and kanji[-1] == "verified"
        drills = {r[0]: r for r in built["daily-life"].rows["track_drill"]}
        assert drills[track_drill["id"]][-1] == "verified"
        payload = json.loads(drills[track_drill["id"]][6])
        assert payload["explanation"] == "Edited explanation." and "reviewed" not in payload
        rejected = drills[daily["drills"][1]["id"]]
        assert rejected[-1] == "llm" and "rejected" not in json.loads(rejected[6])
        assert {r[0]: r[-1] for r in built["gaming"].rows["track_scenario"]}[gaming_practice["scenarios"][0]["id"]] == "verified"
        assert built["daily-life"].rows["track_situation"][0][-1] == "verified"
        task = built["daily-life"].rows["track_task"][0]
        assert task[-1] == "verified" and json.loads(task[5])["place"] == "City hall"
        assert built["military"].rows["track_reading"][0][-1] == "verified"
        gp = read(tools / "packs/tracks/gaming.practice.json")
        assert gp["scenarios"][0]["verified"] is True and gp["scenarios"][0]["reviewed"]["by"] == "owner"

        # Nothing left pending for the verdicts just applied.
        assert f"daily-life:{daily['words'][0]['id']}" not in entry_ids(tools, "track_word")
        assert daily["drills"][1]["id"] in entry_ids(tools, "track_drill"), "a rejected item stays pending"


def test_interactive_review_of_the_new_kinds() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        tools = make_tree(Path(tmp))
        answers = iter(["a", "r", "too vague", "s", "q"])
        ono = tools / "packs/onomatopoeia/entries.json"
        with redirect_stdout(Out()) as out:
            review.review_file(ono, "tester", ask=lambda _p: next(answers))
        entries = read(ono)["entries"]
        assert entries[0]["source"] == "verified" and entries[0]["reviewed"]["by"] == "tester", out.getvalue()[:500]
        assert entries[1]["rejected"]["notes"] == "too vague" and entries[1]["source"] == "llm"
        assert entries[2]["source"] == "llm" and "rejected" not in entries[2]

        # Grammar files hold three kinds; --kind picks the Japanese explanations or the drill examples.
        g_file = tools / "packs/grammar/n4.json"
        answers = iter(["a", "q"])
        with redirect_stdout(Out()):
            review.review_file(g_file, "tester", kind="grammar_ja", ask=lambda _p: next(answers))
        p0 = read(g_file)["points"][0]
        assert p0["ja_source"] == "verified" and p0.get("source", "llm") == "llm"
        answers = iter(["a", "q"])
        with redirect_stdout(Out()):
            review.review_file(g_file, "tester", kind="drill_item", ask=lambda _p: next(answers))
        assert read(g_file)["points"][0]["examples"][0]["source"] == "verified"

        # OPI arrays become objects once reviewed; the rest stay compact.
        opi = tools / "packs/speaking/opi.json"
        answers = iter(["a", "q"])
        with redirect_stdout(Out()):
            review.review_file(opi, "tester", ask=lambda _p: next(answers))
        qs = read(opi)["levels"][0]["questions"]
        assert isinstance(qs[0], dict) and qs[0]["source"] == "verified" and qs[0]["phase"] == "WARM_UP"
        assert isinstance(qs[1], list)

        # A track file walks its words first; the saved file keeps build_tracks' layout and stays loadable.
        track = tools / "packs/tracks/military.json"
        answers = iter(["a", "q"])
        with redirect_stdout(Out()):
            review.review_file(track, "tester", ask=lambda _p: next(answers))
        doc = read(track)
        assert doc["words"][0]["source"] == "verified" and doc["words"][0]["verified"] is True
        assert '\n  {"text": ' in track.read_text(encoding="utf-8"), "one word per line"
        bt.Track(bt.load_track(track), FakeDictionary(), 0, set(),
                 json.loads((TOOLS / "items/ilr_bands.json").read_text(encoding="utf-8"))["levels"])

        # Readers: a story flips both flags.
        story = tools / "packs/readers/stories/n5-a.json"
        answers = iter(["a"])
        with redirect_stdout(Out()):
            review.review_file(story, "tester", ask=lambda _p: next(answers))
        s = read(story)["passages"][0]
        assert s["source"] == "verified" and s["verified"] is True


def test_every_kind_of_the_app_is_known() -> None:
    """The kinds ContentReview.kt exports (ReviewKind.code) are exactly the ones review.py ingests."""
    kt = (TOOLS.parent / "shared/src/commonMain/kotlin/app/tsumugi/review/ContentReview.kt").read_text(encoding="utf-8")
    import re

    codes = set(re.findall(r'^\s+[A-Z_]+\("([a-z_]+)", "', kt, re.MULTILINE))
    assert codes == set(review.EDITABLE_BY_KIND), (codes ^ set(review.EDITABLE_BY_KIND))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    tests = [v for k, v in dict(globals()).items() if k.startswith("test_")]
    for t in tests:
        t()
        print(f"ok  {t.__name__}")
    print(f"{len(tests)} passed")
