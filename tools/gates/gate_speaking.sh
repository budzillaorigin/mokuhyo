#!/usr/bin/env bash
# gate_speaking (BRIEF §11.1, Phase 4 onward):
#  1. OPI sessions complete all five phases against a mock model and the scripted fallback for every language; rating
#     JSON validates 100 % on golden fixtures (Kotlin tests, packs required).
#  2. eval_speaking.py runs against the Tier B model (EuroLLM-9B, pulled into the §7.1 Ollama server) for ja, es, ar
#     and writes docs/MODELS.md.
#  3. (Phase 4) a full OPI test in two languages on the Tier B model through the embedded engine, voice in and out,
#     rated with evidence, saved and replayable: Mokuhyo --smoke-opi-full.
#  SKIP_EVAL=1 / SKIP_FULL=1 skip 2 / 3 (CI has neither the GPU server nor the 5.6 GB model).
set -euo pipefail
cd "$(dirname "$0")/../.."
GRADLE="${GRADLE:-./gradlew}"
export MOKUHYO_REQUIRE_PACKS=1

echo "== gate_speaking: packs"
(cd tools && uv run --locked --group content python -W ignore packs/build_packs.py --language all)

echo "== gate_speaking: sessions, mock model, scripted fallback, golden ratings"
$GRADLE :shared:jvmTest --tests 'app.mokuhyo.opi.*' --tests 'app.mokuhyo.ai.*' --rerun --console=plain

if [ -z "${SKIP_EVAL:-}" ]; then
  echo "== gate_speaking: eval_speaking (Tier B: ja, es, ar)"
  (cd tools && uv run --locked --group content python -W ignore models/eval_speaking.py --languages ja,es,ar --models tier-b)
fi

if [ -z "${SKIP_FULL:-}" ]; then
  echo "== gate_speaking: full OPI test on Tier B (es, ja)"
  (cd tools && uv run --locked python - <<'PY'
import json, sys
sys.path.insert(0, "release")
from stage_resources import fetch, CACHE
m = {x["id"]: x for x in json.load(open("../content/models/manifest.json"))["models"]}
for mid in ("eurollm-9b-instruct-q4km", "whisper-small"):
    f = m[mid]["files"][0]
    fetch(f["url"], CACHE / f["name"], f["sha256"], f["bytes"])
PY
  )
  for lang in es ja; do
    $GRADLE :desktopApp:run --console=plain -q --args="--smoke-opi-full --language $lang --model $PWD/tools/.cache/models/EuroLLM-9B-Instruct-Q4_K_M.gguf --model-id eurollm-9b-instruct-q4km --whisper $PWD/tools/.cache/models/ggml-small.bin" 2>&1 | grep 'smoke-opi-full' | tee /tmp/gate_speaking_$lang.log
    grep -q 'smoke-opi-full: OK' /tmp/gate_speaking_$lang.log
  done
fi
echo "gate_speaking: PASS"
