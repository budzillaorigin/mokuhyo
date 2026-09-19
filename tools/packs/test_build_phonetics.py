"""Tests for build_phonetics.py (BRIEF_V2 §6.15, DECISIONS D-281…D-283).

Unit tests run on hand-made KanjiVG trees. The known-family test runs the whole derivation on the real inputs (the
built dictionary pack and the pinned KanjiVG zip, found in this checkout or a parent one) and is skipped without them.

The tools project has no pytest; run with: uv run python packs/test_build_phonetics.py
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_phonetics as bp

SVG = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:kvg="http://kanjivg.tagaini.net">
<g id="kvg:StrokePaths_x"><g id="kvg:x" kvg:element="{k}">{parts}</g></g></svg>"""


def svg(kanji: str, *parts: str) -> bytes:
    return SVG.format(k=kanji, parts="".join(parts)).encode("utf-8")


def part(el: str, pos: str = "", radical: bool = False, phon: bool = False, inner: str = "") -> str:
    attrs = f' kvg:element="{el}"' + (f' kvg:position="{pos}"' if pos else "")
    attrs += ' kvg:radical="general"' if radical else ""
    attrs += f' kvg:phon="{el}"' if phon else ""
    return f"<g{attrs}>{inner}</g>"


def row(lit: str, on: list[str], freq: int | None = 100) -> bp.KanjiRow:
    return bp.KanjiRow(lit, on, freq, 4, None, 10)


def test_fold_and_match() -> None:
    assert bp.fold("ジョウ") == "ショ" and bp.fold("バン") == "ハン" and bp.fold("コウ") == "コ"
    assert bp.match(["セイ", "ジョウ"], ["セイ", "ショウ"]) == ("same", "セイ")
    assert bp.match(["ジョウ"], ["ショウ"]) == ("related", "ジョウ")
    assert bp.match(["ソウ"], ["セイ", "ショウ"]) is None
    assert bp.on_readings('["セイ","ショウ","-シン"]') == ["セイ", "ショウ", "シン"]


def test_tree_parts_and_elements() -> None:
    tree = bp.parse_tree("清", svg("清", part("氵", "left", radical=True),
                                  part("青", "right", phon=True, inner=part("龶", "top") + part("月", "bottom"))))
    assert [p.element for p in tree.parts] == ["氵", "青"]
    assert tree.parts[0].radical and tree.parts[1].phon
    assert tree.elements == {"氵": (1, "left"), "青": (1, "right"), "龶": (2, "right"), "月": (2, "right")}
    # kvg:part splits one element into several groups; it's still one part.
    split = bp.parse_tree("裏", svg("裏", part("衣"), part("里"), part("衣")))
    assert [p.element for p in split.parts] == ["衣", "里"]


def test_heuristic_roles_and_series() -> None:
    kanji = {k: row(k, on) for k, on in {
        "青": ["セイ", "ショウ"], "清": ["セイ", "ショウ"], "晴": ["セイ"], "静": ["セイ", "ジョウ"],
        "日": ["ニチ", "ジツ"], "争": ["ソウ"], "明": ["メイ", "ミョウ"], "月": ["ゲツ", "ガツ"],
    }.items()}
    trees = {
        "清": bp.parse_tree("清", svg("清", part("氵", radical=True), part("青"))),
        "晴": bp.parse_tree("晴", svg("晴", part("日", radical=True), part("青"))),
        "静": bp.parse_tree("静", svg("静", part("青", radical=True, phon=True), part("争"))),
        "明": bp.parse_tree("明", svg("明", part("日", radical=True), part("月"))),
    }
    chosen = bp.choose_phonetics(trees, kanji)
    assert {k: c.component for k, c in chosen.items()} == {"清": "青", "晴": "青", "静": "青"}
    assert chosen["静"].match == "same"
    series = bp.derive_series(chosen, kanji)
    assert list(series) == ["青"] and series["青"]["members"][0] == "青"
    assert set(series["青"]["members"]) == set("青清晴静") and series["青"]["readings"].split()[0] == "セイ"

    _, roles, rows = bp.build_rows(trees, kanji, chosen, list(series.values()))
    by = {(r[0], r[1]): r[3] for r in roles}
    assert by[("清", "青")] == "PHONETIC" and by[("清", "氵")] == "SEMANTIC"
    assert by[("静", "争")] == "SEMANTIC", "the other part of a phono-semantic kanji is semantic"
    assert by[("明", "日")] == "SEMANTIC" and by[("明", "月")] == "FORM", "no phonetic: radical semantic, rest form"
    assert rows[0][0] == "青" and rows[0][4] == "derived"


