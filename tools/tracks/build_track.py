#!/usr/bin/env python3
"""Builds the Counter-UAS & Base Defense track per language (BRIEF_PHASE8 C-03, §B.3):
tools/tracks/cuas-base-defense.<lang>.json, copied into content/packs/<lang>/track-cuas-base-defense.json by the pack
builder.

Inputs: tools/terms/seed_terms.csv (English, provenance), tools/terms/term_alignment.csv (target term, kind, source,
drafted definition, status/badge), tools/terms/scenarios.json (scenario catalog). Drafting goes through the §7.1
endpoint (primary model); everything drafted is `source: "llm"`, `verified: false` and badged in the app.

Stages (resumable; work files in tools/tracks/.work/, git-ignored):
  examples   two example sentences (military register, with English), 3 collocations and a one-line register note
             per term
  extras     scenario openers (12), dialogues (8), OPI probes (12), register drills (~16)
  assemble   writes the track JSON: terms with equivalents[], meaning / fill-in / register / brevity drills,
             scenarios, dialogues; OPI probes are merged into tools/opi/<lang>.json (ids <lang>-cuas-probe-NN)
  all        examples → extras → assemble

  uv run --group content python tracks/build_track.py all --language ja
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import random
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "terms"))

from align_terms import NAMES, PARTNER, parse_lines

import langtext
import llm

sys.path.insert(0, str(TOOLS / "items"))
import review_state

import sources as S

TRACK = "cuas-base-defense"
TITLE = "Counter-UAS & Base Defense"
WORK = HERE / ".work"
SEEDS = TOOLS / "terms" / "seed_terms.csv"
ALIGN = TOOLS / "terms" / "term_alignment.csv"
SCENARIOS = TOOLS / "terms" / "scenarios.json"
OPI = TOOLS / "opi"
LICENSE = "CC BY-SA 4.0"
ATTRIBUTION = ("Mokuhyo contributors. English definitions: US Government works (public domain) — DoD Dictionary, ATP 3-01.81, "
               "JP 3-10, JP 3-01, ATP 1-02.1 — or original where marked. Target-language terms aligned with the allied sources "
               "cited per term; definitions, examples and dialogues AI-drafted with Mistral-Small-3.2-24B, pending human review.")
# Registers the register drills contrast, per language (BRIEF_PHASE8 C-03 "keigo/register where the language has it").
REGISTER = {
    "ja": "keigo (尊敬語/謙譲語) to a senior officer vs. plain form to a peer", "ko": "합쇼체 to a senior officer vs. 해요체/반말 to a peer",
    "de": "Sie with rank and surname vs. du to a comrade", "fr": "vous with « mon colonel » vs. tu to a comrade",
    "es": "usted with rank vs. tú to a peer", "pt-BR": "o senhor / a senhora with rank vs. você to a peer",
    "ru": "вы with rank (товарищ полковник) vs. ты to a peer", "ar": "حضرتك / formal address with rank vs. أنت to a peer",
    "fa": "شما with honorifics (جناب سرهنگ) vs. تو to a peer", "id": "Bapak/Ibu with rank vs. kamu to a peer",
    "zh-Hans": "您 with rank (首长/上校同志) vs. 你 to a peer",
}


def work(lang: str, stage: str) -> Path:
    WORK.mkdir(exist_ok=True)
    return WORK / f"{lang}.{stage}.json"


def load(lang: str, stage: str) -> dict:
    p = work(lang, stage)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def save(lang: str, stage: str, data: dict) -> None:
    p = work(lang, stage)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(p)


def seeds() -> dict[str, dict]:
    with open(SEEDS, encoding="utf-8") as f:
        return {r["id"]: r for r in csv.DictReader(f)}


def aligned(lang: str) -> dict[str, dict]:
    with open(ALIGN, encoding="utf-8") as f:
        return {r["seed_id"]: r for r in csv.DictReader(f) if r["lang"] == lang}


def clean_text(s: str) -> str:
    return langtext.nfc(s.strip().strip('"“”「」'))


def pure(text: str, lang: str, allow: set[str] = frozenset()) -> bool:
    bad = [w for w in langtext.foreign_script(text, lang) if w not in allow]
    return not bad


# ------------------------------------------------------------------------------------------------ examples
def examples(client: llm.Client, lang: str) -> None:
    al, sd = aligned(lang), seeds()
    done = load(lang, "examples")
    # Redraft when the aligned term changed since the examples were written (C-02 verification can replace a term).
    todo = [sid for sid in sd if sid in al and (sid not in done or done[sid].get("term", al[sid]["term"]) != al[sid]["term"])]
    if not todo:
        return
    print(f"examples {lang}: {len(todo)}", flush=True)
    for n in range(0, len(todo), 10):
        batch = todo[n:n + 10]
        listing = "\n".join(f'{sid} | {al[sid]["term"]} | {sd[sid]["term_en"]}: {sd[sid]["definition_en"][:160]}' for sid in batch)
        msg = [
            {"role": "system", "content": f"You write {NAMES[lang]} teaching material for military linguists working with {PARTNER[lang]}."},
            {"role": "user", "content":
                f"For each term (seed_id | {NAMES[lang]} term | English term: meaning), write:\n"
                f"1. two different natural example sentences in {NAMES[lang]} that use the term exactly as given, in the register "
                f"of military work (briefings, radio, reports, liaison), 8–20 words each, with an English translation each;\n"
                f"2. three common collocations in {NAMES[lang]} (the term with a verb, adjective or noun it often goes with), "
                f"separated by ';';\n"
                f"3. one short English note on register or usage (who says it, formal/informal, radio vs. written), max 20 words.\n"
                f"Invent no real names or units; write only {NAMES[lang]} in the sentences (acronyms excepted).\n"
                f"Output one line per term, nothing else:\n"
                f"<seed_id> | <sentence 1> | <English 1> | <sentence 2> | <English 2> | <collocations> | <note>\n\n{listing}"},
        ]
        try:
            text = client.chat(msg, temperature=0.5, max_tokens=3500)
        except (RuntimeError, ValueError) as e:
            if isinstance(e, llm.EndpointDown):
                raise
            print(f"  batch failed: {e}")
            continue
        for sid, parts in parse_lines(text, set(batch)).items():
            if len(parts) < 6:
                continue
            ex = [{"text": clean_text(parts[0]), "english": parts[1].strip()}, {"text": clean_text(parts[2]), "english": parts[3].strip()}]
            allow = {w for w in re.findall(r"[A-Za-z]+", al[sid]["term"])}
            if not all(e["text"] and pure(e["text"], lang, allow) for e in ex):
                continue
            done[sid] = {"term": al[sid]["term"], "examples": ex, "collocations": [clean_text(c) for c in parts[4].split(";") if c.strip()][:4],
                         "registerNote": parts[5].strip() if parts[5].strip() not in ("-", "") else ""}
        save(lang, "examples", done)
    print(f"  {lang}: {len(done)} terms with examples")


# ------------------------------------------------------------------------------------------------ extras
DIALOGUE_SCHEMA = {"type": "object", "properties": {"title": {"type": "string"}, "lines": {"type": "array", "items": {"type": "object", "properties": {
    "speaker": {"type": "string"}, "voice": {"type": "string", "enum": ["female", "male"]}, "text": {"type": "string"},
    "english": {"type": "string"}}, "required": ["speaker", "voice", "text", "english"]}}}, "required": ["title", "lines"]}
REGDRILL_SCHEMA = {"type": "object", "properties": {"items": {"type": "array", "items": {"type": "object", "properties": {
    "situation": {"type": "string"}, "choices": {"type": "array", "items": {"type": "string"}}, "answer": {"type": "integer"},
    "explanation": {"type": "string"}}, "required": ["situation", "choices", "answer", "explanation"]}}}, "required": ["items"]}


def parse_probes(text: str, lang: str) -> list[dict]:
    """`<n> | <phase> | <ILR level> | <question> | <English>` lines; tolerates "ILR 2", "Level check", bold markup."""
    probes = []
    for n, p in parse_lines(text, {str(i) for i in range(1, 13)}).items():
        if len(p) < 4:
            continue
        phase = "level_check" if "check" in p[0].lower() else "probe" if "probe" in p[0].lower() else ""
        level = re.sub(r"(?i)^\s*ilr\s*", "", p[1].strip().strip("*")).strip()
        if phase and level in ("2", "2+", "3") and pure(p[2], lang):
            probes.append({"n": int(n), "phase": phase, "level": level, "prompt": clean_text(p[2]), "english": p[3].strip()})
    return probes


def extras(client: llm.Client, lang: str) -> None:
    done = load(lang, "extras")
    cat = json.loads(SCENARIOS.read_text(encoding="utf-8"))["scenarios"]
    al = aligned(lang)
    name = NAMES[lang]
    if len(done.get("openers", {})) < len(cat):
        got = dict(done.get("openers", {}))
        for _attempt in range(6):
            todo = [s for s in cat if s["id"] not in got]
            if not todo:
                break
            listing = "\n".join(f'{s["id"]} | {s["situation"]} The learner is: {s["learnerRole"]}. You are: {s["partnerRole"]}.' for s in todo)
            text = client.chat([{"role": "user", "content":
                f"For each role-play below, write the first line the partner says to open it, in natural {name} as spoken by a "
                f"member of {PARTNER[lang]} (or a local civilian where the role says so), at ILR level of the scenario (1+ to 3), "
                f"ending with something the learner must answer; plus an English translation. The opening line must be written "
                f"entirely in {name} script: no romanization, no English words or acronyms.\nOutput one line per role-play:\n"
                f"<id> | <opening line in {name}> | <English>\n\n{listing}"}], temperature=0.6, max_tokens=2500)
            got.update({sid: {"opener": clean_text(p[0]), "english": p[1].strip() if len(p) > 1 else ""}
                        for sid, p in parse_lines(text, {s["id"] for s in todo}).items() if pure(p[0], lang)})
        done["openers"] = got  # partial sets are kept and completed on the next run
        save(lang, "extras", done)
    dialogues = done.setdefault("dialogues", {})
    for s in cat[:8]:
        if s["id"] in dialogues:
            continue
        terms = [al[t]["term"] for t in s["terms"] if t in al]
        for _attempt in range(5):
            try:
                out = client.chat_json([{"role": "system", "content": "You write ORIGINAL listening practice dialogues. Answer in JSON only."},
                    {"role": "user", "content":
                        f"Write a {name} dialogue of 8–12 lines for this situation: {s['situation']} Speakers: {s['learnerRole']} "
                        f"(a US service member speaking {name}) and {s['partnerRole']} (a native speaker; {PARTNER[lang]}). "
                        f"Use these terms naturally: {', '.join(terms)}. ILR level {s['level']}. Speaker labels in {name}; voice "
                        f"female or male, different for the two speakers; each line with an English translation. Natural spoken "
                        f"{name}, correct military register; every line written entirely in {name} script (no words in Latin letters); "
                        f"invent names, no real units. Give a short English title."}],
                    DIALOGUE_SCHEMA, temperature=0.6, max_tokens=3500)
            except (RuntimeError, ValueError) as e:
                if isinstance(e, llm.EndpointDown):
                    raise
                continue
            lines = [{"speaker": clean_text(x["speaker"]), "voice": x["voice"], "text": clean_text(x["text"]), "english": x["english"].strip()}
                     for x in out.get("lines", []) if x.get("text", "").strip()]
            if 6 <= len(lines) <= 14 and all(pure(x["text"], lang) for x in lines) and len({x["voice"] for x in lines}) == 2:
                dialogues[s["id"]] = {"title": out.get("title", s["title"]).strip(), "lines": lines}
                save(lang, "extras", done)
                break
    if len(done.get("probes", [])) < 10:
        for _attempt in range(3):
            text = client.chat([{"role": "user", "content":
                f"Write 12 interview questions in {name} for a practice oral proficiency interview of a military linguist, on "
                f"counter-drone defense and base security work with {PARTNER[lang]}: 4 level checks at ILR 2 (describe, narrate a past "
                f"event), 4 probes at ILR 2+ (compare, explain a procedure and its reasons), 4 probes at ILR 3 (support an opinion, "
                f"hypothesize about policy). One question each, natural spoken {name}, polite register, written entirely in {name} "
                f"script.\nOutput one line per question:\n<n> | <phase: level_check or probe> | <ILR level> | <question in {name}> | <English>"}],
                temperature=0.6, max_tokens=2500)
            probes = parse_probes(text, lang)
            if len(probes) >= 10:
                done["probes"] = sorted(probes, key=lambda x: x["n"])
                save(lang, "extras", done)
                break
    if "register" not in done:
        sd = seeds()
        picks = [t for t in ("bd-001", "bd-004", "bd-011", "cuas-047", "c2-005", "air-008", "c2-003", "roe-001") if t in al]
        try:
            out = client.chat_json([{"role": "system", "content": "You write register (politeness) drills for language learners. Answer in JSON only."},
                {"role": "user", "content":
                    f"Write 8 multiple-choice register drills for {name} ({REGISTER[lang]}). Each: situation (English, who speaks to "
                    f"whom in a military setting, e.g. reporting to a senior host-nation officer, or telling a peer), choices = 3 "
                    f"{name} versions of the same message differing only in register/politeness, answer = 0-based index of the "
                    f"version that fits the situation, explanation (English, one sentence). Use these terms, one per drill: "
                    f"{'; '.join(al[t]['term'] + ' (' + sd[t]['term_en'] + ')' for t in picks)}."}],
                REGDRILL_SCHEMA, temperature=0.5, max_tokens=3500)
            items = [i for i in out.get("items", []) if len(i.get("choices", [])) == 3 and 0 <= i.get("answer", -1) < 3
                     and all(pure(c, lang) for c in i["choices"])]
            done["register"] = [dict(i, termId=picks[k] if k < len(picks) else "") for k, i in enumerate(items)]
            save(lang, "extras", done)
        except (RuntimeError, ValueError) as e:
            if isinstance(e, llm.EndpointDown):
                raise
            print(f"  register drills failed: {e}")
    print(f"extras {lang}: openers {len(done.get('openers', {}))} · dialogues {len(dialogues)} · probes {len(done.get('probes', []))} · "
          f"register drills {len(done.get('register', []))}", flush=True)


# ------------------------------------------------------------------------------------------------ assemble
def assemble(lang: str) -> dict:
    sd, al = seeds(), aligned(lang)
    ex = load(lang, "examples")
    xt = load(lang, "extras")
    cat = json.loads(SCENARIOS.read_text(encoding="utf-8"))["scenarios"]
    rng = random.Random(f"{TRACK}-{lang}")
    terms = []
    for sid, s in sd.items():
        a = al.get(sid)
        if a is None:
            continue
        e = ex.get(sid, {})
        cite = {"doc": s["source_doc"], "page": s["source_page"], "sourceId": s["source_id"]} if s["definition_source"] == "doctrine" else {"doc": "authored"}
        equivalents = [{"text": a["term"], "kind": a["term_kind"] or "calque", "source": a["term_source_id"], "page": a["term_source_page"],
                        "verified": a["status"] == "approved"}]
        if a.get("radio_english") == "yes":
            equivalents.append({"text": s["term_en"].split(" (")[0], "kind": "loanword", "source": "ATP 1-02.1 (English brevity)", "page": "",
                                "verified": False, "radio": True})
        terms.append({
            "id": sid, "domain": s["domain"], "priority": int(s["priority"]), "termEn": s["term_en"], "acronym": s["acronym"],
            "definitionEn": s["definition_en"], "definitionEnSource": cite, "term": a["term"], "termKind": a["term_kind"],
            "radioEnglish": a.get("radio_english") == "yes", "equivalents": equivalents, "definition": a["definition"],
            "status": a["status"], "badge": a["badge"], "registerNote": e.get("registerNote", ""), "examples": e.get("examples", []),
            "collocations": e.get("collocations", []), "source": "llm", "verified": a["status"] == "approved",
        })
    by_domain: dict[str, list[dict]] = {}
    for t in terms:
        by_domain.setdefault(t["domain"], []).append(t)
    drills = []
    for t in terms:
        if t["priority"] <= 2:  # meaning: target term → English meaning, distractors from the same domain
            pool = [x for x in by_domain[t["domain"]] if x["id"] != t["id"]] or [x for x in terms if x["id"] != t["id"]]
            wrong = [x["termEn"] for x in rng.sample(pool, min(3, len(pool)))]
            choices = wrong + [t["termEn"]]
            rng.shuffle(choices)
            drills.append({"id": f"{t['id']}-meaning", "kind": "meaning", "termId": t["id"], "prompt": t["term"], "choices": choices,
                           "answer": choices.index(t["termEn"]), "explanation": t["definitionEn"][:240]})
        for k, exm in enumerate(t["examples"][:1]):  # fill-in: blank the term in its first example
            if t["term"] in exm["text"]:
                pool = [x["term"] for x in by_domain[t["domain"]] if x["id"] != t["id"] and x["term"] != t["term"]]
                wrong = rng.sample(pool, min(3, len(pool)))
                if len(wrong) == 3:
                    choices = wrong + [t["term"]]
                    rng.shuffle(choices)
                    drills.append({"id": f"{t['id']}-fill-{k}", "kind": "fill_in", "termId": t["id"], "prompt": exm["text"].replace(t["term"], "＿＿＿", 1),
                                   "choices": choices, "answer": choices.index(t["term"]), "explanation": exm["english"]})
        if t["domain"] == "brevity":  # radio brevity: English word → the form the partner force uses on the radio
            radio = t["termEn"].split(" (")[0] if t["radioEnglish"] else t["term"]
            drills.append({"id": f"{t['id']}-brevity", "kind": "brevity", "termId": t["id"], "prompt": t["termEn"].split(" (")[0],
                           "choices": [], "answer": 0, "expected": radio,
                           "explanation": ("The partner force uses the English brevity word on the radio. " if t["radioEnglish"] else
                                           f"Radio form: {t['term']}. ") + t["definitionEn"][:200]})
    for i, r in enumerate(xt.get("register", [])):
        drills.append({"id": f"{TRACK}-{lang}-register-{i + 1:02d}", "kind": "register", "termId": r.get("termId", ""), "prompt": r["situation"],
                       "choices": [clean_text(c) for c in r["choices"]], "answer": r["answer"], "explanation": r["explanation"]})
    openers = xt.get("openers", {})
    scenarios = [{"id": s["id"], "title": s["title"], "level": s["level"], "situation": s["situation"], "learnerRole": s["learnerRole"],
                  "partnerRole": s["partnerRole"], "tags": s["tags"], "terms": s["terms"], "opener": openers.get(s["id"], {}).get("opener", ""),
                  "openerEnglish": openers.get(s["id"], {}).get("english", ""), "source": "llm", "verified": False} for s in cat]
    dialogues = [{"id": f"{TRACK}-{lang}-dlg-{sid}", "scenario": sid, "title": d["title"],
                  "level": next(s["level"] for s in cat if s["id"] == sid), "lines": d["lines"], "source": "llm", "verified": False}
                 for sid, d in xt.get("dialogues", {}).items()]
    out = HERE / f"{TRACK}.{lang}.json"
    old = json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}
    scenarios = review_state.carry("scenario", lang, scenarios, old.get("scenarios", []))
    dialogues = review_state.carry("dialogue", lang, dialogues, old.get("dialogues", []))
    track = {"format": "mokuhyo-track/1", "id": TRACK, "lang": lang, "title": TITLE, "version": "1.0.0",
             "built": dt.datetime.now(dt.UTC).strftime("%Y-%m-%d"), "license": LICENSE, "attribution": ATTRIBUTION,
             "terms": terms, "drills": drills, "scenarios": scenarios, "dialogues": dialogues,
             "sources": {sid: S.cite(sid) for sid in sorted({e["source"] for t in terms for e in t["equivalents"] if e["source"] in S.rows()}
                                                           | {t["definitionEnSource"].get("sourceId", "") for t in terms} - {""})}}
    out.write_text(json.dumps(track, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    merge_probes(lang, xt.get("probes", []))
    print(f"assemble {lang}: {len(terms)} terms ({sum(1 for t in terms if t['examples'])} with examples) · {len(drills)} drills · "
          f"{len(scenarios)} scenarios ({sum(1 for s in scenarios if s['opener'])} with openers) · {len(dialogues)} dialogues")
    return track


def merge_probes(lang: str, probes: list[dict]) -> None:
    """Adds the track's OPI probes to tools/opi/<lang>.json (replacing earlier track probes, keeping everything else)."""
    if not probes:
        return
    p = OPI / f"{lang}.json"
    pack = json.loads(p.read_text(encoding="utf-8"))
    keep = [q for q in pack["questions"] if not q["id"].startswith(f"{lang}-cuas-probe-")]
    for i, q in enumerate(probes, 1):
        keep.append({"id": f"{lang}-cuas-probe-{i:02d}", "phase": q["phase"], "level": q["level"], "prompt": q["prompt"],
                     "english": q["english"], "domain": "military", "track": TRACK, "source": "llm", "verified": False})
    pack["questions"] = keep
    p.write_text(json.dumps(pack, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["examples", "extras", "assemble", "all"])
    ap.add_argument("--language", default="all")
    a = ap.parse_args()
    langs = list(langtext.LANGS) if a.language == "all" else a.language.split(",")
    try:
        if a.stage in ("examples", "extras", "all"):
            client = llm.Client.from_args()
            client.ping()
            for lang in langs:
                if a.stage in ("examples", "all"):
                    examples(client, lang)
            for lang in langs:
                if a.stage in ("extras", "all"):
                    extras(client, lang)
        if a.stage in ("assemble", "all"):
            for lang in langs:
                assemble(lang)
    except llm.EndpointDown as e:
        print(f"build_track: {e}\nrerun: {e.rerun}")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
