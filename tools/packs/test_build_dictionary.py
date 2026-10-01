"""Tests for build_dictionary.py and its adapters on small inline fixtures (no network, no downloads).

Run: uv run --group content python packs/test_build_dictionary.py
"""

from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import traceback
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import build_dictionary as bd
import dict_cedict
import dict_jmdict
import dict_kaikki
from common import REPO
from dict_fold import fold

FOLD_VECTORS = REPO / "shared/src/commonTest/resources/dictionary/fold_vectors.json"


def check(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def eq(got, want, what: str = "") -> None:
    if got != want:
        raise AssertionError(f"{what}: got {got!r}, want {want!r}")


# --- fold -------------------------------------------------------------------------------------------------------


def test_fold_vectors() -> None:
    vectors = json.loads(FOLD_VECTORS.read_text(encoding="utf-8"))["vectors"]
    check(len(vectors) >= 30, "fold vectors missing")
    for v in vectors:
        eq(fold(v["input"], v["lang"]), v["expected"], f"{v['lang']} {v['note']} {v['input']!r}")


def test_fold_never_nfkc() -> None:
    eq(fold("〜", "ja"), "〜", "wave dash")
    eq(fold("㍿", "ja"), "㍿", "squared kabushiki")


# --- CC-CEDICT --------------------------------------------------------------------------------------------------

CEDICT = """# CC-CEDICT
#! version=1
中國 中国 [Zhong1 guo2] /China/
說話 说话 [shuo1 hua4] /to speak/to say/to talk/
后 后 [hou4] /empress/queen/
後 后 [hou4] /back/behind/rear/
女 女 [nu:3] /female/woman/
謝謝 谢谢 [xie4 xie5] /to thank/thanks/
綠 绿 [lu:4] /green/
broken line without brackets
"""


def test_pinyin() -> None:
    eq(dict_cedict.pinyin("zhong1 guo2"), "zhōng guó")
    eq(dict_cedict.pinyin("Zhong1 guo2"), "Zhōng guó")
    eq(dict_cedict.pinyin("nu:3"), "nǚ")
    eq(dict_cedict.pinyin("lu:4 e4"), "lǜ è")
    eq(dict_cedict.pinyin("xie4 xie5"), "xiè xie")
    eq(dict_cedict.pinyin("liu4 gui4 zuo4"), "liù guì zuò")
    eq(dict_cedict.pinyin("hao3 r5"), "hǎo r")
    eq(dict_cedict.pinyin("m2"), "m")
    eq(dict_cedict.pinyin("san1 D da3"), "sān D dǎ")


def test_cedict_read() -> None:
    lemmas = {(lm.headword, lm.reading): lm for lm in dict_cedict.read(CEDICT.splitlines(keepends=True))}
    eq(len(lemmas), 6, "entries (后 hou4 merged)")
    hou = lemmas[("后", "hòu")]
    eq([s[0] for s in hou.senses], ["empress", "queen", "back", "behind", "rear"], "merged senses")
    eq(hou.forms, {"後": "traditional"}, "traditional form of the merged line")
    china = lemmas[("中国", "Zhōng guó")]
    eq(china.forms, {"中國": "traditional"})
    check(china.weight < lemmas[("说话", "shuō huà")].weight, "proper nouns weigh less")
    eq(lemmas[("女", "nǚ")].forms, {}, "no form when traditional == simplified")


# --- JMdict -----------------------------------------------------------------------------------------------------


def jm_word(kanji, kana, senses):
    return {
        "id": "1",
        "kanji": [{"common": c, "text": t, "tags": tags} for t, c, tags in kanji],
        "kana": [{"common": c, "text": t, "tags": tags, "appliesToKanji": ak} for t, c, tags, ak in kana],
        "sense": [
            {"partOfSpeech": pos, "misc": misc, "field": field, "dialect": [], "appliesToKanji": ["*"],
             "appliesToKana": ["*"], "gloss": [{"lang": "eng", "text": g} for g in glosses]}
            for pos, misc, field, glosses in senses
        ],
    }


def test_jmdict_lemma() -> None:
    w = jm_word(
        [("食べる", True, []), ("喰べる", False, ["iK"])],
        [("たべる", True, [], ["*"])],
        [(["v1", "vt"], [], [], ["to eat"]), ([], ["col"], ["food"], ["to live on"])],
    )
    lm = dict_jmdict.lemma(w)
    eq((lm.headword, lm.reading, lm.pos), ("食べる", "たべる", "v1,vt"))
    eq(lm.senses, [("to eat", None, None), ("to live on", "food", "col")])
    eq(lm.forms, {"喰べる": "kanji,iK"})
    eq(lm.weight, 1.0, "common")

    kana_only = dict_jmdict.lemma(jm_word([], [("テレビ", True, [], ["*"])], [(["n"], [], [], ["television", "TV"])]))
    eq((kana_only.headword, kana_only.reading), ("テレビ", None))
    eq(kana_only.senses[0][0], "television; TV")

    # Reading must apply to the first kanji form; other readings become kana forms.
    w = jm_word([("今日", True, [])], [("きょう", True, [], ["*"]), ("こんにち", True, [], ["今日"])],
                [(["n"], ["uk"], [], ["today"])])
    lm = dict_jmdict.lemma(w)
    eq(lm.reading, "きょう")
    eq(lm.forms, {"こんにち": "kana"})
    eq(lm.freq_words, ["今日", "きょう"], "usually-kana words also rank by their reading")


# --- kaikki / Wiktionary ----------------------------------------------------------------------------------------

ES_HABLAR = {
    "word": "hablar", "pos": "verb", "lang": "Spanish",
    "forms": [
        {"form": "no-table-tags", "tags": ["table-tags"], "source": "conjugation"},
        {"form": "es-conj", "tags": ["inflection-template"], "source": "conjugation"},
        {"form": "hablamos", "tags": ["first-person", "plural", "present", "indicative"], "source": "conjugation"},
        {"form": "habló", "tags": ["third-person", "singular", "preterite"], "source": "conjugation"},
        {"form": "hablar", "tags": ["infinitive"], "source": "conjugation"},
        {"form": "he hablado", "tags": ["perfect"], "source": "conjugation"},
    ],
    "senses": [
        {"glosses": ["to speak"], "tags": ["intransitive"],
         "examples": [{"text": "Hablo español.", "english": "I speak Spanish.", "type": "example"}]},
        {"glosses": ["to talk"], "tags": ["colloquial", "Mexico"], "topics": ["communication"]},
    ],
}
ES_HABLAN = {
    "word": "hablan", "pos": "verb",
    "senses": [{"glosses": ["inflection of hablar:", "third-person plural present indicative"],
                "tags": ["form-of", "third-person", "plural"], "form_of": [{"word": "hablar"}]}],
}
ES_COMO = {
    "word": "como", "pos": "verb",
    "senses": [{"glosses": ["first-person singular present indicative of comer"], "tags": ["form-of"],
                "form_of": [{"word": "comer"}]},
               {"glosses": ["(nonstandard) as"], "tags": []}],
}


def test_kaikki_lemma_and_forms() -> None:
    lm = dict_kaikki.lemma(ES_HABLAR, "es")
    eq((lm.headword, lm.reading, lm.pos), ("hablar", None, "verb"))
    eq(lm.senses, [("to speak", None, None), ("to talk", "communication", "colloquial,Mexico")])
    eq(lm.forms, {"hablamos": "first-person,plural,present,indicative", "habló": "third-person,singular,preterite"},
       "table metadata, the headword itself and periphrases are skipped")
    eq(lm.examples, [("Hablo español.", "I speak Spanish.", "wiktionary")])
    check(dict_kaikki.lemma(ES_HABLAN, "es") is None, "pure form-of entries are not lemmas")
    eq(dict_kaikki.candidate(ES_HABLAN, "es"), None)
    eq(dict_kaikki.candidate(ES_COMO, "es"), ("como", 0.1), "mixed entries are lemmas")
    sub = dict_kaikki.lemma({"word": "x", "pos": "noun", "senses": [{"glosses": ["inflection of y:", "plural"]}]}, "es")
    eq(sub.senses[0][0], "inflection of y: plural", "sub-gloss joined to an ending-colon parent")


def test_kaikki_links() -> None:
    links = dict_kaikki.links(ES_HABLAN, "es")
    eq([(lk.surface, lk.target, lk.pos, lk.tags) for lk in links],
       [("hablan", "hablar", "verb", "third-person,plural")])
    eq([lk.target for lk in dict_kaikki.links(ES_COMO, "es")], ["comer"])
    alt = {"word": "fato", "pos": "noun", "senses": [{"glosses": ["Brazilian spelling of facto"],
                                                      "tags": ["alt-of", "Brazil"], "alt_of": [{"word": "facto"}]}]}
    eq(dict_kaikki.links(alt, "pt-BR")[0].tags, "alternative,Brazil")


def test_kaikki_russian() -> None:
    d = {
        "word": "говорить", "pos": "verb",
        "forms": [
            {"form": "говори́ть", "tags": ["canonical", "imperfective"]},
            {"form": "govorítʹ", "tags": ["romanization"]},
            {"form": "сказа́ть", "tags": ["perfective"]},
            {"form": "говорю́", "tags": ["first-person", "singular", "present"], "source": "conjugation"},
            {"form": "бу́ду говори́ть", "tags": ["future"], "source": "conjugation"},
        ],
        "senses": [{"glosses": ["to speak, to talk"]}],
    }
    lm = dict_kaikki.lemma(d, "ru")
    eq(lm.reading, "говори́ть", "stress-marked canonical form is the reading")
    eq(lm.forms, {"говорю": "first-person,singular,present"}, "stress stripped; aspect partner skipped")
    link = dict_kaikki.links({"word": "говорю", "pos": "verb", "senses": [
        {"glosses": ["first-person singular of говори́ть"], "tags": ["form-of"], "form_of": [{"word": "говори́ть"}]}]},
        "ru")
    eq(link[0].target, "говорить", "form-of target without stress")
    eq(dict_kaikki.lemma({"word": "ёлка", "pos": "noun", "senses": [{"glosses": ["fir"]}]}, "ru").headword, "ёлка",
       "ё kept in the stored headword (only the fold key folds it)")


def test_kaikki_arabic_persian_korean() -> None:
    ar = dict_kaikki.lemma({
        "word": "كتاب", "pos": "noun",
        "forms": [{"form": "كِتَاب", "tags": ["canonical"]}, {"form": "kitāb", "tags": ["romanization"]},
                  {"form": "كُتُب", "tags": ["plural"], "roman": "kutub"},
                  {"form": "الْكِتَاب", "tags": ["definite"], "source": "declension"}],
        "senses": [{"glosses": ["book"]}],
    }, "ar")
    eq(ar.reading, "kitāb")
    eq(ar.forms, {"كتب": "plural", "الكتاب": "definite"}, "tashkeel stripped from surfaces")

    fa = dict_kaikki.lemma({
        "word": "کتاب", "pos": "noun",
        "forms": [{"form": "kitāb", "tags": ["romanization"]}, {"form": "ketâb", "tags": ["romanization"]},
                  {"form": "китоб", "tags": ["Cyrillic", "Tajik"]},
                  {"form": "کتابهایم، کتابهام (ketâb-hấyam, ketâbấm^△)", "tags": ["colloquial", "possessive"],
                   "source": "declension"}],
        "senses": [{"glosses": ["book"]}],
    }, "fa")
    eq(fa.reading, "ketâb", "Iranian romanization")
    eq(set(fa.forms), {"کتابهایم", "کتابهام"}, "split lists, drop romanized notes and Tajik Cyrillic")

    ko = dict_kaikki.lemma({
        "word": "먹다", "pos": "verb",
        "forms": [{"form": "meokda", "tags": ["romanization"]},
                  {"form": "먹었다", "tags": ["formal", "past"], "source": "conjugation", "roman": "meogeotda"}],
        "senses": [{"glosses": ["to eat"]}],
    }, "ko")
    eq((ko.reading, ko.forms), ("meokda", {"먹었다": "formal,past"}))


def test_kaikki_portuguese_prefers_brazil() -> None:
    d = {
        "word": "facto", "pos": "noun",
        "forms": [{"form": "factos", "tags": ["plural"]}, {"form": "fato", "tags": ["alternative", "Brazil"]}],
        "senses": [{"glosses": ["suit"], "tags": ["Portugal"]}, {"glosses": ["fact"], "tags": ["masculine"]}],
    }
    lm = dict_kaikki.lemma(d, "pt-BR")
    eq(lm.headword, "fato")
    eq(lm.aliases, ["facto"])
    eq(lm.forms, {"facto": "Portugal", "factos": "plural"})
    eq([s[0] for s in lm.senses], ["fact", "suit"], "Portugal-only senses last")
    eq(dict_kaikki.candidate(d, "pt-BR")[0], "fato", "pass 1 ranks the Brazilian headword")


# --- Ranking and pack writing -----------------------------------------------------------------------------------

FAKE_ZIPF = {"hablar": 5.4, "comer": 5.3, "como": 6.5, "hablador": 3.0}


def fake_zipf(word: str) -> float:
    return FAKE_ZIPF.get(word, 0.0)


def test_rank_order() -> None:
    zipf = {"a": 4.0, "b": 5.0, "c": 4.5, "A": 4.0}.get
    order = bd.rank_order([(["b"], 0.0, 0.0), (["a"], 0.5, 0.0), (["a"], 0.9, 0.0), (["zz"], 0.0, 0.0)],
                          lambda w: zipf(w, 0.0))
    eq([i for i, _ in order], [0, 2, 1, 3], "frequency, then weight, then input order")
    order = bd.rank_order([(["A"], 0.9, 0.0), (["a"], 0.0, 0.0), (["c"], 0.0, 1.5)], lambda w: zipf(w, 0.0))
    eq([i for i, _ in order], [1, 0, 2], "case variant after its lower-case twin; penalty lowers rank")
    eq(order[2][1], 4.5, "the reported Zipf is not penalized")


def test_zipf_guards() -> None:
    zipf = bd.zipf_function("de")
    check(zipf("und") > 5, "wordfreq available")
    eq(zipf("und,"), 0.0, "punctuation")
    eq(zipf("-chen"), 0.0, "affix")
    check(zipf("Haus") > 4, "case folded by wordfreq")


def test_kaikki_two_pass_and_pack() -> None:
    comer = {"word": "comer", "pos": "verb", "senses": [{"glosses": ["to eat"]}],
             "forms": [{"form": "comemos", "tags": ["first-person", "plural"], "source": "conjugation"}]}
    hablador = {"word": "hablador", "pos": "adj", "senses": [{"glosses": ["talkative"]}]}
    rare = {"word": "hablantín", "pos": "adj", "senses": [{"glosses": ["chatterbox"]}]}
    lines = [ES_HABLAR, ES_HABLAN, ES_COMO, comer, hablador, rare]
    ranked, links = bd.kaikki_lemmas(lambda: enumerate(lines), "es", fake_zipf, max_entries=4)
    eq([(lm.headword, r) for lm, r in ranked], [("como", 1), ("hablar", 2), ("comer", 3), ("hablador", 4)],
       "capped at 4, ranked by frequency")
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "es" / "dictionary.sqlite"
        counts = bd.write_pack(path, "es", ranked, links, {"language": "es", "license": "CC BY-SA 3.0"})
        eq(counts["entries"], 4)
        db = sqlite3.connect(path)
        eq(db.execute("SELECT headword, frequencyRank FROM entry ORDER BY id").fetchall(),
           [("como", 1), ("hablar", 2), ("comer", 3), ("hablador", 4)])
        forms = db.execute("SELECT f.surface, e.headword FROM form f JOIN entry e ON e.id = f.entryId "
                           "ORDER BY f.surface").fetchall()
        eq(forms, [("comemos", "comer"), ("como", "comer"), ("hablamos", "hablar"), ("hablan", "hablar"),
                   ("habló", "hablar")], "forms[] and resolved form-of links")
        eq(db.execute("SELECT kind FROM fold WHERE key = 'hablo'").fetchall(), [(1,)], "folded form key")
        eq(db.execute("SELECT count(*) FROM fold WHERE key = 'como'").fetchone(), (0,),
           "keys equal to their text are left to the entry/form indexes")
        meta = dict(db.execute("SELECT key, value FROM meta").fetchall())
        eq((meta["license"], meta["entry_count"], meta["form_count"]), ("CC BY-SA 3.0", "4", "5"))
        eq(db.execute("PRAGMA user_version").fetchone()[0], 1)
        db.close()


def test_select_cap_and_unranked() -> None:
    from dict_model import Lemma

    lemmas = [Lemma("x", None, "", [("g", None, None)]), Lemma("hablar", None, "", [("g", None, None)])]
    sel = bd.select(lemmas, fake_zipf, None)
    eq([(lm.headword, r) for lm, r in sel], [("hablar", 1), ("x", None)])
    eq(len(bd.select(lemmas, fake_zipf, 1)), 1)


def test_schema_matches_sq() -> None:
    stmts = bd.schema_statements(bd.DICTIONARY_SQ)
    names = [s.split()[2] for s in stmts]
    for table in ("meta", "entry", "sense", "form", "example", "fold"):
        check(table in names, f"{table} missing from dictionary.sq")


def main() -> int:
    tests = [(n, f) for n, f in globals().items() if n.startswith("test_") and callable(f)]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print(f"ok   {name}")
        except Exception:  # noqa: BLE001
            failed += 1
            print(f"FAIL {name}")
            traceback.print_exc()
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
