"""Chatterbox Multilingual renderer for the GPU host (BRIEF_PHASE8 N-00b; owner exception D-041 to rule 13).

Runs on the render host (the RTX 5090), not in the app. Reads a jobs JSONL written on the Mac by
tools/packs/chatterbox_jobs.py and writes one 24 kHz mono WAV per job into OUT_DIR/<lang>/<id>.wav, skipping jobs whose
WAV already exists. A job: {"id", "lang", "lines": [{"text", "ref"}], "pause": seconds}; "ref" is a reference clip in
REF_DIR (a donated TTS voice, see voices/chatterbox_voices.json) or "" for Chatterbox's built-in voice. Chinese text
arrives already segmented with ICU (spaces between words), so Chatterbox's PRC-origin pkuseg segmenter is never used.

    python chatterbox_render.py JOBS.jsonl OUT_DIR REF_DIR [--limit N] [--shard i/n]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from chatterbox.mtl_tts import ChatterboxMultilingualTTS

LANG_IDS = {"ja": "ja", "es": "es", "fr": "fr", "de": "de", "pt-BR": "pt", "ru": "ru", "zh-Hans": "zh", "ko": "ko", "ar": "ar",
            "fa": None, "id": "ms"}  # fa: not supported by the model; id: Malay is the closest (logged listening test)


def yield_to_other_work() -> None:
    """Below-normal priority on Windows (BELOW_NORMAL_PRIORITY_CLASS), nice 10 elsewhere: the render host is the owner's
    machine and their own work always comes first; the render only takes spare capacity."""
    try:
        if sys.platform == "win32":
            import ctypes
            ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x00004000)
        else:
            import os
            os.nice(10)
    except Exception as e:  # noqa: BLE001 - lowering our own priority is best effort
        print(f"note: couldn't lower priority ({e})", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("jobs")
    ap.add_argument("out")
    ap.add_argument("refs")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--shard", default="0/1", help="i/n: render every n-th job starting at i (run n processes on one GPU)")
    ap.add_argument("--threads", type=int, default=4, help="CPU threads per process; parallel shards oversubscribe the CPU otherwise")
    a = ap.parse_args()
    yield_to_other_work()
    out, refs = Path(a.out), Path(a.refs)
    torch.set_num_threads(a.threads)  # PyTorch defaults to one thread per core in every process
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = ChatterboxMultilingualTTS.from_pretrained(device=device)
    sr = model.sr
    jobs = [json.loads(ln) for ln in Path(a.jobs).read_text(encoding="utf-8").splitlines() if ln.strip()]
    i, n = (int(x) for x in a.shard.split("/"))
    jobs = jobs[i::n]
    done = 0
    for job in jobs:
        lid = LANG_IDS.get(job["lang"])
        target = out / job["lang"] / f"{job['id']}.wav"
        if lid is None or target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        t0 = time.time()
        pieces = []
        gap = np.zeros(int(sr * job.get("pause", 0.6)), dtype=np.float32)
        for ln in job["lines"]:
            kw = {"audio_prompt_path": str(refs / ln["ref"])} if ln.get("ref") else {}
            wav = model.generate(ln["text"], language_id=lid, **kw)
            pieces += [wav.squeeze(0).cpu().numpy().astype(np.float32), gap]
        tmp = target.with_suffix(".part.wav")
        sf.write(tmp, np.concatenate(pieces), sr, subtype="PCM_16")
        tmp.replace(target)
        done += 1
        print(f"render {job['lang']} {job['id']}: {sum(len(p) for p in pieces) / sr:.1f}s audio in {time.time() - t0:.1f}s", flush=True)
        if a.limit and done >= a.limit:
            break
    print(f"chatterbox_render: {done} rendered", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
