"""VOICEVOX renderer for Japanese exam listening (BRIEF_PHASE8 N-00(d); ported from Tsumugi's render path).

Runs next to a VOICEVOX engine (HTTP, default http://127.0.0.1:50021) on the render host. Reads the same jobs JSONL as
chatterbox_render.py, built with voices/voicevox_voices.json: each line's "ref" is "vv:<style id>" or "vv:<style id>:low"
(pitch and speed lowered slightly for a second male speaker, as Tsumugi did; D-170 there). Writes OUT_DIR/ja/<id>.wav
(24 kHz mono) with a sidecar identity (render_identity.py, QC 2026-10-30 V11): a clip is reused only when its identity
matches; an existing master rendered differently is kept and the new render goes to OUT_DIR/compare/<key>/. VOICEVOX
keeps its voice models inside the engine, so the engine and core versions stand in for a weight hash. Standard library only. VOICEVOX audio needs the character credit
("VOICEVOX:春日部つむぎ" …), which the importer records per clip and docs/LICENSES.md lists.

    python voicevox_render.py JOBS.jsonl OUT_DIR [--endpoint http://127.0.0.1:50021]
    python voicevox_render.py --print-identity [--endpoint ...]
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import urllib.parse
import urllib.request
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_identity

TIMEOUT = 120
ENGINE = "voicevox"


def post(url: str, body: bytes | None = None, ctype: str = "application/json") -> bytes:
    req = urllib.request.Request(url, data=body if body is not None else b"", method="POST", headers={"Content-Type": ctype})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return r.read()


def synth(endpoint: str, text: str, ref: str) -> tuple[bytes, int]:
    parts = ref.split(":")
    style = int(parts[1])
    q = json.loads(post(f"{endpoint}/audio_query?" + urllib.parse.urlencode({"text": text, "speaker": style})))
    if len(parts) > 2 and parts[2] == "low":
        q["pitchScale"] = q.get("pitchScale", 0.0) - 0.05
        q["speedScale"] = q.get("speedScale", 1.0) - 0.05
    q["outputSamplingRate"] = 24000
    wav = post(f"{endpoint}/synthesis?speaker={style}", json.dumps(q).encode("utf-8"))
    with wave.open(io.BytesIO(wav)) as w:
        return w.readframes(w.getnframes()), w.getframerate()


def get(url: str) -> str:
    with urllib.request.urlopen(url, timeout=10) as r:
        return r.read().decode()


def engine_identity(endpoint: str) -> dict:
    version = get(f"{endpoint}/version").strip('"')
    try:
        cores = json.loads(get(f"{endpoint}/core_versions"))
    except Exception:  # noqa: BLE001 - older engines have no core list
        cores = []
    return {"engine_version": version, "core_versions": cores}


def job_identity(job: dict, eng: dict) -> dict:
    return render_identity.identity(
        text=[[ln["text"], ln["ref"]] for ln in job["lines"]], engine=ENGINE, model_revision=eng["engine_version"],
        weight_sha256=render_identity.combined({"core_versions": json.dumps(eng["core_versions"])}),
        reference_sha256=render_identity.combined({ln["ref"]: ln["ref"] for ln in job["lines"]}),
        settings={"pause": job.get("pause", 0.6), "low": {"pitchScale": -0.05, "speedScale": -0.05}, "rate": 24000})


def render_jobs(jobs: list[dict], out: Path, synthesize, eng: dict) -> int:
    """[synthesize] is (text, ref) -> (pcm frames, rate)."""
    n = 0
    for job in jobs:
        ident = job_identity(job, eng)
        target, reuse = render_identity.plan(out, job["lang"], job["id"], ident)
        if reuse:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        t0 = time.time()
        pcm, rate = b"", 24000
        for line in job["lines"]:
            frames, rate = synthesize(line["text"], line["ref"])
            pcm += frames + b"\0\0" * int(rate * job.get("pause", 0.6))
        tmp = target.with_suffix(".part.wav")
        with wave.open(str(tmp), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(pcm)
        tmp.replace(target)
        render_identity.write(target, ident)
        n += 1
        print(f"render {job['lang']} {job['id']}: {len(pcm) / 2 / rate:.1f}s audio in {time.time() - t0:.1f}s", flush=True)
    return n


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("jobs", nargs="?")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--endpoint", default="http://127.0.0.1:50021")
    ap.add_argument("--print-identity", action="store_true", help="print the engine and core versions, then exit")
    a = ap.parse_args()
    eng = engine_identity(a.endpoint)
    print(f"voicevox engine {eng['engine_version']}", flush=True)
    if a.print_identity:
        print(json.dumps({"engine": ENGINE, **eng}, indent=1))
        return 0
    if not (a.jobs and a.out):
        ap.error("JOBS OUT_DIR are required")
    jobs = [json.loads(ln) for ln in Path(a.jobs).read_text(encoding="utf-8").splitlines() if ln.strip()]
    n = render_jobs(jobs, Path(a.out), lambda text, ref: synth(a.endpoint, text, ref), eng)
    print(f"voicevox_render: {n} rendered", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
