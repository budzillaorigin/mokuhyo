"""Tests for boundary.py against the fixture table shared with BoundaryPolicyTest (QC 2026-10-30 V2).
Run: python voices/test_boundary.py"""
from __future__ import annotations

import json
import sys
from itertools import pairwise
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import boundary

FIXTURE = json.loads((Path(__file__).resolve().parent / "fixtures" / "boundary_cases.json").read_text(encoding="utf-8"))


def clip(lead: int, voiced: int, trail: int) -> list[int]:
    return [0] * lead + [1000] * voiced + [0] * trail


def heard_gaps(pcm: list[int]) -> list[int]:
    voiced = [i for i, s in enumerate(pcm) if abs(s) >= boundary.THRESHOLD]
    return [b - a - 1 for a, b in pairwise(voiced) if b - a > 1]


def test_fixture_joins() -> None:
    policy = boundary.Policy.parse(FIXTURE["policy"])
    for case in FIXTURE["joins"]:
        out = policy.join([clip(*c) for c in case["clips"]], case["boundaries"], FIXTURE["rate"])
        assert heard_gaps(out) == case["heard_gaps_ms"], (case["name"], heard_gaps(out))
        assert len(out) == case["total_ms"], (case["name"], len(out))


def test_shipped_policy_is_complete_and_versioned() -> None:
    p = boundary.load()
    assert p.version and p.version != "legacy-fixed-1"
    assert p.gaps_ms["continuation"] <= p.gaps_ms["sentence_end"] <= p.gaps_ms["speaker_turn"], p.gaps_ms


def test_renderers_use_the_policy_without_a_final_pad() -> None:
    """chatterbox/voicevox used to add the job pause after every line, including the last."""
    import voicevox_render

    job = {"id": "x", "lang": "ja", "pause": 0.6,
           "lines": [{"text": "a", "ref": "vv:8", "speaker": "A"}, {"text": "b", "ref": "vv:2", "speaker": "B"}]}
    frames = (b"\0\0" * 24 + b"\xe8\x03" * 240 + b"\0\0" * 24, 24000)  # 1 ms lead, 10 ms voiced, 1 ms trail at 24 kHz
    pcm, rate = voicevox_render.compose(job, lambda text, ref: frames)
    gap = boundary.POLICY.gaps_ms["speaker_turn"]
    assert len(pcm) // 2 == 2 * (24 + 240 + 24) + (gap - 2) * rate // 1000, (len(pcm) // 2, gap)


if __name__ == "__main__":
    for t in [test_fixture_joins, test_shipped_policy_is_complete_and_versioned, test_renderers_use_the_policy_without_a_final_pad]:
        t()
        print("ok", t.__name__)
