"""Tests for gen_dlpt.py: validation, band checks, drafting/check/merge with a fake model (no network).

Run: uv run --group content python items/test_gen_dlpt.py
"""
from __future__ import annotations

import argparse
import copy
import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import gen_dlpt as g

BANDS = g.load_bands()

ES_BODY = (
    "El ayuntamiento de la ciudad anunció ayer un plan para mejorar el transporte público durante los próximos dos "
    "años. Según el alcalde, se comprarán cuarenta autobuses eléctricos y se abrirán tres nuevas líneas que unirán "
    "los barrios del norte con el centro. El plan costará unos ochenta millones de euros, que vendrán en parte de "
    "fondos europeos. Los vecinos del norte llevan años quejándose de que los autobuses pasan con poca frecuencia y "
    "llegan llenos. Una asociación de vecinos dijo que el anuncio es una buena noticia, pero pidió que las obras no "
    "corten las calles principales durante el verano. El ayuntamiento respondió que las obras empezarán en otoño y "
    "que informará a los comercios afectados con un mes de antelación. Además, el precio del billete no subirá este "
    "año, aunque la empresa municipal tendrá que contratar a sesenta conductores nuevos."
)


def es_bank() -> dict:
    pid = "es-dr-2-news-001"
    return {
        "bank": "es-reading-core", "language": "es", "title": "t", "license": "CC BY-SA 4.0", "attribution": "a",
        "passages": [{"id": pid, "exam": "DLPT_READING", "language": "es", "level": "2", "textType": "news",
                      "title": "City transport plan", "body": ES_BODY, "script": [], "source": "llm", "verified": False}],
        "items": [
            {"id": f"{pid}-q{i}", "exam": "DLPT_READING", "level": "2", "type": t, "passageId": pid,
             "stem": s, "choices": c, "answer": a, "explanation": "x", "source": "llm", "verified": False}
            for i, (t, s, c, a) in enumerate([
                ("main_idea", "What is the article mainly about?",
                 ["A plan to improve public transport", "A strike", "A new airport", "Higher ticket prices"], 0),
                ("detail", "How many electric buses will be bought?", ["Forty", "Three", "Eighty", "Sixty"], 0),
                ("inference", "When will construction start?", ["In autumn", "In summer", "Next year", "Already"], 0),
            ], 1)
        ],
    }


def run(bank: dict, strict: bool = True, lang: str = "es", skill: str = "reading") -> g.Report:
    r = g.Report()
    g.validate_bank(bank, lang, skill, r, BANDS, strict, "t")
    return r


def test_valid_bank_passes():
    r = run(es_bank())
    assert not r.errors, r.errors


def test_schema_errors():
    b = es_bank()
    b["items"][0]["choices"] = ["A", "A", "B", "C"]
    b["items"][1]["answer"] = 7
    b["items"][2]["passageId"] = "nope"
    errs = "\n".join(run(b).errors)
    assert "4 distinct choices" in errs and "answer must be 0–3" in errs and "unknown passage" in errs, errs
    b2 = es_bank()
    b2["passages"][0]["id"] = "bad id"
    assert any("id must look like" in e for e in run(b2).errors)


def test_questions_must_be_english():
    b = es_bank()
    b["items"][0]["stem"] = "¿De qué trata principalmente el artículo según el ayuntamiento?"
    b["items"][0]["choices"] = ["Un plan de transporte", "Una huelga", "Un aeropuerto", "Precios más altos"]
    assert any("in English" in e for e in run(b).errors)


def test_english_questions_with_quoted_target_words_pass():
    # Regression (Phase 6 fill): every Russian ILR 2+ reading draft was rejected — "we can infer that" had no cue
    # word, and a vocabulary stem quoting «принимает решения» fell under the ASCII ratio.
    assert g.is_english("From this interview, we can infer that...", ["rescue workers are always in good shape", "they are poorly paid"])
    assert g.is_english("In context, the phrase «принимает решения» means...", ["saves lives", "gives orders", "finds people", "makes decisions"])
    assert g.is_english("What does 「申し込み」 mean in this notice?", ["apply", "cancel", "pay", "wait"])
    assert not g.is_english("Из этого интервью можно сделать вывод, что...", ["a", "b", "c", "d"])
    assert not g.is_english("What is the main idea?", ["спасатели", "альпинисты", "горы", "погода"])


def test_band_misses_are_warnings_unless_strict():
    b = es_bank()
    b["passages"][0]["level"] = "0+"
    for it in b["items"]:
        it["level"] = "0+"
    lax = run(b, strict=False)
    assert not any("length" in e for e in lax.errors) and any("length" in w for w in lax.warnings)
    assert any("length" in e for e in run(b, strict=True).errors)


def test_wrong_language_is_caught():
    b = es_bank()
    b["passages"][0]["body"] = "これは日本語の文章です。" * 40
    assert any("script" in e for e in run(b).errors)


def test_listening_needs_script_with_voices():
    b = es_bank()
    p = b["passages"][0]
    p["exam"] = "DLPT_LISTENING"
    p["id"] = "es-dl-2-news-001"
    for it in b["items"]:
        it["exam"] = "DLPT_LISTENING"
        it["passageId"] = p["id"]
    p["script"] = [{"speaker": "Locutora", "voice": "robot", "text": ES_BODY}]
    errs = run(b, skill="listening").errors
    assert any("voice must be" in e for e in errs), errs
    p["script"][0]["voice"] = "female"
    assert not run(b, skill="listening").errors


