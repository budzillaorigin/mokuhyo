#!/usr/bin/env python3
"""Allied term alignment and definition drafting (BRIEF_PHASE8 C-02; docs/TERM_PIPELINE.md steps 2–4; D-028).

Stages (each resumable; work files in tools/terms/.work/, git-ignored). The primary model's stages run for every
language before the checker's, so the GPU loads each model once:

  propose   primary model: up to three target-language candidates per seed term, each with its kind
            (native | calque | loanword | acronym), and for brevity rows whether the partner force says it in English.
  confirm   no model: each candidate is searched in the language's machine-usable alignment sources
            (machine_extract_ok = true only; AAP-06's English / French entry pairs are read directly). The first
            candidate found is the term, cited by source and printed page (D-028 translation rule). Languages whose
            sources are human-review-only (de, id, zh-Hans) or have none (es, ru, ar, fa) keep the model's first
            candidate with badge = unconfirmed-term; de/id/zh rows are also listed in lookup_queue.csv for a human.
  draft     primary model: the learner definition in the target language from the US definition (prompts/
            draft_definition.md; no allied text in the prompt).
  check     checker model: prompts/check_accuracy.md — against the US meaning, plus a short excerpt of the cited
            page when the source is machine_extract_ok.
  overlap   overlap_check.py on every drafted definition; failures and "fix" verdicts are redrafted (2 rounds).
  write     term_alignment.csv (status checked when 4a and 4b pass, else draft; badge per the pipeline).

  uv run python terms/align_terms.py all [--language ja,fr] [--limit N]
  uv run python terms/align_terms.py <stage> ...
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import csv
import json
import re
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(TOOLS))

import doctrine as D

import llm
import sources as S

WORK = HERE / ".work"
SEEDS = HERE / "seed_terms.csv"
OUT = HERE / "term_alignment.csv"
QUEUE = HERE / "lookup_queue.csv"
LANGS = ["ja", "ko", "de", "fr", "es", "pt-BR", "ru", "ar", "fa", "id", "zh-Hans"]
NAMES = {"ja": "Japanese", "ko": "Korean", "de": "German", "fr": "French", "es": "Spanish", "pt-BR": "Brazilian Portuguese",
         "ru": "Russian", "ar": "Modern Standard Arabic", "fa": "Persian (Farsi)", "id": "Indonesian",
         "zh-Hans": "Chinese (Simplified characters, mainland usage)"}
PARTNER = {  # D-028: each partner country's air force
    "ja": "the Japan Air Self-Defense Force (JASDF) and Japan's Ministry of Defense", "ko": "the Republic of Korea Air Force (ROKAF)",
    "de": "the Bundeswehr and its Luftwaffe", "fr": "the French Armée de l'air et de l'espace and NATO's French terminology",
    "es": "the Mexican Air Force (Fuerza Aérea Mexicana); Spanish Armed Forces usage as the alternate",
    "pt-BR": "the Brazilian Armed Forces and the Força Aérea Brasileira", "ru": "the Russian Aerospace Forces (VKS)",
    "ar": "Gulf air forces (Royal Saudi Air Force, Qatar Emiri Air Force) in Modern Standard Arabic",
    "fa": "Iranian armed forces (IRIAF) in standard Persian", "id": "the Indonesian Air Force (TNI-AU)",
    "zh-Hans": "the PLA Air Force, in mainland Simplified Chinese usage",
}
# Machine-usable alignment sources per language, in citation preference order (machine_extract_ok = true).
SEARCH = {"ja": ["ja-doj-2026-ja-index", "ja-doj-2026-ja", "ja-doj-2025-ja"], "ko": ["ko-dwp-2022-ko"],
          "fr": ["nato-aap-06-2019", "fr-rns-2025-fr"], "pt-BR": ["ptbr-md35-g-01"]}
# Alignment sources a human must read (machine_extract_ok false / unclear).
HUMAN = {"de": ["de-weissbuch-2016", "de-vpr-2023-de"], "id": ["id-bukuputih-2015-id"], "zh-Hans": ["zh-tw-mnd-dict", "zh-tw-ndr-2025-zh"]}
KINDS = ["native", "calque", "loanword", "acronym"]
CJK = {"ja", "ko", "zh-Hans"}
PROMPTS = HERE / "prompts"


def seeds() -> list[dict]:
    with open(SEEDS, encoding="utf-8") as f:
        return list(csv.DictReader(f))


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


def batches(items: list, n: int) -> list[list]:
    return [items[i:i + n] for i in range(0, len(items), n)]


def client() -> llm.Client:
    c = llm.Client.from_args()
    c.ping()
    return c


def run_parallel(fn, jobs: list, workers: int = 3) -> None:
    with cf.ThreadPoolExecutor(workers) as ex:
        for f in cf.as_completed([ex.submit(fn, j) for j in jobs]):
            f.result()


# ------------------------------------------------------------------------------------------------ propose
def parse_lines(text: str, ids: set[str]) -> dict[str, list[str]]:
    """Compact line output: `<seed_id> | field | field …` (one line per item; extra text ignored)."""
    out: dict[str, list[str]] = {}
    for ln in text.splitlines():
        ln = re.sub(r"^\s*(?:[-*•]|\d+[.)])\s+", "", ln.strip().strip("`")).replace("**", "")
        parts = [x.strip() for x in ln.strip().strip("|").split("|")]
        sid = parts[0].strip("-*[]: ").strip() if parts else ""
        if sid in ids and len(parts) >= 2 and sid not in out:
            out[sid] = parts[1:]
    return out


def propose(langs: list[str], limit: int | None) -> None:
    c = client()
    rows = seeds()[:limit] if limit else seeds()
    for lang in langs:
        done = load(lang, "propose")
        todo = [r for r in rows if r["id"] not in done]
        if not todo:
            continue
        print(f"propose {lang}: {len(todo)} terms", flush=True)

        rows_by_id = {r["id"]: r for r in rows}

        def job(batch: list[dict], lang: str = lang, done: dict = done, rows_by_id: dict = rows_by_id) -> None:
            listing = "\n".join(f'{r["id"]} [{r["domain"]}] "{r["term_en"]}": {r["definition_en"][:220]}' for r in batch)
            msg = [
                {"role": "system", "content": "You are a military terminologist. Follow the output format exactly."},
                {"role": "user", "content":
                    f"For each US military term below, give the standard {NAMES[lang]} term that {PARTNER[lang]} would use for "
                    f"the same concept: up to four candidates, most standard first (the official term used in that country's "
                    f"defense white papers and doctrine first, then common alternatives). Prefer the official military term "
                    f"over a descriptive phrase; keep established acronyms if the force uses them. Kind: native (own-language "
                    f"word), calque (word-for-word translation), loanword (borrowed foreign word, incl. transliteration), "
                    f"acronym. For [brevity] radio words write radio=en when the partner air force says the English word on "
                    f"the radio (then the first candidate is the English word, kind loanword), else radio=native. Write terms in "
                    f"{NAMES[lang]} script (acronyms and English radio words excepted).\n\n"
                    f"Never invent an acronym: give one only when it is in established use. Output one line per term, nothing else:\n"
                    f"<seed_id> | <term> (<kind>) ; <term> (<kind>) ; … | radio=<en or native, only for [brevity] rows>\n\n"
                    f"{listing}"},
            ]
            text = c.chat(msg, temperature=0.2, max_tokens=2500)
            for sid, parts in parse_lines(text, {r["id"] for r in batch}).items():
                cands = []
                for chunk in parts[0].split(";"):
                    m = re.match(r"^(.+?)\s*\((native|calque|loanword|acronym)\)\s*$", chunk.strip())
                    term, kind = (m.group(1).strip(), m.group(2)) if m else (chunk.strip(), "calque")
                    if term:
                        cands.append({"term": term.strip('"「」 '), "kind": kind})
                radio = rows_by_id[sid]["domain"] == "brevity" and len(parts) > 1 and parts[1].replace(" ", "").lower() == "radio=en"
                if cands:
                    done[sid] = {"candidates": cands[:4], "radio_english": radio}
            save(lang, "propose", done)

        run_parallel(job, batches(todo, 15), workers=1)
        missing = [r["id"] for r in todo if r["id"] not in done]
        if missing:
            print(f"  {lang}: {len(missing)} without proposals (rerun to retry)")


# ------------------------------------------------------------------------------------------------ confirm
def norm(text: str, lang: str) -> str:
    t = unicodedata.normalize("NFKC", text)
    return t if lang in CJK else t.lower()


_texts: dict[str, tuple[list[str], list[str]]] = {}


def texts(sid: str) -> tuple[list[str], list[str]]:
    """(reading-order pages, printed page labels) for a machine-usable source."""
    if sid not in _texts:
        S.require(sid, "parse")
        flow = S.pages(sid, layout=False)
        lay = S.pages(sid)
        _texts[sid] = (flow, [D._label(lay[i], i) if i < len(lay) else str(i + 1) for i in range(len(flow))])
    return _texts[sid]


_aap: dict[str, tuple[str, str]] | None = None


def aap06_pairs() -> dict[str, tuple[str, str]]:
    """English term key → (French term, printed page) from AAP-06's "english / français" entry heads."""
    global _aap
    if _aap is None:
        _aap = {}
        flow, labels = texts("nato-aap-06-2019")
        for i, page in enumerate(flow):
            lines = page.splitlines()
            for j, ln in enumerate(lines):
                if " / " not in ln and not ln.rstrip().endswith(" /"):
                    continue
                en, _, fr = ln.partition(" /")
                fr = fr.strip()
                nxt = lines[j + 1].strip() if j + 1 < len(lines) else ""
                if (not fr or (nxt and nxt[0].islower() and len(nxt) < 45 and not nxt.endswith("."))) and nxt:
                    fr = (fr + " " + nxt).strip()
                en, fr = re.sub(r"\s+\d$", "", en.strip()), re.sub(r"\s+\d$", "", fr.strip())
                if en and fr and en[0].islower() and len(en) < 90 and len(fr) < 90:
                    _aap.setdefault(D.key(en), (fr, labels[i]))
    return _aap


