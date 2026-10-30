"""Register audit of the fixed interviewer questions (QC 2026-10-30 V9). Standard library only.

Each language whose OPI profile sets how the interviewer addresses the candidate (T/V or a politeness level) has a rule
here: the informal forms that contradict it. Every bank question (the interviewer speaking to the candidate; role-play
openings are exempt, since a role may call for the informal register) is checked. A hit is a lead for native review
(AFCLC), not an automatic rewrite: the checks are word lists and endings, so they over- and under-report.

    python opi/register_audit.py [--packs content/packs] [--json]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# language -> (register the profile asks for, informal pattern that contradicts it)
RULES: dict[str, tuple[str, re.Pattern[str]]] = {
    "es": ("usted", re.compile(r"\b(tú|te|ti|tu|tus|contigo|eres|estás|tienes|llamas|vives|haces|puedes|quieres|sabes|"
                               r"trabajas|estudias|prefieres|piensas|crees|gusta(?=\s+a\s+ti))\b", re.IGNORECASE)),
    "fr": ("vous", re.compile(r"\b(tu|te|toi|ton|ta|tes)\b|\bt['’]", re.IGNORECASE)),
    "de": ("Sie", re.compile(r"\b(du|dich|dir|dein|deine|deinen|deinem|deiner|deines)\b")),
    "ru": ("вы", re.compile(r"(?<![\w])(ты|тебя|тебе|тобой|твой|твоя|твоё|твое|твои|твоего|твоей|твоих|твоим)(?![\w])", re.IGNORECASE)),
    "fa": ("شما", re.compile(r"(?<![\w‌])(تو|تُو)(?![\w‌])")),
    "id": ("Anda / Bapak / Ibu", re.compile(r"\b(kamu|kau|engkau)\b", re.IGNORECASE)),
    # Polite です/ます: a question with none of the polite forms is plain.
    "ja": ("です/ます", re.compile(r"^(?!.*(です|でし|ます|まし|ませ|でしょう|ください)).+$", re.DOTALL)),
    # 해요체/합쇼체: a sentence ending in 반말 (no 요/다/까/오 before the final punctuation).
    "ko": ("해요체", re.compile(r"[가-힣](?<![요다까오])[?.!]")),
}


def audit_pack(lang: str, pack: dict) -> list[dict[str, str]]:
    rule = RULES.get(lang)
    if rule is None:
        return []
    register, informal = rule
    hits = []
    for q in pack.get("questions", []):
        m = informal.search(q["prompt"])
        if m:
            hits.append({"language": lang, "id": q["id"], "prompt": q["prompt"], "expected": register,
                         "found": m.group(0)[:20], "source": q.get("source", ""), "verified": str(q.get("verified", False)).lower()})
    return hits


def audit(packs: Path) -> list[dict[str, str]]:
    out = []
    for lang in sorted(RULES):
        f = packs / lang / "opi.json"
        if f.is_file():
            out += audit_pack(lang, json.loads(f.read_text(encoding="utf-8")))
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--packs", type=Path, default=ROOT / "content" / "packs")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    hits = audit(a.packs)
    sys.stdout.reconfigure(encoding="utf-8")
    if a.json:
        print(json.dumps(hits, ensure_ascii=False, indent=1))
    else:
        for h in hits:
            print(f"{h['language']}\t{h['id']}\texpected {h['expected']}\tfound {h['found']!r}\t{h['prompt']}")
        print(f"register_audit: {len(hits)} questions for native review")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
