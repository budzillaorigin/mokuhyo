"""Build content/packs/practice.sqlite: speaking & listening practice content (BRIEF §5.9, §5.10).

- Role-play scenarios with scripted fallback turns (tools/packs/speaking/scenarios.json, source="llm").
- OPI practice question banks per ILR level + self-rating checklists paraphrased from the public-domain ILR
  speaking descriptors (tools/packs/speaking/opi.json).
- Two-speaker listening dialogues with comprehension questions, gap-fill targets and "order the chunks" splits
  (tools/packs/listening/dialogues.json, source="llm").
- Minimal pairs derived algorithmically from the dictionary pack (JMdict kana + Kanjium pitch), source="derived".

Vocabulary and gap targets are resolved to JMdict entry ids; the build fails on anything that doesn't resolve.
Requires content/packs/dictionary.sqlite (build_dictionary.py).

Run: uv run python packs/build_practice.py
"""

from __future__ import annotations

import json
import re
from collections import defaultdict
from pathlib import Path

from common import (
    DICTIONARY_PACK,
    PACKS,
    REPO,
    dumps,
    finish_pack,
    log,
    nfc,
    open_pack,
    reset_tables,
    set_meta,
    to_hiragana,
)

PRACTICE_PACK = PACKS / "practice.sqlite"
PRACTICE_SQ = REPO / "shared/src/commonMain/sqldelightPractice/app/tsumugi/practice/db/practice.sq"
HERE = Path(__file__).resolve().parent
SCENARIOS = HERE / "speaking" / "scenarios.json"
OPI = HERE / "speaking" / "opi.json"
DIALOGUES = HERE / "listening" / "dialogues.json"
PRACTICE_PACK_VERSION = "1"

TABLES = {
    "pack_meta", "scenario", "scripted_turn", "opi_question", "opi_checklist",
    "dialogue", "dialogue_line", "dialogue_question", "minimal_pair",
}
ILR_LEVELS = ["0+", "1", "1+", "2", "2+", "3"]
REGISTERS = {"casual", "polite", "keigo"}
PHASES = {"WARM_UP", "LEVEL_CHECK", "PROBE", "ROLE_PLAY", "WIND_DOWN"}
VOICES = {"male", "female"}
AGES = {"young", "adult", "senior"}

JAPANESE = re.compile(r"[ぁ-ゖァ-ヺ一-鿿々]")
KANA_WORD = re.compile(r"^[ぁ-ゖー]+$")

# Minimal pairs: per-category caps (~600 total), most common first.
PAIR_CAPS = {"LENGTH": 150, "GEMINATION": 110, "VOICING": 150, "NASAL": 70, "PITCH": 150}
MIN_READING, MAX_READING = 2, 6


class BuildError(Exception):
    pass


def require(cond: bool, msg: str) -> None:
    if not cond:
        raise BuildError(msg)


def japanese(s: str, where: str) -> str:
    require(bool(JAPANESE.search(s)), f"{where}: expected Japanese text, got {s!r}")
    return nfc(s)


def utf16_offsets(text: str, start: int, end: int) -> tuple[int, int]:
    """Convert code-point offsets to UTF-16 code-unit offsets (what Kotlin String indices use)."""
    def units(s: str) -> int:
        return len(s.encode("utf-16-le")) // 2
    return units(text[:start]), units(text[start:end]) + units(text[:start])


# ---------------------------------------------------------------- dictionary lookup

class Dictionary:
    """Exact-form lookup of JMdict entries (written forms and readings), most common entry first."""

    def __init__(self) -> None:
        db = open_pack(DICTIONARY_PACK)
        self.db = db
        rank = dict(db.execute("SELECT id, rank FROM entry"))
        self.by_form: dict[str, list[int]] = defaultdict(list)
        for eid, text in db.execute("SELECT entry_id, text FROM entry_kanji"):
            self.by_form[text].append(eid)
        for eid, text in db.execute("SELECT entry_id, text FROM entry_kana"):
            self.by_form[text].append(eid)
        for ids in self.by_form.values():
            ids.sort(key=lambda e: (rank[e], e))
        self.reading = {e: t for e, t in db.execute("SELECT entry_id, text FROM entry_kana WHERE ord = 0")}

    def lookup(self, form: str) -> int | None:
        ids = self.by_form.get(nfc(form))
        return ids[0] if ids else None

    def close(self) -> None:
        self.db.close()


# ---------------------------------------------------------------- chunks

