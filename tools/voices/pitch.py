"""Speaker pitch from a synthesized sentence (BRIEF_PHASE8 N-07): the voice manifest records a speaker's gender as
measured rather than guessed from a name. Median F0 by autocorrelation over voiced 40 ms frames of a mono 16-bit WAV;
below 150 Hz reads as male, above 190 Hz as female; between is ambiguous (synthetic voices of either gender land
there) and stays unverified. Standard library only."""
from __future__ import annotations

import array
import statistics
import wave
from pathlib import Path


def median_f0(path: Path) -> float | None:
    with wave.open(str(path), "rb") as w:
        rate = w.getframerate()
        pcm = array.array("h", w.readframes(w.getnframes()))
        if w.getnchannels() == 2:
            pcm = pcm[::2]
    frame = int(rate * 0.04)
    lo, hi = int(rate / 400), int(rate / 70)  # 70–400 Hz
    f0s = []
    for start in range(0, len(pcm) - frame - hi, frame):
        x = pcm[start:start + frame + hi]
        energy = sum(v * v for v in x[:frame]) / frame
        if energy < 300_000:  # silence / unvoiced
            continue
        e0 = sum(x[i] * x[i] for i in range(0, frame, 2)) or 1
        corr = [(k, sum(x[i] * x[i + k] for i in range(0, frame, 2)) / e0) for k in range(lo, hi)]
        peak = max(c for _, c in corr)
        if peak < 0.45:
            continue
        # the shortest lag close to the peak: picking the strongest lag halves the pitch (octave error)
        lag = next(k for k, c in corr if c >= 0.85 * peak and k > lo)
        f0s.append(rate / lag)
    return statistics.median(f0s) if len(f0s) >= 5 else None


def gender(f0: float | None) -> str:
    if f0 is None:
        return "unknown"
    return "male" if f0 < 150 else "female" if f0 > 190 else "unknown"
