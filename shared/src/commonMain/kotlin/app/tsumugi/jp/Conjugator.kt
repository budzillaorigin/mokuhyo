package app.tsumugi.jp

/** Rows of a conjugation table. Not every word class has every row. */
enum class Conjugation {
    NON_PAST,
    NON_PAST_NEGATIVE,
    POLITE,
    POLITE_NEGATIVE,
    PAST,
    PAST_NEGATIVE,
    POLITE_PAST,
    POLITE_PAST_NEGATIVE,
    TE,
    TE_NEGATIVE,
    PROGRESSIVE,
    POTENTIAL,
    PASSIVE,
    CAUSATIVE,
    CAUSATIVE_PASSIVE,
    CAUSATIVE_PASSIVE_SHORT,
    VOLITIONAL,
    POLITE_VOLITIONAL,
    IMPERATIVE,
    PROHIBITIVE,
    CONDITIONAL_BA,
    CONDITIONAL_TARA,
    DESIRE_TAI,
    ADVERBIAL,
    ATTRIBUTIVE,
    NOMINAL_SA,
}

/** Generates conjugation tables for verbs and adjectives. Inverse of [Deinflector]; round-trip tested. */
object Conjugator {

    private val godanRows: Map<Char, String> = mapOf(
        // dictionary ending -> a i u e o
        'う' to "わいうえお",
        'く' to "かきくけこ",
        'ぐ' to "がぎぐげご",
        'す' to "さしすせそ",
        'つ' to "たちつてと",
        'ぬ' to "なにぬねの",
        'ぶ' to "ばびぶべぼ",
        'む' to "まみむめも",
        'る' to "らりるれろ",
    )

    private val honorificGodan = listOf("くださる", "下さる", "なさる", "いらっしゃる", "おっしゃる", "仰る", "ござる")
    private val tou = listOf("問う", "請う", "乞う", "恋う")
    private val aru = setOf("ある", "有る", "在る")

    /**
     * Conjugation table for [dictionaryForm] (e.g. 書く, 食べる, 来る, 勉強する, 高い, 綺麗).
     * [godanEnding] overrides the ending used to pick the godan row (defaults to the last character).
     * Returns null if the form doesn't fit the class (e.g. an ICHIDAN word not ending in る).
     */
    fun conjugate(dictionaryForm: String, wordClass: WordClass, godanEnding: Char? = null): Map<Conjugation, String>? =
        conjugate(dictionaryForm, wordClass, godanEnding, pos = null)

    /** Table for a JMdict entry given its part-of-speech codes; null for non-inflecting words. */
    fun table(dictionaryForm: String, jmdictPos: List<String>): List<Pair<Conjugation, String>>? {
        val pos = jmdictPos.firstOrNull { WordClass.fromJmdictPos(it) != null } ?: return null
        val wordClass = WordClass.fromJmdictPos(pos)!!
        val form = if (wordClass == WordClass.SURU && !dictionaryForm.endsWith("する")) "${dictionaryForm}する" else dictionaryForm
        return conjugate(form, wordClass, null, pos)?.toList()
    }

    private fun conjugate(form: String, wordClass: WordClass, godanEnding: Char?, pos: String?): Map<Conjugation, String>? =
        when (wordClass) {
            WordClass.GODAN -> godan(form, godanEnding ?: form.lastOrNull() ?: return null, pos)
            WordClass.ICHIDAN -> if (form.endsWith("る")) ichidan(form.dropLast(1), form) else null
            WordClass.KURU -> kuru(form)
            WordClass.SURU -> if (form.endsWith("する")) suru(form.dropLast(2)) else null
            WordClass.ADJ_I -> if (form.endsWith("い")) adjI(form, pos) else null
            WordClass.ADJ_NA -> adjNa(form)
        }

