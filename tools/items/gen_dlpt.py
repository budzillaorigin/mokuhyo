"""DLPT-style practice item banks for every language: validate, draft, second-opinion check, merge (BRIEF §5.3, §7).

Banks live in items/bank/<lang>/reading.json and listening.json (schema: docs/CONTENT_PACKS.md "Exam item banks").
Everything drafted is source="llm", verified=false and badged in the app until a human accepts it in review.py.

  validate  Schema checks plus the ILR bands in items/ilr_bands.json (length, sentence length, rare-word ratio,
            target-language purity). --strict turns band misses into errors.
  draft     Drafts passages + English multiple-choice items through the §7.1 endpoint (primary model) into a
            resumable staging file items/drafts/<lang>-<skill>.jsonl. Drafts that fail validation are retried.
  check     Second opinion (LLM_CHECK_MODEL, gpt-oss:20b): answers each staged item without the key; items it gets
            wrong or calls ambiguous fail. Run after draft, so the GPU swaps models once per batch.
  merge     Moves checked drafts into the bank (atomic write; never partial).
  fill      draft → check → merge until every ILR band has --per-band passages (resumable; the Phase 3/6 driver).
  status    Counts per language, skill and band against the targets.

Run (from tools/):
  uv run --group content python items/gen_dlpt.py status
  uv run --group content python items/gen_dlpt.py fill --language es --skill reading --per-band 12
  uv run --group content python items/gen_dlpt.py validate --strict --language es
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import re
import sys
import tempfile
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import langtext
import llm

BANDS_PATH = HERE / "ilr_bands.json"
BANK_DIR = HERE / "bank"
DRAFTS = HERE / "drafts"

SKILLS = ("reading", "listening")
EXAM = {"reading": "DLPT_READING", "listening": "DLPT_LISTENING"}
LEVELS = ("0+", "1", "1+", "2", "2+", "3")
QUESTION_TYPES = ("main_idea", "detail", "inference", "purpose", "vocabulary_in_context", "tone")
VOICES = ("female", "male")
LEVEL_SLUG = {"0+": "0p", "1": "1", "1+": "1p", "2": "2", "2+": "2p", "3": "3"}
ID_RE = re.compile(r"^[a-z]{2}(-[A-Za-z]+)?-(dr|dl)-(0p|1p|2p|0|1|2|3)-[a-z_]+-\d{3}$")
LICENSE = "CC BY-SA 4.0"
ATTRIBUTION = "Mokuhyo contributors (AI-drafted with Mistral-Small-3.2-24B, pending human review)"

LANG_NAMES = {"ja": "Japanese", "es": "Spanish", "fr": "French", "de": "German", "pt-BR": "Brazilian Portuguese",
              "ru": "Russian", "zh-Hans": "Mandarin Chinese (Simplified characters)", "ko": "Korean",
              "ar": "Modern Standard Arabic", "fa": "Persian (Farsi, Iranian standard)", "id": "Indonesian"}

# Paraphrases of the public ILR skill descriptions (US Government, public domain), used to steer drafting.
ILR_READING = {
    "0+": "can read numbers, isolated words and short phrases in very predictable contexts: signs, labels, schedules, menus, simple forms",
    "1": "can read very simple connected texts on familiar everyday topics: short notices, simple messages, basic announcements and instructions",
    "1+": "can get the main idea and some details of simple authentic texts: brief news items, straightforward instructions, personal letters",
    "2": "can read simple authentic prose on familiar concrete subjects: factual news reports, routine letters and reports, descriptions of events and people",
    "2+": "can follow more complex factual material and some opinion: news analysis, extended reports, feature articles, with some inference",
    "3": "can read a variety of authentic prose on unfamiliar subjects, including abstract argument, editorials and analysis, inferring the author's purpose and tone",
}
ILR_LISTENING = {
    "0+": "can understand a number of memorized utterances in areas of immediate need: short announcements, prices, times, simple requests",
    "1": "can understand utterances about basic survival needs and simple everyday exchanges: announcements, voicemails, short conversations",
    "1+": "can understand short conversations and simple broadcasts on familiar topics: news briefs, phone calls, instructions",
    "2": "can understand conversations and broadcasts on routine social and work topics: news, interviews, factual reports",
    "2+": "can understand most routine and some complex speech: discussions, analysis on the radio, interviews with opinion",
    "3": "can understand the essentials of all speech in a standard dialect, including lectures, debates, commentary and argument on abstract topics",
}

TOPICS = [
    "public transportation changes", "a local election", "a new hospital", "food prices", "a regional festival",
    "a flood warning", "a university admissions change", "a new trade agreement", "renewable energy investment",
    "a military exercise with an allied country", "a border crossing procedure", "disaster relief after an earthquake",
    "a cyberattack on a public utility", "tourism recovery", "housing shortages in cities", "an aging population",
    "a vaccination campaign", "a sports championship", "a museum exhibition", "a new metro line",
    "water shortages in summer", "a factory closure", "a technology start-up", "smartphone use by children",
    "a naval port visit", "humanitarian aid convoys", "an international summit", "inflation and interest rates",
    "a new law on road safety", "forest fires", "air pollution in a capital city", "a strike by transport workers",
    "a space launch", "fishing rights", "a historical anniversary", "migration and labor", "a peacekeeping mission",
    "a refugee camp", "online education", "a community volunteer program", "a weather forecast for the weekend",
    "a hotel reservation problem", "a lost passport", "a job interview", "a doctor's appointment", "renting an apartment",
    "a company relocating", "agricultural drought", "a new airport terminal", "a debate on military service",
    "freedom of the press", "artificial intelligence regulation", "corruption investigation", "energy subsidies",
    "an evacuation of foreign nationals", "a joint search-and-rescue operation", "a cultural exchange program",
    "the cost of weddings", "public library services", "electric cars", "a heat wave", "coastal erosion",
]


# --- Validation ------------------------------------------------------------------------------------------


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def error(self, where: str, msg: str) -> None:
        self.errors.append(f"{where}: {msg}")

    def warn(self, where: str, msg: str) -> None:
        self.warnings.append(f"{where}: {msg}")


def load_bands() -> dict:
    return json.loads(BANDS_PATH.read_text(encoding="utf-8"))


def passage_text(p: dict) -> str:
    if p.get("script"):
        return "\n".join(line.get("text", "") for line in p["script"])
    return p.get("body", "")


def band_misses(p: dict, lang: str, bands: dict) -> list[str]:
    band = bands["levels"].get(p.get("level"))
    skill = "listening" if p.get("exam") == "DLPT_LISTENING" else "reading"
    if band is None:
        return [f"unknown level {p.get('level')}"]
    m = langtext.measure(passage_text(p), lang)
    out = []
    words = m.scaled_tokens(lang)
    lo, hi = band["words"][skill]
    if not lo <= words <= hi:
        out.append(f"length {words:.0f} words outside {lo}–{hi}")
    lo, hi = band["meanSentence"]
    ms = m.mean_sentence / langtext.TOKEN_FACTOR.get(lang, 1.0)
    if not lo <= ms <= hi:
        out.append(f"mean sentence {ms:.1f} words outside {lo}–{hi}")
    lo, hi = band["rareRatio"]
    if not lo <= m.rare_ratio <= hi:
        out.append(f"rare-word ratio {m.rare_ratio:.2f} outside {lo:.2f}–{hi:.2f}")
    if m.purity < bands["purity"]:
        out.append(f"only {m.purity:.0%} of letters are in the {lang} script")
    return out


def is_nfc(s: str) -> bool:
    return unicodedata.normalize("NFC", s) == s


def validate_bank(bank: dict, lang: str, skill: str, report: Report, bands: dict, strict: bool, name: str) -> None:
    if bank.get("language") != lang:
        report.error(name, f"language must be '{lang}'")
    for key in ("bank", "title", "license", "attribution"):
        if not isinstance(bank.get(key), str) or not bank[key].strip():
            report.error(name, f"missing {key}")
    passages = bank.get("passages", [])
    items = bank.get("items", [])
    pids: dict[str, dict] = {}
    for p in passages:
        where = f"{name} {p.get('id')}"
        pid = p.get("id", "")
        if pid in pids:
            report.error(where, "duplicate passage id")
        pids[pid] = p
        if not ID_RE.match(pid) or not pid.startswith(lang.split("-")[0]):
            report.error(where, "id must look like '<lang>-dr-2-news-001'")
        if p.get("exam") != EXAM[skill]:
            report.error(where, f"exam must be {EXAM[skill]}")
        if p.get("level") not in LEVELS:
            report.error(where, f"level must be one of {LEVELS}")
        if p.get("source") not in ("llm", "human", "verified"):
            report.error(where, "source must be llm|human|verified")
        if skill == "listening":
            script = p.get("script")
            if not isinstance(script, list) or not script:
                report.error(where, "listening passages need a non-empty script")
            else:
                for i, line in enumerate(script):
                    if not isinstance(line, dict) or not str(line.get("text", "")).strip():
                        report.error(where, f"script[{i}] needs text")
                    elif line.get("voice") not in VOICES:
                        report.error(where, f"script[{i}] voice must be female|male")
        elif not str(p.get("body", "")).strip():
            report.error(where, "reading passages need a body")
        for s in [p.get("body", ""), p.get("title", "")] + [x.get("text", "") for x in p.get("script", [])]:
            if not is_nfc(s):
                report.error(where, "text is not NFC")
                break
        if p.get("level") in LEVELS:
            for miss in band_misses(p, lang, bands):
                (report.error if strict else report.warn)(where, miss)
    per_passage: dict[str, int] = {}
    seen: set[str] = set()
    for it in items:
        where = f"{name} {it.get('id')}"
        if it.get("id") in seen:
            report.error(where, "duplicate item id")
        seen.add(it.get("id"))
        pid = it.get("passageId")
        if pid not in pids:
            report.error(where, f"unknown passage {pid}")
            continue
        per_passage[pid] = per_passage.get(pid, 0) + 1
        if it.get("level") != pids[pid].get("level"):
            report.error(where, "item level differs from its passage")
        if it.get("type") not in QUESTION_TYPES:
            report.error(where, f"type must be one of {QUESTION_TYPES}")
        choices = it.get("choices")
        if not isinstance(choices, list) or len(choices) != 4 or len({c.strip().lower() for c in choices}) != 4:
            report.error(where, "needs exactly 4 distinct choices")
        elif not isinstance(it.get("answer"), int) or not 0 <= it["answer"] < 4:
            report.error(where, "answer must be 0–3")
        stem = it.get("stem", "")
        if not stem.strip():
            report.error(where, "empty stem")
        # Lower-range DLPT style: questions and choices in English.
        if not is_english(stem, choices or []):
            report.error(where, "stem and choices must be in English")
    for pid, p in pids.items():
        n = per_passage.get(pid, 0)
        lo, hi = bands["levels"].get(p.get("level"), {}).get("itemsPerPassage", [1, 4])
        if not lo <= n <= hi:
            (report.error if strict else report.warn)(f"{name} {pid}", f"{n} items; level {p.get('level')} wants {lo}–{hi}")


ENGLISH_CUES = frozenset(
    ["the", "a", "an", "of", "to", "is", "are", "was", "were", "what", "which", "who", "whom", "whose", "why", "how", "when", "where", "does", "do", "did", "according", "would", "could", "should", "most", "best", "main", "mainly", "author", "speaker", "text", "passage", "article", "message", "announcement"]
)


def is_english(stem: str, choices: list[str]) -> bool:
    """Stems are full English questions (at least one common English function word, mostly ASCII letters);
    choices may be short but must be mostly ASCII letters too."""
    words = re.findall(r"[A-Za-z']+", stem.lower())
    if not ENGLISH_CUES.intersection(words):
        return False
    text = " ".join([stem, *choices])
    letters = [c for c in text if c.isalpha()]
    return sum(c.isascii() for c in letters) >= 0.85 * max(1, len(letters))


def bank_path(lang: str, skill: str) -> Path:
    return BANK_DIR / lang / f"{skill}.json"


def load_bank(lang: str, skill: str) -> dict:
    path = bank_path(lang, skill)
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {"bank": f"{lang.lower()}-{skill}-core", "language": lang, "title": f"{LANG_NAMES[lang]} {skill}: core bank",
            "license": LICENSE, "attribution": ATTRIBUTION, "passages": [], "items": []}


def write_atomic(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    os.replace(tmp, path)


def cmd_validate(args) -> int:
    bands = load_bands()
    langs = langtext.LANGS if args.language == "all" else [args.language]
    total_err = 0
    for lang in langs:
        for skill in SKILLS:
            path = bank_path(lang, skill)
            if not path.exists():
                print(f"{lang} {skill}: no bank")
                continue
            report = Report()
            validate_bank(json.loads(path.read_text(encoding="utf-8")), lang, skill, report, bands, args.strict, f"{lang}/{skill}")
            for e in report.errors[: args.show]:
                print("ERROR", e)
            if args.show_warnings:
                for w in report.warnings[: args.show]:
                    print("warn ", w)
            print(f"{lang} {skill}: {len(report.errors)} errors, {len(report.warnings)} warnings")
            total_err += len(report.errors)
    return 1 if total_err else 0


# --- Drafting --------------------------------------------------------------------------------------------


def draft_schema(skill: str, n_items: int) -> dict:
    item = {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": list(QUESTION_TYPES)},
            "stem": {"type": "string"},
            "choices": {"type": "array", "items": {"type": "string"}, "minItems": 4, "maxItems": 4},
            "answer": {"type": "integer", "minimum": 0, "maximum": 3},
            "explanation": {"type": "string"},
        },
        "required": ["type", "stem", "choices", "answer", "explanation"],
    }
    props: dict = {"title": {"type": "string"}, "items": {"type": "array", "items": item, "minItems": n_items, "maxItems": n_items}}
    if skill == "reading":
        props["body"] = {"type": "string"}
        req = ["title", "body", "items"]
    else:
        props["script"] = {"type": "array", "minItems": 1, "items": {"type": "object", "properties": {
            "speaker": {"type": "string"}, "voice": {"type": "string", "enum": list(VOICES)}, "text": {"type": "string"}},
            "required": ["speaker", "voice", "text"]}}
        req = ["title", "script", "items"]
    return {"type": "object", "properties": props, "required": req}


def draft_messages(lang: str, skill: str, level: str, text_type: str, topic: str, band: dict, n_items: int,
                   avoid: list[str], feedback: str | None) -> list[dict]:
    name = LANG_NAMES[lang]
    lo, hi = band["words"][skill]
    target = int((lo + hi) / 2)
    desc = (ILR_READING if skill == "reading" else ILR_LISTENING)[level]
    qtypes = ", ".join(band["questionTypes"])
    form = (
        f"Write the passage body in {name}." if skill == "reading" else
        f"Write a listening script in {name}: a list of lines, each with a speaker label (in {name}), a voice "
        f"('female' or 'male', alternate for different speakers) and the spoken text. Use natural spoken {name}."
    )
    system = (
        "You write ORIGINAL practice material for foreign-language reading and listening proficiency tests that follow "
        "the US Interagency Language Roundtable (ILR) scale, in the style of a multiple-choice test with English "
        "questions about a target-language text. Never copy or imitate real test items or copyrighted text; invent "
        "places, organizations and people when needed (but keep them plausible), and never include personal data. "
        "The text must read like an authentic document of its type written by a native speaker for native speakers."
    )
    user = f"""Language: {name}
