"""Drafts a language's OPI pack source: scripted question bank (80), role-plays (24) and topic catalog (~100)
(BRIEF §5.3, §6.2, §6.3) through the §7.1 endpoint. Output: tools/opi/<lang>.json (profile + checklist from
profiles.json, everything drafted source="llm", verified=false, badged until reviewed). Resumable: re-running keeps
what's there and fills what's missing.

    uv run --group content python opi/draft_opi.py --language es
    uv run --group content python opi/draft_opi.py --language all
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import langtext  # noqa: E402
import llm  # noqa: E402

PROFILES = HERE / "profiles.json"
LEVELS = ["0+", "1", "1+", "2", "2+", "3"]
NAMES = {"ja": "Japanese", "es": "Spanish", "fr": "French", "de": "German", "pt-BR": "Brazilian Portuguese",
         "ru": "Russian", "zh-Hans": "Mandarin Chinese (Simplified characters)", "ko": "Korean",
         "ar": "Modern Standard Arabic", "fa": "Persian (Farsi)", "id": "Indonesian"}

# (phase, level) → how many questions; 80 in total.
QUESTION_PLAN = (
    [("warmup", lv, 4) for lv in ("0+", "1", "1+")]
    + [("level_check", lv, 6) for lv in LEVELS]
    + [("probe", lv, 4) for lv in LEVELS]
    + [("winddown", lv, 4) for lv in ("1", "2")]
)
ROLEPLAYS_PER_LEVEL = 4
DOMAINS = {
    "daily_life": "introductions, family, housing, food, shopping, health, transport, weather, hobbies, weekend plans",
    "work_study": "your job, schedules, meetings, email follow-ups, education, career goals",
    "society": "news summary, local issues, environment, technology, education policy",
    "travel_culture": "airports, hotels, directions, customs, festivals, etiquette",
    "military_garrison": "ranks and units, base life, schedules, duty roster, physical training, inspections",
    "military_operations": "briefing a counterpart, exercise coordination, logistics and supply, movement orders, airfield operations, communications checks",
    "military_field": "checkpoint, medical evacuation request, casualty report, convoy, disaster relief, evacuation of civilians",
    "interpreter": "escort-interpreter tasks, sight translation of a notice, relaying a commander's intent, clarifying ambiguity, cultural brokering",
    "abstract": "opinions, hypotheticals, policy argument, ethics",
}
TOPICS_PER_DOMAIN = 11
PHASE_HELP = {
    "warmup": "easy, friendly personal questions that put the candidate at ease",
    "level_check": "questions that let the candidate show what they can do at the level",
    "probe": "questions one level harder: ask to narrate in detail, compare, explain, support an opinion or hypothesize",
    "winddown": "easy closing questions that end the interview on a comfortable note",
}
ILR = {"0+": "memorized words and phrases", "1": "simple sentences on familiar personal topics", "1+": "connected sentences; narrate and describe",
       "2": "paragraphs; narrate in past/present/future; concrete work and social topics", "2+": "some abstract topics and supported opinion",
       "3": "supported opinion, hypothesis, abstract topics in extended discourse"}


def src_path(lang: str) -> Path:
    return HERE / f"{lang}.json"


def load(lang: str) -> dict:
    p = src_path(lang)
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    prof = json.loads(PROFILES.read_text(encoding="utf-8"))
    profile = {"language": lang, **prof["profiles"][lang], "levelCheckTopics": prof["levelCheckTopics"], "rolePlaySeeds": prof["rolePlaySeeds"]}
    return {"language": lang, "profile": profile, "questions": [], "rolePlays": [], "topics": [], "checklist": prof["checklist"]}


def save(lang: str, data: dict) -> None:
    p = src_path(lang)
    fd, tmp = tempfile.mkstemp(dir=p.parent, suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, p)


def clean(text: str, lang: str) -> str | None:
    """NFC text in the language, or None when it isn't (foreign-script leak or mostly another script)."""
    t = langtext.nfc(text.strip())
    if not t or langtext.foreign_script(t, lang):
        return None
    m = langtext.measure(t, lang)
    return t if m.purity >= 0.8 else None


