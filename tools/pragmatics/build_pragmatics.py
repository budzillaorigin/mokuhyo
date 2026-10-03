#!/usr/bin/env python3
"""Pragmatics pack per language (BRIEF_PHASE8 C-07, §B.4.4): tools/pragmatics/<lang>.json → packs/<lang>/pragmatics.json.

Seven topics — address and rank etiquette, indirectness and refusals, apology and thanks, safe and taboo small talk,
disagreement in meetings, hospitality obligations, gestures and silence — with 2–3 entries each: a rule (English,
own wording) and examples {situation (English), say and dontSay (target language), why (English)}.

Informed by the AFCLC field guide chapter for the partner country where there is one (machine_extract_ok = true;
cited by chapter and page); Germany and France have no guide (general knowledge, badged). Every string passes
tools/terms/overlap_check.py; failures are redrafted.

  uv run --group content python pragmatics/build_pragmatics.py --language all
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "terms"))
sys.path.insert(0, str(TOOLS / "culture"))

from align_terms import NAMES, PARTNER
from build_cards import GUIDE, chapter_text, chapters, title_case

import langtext
import llm

sys.path.insert(0, str(TOOLS / "items"))
import review_state

import sources as S

TOPICS = {
    "address": ("Address and rank etiquette", ["LANGUAGE AND COMMUNICATION", "POLITICAL AND SOCIAL RELATIONS"]),
    "refusal": ("Indirectness and refusals", ["LANGUAGE AND COMMUNICATION"]),
    "apology": ("Apology and thanks", ["LANGUAGE AND COMMUNICATION"]),
    "small_talk": ("Safe and taboo small talk", ["LANGUAGE AND COMMUNICATION", "RELIGION AND SPIRITUALITY", "FAMILY AND KINSHIP"]),
    "disagreement": ("Disagreement in meetings", ["LANGUAGE AND COMMUNICATION", "POLITICAL AND SOCIAL RELATIONS"]),
    "hospitality": ("Hospitality obligations", ["SUSTENANCE AND HEALTH", "TIME AND SPACE"]),
    "nonverbal": ("Gestures and silence", ["LANGUAGE AND COMMUNICATION", "TIME AND SPACE"]),
}
WORK = HERE / ".work"
SCHEMA = {"type": "object", "properties": {"entries": {"type": "array", "items": {"type": "object", "properties": {
    "rule": {"type": "string"}, "page": {"type": "integer"},
    "examples": {"type": "array", "items": {"type": "object", "properties": {
        "situation": {"type": "string"}, "say": {"type": "string"}, "dontSay": {"type": "string"}, "why": {"type": "string"}},
        "required": ["situation", "say", "dontSay", "why"]}}},
    "required": ["rule", "page", "examples"]}}}, "required": ["entries"]}


def failures(entries: list[dict], lang: str) -> set[str]:
    rows = []
    for e in entries:
        rows.append({"id": e["id"], "lang": "en", "text": " ".join([e["rule"]] + [x["situation"] + " " + x["why"] for x in e["examples"]])})
        rows.append({"id": e["id"], "lang": lang, "text": " ".join(x["say"] + " " + x["dontSay"] for x in e["examples"])})
    with tempfile.TemporaryDirectory() as tmp:
        f = Path(tmp) / "p.jsonl"
        f.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows), encoding="utf-8")
        rep = Path(tmp) / "r.json"
        subprocess.run([sys.executable, str(TOOLS / "terms" / "overlap_check.py"), "check", str(f), "--fields", "text", "--json", str(rep)],
                       capture_output=True, check=False)
        return {fd["item"] for fd in json.loads(rep.read_text(encoding="utf-8"))["findings"] if fd["verdict"] == "FAIL"}


def build(client: llm.Client, lang: str) -> dict:
    sid, country = GUIDE[lang]
    WORK.mkdir(exist_ok=True)
    wf = WORK / f"{lang}.json"
    done: dict[str, list[dict]] = json.loads(wf.read_text(encoding="utf-8")) if wf.exists() else {}
    chs = {c["name"]: c for c in chapters(sid)} if sid else {}
    row = S.require(sid, "llm") if sid else None
    for topic, (title, wanted) in TOPICS.items():
        if topic in done:
            continue
        ch = next((chs[n] for n in wanted if n in chs), None)
        excerpt = chapter_text(sid, ch, 7000) if ch else None
        feedback = ""
        for _attempt in range(3):
            msg = (f"Write 3 pragmatics entries for US service members speaking {NAMES[lang]} with {PARTNER[lang]} and local civilians in "
                   f"{country}. Topic: {title}. Each entry: rule (one or two English sentences, practical), page, and 2 examples. Each "
                   f"example: situation (English, a military work setting: liaison meeting, gate, briefing, office visit, meal with "
                   f"counterparts), say (what to say, natural {NAMES[lang]}), dontSay (a plausible but inappropriate alternative in "
                   f"{NAMES[lang]}), why (one English sentence). ")
            msg += (f"Base the rules ONLY on this field-guide section; page = its [p. N] marker. Use entirely your own words; never reuse 6 or "
                    f"more consecutive words from it.\n\n{excerpt}" if excerpt else "Use well-established general knowledge; page = 0.")
            if feedback:
                msg += f"\n\nA previous draft was rejected: {feedback}"
            try:
                out = client.chat_json([{"role": "system", "content": "You write accurate cross-cultural pragmatics guidance. Answer in JSON only."},
                                        {"role": "user", "content": msg}], SCHEMA, temperature=0.4, max_tokens=3000)
            except (RuntimeError, ValueError) as e:
                if isinstance(e, llm.EndpointDown):
                    raise
                feedback = f"invalid output ({e})"
                continue
            entries = []
            for k, e in enumerate(out.get("entries", [])[:3], 1):
                exs = [x for x in e.get("examples", [])[:2]
                       if x.get("say") and not langtext.foreign_script(x["say"], lang) and not langtext.foreign_script(x.get("dontSay", ""), lang)]
                if not e.get("rule") or not exs:
                    continue
                src = ({"doc": f"AFCLC Expeditionary Culture Field Guide: {country} ({row.get('edition', '')})", "sourceId": sid,
                        "section": title_case(ch["name"]), "page": str(max(1, int(e.get("page") or 1)))} if ch else
                       {"doc": f"general knowledge — no AFCLC field guide for {country}", "sourceId": "", "section": "", "page": ""})
                entries.append({"id": f"{lang}-prag-{topic}-{k}", "topic": topic, "rule": e["rule"].strip(),
                                "examples": [{k2: langtext.nfc(str(x.get(k2, "")).strip()) for k2 in ("situation", "say", "dontSay", "why")} for x in exs],
                                "source": src, "verified": False})
            bad = failures(entries, lang)
            if bad:
                feedback = "it reused the field guide's wording; rewrite every sentence in different words"
                entries = [e for e in entries if e["id"] not in bad]
            if len(entries) >= 2:
                done[topic] = entries
                wf.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
                break
        print(f"  {lang} {topic}: {len(done.get(topic, []))}", flush=True)
    target = HERE / f"{lang}.json"
    entries = review_state.carry("pragmatics", lang, [e for t in TOPICS for e in done.get(t, [])],
                                 json.loads(target.read_text(encoding="utf-8"))["entries"] if target.exists() else [])
    pack = {"format": "mokuhyo-pragmatics/1", "lang": lang,
            "license": "CC BY-SA 4.0 (Mokuhyo contributors). Original wording; informed by the AFCLC Expeditionary Culture Field Guides, cited per entry.",
            "entries": entries}
    (HERE / f"{lang}.json").write_text(json.dumps(pack, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"pragmatics {lang}: {len(pack['entries'])} entries")
    return pack


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--language", default="all")
    a = ap.parse_args()
    langs = list(langtext.LANGS) if a.language == "all" else a.language.split(",")
    client = llm.Client.from_args()
    try:
        client.ping()
        for lang in langs:
            build(client, lang)
    except llm.EndpointDown as e:
        print(f"build_pragmatics: {e}\nrerun: {e.rerun}")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
