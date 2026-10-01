"""ja adapter: JMdict (EDRDG, CC BY-SA 4.0) via scriptin/jmdict-simplified → language-neutral Lemma records.

headword = first kanji form (else first kana form); reading = first kana form that applies to it (NULL for kana-only
words). Other kanji and kana spellings become `form` rows; inflection is left to the app's lang/ja deinflector.
"""

from __future__ import annotations

from collections.abc import Iterable

from dict_model import MAX_SENSES, Lemma, clip, nfc

# Zipf points subtracted from entries JMdict doesn't mark common (news/ichi/spec/gai priority lists).
NOT_COMMON_PENALTY = 1.5


def lemma(word: dict) -> Lemma | None:
    kanji = [k for k in word["kanji"]]
    kana = [k for k in word["kana"]]
    if not kana:
        return None
    if kanji:
        head = nfc(kanji[0]["text"])
        reading = next(
            (nfc(k["text"]) for k in kana if "*" in k["appliesToKanji"] or kanji[0]["text"] in k["appliesToKanji"]),
            nfc(kana[0]["text"]),
        )
    else:
        head, reading = nfc(kana[0]["text"]), None

    senses = []
    usually_kana = False
    for s in word["sense"]:
        glosses = [g["text"] for g in s["gloss"] if g["lang"] == "eng"]
        if not glosses:
            continue
        usually_kana |= "uk" in s["misc"]
        domain = ",".join(s["field"]) or None
        register = ",".join(s["misc"] + s["dialect"]) or None
        senses.append((clip("; ".join(glosses)), domain, register))
    if not senses:
        return None
    first_pos = next((s["partOfSpeech"] for s in word["sense"] if s["partOfSpeech"]), [])

    common = any(k["common"] for k in kanji) or any(k["common"] for k in kana)
    common_reading = any(k["common"] and nfc(k["text"]) == reading for k in kana)
    out = Lemma(
        headword=head,
        reading=reading,
        pos=",".join(first_pos),
        senses=senses[:MAX_SENSES],
        # Usually-kana words are written with their reading, so its frequency counts, but only for a common reading
        # (otherwise every archaic homograph of ない or と would rank with the particle).
        freq_words=[head] + ([reading] if usually_kana and common_reading and reading else []),
        weight=1.0 if common else 0.0,
        # wordfreq scores morphemes, so rare compounds and homographs of common words need the JMdict common flag.
        penalty=0.0 if common else NOT_COMMON_PENALTY,
    )
    for k in kanji[1:]:
        out.add_form(k["text"], ["kanji", *k["tags"]])
    for k in kana:
        if nfc(k["text"]) != reading:
            out.add_form(k["text"], ["kana", *k["tags"]])
    return out


def read(data: dict) -> Iterable[Lemma]:
    """Lemmas from a parsed jmdict-eng JSON document (jmdict-simplified 3.x)."""
    for word in data["words"]:
        entry = lemma(word)
        if entry is not None:
            yield entry
