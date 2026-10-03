#!/usr/bin/env python3
"""Conversation personas per language (BRIEF_PHASE8 C-08, §B.4.2): tools/personas/<lang>.json → packs/<lang>/personas.json.

Six roles — senior counterpart, peer officer, junior enlisted, interpreter, local contractor, civilian official —
set in the partner country's air force (D-028); Arabic has two sets, Royal Saudi Air Force and Qatar Emiri Air Force.
The model invents names and writes rank/title, register, a short bio and a greeting; patience/formality, the
pragmatics entries each persona enforces and its culture card are assigned here from the role, so they are
consistent across languages. Run after build_cards.py and build_pragmatics.py.

  uv run --group content python personas/build_personas.py --language all
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "terms"))

from align_terms import NAMES

import langtext
import llm

sys.path.insert(0, str(TOOLS / "items"))
import review_state

FORCES = {
    "ja": ["Japan Air Self-Defense Force (JASDF)"], "ko": ["Republic of Korea Air Force (ROKAF)"], "de": ["Luftwaffe (Bundeswehr)"],
    "fr": ["Armée de l'air et de l'espace"], "es": ["Fuerza Aérea Mexicana"], "pt-BR": ["Força Aérea Brasileira (FAB)"],
    "ru": ["Russian Aerospace Forces (VKS)"], "id": ["TNI Angkatan Udara (TNI-AU)"], "fa": ["Islamic Republic of Iran Air Force (IRIAF)"],
    "zh-Hans": ["PLA Air Force (PLAAF)"], "ar": ["Royal Saudi Air Force (RSAF)", "Qatar Emiri Air Force (QEAF)"],
}
# role → (patience, formality, pragmatics topics, culture-card tags)
ROLES = {
    "senior_counterpart": (2, 5, ["address", "disagreement"], ["rank", "meeting"]),
    "peer_officer": (3, 3, ["small_talk", "refusal"], ["meeting", "hospitality"]),
    "junior_enlisted": (4, 3, ["address", "apology"], ["rank", "radio"]),
    "interpreter": (5, 3, ["refusal", "nonverbal"], ["meeting", "face"]),
    "local_contractor": (3, 2, ["hospitality", "refusal"], ["gate", "time"]),
    "civilian_official": (3, 4, ["disagreement", "hospitality"], ["meeting", "face"]),
}
SCHEMA = {"type": "object", "properties": {"personas": {"type": "array", "items": {"type": "object", "properties": {
    "role": {"type": "string", "enum": list(ROLES)}, "name": {"type": "string"}, "gender": {"type": "string", "enum": ["female", "male"]},
    "rankTitle": {"type": "string"}, "rankEnglish": {"type": "string"}, "register": {"type": "string"}, "bio": {"type": "string"},
    "greeting": {"type": "string"}, "greetingEnglish": {"type": "string"}},
    "required": ["role", "name", "gender", "rankTitle", "rankEnglish", "register", "bio", "greeting", "greetingEnglish"]}}}, "required": ["personas"]}


def assign(lang: str, role: str) -> tuple[list[str], str]:
    _, _, topics, tags = ROLES[role]
    prag_file = TOOLS / "pragmatics" / f"{lang}.json"
    cards_file = TOOLS / "culture" / f"{lang}.cards.json"
    prag = json.loads(prag_file.read_text(encoding="utf-8"))["entries"] if prag_file.exists() else []
    cards = json.loads(cards_file.read_text(encoding="utf-8"))["cards"] if cards_file.exists() else []
    ids = [e["id"] for t in topics for e in prag if e["topic"] == t][:4]
    card = next((c["id"] for t in tags for c in cards if t in c["tags"]), "")
    return ids, card


def build(client: llm.Client, lang: str) -> dict:
    out = []
    for force in FORCES[lang]:
        country_hint = "Qatar" if "Qatar" in force else ""
        for attempt in range(3):
            msg = (f"Create six fictional conversation partners for a US service member who speaks {NAMES[lang]} and works with the {force}"
                   f"{' in ' + country_hint if country_hint else ''}: one of each role {', '.join(ROLES)} (interpreter = a local interpreter "
                   f"working for the partner force; local_contractor = a local businessperson supplying the base; civilian_official = a local "
                   f"government official). For each: role, name (invented, culturally typical, no real public figures), gender, rankTitle "
                   f"(rank or title in {NAMES[lang]} as the learner should use it in address), rankEnglish, register (one or two English "
                   f"sentences: how this person speaks and expects to be addressed, honorifics, formality), bio (one English sentence), "
                   f"greeting (their first line to the learner, natural {NAMES[lang]}), greetingEnglish. Mix genders.")
            try:
                raw = client.chat_json([{"role": "system", "content": "You design realistic, respectful practice personas. Answer in JSON only."},
                                        {"role": "user", "content": msg}], SCHEMA, temperature=0.7, max_tokens=3000)
            except (RuntimeError, ValueError) as e:
                if isinstance(e, llm.EndpointDown):
                    raise
                continue
            ps = {p["role"]: p for p in raw.get("personas", []) if p.get("role") in ROLES and p.get("greeting")}
            if set(ps) == set(ROLES) and all(not langtext.foreign_script(p["greeting"], lang) for p in ps.values()):
                break
        else:
            print(f"  {lang} {force}: incomplete persona set after 3 tries")
        suffix = "-qeaf" if "Qatar" in force else ("-rsaf" if "Saudi" in force else "")
        for role, (patience, formality, _, _) in ROLES.items():
            p = ps.get(role)
            if not p:
                continue
            prag, card = assign(lang, role)
            out.append({"id": f"{lang}{suffix}-{role}", "lang": lang, "role": role, "name": p["name"].strip(), "rankTitle": langtext.nfc(p["rankTitle"].strip()),
                        "rankEnglish": p["rankEnglish"].strip(), "force": force, "gender": p["gender"], "register": p["register"].strip(),
                        "patience": patience, "formality": formality, "bio": p["bio"].strip(), "greeting": langtext.nfc(p["greeting"].strip()),
                        "greetingEnglish": p["greetingEnglish"].strip(), "pragmatics": prag, "card": card, "source": "llm", "verified": False})
    target = HERE / f"{lang}.json"
    out = review_state.carry("persona", lang, out, json.loads(target.read_text(encoding="utf-8"))["personas"] if target.exists() else [])
    pack = {"format": "mokuhyo-personas/1", "lang": lang, "license": "CC BY-SA 4.0 (Mokuhyo contributors). Fictional personas, AI-drafted.",
            "personas": out}
    (HERE / f"{lang}.json").write_text(json.dumps(pack, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"personas {lang}: {len(out)}")
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
        print(f"build_personas: {e}\nrerun: {e.rerun}")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
