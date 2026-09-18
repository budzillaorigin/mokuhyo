"""Benchmark a model on Japanese learner-sentence correction (BRIEF §7.1, the `correct_sentence` prompt).

Runs every sentence in data/learner_ja.jsonl through an OpenAI-compatible endpoint (llama-server, Ollama's /v1,
LM Studio, vLLM) with the same system prompt and JSON schema the app uses, then reports:

- exact match against the gold correction,
- GLEU (Napoles et al. 2015/2016, the GEC variant that also penalises n-grams kept from the source when the
  reference changed them), computed over characters because Japanese has no spaces,
- error detection precision/recall/F0.5 from the model's `is_correct` verdict,
- the rate of valid JSON answers and mean latency.

Usage:
    uv run python models/eval_ja.py --base-url http://localhost:8080 --model qwen2.5-1.5b-instruct
    uv run python models/eval_ja.py --predictions preds.jsonl       # score saved {"id", "corrected", ...} lines
    uv run python models/eval_ja.py --self-test                     # scorer sanity checks, no model needed

Standard library only. The test sentences were written by an LLM and are labelled "origin": "llm" until reviewed.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

DATA = Path(__file__).parent / "data" / "learner_ja.jsonl"

# Keep in sync with shared/src/commonMain/kotlin/app/tsumugi/ai/prompts/{Common,Writing}.kt (CorrectSentence).
HOUSE_RULES = (
    "You are a careful Japanese teacher inside a study app. Be accurate; if you are not sure, say so rather than "
    "guess. Write Japanese fields in natural standard Japanese (kanji and kana, no romaji). Write explanation "
    "fields in plain English."
)
SYSTEM_LINES = [
    "Task: check one sentence written by a {level} learner of Japanese.",
    "If it is grammatical and natural enough, set is_correct to true and copy it unchanged into corrected.",
    "Otherwise fix only what is wrong, changing as little as possible: keep the learner's words, style and meaning.",
    "List each change in edits (original fragment, replacement, a short reason in English).",
    (
        "confidence is 0 to 1: how sure you are of your verdict. Use a low value for dialect, casual speech or "
        "anything you are unsure about."
    ),
]
SCHEMA = {
    "type": "object",
    "properties": {
        "is_correct": {"type": "boolean"},
        "corrected": {"type": "string", "maxLength": 300},
        "confidence": {"type": "number"},
        "edits": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "original": {"type": "string", "maxLength": 80},
                    "replacement": {"type": "string", "maxLength": 80},
                    "reason": {"type": "string", "maxLength": 300},
                },
                "required": ["original", "replacement", "reason"],
                "additionalProperties": False,
            },
            "maxItems": 6,
        },
        "explanation": {"type": "string", "maxLength": 600},
    },
    "required": ["is_correct", "corrected", "confidence", "edits", "explanation"],
    "additionalProperties": False,
}


# --- Scoring ---------------------------------------------------------------------------------------------


def ngrams(text: str, n: int) -> Counter[str]:
    return Counter(text[i : i + n] for i in range(len(text) - n + 1))


def gleu_stats(hyp: str, ref: str, src: str, max_n: int = 4) -> list[int]:
    """Sufficient statistics for one sentence, as in the reference gleu.py: [hyp_len, ref_len, (num, den) * n]."""
    stats = [len(hyp), len(ref)]
    for n in range(1, max_n + 1):
        h, r, s = ngrams(hyp, n), ngrams(ref, n), ngrams(src, n)
        source_not_in_ref = s - r
        matched = sum((h & r).values()) - sum((h & source_not_in_ref).values())
        stats += [max(matched, 0), max(len(hyp) + 1 - n, 0)]
    return stats


def gleu(all_stats: list[list[int]]) -> float:
    """Corpus GLEU from summed sentence statistics (0 if any n-gram order has no matches)."""
    totals = [sum(col) for col in zip(*all_stats)]
    hyp_len, ref_len = totals[0], totals[1]
    if hyp_len == 0:
        return 0.0
    pairs = list(zip(totals[2::2], totals[3::2]))
    if any(num == 0 or den == 0 for num, den in pairs):
        return 0.0
    log_prec = sum(math.log(num / den) for num, den in pairs) / len(pairs)
    return math.exp(min(0.0, 1 - ref_len / hyp_len) + log_prec)


def normalise(text: str) -> str:
    return "".join(text.split())


def score(rows: list[dict], predictions: dict[int, dict]) -> dict:
    stats, exact, valid, latencies = [], 0, 0, []
    tp = fp = fn = tn = 0
    for row in rows:
        pred = predictions.get(row["id"])
        src, ref = normalise(row["source"]), normalise(row["gold"])
        if pred is None or not isinstance(pred.get("corrected"), str):
            hyp, flagged = src, False  # no usable answer: count it as "left unchanged"
        else:
            valid += 1
            hyp = normalise(pred["corrected"])
            flagged = pred.get("is_correct") is False
            if "latency_s" in pred:
                latencies.append(pred["latency_s"])
        exact += hyp == ref
        stats.append(gleu_stats(hyp, ref, src))
        has_error = src != ref
        if flagged and has_error:
            tp += 1
        elif flagged:
            fp += 1
        elif has_error:
            fn += 1
        else:
            tn += 1
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    beta2 = 0.25
    f05 = (1 + beta2) * precision * recall / (beta2 * precision + recall) if precision + recall else 0.0
    return {
        "sentences": len(rows),
        "exact_match": exact / len(rows),
        "gleu": gleu(stats),
        "source_gleu": gleu(
            [gleu_stats(normalise(r["source"]), normalise(r["gold"]), normalise(r["source"])) for r in rows]
        ),
        "detection": {
            "precision": precision,
            "recall": recall,
            "f0.5": f05,
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "tn": tn,
        },
        "json_valid": valid / len(rows),
        "mean_latency_s": sum(latencies) / len(latencies) if latencies else None,
    }


# --- Model calls -----------------------------------------------------------------------------------------


def extract_json(text: str) -> dict | None:
    start = text.find("{")
    if start < 0:
        return None
    depth, in_string, escaped = 0, False, False
    for i in range(start, len(text)):
        c = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
            continue
        if c == '"':
            in_string = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                try:
                    value = json.loads(text[start : i + 1])
                except json.JSONDecodeError:
                    return None
                return value if isinstance(value, dict) else None
    return None


class Endpoint:
    def __init__(self, base_url: str, model: str, api_key: str | None, timeout: float) -> None:
        base = base_url.strip().rstrip("/")
        self.url = (base if base.endswith("/v1") else base + "/v1") + "/chat/completions"
        self.model, self.api_key, self.timeout = model, api_key, timeout
        self.modes = ["json_schema", "json_object", None]  # stepped down on HTTP 400-422, as in the app

    def post(self, body: dict) -> tuple[int, dict | str]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        req = urllib.request.Request(self.url, json.dumps(body).encode(), headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status, json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")

    def correct(self, sentence: str, level: str) -> dict | None:
        system = "\n".join([HOUSE_RULES, *(line.format(level=level) for line in SYSTEM_LINES)])
        system += "\n\nRespond with a single JSON object and nothing else. It must match this JSON Schema:\n"
        system += json.dumps(SCHEMA, ensure_ascii=False, separators=(",", ":"))
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": f"Sentence: {sentence}"},
        ]
        while True:
            body = {
                "model": self.model,
                "messages": messages,
                "max_tokens": 512,
                "temperature": 0.1,
                "stream": False,
            }
            mode = self.modes[0]
            if mode == "json_schema":
                body["response_format"] = {
                    "type": "json_schema",
                    "json_schema": {"name": "output", "strict": True, "schema": SCHEMA},
                }
            elif mode == "json_object":
                body["response_format"] = {"type": "json_object"}
            status, payload = self.post(body)
            if 400 <= status <= 422 and mode is not None:
                self.modes.pop(0)
                continue
            if status != 200 or not isinstance(payload, dict):
                raise RuntimeError(f"HTTP {status}: {str(payload)[:200]}")
            content = payload["choices"][0]["message"].get("content") or ""
            return extract_json(content)


def run_model(args: argparse.Namespace, rows: list[dict]) -> dict[int, dict]:
    endpoint = Endpoint(
        args.base_url, args.model, args.api_key or os.environ.get("TSUMUGI_EVAL_API_KEY"), args.timeout
    )
    predictions: dict[int, dict] = {}
    for n, row in enumerate(rows, 1):
        started = time.monotonic()
        try:
            answer = endpoint.correct(row["source"], args.level)
        except (OSError, RuntimeError, KeyError, IndexError) as e:
            print(f"  #{row['id']}: {e}", file=sys.stderr)
            answer = None
        elapsed = time.monotonic() - started
        if answer is not None:
            answer["latency_s"] = elapsed
            predictions[row["id"]] = answer
        shown = answer.get("corrected") if answer else "(no valid JSON)"
        print(f"[{n}/{len(rows)}] {row['source']} → {shown}  ({elapsed:.1f}s)", file=sys.stderr)
    return predictions


# --- CLI -------------------------------------------------------------------------------------------------


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def self_test() -> None:
    rows = load_jsonl(DATA)
    assert len(rows) == 100, len(rows)
    assert len({r["id"] for r in rows}) == 100
    perfect = score(
        rows, {r["id"]: {"corrected": r["gold"], "is_correct": r["source"] == r["gold"]} for r in rows}
    )
    # Not exactly 1.0: like the reference gleu.py, n-grams the source has more of than the reference are
    # penalised even when the hypothesis equals the reference (e.g. a deleted duplicate particle).
    assert perfect["exact_match"] == 1.0 and perfect["gleu"] > 0.99, perfect
    assert perfect["detection"]["f0.5"] == 1.0, perfect
    unchanged = score(rows, {r["id"]: {"corrected": r["source"], "is_correct": True} for r in rows})
    assert unchanged["gleu"] < perfect["gleu"], unchanged
    assert unchanged["gleu"] == unchanged["source_gleu"]
    # A hypothesis that keeps the source's error scores below one that fixes it.
    kept = gleu([gleu_stats("私は学校を行きます。", "私は学校に行きます。", "私は学校を行きます。")])
    fixed = gleu([gleu_stats("私は学校に行きます。", "私は学校に行きます。", "私は学校を行きます。")])
    assert kept < fixed == 1.0, (kept, fixed)
    assert extract_json('```json\n{"a": "}"}\n```') == {"a": "}"}
    assert extract_json("no json") is None
    print(f"self-test ok: source-copy GLEU {unchanged['gleu']:.3f}, perfect GLEU {perfect['gleu']:.3f}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--base-url", help="OpenAI-compatible server, e.g. http://localhost:8080")
    parser.add_argument("--model", default="local", help="model name to send")
    parser.add_argument("--api-key", help="bearer token (or set TSUMUGI_EVAL_API_KEY)")
    parser.add_argument("--level", default="N4", help="learner level named in the prompt")
    parser.add_argument("--data", type=Path, default=DATA)
    parser.add_argument("--limit", type=int, help="only the first N sentences")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--predictions", type=Path, help="score saved predictions instead of calling a model")
    parser.add_argument("--save", type=Path, help="write model predictions here (JSONL)")
    parser.add_argument("--out", type=Path, help="write the score report here (JSON)")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return

    rows = load_jsonl(args.data)[: args.limit]
    if args.predictions:
        predictions = {p["id"]: p for p in load_jsonl(args.predictions)}
    elif args.base_url:
        predictions = run_model(args, rows)
        if args.save:
            with args.save.open("w", encoding="utf-8") as f:
                for pid, p in sorted(predictions.items()):
                    f.write(json.dumps({"id": pid, **p}, ensure_ascii=False) + "\n")
    else:
        parser.error("give --base-url, --predictions or --self-test")

    report = score(rows, predictions)
    report["model"] = args.model if args.base_url else str(args.predictions)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
