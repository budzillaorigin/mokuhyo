#!/usr/bin/env bash
# gate_terms (BRIEF_PHASE8 C-00; CLAUDE.md rule 6): source files match SOURCES.json, seed and alignment files validate,
# and every shipped string from the term/culture/content pipelines passes tools/terms/overlap_check.py against the
# verbatim_ok = false sources. Needs the PDFs in tools/sources/ (fetch_sources.py) and poppler's pdftotext.
# On a machine without the PDFs: MOKUHYO_NO_SOURCES=1 runs the validators only and says the overlap check was skipped.
set -euo pipefail
cd "$(dirname "$0")/../.."
args=()
[ "${MOKUHYO_NO_SOURCES:-}" = 1 ] && args+=(--no-sources)
(cd tools && uv run --locked python -W ignore terms/gate_terms.py ${args[@]+"${args[@]}"})
