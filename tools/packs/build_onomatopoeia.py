"""Onomatopoeia module (BRIEF_V2 §6.8, DECISIONS D-235..D-237): extra tables in content/packs/dictionary.sqlite.

The word list is every JMdict entry tagged on-mim. Glosses come from JMdict (CC BY-SA 4.0) and examples from the Tatoeba
sentences already in the pack (CC BY 2.0 FR). Our own data lives in tools/packs/onomatopoeia/:
- themes.json: the theme groups and their original SVG glyphs, one per theme.
- entries.json: per word, the theme, the type (giongo / gitaigo / gijougo), a one-line English "feel" and an
  optional Japanese one. It's drafted by an LLM (source "llm", badge on) until a human reviews it.
Words without an entry get a rule-based theme and type (source "rule") and show only gloss and examples.
The schema is shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/onomatopoeia.sq.

    uv run python packs/build_onomatopoeia.py                     # build the tables (after build_sentences.py)
    uv run python packs/build_onomatopoeia.py status              # counts
    uv run python packs/build_onomatopoeia.py merge drafts.json   # [{"id", "theme", "type", "feel", "feel_ja"}] files
    uv run python packs/build_onomatopoeia.py draft --endpoint http://localhost:11434/v1 --model qwen3:8b --limit 100

merge and draft only add entries or fill missing feel lines (merge --overwrite replaces), so ids never duplicate.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from common import (
    DICTIONARY_PACK,
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
from llm_draft import add_endpoint_args, chat_json

ONOMATOPOEIA_SQ = REPO / "shared/src/commonMain/sqldelightDictionary/app/tsumugi/dictionary/db/onomatopoeia.sq"
DATA = Path(__file__).resolve().parent / "onomatopoeia"
THEMES_FILE = DATA / "themes.json"
ENTRIES_FILE = DATA / "entries.json"

TYPES = {"giongo": "GIONGO", "gitaigo": "GITAIGO", "gijougo": "GIJOUGO"}
MAX_FEEL, MAX_FEEL_JA = 100, 40
MAX_EXAMPLES = 3
EXAMPLE_MAX_LEN, EXAMPLE_IDEAL_LEN = 40, 18
MAX_VARIANTS = 6
EXCLUDED_TAGS = ('"vulg"', '"X"')
FALLBACK_MIN_FORM = 3
ENTRIES_NOTE = (
    "Theme, type and feel lines for JMdict on-mim entries, most frequent first (BRIEF_V2 §6.8, DECISIONS D-235). "
    "Written for Tsumugi by an LLM (Claude, owner decision, 2026-09-18) in its own words; source \"llm\" shows the "
    "AI-generated badge until a human reviews the entry. CC BY-SA 4.0, Tsumugi contributors. Add more with "
    "build_onomatopoeia.py merge / draft."
)

# Rule-based fallback for words nobody has classified yet (first matching gloss keyword wins).
THEME_RULES = [
    ("weather", r"\b(rain|drizzl|snow|wind|thunder|sunshine|sunny|breez|storm)"),
    ("pain", r"\b(pain|ache|aching|throb|sting|prickl|itch|smart(ing)?|sore|nause)"),
    ("voice", r"\b(laugh|giggl|cry|crying|sob|weep|shout|mutter|murmur|chatter|whisper|bark|meow|moo|chirp|croak|voice)"),
    ("eating", r"\b(eat|eating|chew|gulp|slurp|swallow|munch|drink|sip)"),
    ("texture", r"\b(sticky|smooth|rough|soft|fluffy|slimy|slippery|crisp|crunch|chewy|moist|soggy|dry|wet|damp)"),
    ("feelings", r"\b(nervous|excit|irritat|anxious|worr|reliev|disappoint|surpris|angr|fear|afraid|happy|joy|sad|lonely|annoy)"),
    ("body", r"\b(sleep|drows|tired|exhaust|sweat|hungry|heartbeat|dizz|drunk|breath)"),
    ("appearance", r"\b(shin|glitter|sparkl|twinkl|flash|glar|stare|gaz|plump|fat|thin|slender|bright|dim)"),
    ("movement", r"\b(walk|run|roll|spin|swing|sway|shak|trembl|flutter|fall|jump|hop|creep|crawl|dash|quick move)"),
    ("sounds", r"\b(bang|clatter|rattle|knock|ring|clang|thud|crash|splash|rustl|creak|click|pop|boom|sound|noise)"),
    ("state", r"\b(scatter|packed|full|empty|mess|loose|crowd|tight|in order|neat)"),
]
SOUND_WORDS = re.compile(r"\b(sound|noise|bang|clatter|rattle|knock|ring|thud|crash|splash|creak|click|boom|bark|meow|chirp|laugh|cry|shout)")
FEELING_WORDS = re.compile(r"\b(nervous|excit|irritat|anxious|worr|reliev|disappoint|surpris|angr|afraid|annoy|lonely|joy)")


def load_themes() -> list[dict]:
    return json.loads(THEMES_FILE.read_text(encoding="utf-8"))["themes"]


def load_entries() -> dict:
    data = json.loads(ENTRIES_FILE.read_text(encoding="utf-8")) if ENTRIES_FILE.exists() else {"entries": []}
    data["note"] = data.get("note") or ENTRIES_NOTE
    return {"note": data["note"], "entries": data["entries"]}


def save_entries(data: dict) -> None:
    ENTRIES_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def on_mim_words(db) -> list[dict]:
    """Every on-mim entry, most frequent first: forms and the glosses of its first on-mim sense."""
    ids = [r[0] for r in db.execute(
        """SELECT e.id FROM entry e WHERE e.id IN (SELECT entry_id FROM sense WHERE misc LIKE '%"on-mim"%')
           ORDER BY e.rank, e.id"""
    )]
    words = []
    for eid in ids:
        kana = [nfc(r[0]) for r in db.execute("SELECT text FROM entry_kana WHERE entry_id = ? ORDER BY ord", (eid,))]
        kanji = [nfc(r[0]) for r in db.execute("SELECT text FROM entry_kanji WHERE entry_id = ? ORDER BY ord", (eid,))]
        senses = db.execute("SELECT glosses, misc FROM sense WHERE entry_id = ? ORDER BY ord", (eid,)).fetchall()
        mim_sense = next(((g, misc) for g, misc in senses if "on-mim" in misc), senses[0] if senses else ("[]", ""))
        if not kana or any(tag in mim_sense[1] for tag in EXCLUDED_TAGS):
            continue  # vulgar and X-rated senses stay out of a study module (D-235)
        words.append({"id": eid, "kana": kana, "kanji": kanji, "gloss": json.loads(mim_sense[0])})
    return words


def to_katakana(s: str) -> str:
    return "".join(chr(ord(c) + 0x60) if "ぁ" <= c <= "ゖ" else c for c in s)


def forms(word: dict) -> list[str]:
    return word["kana"] + word["kanji"]


def contains_form(text: str, word: dict) -> bool:
    folded = to_hiragana(text)
    return any(f in text or to_hiragana(f) in folded for f in word["kana"] if len(f) >= 2) or \
        any(f in text for f in word["kanji"])


def validate(entry: dict, word: dict, theme_ids: set[str]) -> list[str]:
    issues = []
    if entry.get("theme") not in theme_ids:
        issues.append(f"unknown theme {entry.get('theme')!r}")
    if entry.get("type") not in TYPES:
        issues.append(f"unknown type {entry.get('type')!r}")
    feel, feel_ja = entry.get("feel", ""), entry.get("feel_ja", "")
    if feel and (not feel.isascii() or len(feel) > MAX_FEEL):
        issues.append("feel must be ASCII and at most 100 characters")
    if feel and contains_form(feel, word):
        issues.append("feel contains the word")
    if feel_ja and (len(feel_ja) > MAX_FEEL_JA or contains_form(feel_ja, word)):
        issues.append("feel_ja is too long or contains the word")
    return issues


def rule_classify(word: dict) -> tuple[str, str]:
    gloss = " ".join(word["gloss"]).lower()
    theme = next((t for t, rx in THEME_RULES if re.search(rx, gloss)), "manner")
    typ = "giongo" if SOUND_WORDS.search(gloss) else "gijougo" if FEELING_WORDS.search(gloss) else "gitaigo"
    return theme, typ


def examples(db, word: dict) -> list[int]:
    """Up to MAX_EXAMPLES Tatoeba sentences linked to the entry that show one of its forms literally."""
    rows = db.execute(
        "SELECT s.id, s.ja FROM sentence_word w JOIN sentence s ON s.id = w.sentence_id WHERE w.entry_id = ?",
        (word["id"],),
    ).fetchall()
    fitting = [(sid, ja) for sid, ja in rows if len(ja) <= EXAMPLE_MAX_LEN and contains_form(ja, word)]
    if len(fitting) < MAX_EXAMPLES:
        # The pack links a sentence to a word only through Tatoeba's own index, which misses most mimetic words. Fall
        # back to a literal search for forms of 3+ characters (shorter ones match inside unrelated words).
        seen = {sid for sid, _ in fitting}
        kana_variants = {v for k in word["kana"] for v in (to_hiragana(k), to_katakana(k))}
        for f in sorted(set(forms(word)) | kana_variants):
            if len(f) < FALLBACK_MIN_FORM:
                continue
            for sid, ja in db.execute(
                "SELECT id, ja FROM sentence WHERE instr(ja, ?) > 0 AND length(ja) <= ?", (f, EXAMPLE_MAX_LEN)
            ):
                if sid not in seen:
                    seen.add(sid)
                    fitting.append((sid, ja))
    fitting.sort(key=lambda r: (abs(len(r[1]) - EXAMPLE_IDEAL_LEN), r[0]))
    return [sid for sid, _ in fitting[:MAX_EXAMPLES]]


def build() -> None:
    if not DICTIONARY_PACK.exists():
        raise SystemExit("dictionary pack not built yet: run build_dictionary.py and build_sentences.py first")
    db = open_pack(DICTIONARY_PACK)
    reset_tables(db, {"onomatopoeia", "onomatopoeia_theme"}, ONOMATOPOEIA_SQ)
    themes = load_themes()
    theme_ids = {t["id"] for t in themes}
    db.executemany(
        "INSERT INTO onomatopoeia_theme VALUES (?,?,?,?,?,?)",
        [(t["id"], i, t["title"], t["title_ja"], t["blurb"], t["svg"]) for i, t in enumerate(themes)],
    )
    authored = {e["id"]: e for e in load_entries()["entries"]}
    excluded = {i for i, e in authored.items() if e.get("exclude")}  # reviewed out by hand (explicit content)
    words = [w for w in on_mim_words(db) if w["id"] not in excluded]
    counts = {"feel": 0, "feel_ja": 0, "rule": 0, "examples": 0, "rejected": 0}
    rows = []
    for order, w in enumerate(words, 1):
        entry = authored.get(w["id"])
        if entry and (problems := validate(entry, w, theme_ids)):
            log(f"{w['id']} {w['kana'][0]}: {'; '.join(problems)}; using rules and no feel")
            counts["rejected"] += 1
            entry = None
        if entry:
            theme, typ, feel, feel_ja = entry["theme"], entry["type"], entry.get("feel", ""), entry.get("feel_ja", "")
            source = entry.get("source", "llm")
        else:
            (theme, typ), feel, feel_ja, source = rule_classify(w), "", "", "rule"
            counts["rule"] += 1
        ex = examples(db, w)
        counts["feel"] += bool(feel)
        counts["feel_ja"] += bool(feel_ja)
        counts["examples"] += bool(ex)
        variants = [f for f in forms(w) if f != w["kana"][0]][:MAX_VARIANTS]
        rows.append((
            w["id"], order, w["kana"][0], dumps(variants), TYPES[typ], theme, dumps(w["gloss"]),
            nfc(feel), nfc(feel_ja), dumps(ex), source,
        ))
    db.executemany("INSERT INTO onomatopoeia VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
    by_theme = {t: sum(1 for r in rows if r[5] == t) for t in theme_ids}
    set_meta(
        db, onomatopoeia=str(len(rows)), onomatopoeia_with_feel=str(counts["feel"]),
        onomatopoeia_with_feel_ja=str(counts["feel_ja"]), onomatopoeia_with_examples=str(counts["examples"]),
    )
    finish_pack(db)
    log(
        f"onomatopoeia: {len(rows)} words, {counts['feel']} with feel, {counts['feel_ja']} with feel_ja, "
        f"{counts['examples']} with examples, {counts['rule']} rule-classified, {counts['rejected']} rejected; "
        f"themes {dict(sorted(by_theme.items()))}"
    )


def cmd_status(_args) -> None:
    entries = load_entries()["entries"]
    log(f"entries.json: {len(entries)} classified, {sum(1 for e in entries if e.get('feel'))} with feel, "
        f"{sum(1 for e in entries if e.get('feel_ja'))} with feel_ja")


def merge_into(data: dict, drafts: list[dict], overwrite: bool, source: str = "llm") -> tuple[int, int]:
    by_id = {e["id"]: e for e in data["entries"]}
    added = filled = 0
    for d in drafts:
        clean = {k: d.get(k, "") for k in ("theme", "type", "feel", "feel_ja")}
        old = by_id.get(d["id"])
        if old is None:
            entry = {"id": d["id"], "text": d.get("text", ""), **clean, "source": source}
            data["entries"].append(entry)
            by_id[d["id"]] = entry
            added += 1
        elif overwrite or (not old.get("feel") and clean["feel"]):
            old.update({k: v for k, v in clean.items() if v or overwrite})
            old["source"] = source
            filled += 1
    return added, filled


def cmd_merge(args) -> None:
    db = open_pack(DICTIONARY_PACK)
    words = {w["id"]: w for w in on_mim_words(db)}
    db.close()
    theme_ids = {t["id"] for t in load_themes()}
    drafts, bad = [], 0
    for f in args.files:
        for d in json.loads(Path(f).read_text(encoding="utf-8")):
            w = words.get(d["id"])
            if w is None:
                log(f"{d['id']}: not an on-mim entry in this dictionary pack; skipped")
                bad += 1
                continue
            if problems := validate(d, w, theme_ids):
                log(f"{d['id']} {w['kana'][0]}: {'; '.join(problems)}")
                bad += 1
                continue
            drafts.append({**d, "text": w["kana"][0]})
    data = load_entries()
    added, filled = merge_into(data, drafts, args.overwrite)
    order = {wid: i for i, wid in enumerate(words)}
    data["entries"].sort(key=lambda e: order.get(e["id"], len(order)))
    save_entries(data)
    log(f"merged: {added} added, {filled} updated, {bad} rejected")


DRAFT_SYSTEM = (
    "You classify Japanese onomatopoeia and mimetic words and describe their feel. Write in your own words. "
    "theme: one of {themes}. type: giongo (imitates a sound, voices included), gitaigo (a state, look, movement or "
    "manner), gijougo (an inner feeling). feel: one ASCII English line, at most 100 characters, evoking the scene or "
    "sensation of the onomatopoeic sense; it must not contain the word or any Japanese or romaji. feel_ja: optional "
    "Japanese line, at most 40 characters, without the word. Reply with JSON only."
)


def cmd_draft(args) -> None:
    if not args.endpoint or not args.model:
        raise SystemExit("draft needs --endpoint and --model")
    db = open_pack(DICTIONARY_PACK)
    words = on_mim_words(db)
    db.close()
    theme_ids = sorted(t["id"] for t in load_themes())
    data = load_entries()
    done = {e["id"] for e in data["entries"] if e.get("feel")}
    todo = [w for w in words if w["id"] not in done][: args.limit]
    system = DRAFT_SYSTEM.format(themes=", ".join(theme_ids))
    for w in todo:
        user = (
            f"Word: {' / '.join(forms(w))}\nOnomatopoeic sense (English glosses): {'; '.join(w['gloss'])}\n"
            'Reply as {"theme": "...", "type": "...", "feel": "...", "feel_ja": "..."}'
        )
        try:
            out = chat_json(args.endpoint, args.model, system, user, args.api_key_env)
        except Exception as e:  # noqa: BLE001 - report and continue with the next word
            log(f"{w['id']}: {e}")
            continue
        draft = {"id": w["id"], **{k: str(out.get(k, "")) for k in ("theme", "type", "feel", "feel_ja")}}
        if problems := validate(draft, w, set(theme_ids)):
            log(f"{w['id']} {w['kana'][0]}: rejected ({'; '.join(problems)})")
            continue
        merge_into(data, [{**draft, "text": w["kana"][0]}], overwrite=False)
        save_entries(data)  # after every word, so an interrupted run keeps its work
        log(f"{w['id']} {w['kana'][0]}: drafted")


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("build").set_defaults(fn=lambda _a: build())
    sub.add_parser("status").set_defaults(fn=cmd_status)
    m = sub.add_parser("merge")
    m.add_argument("files", nargs="+")
    m.add_argument("--overwrite", action="store_true")
    m.set_defaults(fn=cmd_merge)
    d = sub.add_parser("draft")
    add_endpoint_args(d)
    d.set_defaults(fn=cmd_draft)
    args = ap.parse_args(argv)
    if args.cmd is None:
        build()
    else:
        args.fn(args)


if __name__ == "__main__":
    main()
