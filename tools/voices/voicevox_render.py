"""VOICEVOX renderer for Japanese exam listening (BRIEF_PHASE8 N-00(d); ported from Tsumugi's render path).

Runs next to a VOICEVOX engine (HTTP, default http://127.0.0.1:50021) on the render host. Reads the same jobs JSONL as
chatterbox_render.py, built with voices/voicevox_voices.json: each line's "ref" is "vv:<style id>" or "vv:<style id>:low"
(pitch and speed lowered slightly for a second male speaker, as Tsumugi did; D-170 there). Writes OUT_DIR/ja/<id>.wav
(24 kHz mono), skipping finished clips. Standard library only. VOICEVOX audio needs the character credit
("VOICEVOX:春日部つむぎ" …), which the importer records per clip and docs/LICENSES.md lists.

    python voicevox_render.py JOBS.jsonl OUT_DIR [--endpoint http://127.0.0.1:50021]
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

TIMEOUT = 120


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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("jobs")
    ap.add_argument("out")
    ap.add_argument("--endpoint", default="http://127.0.0.1:50021")
    a = ap.parse_args()
    version = urllib.request.urlopen(f"{a.endpoint}/version", timeout=10).read().decode().strip('"')
    print(f"voicevox engine {version}", flush=True)
    n = 0
    for ln in Path(a.jobs).read_text(encoding="utf-8").splitlines():
        if not ln.strip():
            continue
        job = json.loads(ln)
        target = Path(a.out) / job["lang"] / f"{job['id']}.wav"
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        t0 = time.time()
        pcm, rate = b"", 24000
        for line in job["lines"]:
            frames, rate = synth(a.endpoint, line["text"], line["ref"])
            pcm += frames + b"\0\0" * int(rate * job.get("pause", 0.6))
        tmp = target.with_suffix(".part.wav")
        with wave.open(str(tmp), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(pcm)
        tmp.replace(target)
        n += 1
        print(f"render ja {job['id']}: {len(pcm) / 2 / rate:.1f}s audio in {time.time() - t0:.1f}s", flush=True)
    print(f"voicevox_render: {n} rendered", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
