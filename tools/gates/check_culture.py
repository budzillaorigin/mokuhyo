#!/usr/bin/env python3
"""Validates the Phase 8 culture layer (BRIEF_PHASE8 C-05, C-07, C-08) for every language:

- culture cards (tools/culture/<lang>.cards.json): every scenario in tools/terms/scenarios.json has at least one card
  sharing a tag; every card cites a field-guide section (sourceId in SOURCES.json, section, page) or says it is general
  knowledge for a country without a guide; drafted cards are source = llm and unverified until reviewed;
- pragmatics (tools/pragmatics/<lang>.json): all seven topics, each entry with a rule and at least one example with
  say / dontSay / why;
- personas (tools/personas/<lang>.json): six roles per force (twelve for Arabic), each with name, gender, rank, register,
  greeting, patience and formality.

Verbatim copying is checked separately by tools/gates/gate_terms.sh (overlap_check.py over these folders).

  uv run python gates/check_culture.py [--language es,ja]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
LANGS = ["ja", "es", "fr", "de", "pt-BR", "ru", "zh-Hans", "ko", "ar", "fa", "id"]
TOPICS = {"address", "refusal", "apology", "small_talk", "disagreement", "hospitality", "nonverbal"}
ROLES = {"senior_counterpart", "peer_officer", "junior_enlisted", "interpreter", "local_contractor", "civilian_official"}
NO_GUIDE = {"de", "fr"}  # no AFCLC field guide for the partner country (D-028): cards are general knowledge, badged


def load(p: Path) -> dict | None:
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def check_cards(lang: str, scenarios: list[dict], source_ids: set[str]) -> list[str]:
    pack = load(TOOLS / "culture" / f"{lang}.cards.json")
    if pack is None:
        return [f"{lang}: no culture cards"]
    errs = []
    cards = pack.get("cards", [])
    tags = {t for c in cards for t in c.get("tags", [])}
    errs += [f"{lang}: scenario {s['id']} has no card (tags {s['tags']})" for s in scenarios if not tags & set(s["tags"])]
    for c in cards:
        where = f"{lang} {c.get('id')}"
        src = c.get("source") or {}
        if (lang in NO_GUIDE or "no afclc field guide" in str(src.get("doc", "")).lower()) and not src.get("sourceId"):
            if "general" not in json.dumps(src).lower():
                errs.append(f"{where}: no field guide for this country, so the source must say general knowledge")
        else:
            if src.get("sourceId") not in source_ids:
                errs.append(f"{where}: sourceId {src.get('sourceId')!r} not in SOURCES.json")
            if not src.get("section") or not str(src.get("page", "")).strip():
                errs.append(f"{where}: must cite a field-guide section and page")
        if not c.get("title") or not c.get("body") or not c.get("doThis") or not c.get("avoidThis"):
            errs.append(f"{where}: title, body, doThis and avoidThis are required")
        if c.get("drafted") == "llm" and c.get("verified") is not False and "reviewedBy" not in c:
            errs.append(f"{where}: drafted card marked verified without a review")
    return errs


def check_pragmatics(lang: str) -> list[str]:
    pack = load(TOOLS / "pragmatics" / f"{lang}.json")
    if pack is None:
        return [f"{lang}: no pragmatics pack"]
    errs = []
    topics: dict[str, list[dict]] = {}
    for e in pack.get("entries", []):
        topics.setdefault(e.get("topic"), []).append(e)
    errs += [f"{lang}: pragmatics topic {t} missing" for t in sorted(TOPICS - set(topics))]
    for tid, entries in topics.items():
        for i, e in enumerate(entries):
            if not e.get("rule") or not e.get("examples"):
                errs.append(f"{lang} {tid}[{i}]: rule and examples required")
            for x in e.get("examples", []):
                if not all(str(x.get(k, "")).strip() for k in ("situation", "say", "dontSay", "why")):
                    errs.append(f"{lang} {tid}[{i}]: example needs situation, say, dontSay, why")
    return errs


def check_personas(lang: str) -> list[str]:
    pack = load(TOOLS / "personas" / f"{lang}.json")
    if pack is None:
        return [f"{lang}: no personas"]
    errs = []
    personas = pack.get("personas", [])
    forces = {p.get("force") for p in personas}
    want = 12 if lang == "ar" else 6
    if len(personas) < want:
        errs.append(f"{lang}: {len(personas)} personas (want {want})")
    for force in forces:
        roles = {p.get("role") for p in personas if p.get("force") == force}
        if roles != ROLES:
            errs.append(f"{lang} {force}: roles {sorted(r for r in roles if r)} (want all six)")
    for p in personas:
        missing = [k for k in ("id", "name", "gender", "rankTitle", "register", "greeting", "patience", "formality") if p.get(k) in (None, "")]
        if missing:
            errs.append(f"{lang} {p.get('id')}: missing {', '.join(missing)}")
    return errs


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--language", default="all")
    ap.add_argument("--only", choices=["cards", "pragmatics", "personas"])
    a = ap.parse_args()
    langs = LANGS if a.language == "all" else a.language.split(",")
    scenarios = json.loads((TOOLS / "terms" / "scenarios.json").read_text(encoding="utf-8"))["scenarios"]
    source_ids = {r["id"] for r in json.loads((TOOLS / "sources" / "SOURCES.json").read_text(encoding="utf-8"))["sources"]}
    errs: list[str] = []
    for lang in langs:
        if a.only in (None, "cards"):
            errs += check_cards(lang, scenarios, source_ids)
        if a.only in (None, "pragmatics"):
            errs += check_pragmatics(lang)
        if a.only in (None, "personas"):
            errs += check_personas(lang)
    for e in errs[:60]:
        print("ERROR " + e)
    print(f"check_culture: {len(langs)} languages, {len(errs)} errors")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
