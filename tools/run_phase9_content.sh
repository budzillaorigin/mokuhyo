#!/usr/bin/env bash
# Phase 9 drafting on the §7.1 server, one job at a time (logs in tools/logs/). Resumable: each step skips done work.
set -u
cd "$(dirname "$0")"
step() { echo "== $(date '+%H:%M:%S') $*"; }
step "authentic-format reading (N-08)"; uv run --group content python items/gen_dlpt.py formats --language all --per-format 6
step "exemplar answers (N-05)"; uv run --group content python exemplars/build_exemplars.py --language all
step "interviewer coherence (N-00b)"; uv run --group content python models/eval_speaking.py --coherence --languages all --interviews 1 --no-pull \
  --models "hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M,mistral-nemo:12b,mistral-small3.2:24b-instruct-2506-q8_0"
step "done"