ILR level: {level} — a reader/listener at this level {desc}.
Text type: {text_type.replace('_', ' ')}
Topic: {topic}
Length: about {target} words (between {lo} and {hi} English-equivalent words); keep sentence length typical for the level.
{form}
Give it a short English title.

Then write exactly {n_items} multiple-choice question(s) IN ENGLISH about the text:
- question types to use (vary them): {qtypes}
- each with exactly 4 English answer choices, one clearly correct according to the text, three plausible distractors
  that a learner who misread the text might choose; no "all of the above"; answer is the 0-based index of the
  correct choice; put the correct answer at varied positions
- answerable only from the text, not from general knowledge; test understanding at ILR {level}
- explanation (English): why the answer is right, quoting the relevant words of the text in {name}
"""
    if avoid:
        user += "\nDo not reuse these titles or scenarios: " + "; ".join(avoid[-12:]) + "\n"
    if feedback:
        user += f"\nA previous draft was rejected: {feedback}. Fix that.\n"
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def next_index(lang: str, skill: str, level: str, text_type: str, taken: set[str]) -> str:
    prefix = f"{lang.split('-')[0]}-{'dr' if skill == 'reading' else 'dl'}-{LEVEL_SLUG[level]}-{text_type}-"
    n = 1
    while f"{prefix}{n:03d}" in taken:
        n += 1
    return f"{prefix}{n:03d}"


def to_entries(raw: dict, lang: str, skill: str, level: str, text_type: str, pid: str, engine: str) -> tuple[dict, list[dict]]:
    nfc = langtext.nfc
    passage = {"id": pid, "exam": EXAM[skill], "language": lang, "level": level, "textType": text_type,
               "title": nfc(raw.get("title", "").strip()), "body": nfc(raw.get("body", "").strip()) if skill == "reading" else "",
               "script": [{"speaker": nfc(x["speaker"].strip()), "voice": x["voice"], "text": nfc(x["text"].strip())}
                          for x in raw.get("script", [])] if skill == "listening" else [],
               "source": "llm", "verified": False, "engine": engine,
               "drafted": dt.datetime.now(dt.UTC).date().isoformat()}
    items = []
    rng = random.Random(pid)
    for i, q in enumerate(raw.get("items", []), 1):
        # Models favour one key position; reshuffle so keys are balanced (BRIEF §5.1).
        choices = [nfc(c.strip()) for c in q["choices"]]
        correct = choices[q["answer"]]
        rng.shuffle(choices)
        items.append({"id": f"{pid}-q{i}", "exam": EXAM[skill], "level": level, "type": q["type"], "passageId": pid,
                      "stem": nfc(q["stem"].strip()), "choices": choices,
                      "answer": choices.index(correct), "explanation": nfc(q.get("explanation", "").strip()),
                      "source": "llm", "verified": False})
    return passage, items


def staging(lang: str, skill: str) -> Path:
    return DRAFTS / f"{lang}-{skill}.jsonl"


def read_staging(lang: str, skill: str) -> list[dict]:
    path = staging(lang, skill)
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_staging(lang: str, skill: str, rows: list[dict]) -> None:
    path = staging(lang, skill)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    os.replace(tmp, path)


def draft_one(client: llm.Client, lang: str, skill: str, level: str, bands: dict, taken: set[str], avoid: list[str],
              rng: random.Random, tries: int = 3) -> dict | None:
    band = bands["levels"][level]
    text_type = rng.choice(band["textTypes"][skill])
    topic = rng.choice(TOPICS)
    n_items = 3 if level not in ("0+", "1") else rng.choice(band["itemsPerPassage"])
    feedback = None
    for _ in range(tries):
        try:
            raw = client.chat_json(draft_messages(lang, skill, level, text_type, topic, band, n_items, avoid, feedback),
                                   draft_schema(skill, n_items), temperature=0.8, max_tokens=3000)
        except (ValueError, KeyError, RuntimeError) as e:
            if isinstance(e, llm.EndpointDown):
                raise
            feedback = f"invalid JSON ({e})"
            continue
        pid = next_index(lang, skill, level, text_type, taken)
        try:
            passage, items = to_entries(raw, lang, skill, level, text_type, pid, client.model)
        except (KeyError, TypeError, AttributeError) as e:
            feedback = f"missing fields ({e})"
            continue
        report = Report()
        validate_bank({"bank": "x", "language": lang, "title": "x", "license": "x", "attribution": "x",
                       "passages": [passage], "items": items}, lang, skill, report, bands, True, "draft")
        if report.errors:
            feedback = "; ".join(e.split(": ", 1)[-1] for e in report.errors[:4])
            continue
        taken.add(pid)
        avoid.append(passage["title"])
        return {"passage": passage, "items": items, "check": None}
    return None


def cmd_draft(args, client: llm.Client | None = None) -> int:
    client = client or llm.Client.from_args(args.endpoint, args.model, args.check_model)
    try:
        client.ping()
    except llm.EndpointDown as e:
        print(f"ERROR {e}\nre-run: {e.rerun}", file=sys.stderr)
        return 2
    bands = load_bands()
    rows = read_staging(args.language, args.skill)
    bank = load_bank(args.language, args.skill)
    taken = {p["id"] for p in bank["passages"]} | {r["passage"]["id"] for r in rows}
    avoid = [p["title"] for p in bank["passages"] if p["level"] == args.ilr] + [r["passage"]["title"] for r in rows]
    rng = random.Random(f"{args.language}{args.skill}{args.ilr}{len(taken)}")
    made = 0
    for _ in range(args.n):
        try:
            row = draft_one(client, args.language, args.skill, args.ilr, bands, taken, avoid, rng)
        except llm.EndpointDown as e:
            print(f"ERROR {e}\nre-run: {e.rerun}", file=sys.stderr)
            write_staging(args.language, args.skill, rows)
            return 2
        if row:
            rows.append(row)
            made += 1
            write_staging(args.language, args.skill, rows)  # resumable after every passage
            print(f"  drafted {row['passage']['id']}: {row['passage']['title']}", flush=True)
        else:
            print(f"  gave up on one {args.language} {args.skill} {args.ilr} draft after 3 tries", flush=True)
    print(f"{args.language} {args.skill} {args.ilr}: drafted {made}/{args.n}")
    return 0


# --- Second opinion --------------------------------------------------------------------------------------

CHECK_SCHEMA = {"type": "object", "properties": {"answers": {"type": "array", "items": {"type": "object", "properties": {
    "answer": {"type": "integer", "minimum": -1, "maximum": 3}, "ambiguous": {"type": "boolean"}},
    "required": ["answer", "ambiguous"]}}}, "required": ["answers"]}


def check_row(client: llm.Client, row: dict) -> tuple[bool, str]:
    p = row["passage"]
    text = passage_text(p) if not p.get("script") else "\n".join(f"{x['speaker']}: {x['text']}" for x in p["script"])
    qs = "\n".join(
        f"Q{i}. {it['stem']}\n" + "\n".join(f"  {j}) {c}" for j, c in enumerate(it["choices"]))
        for i, it in enumerate(row["items"], 1)
    )
    messages = [
        {"role": "system", "content": "You are checking practice test items. Answer each question using only the text. "
                                      "Mark a question ambiguous if two choices could be correct or none is."},
        {"role": "user", "content": f"Text ({LANG_NAMES.get(p['language'], p['language'])}):\n{text}\n\nQuestions:\n{qs}\n\n"
                                    "For each question give the 0-based index of the correct choice (or -1 if none) and "
                                    "whether it is ambiguous."},
    ]
    out = client.chat_json(messages, CHECK_SCHEMA, temperature=0.0, max_tokens=4000, model=client.fallback or client.model)
    answers = out.get("answers", [])
    problems = []
    for i, it in enumerate(row["items"]):
        a = answers[i] if i < len(answers) else {"answer": -1, "ambiguous": True}
        if a.get("ambiguous"):
            problems.append(f"q{i + 1} ambiguous")
        elif a.get("answer") != it["answer"]:
            problems.append(f"q{i + 1} checker chose {a.get('answer')} not {it['answer']}")
    return (not problems), "; ".join(problems)


def cmd_check(args, client: llm.Client | None = None) -> int:
    client = client or llm.Client.from_args(args.endpoint, args.model, args.check_model)
    rows = read_staging(args.language, args.skill)
    done = 0
    for row in rows:
        if row.get("check") is not None:
            continue
        try:
            ok, why = check_row(client, row)
        except llm.EndpointDown as e:
            print(f"ERROR {e}\nre-run: {e.rerun}", file=sys.stderr)
            write_staging(args.language, args.skill, rows)
            return 2
        except (ValueError, RuntimeError) as e:
            ok, why = False, f"checker failed: {e}"
        row["check"] = {"ok": ok, "why": why, "model": client.fallback or client.model}
        done += 1
        write_staging(args.language, args.skill, rows)
        print(f"  {'pass' if ok else 'FAIL'} {row['passage']['id']} {why}", flush=True)
    print(f"{args.language} {args.skill}: checked {done}")
    return 0


def cmd_merge(args) -> int:
    rows = read_staging(args.language, args.skill)
    bank = load_bank(args.language, args.skill)
    keep, moved, dropped = [], 0, 0
    ids = {p["id"] for p in bank["passages"]}
    for row in rows:
        chk = row.get("check")
        if chk is None:
            keep.append(row)
        elif chk["ok"] and row["passage"]["id"] not in ids:
            row["passage"]["checkedBy"] = chk["model"]
            bank["passages"].append(row["passage"])
            bank["items"].extend(row["items"])
            ids.add(row["passage"]["id"])
            moved += 1
        else:
            dropped += 1
    bank["passages"].sort(key=lambda p: (LEVELS.index(p["level"]), p["id"]))
    order = {p["id"]: i for i, p in enumerate(bank["passages"])}
    bank["items"].sort(key=lambda it: (order[it["passageId"]], it["id"]))
    write_atomic(bank_path(args.language, args.skill), bank)
    write_staging(args.language, args.skill, keep)
    print(f"{args.language} {args.skill}: merged {moved}, dropped {dropped} that failed the check, {len(keep)} still staged")
    return 0


def counts(lang: str, skill: str) -> dict[str, int]:
    bank = load_bank(lang, skill)
    out = {lv: 0 for lv in LEVELS}
    for p in bank["passages"]:
        if p["level"] in out:
            out[p["level"]] += 1
    return out


def cmd_fill(args) -> int:
    client = llm.Client.from_args(args.endpoint, args.model, args.check_model)
    try:
        client.ping()
    except llm.EndpointDown as e:
        print(f"ERROR {e}\nre-run: {e.rerun}", file=sys.stderr)
        return 2
    langs = langtext.LANGS if args.language == "all" else args.language.split(",")
    skills = SKILLS if args.skill == "both" else (args.skill,)
    for lang in langs:
        for skill in skills:
            for _round in range(args.rounds):
                have = counts(lang, skill)
                staged = read_staging(lang, skill)
                pending = {lv: sum(1 for r in staged if r["passage"]["level"] == lv and r.get("check") is None) for lv in LEVELS}
                need = {lv: max(0, args.per_band - have[lv] - pending[lv]) for lv in LEVELS}
                if not any(need.values()) and not any(pending.values()):
                    break
                for lv in LEVELS:
                    if need[lv]:
                        ns = argparse.Namespace(language=lang, skill=skill, ilr=lv, n=need[lv])
                        rc = cmd_draft(ns, client)
                        if rc:
                            return rc
                rc = cmd_check(argparse.Namespace(language=lang, skill=skill), client)
                if rc:
                    return rc
                cmd_merge(argparse.Namespace(language=lang, skill=skill))
            print(f"== {lang} {skill}: {counts(lang, skill)}", flush=True)
    return 0


def cmd_status(args) -> int:
    print(f"{'lang':8} {'skill':10} " + " ".join(f"{lv:>4}" for lv in LEVELS) + "   items  verified")
    for lang in langtext.LANGS:
        for skill in SKILLS:
            bank = load_bank(lang, skill)
            c = counts(lang, skill)
            print(f"{lang:8} {skill:10} " + " ".join(f"{c[lv]:>4}" for lv in LEVELS)
                  + f"   {len(bank['items']):>5}  {sum(1 for p in bank['passages'] if p.get('verified'))}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("validate")
    v.add_argument("--language", default="all")
    v.add_argument("--strict", action="store_true")
    v.add_argument("--show", type=int, default=20)
    v.add_argument("--show-warnings", action="store_true")
    for name in ("draft", "check", "merge", "fill"):
        p = sub.add_parser(name)
        p.add_argument("--language", required=True, help="BCP-47 code, comma list, or 'all' (fill)")
        p.add_argument("--skill", required=True, choices=SKILLS + (("both",) if name == "fill" else ()))
        if name == "draft":
            p.add_argument("--ilr", required=True, choices=LEVELS)
            p.add_argument("--n", type=int, default=1)
        if name == "fill":
            p.add_argument("--per-band", type=int, default=12)
            p.add_argument("--rounds", type=int, default=3)
        llm.add_args(p)
    sub.add_parser("status")
    args = ap.parse_args(argv)
    return {"validate": cmd_validate, "draft": cmd_draft, "check": cmd_check, "merge": cmd_merge, "fill": cmd_fill,
            "status": cmd_status}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
