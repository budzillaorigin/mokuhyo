#!/usr/bin/env python3
"""Exemplar answers (BRIEF_PHASE8 N-05): tools/exemplars/<lang>.json → packs/<lang>/exemplars.json.

For 30 interview prompts per language (level checks and probes from the language's OPI bank, ILR 1–3, plus the
Counter-UAS track probes when present) the drafting model writes model answers at ILR 1+, 2 and 3 — 90 per language —
each with a two-line English note on why it is that level and not the next, tied to the ILR factors (functions,
content, accuracy, text type). AI-drafted, badged until reviewed; audio is rendered by packs/render_audio.py for the
languages with a bundled voice.

  uv run --group content python exemplars/build_exemplars.py --language all
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "items"))

import langtext
import llm
import review_state

LEVELS = ("1+", "2", "3")
NAMES = {"ja": "Japanese", "es": "Spanish", "fr": "French", "de": "German", "pt-BR": "Brazilian Portuguese", "ru": "Russian",
         "zh-Hans": "Mandarin Chinese (Simplified)", "ko": "Korean", "ar": "Modern Standard Arabic", "fa": "Persian", "id": "Indonesian"}
FACTORS = {
    "1+": "short connected sentences on familiar topics; can narrate simply but breaks down under complication",
    "2": "paragraph-length narration and description in past, present and future on concrete topics with good control",
    "3": "extended discourse supporting an opinion and hypothesizing on abstract topics with precise vocabulary and few errors",
}
SCHEMA = {"type": "object", "properties": {"answers": {"type": "array", "minItems": 3, "maxItems": 3, "items": {"type": "object", "properties": {
    "level": {"type": "string", "enum": list(LEVELS)}, "response": {"type": "string"}, "note": {"type": "string"}},
    "required": ["level", "response", "note"]}}}, "required": ["answers"]}


def prompts(lang: str) -> list[dict]:
    opi = json.loads((TOOLS / "opi" / f"{lang}.json").read_text(encoding="utf-8"))
    track = [q for q in opi["questions"] if q.get("track")]
    bank = [q for q in opi["questions"] if q["phase"] in ("level_check", "probe") and q["level"] in ("1", "1+", "2", "2+", "3") and not q.get("track")]
    rng = random.Random(f"exemplars-{lang}")
    rng.shuffle(bank)
    return (track[:6] + bank)[:30]


def build(client: llm.Client, lang: str) -> dict:
    out_file = HERE / f"{lang}.json"
    old = json.loads(out_file.read_text(encoding="utf-8")) if out_file.exists() else {"exemplars": []}
    have = {e["questionId"] for e in old["exemplars"]}
    exemplars = list(old["exemplars"])
    for q in prompts(lang):
        if q["id"] in have:
            continue
        msg = (f"Interview question (practice oral proficiency interview in {NAMES[lang]}): {q['prompt']}  ({q['english']})\n\n"
               f"Write three model answers a military linguist candidate might give, in natural spoken {NAMES[lang]}: one at ILR 1+ "
               f"({FACTORS['1+']}), one at ILR 2 ({FACTORS['2']}), one at ILR 3 ({FACTORS['3']}). Length grows with the level "
               f"(about 25, 70 and 130 words). For each, note = exactly two English sentences: why it is this level and not the next one "
               f"up, naming the ILR factors (functions, content, accuracy, text type). Invent no real people.")
        for _attempt in range(3):
            try:
                raw = client.chat_json([{"role": "system", "content": "You write graded model answers for oral proficiency practice. JSON only."},
                                        {"role": "user", "content": msg}], SCHEMA, temperature=0.5, max_tokens=3000)
            except (RuntimeError, ValueError) as e:
                if isinstance(e, llm.EndpointDown):
                    raise
                continue
            answers = {a["level"]: a for a in raw.get("answers", [])}
            if set(answers) != set(LEVELS) or any(langtext.foreign_script(a["response"], lang) for a in answers.values()):
                continue
            for lv in LEVELS:
                a = answers[lv]
                exemplars.append({"id": f"{q['id']}-ex-{lv.replace('+', 'p')}", "questionId": q["id"], "prompt": q["prompt"], "english": q["english"],
                                  "level": lv, "response": langtext.nfc(a["response"].strip()), "note": a["note"].strip(), "source": "llm",
                                  "verified": False})
            break
        out_file.write_text(json.dumps({"format": "mokuhyo-exemplars/1", "lang": lang, "license": "CC BY-SA 4.0 (Mokuhyo contributors); AI-drafted, pending review.",
                                        "exemplars": exemplars}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    exemplars = review_state.carry("exemplar", lang, exemplars, old["exemplars"])
    pack = {"format": "mokuhyo-exemplars/1", "lang": lang, "license": "CC BY-SA 4.0 (Mokuhyo contributors); AI-drafted, pending review.", "exemplars": exemplars}
    out_file.write_text(json.dumps(pack, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"exemplars {lang}: {len(exemplars)}", flush=True)
    return pack


def validate(lang: str) -> list[str]:
    """90 per language: 30 questions × ILR 1+/2/3, responses in the language, two-sentence English notes."""
    f = HERE / f"{lang}.json"
    if not f.exists():
        return [f"{lang}: no exemplars"]
    ex = json.loads(f.read_text(encoding="utf-8"))["exemplars"]
    errs = []
    if len(ex) < 90:
        errs.append(f"{lang}: {len(ex)} exemplars (want 90)")
    by_q: dict[str, set[str]] = {}
    for e in ex:
        by_q.setdefault(e["questionId"], set()).add(e["level"])
        if langtext.foreign_script(e["response"], lang):
            errs.append(f"{e['id']}: response not purely {lang}")
        if not e["note"].strip() or langtext.LATIN_WORD.search(e["note"]) is None:
            errs.append(f"{e['id']}: note must be English")
    errs += [f"{lang} {q}: levels {sorted(v)}" for q, v in by_q.items() if v != set(LEVELS)]
    return errs


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--language", default="all")
    ap.add_argument("--validate", action="store_true")
    a = ap.parse_args()
    langs = list(langtext.LANGS) if a.language == "all" else a.language.split(",")
    if a.validate:
        errs = [e for lg in langs for e in validate(lg)]
        for e in errs[:40]:
            print("ERROR " + e)
        print(f"validate exemplars: {len(errs)} errors")
        return 1 if errs else 0
    client = llm.Client.from_args()
    try:
        client.ping()
        for lang in langs:
            build(client, lang)
    except llm.EndpointDown as e:
        print(f"build_exemplars: {e}\nrerun: {e.rerun}")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