CHUNK_PARTICLES = set("はがをにでへもと")
# Particles (other than が, which starts verbs like 上がる/曲がる) may also break before hiragana, unless the
# hiragana continues the word or particle: です/でした/できる, には/では/にも/での/とか…
LOOSE_PARTICLES = set("はをにでへもと")
NO_BREAK_BEFORE = set("すしきはもがのをかねよぁぃぅぇぉゃゅょっー")
CHUNK_PUNCT = set("、。？！…")
MAX_CHUNKS = 6


def is_hiragana(c: str) -> bool:
    return "ぁ" <= c <= "ゟ"


def chunk(ja: str) -> list[str]:
    """Split a line into phrase chunks for the "order the chunks" drill; the chunks concatenate to `ja`.

    Breaks after punctuation, and after a particle that follows kanji/katakana (so ですか, ありがとう, etc. are
    never split). Deterministic and tokenizer-free; lines that don't split into 3+ chunks just aren't used
    for the drill.
    """
    out, cur = [], ""
    for i, c in enumerate(ja):
        cur += c
        nxt = ja[i + 1] if i + 1 < len(ja) else ""
        if not nxt:
            break
        after_punct = c in CHUNK_PUNCT and nxt not in CHUNK_PUNCT and nxt not in "」）"
        after_particle = (
            c in CHUNK_PARTICLES and len(cur) >= 2 and not is_hiragana(cur[-2]) and nxt not in CHUNK_PUNCT
            and (not is_hiragana(nxt) or (c in LOOSE_PARTICLES and nxt not in NO_BREAK_BEFORE))
        )
        if after_punct or after_particle:
            out.append(cur)
            cur = ""
    if cur:
        out.append(cur)
    while len(out) > MAX_CHUNKS:  # merge the shortest adjacent pair
        i = min(range(len(out) - 1), key=lambda k: len(out[k]) + len(out[k + 1]))
        out[i:i + 2] = [out[i] + out[i + 1]]
    assert "".join(out) == ja
    return out


# ---------------------------------------------------------------- scenarios

REGISTER_GUIDE = {
    "casual": "casual plain-form Japanese, as between friends",
    "polite": "polite です/ます Japanese",
    "keigo": "formal Japanese with appropriate keigo (尊敬語/謙譲語)",
}


def system_prompt(s: dict) -> str:
    goals = "; ".join(s["goals"])
    vocab = "、".join(s["vocabulary"])
    return (
        f"You are role-playing a conversation with a Japanese learner. Your role: {s['partnerRole']}. "
        f"The learner's role: {s['learnerRole']}. Situation: {s['setting']}\n"
        f"Rules: Stay in character at all times and never mention that you are an AI. "
        f"Speak only in {REGISTER_GUIDE[s['register']]}. Keep every reply to one or two short sentences. "
        f"Adapt your vocabulary and grammar to about JLPT N{s['jlpt']} (ILR {s['ilr']}); if the learner seems "
        f"lost, rephrase more simply instead of switching language. Do not use English unless the learner "
        f"explicitly asks for it. Keep all content PG and respectful. "
        f"Steer the conversation so the learner gets a chance to: {goals}. "
        f"Where natural, use these words: {vocab}. "
        f"Start the conversation with your first line in character."
    )


def build_scenarios(db, dic: Dictionary) -> tuple[int, int]:
    doc = json.loads(SCENARIOS.read_text(encoding="utf-8"))
    source = doc.get("source", "llm")
    seen: set[str] = set()
    n_turns = 0
    for ord_, s in enumerate(doc["scenarios"]):
        sid = s["id"]
        where = f"scenario {sid}"
        require(sid not in seen, f"duplicate scenario id {sid}")
        seen.add(sid)
        require(s["jlpt"] in (1, 2, 3, 4, 5), f"{where}: bad jlpt {s['jlpt']}")
        require(s["ilr"] in ILR_LEVELS, f"{where}: bad ilr {s['ilr']}")
        require(s["register"] in REGISTERS, f"{where}: bad register {s['register']}")
        require(2 <= len(s["goals"]) <= 4, f"{where}: needs 2-4 goals")
        require(6 <= len(s["turns"]) <= 10, f"{where}: needs 6-10 scripted turns")
        vocab = []
        for w in s["vocabulary"]:
            eid = dic.lookup(w)
            require(eid is not None, f"{where}: vocabulary {w!r} not in JMdict")
            vocab.append({"text": nfc(w), "reading": dic.reading.get(eid, ""), "entryId": eid})
        phrases = [japanese(p, f"{where} phrase") for p in s["phrases"]]
        db.execute(
            "INSERT INTO scenario VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                sid, ord_, s["titleEn"], japanese(s["titleJa"], f"{where} title"), s["jlpt"], s["ilr"],
                s["category"], s["setting"], s["learnerRole"], s["partnerRole"], s["register"],
                dumps(s["goals"]), dumps(vocab), dumps(phrases), system_prompt(s), s.get("source", source),
            ),
        )
        for i, t in enumerate(s["turns"]):
            db.execute(
                "INSERT INTO scripted_turn VALUES (?,?,?,?,?,?)",
                (
                    sid, i, japanese(t["partnerJa"], f"{where} turn {i}"), t["partnerEn"], t["intent"],
                    japanese(t["sample"], f"{where} turn {i} sample"),
                ),
            )
            n_turns += 1
    return len(seen), n_turns


