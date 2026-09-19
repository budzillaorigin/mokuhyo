"""DLPT practice item banks: validate and draft (BRIEF §5.11, docs/CONTENT_PACKS.md "Exam item banks").

validate  Schema checks for exam item banks (any exam) plus, for DLPT passages, the ILR level bands in
          items/ilr_bands.json (length, kanji density, abstract-vocabulary ratio). Schema problems are errors;
          band misses are warnings unless --strict. Exits non-zero on errors.

draft     Drafts new DLPT passages + items through any OpenAI-compatible chat endpoint (Ollama, LM Studio,
          llama-server, vLLM). Stdlib only, no API key. Everything drafted is source="llm", verified=false and
          stays that way until a human accepts it in items/review.py (CLAUDE.md rule 10).

Run (from tools/):
  uv run python items/gen_dlpt.py validate items/bank/dlpt_reading.json items/bank/dlpt_listening.json
  uv run python items/gen_dlpt.py draft --endpoint http://localhost:11434/v1 --model qwen2.5:14b \\
      --level 2 --count 3 --exam reading --out items/bank/dlpt_reading_drafts.json
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import unicodedata
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
BANDS_PATH = HERE / "ilr_bands.json"
BLUEPRINTS_PATH = HERE / "jlpt_blueprints.json"

EXAMS = ("JLPT", "DLPT_READING", "DLPT_LISTENING")
DLPT_LEVELS = ("0+", "1", "1+", "2", "2+", "3", "3+", "4")
# The upper range (BRIEF_V2 G-08): own banks, 2–4 items per passage, restricted text types.
UPPER_LEVELS = ("3+", "4")
JLPT_LEVELS = ("N5", "N4", "N3", "N2", "N1")
DLPT_TYPES = ("main_idea", "detail", "inference", "purpose", "vocabulary_in_context", "tone")
VOICES = ("female", "male")
ID_PREFIX = {"DLPT_READING": "dr", "DLPT_LISTENING": "dl"}
# Ids write "+" as "p" so they stay safe in file names and URLs: dr-2p-editorial-001, dl-0p-announcement-002.
LEVEL_SLUG = {"0+": "0p", "1": "1", "1+": "1p", "2": "2", "2+": "2p", "3": "3", "3+": "3p", "4": "4"}
DLPT_PASSAGE_ID = re.compile(r"^(dr|dl)-(0p|1p|2p|3p|0|1|2|3|4)-([a-z]+)-(\d{3})$")
ITEM_ID = re.compile(r"^(?P<passage>.+)-q(?P<n>\d+)$")
BANK_ID = re.compile(r"^(user:)?[a-z0-9][a-z0-9-]*$")

KANJI = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u3005]")  # incl. 々
# Approximate tokens: runs of one script. Crude, but stable and dependency-free.
TOKEN_RUN = re.compile(
    r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u3005]+|[\u3041-\u309f]+|[\u30a0-\u30ff\u31f0-\u31ff]+"
    r"|[A-Za-z\uff21-\uff3a\uff41-\uff5a]+|[0-9\uff10-\uff19]+"
)


# --- Text measures ---------------------------------------------------------------------------------------


def passage_text(passage: dict) -> str:
    """The Japanese text of a passage: body for reading, all script lines for listening."""
    if passage.get("script"):
        return "\n".join(line.get("text", "") for line in passage["script"])
    return passage.get("body", "")


def measure(text: str, lexicon: list[str]) -> dict:
    compact = re.sub(r"\s+", "", text)
    chars = len(compact)
    kanji = len(KANJI.findall(compact))
    tokens = len(TOKEN_RUN.findall(compact))
    hits = 0
    rest = compact
    for word in sorted(lexicon, key=len, reverse=True):  # longest first, non-overlapping
        n = rest.count(word)
        if n:
            hits += n
            rest = rest.replace(word, "\0")
    return {
        "chars": chars,
        "kanjiDensity": kanji / chars if chars else 0.0,
        "abstractRatio": hits / tokens if tokens else 0.0,
        "abstractHits": hits,
        "tokens": tokens,
    }


def band_misses(passage: dict, bands: dict) -> list[str]:
    band = bands["levels"].get(passage.get("level"))
    if band is None:
        return []
    m = measure(passage_text(passage), bands["abstractLexicon"])
    misses = []
    if not band["minChars"] <= m["chars"] <= band["maxChars"]:
        misses.append(f"length {m['chars']} outside {band['minChars']}–{band['maxChars']}")
    lo, hi = band["kanjiDensity"]
    if not lo <= m["kanjiDensity"] <= hi:
        misses.append(f"kanji density {m['kanjiDensity']:.2f} outside {lo:.2f}–{hi:.2f}")
    lo, hi = band["abstractRatio"]
    if not lo <= m["abstractRatio"] <= hi:
        misses.append(
            f"abstract ratio {m['abstractRatio']:.3f} ({m['abstractHits']}/{m['tokens']}) outside {lo:.3f}–{hi:.3f}"
        )
    return misses


# --- Validation ------------------------------------------------------------------------------------------


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def error(self, where: str, msg: str) -> None:
        self.errors.append(f"{where}: {msg}")

    def warn(self, where: str, msg: str) -> None:
        self.warnings.append(f"{where}: {msg}")


def load_bands(path: Path = BANDS_PATH) -> dict:
    bands = json.loads(path.read_text(encoding="utf-8"))
    missing = [lv for lv in DLPT_LEVELS if lv not in bands.get("levels", {})]
    if missing:
        raise SystemExit(f"{path}: missing levels {missing}")
    return bands


def jlpt_types() -> dict[str, set[str]]:
    """JLPT item types allowed per level ("N1".."N5"), from the blueprint file."""
    if not BLUEPRINTS_PATH.exists():
        return {}
    data = json.loads(BLUEPRINTS_PATH.read_text(encoding="utf-8"))
    return {
        f"N{lv['level']}": {it["type"] for sec in lv["sections"] for it in sec["items"]}
        for lv in data["levels"]
    }


def _strings(value, path: str = ""):
    """Yield (path, string) for every string nested in value."""
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, dict):
        for k, v in value.items():
            yield from _strings(v, f"{path}.{k}" if path else k)
    elif isinstance(value, list):
        for i, v in enumerate(value):
            yield from _strings(v, f"{path}[{i}]")


def _nonempty_str(obj: dict, key: str) -> bool:
    return isinstance(obj.get(key), str) and obj[key].strip() != ""


def _check_script(where: str, script, report: Report) -> None:
    if not isinstance(script, list) or not script:
        report.error(where, "script must be a non-empty list")
        return
    for i, line in enumerate(script):
        w = f"{where} script[{i}]"
        if not isinstance(line, dict):
            report.error(w, "line must be an object")
            continue
        for key in ("speaker", "text"):
            if not _nonempty_str(line, key):
                report.error(w, f"missing {key}")
        if line.get("voice") not in VOICES:
            report.error(w, f"voice must be one of {VOICES}")


def validate_bank(
    bank: dict, report: Report, bands: dict, seen_ids: set[str], name: str = "bank", types=None
) -> None:
    """Validate one bank dict, appending to report. seen_ids carries ids across banks (unique across all)."""
    types = jlpt_types() if types is None else types
    if not isinstance(bank, dict):
        report.error(name, "top level must be an object")
        return
    if not isinstance(bank.get("bank"), str) or not BANK_ID.match(bank["bank"]):
        report.error(name, "bank must be a lowercase id like 'dlpt-reading-core' (imports prefixed 'user:')")
    for key in ("title", "license", "attribution"):
        if not _nonempty_str(bank, key):
            report.error(name, f"missing {key}")
    passages = bank.get("passages", [])
    items = bank.get("items")
    if not isinstance(passages, list):
        report.error(name, "passages must be a list")
        passages = []
    if not isinstance(items, list) or not items:
        report.error(name, "items must be a non-empty list")
        items = items if isinstance(items, list) else []

    for path, s in _strings(bank):
        if unicodedata.normalize("NFC", s) != s:
            report.error(name, f"{path} is not NFC")

    by_id: dict[str, dict] = {}
    for i, p in enumerate(passages):
        where = f"{name} passage[{i}]"
        if not isinstance(p, dict):
            report.error(where, "passage must be an object")
            continue
        pid = p.get("id")
        if not isinstance(pid, str) or not pid:
            report.error(where, "missing id")
            continue
        where = f"{name} {pid}"
        if pid in seen_ids:
            report.error(where, "duplicate id")
        seen_ids.add(pid)
        by_id[pid] = p
        _check_common(where, p, report)
        exam = p.get("exam")
        if exam == "DLPT_LISTENING":
            if p.get("body"):
                report.error(where, "listening passages leave body empty and use script")
            _check_script(where, p.get("script"), report)
        else:
            if not _nonempty_str(p, "body"):
                report.error(where, "missing body")
            if p.get("script"):
                report.error(where, "only listening passages have a script")
        if not _nonempty_str(p, "textType"):
            report.error(where, "missing textType")
        if not _nonempty_str(p, "title"):
            report.error(where, "missing title")
        if exam in ID_PREFIX:
            m = DLPT_PASSAGE_ID.match(pid)
            if not m:
                report.warn(where, "id does not follow <dr|dl>-<level>-<texttype>-NNN")
            elif m.group(1) != ID_PREFIX[exam] or m.group(2) != LEVEL_SLUG.get(p.get("level")):
                report.warn(where, "id prefix/level does not match exam/level")
            elif m.group(3) != p.get("textType"):
                report.warn(where, "id text type does not match textType")
            for miss in band_misses(p, bands):
                report.warn(where, f"ILR {p.get('level')} band: {miss}")

    per_passage: dict[str, int] = {pid: 0 for pid in by_id}
    for i, it in enumerate(items):
        where = f"{name} item[{i}]"
        if not isinstance(it, dict):
            report.error(where, "item must be an object")
            continue
        iid = it.get("id")
        if not isinstance(iid, str) or not iid:
            report.error(where, "missing id")
            continue
        where = f"{name} {iid}"
        if iid in seen_ids:
            report.error(where, "duplicate id")
        seen_ids.add(iid)
        _check_common(where, it, report)
        exam, level = it.get("exam"), it.get("level")
        if exam in ID_PREFIX:
            if it.get("type") not in DLPT_TYPES:
                report.error(where, f"type must be one of {DLPT_TYPES}")
        elif exam == "JLPT" and types and it.get("type") not in types.get(level, set()):
            report.error(where, f"type {it.get('type')!r} is not in the JLPT blueprint for {level}")
        if not _nonempty_str(it, "stem"):
            report.error(where, "missing stem")
        choices = it.get("choices")
        if not isinstance(choices, list) or not all(
            isinstance(c, str) and c.strip() for c in choices or [None]
        ):
            report.error(where, "choices must be non-empty strings")
        else:
            allowed = (4,) if exam in ID_PREFIX else (3, 4)
            if len(choices) not in allowed:
                report.error(where, f"expected {' or '.join(map(str, allowed))} choices, got {len(choices)}")
            if len({c.strip() for c in choices}) != len(choices):
                report.error(where, "choices are not distinct")
            ans = it.get("answer")
            if not isinstance(ans, int) or isinstance(ans, bool) or not 0 <= ans < len(choices):
                report.error(where, "answer must be a 0-based index into choices")
        if exam in ID_PREFIX and not _nonempty_str(it, "explanation"):
            report.error(where, "DLPT items need an English explanation")
        if "script" in it:
            _check_script(where, it["script"], report)
        if "refs" in it and not (
            isinstance(it["refs"], list) and all(isinstance(r, str) for r in it["refs"])
        ):
            report.error(where, "refs must be a list of strings")
        pid = it.get("passageId")
        if pid is not None:
            p = by_id.get(pid)
            if p is None:
                report.error(where, f"passageId {pid!r} not found in this bank")
            else:
                per_passage[pid] += 1
                if (p.get("exam"), p.get("level")) != (exam, level):
                    report.error(where, "exam/level differ from its passage")
                m = ITEM_ID.match(iid)
                if exam in ID_PREFIX and (not m or m.group("passage") != pid):
                    report.warn(where, "item id should be <passageId>-qN")
        elif exam in ID_PREFIX:
            report.error(where, "DLPT items need a passageId")

    for pid, n in per_passage.items():
        p = by_id[pid]
        lo = 2 if p.get("level") in UPPER_LEVELS else 1
        if p.get("exam") in ID_PREFIX and not lo <= n <= 4:
            report.warn(f"{name} {pid}", f"has {n} items (expected {lo}–4)")
        _check_upper(f"{name} {pid}", p, report)

    for (exam, level), (longest, total) in sorted(longest_key_stats(items).items()):
        if total >= LONGEST_KEY_MIN_ITEMS and longest / total > LONGEST_KEY_MAX_SHARE:
            report.warn(
                f"{name} {exam} {level}",
                f"the key is the longest choice in {longest}/{total} items; lengthen distractors "
                f"(test-wise learners pick the longest option; keep it under {LONGEST_KEY_MAX_SHARE:.0%})",
            )


def _check_upper(where: str, passage: dict, report: Report) -> None:
    """ILR 3+/4 passages: only the upper-range text types (argument, academic and literary register)."""
    level, exam = passage.get("level"), passage.get("exam")
    if level not in UPPER_LEVELS or exam not in ID_PREFIX:
        return
    kind = "listening" if exam == "DLPT_LISTENING" else "reading"
    allowed = TEXT_TYPES[kind][level]
    if passage.get("textType") not in allowed:
        report.warn(where, f"ILR {level} {kind} text type should be one of {allowed}")


# With 4 choices a key that is uniquely longest ~25% of the time gives nothing away.
LONGEST_KEY_MAX_SHARE = 0.4
LONGEST_KEY_MIN_ITEMS = 10


def longest_key_stats(items: list) -> dict[tuple[str, str], list[int]]:
    """(exam, level) -> [items whose key is the uniquely longest choice, items checked]."""
    stats: dict[tuple[str, str], list[int]] = {}
    for it in items:
        choices, ans = (it.get("choices"), it.get("answer")) if isinstance(it, dict) else (None, None)
        if not isinstance(choices, list) or not isinstance(ans, int) or not 0 <= ans < len(choices):
            continue
        if not all(isinstance(c, str) for c in choices):
            continue
        lengths = [len(c) for c in choices]
        s = stats.setdefault((it.get("exam"), it.get("level")), [0, 0])
        s[1] += 1
        if lengths[ans] == max(lengths) and lengths.count(max(lengths)) == 1:
            s[0] += 1
    return stats


def _check_common(where: str, obj: dict, report: Report) -> None:
    exam = obj.get("exam")
    if exam not in EXAMS:
        report.error(where, f"exam must be one of {EXAMS}")
    levels = JLPT_LEVELS if exam == "JLPT" else DLPT_LEVELS
    if obj.get("level") not in levels:
        report.error(where, f"level must be one of {levels}")
    if not _nonempty_str(obj, "source"):
        report.error(where, "missing source")
    if not isinstance(obj.get("verified"), bool):
        report.error(where, "verified must be true or false")
    elif obj["verified"] and obj.get("source") == "llm" and "reviewed" not in obj:
        report.warn(where, "verified LLM content should carry reviewed {by, on} (use items/review.py)")


def summarize(bank: dict, bands: dict) -> str:
    """Counts per level/text type/item type and answer-key balance, for eyeballing a bank."""
    lines = [
        f"{bank.get('bank')}: {len(bank.get('passages', []))} passages, {len(bank.get('items', []))} items"
    ]
    for lv in DLPT_LEVELS:
        ps = [p for p in bank.get("passages", []) if p.get("level") == lv]
        if not ps:
            continue
        ms = [measure(passage_text(p), bands["abstractLexicon"]) for p in ps]
        tt: dict[str, int] = {}
        for p in ps:
            tt[p.get("textType", "?")] = tt.get(p.get("textType", "?"), 0) + 1
        lines.append(
            f"  {lv:>2}: {len(ps):2} passages · chars {min(m['chars'] for m in ms)}–{max(m['chars'] for m in ms)}"
            f" · kanji {min(m['kanjiDensity'] for m in ms):.2f}–{max(m['kanjiDensity'] for m in ms):.2f}"
            f" · abstract {min(m['abstractRatio'] for m in ms):.3f}–{max(m['abstractRatio'] for m in ms):.3f}"
            f" · {', '.join(f'{k} {v}' for k, v in sorted(tt.items()))}"
        )
    types: dict[str, int] = {}
    answers = [0, 0, 0, 0]
    for it in bank.get("items", []):
        types[it.get("type", "?")] = types.get(it.get("type", "?"), 0) + 1
        if isinstance(it.get("answer"), int) and 0 <= it["answer"] < 4:
            answers[it["answer"]] += 1
    lines.append("  item types: " + ", ".join(f"{k} {v}" for k, v in sorted(types.items())))
    lines.append("  answer keys A–D: " + " / ".join(map(str, answers)))
    return "\n".join(lines)


def cmd_validate(args) -> int:
    bands = load_bands(args.bands)
    report = Report()
    seen: set[str] = set()
    types = jlpt_types()
    for path in args.files:
        try:
            bank = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            report.error(str(path), f"cannot read: {e}")
            continue
        validate_bank(bank, report, bands, seen, name=path.name, types=types)
        if not args.quiet and isinstance(bank, dict):
            print(summarize(bank, bands))
    for w in report.warnings:
        print(f"warning: {w}")
    for e in report.errors:
        print(f"error: {e}")
    print(f"{len(report.errors)} errors, {len(report.warnings)} warnings")
    if report.errors or (args.strict and report.warnings):
        return 1
    return 0


# --- Drafting --------------------------------------------------------------------------------------------

LEVEL_GUIDE = {
    "0+": "Signs, labels, notices, short schedules or forms: isolated words and set phrases, a few short lines.",
    "1": "Simple notices, short messages, very short simple narrative about everyday topics; concrete, "
    "predictable content in simple sentences.",
    "1+": "Short routine texts: personal letters and emails, simple announcements, short factual pieces with "
    "some connected sentences; main ideas of simple factual material.",
    "2": "Straightforward news and factual reporting, instructions and procedures, concrete descriptions; "
    "connected paragraphs in standard written Japanese.",
    "2+": "Longer reporting with some analysis, opinion columns, texts where the writer's stance and some "
    "inference matter; beginning of abstract discussion.",
    "3": "Editorials, argumentative essays, hypotheses and abstract topics; idiom, nuance, implied meaning and "
    "the writer's tone must be understood.",
    "3+": "Dense editorials, academic essays and literary prose: tightly argued, allusive, with concessions, "
    "irony and qualifications the reader must weigh; much of the stance is implied rather than stated.",
    "4": "Highly abstract or specialized argument, literary and philosophical prose, cultural allusion, "
    "four-character idioms and classical echoes; the reader must follow the writer's reasoning, register "
    "shifts and nuance as an educated native reader would.",
}
TEXT_TYPES = {
    "reading": {
        "0+": ["sign", "notice", "schedule"],
        "1": ["notice", "schedule", "email", "narrative"],
        "1+": ["email", "letter", "notice", "narrative", "announcement"],
        "2": ["news", "instructions", "announcement", "report", "liaison"],
        "2+": ["news", "column", "report", "editorial", "liaison"],
        "3": ["editorial", "essay", "column", "liaison"],
        "3+": ["editorial", "academic", "essay", "literary", "commentary"],
        "4": ["editorial", "academic", "essay", "literary", "commentary"],
    },
    "listening": {
        "0+": ["announcement", "voicemail"],
        "1": ["announcement", "voicemail", "conversation"],
        "1+": ["conversation", "voicemail", "announcement"],
        "2": ["broadcast", "conversation", "interview", "briefing", "liaison"],
        "2+": ["interview", "broadcast", "discussion", "liaison"],
        "3": ["discussion", "interview", "commentary", "briefing"],
        "3+": ["lecture", "discussion", "commentary", "interview"],
        "4": ["lecture", "discussion", "commentary", "speech"],
    },
}


def draft_schema(exam: str) -> dict:
    line = {
        "type": "object",
        "properties": {
            "speaker": {"type": "string"},
            "voice": {"type": "string", "enum": list(VOICES)},
            "text": {"type": "string"},
        },
        "required": ["speaker", "voice", "text"],
    }
    item = {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": list(DLPT_TYPES)},
            "stem": {"type": "string"},
            "choices": {"type": "array", "items": {"type": "string"}, "minItems": 4, "maxItems": 4},
            "answer": {"type": "integer", "minimum": 0, "maximum": 3},
            "explanation": {"type": "string"},
        },
        "required": ["type", "stem", "choices", "answer", "explanation"],
    }
    passage = {"textType": {"type": "string"}, "title": {"type": "string"}}
    if exam == "DLPT_LISTENING":
        passage["script"] = {"type": "array", "items": line, "minItems": 1}
    else:
        passage["body"] = {"type": "string"}
    passage["items"] = {"type": "array", "items": item, "minItems": 1, "maxItems": 4}
    return {"type": "object", "properties": passage, "required": list(passage)}


def draft_messages(exam: str, level: str, text_type: str, bands: dict, avoid: list[str]) -> list[dict]:
    band = bands["levels"][level]
    kind = (
        "listening script (announcement, message or conversation; 2 voices for dialogue)"
        if exam == ("DLPT_LISTENING")
        else "reading passage"
    )
    system = (
        "You write original practice material for a Japanese proficiency test on the ILR scale (DLPT-style). "
        "Never copy or paraphrase real test items or published texts; everything must be fictional and original. "
        "Japanese must be natural, grammatical, standard modern Japanese (NFC, full-width punctuation). "
        "Questions and answer choices are in English. Each item has exactly 4 choices, exactly one of which is "
        "correct according to the text; distractors are plausible but clearly contradicted or unsupported by "
        "the text. The explanation (English) says why the key is right and why each distractor is wrong. "
        "Reply with a single JSON object only."
    )
    user = (
        f"Write one ILR level {level} {kind}. Text type: {text_type}.\n"
        f"Level description: {LEVEL_GUIDE[level]}\n"
        f"Length: {band['minChars']}–{band['maxChars']} Japanese characters in total "
        f"(aim for the middle). Kanji should be about {band['kanjiDensity'][0]:.0%}–{band['kanjiDensity'][1]:.0%}"
        " of characters.\n"
        "Then write 1–4 multiple-choice items about it. Item types: "
        + ", ".join(DLPT_TYPES)
        + ". Vary the position of the correct answer.\n"
        + (f"Do not reuse these topics: {'; '.join(avoid[-40:])}.\n" if avoid else "")
        + "JSON fields: textType, title (short English), "
        + ("script (list of {speaker, voice: female|male, text})" if exam == "DLPT_LISTENING" else "body")
        + ", items (list of {type, stem, choices[4], answer (0-based), explanation})."
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def chat(
    endpoint: str, model: str, messages: list[dict], schema: dict | None, temperature: float, timeout: float
):
    url = endpoint.rstrip("/")
    if not url.endswith("/chat/completions"):
        url += "/chat/completions"
    body = {"model": model, "messages": messages, "temperature": temperature, "stream": False}
    if schema is not None:
        body["response_format"] = {
            "type": "json_schema",
            "json_schema": {"name": "dlpt_passage", "schema": schema, "strict": False},
        }
    else:
        body["response_format"] = {"type": "json_object"}
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "User-Agent": "tsumugi-tools"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.load(resp)
    content = data["choices"][0]["message"]["content"]
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip())
    return json.loads(content)


def to_bank_entries(raw: dict, exam: str, level: str, pid: str) -> tuple[dict, list[dict]]:
    """Turn a model reply into a passage + items in bank format, with provenance flags forced."""
    nfc = lambda s: unicodedata.normalize("NFC", str(s)).strip()
    text_type = re.sub(r"[^a-z]", "", str(raw.get("textType", "")).lower()) or "text"
    passage = {
        "id": pid,
        "exam": exam,
        "level": level,
        "textType": text_type,
        "title": nfc(raw.get("title", "")),
        "body": "",
        "source": "llm",
        "verified": False,
    }
    if exam == "DLPT_LISTENING":
        passage["script"] = [
            {"speaker": nfc(ln.get("speaker", "")), "voice": ln.get("voice"), "text": nfc(ln.get("text", ""))}
            for ln in raw.get("script", [])
            if isinstance(ln, dict)
        ]
    else:
        passage["body"] = nfc(raw.get("body", ""))
    items = []
    for n, it in enumerate(raw.get("items", []), start=1):
        if not isinstance(it, dict):
            continue
        items.append(
            {
                "id": f"{pid}-q{n}",
                "exam": exam,
                "level": level,
                "type": it.get("type"),
                "passageId": pid,
                "stem": nfc(it.get("stem", "")),
                "choices": [nfc(c) for c in it.get("choices", [])],
                "answer": it.get("answer"),
                "explanation": nfc(it.get("explanation", "")),
                "source": "llm",
                "verified": False,
            }
        )
    return passage, items


def next_id(exam: str, level: str, text_type: str, taken: set[str]) -> str:
    n = 1
    while True:
        pid = f"{ID_PREFIX[exam]}-{LEVEL_SLUG[level]}-{text_type}-{n:03d}"
        if pid not in taken:
            return pid
        n += 1


def cmd_draft(args) -> int:
    exam = "DLPT_LISTENING" if args.exam == "listening" else "DLPT_READING"
    bands = load_bands(args.bands)
    out: Path = args.out or HERE / "bank" / f"dlpt_{args.exam}_drafts.json"
    if out.exists():
        bank = json.loads(out.read_text(encoding="utf-8"))
    else:
        bank = {
            "bank": f"dlpt-{args.exam}-drafts",
            "title": f"DLPT {args.exam}: unreviewed drafts",
            "license": "CC BY-SA 4.0",
            "attribution": "Tsumugi contributors (AI-drafted, pending review)",
            "passages": [],
            "items": [],
        }
    taken = {p["id"] for p in bank["passages"]} | {i["id"] for i in bank["items"]}
    avoid = [p.get("title", "") for p in bank["passages"]]
    types = TEXT_TYPES[args.exam][args.level]
    added = 0
    for k in range(args.count):
        text_type = args.text_type or types[k % len(types)]
        for attempt in range(1, args.retries + 2):
            try:
                raw = chat(
                    args.endpoint,
                    args.model,
                    draft_messages(exam, args.level, text_type, bands, avoid),
                    None if args.json_object else draft_schema(exam),
                    args.temperature,
                    args.timeout,
                )
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, IndexError) as e:
                print(f"[{k + 1}/{args.count}] attempt {attempt}: request failed: {e}", file=sys.stderr)
                continue
            if not isinstance(raw, dict):
                print(f"[{k + 1}/{args.count}] attempt {attempt}: reply is not an object", file=sys.stderr)
                continue
            text_type_out = re.sub(r"[^a-z]", "", str(raw.get("textType", "")).lower()) or text_type
            pid = next_id(exam, args.level, text_type_out, taken)
            passage, items = to_bank_entries({**raw, "textType": text_type_out}, exam, args.level, pid)
            report = Report()
            candidate = {**bank, "passages": [passage], "items": items}
            validate_bank(candidate, report, bands, set(), name="draft", types={})
            if report.errors:
                print(f"[{k + 1}/{args.count}] attempt {attempt}: rejected:", file=sys.stderr)
                for e in report.errors:
                    print(f"  {e}", file=sys.stderr)
                continue
            for w in report.warnings:
                print(f"  warning: {w}", file=sys.stderr)
            bank["passages"].append(passage)
            bank["items"].extend(items)
            taken |= {pid} | {i["id"] for i in items}
            avoid.append(passage["title"])
            added += 1
            print(f"[{k + 1}/{args.count}] {pid}: {passage['title']} ({len(items)} items)")
            break
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(bank, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    stamp = dt.datetime.now(dt.UTC).isoformat(timespec="seconds")
    print(f"{stamp}: added {added} of {args.count} drafts to {out} (source=llm, verified=false).")
    print(f"Review with: uv run python items/review.py {out}")
    return 0 if added == args.count else 1


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--bands", type=Path, default=BANDS_PATH)
    sub = parser.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("validate", help="validate item-bank JSON files")
    v.add_argument("files", type=Path, nargs="+")
    v.add_argument("--strict", action="store_true", help="fail on ILR band misses and other warnings")
    v.add_argument("--quiet", action="store_true", help="skip the per-bank summary")
    v.set_defaults(func=cmd_validate)

    d = sub.add_parser("draft", help="draft passages + items with an OpenAI-compatible endpoint")
    d.add_argument("--endpoint", required=True, help="base URL, e.g. http://localhost:11434/v1")
    d.add_argument("--model", required=True)
    d.add_argument("--level", required=True, choices=DLPT_LEVELS)
    d.add_argument("--count", type=int, default=1)
    d.add_argument("--exam", choices=("reading", "listening"), default="reading")
    d.add_argument("--text-type", help="force a text type (default: rotate through the level's types)")
    d.add_argument("--out", type=Path, help="bank file to append to (created if missing)")
    d.add_argument("--temperature", type=float, default=0.7)
    d.add_argument("--timeout", type=float, default=600)
    d.add_argument("--retries", type=int, default=2)
    d.add_argument(
        "--json-object", action="store_true", help="use response_format json_object, not json_schema"
    )
    d.set_defaults(func=cmd_draft)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
