"""Tests for render_identity.py and the renderers' reuse rule (QC 2026-10-30 V11). Standard library only.
Run: python voices/test_render_identity.py"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chatterbox_render
import render_identity
import voicevox_render

BASE = {"text": [["Hola.", "a.wav"]], "engine": "chatterbox-multilingual", "model_revision": "r1", "weight_sha256": "w1",
        "reference_sha256": "ref1", "settings": {"pause": 0.6}, "segmenter_version": "2", "timing_policy_version": "t1"}
CHANGED = {"text": [["Hola!", "a.wav"]], "engine": "other", "model_revision": "r2", "weight_sha256": "w2",
           "reference_sha256": "ref2", "settings": {"pause": 0.5}, "segmenter_version": "3", "timing_policy_version": "t2"}


def test_every_key_invalidates_and_an_unchanged_one_reuses() -> None:
    assert set(CHANGED) == set(render_identity.FIELDS)
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        ident = render_identity.identity(**BASE)
        master, reuse = render_identity.plan(out, "es", "c1", ident)
        assert (master, reuse) == (out / "es" / "c1.wav", False)
        master.parent.mkdir(parents=True)
        master.write_bytes(b"master")
        render_identity.write(master, ident)
        assert render_identity.plan(out, "es", "c1", render_identity.identity(**BASE)) == (master, True)
        for field, value in CHANGED.items():
            path, reuse = render_identity.plan(out, "es", "c1", render_identity.identity(**{**BASE, field: value}))
            assert not reuse and path != master and "compare" in path.parts, field
        assert master.read_bytes() == b"master"


def test_a_master_without_identity_is_never_overwritten() -> None:
    """Both renderers used to skip any existing file, whatever model or reference made it."""
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        old = out / "ja" / "c1.wav"
        old.parent.mkdir(parents=True)
        old.write_bytes(b"old master")
        path, reuse = render_identity.plan(out, "ja", "c1", render_identity.identity(**BASE))
        assert not reuse and path != old
        assert old.read_bytes() == b"old master"


def test_voicevox_renders_once_then_reuses_and_a_new_engine_goes_to_compare() -> None:
    calls = []

    def synth(text: str, ref: str) -> tuple[bytes, int]:
        calls.append(text)
        return b"\1\0" * 10, 24000

    job = {"id": "ja-dl-1", "lang": "ja", "lines": [{"text": "はい。", "ref": "vv:8"}], "pause": 0.6}
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        eng = {"engine_version": "0.20.0", "core_versions": ["0.15.0"]}
        assert voicevox_render.render_jobs([job], out, synth, eng) == 1
        master = out / "ja" / "ja-dl-1.wav"
        first = master.read_bytes()
        assert render_identity.sidecar(master).is_file()
        assert voicevox_render.render_jobs([job], out, synth, eng) == 0 and len(calls) == 1, "same identity: reused"
        assert voicevox_render.render_jobs([job], out, synth, {**eng, "engine_version": "0.21.0"}) == 1
        assert master.read_bytes() == first, "the master is never overwritten"
        assert list((out / "compare").rglob("ja-dl-1.wav")), "the new render is kept for comparison"


def test_chatterbox_identity_covers_reference_audio_and_job_settings() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        refs = Path(tmp)
        (refs / "a.wav").write_bytes(b"one voice")
        job = {"id": "c", "lang": "es", "lines": [{"text": "Hola.", "ref": "a.wav"}], "pause": 0.6}
        k1 = render_identity.key(chatterbox_render.job_identity(job, "r", "w", refs, {"seed": None}))
        (refs / "a.wav").write_bytes(b"another voice")
        k2 = render_identity.key(chatterbox_render.job_identity(job, "r", "w", refs, {"seed": None}))
        k3 = render_identity.key(chatterbox_render.job_identity({**job, "pause": 0.3}, "r", "w", refs, {"seed": None}))
        assert len({k1, k2, k3}) == 3


def test_checkpoint_is_named() -> None:
    assert chatterbox_render.CHECKPOINT == "t3_mtl23ls_v3"


if __name__ == "__main__":
    for t in [test_every_key_invalidates_and_an_unchanged_one_reuses, test_a_master_without_identity_is_never_overwritten,
              test_voicevox_renders_once_then_reuses_and_a_new_engine_goes_to_compare,
              test_chatterbox_identity_covers_reference_audio_and_job_settings, test_checkpoint_is_named]:
        t()
        print("ok", t.__name__)
