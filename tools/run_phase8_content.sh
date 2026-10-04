#!/usr/bin/env bash
# Phase 8 content drafting, one job at a time on the §7.1 server (logs in tools/logs/). Resumable: every step skips done work.
set -u
cd "$(dirname "$0")"
step() { echo "== $(date '+%H:%M:%S') $*"; }
step "align retry (missing proposals)"; uv run python terms/align_terms.py all
step "align attempts 2-3: glossary retrieval and parallel editions (D-033)"; uv run python terms/align_terms.py retrieve
step "culture cards (C-05)"; uv run --group content python culture/build_cards.py all
step "pragmatics (C-07)"; uv run --group content python pragmatics/build_pragmatics.py --language all
step "personas (C-08)"; uv run --group content python personas/build_personas.py --language all
step "track examples + extras (C-03)"; uv run --group content python tracks/build_track.py all
step "track passages (C-03)"; uv run --group content python items/gen_dlpt.py fill --language all --skill both --track cuas-base-defense --rounds 3
step "inference items (C-07)"; uv run --group content python items/gen_dlpt.py fill --language all --skill listening --track pragmatics --rounds 3
step "done"