# ---------------------------------------------------------------- OPI

def build_opi(db) -> tuple[int, int]:
    doc = json.loads(OPI.read_text(encoding="utf-8"))
    source = doc.get("source", "llm")
    n_q = n_c = 0
    levels = [lv["ilr"] for lv in doc["levels"]]
    require(sorted(levels, key=ILR_LEVELS.index) == ILR_LEVELS, f"OPI levels must be exactly {ILR_LEVELS}")
    for lv in doc["levels"]:
        ilr = lv["ilr"]
        phases = {q[0] for q in lv["questions"]}
        require(phases == PHASES, f"OPI {ilr}: every phase needs at least one question (have {sorted(phases)})")
        for i, (phase, ja, en, note) in enumerate(lv["questions"]):
            db.execute(
                "INSERT INTO opi_question VALUES (?,?,?,?,?,?,?)",
                (ilr, i, phase, japanese(ja, f"OPI {ilr} q{i}"), en, note, source),
            )
            n_q += 1
        for i, statement in enumerate(lv["checklist"]):
            db.execute("INSERT INTO opi_checklist VALUES (?,?,?)", (ilr, i, statement))
            n_c += 1
    set_meta(db, opi_checklist_source=doc["checklistSource"])
    return n_q, n_c


# ---------------------------------------------------------------- dialogues

def build_dialogues(db, dic: Dictionary) -> tuple[int, int, int, int]:
    doc = json.loads(DIALOGUES.read_text(encoding="utf-8"))
    source = doc.get("source", "llm")
    seen: set[str] = set()
    n_lines = n_gaps = n_q = 0
    for ord_, d in enumerate(doc["dialogues"]):
        did = d["id"]
        where = f"dialogue {did}"
        require(did not in seen, f"duplicate dialogue id {did}")
        seen.add(did)
        require(d["jlpt"] in (3, 4, 5), f"{where}: listening dialogues are N5-N3, got N{d['jlpt']}")
        speakers = d["speakers"]
        ids = {sp["id"] for sp in speakers}
        require(len(speakers) == 2 and len(ids) == 2, f"{where}: needs exactly two distinct speakers")
        for sp in speakers:
            require(sp["voice"] in VOICES and sp["age"] in AGES, f"{where}: bad voice hint {sp}")
            sp["name"] = japanese(sp["name"], f"{where} speaker name")
        require(6 <= len(d["lines"]) <= 12, f"{where}: needs 6-12 lines")
        require(len(d["questions"]) >= 1, f"{where}: needs a comprehension question")
        db.execute(
            "INSERT INTO dialogue VALUES (?,?,?,?,?,?,?)",
            (did, ord_, d["title"], d["jlpt"], d["topic"], dumps(speakers), d.get("source", source)),
        )
        for i, ln in enumerate(d["lines"]):
            require(ln["speaker"] in ids, f"{where} line {i}: unknown speaker {ln['speaker']}")
            ja = japanese(ln["ja"], f"{where} line {i}")
            gaps = []
            for g in ln["gaps"]:
                g = nfc(g)
                pos = ja.find(g)
                require(pos >= 0, f"{where} line {i}: gap {g!r} is not in {ja!r}")
                eid = dic.lookup(g)
                require(eid is not None, f"{where} line {i}: gap {g!r} is not a JMdict word")
                start, end = utf16_offsets(ja, pos, pos + len(g))
                gaps.append({"text": g, "start": start, "end": end, "entryId": eid})
            gaps.sort(key=lambda x: x["start"])
            db.execute(
                "INSERT INTO dialogue_line VALUES (?,?,?,?,?,?,?)",
                (did, i, ln["speaker"], ja, ln["en"], dumps(gaps), dumps(chunk(ja))),
            )
            n_lines += 1
            n_gaps += len(gaps)
        for i, q in enumerate(d["questions"]):
            require(0 <= q["answer"] < len(q["choices"]) and len(q["choices"]) >= 2,
                    f"{where} question {i}: answer index out of range")
            require(len(set(q["choices"])) == len(q["choices"]), f"{where} question {i}: duplicate choices")
            db.execute(
                "INSERT INTO dialogue_question VALUES (?,?,?,?,?)",
                (did, i, q["question"], dumps(q["choices"]), q["answer"]),
            )
            n_q += 1
    return len(seen), n_lines, n_gaps, n_q


