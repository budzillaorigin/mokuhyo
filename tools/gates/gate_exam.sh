#!/usr/bin/env bash
# gate_exam (BRIEF §11.1, Phase 3 onward): for every language, the item banks validate strictly, the exam pack builds,
# and a full Reading and a full Listening form assemble at every ILR band with no repeats; scoring and ILR estimation
# are deterministic on fixtures; a timed form in Japanese and Spanish is taken end to end and estimates are recorded.
set -euo pipefail
cd "$(dirname "$0")/../.."
GRADLE="${GRADLE:-./gradlew}"
export MOKUHYO_REQUIRE_PACKS=1

echo "== gate_exam: validate --strict, every language"
(cd tools && uv run --locked --group content python -W ignore items/gen_dlpt.py validate --strict --language all)

echo "== gate_exam: build exam packs"
(cd tools && uv run --locked --group content python -W ignore packs/build_packs.py --language all)

echo "== gate_exam: assembly, scoring, end-to-end"
$GRADLE :shared:jvmTest --tests 'app.mokuhyo.exam.*' --tests 'app.mokuhyo.history.*' --rerun --console=plain
echo "gate_exam: PASS"
