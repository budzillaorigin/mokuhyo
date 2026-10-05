"""Per-language speaking quality of the tier models (BRIEF §6.1): a 60-prompt set per language (20 interviewer turns,
20 topic-conversation turns, 20 interview ratings) run through the app's own prompts and validators
(`Mokuhyo --eval-speaking`), scored on four metrics, written to docs/MODELS.md "## Speaking eval".

- json:      the model's first answer parsed into the task's JSON schema
- purity:    target-language fields are in the target language (no foreign-script leaks, not English)
- register:  the reference model (§7.1 primary) judges whether interviewer/partner lines follow the language's
             register notes (usted/vous/Sie/です・ます/…)
- agreement: rating prompts: the model's ILR estimate is within one step of the transcript's intended level

score = mean of the four. A tier whose score for a language is below 0.6 shows a warning in the model picker.

Fixtures (tools/models/eval/<lang>.json) are built once from the language's OPI pack (tools/opi/<lang>.json) with
learner answers written by the reference model at known ILR levels (source = "llm"); they are cached and committed.

    uv run --group content python models/eval_speaking.py --languages ja,es,ar --models tier-b
    uv run --group content python models/eval_speaking.py --languages all --models all
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import random
import re
import statistics
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import langtext
import llm

EVAL = TOOLS / "models" / "eval"
LEVELS = ["0+", "1", "1+", "2", "2+", "3"]
PHASES = ["warmup", "level_check", "probe", "roleplay", "winddown"]
NAMES = {"ja": "Japanese", "es": "Spanish", "fr": "French", "de": "German", "pt-BR": "Brazilian Portuguese", "ru": "Russian",
         "zh-Hans": "Mandarin Chinese", "ko": "Korean", "ar": "Modern Standard Arabic", "fa": "Persian", "id": "Indonesian"}

# Ollama tag → app manifest id (docs/MODELS.md keys the table by manifest id).
TIERS = {
    "phi4-mini:3.8b": ("phi-4-mini-instruct-q4km", "A"),
    "granite3.3:8b": ("granite-3.3-8b-instruct-q4km", "B alt"),
    "hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M": ("eurollm-9b-instruct-q4km", "B"),
    "mistral-nemo:12b": ("mistral-nemo-instruct-2407-q4km", "C"),
    "phi4:14b": ("phi-4-q4km", "C alt"),
    "mistral-small3.2:24b-instruct-2506-q8_0": ("mistral-small-3.2-24b-instruct-2506-q4km", "D (reference, q8)"),
}
GROUPS = {"tier-b": ["hf.co/bartowski/EuroLLM-9B-Instruct-GGUF:Q4_K_M"], "all": list(TIERS)}


# --- fixtures ----------------------------------------------------------------------------------------------


def answers(client: llm.Client, lang: str, register: str, level: str, questions: list[str]) -> list[str]:
    schema = {"type": "object", "properties": {"answers": {"type": "array", "items": {"type": "string"},
              "minItems": len(questions), "maxItems": len(questions)}}, "required": ["answers"]}
    qs = "\n".join(f"{i + 1}. {q}" for i, q in enumerate(questions))
    out = client.chat_json([
        {"role": "system", "content": "You simulate language learners for testing a speaking-practice app."},
        {"role": "user", "content": f"Answer each interview question in {NAMES[lang]} as a learner whose speaking is exactly at ILR {level}: "
                                    f"match that level's length, accuracy and complexity (0+: a few memorized words; 1: short simple sentences "
                                    f"with errors; 1+: connected sentences, some errors; 2: a paragraph, past/future, minor errors; 2+: "
                                    f"opinions with reasons, occasional breakdown; 3: extended, abstract, accurate). Spoken style, no "
                                    f"translations.\n{qs}"},
    ], schema, temperature=0.8, max_tokens=3000)
    return [langtext.nfc(re.sub(r"^\s*\d+[.)]\s*", "", a)) for a in out["answers"]][: len(questions)]


def error_lines(client: llm.Client, lang: str, topics: list[dict]) -> list[str]:
    schema = {"type": "object", "properties": {"lines": {"type": "array", "items": {"type": "string"}, "minItems": len(topics),
              "maxItems": len(topics)}}, "required": ["lines"]}
    listing = "\n".join(f"{i + 1}. {t['opener']}" for i, t in enumerate(topics))
    out = client.chat_json([
        {"role": "system", "content": "You simulate language learners for testing a speaking-practice app."},
        {"role": "user", "content": f"For each conversation opener below, write the learner's reply in {NAMES[lang]}: 1–2 sentences at about "
                                    f"ILR 1+ with one or two typical learner errors (agreement, tense, particles/prepositions, word "
                                    f"choice).\n{listing}"},
    ], schema, temperature=0.8, max_tokens=3000)
    return [langtext.nfc(re.sub(r"^\s*\d+[.)]\s*", "", x)) for x in out["lines"]][: len(topics)]


def build_fixtures(client: llm.Client, lang: str) -> dict:
    opi = json.loads((TOOLS / "opi" / f"{lang}.json").read_text(encoding="utf-8"))
    rng = random.Random(lang)
    register = opi["profile"]["registerNotes"]
    qa: dict[str, list[tuple[str, str]]] = {}
    for lv in LEVELS:
        qs = [q["prompt"] for q in opi["questions"] if q["level"] == lv] or [q["prompt"] for q in opi["questions"]]
        picked = rng.sample(qs, min(8, len(qs)))
        while len(picked) < 8:
            picked.append(rng.choice(qs))
        qa[lv] = list(zip(picked, answers(client, lang, register, lv, picked), strict=False))

    def history(level: str, n: int) -> list[dict]:
        pairs = rng.sample(qa[level], min(n, len(qa[level])))
        return [t for q, a in pairs for t in ({"speaker": "PARTNER", "text": q}, {"speaker": "LEARNER", "text": a})]

    interviewer = [{"id": f"{lang}-int-{i:02d}", "phase": PHASES[i % 5], "level": LEVELS[i % 6], "history": history(LEVELS[i % 6], i % 4)}
                   for i in range(20)]
    topics = rng.sample(opi["topics"], min(20, len(opi["topics"])))
    lines = error_lines(client, lang, topics)
    topic = [{"id": f"{lang}-top-{i:02d}", "topic": t["title"], "domain": t["domain"], "level": rng.choice(["1", "1+", "2"]),
              "history": [{"speaker": "PARTNER", "text": t["opener"]}, {"speaker": "LEARNER", "text": line}]}
             for i, (t, line) in enumerate(zip(topics, lines, strict=False))]
    rating = []
    for i in range(20):
        lv = LEVELS[i % 6]
        rating.append({"id": f"{lang}-rate-{i:02d}", "intendedLevel": lv, "history": history(lv, 5)})
    return {"language": lang, "registerNotes": register, "source": "llm", "generatedBy": client.model,
            "interviewer": interviewer, "topic": topic, "rating": rating}


def fixtures(client: llm.Client, lang: str, rebuild: bool) -> Path:
    path = EVAL / f"{lang}.json"
    if path.exists() and not rebuild:
        return path
    EVAL.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(build_fixtures(client, lang), ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"fixtures: {path.relative_to(REPO)}", flush=True)
    return path


# --- running and scoring ----------------------------------------------------------------------------------


def pull(client: llm.Client, tag: str) -> None:
    """Pulls a model onto the Ollama server (EuroLLM isn't in Ollama's library). Only approved tags."""
    llm.check_approved(tag)
    base = client.endpoint.removesuffix("/v1")
    print(f"pulling {tag} on {base} (may take a while)…", flush=True)
    r = subprocess.run(["curl", "-sS", "--max-time", "3600", "-X", "POST", f"{base}/api/pull", "-d",
                        json.dumps({"model": tag, "stream": False})], capture_output=True, text=True, check=False)
    print(r.stdout[-300:] or r.stderr[-300:])


def run_app(fixture_files: list[Path], endpoint: str, models: list[str], out: Path, limit: int | None) -> None:
    """Runs `Mokuhyo --eval-speaking`: the packaged launcher when MOKUHYO_APP points at one, else `gradlew run`."""
    args = ["--eval-speaking", "--fixtures", ",".join(str(f) for f in fixture_files), "--endpoint", endpoint, "--models", ",".join(models),
            "--out", str(out)] + (["--limit", str(limit)] if limit else [])
    launcher = os.environ.get("MOKUHYO_APP")
    if launcher:
        subprocess.run([launcher, *args], cwd=REPO, check=True)
        return
    gradle = str(REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    subprocess.run([gradle, ":desktopApp:run", "--args=" + " ".join(args), "--console=plain", "-q"], cwd=REPO, check=True)


def judge_register(client: llm.Client, lang: str, register: str, outputs: list[str]) -> list[bool]:
    if not outputs:
        return []
    schema = {"type": "object", "properties": {"ok": {"type": "array", "items": {"type": "boolean"}, "minItems": len(outputs),
              "maxItems": len(outputs)}}, "required": ["ok"]}
    listing = "\n".join(f"{i + 1}. {o}" for i, o in enumerate(outputs))
    out = client.chat_json([
        {"role": "system", "content": "You are a strict native-speaker examiner checking register (formality) in interview questions."},
        {"role": "user", "content": f"Register rules for {NAMES[lang]}: {register}\nFor each line, answer true if it follows the rules "
                                    f"and is natural {NAMES[lang]}, false otherwise.\n{listing}"},
    ], schema, temperature=0.0, max_tokens=1000)
    return [bool(x) for x in out["ok"]][: len(outputs)]


def level_index(level: str | None) -> int | None:
    return LEVELS.index(level) if level in LEVELS else None


def field(output: str, key: str) -> str | None:
    try:
        value = llm.parse_json_object(output).get(key)
    except (ValueError, AttributeError):
        return None
    return value if isinstance(value, str) else None


def score(rows: list[dict], client: llm.Client, registers: dict[str, str]) -> list[dict]:
    results = []
    by = {}
    for r in rows:
        by.setdefault((r["model"], r["language"]), []).append(r)
    for (model, lang), rs in sorted(by.items()):
        js = statistics.mean(1.0 if r["jsonValid"] else 0.0 for r in rs)
        speech = [r for r in rs if r["kind"] in ("interviewer", "topic")]
        purity = statistics.mean(1.0 if r["purity"] else 0.0 for r in speech) if speech else 0.0
        lines = [field(r["output"], "utterance" if r["kind"] == "interviewer" else "reply") or "" for r in speech if r["jsonValid"]]
        lines = [x for x in lines if x]
        try:
            verdicts = judge_register(client, lang, registers[lang], lines)
            register = sum(verdicts) / len(speech) if speech else 0.0
        except (ValueError, RuntimeError, KeyError):
            register = 0.0
        rated = [r for r in rs if r["kind"] == "rating"]
        agree = [1.0 if (level_index(r.get("level")) is not None and abs(level_index(r["level"]) - level_index(r["intendedLevel"])) <= 1) else 0.0
                 for r in rated]
        agreement = statistics.mean(agree) if agree else 0.0
        total = statistics.mean([js, purity, register, agreement])
        latency = statistics.median(r["ms"] for r in rs) / 1000
        mid, tier = TIERS.get(model, (model, "?"))
        results.append({"model": model, "id": mid, "tier": tier, "language": lang, "score": round(total, 2), "json": round(js, 2),
                        "purity": round(purity, 2), "register": round(register, 2), "agreement": round(agreement, 2),
                        "latency": round(latency, 1), "n": len(rs)})
        print(f"{lang:7} {tier:16} score {total:.2f} json {js:.2f} purity {purity:.2f} register {register:.2f} agreement {agreement:.2f} "
              f"median {latency:.1f}s", flush=True)
    return results


def write_models_md(results: list[dict], endpoint_note: str) -> None:
    doc = REPO / "docs" / "MODELS.md"
    text = doc.read_text(encoding="utf-8")
    head = text[: text.index("## Speaking eval")] if "## Speaking eval" in text else text
    old = {}
    if "## Speaking eval" in text:
        for line in text[text.index("## Speaking eval"):].splitlines():
            cells = [c.strip().strip("`") for c in line.strip().strip("|").split("|")]
            if len(cells) >= 9 and cells[2].replace(".", "", 1).isdigit():
                old[(cells[0], cells[1])] = line
    for r in results:
        old[(r["id"], r["language"])] = (f"| `{r['id']}` | {r['language']} | {r['score']:.2f} | {r['tier']} | {r['json']:.2f} | {r['purity']:.2f} | "
                                         f"{r['register']:.2f} | {r['agreement']:.2f} | {r['latency']:.1f} s |")
    rows = [old[k] for k in sorted(old)]
    section = [
        "## Speaking eval",
        "",
        (f"`tools/models/eval_speaking.py`, last run {dt.datetime.now(dt.UTC).strftime('%Y-%m-%d')} ({endpoint_note}). 60 prompts per "
         "language through the app's own prompts and validators; score = mean of json validity, target-language purity, "
         "register (judged by the reference model) and rating agreement (estimate within one ILR step of the intended level). "
         "Below 0.60 the model picker warns for that language. Latency is the 5090 server's, not a laptop's."),
        "",
        "| model | lang | score | tier | json | purity | register | agreement | median latency |",
        "|---|---|---|---|---|---|---|---|---|",
        *rows,
        "",
    ]
    keep = text[text.index(COHERENCE):] if COHERENCE in text else ""  # the coherence section survives a speaking-eval run
    doc.write_text(head + "\n".join(section) + ("\n" + keep if keep else ""), encoding="utf-8")
    print("wrote docs/MODELS.md")


COHERENCE = "## Interviewer coherence"
REFERENCE = "mistral-small3.2:24b-instruct-2506-q8_0"  # §7.1 primary: simulates the candidate and judges the turns


def run_coherence(fixture_files: list[Path], endpoint: str, models: list[str], out: Path, interviews: int) -> None:
    """`Mokuhyo --eval-coherence`: whole practice interviews through the app's own OpiSession (BRIEF_PHASE8 N-00b)."""
    args = ["--eval-coherence", "--fixtures", ",".join(str(f) for f in fixture_files), "--endpoint", endpoint, "--models", ",".join(models),
            "--out", str(out), "--interviews", str(interviews), "--candidate-model", REFERENCE]
    launcher = os.environ.get("MOKUHYO_APP")
    if launcher:
        subprocess.run([launcher, *args], cwd=REPO, check=True)
        return
    gradle = str(REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    subprocess.run([gradle, ":desktopApp:run", "--args=" + " ".join(args), "--console=plain", "-q"], cwd=REPO, check=True)


def judge_coherence(client: llm.Client, rows: list[dict]) -> list[bool]:
    """The reference model: is each interviewer question a sensible next turn (right language, coherent, not a repeat)?"""
    out: list[bool] = []
    for i in range(0, len(rows), 8):
        batch = rows[i:i + 8]
        listing = "\n\n".join(f"Case {k + 1} ({NAMES[r['lang']]}, phase {r['phase']}):\n{r['before'] or '(interview starting)'}\n"
                               f"NEXT QUESTION: {r['question']}" for k, r in enumerate(batch))
        schema = {"type": "object", "properties": {"ok": {"type": "array", "items": {"type": "boolean"}, "minItems": len(batch),
                  "maxItems": len(batch)}}, "required": ["ok"]}
        try:
            got = client.chat_json([
                {"role": "system", "content": "You judge practice language-proficiency interviews. Answer in JSON only."},
                {"role": "user", "content": "For each case, answer true if NEXT QUESTION is a sensible next interviewer turn: in the "
                 "interview's language, grammatical and natural, fitting the phase, not repeating an earlier question or the "
                 "candidate's words, and not contradicting what was said. Interviewers move to a new topic between questions on "
                 "purpose; a topic change is fine. Otherwise false.\n\n" + listing},
            ], schema, temperature=0.0, max_tokens=400)
            out += [bool(x) for x in got["ok"]][: len(batch)]
        except (RuntimeError, ValueError):
            out += [False] * len(batch)
    return out


def write_coherence(rows: list[dict], sensible: list[bool]) -> list[dict]:
    """Per model and language: share of sensible interviewer turns and of scripted fallbacks (targets ≥ 90 % / < 10 %)."""
    groups: dict[tuple[str, str], list[tuple[dict, bool]]] = {}
    for r, ok in zip(rows, sensible, strict=True):
        groups.setdefault((r["model"], r["lang"]), []).append((r, ok))
    results = []
    for (m, lang), items in sorted(groups.items()):
        n = len(items)
        model_turns = [ok for r, ok in items if not r["scripted"]]
        results.append({"model": m, "lang": lang, "turns": n, "sensible": sum(ok for _, ok in items) / n,
                        "modelSensible": (sum(model_turns) / len(model_turns)) if model_turns else 0.0,
                        "scripted": sum(1 for r, _ in items if r["scripted"]) / n})
    doc = REPO / "docs" / "MODELS.md"
    text = doc.read_text(encoding="utf-8")
    base = text[: text.index(COHERENCE)].rstrip("\n") + "\n" if COHERENCE in text else text.rstrip("\n") + "\n"
    lines = ["", COHERENCE, "",
             (f"`tools/models/eval_speaking.py --coherence`, last run {dt.datetime.now(dt.UTC).strftime('%Y-%m-%d')} (Ollama on the owner's "
              "RTX 5090). Whole practice interviews through the app's own interview session; the reference model plays a learner "
              "at ILR 1 to 2+ answering each actual question, then judges each interviewer turn (topic changes between questions "
              "are allowed). Targets (BRIEF_PHASE8 N-00b): ≥ 90 % sensible turns and "
              "< 10 % scripted fallbacks for Tier B and above."), "",
             "| model | lang | turns | sensible | model turns sensible | scripted fallbacks | meets |", "|---|---|---|---|---|---|---|"]
    for r in results:
        ok = r["sensible"] >= 0.9 and r["scripted"] < 0.1
        lines.append(f"| `{r['model']}` | {r['lang']} | {r['turns']} | {r['sensible']:.0%} | {r['modelSensible']:.0%} | {r['scripted']:.0%} | {'yes' if ok else 'no'} |")
    doc.write_text(base + "\n".join(lines) + "\n", encoding="utf-8")
    print("wrote docs/MODELS.md (interviewer coherence)")
    return results


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--languages", default="ja,es,ar")
    ap.add_argument("--models", default="tier-b", help="tier-b, all, or comma-separated Ollama tags")
    ap.add_argument("--rebuild-fixtures", action="store_true")
    ap.add_argument("--limit", type=int, help="prompts per kind (smoke runs)")
    ap.add_argument("--no-pull", action="store_true")
    ap.add_argument("--fixtures-only", action="store_true", help="build/cache the fixtures and stop")
    ap.add_argument("--coherence", action="store_true", help="whole interviews: sensible-turn and scripted-fallback rates (N-00b)")
    ap.add_argument("--interviews", type=int, default=2, help="with --coherence: interviews per language and model")
    llm.add_args(ap)
    args = ap.parse_args()
    client = llm.Client.from_args(args.endpoint, args.model, args.check_model)
    try:
        client.ping()
    except llm.EndpointDown as e:
        print(f"ERROR {e}\nre-run: {e.rerun}", file=sys.stderr)
        return 2
    langs = list(langtext.LANGS) if args.languages == "all" else args.languages.split(",")
    models = GROUPS.get(args.models, args.models.split(","))
    for m in models:
        llm.check_approved(m.split(":Q")[0] if m.startswith("hf.co/") else m)
    if args.fixtures_only:
        for lang in langs:
            fixtures(client, lang, args.rebuild_fixtures)
        return 0
    if not args.no_pull:
        for m in models:
            if m.startswith("hf.co/"):
                pull(client, m)
    files = [fixtures(client, lang, args.rebuild_fixtures) for lang in langs]
    if args.coherence:
        out = TOOLS / "logs" / "eval-coherence.jsonl"
        run_coherence(files, client.endpoint, models, out, args.interviews)
        rows = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines() if line.strip()]
        results = write_coherence(rows, judge_coherence(client, rows))
        for r in results:
            print(f"coherence {r['model']} {r['lang']}: sensible {r['sensible']:.0%}, scripted {r['scripted']:.0%} over {r['turns']} turns")
        return 0
    out = TOOLS / "logs" / "eval-speaking.jsonl"
    run_app(files, client.endpoint, models, out, args.limit)
    rows = [json.loads(line) for line in out.read_text(encoding="utf-8").splitlines() if line.strip()]
    registers = {lang: json.loads((EVAL / f"{lang}.json").read_text(encoding="utf-8"))["registerNotes"] for lang in langs}
    results = score(rows, client, registers)
    write_models_md(results, "Ollama on the owner's RTX 5090")  # no LAN address in a public doc
    return 0


if __name__ == "__main__":
    sys.exit(main())
