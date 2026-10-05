#!/usr/bin/env python3
"""Measures a Piper voice's speaker pitch (BRIEF_PHASE8 N-07) so voices/manifest records gender as measured.

  python voices/measure.py MODEL.onnx LANG [--speaker N ...]     # prints "speaker f0 gender" per speaker

Uses the locally built Piper (voices/build/<os>-<arch>/piper, or $PIPER_DIR) in --json-input mode, as the app does.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
import pitch

SENTENCES = {
    "es": "Buenos días. Esta mañana el puesto de observación reportó un dron pequeño sobre la puerta norte y la patrulla salió de inmediato.",
    "fr": "Bonjour. Ce matin, le poste d'observation a signalé un petit drone au-dessus de la porte nord et la patrouille est sortie aussitôt.",
    "de": "Guten Morgen. Heute früh hat der Beobachtungsposten eine kleine Drohne über dem Nordtor gemeldet, und die Streife ist sofort ausgerückt.",
    "pt-BR": "Bom dia. Hoje de manhã o posto de observação informou um drone pequeno sobre o portão norte e a patrulha saiu imediatamente.",
    "ru": "Доброе утро. Сегодня утром наблюдательный пост сообщил о небольшом дроне над северными воротами, и патруль сразу выехал.",
    "fa": "صبح بخیر. امروز صبح پست دیدبانی یک پهپاد کوچک را بالای در شمالی گزارش کرد و گشت فوراً بیرون رفت.",
}


def piper_dir() -> Path:
    if os.environ.get("PIPER_DIR"):
        return Path(os.environ["PIPER_DIR"])
    osid = {"Darwin": "macos", "Windows": "windows", "Linux": "linux"}[platform.system()]
    arch = "arm64" if platform.machine().lower() in ("arm64", "aarch64") else "x86_64"
    return REPO / "voices" / "build" / f"{osid}-{arch}" / "piper"


def measure(model: Path, lang: str, speakers: list[int | None]) -> list[tuple[int | None, float | None, str]]:
    pd = piper_dir()
    env = dict(os.environ, DYLD_LIBRARY_PATH=str(pd), LD_LIBRARY_PATH=str(pd))
    out = []
    with tempfile.TemporaryDirectory() as tmp:
        lines = []
        for s in speakers:
            req = {"text": SENTENCES[lang], "output_file": str(Path(tmp) / f"s{s}.wav")}
            if s is not None:
                req["speaker_id"] = s
            lines.append(json.dumps(req, ensure_ascii=False))
        subprocess.run([str(pd / "piper"), "--model", str(model), "--espeak_data", str(pd / "espeak-ng-data"), "--json-input"],
                       input="\n".join(lines) + "\n", text=True, capture_output=True, env=env, check=True)
        for s in speakers:
            f0 = pitch.median_f0(Path(tmp) / f"s{s}.wav")
            out.append((s, f0, pitch.gender(f0)))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model", type=Path)
    ap.add_argument("lang", choices=sorted(SENTENCES))
    ap.add_argument("--speaker", type=int, nargs="*")
    a = ap.parse_args()
    for s, f0, g in measure(a.model, a.lang, a.speaker or [None]):
        print(s, round(f0) if f0 else "-", g)
    return 0


if __name__ == "__main__":
    sys.exit(main())
