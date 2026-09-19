package app.tsumugi.tracks

import app.tsumugi.jp.Conjugation
import app.tsumugi.jp.Conjugator
import app.tsumugi.jp.WordClass

/** Which keigo a transformation asks for. */
enum class KeigoTarget(val code: String, val labelJa: String) {
    /** Honorific: raises the other person's actions (おっしゃる, お待ちになる). */
    SONKEIGO("sonkeigo", "尊敬語"),

    /** Humble: lowers one's own actions (申す, お待ちする). */
    KENJOGO("kenjogo", "謙譲語"),

    /** Polite: です/ます, ござる. */
    TEINEIGO("teineigo", "丁寧語"),
    ;

    companion object {
        fun of(code: String): KeigoTarget? = entries.firstOrNull { it.code == code }
    }
}

/** The inflection the blank needs. */
enum class KeigoForm(val code: String, val conjugation: Conjugation) {
    DICTIONARY("dictionary", Conjugation.NON_PAST),
    MASU("masu", Conjugation.POLITE),
    PAST("past", Conjugation.PAST),
    MASU_PAST("masu-past", Conjugation.POLITE_PAST),
    TE("te", Conjugation.TE),
    ;

    companion object {
        fun of(code: String): KeigoForm? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Rule-based keigo conjugation (BRIEF_V2 §6.5 keigo drill): the special verbs (言う → おっしゃる / 申す …), the regular
 * patterns (お＋連用形＋になる, お＋連用形＋する/いたす, ご＋漢語＋になる/する/いたす, 〜れる/られる, 〜なさる) and です/ます,
 * conjugated to the form a blank needs with [Conjugator]. A drill accepts its authored answers plus these forms, so
 * a correct answer the author didn't list still counts.
 */
object KeigoRules {

    private data class Equivalent(val form: String, val wordClass: WordClass)

    private fun godan(vararg forms: String) = forms.map { Equivalent(it, WordClass.GODAN) }
    private fun ichidan(vararg forms: String) = forms.map { Equivalent(it, WordClass.ICHIDAN) }
    private fun suru(vararg forms: String) = forms.map { Equivalent(it, WordClass.SURU) }
    private fun na(vararg forms: String) = forms.map { Equivalent(it, WordClass.ADJ_NA) }

    private fun table(vararg rows: Pair<List<String>, List<Equivalent>>): Map<String, List<Equivalent>> = buildMap {
        for ((keys, values) in rows) for (k in keys) put(k, (get(k).orEmpty() + values).distinct())
    }

    private val SONKEIGO: Map<String, List<Equivalent>> = table(
        listOf("行く", "いく", "来る", "くる", "いる", "居る") to godan("いらっしゃる", "おいでになる"),
        listOf("行く", "いく", "来る", "くる") to godan("お越しになる", "おこしになる"),
        listOf("来る", "くる") to godan("お見えになる", "おみえになる"),
        listOf("言う", "いう") to godan("おっしゃる", "仰る"),
        listOf("食べる", "たべる", "飲む", "のむ") to godan("召し上がる", "めしあがる"),
        listOf("見る", "みる") to godan("ご覧になる", "ごらんになる"),
        listOf("する") to godan("なさる"),
        listOf("知る", "しる", "知っている", "しっている") to na("ご存じ", "ご存知"),
        listOf("くれる", "呉れる") to godan("くださる", "下さる"),
        listOf("寝る", "ねる") to godan("お休みになる", "おやすみになる"),
        listOf("着る", "きる") to godan("お召しになる", "おめしになる"),
        listOf("死ぬ", "しぬ") to godan("お亡くなりになる", "おなくなりになる"),
        listOf("気に入る", "きにいる") to godan("お気に召す", "おきにめす"),
    )

    private val KENJOGO: Map<String, List<Equivalent>> = table(
        listOf("行く", "いく", "来る", "くる") to godan("参る", "まいる"),
        listOf("行く", "いく", "訪ねる", "たずねる", "訪問する", "聞く", "きく", "尋ねる") to godan("伺う", "うかがう"),
        listOf("いる", "居る") to godan("おる", "居る"),
        listOf("言う", "いう") to godan("申す", "もうす") + ichidan("申し上げる", "もうしあげる"),
        listOf("食べる", "たべる", "飲む", "のむ", "もらう", "貰う") to godan("いただく", "頂く"),
        listOf("見る", "みる") to suru("拝見する", "はいけんする"),
        listOf("する") to godan("いたす", "致す"),
        listOf("知る", "しる", "知っている", "しっている", "思う", "おもう") to ichidan("存じる", "ぞんじる"),
        listOf("知る", "しる", "知っている", "しっている") to ichidan("存じ上げる", "ぞんじあげる") + godan("存じておる", "ぞんじておる"),
        listOf("受ける", "うける", "引き受ける", "聞く", "きく") to godan("承る", "うけたまわる"),
        listOf("会う", "あう") to godan("お目にかかる", "おめにかかる"),
        listOf("聞く", "きく") to suru("拝聴する", "はいちょうする"),
        listOf("あげる", "上げる", "やる", "与える") to ichidan("差し上げる", "さしあげる"),
        listOf("見せる", "みせる") to ichidan("お目にかける", "おめにかける", "ご覧に入れる", "ごらんにいれる"),
        listOf("借りる", "かりる") to suru("拝借する", "はいしゃくする"),
        listOf("読む", "よむ") to suru("拝読する", "はいどくする"),
        listOf("わかる", "分かる") to suru("承知する", "しょうちする") + godan("かしこまる", "畏まる"),
    )

    private val TEINEIGO: Map<String, List<Equivalent>> = table(
        listOf("ある", "有る", "在る") to godan("ござる"),
    )

    /** Verbs whose regular お〜になる / お〜する / 〜れる forms are wrong or unidiomatic: only the special forms count. */
    private val NO_REGULAR = setOf(
        "行く", "いく", "来る", "くる", "いる", "居る", "する", "言う", "いう", "見る", "みる", "寝る", "ねる", "着る", "きる",
        "食べる", "たべる", "知る", "しる", "くれる", "呉れる", "やる", "ある", "有る", "在る", "死ぬ", "しぬ", "もらう", "貰う",
    )

    /** Sino-Japanese nouns that take お rather than ご (both for 返事). */
    private val O_NOUNS = setOf("電話", "でんわ", "返事", "へんじ", "世話", "せわ", "食事", "しょくじ", "掃除", "そうじ", "料理", "りょうり")
    private val BOTH_PREFIXES = setOf("返事", "へんじ")

    /**
     * Every form of [plain] in [target] keigo, inflected as [form]. [plain] is a dictionary-form verb (漢語＋する
     * included); [reading] adds the kana spellings; [wordClass] is its inflection class (from JMdict) and enables the
     * regular patterns. Unknown verbs without a class get only the special-verb forms.
     */
    fun forms(plain: String, reading: String?, wordClass: WordClass?, target: KeigoTarget, form: KeigoForm): Set<String> {
        val out = LinkedHashSet<String>()
        for (base in listOfNotNull(plain, reading).distinct()) {
            for (eq in equivalents(base, wordClass, target)) {
                Conjugator.conjugate(eq.form, eq.wordClass)?.get(form.conjugation)?.let { out += it }
            }
        }
        return out
    }

    /** Dictionary-form keigo equivalents (special table first, then the regular patterns). */
    private fun equivalents(plain: String, wordClass: WordClass?, target: KeigoTarget): List<Equivalent> {
        val special = when (target) {
            KeigoTarget.SONKEIGO -> SONKEIGO
            KeigoTarget.KENJOGO -> KENJOGO
            KeigoTarget.TEINEIGO -> TEINEIGO
        }[plain].orEmpty()
        if (target == KeigoTarget.TEINEIGO) {
            val self = wordClass?.let { listOf(Equivalent(plain, it)) }.orEmpty()
            return special + self
        }
        if (wordClass == null || plain in NO_REGULAR) return special
        return special + regular(plain, wordClass, target)
    }

    private fun regular(plain: String, wordClass: WordClass, target: KeigoTarget): List<Equivalent> {
        if (wordClass == WordClass.SURU) {
            val noun = plain.removeSuffix("する")
            if (noun.isEmpty() || noun == plain) return emptyList()
            val prefixes = when (noun) {
                in BOTH_PREFIXES -> listOf("ご", "お")
                in O_NOUNS -> listOf("お")
                else -> listOf("ご")
            }
            return when (target) {
                KeigoTarget.SONKEIGO -> prefixes.flatMap { godan("$it${noun}になる", "$it${noun}なさる") } +
                    godan("${noun}なさる") + ichidan("${noun}される")
                KeigoTarget.KENJOGO -> prefixes.flatMap { suru("$it${noun}する") + godan("$it${noun}いたす", "$it${noun}致す") }
                KeigoTarget.TEINEIGO -> emptyList()
            }
        }
        if (wordClass != WordClass.GODAN && wordClass != WordClass.ICHIDAN) return emptyList()
        val table = Conjugator.conjugate(plain, wordClass) ?: return emptyList()
        val stem = table[Conjugation.POLITE]?.removeSuffix("ます") ?: return emptyList()
        if (stem.length < 2 && wordClass == WordClass.ICHIDAN) return emptyList() // 見/寝/着: special forms only
        return when (target) {
            KeigoTarget.SONKEIGO -> godan("お${stem}になる") + listOfNotNull(table[Conjugation.PASSIVE]).flatMap { ichidan(it) }
            KeigoTarget.KENJOGO -> suru("お${stem}する") + godan("お${stem}いたす", "お${stem}致す")
            KeigoTarget.TEINEIGO -> emptyList()
        }
    }
}
