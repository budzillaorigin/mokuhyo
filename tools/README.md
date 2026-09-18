# tools/

Python scripts that build everything in `content/` reproducibly. Nothing in `content/` is hand-edited.

```bash
cd tools
uv sync                      # create .venv and install deps (once)
uv run ruff check .          # lint
```

| Folder | Purpose | Arrives in |
|---|---|---|
| `packs/` | Dictionary, strokes, kanji path, sentences, grammar pack builders | Phase 1–3 |
| `items/` | JLPT/DLPT item generation, validation, human review tool | Phase 3, 7 |
| `models/` | llama.cpp/whisper.cpp builds, Japanese model eval, handwriting classifier training | Phase 4, 6 |

Full command list: BRIEF.md Appendix B. Pack formats: `docs/CONTENT_PACKS.md`.
