"""Draft more graded-reader stories through an OpenAI-compatible endpoint (BRIEF_V2 §6.4, CLAUDE.md rule 19).

    uv run python packs/readers/draft_readers.py --endpoint http://localhost:11434/v1 --model mistral-small3.2:24b-instruct-2506-q8_0 \\
        --level N4 --count 5
    uv run python packs/readers/draft_readers.py --endpoint http://<lan-ip>:1234/v1 --model my-model \\
        --level N2 --count 3 --genre news --topic "disaster relief"
    uv run python packs/readers/draft_readers.py --level N5 --count 1 --print-prompt   # show the prompt, call nothing

Each story is written against the level's vocabulary (a sample of dictionary.sqlite words whose JLPT tag is this
level, plus the easier levels' words as the allowed base) and the grammar pack's points for the level. The reply is
resolved (vocabulary JMdict ids and glosses), validated with validate_readers.py, and sent back to the model with
the validator's errors up to --attempts times. Stories that pass are appended to a NEW batch file
stories/<level>-draft-<date>[-n].json with the next free ids (re-runs never reuse an id), as source "llm",
verified false: they show the AI-generated badge until reviewed with tools/items/review.py or the in-app review.
Stories that still fail are written to tools/.cache/readers-rejected/ for inspection, never to the pack.

Every request has a timeout (--timeout, default 600 s: local models are slow). An API key, if the server needs one,
comes from the environment variable named by --api-key-env and is sent only to --endpoint.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import re
import sqlite3
import sys
import urllib.error
import urllib.request
from pathlib import Path

import readers_lib as L
from common import CACHE
from validate_readers import Report, check_story, fix

REJECTED = CACHE / "readers-rejected"

STORY_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "titleEn": {"type": "string"},
        "topic": {"type": "string"},
        "body": {"type": "string"},
        "cast": {"type": "array", "items": {"type": "object", "properties": {
            "name": {"type": "string"}, "voice": {"type": "string", "enum": ["female", "male", "male-senior"]}}}},
        "names": {"type": "array", "items": {"type": "string"}},
        "vocabulary": {"type": "array", "items": {"type": "object", "properties": {
            "word": {"type": "string"}, "reading": {"type": "string"}, "gloss": {"type": "string"}}}},
        "questions": {"type": "array", "items": {"type": "object", "properties": {
            "type": {"type": "string"}, "stem": {"type": "string"},
            "choices": {"type": "array", "items": {"type": "string"}},
            "answer": {"type": "integer"}, "explanation": {"type": "string"}}}},
    },
    "required": ["title", "titleEn", "topic", "body", "vocabulary", "questions"],
}


def level_words(packs: Path, level: str, levels: dict, n: int, rng: random.Random) -> tuple[list[str], list[str]]:
    """(target words at this level, sample of easier words) as 'word(reading)' strings from the JLPT tags."""
    need = levels["levels"][level]["jlpt"]
    db = sqlite3.connect(f"file:{packs / 'dictionary.sqlite'}?mode=ro", uri=True)
    rows = db.execute(
        "SELECT e.jlpt, coalesce((SELECT text FROM entry_kanji WHERE entry_id = e.id ORDER BY ord LIMIT 1), ''), "
        "(SELECT text FROM entry_kana WHERE entry_id = e.id ORDER BY ord LIMIT 1) FROM entry e "
        "WHERE e.jlpt IS NOT NULL AND e.jlpt >= ? ORDER BY e.rank", (need,)).fetchall()
    db.close()
    fmt = [(j, f"{k}({r})" if k else r) for j, k, r in rows]
    target = [w for j, w in fmt if j == need]
    easier = [w for j, w in fmt if j > need]
    return rng.sample(target, min(n, len(target))), rng.sample(easier, min(n, len(easier)))


def level_grammar(packs: Path, level: str, levels: dict, n: int, rng: random.Random) -> list[str]:
    jlpt = levels["levels"][level]["jlpt"]
    path = packs / "grammar.sqlite"
    if not path.exists():
        return []
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    rows = [f"{t} ({m})" for t, m in db.execute("SELECT title, meaning FROM grammar_point WHERE jlpt = ?", (jlpt,))]
    db.close()
    return rng.sample(rows, min(n, len(rows)))


def messages(level: str, cfg: dict, genre: str, topic: str | None, words: tuple[list[str], list[str]],
             grammar: list[str], avoid: list[str]) -> list[dict]:
    lo, hi = cfg["chars"]
    qlo, qhi = cfg["questions"]
    qlang = "Japanese (stem and choices)" if cfg["questionLanguage"] == "ja" else "English (stem and choices)"
    label = "level 0 (absolute beginner, N5 words only, very short sentences)" if level == "N6" else f"JLPT {level}"
    system = "\n".join([
        f"You write original graded-reader texts in Japanese for learners at {label}.",
        f"Genre: {genre}. Write a text that reads like a real {genre} of that kind, natural and idiomatic.",
        (f"Length: {lo}-{hi} Japanese characters. Paragraphs separated by a single newline. Mean sentence length "
         f"about {cfg['sentence'][0]}-{cfg['sentence'][1]} characters."),
        "Use standard orthography (kanji where a native writer would use them; no spaces between words).",
        "At least 95% of the words must be at this level or easier. Put every harder word you need in vocabulary.",
        ("Speech: write 名前「…」 on its own line, or 「…」と言った after naming the speaker; list every speaker in "
         "cast with voice female, male or male-senior. List other proper nouns (people, places, shops) in names."),
        ("vocabulary: 3-15 useful or harder words from the text in dictionary form, with hiragana reading and a "
         f"short English gloss. questions: {qlo}-{qhi} multiple-choice comprehension questions in {qlang}, 4 choices, "
         "answer = 0-based index of the one correct choice (vary its position), explanation in English saying where "
         "the text gives the answer, type one of gist, detail, inference, vocabulary, purpose, sequence, reference."),
        "No real brands, no real living people, nothing copied from existing books or sites.",
        ("Reply with one JSON object: title (Japanese), titleEn, topic (short English), body, cast, names, "
         "vocabulary, questions."),
    ])
    target, easier = words
    user = "\n".join(filter(None, [
        f"Topic: {topic}." if topic else "Choose an everyday or cultural topic that suits the genre.",
        "Words at this level you may use (sample): " + "、".join(target[:80]) if target else "",
        "Easier words (sample of the allowed base): " + "、".join(easier[:60]) if easier else "",
        "Grammar at this level (sample): " + "; ".join(grammar) if grammar else "",
        "Don't repeat these existing titles: " + "、".join(avoid[-40:]) if avoid else "",
    ]))
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def chat(endpoint: str, model: str, msgs: list[dict], temperature: float, timeout: float, api_key: str | None,
         json_object: bool) -> dict:
    url = endpoint.rstrip("/")
    if not url.endswith("/chat/completions"):
        url += "/chat/completions"
    body = {"model": model, "messages": msgs, "temperature": temperature, "stream": False}
    body["response_format"] = {"type": "json_object"} if json_object else {
        "type": "json_schema", "json_schema": {"name": "graded_reader", "schema": STORY_SCHEMA, "strict": False}}
    headers = {"Content-Type": "application/json", "User-Agent": "tsumugi-tools"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.load(resp)
    content = data["choices"][0]["message"]["content"]
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip())
    return json.loads(content)


def to_story(raw: dict, sid: str, level: str, genre: str) -> dict:
    """A model reply as a story source, with the provenance flags forced (rule 10)."""
    s = lambda v: L.nfc(str(v)).strip()
    return {
        "id": sid, "level": level, "genre": genre, "topic": s(raw.get("topic", "")) or genre,
        "title": s(raw.get("title", "")), "titleEn": s(raw.get("titleEn", "")),
        "body": re.sub(r"\n{2,}", "\n", s(raw.get("body", ""))),
        **({"cast": [{"name": s(c.get("name", "")), "voice": s(c.get("voice", "female"))}
                     for c in raw.get("cast") or [] if isinstance(c, dict)]} if raw.get("cast") else {}),
        **({"names": [s(n) for n in raw.get("names") or [] if str(n).strip()]} if raw.get("names") else {}),
        "vocabulary": [{"word": s(v.get("word", "")), "reading": s(v.get("reading", "")),
                        **({"gloss": s(v["gloss"])} if v.get("gloss") else {})}
                       for v in raw.get("vocabulary") or [] if isinstance(v, dict)],
        "questions": [{"type": s(q.get("type", "detail")), "stem": s(q.get("stem", "")),
                       "choices": [s(c) for c in q.get("choices") or []], "answer": q.get("answer"),
                       "explanation": s(q.get("explanation", ""))}
                      for q in raw.get("questions") or [] if isinstance(q, dict)],
        "source": "llm",
        "verified": False,
    }


def next_ids(level: str, n: int) -> list[str]:
    used = {int(m.group(2)) for _, p in L.load_stories() if (m := L.STORY_ID.match(p.get("id", "")))
            and m.group(1) == level.lower()}
    out, k = [], max(used, default=0)
    while len(out) < n:
        k += 1
        out.append(f"gr-{level.lower()}-{k:03d}")
    return out


def batch_path(level: str) -> Path:
    base = f"{level.lower()}-draft-{dt.datetime.now(dt.UTC):%Y%m%d}"
    path = L.STORIES / f"{base}.json"
    i = 2
    while path.exists():
        path = L.STORIES / f"{base}-{i}.json"
        i += 1
    return path


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--endpoint", help="OpenAI-compatible base URL, e.g. http://localhost:11434/v1 (Ollama)")
    ap.add_argument("--model", help="model name on that server")
    ap.add_argument("--level", required=True, choices=L.LEVEL_ORDER)
    ap.add_argument("--count", type=int, default=5)
    ap.add_argument("--genre", help="one genre for every story (default: rotate through the genres)")
    ap.add_argument("--topic", help="topic hint for every story")
    ap.add_argument("--attempts", type=int, default=3, help="model calls per story, feeding back validator errors")
    ap.add_argument("--temperature", type=float, default=0.8)
    ap.add_argument("--timeout", type=float, default=600, help="seconds per request")
    ap.add_argument("--api-key-env", help="environment variable holding the endpoint's API key, if it needs one")
    ap.add_argument("--json-object", action="store_true", help="response_format json_object instead of json_schema")
    ap.add_argument("--packs", type=Path, help="directory with dictionary/tokenizer/grammar packs")
    ap.add_argument("--seed", type=int, help="seed for the word and grammar samples")
    ap.add_argument("--print-prompt", action="store_true", help="print the first prompt and exit")
    args = ap.parse_args()
    if not args.print_prompt and (not args.endpoint or not args.model):
        ap.error("--endpoint and --model are required (or use --print-prompt)")

    levels = L.load_levels()
    cfg = levels["levels"][args.level]
    packs = args.packs or L.default_packs()
    rng = random.Random(args.seed)
    existing = [p for _, p in L.load_stories()]
    avoid = [p.get("title", "") for p in existing if p.get("level") == args.level]
    genres = [args.genre] if args.genre else levels["genres"]
    if args.genre and args.genre not in levels["genres"]:
        ap.error(f"--genre must be one of {levels['genres']}")
    if args.print_prompt:
        msgs = messages(args.level, cfg, genres[0], args.topic, level_words(packs, args.level, levels, 120, rng),
                        level_grammar(packs, args.level, levels, 25, rng), avoid)
        for m in msgs:
            print(f"--- {m['role']}\n{m['content']}")
        return 0

    api_key = os.environ.get(args.api_key_env) if args.api_key_env else None
    an = L.Analyzer(packs)
    ids = next_ids(args.level, args.count)
    out_path = batch_path(args.level)
    accepted: list[dict] = []
    tmp = CACHE / "readers-draft.json"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    for i, sid in enumerate(ids):
        genre = genres[(len(existing) + i) % len(genres)]
        msgs = messages(args.level, cfg, genre, args.topic, level_words(packs, args.level, levels, 120, rng),
                        level_grammar(packs, args.level, levels, 25, rng), avoid)
        story, errors = None, []
        for attempt in range(1, args.attempts + 1):
            try:
                raw = chat(args.endpoint, args.model, msgs, args.temperature, args.timeout, api_key, args.json_object)
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, IndexError) as e:
                L.log(f"{sid} attempt {attempt}: request failed ({e})")
                continue
            story = to_story(raw, sid, args.level, genre)
            tmp.write_text(json.dumps({"passages": [story]}, ensure_ascii=False), encoding="utf-8")
            fix([tmp], an)
            story = json.loads(tmp.read_text(encoding="utf-8"))["passages"][0]
            rep = Report()
            check_story(story, levels, an, rep)
            errors = rep.errors
            if not errors:
                break
            L.log(f"{sid} attempt {attempt}: {len(errors)} validator errors")
            msgs = msgs + [{"role": "assistant", "content": json.dumps(raw, ensure_ascii=False)},
                           {"role": "user", "content": "Fix these problems and reply with the whole corrected JSON:\n"
                            + "\n".join(errors)}]
        if story is not None and not errors:
            accepted.append(story)
            avoid.append(story["title"])
            out_path.write_text(json.dumps({
                "batch": out_path.stem, "level": args.level, "license": L.LICENSE,
                "attribution": "Tsumugi contributors (AI-drafted)", "model": args.model,
                "drafted": dt.datetime.now(dt.UTC).date().isoformat(), "passages": accepted,
            }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            L.log(f"{sid}: accepted ({genre}) → {out_path.name}")
        elif story is not None:
            REJECTED.mkdir(parents=True, exist_ok=True)
            (REJECTED / f"{sid}.json").write_text(
                json.dumps({"story": story, "errors": errors}, ensure_ascii=False, indent=2), encoding="utf-8")
            L.log(f"{sid}: rejected after {args.attempts} attempts; see {REJECTED / (sid + '.json')}")
    tmp.unlink(missing_ok=True)
    L.log(f"{len(accepted)}/{len(ids)} stories accepted. Review them (uv run python items/review.py "
          f"{out_path.relative_to(L.TOOLS) if accepted else '...'}) and rebuild: uv run python "
          "packs/readers/build_readers.py")
    return 0 if accepted else 1


if __name__ == "__main__":
    sys.exit(main())