    private fun godan(form: String, ending: Char, pos: String?): Map<Conjugation, String>? {
        val row = godanRows[ending] ?: return null
        val base = form.dropLast(1)
        val (a, i, _, e, o) = row.toList()
        val isIku = pos == "v5k-s" || form == "いく" || form.endsWith("行く") || form.endsWith("ていく") || form.endsWith("でいく")
        val isTou = pos == "v5u-s" || tou.any { form.endsWith(it) }
        val isAru = pos == "v5r-i" || form in aru
        val isHonorific = pos == "v5aru" || honorificGodan.any { form.endsWith(it) }

        val te = when {
            isIku -> "${base}って"
            isTou -> "${base}うて"
            else -> when (ending) {
                'く' -> "${base}いて"
                'ぐ' -> "${base}いで"
                'す' -> "${base}して"
                'う', 'つ', 'る' -> "${base}って"
                else -> "${base}んで" // ぬ ぶ む
            }
        }
        val ta = te.dropLast(1) + (if (te.endsWith("で")) "だ" else "た")
        val stem = if (isHonorific) "${base}い" else "$base$i"
        val neg = if (isAru) "ない" else "$base${a}ない"

        return buildMap {
            put(Conjugation.NON_PAST, form)
            put(Conjugation.NON_PAST_NEGATIVE, neg)
            putPolite(stem)
            put(Conjugation.PAST, ta)
            put(Conjugation.PAST_NEGATIVE, neg.dropLast(1) + "かった")
            put(Conjugation.TE, te)
            if (!isAru) put(Conjugation.TE_NEGATIVE, "${neg}で")
            put(Conjugation.PROGRESSIVE, "${te}いる")
            if (!isAru) {
                put(Conjugation.POTENTIAL, "$base${e}る")
                put(Conjugation.PASSIVE, "$base${a}れる")
                put(Conjugation.CAUSATIVE, "$base${a}せる")
                put(Conjugation.CAUSATIVE_PASSIVE, "$base${a}せられる")
                if (ending != 'す') put(Conjugation.CAUSATIVE_PASSIVE_SHORT, "$base${a}される")
                put(Conjugation.DESIRE_TAI, "$base${i}たい")
            }
            put(Conjugation.VOLITIONAL, "$base${o}う")
            put(Conjugation.IMPERATIVE, if (isHonorific) "${base}い" else "$base$e")
            put(Conjugation.PROHIBITIVE, "${form}な")
            put(Conjugation.CONDITIONAL_BA, "$base${e}ば")
            put(Conjugation.CONDITIONAL_TARA, "${ta}ら")
        }
    }

    private fun MutableMap<Conjugation, String>.putPolite(stem: String) {
        put(Conjugation.POLITE, "${stem}ます")
        put(Conjugation.POLITE_NEGATIVE, "${stem}ません")
        put(Conjugation.POLITE_PAST, "${stem}ました")
        put(Conjugation.POLITE_PAST_NEGATIVE, "${stem}ませんでした")
        put(Conjugation.POLITE_VOLITIONAL, "${stem}ましょう")
    }

    private fun ichidan(stem: String, form: String): Map<Conjugation, String> = buildMap {
        put(Conjugation.NON_PAST, form)
        put(Conjugation.NON_PAST_NEGATIVE, "${stem}ない")
        putPolite(stem)
        put(Conjugation.PAST, "${stem}た")
        put(Conjugation.PAST_NEGATIVE, "${stem}なかった")
        put(Conjugation.TE, "${stem}て")
        put(Conjugation.TE_NEGATIVE, "${stem}ないで")
        put(Conjugation.PROGRESSIVE, "${stem}ている")
        put(Conjugation.POTENTIAL, "${stem}られる")
        put(Conjugation.PASSIVE, "${stem}られる")
        put(Conjugation.CAUSATIVE, "${stem}させる")
        put(Conjugation.CAUSATIVE_PASSIVE, "${stem}させられる")
        put(Conjugation.VOLITIONAL, "${stem}よう")
        put(Conjugation.IMPERATIVE, if (form.endsWith("くれる")) stem else "${stem}ろ")
        put(Conjugation.PROHIBITIVE, "${form}な")
        put(Conjugation.CONDITIONAL_BA, "${stem}れば")
        put(Conjugation.CONDITIONAL_TARA, "${stem}たら")
        put(Conjugation.DESIRE_TAI, "${stem}たい")
    }

    /** 来る / くる, optionally with a prefix (やって来る). Kanji 来 is kept; kana forms change vowel. */
    private fun kuru(form: String): Map<Conjugation, String>? {
        val kanji = form.endsWith("来る")
        if (!kanji && !form.endsWith("くる")) return null
        val prefix = form.dropLast(2)
        val ko = prefix + if (kanji) "来" else "こ"
        val ki = prefix + if (kanji) "来" else "き"
        val ku = prefix + if (kanji) "来" else "く"
        return buildMap {
            put(Conjugation.NON_PAST, form)
            put(Conjugation.NON_PAST_NEGATIVE, "${ko}ない")
            putPolite(ki)
            put(Conjugation.PAST, "${ki}た")
            put(Conjugation.PAST_NEGATIVE, "${ko}なかった")
            put(Conjugation.TE, "${ki}て")
            put(Conjugation.TE_NEGATIVE, "${ko}ないで")
            put(Conjugation.PROGRESSIVE, "${ki}ている")
            put(Conjugation.POTENTIAL, "${ko}られる")
            put(Conjugation.PASSIVE, "${ko}られる")
            put(Conjugation.CAUSATIVE, "${ko}させる")
            put(Conjugation.CAUSATIVE_PASSIVE, "${ko}させられる")
            put(Conjugation.VOLITIONAL, "${ko}よう")
            put(Conjugation.IMPERATIVE, "${ko}い")
            put(Conjugation.PROHIBITIVE, "${form}な")
            put(Conjugation.CONDITIONAL_BA, "${ku}れば")
            put(Conjugation.CONDITIONAL_TARA, "${ki}たら")
            put(Conjugation.DESIRE_TAI, "${ki}たい")
        }
    }