# ---------------------------------------------------------------- minimal pairs

SMALL = set("ゃゅょぁぃぅぇぉゎ")
VOICED = dict(zip(
    "かきくけこさしすせそたちつてとはひふへほ",
    "がぎぐげござじずぜぞだぢづでどばびぶべぼ",
))
# Vowel that lengthens a mora (え-row: both ええ and えい occur in words).
VOWEL_ROWS = {
    "あ": "あかさたなはまやらわがざだばぱ", "い": "いきしちにひみりぎじぢびぴ",
    "う": "うくすつぬふむゆるぐずづぶぷ", "え": "えけせてねへめれげぜでべぺ", "お": "おこそとのほもよろごぞどぼぽ",
}
VOWEL_OF = {c: v for v, row in VOWEL_ROWS.items() for c in row}
YOON_VOWEL = {"ゃ": "あ", "ゅ": "う", "ょ": "お"}
GEMINABLE = set("かきくけこさしすせそたちつてとぱぴぷぺぽ")


def morae(reading: str) -> list[str]:
    out: list[str] = []
    for c in reading:
        if c in SMALL and out:
            out[-1] += c
        else:
            out.append(c)
    return out


def length_variants(m: list[str]) -> set[str]:
    out = set()
    for i, mo in enumerate(m):
        if mo in "んっー":
            continue
        v = YOON_VOWEL.get(mo[-1]) if len(mo) > 1 else VOWEL_OF.get(mo)
        if not v:
            continue
        exts = {"あ": "あ", "い": "い", "う": "う", "え": "いえ", "お": "うお"}[v]
        for ext in exts:
            nxt = m[i + 1] if i + 1 < len(m) else ""
            if nxt != ext:  # don't "lengthen" a mora that's already long
                out.add("".join(m[:i + 1]) + ext + "".join(m[i + 1:]))
    return out


def gemination_variants(m: list[str]) -> set[str]:
    return {
        "".join(m[:i]) + "っ" + "".join(m[i:])
        for i in range(1, len(m))
        if m[i][0] in GEMINABLE and m[i - 1] not in "っんー"
    }


def voicing_variants(m: list[str]) -> set[str]:
    return {"".join(m[:i]) + VOICED[mo[0]] + mo[1:] + "".join(m[i + 1:]) for i, mo in enumerate(m) if mo[0] in VOICED}


def nasal_variants(m: list[str]) -> set[str]:
    return {"".join(m[:i]) + "ん" + "".join(m[i:]) for i in range(1, len(m) + 1) if m[i - 1] not in "んっ"}


