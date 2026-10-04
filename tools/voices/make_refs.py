#!/usr/bin/env python3
"""Reference clips for Chatterbox voices (BRIEF_PHASE8 N-00; owner choice 2026-10-04: donated TTS voices only).

Chatterbox has one built-in voice; every other voice is conditioned on a reference clip. These references come only from
speakers who released their own voice for speech synthesis under an open license (the Piper voices in voices/manifest.json
whose datasets were recorded for TTS) — never LibriVox/MLS readers or anyone else. Each reference is ~12 s of that voice
reading a neutral sentence in its own language, rendered with the bundled Piper model. Writes voices/chatterbox_refs/<id>.wav.

    PIPER_DIR=… python voices/make_refs.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
import measure

OUT = REPO / "voices" / "chatterbox_refs"
TEXT = {
    "es": "Buenos días a todos. Hoy repasamos el plan de seguridad de la base, los horarios de las patrullas y los puntos de control. "
          "Si tienen preguntas, anótenlas y las veremos al final de la reunión.",
    "fr": "Bonjour à tous. Aujourd'hui, nous revoyons le plan de sécurité de la base, les horaires des patrouilles et les points de "
          "contrôle. Si vous avez des questions, notez-les et nous y reviendrons à la fin de la réunion.",
    "de": "Guten Morgen zusammen. Heute gehen wir den Sicherheitsplan des Stützpunkts durch, die Zeiten der Streifen und die "
          "Kontrollpunkte. Wenn Sie Fragen haben, schreiben Sie sie auf, wir besprechen sie am Ende der Besprechung.",
    "ru": "Доброе утро всем. Сегодня мы рассмотрим план безопасности базы, расписание патрулей и контрольные пункты. Если у вас "
          "есть вопросы, запишите их, и мы обсудим их в конце совещания.",
    "fa": "صبح همگی بخیر. امروز طرح امنیتی پایگاه، زمان‌بندی گشت‌ها و ایستگاه‌های بازرسی را مرور می‌کنیم. اگر سؤالی دارید، "
          "یادداشت کنید تا در پایان جلسه درباره‌اش صحبت کنیم.",
    "pt-BR": "Bom dia a todos. Hoje vamos revisar o plano de segurança da base, os horários das patrulhas e os postos de controle. "
             "Se tiverem perguntas, anotem e vamos conversar no final da reunião.",
}
# Donated TTS voices (recorded for speech synthesis, open license); MLS / LibriVox readers are deliberately absent.
DONATED = ["es_ES-davefx-medium", "es_ES-sharvard-medium", "es_ES-sharvard-medium-m", "es_MX-claude-high", "fr_FR-siwis-medium",
           "fr_FR-upmc-medium", "fr_FR-upmc-medium-f", "de_DE-thorsten-medium", "ru_RU-denis-medium", "ru_RU-dmitri-medium",
           "fa_IR-amir-medium", "fa_IR-ganji-medium", "pt_BR-faber-medium", "pt_BR-cadu-medium", "pt_PT-tugao-medium"]


def main() -> int:
    manifest = {v["id"]: v for v in json.loads((REPO / "voices" / "manifest.json").read_text(encoding="utf-8"))["voices"]}
    models = Path(os.environ.get("VOICE_MODELS", REPO / "voices" / "models"))
    pd = measure.piper_dir()
    env = dict(os.environ, DYLD_LIBRARY_PATH=str(pd), LD_LIBRARY_PATH=str(pd))
    OUT.mkdir(parents=True, exist_ok=True)
    for vid in DONATED:
        v = manifest[vid]
        stem = v.get("model", vid)
        req = {"text": TEXT[v["language"]], "output_file": str(OUT / f"{vid}.wav")}
        if "speaker" in v:
            req["speaker_id"] = v["speaker"]
        with tempfile.TemporaryDirectory():
            subprocess.run([str(pd / "piper"), "--model", str(models / stem / f"{stem}.onnx"), "--espeak_data", str(pd / "espeak-ng-data"),
                            "--json-input"], input=json.dumps(req, ensure_ascii=False) + "\n", text=True, capture_output=True, env=env, check=True)
        print(f"ref {vid}: {(OUT / f'{vid}.wav').stat().st_size // 1024} KB ({v['gender']}, {v['provenance']['datasetLicense']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
