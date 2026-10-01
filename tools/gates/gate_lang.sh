#!/usr/bin/env bash
# gate_lang (BRIEF §11.1, Phase 2 onward): LanguageModuleContractTest for all 11 languages with real packs
# (segmentation, lemma lookup, 20 dictionary hits per language), bundled-font coverage, and a TTS render of one
# sentence per language transcribed back by Whisper with ≥ 60 % token match. A language with no voice on this
# computer is logged as a fallback; ALLOWED_FALLBACK (default "ko", open decision 4) may fall back without failing.
set -euo pipefail
cd "$(dirname "$0")/../.."
GRADLE="${GRADLE:-./gradlew}"
LANGS="ja es fr de pt-BR ru zh-Hans ko ar fa id"
export MOKUHYO_REQUIRE_PACKS=1

echo "== gate_lang: packs"
(cd tools && uv run --locked --group content python -W ignore packs/build_tokenizer.py)
for l in $LANGS; do
  if [ ! -f "content/packs/$l/dictionary.sqlite" ] || [ -n "${REBUILD:-}" ]; then
    (cd tools && uv run --locked --group content python -W ignore packs/build_dictionary.py --language "$l")
  fi
done
(cd tools && uv run --locked python release/stage_fonts.py --cache-only)

echo "== gate_lang: contract tests (packs required)"
$GRADLE :shared:jvmTest --tests 'app.mokuhyo.lang.*' --tests 'app.mokuhyo.dictionary.*' :desktopApp:test --tests '*FontCoverageTest*' --rerun --console=plain

echo "== gate_lang: TTS → Whisper round trip"
(cd tools && uv run --locked python - <<'PY'
import json, sys
sys.path.insert(0, "release")
from stage_resources import fetch, CACHE
m = {x["id"]: x for x in json.load(open("../content/models/manifest.json"))["models"]}
f = m["whisper-small"]["files"][0]
fetch(f["url"], CACHE / f["name"], f["sha256"], f["bytes"])
PY
)
$GRADLE :desktopApp:run --args="--smoke-lang --whisper $PWD/tools/.cache/models/ggml-small.bin --allow-fallback ${ALLOWED_FALLBACK:-ko}" --console=plain -q 2>&1 | grep 'smoke-lang' | tee /tmp/gate_lang_tts.log
grep -q 'smoke-lang: OK' /tmp/gate_lang_tts.log
echo "gate_lang: PASS"
