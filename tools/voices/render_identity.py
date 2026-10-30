"""Render identity and cache keys for the production renderers (QC 2026-10-30 V11). Standard library only.

A rendered clip is reused only when everything that shaped it is unchanged: the text and its references, the engine
and model revision, the weight hash, the reference audio hash, the settings, the segmenter version and the timing
policy version. Each WAV gets a sidecar `<id>.wav.json` holding that identity and its key. An existing master whose
sidecar is missing or different is never overwritten or deleted: the new render goes to
`OUT_DIR/compare/<key prefix>/<lang>/<id>.wav`, for a side-by-side listen and an explicit owner decision.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import boundary
import textsplit

FIELDS = ("text", "engine", "model_revision", "weight_sha256", "reference_sha256", "settings", "segmenter_version",
          "timing_policy_version")


def identity(*, text: object, engine: str, model_revision: str, weight_sha256: str, reference_sha256: str,
             settings: dict, segmenter_version: str = textsplit.SEGMENTER_VERSION,
             timing_policy_version: str = boundary.POLICY_VERSION) -> dict:
    return {"text": text, "engine": engine, "model_revision": model_revision, "weight_sha256": weight_sha256,
            "reference_sha256": reference_sha256, "settings": settings, "segmenter_version": segmenter_version,
            "timing_policy_version": timing_policy_version}


def key(ident: dict) -> str:
    missing = [f for f in FIELDS if f not in ident]
    if missing:
        raise ValueError(f"render identity lacks {missing}")
    return hashlib.sha256(json.dumps({f: ident[f] for f in FIELDS}, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def sidecar(wav: Path) -> Path:
    return wav.with_name(wav.name + ".json")


def matches(wav: Path, k: str) -> bool:
    try:
        return wav.is_file() and json.loads(sidecar(wav).read_text(encoding="utf-8")).get("key") == k
    except (OSError, ValueError):
        return False


def plan(out: Path, lang: str, clip_id: str, ident: dict) -> tuple[Path, bool]:
    """Where this render belongs and whether the file already there can be reused (True) or must be rendered."""
    k = key(ident)
    master = out / lang / f"{clip_id}.wav"
    if not master.exists() or matches(master, k):
        return master, master.exists()
    other = out / "compare" / k[:16] / lang / f"{clip_id}.wav"
    return other, matches(other, k)


def write(wav: Path, ident: dict) -> None:
    """Writes the sidecar after the WAV is in place."""
    sidecar(wav).write_text(json.dumps({"key": key(ident), **ident}, ensure_ascii=False, indent=1), encoding="utf-8")


def file_sha256(path: Path, cache: dict | None = None) -> str:
    """SHA-256 of a file; [cache] (keyed by path, size and mtime) avoids rehashing multi-GB weights every batch."""
    st = path.stat()
    ck = f"{path.resolve()}|{st.st_size}|{st.st_mtime_ns}"
    if cache is not None and ck in cache:
        return cache[ck]
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    if cache is not None:
        cache[ck] = h.hexdigest()
    return h.hexdigest()


def combined(hashes: dict[str, str]) -> str:
    """One hash over named file hashes (several weight files, or the references a job uses)."""
    return hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()


def package_versions(names: list[str]) -> dict[str, str]:
    from importlib import metadata
    out = {}
    for n in names:
        try:
            out[n] = metadata.version(n)
        except metadata.PackageNotFoundError:
            out[n] = "not installed"
    return out
