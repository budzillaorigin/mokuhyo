"""Chatterbox Multilingual renderer for the GPU host (BRIEF_PHASE8 N-00b; owner exception D-041 to rule 13).

Runs on the render host (the RTX 5090), not in the app. Reads a jobs JSONL written on the Mac by
tools/packs/chatterbox_jobs.py and writes one 24 kHz mono WAV per job into OUT_DIR/<lang>/<id>.wav with a sidecar
identity (render_identity.py, QC 2026-10-30 V11). A WAV is reused only when its whole identity matches; an existing master
rendered differently is never overwritten — the new render goes to OUT_DIR/compare/<key>/. The checkpoint named in
docs/MODELS.md (CHECKPOINT) is loaded explicitly; the run stops if the installed package can't be shown to load it. A job: {"id", "lang", "lines": [{"text", "ref"}], "pause": seconds}; "ref" is a reference clip in
REF_DIR (a donated TTS voice, see voices/chatterbox_voices.json) or "" for Chatterbox's built-in voice. Chinese text
arrives already segmented with ICU (spaces between words), so Chatterbox's PRC-origin pkuseg segmenter is never used.

    python chatterbox_render.py JOBS.jsonl OUT_DIR REF_DIR [--limit N] [--shard i/n] [--seed N]
    python chatterbox_render.py --print-identity [--checkpoint NAME] [--hf-revision REV]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_identity
from textsplit import chunks

ENGINE = "chatterbox-multilingual"
CHECKPOINT = "t3_mtl23ls_v3"  # docs/MODELS.md
REPO_ID = "ResembleAI/chatterbox"
PACKAGES = ["chatterbox-tts", "torch", "torchaudio", "transformers", "huggingface_hub", "numpy", "soundfile", "librosa"]

LANG_IDS = {"ja": "ja", "es": "es", "fr": "fr", "de": "de", "pt-BR": "pt", "ru": "ru", "zh-Hans": "zh", "ko": "ko", "ar": "ar",
            "fa": None, "id": "ms"}  # fa: not supported by the model; id: Malay is the closest (logged listening test)
# Indonesian through the Malay model is an approximation, not an equivalence: its clips need a native-speaker listening
# check before they are trusted (QC 2026-10-19).


def yield_to_other_work() -> None:
    """Below-normal priority on Windows (BELOW_NORMAL_PRIORITY_CLASS), nice 10 elsewhere: the render host is the owner's
    machine and their own work always comes first; the render only takes spare capacity."""
    try:
        if sys.platform == "win32":
            import ctypes
            from ctypes import wintypes
            k32 = ctypes.windll.kernel32
            k32.GetCurrentProcess.restype = wintypes.HANDLE  # a pointer-sized pseudo-handle; the int default truncates it
            k32.SetPriorityClass.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            if not k32.SetPriorityClass(k32.GetCurrentProcess(), 0x00004000):
                print(f"note: couldn't lower priority (error {ctypes.get_last_error()})", flush=True)
        else:
            import os
            os.nice(10)
    except Exception as e:  # noqa: BLE001 - lowering our own priority is best effort
        print(f"note: couldn't lower priority ({e})", flush=True)


def load_pinned(device: str, checkpoint: str, revision: str) -> tuple[object, Path]:
    """The model with [checkpoint] at HF [revision], or SystemExit. The installed package's from_local names one T3
    file; if it isn't [checkpoint] the run stops instead of rendering with a different model than the one recorded."""
    import inspect

    from chatterbox.mtl_tts import ChatterboxMultilingualTTS
    from huggingface_hub import snapshot_download

    loads = sorted(set(re.findall(r"t3_[a-z0-9_]+(?=\.safetensors)", inspect.getsource(ChatterboxMultilingualTTS.from_local))))
    if loads != [checkpoint]:
        raise SystemExit(f"chatterbox_render: the installed chatterbox-tts loads {loads}, not {checkpoint} (docs/MODELS.md). "
                         f"Install a version that loads {checkpoint}, or pass --checkpoint {loads[0] if loads else '?'} to render "
                         "with that checkpoint deliberately (it is recorded in every clip's identity).")
    ckpt = Path(snapshot_download(repo_id=REPO_ID, repo_type="model", revision=revision,
                                  allow_patterns=["ve.pt", f"{checkpoint}.safetensors", "s3gen.pt", "grapheme_mtl_merged_expanded_v1.json",
                                                  "conds.pt", "Cangjie5_TC.json"]))
    return ChatterboxMultilingualTTS.from_local(ckpt, device), ckpt


def weight_hashes(ckpt: Path, cache_file: Path | None) -> dict[str, str]:
    cache = json.loads(cache_file.read_text()) if cache_file and cache_file.is_file() else {}
    out = {f.name: render_identity.file_sha256(f, cache) for f in sorted(ckpt.iterdir()) if f.is_file()}
    if cache_file:
        cache_file.write_text(json.dumps(cache))
    return out


def job_identity(job: dict, model_revision: str, weights: str, refs: Path, settings: dict) -> dict:
    ref_hashes = {ln.get("ref") or "builtin": render_identity.file_sha256(refs / ln["ref"]) if ln.get("ref") else "builtin"
                  for ln in job["lines"]}
    return render_identity.identity(
        text=[[ln["text"], ln.get("ref", "")] for ln in job["lines"]], engine=ENGINE, model_revision=model_revision,
        weight_sha256=weights, reference_sha256=render_identity.combined(ref_hashes),
        settings={**settings, "pause": job.get("pause", 0.6), "chunk_gap": 0.25, "language_id": LANG_IDS.get(job["lang"])})