def ask(client: llm.Client, system: str, user: str, schema: dict) -> dict:
    return client.chat_json([{"role": "system", "content": system}, {"role": "user", "content": user}], schema, temperature=0.7, max_tokens=3000)


SYSTEM = ("You write ORIGINAL practice material for oral proficiency interviews on the US ILR scale. Never copy real test "
          "items. Write target-language text that a native speaker would say naturally; no English words inside it.")


def draft_questions(client: llm.Client, lang: str, data: dict) -> int:
    made = 0
    for phase, level, n in QUESTION_PLAN:
        have = [q for q in data["questions"] if q["phase"] == phase and q["level"] == level]
        need = n - len(have)
        if need <= 0:
            continue
        schema = {"type": "object", "properties": {"questions": {"type": "array", "minItems": need + 2, "items": {"type": "object", "properties": {
            "prompt": {"type": "string"}, "english": {"type": "string"},
            "domain": {"type": "string", "enum": ["personal", "family", "work", "community", "current_events", "travel", "military", "hypothetical", "abstract"]}},
            "required": ["prompt", "english", "domain"]}}}, "required": ["questions"]}
        avoid = "; ".join(q["english"] for q in data["questions"] if q["phase"] == phase)[-1500:]
        out = ask(client, SYSTEM, f"""Language: {NAMES[lang]}
Register: {data['profile']['registerNotes']}
Interview phase: {phase} — {PHASE_HELP[phase]}.
Target ILR level: {level} ({ILR[level]}).
Write {need + 2} different interviewer questions in {NAMES[lang]}, each one short spoken question, varied across topic areas
(personal, family, work, community, current events, travel, military life, hypotheticals, abstract issues as the level allows),
each with an English translation and a domain label. Avoid repeating: {avoid or 'nothing yet'}""", schema)
        for q in out.get("questions", []):
            prompt = clean(q.get("prompt", ""), lang)
            if not prompt or len(have) >= n or any(prompt == x["prompt"] for x in data["questions"]):
                continue
            item = {"id": f"{lang}-q-{phase}-{level}-{len(have) + 1:02d}", "phase": phase, "level": level, "prompt": prompt,
                    "english": q.get("english", "").strip(), "domain": q.get("domain"), "source": "llm", "verified": False}
            data["questions"].append(item)
            have.append(item)
            made += 1
        save(lang, data)
    return made


def draft_roleplays(client: llm.Client, lang: str, data: dict) -> int:
    made = 0
    seeds = data["profile"]["rolePlaySeeds"]
    for level in LEVELS:
        have = [r for r in data["rolePlays"] if r["level"] == level]
        need = ROLEPLAYS_PER_LEVEL - len(have)
        if need <= 0:
            continue
        schema = {"type": "object", "properties": {"rolePlays": {"type": "array", "minItems": need + 1, "items": {"type": "object", "properties": {
            "situation": {"type": "string"}, "interviewerRole": {"type": "string"}, "opening": {"type": "string"}, "english": {"type": "string"},
            "domain": {"type": "string", "enum": list(DOMAINS)}}, "required": ["situation", "interviewerRole", "opening", "english", "domain"]}}},
            "required": ["rolePlays"]}
        out = ask(client, SYSTEM, f"""Language: {NAMES[lang]}
Register: {data['profile']['registerNotes']}
Write {need + 1} role-play cards for an ILR {level} candidate ({ILR[level]}). Each has: situation (English, 1–2 sentences,
addressed to the candidate, with a small complication at 1+ and above), interviewerRole (English), opening (the interviewer's
first line in {NAMES[lang]}, in role), english (translation of the opening), domain. Ideas (adapt, don't copy): {'; '.join(seeds)}.
Include at least one military or liaison situation. Already written: {'; '.join(r['situation'] for r in data['rolePlays'])[-1200:] or 'none'}""", schema)
        for r in out.get("rolePlays", []):
            opening = clean(r.get("opening", ""), lang)
            if not opening or len(have) >= ROLEPLAYS_PER_LEVEL:
                continue
            item = {"id": f"{lang}-rp-{level}-{len(have) + 1:02d}", "level": level, "situation": r["situation"].strip(),
                    "interviewerRole": r["interviewerRole"].strip(), "opening": opening, "english": r.get("english", "").strip(),
                    "domain": r.get("domain"), "source": "llm", "verified": False}
            data["rolePlays"].append(item)
            have.append(item)
            made += 1
        save(lang, data)
    return made


