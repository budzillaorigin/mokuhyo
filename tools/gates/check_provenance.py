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

# Owner-approved exceptions to rule 13 (CLAUDE.md, docs/DECISIONS.md). An entry is exempt only when its id is listed
# here AND it declares the same decision in "ownerException" and names the PRC-origin component in provenance; its
# text is then not scanned for the denylist. Nothing else is exempt.
OWNER_EXCEPTIONS = {
    "chatterbox-multilingual": "D-041",  # Resemble AI; its speech tokenizer is CosyVoice2's (Alibaba), owner 2026-10-04
}

FILES = [
    "content/models/manifest.json",
    "voices/manifest.json",
    "tools/.env.example",
    "tools/models/approved_models.json",
]


def _strings(v) -> list[str]:
    if isinstance(v, str):
        return [v]
    if isinstance(v, dict):
        return [x for val in v.values() for x in _strings(val)]
    if isinstance(v, list):
        return [x for val in v for x in _strings(val)]
    return []


def main() -> int:
    problems = []
    for rel in FILES:
        path = REPO / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        scan = text
        if rel.endswith("manifest.json"):
            for entry in json.loads(text).get("models", json.loads(text).get("voices", [])):
                eid, dec = entry.get("id"), entry.get("ownerException")
                if eid in OWNER_EXCEPTIONS:
                    if dec != OWNER_EXCEPTIONS[eid] or not (entry.get("provenance") or {}).get("prcComponent"):
                        problems.append(f"{rel}: '{eid}' must declare ownerException {OWNER_EXCEPTIONS[eid]} and provenance.prcComponent")
                    else:
                        for v in _strings(entry):
                            scan = scan.replace(v, "")  # exempt this entry's own strings only
                elif dec:
                    problems.append(f"{rel}: '{eid}' claims ownerException {dec}, which is not recorded in check_provenance.py")
        for m in DENYLIST.finditer(scan):
            line = scan.count("\n", 0, m.start()) + 1
            problems.append(f"{rel}:{line}: denylisted model family '{m.group(0)}'")
        if rel.endswith("manifest.json"):
            data = json.loads(text)
            for entry in data.get("models", data.get("voices", [])):
                prov = entry.get("provenance") or {}
                for field in ("developer", "country", "license", "source"):
                    if not prov.get(field):
                        problems.append(f"{rel}: '{entry.get('id')}' lacks provenance.{field}")
                if str(prov.get("country", "")).strip().lower() in {"china", "prc", "cn", "people's republic of china"} and \
                        entry.get("id") not in OWNER_EXCEPTIONS:
                    problems.append(f"{rel}: '{entry.get('id')}' is PRC-origin")
    for p in problems:
        print("PROVENANCE:", p)
    print(f"check_provenance: {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