    /** する and noun+する compounds; [prefix] is the noun ("" for bare する). */
    private fun suru(prefix: String): Map<Conjugation, String> = buildMap {
        put(Conjugation.NON_PAST, "${prefix}する")
        put(Conjugation.NON_PAST_NEGATIVE, "${prefix}しない")
        putPolite("${prefix}し")
        put(Conjugation.PAST, "${prefix}した")
        put(Conjugation.PAST_NEGATIVE, "${prefix}しなかった")
        put(Conjugation.TE, "${prefix}して")
        put(Conjugation.TE_NEGATIVE, "${prefix}しないで")
        put(Conjugation.PROGRESSIVE, "${prefix}している")
        put(Conjugation.POTENTIAL, "${prefix}できる")
        put(Conjugation.PASSIVE, "${prefix}される")
        put(Conjugation.CAUSATIVE, "${prefix}させる")
        put(Conjugation.CAUSATIVE_PASSIVE, "${prefix}させられる")
        put(Conjugation.VOLITIONAL, "${prefix}しよう")
        put(Conjugation.IMPERATIVE, "${prefix}しろ")
        put(Conjugation.PROHIBITIVE, "${prefix}するな")
        put(Conjugation.CONDITIONAL_BA, "${prefix}すれば")
        put(Conjugation.CONDITIONAL_TARA, "${prefix}したら")
        put(Conjugation.DESIRE_TAI, "${prefix}したい")
    }

    /** い-adjectives. いい (adj-ix) inflects on よ: よくない, よかった. */
    private fun adjI(form: String, pos: String?): Map<Conjugation, String> {
        val isIi = pos == "adj-ix" || form == "いい" || form.endsWith("っこいい")
        val stem = if (isIi && form.endsWith("いい")) form.dropLast(2) + "よ" else form.dropLast(1)
        return buildMap {
            put(Conjugation.NON_PAST, form)
            put(Conjugation.NON_PAST_NEGATIVE, "${stem}くない")
            put(Conjugation.POLITE, "${form}です")
            put(Conjugation.POLITE_NEGATIVE, "${stem}くないです")
            put(Conjugation.PAST, "${stem}かった")
            put(Conjugation.PAST_NEGATIVE, "${stem}くなかった")
            put(Conjugation.POLITE_PAST, "${stem}かったです")
            put(Conjugation.POLITE_PAST_NEGATIVE, "${stem}くなかったです")
            put(Conjugation.TE, "${stem}くて")
            put(Conjugation.ADVERBIAL, "${stem}く")
            put(Conjugation.CONDITIONAL_BA, "${stem}ければ")
            put(Conjugation.CONDITIONAL_TARA, "${stem}かったら")
            put(Conjugation.NOMINAL_SA, "${stem}さ")
        }
    }

    private fun adjNa(form: String): Map<Conjugation, String> = buildMap {
        put(Conjugation.NON_PAST, "${form}だ")
        put(Conjugation.NON_PAST_NEGATIVE, "${form}じゃない")
        put(Conjugation.POLITE, "${form}です")
        put(Conjugation.POLITE_NEGATIVE, "${form}じゃありません")
        put(Conjugation.PAST, "${form}だった")
        put(Conjugation.PAST_NEGATIVE, "${form}じゃなかった")
        put(Conjugation.POLITE_PAST, "${form}でした")
        put(Conjugation.POLITE_PAST_NEGATIVE, "${form}じゃありませんでした")
        put(Conjugation.TE, "${form}で")
        put(Conjugation.ADVERBIAL, "${form}に")
        put(Conjugation.ATTRIBUTIVE, "${form}な")
        put(Conjugation.CONDITIONAL_BA, "${form}なら")
        put(Conjugation.CONDITIONAL_TARA, "${form}だったら")
    }
}