def draft_topics(client: llm.Client, lang: str, data: dict) -> int:
    made = 0
    for domain, examples in DOMAINS.items():
        have = [t for t in data["topics"] if t["domain"] == domain]
        need = TOPICS_PER_DOMAIN - len(have)
        if need <= 0:
            continue
        schema = {"type": "object", "properties": {"topics": {"type": "array", "minItems": need + 2, "items": {"type": "object", "properties": {
            "title": {"type": "string"}, "opener": {"type": "string"}, "minLevel": {"type": "string", "enum": LEVELS}},
            "required": ["title", "opener", "minLevel"]}}}, "required": ["topics"]}
        out = ask(client, SYSTEM, f"""Language: {NAMES[lang]}
Register: {data['profile']['registerNotes']}
Conversation-practice topics in the domain "{domain.replace('_', ' ')}" (examples: {examples}).
Write {need + 2} distinct topics. Each: title (short English label), opener (a conversation partner's first line in {NAMES[lang]}
that starts the conversation with a question, 1–2 sentences), minLevel (lowest ILR level that can handle it).
Already have: {'; '.join(t['title'] for t in have) or 'none'}""", schema)
        for t in out.get("topics", []):
            opener = clean(t.get("opener", ""), lang)
            title = t.get("title", "").strip()
            if not opener or not title or len(have) >= TOPICS_PER_DOMAIN or any(title.lower() == x["title"].lower() for x in have):
                continue
            item = {"id": f"{lang}-topic-{domain}-{len(have) + 1:02d}", "domain": domain, "title": title, "opener": opener,
                    "minLevel": t.get("minLevel", "1"), "source": "llm", "verified": False}
            data["topics"].append(item)
            have.append(item)
            made += 1
        save(lang, data)
    return made


def status(lang: str) -> str:
    d = load(lang)
    return f"{lang}: {len(d['questions'])}/80 questions, {len(d['rolePlays'])}/24 role-plays, {len(d['topics'])}/{len(DOMAINS) * TOPICS_PER_DOMAIN} topics"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--language", required=True)
    ap.add_argument("--rounds", type=int, default=3, help="passes per language (later passes fill what earlier ones dropped)")
    ap.add_argument("--status", action="store_true")
    llm.add_args(ap)
    args = ap.parse_args()
    langs = langtext.LANGS if args.language == "all" else args.language.split(",")
    if args.status:
        for lang in langs:
            print(status(lang))
        return 0
    client = llm.Client.from_args(args.endpoint, args.model, args.check_model)
    try:
        client.ping()
    except llm.EndpointDown as e:
        print(f"ERROR {e}\nre-run: {e.rerun}", file=sys.stderr)
        return 2
    for lang in langs:
        data = load(lang)
        for _ in range(args.rounds):
            try:
                n = draft_questions(client, lang, data) + draft_roleplays(client, lang, data) + draft_topics(client, lang, data)
            except llm.EndpointDown as e:
                print(f"ERROR {e}\nre-run: {e.rerun}", file=sys.stderr)
                return 2
            if n == 0:
                break
        print(status(lang), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