_jaindex: dict[str, str] | None = None


def ja_index() -> dict[str, str]:
    """Japanese white-paper index: term → first printed page of the 2026 full edition."""
    global _jaindex
    if _jaindex is None:
        _jaindex = {}
        flow, _ = texts("ja-doj-2026-ja-index")
        for page in flow:
            for ln in page.splitlines():
                m = re.match(r"^(.+?)\s*／\s*(\d+)", ln.strip())
                if m:
                    term = re.sub(r"（.*?）", "", m.group(1)).strip()
                    _jaindex.setdefault(unicodedata.normalize("NFKC", term), m.group(2))
    return _jaindex


def find(term: str, lang: str) -> tuple[str, str, str] | None:
    """(source_id, page, excerpt) of the first machine-usable source page containing [term]."""
    t = norm(term, lang).strip()
    if len(t) < 2:
        return None
    if lang == "ja":
        page = ja_index().get(t)
        if page:
            return "ja-doj-2026-ja", page, f"(index entry: {term} ／ {page})"
    pat = re.compile(re.escape(t) if lang in CJK else rf"(?<![\w-]){re.escape(t)}(?![\w-])")
    for sid in SEARCH.get(lang, []):
        if sid == "ja-doj-2026-ja-index":
            continue
        flow, labels = texts(sid)
        for i, page in enumerate(flow):
            m = pat.search(norm(page, lang))
            if m:
                a, b = max(0, m.start() - 220), min(len(page), m.end() + 220)
                return sid, labels[i], " ".join(page[a:b].split())
    return None