def test_to_entries_balances_answer_positions():
    raw = {"title": "T", "body": ES_BODY, "items": [
        {"type": "detail", "stem": f"Q{i}?", "choices": ["right", "w1", "w2", "w3"], "answer": 0, "explanation": "e"}
        for i in range(3)]}
    positions = set()
    for n in range(12):
        _, items = g.to_entries(raw, "es", "reading", "2", "news", f"es-dr-2-news-{n:03d}", "m")
        for it in items:
            assert it["choices"][it["answer"]] == "right"
            positions.add(it["answer"])
    assert len(positions) >= 3, positions


class FakeClient:
    """Drafts the Spanish passage above; the checker agrees with the key unless told otherwise."""

    model = "mistral-small3.2:24b-instruct-2506-q8_0"
    fallback = "gpt-oss:20b"

    def __init__(self, disagree: bool = False):
        self.disagree = disagree
        self.drafts = 0

    def ping(self):
        pass

    def chat_json(self, messages, schema=None, **kw):
        if "answers" in json.dumps(schema or {}):
            text = messages[-1]["content"]
            keys = [json.loads(line.split("KEY:")[1]) for line in text.splitlines() if "KEY:" in line]
            return {"answers": [{"answer": (k + 1) % 4 if self.disagree else k, "ambiguous": False} for k in keys]}
        self.drafts += 1
        return {"title": f"Plan {self.drafts}", "body": ES_BODY, "items": [
            {"type": "main_idea", "stem": "What is the article mainly about?", "choices": ["Transport plan", "A strike", "Airport", "Prices"], "answer": 0, "explanation": "e"},
            {"type": "detail", "stem": "How many buses?", "choices": ["Forty", "Three", "Eighty", "Sixty"], "answer": 0, "explanation": "e"},
            {"type": "inference", "stem": "When do works start?", "choices": ["Autumn", "Summer", "Never", "Now"], "answer": 0, "explanation": "e"},
        ]}


def with_temp_dirs(fn):
    def wrapper():
        with tempfile.TemporaryDirectory() as tmp:
            old = g.BANK_DIR, g.DRAFTS
            g.BANK_DIR, g.DRAFTS = Path(tmp) / "bank", Path(tmp) / "drafts"
            try:
                fn()
            finally:
                g.BANK_DIR, g.DRAFTS = old
    wrapper.__name__ = fn.__name__
    return wrapper


def _keyed_check(client):
    """check_row puts no key in the prompt; for the fake we smuggle it in via the stems."""
    orig = g.check_row

    def check(c, row):
        row2 = copy.deepcopy(row)
        for it in row2["items"]:
            it["stem"] += f"\nKEY:{it['answer']}"
        return orig(c, row2)
    return check


@with_temp_dirs
def test_draft_check_merge_flow():
    client = FakeClient()
    ns = argparse.Namespace(language="es", skill="reading", ilr="2", n=2)
    assert g.cmd_draft(ns, client) == 0
    staged = g.read_staging("es", "reading")
    assert len(staged) == 2 and all(r["passage"]["source"] == "llm" for r in staged)
    orig = g.check_row
    g.check_row = _keyed_check(client)
    try:
        assert g.cmd_check(argparse.Namespace(language="es", skill="reading"), client) == 0
    finally:
        g.check_row = orig
    assert all(r["check"]["ok"] for r in g.read_staging("es", "reading"))
    g.cmd_merge(argparse.Namespace(language="es", skill="reading"))
    bank = g.load_bank("es", "reading")
    assert len(bank["passages"]) == 2 and len(bank["items"]) == 6
    assert g.read_staging("es", "reading") == []
    assert not run(bank).errors


@with_temp_dirs
def test_checker_disagreement_drops_the_passage():
    client = FakeClient(disagree=True)
    g.cmd_draft(argparse.Namespace(language="es", skill="reading", ilr="2", n=1), client)
    orig = g.check_row
    g.check_row = _keyed_check(client)
    try:
        g.cmd_check(argparse.Namespace(language="es", skill="reading"), client)
    finally:
        g.check_row = orig
    g.cmd_merge(argparse.Namespace(language="es", skill="reading"))
    assert g.load_bank("es", "reading")["passages"] == []


def test_shipped_banks_validate_strictly():
    for lang_dir in sorted(p for p in g.BANK_DIR.iterdir() if p.is_dir()):
        for skill in g.SKILLS:
            path = lang_dir / f"{skill}.json"
            if path.exists():
                r = g.Report()
                g.validate_bank(json.loads(path.read_text(encoding="utf-8")), lang_dir.name, skill, r, BANDS, True, path.name)
                assert not r.errors, (path, r.errors[:5])


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"ok    {t.__name__}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL  {t.__name__}: {e}")
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


def test_format_text_and_validation() -> None:
    """N-08: body must equal the plain text of formatData; unknown formats are refused."""
    d = {"app": "sms", "title": "t", "messages": [{"from": "Ana", "text": "¿Dron?", "time": "", "me": False}, {"from": "Yo", "text": "Sí.", "time": "", "me": True}]}
    assert g.format_text("chat", d) == "Ana: ¿Dron?\nYo: Sí."
    bands = g.load_bands()
    p = {"id": "es-dr-1-chat-001", "exam": "DLPT_READING", "language": "es", "level": "1", "textType": "chat", "title": "t",
         "body": "otra cosa", "source": "llm", "verified": False, "format": "chat", "formatData": d}
    r = g.Report()
    g.validate_bank({"bank": "x", "language": "es", "title": "x", "license": "x", "attribution": "x", "passages": [p], "items": []}, "es", "reading", r, bands, False, "t")
    assert any("plain text of formatData" in e for e in r.errors), r.errors


if __name__ == "__main__":
    sys.exit(main())
