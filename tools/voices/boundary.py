"""Boundary timing policy for joined clips (QC 2026-10-30 V2). Standard library only.

Mirrors shared/src/commonMain/kotlin/app/mokuhyo/speech/BoundaryPolicy.kt and reads the same versioned config
(shared/src/jvmMain/resources/app/mokuhyo/speech/boundary_policy.json): a target heard gap per boundary type
(continuation, sentence_end, speaker_turn). A join measures each clip's edge silence and inserts only the remainder;
edges and internal pauses are never trimmed, speakers never overlap, and nothing is added after the last clip.
POLICY_VERSION is part of every render identity (render_identity.py), so changing the config invalidates cached renders.
"""
from __future__ import annotations

import json
from pathlib import Path

CONFIG = Path(__file__).resolve().parents[2] / "shared/src/jvmMain/resources/app/mokuhyo/speech/boundary_policy.json"
THRESHOLD = 328  # EdgeSilence.THRESHOLD: about -40 dBFS on int16 samples
BOUNDARIES = ("continuation", "sentence_end", "speaker_turn")


class Policy:
    def __init__(self, version: str, gaps_ms: dict[str, int]) -> None:
        missing = [b for b in BOUNDARIES if b not in gaps_ms]
        if missing:
            raise ValueError(f"boundary policy lacks {missing}")
        self.version = version
        self.gaps_ms = {b: int(gaps_ms[b]) for b in BOUNDARIES}

    @staticmethod
    def parse(obj: dict) -> Policy:
        return Policy(obj["version"], obj["gaps_ms"])

    def insert_ms(self, boundary: str, trailing_ms: int, leading_ms: int) -> int:
        return max(0, self.gaps_ms[boundary] - trailing_ms - leading_ms)

    def join(self, clips: list[list[int]], boundaries: list[str], rate: int) -> list[int]:
        """[clips] (int16 sample sequences at [rate]) joined at [boundaries] (one fewer than clips)."""
        if len(boundaries) != max(len(clips) - 1, 0):
            raise ValueError(f"{len(clips)} clips need {len(clips) - 1} boundaries")
        edges = [edge_silence(c, rate) for c in clips]
        out: list[int] = []
        for i, c in enumerate(clips):
            out.extend(c)
            if i < len(boundaries):
                trailing = 0 if edges[i][2] else edges[i][1]
                leading = 0 if edges[i + 1][2] else edges[i + 1][0]
                out.extend([0] * (self.insert_ms(boundaries[i], trailing, leading) * rate // 1000))
        return out


def edge_silence(pcm, rate: int, threshold: int = THRESHOLD) -> tuple[int, int, bool]:
    """(leading ms, trailing ms, silent) like EdgeSilence.of."""
    n = len(pcm)
    first = next((i for i in range(n) if abs(int(pcm[i])) >= threshold), -1)
    if first < 0:
        return n * 1000 // rate, n * 1000 // rate, True
    last = next(i for i in range(n - 1, -1, -1) if abs(int(pcm[i])) >= threshold)
    return first * 1000 // rate, (n - 1 - last) * 1000 // rate, False


def between(speaker: str, nxt: str) -> str:
    return "sentence_end" if speaker == nxt else "speaker_turn"


def load(path: Path = CONFIG) -> Policy:
    return Policy.parse(json.loads(path.read_text(encoding="utf-8")))


POLICY = load()
POLICY_VERSION = POLICY.version
