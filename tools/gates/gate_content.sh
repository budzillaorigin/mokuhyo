#!/usr/bin/env bash
# gate_content (BRIEF §11.1, Phase 6 onward): every bank validates strictly; packs build for all 11 languages; counts
# against the §5.3 targets are written to docs/CONTENT_STATUS.md with the drafting command for every shortfall; every
# listening passage has a clip or a run-time voice.
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "== gate_content: strict validation"
(cd tools && uv run --locked --group content python -W ignore items/gen_dlpt.py validate --language all --strict)
echo "== gate_content: packs"
(cd tools && uv run --locked --group content python -W ignore packs/build_packs.py --language all)
echo "== gate_content: counts"
(cd tools && uv run --locked --group content python -W ignore gates/check_content.py)
echo "== gate_content: current-events links (BRIEF_PHASE8 C-09)"
(cd tools && uv run --locked python terms/validate_feeds.py)
echo "== gate_content: culture cards, pragmatics, personas (BRIEF_PHASE8 C-05, C-07, C-08)"
(cd tools && uv run --locked python gates/check_culture.py)
echo "gate_content: PASS"