def specific(term: str, lang: str) -> bool:
    t = norm(term, lang).strip()
    return len(t) >= 4 if lang in CJK else len(t.split()) >= 2 or (t.isupper() and len(t) >= 2)


def confirm(langs: list[str]) -> None:
    rows = {r["id"]: r for r in seeds()}
    for lang in langs:
        prop = load(lang, "propose")
        out = {}
        for sid, p in prop.items():
            r = rows.get(sid)
            if r is None:
                continue
            first = p["candidates"][0]
            rec = {"term": first["term"], "kind": first["kind"], "radio_english": p["radio_english"], "source_id": "", "page": "",
                   "excerpt": "", "via": "model"}
            if lang == "fr" and not p["radio_english"]:
                hit = aap06_pairs().get(D.key(re.sub(r"\s*\(.*?\)", "", r["term_en"])))
                if hit:
                    rec.update(term=hit[0], kind=next((c["kind"] for c in p["candidates"] if norm(c["term"], lang) == norm(hit[0], lang)),
                                                       first["kind"]), source_id="nato-aap-06-2019", page=hit[1],
                               excerpt=f"AAP-06 entry head: {r['term_en']} / {hit[0]}", via="aap06-pair")
            if not rec["source_id"] and lang in SEARCH and not p["radio_english"]:
                for n, cand in enumerate(p["candidates"]):
                    if n and not specific(cand["term"], lang):
                        continue  # a generic fallback word somewhere in a white paper does not document the concept
                    hit = find(cand["term"], lang)
                    if hit:
                        rec.update(term=cand["term"], kind=cand["kind"], source_id=hit[0], page=hit[1], excerpt=hit[2], via="source-search")
                        break
            got = load(lang, "retrieve").get(sid, {})
            if not rec["source_id"] and got.get("term"):
                rec.update(term=got["term"], source_id=got["source_id"], page=got["page"], via=got["via"], excerpt="")
            out[sid] = rec
        save(lang, "confirm", out)
        n = sum(1 for v in out.values() if v["source_id"])
        print(f"confirm {lang}: {n}/{len(out)} terms documented in an allied source")


