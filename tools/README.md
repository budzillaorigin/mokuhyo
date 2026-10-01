# tools/

Python scripts that build everything in `content/` reproducibly, draft content through the owner's LLM endpoint,
and run the phase gates. Nothing in `content/` is hand-edited.

```bash
cd tools
uv sync                       # create .venv (once)
uv run ruff check .           # lint
uv run python run_tests.py    # every test_*.py
gates/gate_core.sh            # (from the repo root: tools/gates/gate_core.sh) the per-phase gate
```

Drafting endpoint: `LLM_ENDPOINT` / `LLM_MODEL` from the environment or `tools/.env` (defaults in `.env.example`,
BRIEF §7.1). Only models in `models/approved_models.json` may be used (CLAUDE.md rule 13).

| Folder | Purpose |
|---|---|
| `gates/` | `gate_*.sh` phase gates (BRIEF §11.1) and their checkers |
| `items/` | DLPT-style item drafting (`gen_dlpt.py`), validation, human review tool (`review.py`) |
| `packs/` | Pack builders (dictionary, tokenizer, exam), audio rendering, reader drafting |
| `models/` | Model evaluation (`eval_speaking.py`, Phase 4), approved drafting models |

The per-language cookbook (exact commands, expected minutes, adding a 12th language) lands with Phase 6.
