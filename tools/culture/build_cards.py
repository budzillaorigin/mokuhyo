#!/usr/bin/env python3
"""Culture cards per language (BRIEF_PHASE8 C-05, §B.4.1): tools/culture/<lang>.cards.json → packs/<lang>/culture.json.

Each card is ORIGINAL wording informed by one section of the AFCLC Expeditionary Culture Field Guide for the partner
country (D-028: es → Mexico, ar → Saudi Arabia with Qatar notes, fa → Iran, zh-Hans → China, …). The guides are
machine_extract_ok = true (the chapter text may be given to the model) and verbatim_ok = false: their wording must
never ship, so every card passes tools/terms/overlap_check.py and failures are redrafted. Cards cite the chapter and
the page within it, the way the guides cite themselves ("p. 5 of History and Myth").

Germany and France have no AFCLC guide: their cards are drafted from general knowledge, carry no ECFG citation and
are badged "general knowledge — no field guide" (known gap). Qatar notes for ar are badged the same way.

  uv run --group content python culture/build_cards.py all [--language ja]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
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

import sources as S

GUIDE = {"ja": ("culture-afclc-japan", "Japan"), "ko": ("culture-afclc-southkorea", "South Korea"), "es": ("culture-afclc-mexico", "Mexico"),
         "pt-BR": ("culture-afclc-brazil", "Brazil"), "ru": ("culture-afclc-russia", "Russia"), "ar": ("culture-afclc-saudiarabia", "Saudi Arabia"),
         "fa": ("culture-afclc-iran", "Iran"), "id": ("culture-afclc-indonesia", "Indonesia"), "zh-Hans": ("culture-afclc-china", "China"),
         "de": (None, "Germany"), "fr": (None, "France")}
# Card tags (the scenario/persona vocabulary) → the guide chapters that inform them.
TAGS = {
    "rank": ["POLITICAL AND SOCIAL RELATIONS", "FAMILY AND KINSHIP", "LANGUAGE AND COMMUNICATION"],
    "hospitality": ["SUSTENANCE AND HEALTH", "TIME AND SPACE", "FAMILY AND KINSHIP"],
    "refusal": ["LANGUAGE AND COMMUNICATION"],
    "face": ["LANGUAGE AND COMMUNICATION", "POLITICAL AND SOCIAL RELATIONS"],
    "time": ["TIME AND SPACE"],
    "gender": ["SEX AND GENDER"],
    "religion": ["RELIGION AND SPIRITUALITY"],
    "gift": ["TIME AND SPACE", "LANGUAGE AND COMMUNICATION"],
    "meal": ["SUSTENANCE AND HEALTH"],
    "meeting": ["TIME AND SPACE", "LANGUAGE AND COMMUNICATION"],
    "radio": ["LANGUAGE AND COMMUNICATION"],
    "gate": ["POLITICAL AND SOCIAL RELATIONS", "TIME AND SPACE"],
}
WORK = HERE / ".work"
CHAPTER = re.compile(r"^\s*(\d{1,2})\.\s+([A-Z][A-Z ,&]+?)\s*$")
KNOWN = ["HISTORY AND MYTH", "POLITICAL AND SOCIAL RELATIONS", "RELIGION AND SPIRITUALITY", "FAMILY AND KINSHIP", "SEX AND GENDER",
         "LANGUAGE AND COMMUNICATION", "LEARNING AND KNOWLEDGE", "TIME AND SPACE", "AESTHETICS AND RECREATION", "SUSTENANCE AND HEALTH",
         "ECONOMICS AND RESOURCES", "TECHNOLOGY AND MATERIAL"]


def chapters(source_id: str) -> list[dict]:
    """[{name, start (pdf index), end}] from the chapter title pages ("6. LANGUAGE AND COMMUNICATION")."""
    pages = S.pages(source_id)
    starts = []
    for i, p in enumerate(pages):
        for ln in p.splitlines()[:6]:
            m = CHAPTER.match(ln.strip())
            if m and len(m.group(2)) > 6:
                name = " ".join(m.group(2).split()).replace(" & ", " AND ")
                name = next((k for k in KNOWN if name.startswith(k)), name)  # drop the running-header fragment pdftotext appends
                starts.append((i, name))
                break
    out = []
    for k, (i, name) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else len(pages)
        if not any(c["name"] == name for c in out):
            out.append({"name": name, "start": i, "end": end})
    return out


def chapter_text(source_id: str, ch: dict, limit: int = 9000) -> str:
    flow = S.pages(source_id, layout=False)
    parts = [f"[p. {i - ch['start'] + 1}]\n" + " ".join(flow[i].split()) for i in range(ch["start"], ch["end"])]
    return "\n".join(parts)[:limit]


def title_case(name: str) -> str:
    return " ".join(w.capitalize() if w not in ("AND", "OF") else w.lower() for w in name.split())


CARD_SCHEMA = {"type": "object", "properties": {"cards": {"type": "array", "items": {"type": "object", "properties": {
    "title": {"type": "string"}, "body": {"type": "string"}, "doThis": {"type": "array", "items": {"type": "string"}},
    "avoidThis": {"type": "array", "items": {"type": "string"}}, "page": {"type": "integer"}},
    "required": ["title", "body", "doThis", "avoidThis", "page"]}}}, "required": ["cards"]}


def draft(client: llm.Client, lang: str, tag: str, country: str, excerpt: str | None, section: str | None, feedback: str = "") -> list[dict]:
    partner = {"ar": "Saudi and Qatari air force officers and civilians"}.get(lang, f"{country}'s air force personnel and local civilians")
    base = (f"Write 2 culture cards for US service members who speak {NAMES[lang]} and work with {partner}. Topic tag: {tag} "
            f"(rank = hierarchy and how to address seniors; face = saving face, criticism, disagreement; refusal = how no is said; "
            f"gate = checkpoints, security staff and local nationals at the base; radio = radio and phone etiquette). Each card: "
            f"title (≤ 8 words), body (≤ 60 words, plain English, practical), doThis (2–3 short actions), avoidThis (2–3 short "
            f"actions), page.")
    if excerpt:
        msg = base + (f" Base the cards ONLY on this section of a field guide ('{section}'); page = the [p. N] marker of the part you "
                      f"used. Write entirely in your own words: do not copy or closely paraphrase any sentence, and never reuse a run of "
                      f"6 or more consecutive words from it.\n\n{excerpt}")
    else:
        msg = base + " Use well-established general knowledge; page = 0. Be specific and avoid stereotypes."
    if feedback:
        msg += f"\n\nA previous draft was rejected: {feedback}"
    out = client.chat_json([{"role": "system", "content": "You write concise, accurate cross-cultural guidance. Answer in JSON only."},
                            {"role": "user", "content": msg}], CARD_SCHEMA, temperature=0.4, max_tokens=2000)
    return [c for c in out.get("cards", []) if c.get("title") and c.get("body") and len(c["body"].split()) <= 75][:2]


def overlap_failures(cards: list[dict]) -> set[str]:
    with tempfile.TemporaryDirectory() as tmp:
        f = Path(tmp) / "cards.jsonl"
        f.write_text("\n".join(json.dumps({"id": c["id"], "lang": "en", "text": " ".join([c["title"], c["body"], *c["doThis"], *c["avoidThis"]])})
                               for c in cards), encoding="utf-8")
        rep = Path(tmp) / "r.json"
        subprocess.run([sys.executable, str(TOOLS / "terms" / "overlap_check.py"), "check", str(f), "--fields", "text", "--json", str(rep)],
                       capture_output=True, check=False)
        return {fd["item"] for fd in json.loads(rep.read_text(encoding="utf-8"))["findings"] if fd["verdict"] == "FAIL"}


def build(client: llm.Client, lang: str) -> dict:
    sid, country = GUIDE[lang]
    WORK.mkdir(exist_ok=True)
    wf = WORK / f"{lang}.cards.json"
    done: dict[str, list[dict]] = json.loads(wf.read_text(encoding="utf-8")) if wf.exists() else {}
    chs = {c["name"]: c for c in chapters(sid)} if sid else {}
    row = S.require(sid, "llm") if sid else None
    for tag, wanted in TAGS.items():
        if tag in done:
            continue
        ch = next((chs[n] for n in wanted if n in chs), None)
        section = title_case(ch["name"]) if ch else None
        excerpt = chapter_text(sid, ch) if ch else None
        feedback = ""
        for _attempt in range(3):
            try:
                cards = draft(client, lang, tag, country, excerpt, section, feedback)
            except (RuntimeError, ValueError) as e:
                if isinstance(e, llm.EndpointDown):
                    raise
                feedback = f"invalid output ({e})"
                continue
            for k, c in enumerate(cards, 1):
                c["id"] = f"{lang}-card-{tag}-{k}"
            bad = overlap_failures(cards)
            if bad:
                feedback = "it reused the field guide's wording; rewrite every sentence in different words and structure"
                cards = [c for c in cards if c["id"] not in bad]
                if not cards:
                    continue
            out = []
            for c in cards:
                src = ({"doc": f"AFCLC Expeditionary Culture Field Guide: {country} ({row.get('edition', '')})".replace(" ()", ""),
                        "sourceId": sid, "section": section, "page": str(max(1, int(c.get("page") or 1)))} if ch else
                       {"doc": f"general knowledge — no AFCLC field guide for {country}", "sourceId": "", "section": "", "page": ""})
                out.append({"id": c["id"], "lang": lang, "country": country, "tags": [tag], "title": c["title"].strip(), "body": c["body"].strip(),
                            "doThis": [x.strip() for x in c["doThis"][:3]], "avoidThis": [x.strip() for x in c["avoidThis"][:3]],
                            "source": src, "drafted": "llm", "verified": False})
            done[tag] = out
            wf.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
            break
        print(f"  {lang} {tag}: {len(done.get(tag, []))} cards", flush=True)
    if lang == "ar" and "qatar" not in done:  # D-028: Qatar-specific notes from public knowledge, badged
        try:
            cards = draft(client, lang, "rank, hospitality and meetings with Qatari counterparts (Qatar-specific differences from Saudi practice)",
                          "Qatar", None, None)
        except (RuntimeError, ValueError) as e:
            if isinstance(e, llm.EndpointDown):
                raise
            cards = []
        done["qatar"] = [{"id": f"ar-card-qatar-{k}", "lang": lang, "country": "Qatar", "tags": ["rank", "hospitality", "meeting", "qatar"],
                          "title": c["title"].strip(), "body": c["body"].strip(), "doThis": c["doThis"][:3], "avoidThis": c["avoidThis"][:3],
                          "source": {"doc": "general knowledge — no AFCLC field guide for Qatar", "sourceId": "", "section": "", "page": ""},
                          "drafted": "llm", "verified": False} for k, c in enumerate(cards, 1)]
        wf.write_text(json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
    cards = [c for tag in list(TAGS) + ["qatar"] for c in done.get(tag, [])]
    target = HERE / f"{lang}.cards.json"
    cards = review_state.carry("card", lang, cards, json.loads(target.read_text(encoding="utf-8"))["cards"] if target.exists() else [])
    pack = {"format": "mokuhyo-culture/1", "lang": lang, "country": country,
            "license": "CC BY-SA 4.0 (Mokuhyo contributors). Original wording; informed by the AFCLC Expeditionary Culture Field Guides (US Air Force Culture and Language Center), cited per card.",
            "cards": cards}
    (HERE / f"{lang}.cards.json").write_text(json.dumps(pack, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"cards {lang}: {len(cards)} ({sum(1 for c in cards if c['source']['sourceId'])} cite a field-guide section)")
    return pack


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["all"])
    ap.add_argument("--language", default="all")
    a = ap.parse_args()
    langs = list(langtext.LANGS) if a.language == "all" else a.language.split(",")
    client = llm.Client.from_args()
    try:
        client.ping()
        for lang in langs:
            build(client, lang)
    except llm.EndpointDown as e:
        print(f"build_cards: {e}\nrerun: {e.rerun}")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