# ------------------------------------------------------------------------------------------------ retrieve
# Attempts 2 and 3 for terms the exact search did not document (D-033): the model chooses among glossary/index entries
# retrieved by similarity (MD35-G-01 for pt-BR, AAP-06 entry pairs for fr, the white-paper index for ja), or reads
# the parallel English and target-language pages of a bilingual white paper (ja, ko, fr) and copies the term it uses.
# Every pick is verified to occur in the cited source page before it is recorded.
PARALLEL = {"ja": ("ja-doj-2025-en", "ja-doj-2025-ja"), "ko": ("ko-dwp-2022-en", "ko-dwp-2022-ko"), "fr": ("fr-rns-2025-en", "fr-rns-2025-fr")}


def fold(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", s.lower()) if not unicodedata.combining(c))


def sim(a: str, b: str, cjk: bool) -> float:
    if cjk:
        x = {a[i:i + 2] for i in range(len(a) - 1)} or {a}
        y = {b[i:i + 2] for i in range(len(b) - 1)} or {b}
    else:
        x, y = set(re.findall(r"\w+", fold(a))), set(re.findall(r"\w+", fold(b)))
    return len(x & y) / len(x | y) if x and y else 0.0


_md35: list[tuple[str, str, str]] | None = None


def md35_heads() -> list[tuple[str, str, str]]:
    """(headword, printed page, definition start) of the Brazilian glossary MD35-G-01."""
    global _md35
    if _md35 is None:
        flow, labels = texts("ptbr-md35-g-01")
        _md35 = []
        for i, page in enumerate(flow):
            for m in re.finditer(r"(?m)^([A-ZÁÉÍÓÚÂÊÔÃÕÇÀ0-9][A-ZÁÉÍÓÚÂÊÔÃÕÇÀ0-9 ,()/\-]{2,80}?) - (\S.{10,240})", page):
                _md35.append((m.group(1).strip(), labels[i], " ".join(m.group(2).split())))
    return _md35


def retrieval_options(lang: str, seed: dict, cands: list[str]) -> list[tuple[str, str, str, str]]:
    """Up to 6 (term, source_id, page, gloss) options from the language's glossary or index."""
    opts: list[tuple[float, str, str, str, str]] = []
    if lang == "pt-BR":
        for head, page, gloss in md35_heads():
            score = max((sim(head, c, False) for c in cands), default=0)
            if score > 0:
                opts.append((score, head, "ptbr-md35-g-01", page, gloss[:180]))
    elif lang == "fr":
        en = re.sub(r"\s*\(.*?\)", "", seed["term_en"])
        for key, (frt, page) in aap06_pairs().items():
            score = sim(key, en, False) + max((sim(frt, c, False) for c in cands), default=0)
            if score > 0.4:
                opts.append((score, frt, "nato-aap-06-2019", page, f"English entry: {key}"))
    elif lang == "ja":
        for term, page in ja_index().items():
            score = max((sim(term, unicodedata.normalize("NFKC", c), True) for c in cands), default=0)
            if score > 0.2:
                opts.append((score, term, "ja-doj-2026-ja", page, "white-paper index entry"))
    opts.sort(key=lambda x: -x[0])
    seen, out = set(), []
    for _, t, sid, page, gloss in opts:
        if t not in seen:
            seen.add(t)
            out.append((t, sid, page, gloss))
        if len(out) == 6:
            break
    return out


def parallel_context(lang: str, seed: dict) -> tuple[str, str, list[int]] | None:
    """(English excerpt, target pages text, target page indexes) around the term in a bilingual white paper."""
    en_id, tg_id = PARALLEL[lang]
    en_flow, _ = texts(en_id)
    tg_flow, _ = texts(tg_id)
    name = re.sub(r"\s*\(.*?\)", "", seed["term_en"]).strip()
    pat = re.compile(rf"(?<![\w-]){re.escape(name)}(?![\w-])", re.IGNORECASE)
    for i, page in enumerate(en_flow):
        m = pat.search(page)
        if m:
            j = round(i * len(tg_flow) / max(1, len(en_flow)))
            idx = [k for k in range(j - 3, j + 4) if 0 <= k < len(tg_flow)]
            en = " ".join(page[max(0, m.start() - 300):m.end() + 300].split())
            tg = "\n".join(f"[page {k + 1}] " + " ".join(tg_flow[k].split())[:1600] for k in idx)
            return en, tg, idx
    return None


def retrieve(langs: list[str]) -> None:
    c = client()
    seeds_by = {r["id"]: r for r in seeds()}
    for lang in [x for x in langs if x in SEARCH]:
        conf, prop = load(lang, "confirm"), load(lang, "propose")
        done = load(lang, "retrieve")
        todo = [sid for sid, k in conf.items() if not k["source_id"] and sid not in done and not k["radio_english"]]
        if not todo:
            continue
        print(f"retrieve {lang}: {len(todo)} undocumented", flush=True)
        for sid in todo:
            seed = seeds_by[sid]
            cands = [x["term"] for x in prop.get(sid, {}).get("candidates", [])]
            result = {"tried": True}
            opts = retrieval_options(lang, seed, cands)
            if opts:
                listing = "\n".join(f"{i + 1}. {t} — {g}" for i, (t, _, _, g) in enumerate(opts))
                text = c.chat([{"role": "user", "content":
                    f"US military term: {seed['term_en']} — {seed['definition_en'][:300]}\n\nEntries from a {NAMES[lang]} official source:\n"
                    f"{listing}\n\nWhich entry is the {NAMES[lang]} term for exactly this concept (same meaning, not a broader or related "
                    f"one)? Answer with the number only, or 0 if none."}], temperature=0.0, max_tokens=10)
                m = re.search(r"\d+", text)
                n = int(m.group()) if m else 0
                if 1 <= n <= len(opts):
                    t, src, page, _ = opts[n - 1]
                    result.update(term=t, source_id=src, page=page, via="glossary-retrieval")
            if "term" not in result and lang in PARALLEL:
                ctx = parallel_context(lang, seed)
                if ctx:
                    en, tg, idx = ctx
                    text = c.chat([{"role": "user", "content":
                        f"An English government document uses the term \"{seed['term_en']}\" here:\n{en}\n\nThese pages are from the "
                        f"{NAMES[lang]} edition of the same document:\n{tg}\n\nCopy, character for character, the {NAMES[lang]} expression "
                        f"this edition uses for \"{seed['term_en']}\" (the term only, no sentence). If it is not there, answer NONE."}],
                        temperature=0.0, max_tokens=40)
                    t = text.strip().strip("\"'「」『』 .。")
                    if t and t.upper() != "NONE" and len(t) <= 40:
                        tg_id = PARALLEL[lang][1]
                        flow, labels = texts(tg_id)
                        hit = next((k for k in idx if norm(t, lang) in norm(flow[k], lang)), None)
                        if hit is not None:
                            result.update(term=t, source_id=tg_id, page=labels[hit], via="parallel-edition")
            done[sid] = result
            save(lang, "retrieve", done)
        got = sum(1 for v in done.values() if v.get("term"))
        print(f"  {lang}: {got} more documented", flush=True)


# ------------------------------------------------------------------------------------------------ draft
def draft(langs: list[str], redo: dict[str, dict[str, str]] | None = None) -> None:
    c = client()
    rows = {r["id"]: r for r in seeds()}
    template = (PROMPTS / "draft_definition.md").read_text(encoding="utf-8").split("---", 1)[-1]
    for lang in langs:
        conf = load(lang, "confirm")
        done = load(lang, "draft")
        want = redo.get(lang, {}) if redo is not None else {}
        todo = [sid for sid in conf if ((sid not in done or done[sid].get("term", conf[sid]["term"]) != conf[sid]["term"]) if redo is None
                                        else sid in want)]
        if not todo:
            continue
        print(f"draft {lang}: {len(todo)}", flush=True)

        def job(batch: list[str], lang: str = lang, conf: dict = conf, done: dict = done, want: dict = want) -> None:
            blocks = []
            for sid in batch:
                r, k = rows[sid], conf[sid]
                where = f"{k['source_id']}, p. {k['page']}" if k["source_id"] else "model-proposed, unconfirmed"
                fb = f"\nREVISE: {want[sid]}" if want.get(sid) else ""
                blocks.append(f'seed_id: {sid}\nterm: {k["term"]}\nconfirmed in: {where}\nUS definition: {r["definition_en"]}\n'
                              f'source: {r["source_doc"]} p. {r["source_page"]}{fb}')
            msg = [
                {"role": "system", "content": "You draft learner-facing definitions for a military terminology app. "
                    "Instructions (the template the project uses):\n" + template.replace("{lang}", NAMES[lang])},
                {"role": "user", "content": f"Target language: {NAMES[lang]}. For each term, write the definition in {NAMES[lang]} "
                    f"(1–2 sentences, intermediate learner, your own wording, based only on the US definition). Use the term exactly "
                    f"as given. If you think the term is wrong, still write the definition and add the reason and an alternative "
                    f"after a second bar. No reference excerpts are provided. Output (instead of JSON) one line per term, nothing "
                    f"else:\n<seed_id> | <definition in {NAMES[lang]}> | <TERM-CONCERN text, or ->\n\n" + "\n\n".join(blocks)},
            ]
            text = c.chat(msg, temperature=0.3, max_tokens=3000)
            for sid, parts in parse_lines(text, set(batch)).items():
                definition = parts[0].strip()
                concern = parts[1].strip() if len(parts) > 1 and parts[1].strip().lower() not in ("-", "->", "", "null", "none", "n/a") else ""
                if definition:
                    done[sid] = {"definition": definition, "term_concern": concern, "term": conf[sid]["term"],
                                 "round": done.get(sid, {}).get("round", 0) + (1 if want else 0)}
            save(lang, "draft", done)

        run_parallel(job, batches(todo, 10), workers=1)


# ------------------------------------------------------------------------------------------------ check
def check(langs: list[str], only: dict[str, set[str]] | None = None) -> None:
    c = client()
    rows = {r["id"]: r for r in seeds()}
    template = (PROMPTS / "check_accuracy.md").read_text(encoding="utf-8").split("---", 1)[-1]
    for lang in langs:
        conf, drafts = load(lang, "confirm"), load(lang, "draft")
        done = load(lang, "check")
        todo = [sid for sid in drafts if ((sid not in done or done[sid].get("definition", drafts[sid]["definition"]) != drafts[sid]["definition"])
                                          if only is None else sid in only.get(lang, set()))]
        if not todo:
            continue
        print(f"check {lang}: {len(todo)}", flush=True)

        def job(batch: list[str], lang: str = lang, conf: dict = conf, drafts: dict = drafts, done: dict = done) -> None:
            blocks = []
            for sid in batch:
                k = conf[sid]
                machine = k["source_id"] and S.refusal(S.rows()[k["source_id"]], "llm") is None
                ex = (f"How the term is used in {k['source_id']}, p. {k['page']} (terminology check only):\n{k['excerpt'][:500]}"
                      if machine and k["excerpt"] else "No allied source excerpt: judge against the US meaning only "
                      "(term_matches_source_usage and definition_consistent_with_source_usage = true).")
                blocks.append(f"seed_id: {sid}\nterm: {k['term']}\ndrafted definition: {drafts[sid]['definition']}\n"
                              f"US definition: {rows[sid]['definition_en']}\n{ex}")
            msg = [
                {"role": "system", "content": "You check drafted definitions for accuracy; you do not rewrite them. The project's "
                    "check template (its JSON answer format is replaced by the line format below):\n" + template.replace("{lang}", NAMES[lang])},
                {"role": "user", "content": f"Language: {NAMES[lang]}. Check each item. match=no only when the excerpt uses the term "
                    "for a different or more general concept than the US definition (match=- when there is no excerpt). verdict: pass, "
                    "fix (small wording problem) or escalate (substantive meaning problem). Output one line per item, nothing else:\n"
                    "<seed_id> | <pass, fix or escalate> | match=<yes, no or -> | <problems in English separated by ';', or ->\n\n" + "\n\n".join(blocks)},
            ]
            text = c.chat(msg, temperature=0.0, max_tokens=6000, model=c.fallback, extra={"reasoning_effort": "low"})
            for sid, parts in parse_lines(text, set(batch)).items():
                verdict = parts[0].lower().strip()
                verdict = verdict if verdict in ("pass", "fix", "escalate") else "escalate"
                match = parts[1].replace(" ", "").lower() if len(parts) > 1 else ""
                problems = [x.strip() for x in parts[2].split(";") if x.strip() not in ("-", "->", "")] if len(parts) > 2 else []
                done[sid] = {"verdict": verdict, "problems": problems[:4], "model": c.fallback,
                             "term_matches_source": match != "match=no", "definition": drafts[sid]["definition"]}
            save(lang, "check", done)

        run_parallel(job, batches(todo, 10), workers=1)


# ------------------------------------------------------------------------------------------------ overlap
def overlap(langs: list[str]) -> dict[str, set[str]]:
    failing: dict[str, set[str]] = {}
    phrases: dict[str, dict[str, str]] = {}
    with tempfile.TemporaryDirectory() as tmp:
        f = Path(tmp) / "defs.jsonl"
        lines = []
        for lang in langs:
            for sid, d in load(lang, "draft").items():
                lines.append(json.dumps({"id": f"{sid}|{lang}", "lang": lang, "definition": d["definition"]}, ensure_ascii=False))
        f.write_text("\n".join(lines), encoding="utf-8")
        rep = Path(tmp) / "r.json"
        subprocess.run([sys.executable, str(HERE / "overlap_check.py"), "check", str(f), "--fields", "definition", "--json", str(rep)],
                       capture_output=True, check=False)
        for fd in json.loads(rep.read_text(encoding="utf-8"))["findings"]:
            if fd["verdict"] == "FAIL":
                sid, lang = fd["item"].split("|")
                failing.setdefault(lang, set()).add(sid)
                phrases.setdefault(lang, {})[sid] = fd.get("run", "")
    for lang in langs:
        save(lang, "overlap", {"failing": sorted(failing.get(lang, set())), "phrases": phrases.get(lang, {})})
    print("overlap: " + ", ".join(f"{lg} {len(failing.get(lg, ()))}" for lg in langs) + " failing")
    return failing


def overlap_feedback(lang: str, sid: str) -> str:
    phrase = load(lang, "overlap").get("phrases", {}).get(sid, "")
    return ("Your earlier wording was too close to a published text" + (f" (it shared the run \"{phrase}\")" if phrase else "") +
            ". Use entirely different wording and sentence structure: change the verb, reorder the clauses, and do not reuse "
            "that run of words.")


def fix_overlap(langs: list[str], rounds: int = 3) -> None:
    """Targeted redraft of the definitions that still fail overlap_check, naming the shared run of words."""
    failing = overlap(langs)
    for _round in range(rounds):
        redo = {lg: {sid: overlap_feedback(lg, sid) for sid in ids} for lg, ids in failing.items() if ids}
        if not redo:
            break
        draft(list(redo), redo)
        check(list(redo), {lg: set(v) for lg, v in redo.items()})
        failing = overlap(langs)


# ------------------------------------------------------------------------------------------------ write
COLUMNS = ["seed_id", "lang", "term", "term_kind", "radio_english", "term_source_id", "term_source_page", "definition", "status",
           "badge", "approvedBy", "notes", "accuracy", "drafted_by", "checked_by"]


def write(langs_done: list[str]) -> None:
    existing: dict[tuple[str, str], dict] = {}
    if OUT.exists():
        with open(OUT, encoding="utf-8") as f:
            for r in csv.DictReader(f):
                existing[(r["seed_id"], r["lang"])] = r
    rows = seeds()
    queue = []
    model = llm.setting("LLM_MODEL") or ""
    for lang in langs_done:
        conf, drafts, checks = load(lang, "confirm"), load(lang, "draft"), load(lang, "check")
        bad = set(load(lang, "overlap").get("failing", []))
        for r in rows:
            sid = r["id"]
            if sid not in conf or sid not in drafts:
                continue
            prev = existing.get((sid, lang))
            if prev and (prev["status"] == "approved" or "rejected by" in prev.get("notes", "")):
                continue  # a reviewer's verdict is never overwritten
            k, d, ck = dict(conf[sid]), drafts[sid], checks.get(sid, {})
            if k["source_id"] and ck.get("term_matches_source") is False:
                k.update(source_id="", page="")  # the checker found the source uses the word for another concept
                notes_pre = ["source usage did not match this concept; citation dropped"]
            else:
                notes_pre = []
            verdict = ck.get("verdict", "")
            ok = verdict == "pass" and sid not in bad
            notes = list(notes_pre)
            if k["via"] == "aap06-pair" and k["source_id"]:
                notes.append("term from the AAP-06 English/French entry pair")
            elif k["via"] == "glossary-retrieval" and k["source_id"]:
                notes.append("term chosen from the source's glossary/index entries")
            elif k["via"] == "parallel-edition" and k["source_id"]:
                notes.append("term read from the parallel edition of the white paper")
            if d.get("term_concern"):
                notes.append("TERM-CONCERN: " + d["term_concern"])
            if verdict and verdict != "pass":
                notes.append(f"check {verdict}: " + "; ".join(ck.get("problems", [])))
            if sid in bad:
                notes.append("overlap: drafted wording too close to a restricted source, withheld; needs a human redraft")
            if lang in HUMAN and not k["source_id"]:
                notes.append("human look-up queued (" + ", ".join(HUMAN[lang]) + ")")
                queue.append({"seed_id": sid, "lang": lang, "term_en": r["term_en"], "proposed_term": k["term"],
                              "sources": " ".join(HUMAN[lang]), "found_term": "", "page": "", "reviewer": ""})
            existing[(sid, lang)] = {
                "seed_id": sid, "lang": lang, "term": k["term"], "term_kind": k["kind"], "radio_english": "yes" if k["radio_english"] else "",
                # Wording that still fails the overlap gate is never written (BRIEF_PHASE8 hard rule): the row ships without it.
                "term_source_id": k["source_id"], "term_source_page": k["page"], "definition": "" if sid in bad else d["definition"],
                "status": "checked" if ok else "draft", "badge": "unreviewed" if k["source_id"] else "unconfirmed-term",
                "approvedBy": "", "notes": " | ".join(notes), "accuracy": verdict or "unchecked", "drafted_by": model,
                "checked_by": ck.get("model", ""),
            }
    order = {r["id"]: i for i, r in enumerate(rows)}
    out = sorted(existing.values(), key=lambda x: (LANGS.index(x["lang"]) if x["lang"] in LANGS else 99, order.get(x["seed_id"], 9999)))
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, COLUMNS, extrasaction="ignore")
        w.writeheader()
        w.writerows(out)
    if queue:
        with open(QUEUE, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, list(queue[0]))
            w.writeheader()
            w.writerows(queue)
    summary(out)