def test_merge_keeps_reviewed_series() -> None:
    data = {"series": [
        {"id": "青", "members": "青清", "readings": "セイ", "source": "verified", "reviewed": {"by": "o", "on": "d"}},
        {"id": "方", "members": "方放", "readings": "ホウ", "source": "derived"},
        {"id": "口", "members": "口高", "readings": "コウ", "source": "derived", "rejected": {"by": "o", "on": "d", "notes": "no"}},
        {"id": "工", "members": "工功", "readings": "コウ", "source": "derived"},
    ]}
    derived = {
        "青": {"id": "青", "members": "青清晴精", "readings": "セイ", "source": "derived"},
        "方": {"id": "方", "members": "方放訪", "readings": "ホウ", "source": "derived"},
        "口": {"id": "口", "members": "口高豪", "readings": "コウ", "source": "derived"},
    }
    kept, added, dropped = bp.merge_series(data, derived)
    by = {e["id"]: e for e in data["series"]}
    assert by["青"]["members"] == "青清", "a reviewed series keeps its reviewed members"
    assert by["方"]["members"] == "方放訪", "an unreviewed series is re-derived"
    assert "rejected" in by["口"] and "工" not in by
    assert (kept, added, dropped) == (2, 0, 1)
    assert [e["id"] for e in bp.pack_series(data)] == ["方", "青"], "rejected series stay out of the pack"


def _find(rel: str, pattern: str | None = None) -> Path | None:
    for d in [HERE, *HERE.parents]:
        target = d / rel
        if pattern:
            hits = sorted(target.glob(pattern)) if target.is_dir() else []
            if hits:
                return hits[-1]
        elif target.exists():
            return target
    return None


KNOWN_FAMILIES = {
    "青": "清晴精請情静",
    "方": "放訪防房",
    "反": "販版坂板飯",
    "同": "銅洞筒胴",
    "交": "校効較郊",
    "召": "招昭沼紹",
    "令": "冷齢鈴零",
    "包": "抱砲胞飽泡",
}


def test_known_families_from_the_real_sources() -> None:
    dictionary = _find("content/packs/dictionary.sqlite")
    kanjivg = _find("tools/.cache", "kanjivg-*.zip")
    if dictionary is None or kanjivg is None:
        print("  skipped: no built dictionary pack or KanjiVG zip")
        return
    kanji, trees, chosen, series = bp.derive(dictionary, kanjivg)
    for head, members in KNOWN_FAMILIES.items():
        assert head in series, f"no series for {head}"
        missing = set(members) - set(series[head]["members"])
        assert not missing, f"{head}: missing {''.join(sorted(missing))} (got {series[head]['members']})"
        assert series[head]["members"][0] == head
    assert chosen["清"].component == "青" and chosen["静"].component == "青"
    # 氵, 亻 and 艹 are radicals, never phonetic families.
    assert not {"氵", "亻", "艹", "扌", "言"} & set(series)
    agree = bp.agreement(trees, kanji, chosen)
    assert agree["agree"] * 2 > agree["kvg_marked"], f"too little agreement with KanjiVG's own marks: {agree}"
    with tempfile.TemporaryDirectory():
        _, roles, rows = bp.build_rows(trees, kanji, chosen, list(series.values()))
    assert len(rows) == len(series) and roles


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for t in tests:
        print(t.__name__)
        t()
    print(f"{len(tests)} tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
