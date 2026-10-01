"""Regenerates the catalog part of docs/MODELS.md from content/models/manifest.json (CLAUDE.md rule 13).

The "## Speaking eval" section (written by eval_speaking.py) and anything after it is preserved.

    uv run python models/models_doc.py [--check]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DOC = REPO / "docs" / "MODELS.md"


def render(manifest: dict) -> str:
    out = [
        "# Models",
        "",
        "Every weight file the app can download or bundle, with developer, country, license and source (CLAUDE.md rule 13).",
        "Generated from `content/models/manifest.json` by `tools/models/models_doc.py`; edit the manifest, not this table.",
        "",
        "Excluded by policy: all models from organizations based in the PRC and their derivatives (Qwen, DeepSeek, Yi,",
        "GLM, InternLM, MiniCPM, …; rule 13); Gemma and Llama (non-permissive terms; rule 6, owner may opt in).",
        "",
        "## Catalog",
        "",
        "| id | Tier | Role | Model | Developer (country) | License | Size | SHA-256 | Source |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for m in manifest["models"]:
        p = m["provenance"]
        size = sum(f["bytes"] for f in m["files"]) / 1e9
        tier = m.get("tier") or ("bundled" if m.get("bundled") else "STT")
        shas = ", ".join(f"`{f['sha256'][:12]}…`" for f in m["files"])
        out.append(
            f"| `{m['id']}` | {tier} | {m.get('role', '')} | {m['name']} | {p['developer']} ({p['country']}) | "
            f"{m['license']} | {size:.2f} GB | {shas} | {p['source']} |"
        )
    out += [
        "",
        "Weights are fetched from the GGUF/ggml conversions named in each entry's `provenance.conversion` "
        "(conversion only; the developer and license are the original model's). The app verifies every file's "
        "SHA-256 before use.",
        "",
        "## Drafting models (tools/, not shipped)",
        "",
        "The owner's Ollama server (BRIEF §7.1) runs the approved list in `tools/models/approved_models.json`: "
        "`mistral-small3.2:24b-instruct-2506-q8_0` (Mistral AI, France, Apache-2.0) drafts all content and is the "
        "reference grader; `gpt-oss:20b` (OpenAI, US, Apache-2.0) is the second-opinion checker.",
        "",
    ]
    return "\n".join(out)


def main() -> int:
    manifest = json.loads((REPO / "content/models/manifest.json").read_text(encoding="utf-8"))
    head = render(manifest)
    old = DOC.read_text(encoding="utf-8") if DOC.exists() else ""
    tail = old[old.index("## Speaking eval"):] if "## Speaking eval" in old else (
        "## Speaking eval\n\nNot run yet: `tools/models/eval_speaking.py` fills this table in Phase 4 "
        "(| model | lang | score |).\n"
    )
    new = head + "\n" + tail
    if "--check" in sys.argv:
        if new != old:
            print("docs/MODELS.md is out of date; run tools/models/models_doc.py")
            return 1
        return 0
    DOC.write_text(new, encoding="utf-8")
    print("wrote", DOC.relative_to(REPO))
    return 0


if __name__ == "__main__":
    sys.exit(main())
