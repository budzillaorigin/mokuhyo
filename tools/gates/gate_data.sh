#!/usr/bin/env bash
# gate_data (BRIEF §11.1, Phase 5 onward): bundle export → import round-trip on fixtures (plain and encrypted) is
# lossless and merge-idempotent; the PDF report renders for a fixture learner in all 11 languages with no .notdef
# glyphs; fixture bundles written on other OSes import cleanly (CI passes MOKUHYO_FIXTURE_BUNDLES with the artifacts).
set -euo pipefail
cd "$(dirname "$0")/../.."
GRADLE="${GRADLE:-./gradlew}"
export MOKUHYO_REQUIRE_PACKS=1
(cd tools && uv run --locked python release/stage_fonts.py --cache-only)
$GRADLE :shared:jvmTest --tests 'app.mokuhyo.backup.*' --tests 'app.mokuhyo.report.*' --tests 'app.mokuhyo.history.*' --rerun --console=plain
echo "gate_data: PASS"