def render_jobs(jobs: list[dict], out: Path, refs: Path, generate, sr: int, model_revision: str, weights: str,
                settings: dict, limit: int = 0, on_done=None) -> int:
    """Renders what isn't already rendered with the same identity; returns the number rendered. [generate] is
    (text, language_id, ref path or None) -> float32 mono samples."""
    import numpy as np
    import soundfile as sf

    done = 0
    for job in jobs:
        lid = LANG_IDS.get(job["lang"])
        if lid is None:
            continue
        ident = job_identity(job, model_revision, weights, refs, settings)
        target, reuse = render_identity.plan(out, job["lang"], job["id"], ident)
        if reuse:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        t0 = time.time()
        pieces = []
        gap = np.zeros(int(sr * job.get("pause", 0.6)), dtype=np.float32)
        short_gap = np.zeros(int(sr * 0.25), dtype=np.float32)
        for ln in job["lines"]:
            ref = refs / ln["ref"] if ln.get("ref") else None
            parts = chunks(ln["text"], lang=job["lang"])
            for k, piece in enumerate(parts):
                pieces += [np.asarray(generate(piece, lid, ref), dtype=np.float32), short_gap if k < len(parts) - 1 else gap]
        tmp = target.with_suffix(".part.wav")
        sf.write(tmp, np.concatenate(pieces), sr, subtype="PCM_16")
        tmp.replace(target)
        render_identity.write(target, ident)
        done += 1
        where = "" if target.parent == out / job["lang"] else f" (identity changed: {target.parent.parent})"
        print(f"render {job['lang']} {job['id']}: {sum(len(p) for p in pieces) / sr:.1f}s audio in {time.time() - t0:.1f}s{where}", flush=True)
        del pieces
        if on_done:
            on_done()
        if limit and done >= limit:
            break
    return done


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("jobs", nargs="?")
    ap.add_argument("out", nargs="?")
    ap.add_argument("refs", nargs="?")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--shard", default="0/1", help="i/n: render every n-th job starting at i (run n processes on one GPU)")
    ap.add_argument("--max-jobs", type=int, default=0, help="exit after this many clips (Chatterbox leaks memory; a fresh process frees it)")
    ap.add_argument("--threads", type=int, default=4, help="CPU threads per process; parallel shards oversubscribe the CPU otherwise")
    ap.add_argument("--checkpoint", default=CHECKPOINT, help="T3 checkpoint the installed package must load (docs/MODELS.md)")
    ap.add_argument("--hf-revision", default="main", help="Hugging Face revision of ResembleAI/chatterbox; the resolved files are hashed")
    ap.add_argument("--seed", type=int, default=None, help="torch seed per clip (recorded in the identity)")
    ap.add_argument("--print-identity", action="store_true", help="print package versions and weight hashes, then exit")
    a = ap.parse_args()
    import torch

    device = "cuda" if torch.cuda.is_available() else "cpu"
    if a.print_identity:
        model, ckpt = load_pinned(device, a.checkpoint, a.hf_revision)
        print(json.dumps({"engine": ENGINE, "checkpoint": a.checkpoint, "hf_revision": a.hf_revision, "snapshot": ckpt.name,
                          "packages": render_identity.package_versions(PACKAGES), "device": device,
                          "cuda": torch.version.cuda, "weights": weight_hashes(ckpt, None)}, indent=1))
        return 0
    if not (a.jobs and a.out and a.refs):
        ap.error("JOBS OUT_DIR REF_DIR are required")
    yield_to_other_work()
    out, refs = Path(a.out), Path(a.refs)
    torch.set_num_threads(a.threads)  # PyTorch defaults to one thread per core in every process
    model, ckpt = load_pinned(device, a.checkpoint, a.hf_revision)
    out.mkdir(parents=True, exist_ok=True)
    weights = render_identity.combined(weight_hashes(ckpt, out / ".weight-hashes.json"))
    versions = render_identity.package_versions(["chatterbox-tts", "torch"])
    model_revision = f"{REPO_ID}@{ckpt.name}/{a.checkpoint} chatterbox-tts {versions['chatterbox-tts']} torch {versions['torch']}"
    jobs = [json.loads(ln) for ln in Path(a.jobs).read_text(encoding="utf-8").splitlines() if ln.strip()]
    i, n = (int(x) for x in a.shard.split("/"))

    def generate(text: str, lid: str, ref: Path | None):
        if a.seed is not None:
            torch.manual_seed(a.seed)
        kw = {"audio_prompt_path": str(ref)} if ref else {}
        return model.generate(text, language_id=lid, **kw).squeeze(0).cpu().numpy()

    def free() -> None:
        if device == "cuda":
            torch.cuda.empty_cache()

    limit = min(x for x in (a.limit, a.max_jobs) if x) if (a.limit or a.max_jobs) else 0
    done = render_jobs(jobs[i::n], out, refs, generate, model.sr, model_revision, weights, {"seed": a.seed}, limit, free)
    print(f"chatterbox_render: {done} rendered", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