def build_minimal_pairs(db, dic: Dictionary) -> dict[str, int]:
    d = dic.db
    gloss, kana_usually = {}, set()
    for eid, glosses, misc in d.execute("SELECT entry_id, glosses, misc FROM sense WHERE ord = 0"):
        gl = json.loads(glosses) if glosses else []
        if gl:
            gloss[eid] = gl[0]
        if misc and "uk" in json.loads(misc):  # "usually written using kana alone": show まだ, not 未だ
            kana_usually.add(eid)
    kanji = dict(d.execute("SELECT entry_id, text FROM entry_kanji WHERE ord = 0"))
    pitch: dict[tuple[str, str], int] = {}
    for text, reading, accents in d.execute("SELECT text, reading, accents FROM pitch"):
        first = accents.split(",")[0].strip()
        if first.isdigit():
            pitch[(nfc(text), to_hiragana(nfc(reading)))] = int(first)

    # Common or JLPT-tagged words with a plain-kana reading of 2-6 morae and an English gloss.
    words = []
    for eid, rank, jlpt in d.execute(
        "SELECT id, rank, jlpt FROM entry WHERE is_common = 1 OR jlpt IS NOT NULL ORDER BY rank, id"
    ):
        reading = to_hiragana(dic.reading.get(eid, ""))
        if not KANA_WORD.match(reading) or eid not in gloss:
            continue
        if not MIN_READING <= len(morae(reading)) <= MAX_READING:
            continue
        kana = dic.reading[eid]
        text = kana if eid in kana_usually else kanji.get(eid, kana)
        words.append((eid, rank, text, reading, kanji.get(eid, kana)))  # [4]: the form Kanjium keys pitch by
    best_by_reading: dict[str, tuple] = {}
    for w in words:
        best_by_reading.setdefault(w[3], w)  # words are rank-ordered: first is most common

    def accent(w) -> int | None:
        return pitch.get((w[4], w[3]))

    pairs: dict[str, list[tuple]] = defaultdict(list)
    seen: set[tuple[int, int]] = set()

    def add(cat: str, a, b) -> None:
        key = (min(a[0], b[0]), max(a[0], b[0]))
        if key in seen or a[0] == b[0]:
            return
        seen.add(key)
        pairs[cat].append((max(a[1], b[1]), a, b))

    variants = {
        "LENGTH": length_variants, "GEMINATION": gemination_variants,
        "VOICING": voicing_variants, "NASAL": nasal_variants,
    }
    for reading, a in best_by_reading.items():
        m = morae(reading)
        for cat, fn in variants.items():
            for v in fn(m):
                b = best_by_reading.get(v)
                if b:
                    add(cat, a, b)

    # Pitch homographs: same reading, different words, different (first) accent.
    by_reading: dict[str, list[tuple]] = defaultdict(list)
    for w in words:
        if accent(w) is not None:
            by_reading[w[3]].append(w)
    for group in by_reading.values():
        for i, a in enumerate(group):
            for b in group[i + 1:]:
                if accent(a) != accent(b) and a[2] != b[2] and gloss[a[0]] != gloss[b[0]]:
                    add("PITCH", a, b)
                    break  # one partner per word keeps the list varied

    counts = {}
    pid = 0
    for cat, cap in PAIR_CAPS.items():
        chosen = sorted(pairs[cat], key=lambda p: (p[0], p[1][0], p[2][0]))[:cap]
        for rank, a, b in chosen:
            if len(a[3]) > len(b[3]):
                a, b = b, a  # shorter form first (base vs. long/geminate/nasal); for voicing, unvoiced first
            db.execute(
                "INSERT INTO minimal_pair VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (pid, cat, a[0], a[2], a[3], accent(a), gloss[a[0]], b[0], b[2], b[3], accent(b), gloss[b[0]], rank),
            )
            pid += 1
        counts[cat] = len(chosen)
    return counts


# ---------------------------------------------------------------- main

def main() -> None:
    require(DICTIONARY_PACK.exists(), f"{DICTIONARY_PACK} missing: run build_dictionary.py first")
    dic = Dictionary()
    db = open_pack(PRACTICE_PACK, PRACTICE_SQ)
    reset_tables(db, TABLES, PRACTICE_SQ)
    try:
        n_scen, n_turns = build_scenarios(db, dic)
        n_q, n_c = build_opi(db)
        n_dia, n_lines, n_gaps, n_dq = build_dialogues(db, dic)
        pair_counts = build_minimal_pairs(db, dic)
    except BuildError as e:
        db.close()
        dic.close()
        raise SystemExit(f"practice: {e}") from None
    dic.close()
    n_pairs = sum(pair_counts.values())
    set_meta(
        db, pack="practice", pack_version=PRACTICE_PACK_VERSION, scenarios=str(n_scen), dialogues=str(n_dia),
        minimal_pairs=str(n_pairs),
    )
    finish_pack(db)
    log(f"practice: {n_scen} scenarios / {n_turns} scripted turns")
    log(f"practice: OPI {n_q} questions, {n_c} checklist statements over {len(ILR_LEVELS)} ILR levels")
    log(f"practice: {n_dia} dialogues / {n_lines} lines / {n_gaps} gap targets / {n_dq} questions")
    log(f"practice: {n_pairs} minimal pairs " + ", ".join(f"{k}={v}" for k, v in pair_counts.items()))


if __name__ == "__main__":
    main()
