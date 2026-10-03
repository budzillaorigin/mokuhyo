#!/usr/bin/env python3
"""Rater calibration harness (BRIEF_PHASE8 N-04).

Rates instructor-rated practice recordings (tools/sources/calibration/<lang>/<id>.json: transcript, humanRating,
rationale, rater — the owner's input) with the app's own `opi_rate` prompt on each tier's model, and writes
content/models/calibration.json (the table the app reads for the confidence band) and the agreement table in
docs/MODELS.md. Without owner samples it runs on tools/models/calibration_fixtures/ and marks every cell `fixture`,
so the app keeps saying "uncalibrated".

  uv run python models/calibrate.py [--endpoint URL] [--tiers A=phi4-mini:3.8b,B=...] [--fixtures]
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import llm  # noqa: E402

REAL = TOOLS / "sources" / "calibration"
FIXTURES = TOOLS / "models" / "calibration_fixtures"
OUT = REPO / "content" / "models" / "calibration.json"
# Tier → the model the app runs at that tier, as served by the §7.1 Ollama server (approved_models.json).
DEFAULT_TIERS = "A=phi4-mini:3.8b,B=hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M,C=mistral-nemo:12b,D=mistral-small3.2:24b-instruct-2506-q8_0"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--endpoint", default=llm.setting("LLM_ENDPOINT"))
    ap.add_argument("--tiers", default=DEFAULT_TIERS)
    ap.add_argument("--fixtures", action="store_true", help="use the fixture samples even when real ones exist")
    a = ap.parse_args()
    for pair in a.tiers.split(","):
        llm.check_approved(pair.split("=", 1)[1])
    real = REAL.is_dir() and any(REAL.rglob("*.json"))
    samples = FIXTURES if a.fixtures or not real else REAL
    fixture = samples == FIXTURES
    if fixture:
        print("calibrate: no instructor-rated samples in tools/sources/calibration/ — running on fixtures (the app stays 'uncalibrated')")
    tmp = REPO / "build" / "calibration-report.json"
    gradle = str(REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    args = f"--calibrate --samples {samples} --endpoint {a.endpoint} --tiers {a.tiers} --out {tmp}" + (" --fixture" if fixture else "")
    r = subprocess.run([gradle, ":desktopApp:run", f"--args={args}", "--console=plain", "-q"], cwd=REPO, capture_output=True, text=True, check=False)
    print("\n".join(ln for ln in r.stdout.splitlines() if ln.startswith("calibrate")))
    if r.returncode or not tmp.exists():
        print(r.stderr[-2000:], file=sys.stderr)
        return 1
    report = json.loads(tmp.read_text(encoding="utf-8"))
    OUT.write_text(json.dumps(report["table"], indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    subprocess.run([sys.executable, str(TOOLS / "models" / "models_doc.py")], check=True)
    print(f"calibrate: wrote {OUT.relative_to(REPO)} and the docs/MODELS.md agreement table")
    return 0


if __name__ == "__main__":
    sys.exit(main())