def summary(out: list[dict]) -> None:
    for lang in LANGS:
        rs = [r for r in out if r["lang"] == lang]
        if not rs:
            continue
        doc = sum(1 for r in rs if r["term_source_id"])
        chk = sum(1 for r in rs if r["status"] == "checked")
        print(f"  {lang:8} {len(rs):4} rows · documented {doc:4} ({100 * doc / len(rs):5.1f}%) · checked {chk:4}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["propose", "confirm", "retrieve", "draft", "check", "overlap", "fixoverlap", "write", "all"])
    ap.add_argument("--language", default="all")
    ap.add_argument("--limit", type=int)
    a = ap.parse_args()
    langs = LANGS if a.language == "all" else a.language.split(",")
    try:
        if a.stage in ("propose", "all"):
            propose(langs, a.limit)
        if a.stage in ("confirm", "all"):
            confirm(langs)
        if a.stage in ("draft", "all"):
            draft(langs)
        if a.stage in ("check", "all"):
            check(langs)
        if a.stage in ("overlap", "all"):
            failing = overlap(langs)
            if a.stage == "all":
                for _round in range(2):  # redraft overlap failures and "fix" verdicts, then re-check them
                    redo: dict[str, dict[str, str]] = {}
                    for lang in langs:
                        ck = load(lang, "check")
                        for sid in failing.get(lang, set()):
                            redo.setdefault(lang, {})[sid] = overlap_feedback(lang, sid)
                        for sid, v in ck.items():
                            if v["verdict"] == "fix":
                                redo.setdefault(lang, {})[sid] = "Fix: " + "; ".join(v["problems"])
                    if not redo:
                        break
                    draft(langs, redo)
                    check(langs, {lg: set(v) for lg, v in redo.items()})
                    failing = overlap(langs)
        if a.stage == "fixoverlap":
            fix_overlap(langs)
            write(langs)
        if a.stage == "retrieve":
            retrieve(langs)
            confirm(langs)
            draft(langs)  # redrafts rows whose term changed
            check(langs)  # re-checks rows whose definition changed
            overlap(langs)
            write(langs)
        if a.stage in ("write", "all"):
            write(langs)
    except llm.EndpointDown as e:
        print(f"align_terms: {e}\nrerun: {e.rerun}")
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
