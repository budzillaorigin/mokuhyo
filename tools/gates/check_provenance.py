"""Model provenance gate (CLAUDE.md rule 13): no PRC-origin model anywhere in the manifests or drafting defaults.

Scans content/models/manifest.json, voices/manifest.json, tools/.env.example and tools/models/approved_models.json
for denylisted model families, and checks every manifest entry carries a provenance block.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# Families developed or released by organizations based in the PRC, and their derivatives (rule 13).
DENYLIST = re.compile(
    r"qwen|deepseek|\byi[-_:.]|01-ai|chatglm|\bglm[-_:.\d]|internlm|minicpm|baichuan|cosyvoice|gpt-?sovits|melo-?tts|"
    r"sensevoice|paraformer|funasr|fish-?speech|f5-?tts|hunyuan|ernie|moonshot|kimi|doubao|step-?\d|seed-?tts|"
    r"spark-?tts|index-?tts|megatts|chattts|telechat|skywork|aquila|xverse|orion-?\d|\bmoss\b|pangu|codegeex",
    re.IGNORECASE,
)

FILES = [
    "content/models/manifest.json",
    "voices/manifest.json",
    "tools/.env.example",
    "tools/models/approved_models.json",
]


def main() -> int:
    problems = []
    for rel in FILES:
        path = REPO / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        for m in DENYLIST.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            problems.append(f"{rel}:{line}: denylisted model family '{m.group(0)}'")
        if rel.endswith("manifest.json"):
            data = json.loads(text)
            for entry in data.get("models", data.get("voices", [])):
                prov = entry.get("provenance") or {}
                for field in ("developer", "country", "license", "source"):
                    if not prov.get(field):
                        problems.append(f"{rel}: '{entry.get('id')}' lacks provenance.{field}")
                if str(prov.get("country", "")).strip().lower() in {"china", "prc", "cn", "people's republic of china"}:
                    problems.append(f"{rel}: '{entry.get('id')}' is PRC-origin")
    for p in problems:
        print("PROVENANCE:", p)
    print(f"check_provenance: {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
