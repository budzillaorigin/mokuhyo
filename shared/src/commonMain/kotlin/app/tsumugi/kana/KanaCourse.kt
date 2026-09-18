package app.tsumugi.kana

import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.jp.Kana
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import kotlin.random.Random

enum class KanaScript { HIRAGANA, KATAKANA }

enum class KanaGroup { BASE, DAKUTEN, HANDAKUTEN, YOON }

/** One kana (or yōon pair) of the course. [mnemonic] is always AI-drafted text ([mnemonicSource] "llm", rule 10). */
data class KanaChar(
    val kana: String,
    val script: KanaScript,
    val group: KanaGroup,
    /** Hepburn first, then accepted alternatives (し: shi, si). */
    val romaji: List<String>,
    val mnemonic: String,
    val mnemonicSource: String = "llm",
) {
    /** The SRS item id. */
    val itemId: String get() = ITEM_PREFIX + kana

    /** The characters to practise stroke by stroke (KanjiVG has every kana; yōon is two characters). */
    val strokeChars: List<String> get() = kana.map { it.toString() }

    companion object {
        const val ITEM_PREFIX = "kana:"
    }
}

/** One lesson: a row of the table (or the voiced marks / yōon) with an intro written for the course. */
data class KanaLesson(
    val id: String,
    val script: KanaScript,
    val order: Int,
    val title: String,
    val intro: String,
    val chars: List<KanaChar>,
    /** The intro is AI-drafted like the mnemonics. */
    val introSource: String = "llm",
)

/** The hiragana and katakana tables the course teaches: 46 + 46 base kana, 25 + 25 voiced, 33 + 33 yōon. */
object KanaTable {
    private val baseRows: List<Pair<String, List<Pair<String, List<String>>>>> = listOf(
        "vowels" to listOf("あ" to listOf("a"), "い" to listOf("i"), "う" to listOf("u"), "え" to listOf("e"), "お" to listOf("o")),
        "k" to listOf("か" to listOf("ka"), "き" to listOf("ki"), "く" to listOf("ku"), "け" to listOf("ke"), "こ" to listOf("ko")),
        "s" to listOf("さ" to listOf("sa"), "し" to listOf("shi", "si"), "す" to listOf("su"), "せ" to listOf("se"), "そ" to listOf("so")),
        "t" to listOf("た" to listOf("ta"), "ち" to listOf("chi", "ti"), "つ" to listOf("tsu", "tu"), "て" to listOf("te"), "と" to listOf("to")),
        "n" to listOf("な" to listOf("na"), "に" to listOf("ni"), "ぬ" to listOf("nu"), "ね" to listOf("ne"), "の" to listOf("no")),
        "h" to listOf("は" to listOf("ha"), "ひ" to listOf("hi"), "ふ" to listOf("fu", "hu"), "へ" to listOf("he"), "ほ" to listOf("ho")),
        "m" to listOf("ま" to listOf("ma"), "み" to listOf("mi"), "む" to listOf("mu"), "め" to listOf("me"), "も" to listOf("mo")),
        "y" to listOf("や" to listOf("ya"), "ゆ" to listOf("yu"), "よ" to listOf("yo")),
        "r" to listOf("ら" to listOf("ra"), "り" to listOf("ri"), "る" to listOf("ru"), "れ" to listOf("re"), "ろ" to listOf("ro")),
        "w" to listOf("わ" to listOf("wa"), "を" to listOf("wo", "o"), "ん" to listOf("n", "nn")),
    )

    private val dakuten: List<Pair<String, List<String>>> = listOf(
        "が" to listOf("ga"), "ぎ" to listOf("gi"), "ぐ" to listOf("gu"), "げ" to listOf("ge"), "ご" to listOf("go"),
        "ざ" to listOf("za"), "じ" to listOf("ji", "zi"), "ず" to listOf("zu"), "ぜ" to listOf("ze"), "ぞ" to listOf("zo"),
        "だ" to listOf("da"), "ぢ" to listOf("ji", "di"), "づ" to listOf("zu", "du"), "で" to listOf("de"), "ど" to listOf("do"),
        "ば" to listOf("ba"), "び" to listOf("bi"), "ぶ" to listOf("bu"), "べ" to listOf("be"), "ぼ" to listOf("bo"),
    )

    private val handakuten: List<Pair<String, List<String>>> = listOf(
        "ぱ" to listOf("pa"), "ぴ" to listOf("pi"), "ぷ" to listOf("pu"), "ぺ" to listOf("pe"), "ぽ" to listOf("po"),
    )

    /** (い-column kana, Hepburn consonant, alternative consonant or null). */
    private val yoonHeads: List<Triple<String, String, String?>> = listOf(
        Triple("き", "ky", null), Triple("し", "sh", "sy"), Triple("ち", "ch", "ty"), Triple("に", "ny", null),
        Triple("ひ", "hy", null), Triple("み", "my", null), Triple("り", "ry", null), Triple("ぎ", "gy", null),
        Triple("じ", "j", "zy"), Triple("び", "by", null), Triple("ぴ", "py", null),
    )
    private val smallY = listOf("ゃ" to "a", "ゅ" to "u", "ょ" to "o")

    private fun char(kana: String, script: KanaScript, group: KanaGroup, romaji: List<String>): KanaChar {
        val text = if (script == KanaScript.KATAKANA) Kana.toKatakana(kana) else kana
        val mnemonic = when (group) {
            KanaGroup.BASE -> (if (script == KanaScript.KATAKANA) KanaMnemonics.katakana[text] else KanaMnemonics.hiragana[text]).orEmpty()
            KanaGroup.DAKUTEN -> "$text: ${plain(text)} with the two voicing marks (゛) — \"${romaji.first()}\"."
            KanaGroup.HANDAKUTEN -> "$text: ${plain(text)} with the small circle (゜) — \"${romaji.first()}\"."
            KanaGroup.YOON -> "$text: ${text.first()} with a small ${text.last()} glued on, said as one beat — \"${romaji.first()}\"."
        }
        val source = if (group == KanaGroup.BASE && text in REVIEWED_KANA_MNEMONICS) "verified" else "llm"
        return KanaChar(text, script, group, romaji, mnemonic, source)
    }

    /** The unvoiced kana under a voiced one (が → か), for the rule text. */
    private fun plain(kana: String): String {
        val c = kana.first()
        val base = when {
            c in "ぱぴぷぺぽパピプペポ" -> c - 2
            else -> c - 1
        }
        return base.toString()
    }

    fun chars(script: KanaScript): List<KanaChar> = lessons(script).flatMap { it.chars }

    /** Every kana of both scripts. */
    val all: List<KanaChar> by lazy { chars(KanaScript.HIRAGANA) + chars(KanaScript.KATAKANA) }

    fun byKana(kana: String): KanaChar? = all.firstOrNull { it.kana == kana }

    /** The 15 lessons of one script, in teaching order. */
    fun lessons(script: KanaScript): List<KanaLesson> {
        val p = if (script == KanaScript.HIRAGANA) "hira" else "kata"
        val name = if (script == KanaScript.HIRAGANA) "Hiragana" else "Katakana"
        val out = ArrayList<KanaLesson>()
        baseRows.forEachIndexed { i, (row, chars) ->
            val kana = chars.map { (k, r) -> char(k, script, KanaGroup.BASE, r) }
            out += KanaLesson("$p-${i + 1}", script, i + 1, "$name: ${rowTitle(row, kana)}", baseIntro(script, row), kana)
        }
        val voiced = dakuten.map { (k, r) -> char(k, script, KanaGroup.DAKUTEN, r) }
        out += KanaLesson("$p-11", script, 11, "$name: が and ざ rows".let { if (script == KanaScript.KATAKANA) Kana.toKatakana(it) else it }, DAKUTEN_INTRO, voiced.take(10))
        out += KanaLesson("$p-12", script, 12, "$name: だ and ば rows".let { if (script == KanaScript.KATAKANA) Kana.toKatakana(it) else it }, DAKUTEN_INTRO_2, voiced.drop(10))
        out += KanaLesson("$p-13", script, 13, "$name: ぱ row".let { if (script == KanaScript.KATAKANA) Kana.toKatakana(it) else it }, HANDAKUTEN_INTRO, handakuten.map { (k, r) -> char(k, script, KanaGroup.HANDAKUTEN, r) })
        val yoon = yoonHeads.flatMap { (head, cons, alt) ->
            smallY.map { (small, vowel) -> char(head + small, script, KanaGroup.YOON, listOfNotNull(cons + vowel, alt?.plus(vowel))) }
        }
        out += KanaLesson("$p-14", script, 14, "$name: small ゃゅょ (1)".let { if (script == KanaScript.KATAKANA) Kana.toKatakana(it) else it }, YOON_INTRO, yoon.take(15))
        out += KanaLesson("$p-15", script, 15, "$name: small ゃゅょ (2)".let { if (script == KanaScript.KATAKANA) Kana.toKatakana(it) else it }, YOON_INTRO_2, yoon.drop(15))
        return out
    }

    fun lessons(): List<KanaLesson> = lessons(KanaScript.HIRAGANA) + lessons(KanaScript.KATAKANA)

    private fun rowTitle(row: String, kana: List<KanaChar>) = kana.joinToString("") { it.kana } + if (row == "vowels") " (vowels)" else ""

    private fun baseIntro(script: KanaScript, row: String): String {
        val first = when (row) {
            "vowels" -> if (script == KanaScript.HIRAGANA) {
                "Hiragana spell Japanese words and endings. Every kana is one beat (mora). Start with the five vowels: they are short and pure, a-i-u-e-o, never glided."
            } else {
                "Katakana are the same sounds in a sharper, angular hand, used for loanwords, names and emphasis. A long bar ー stretches the vowel before it: コーヒー is ko-o-hi-i."
            }
            "k" -> "Add k to each vowel. The shapes of the k row are the first ones people mix up, so say the sound out loud as you trace each one."
            "s" -> "The s row has one irregular sound: し is \"shi\", not \"si\"."
            "t" -> "Two irregular sounds here: ち is \"chi\" and つ is \"tsu\". The small っ (a half-size つ) is a pause that doubles the next consonant: きって is kit-te."
            "n" -> "The n row is regular: na, ni, nu, ne, no."
            "h" -> "ふ sounds between \"fu\" and \"hu\": the lips don't touch. は is also the topic particle, and then it is read \"wa\"."
            "m" -> "The m row is regular: ma, mi, mu, me, mo."
            "y" -> "Only three kana: ya, yu, yo. Their small forms will later glue onto other kana."
            "r" -> "The Japanese r is a light tap of the tongue, between an English r, l and d."
            else -> "わ is regular. を sounds \"o\" and marks the object of a sentence. ん is the only kana that is just a consonant, and it counts as a full beat."
        }
        return first
    }

    private const val DAKUTEN_INTRO =
        "Two small strokes at the top right (゛, dakuten) voice a consonant: k becomes g, s becomes z. The shape underneath doesn't change, so you already know how to read these."
    private const val DAKUTEN_INTRO_2 =
        "The same marks turn t into d and h into b. ぢ and づ sound like じ and ず and are rare; you'll mostly meet them in words like ちぢむ and つづく."
    private const val HANDAKUTEN_INTRO =
        "A small circle (゜, handakuten) turns the h row into p: ぱ, ぴ, ぷ, ぺ, ぽ. Only the h row takes the circle."
    private const val YOON_INTRO =
        "A small ゃ, ゅ or ょ after an i-column kana fuses with it into one beat: き + ゃ = きゃ \"kya\". Written full-size, きや is two beats, \"ki-ya\"."
    private const val YOON_INTRO_2 =
        "The same rule for the rest of the i column, including the voiced ones: ぎゃ \"gya\", じゃ \"ja\", びゃ \"bya\", ぴゃ \"pya\"."
}

/** A placement answer: the kana shown and what the learner typed. */
data class KanaPlacementAnswer(val kana: KanaChar, val typed: String) {
    val correct: Boolean get() = typed.trim().lowercase() in kana.romaji
}

data class KanaPlacementResult(val script: KanaScript, val correct: Int, val total: Int) {
    /** 90% or better skips the script's lessons. */
    val passed: Boolean get() = total > 0 && correct * 10 >= total * 9
}

/** Where the learner is in the course. */
data class KanaCourseStatus(
    val hiraganaLessonsDone: Int,
    val katakanaLessonsDone: Int,
    val lessonsPerScript: Int,
    val hiraganaSkipped: Boolean,
    val katakanaSkipped: Boolean,
) {
    val hiraganaDone: Boolean get() = hiraganaSkipped || hiraganaLessonsDone >= lessonsPerScript
    val katakanaDone: Boolean get() = katakanaSkipped || katakanaLessonsDone >= lessonsPerScript
    val complete: Boolean get() = hiraganaDone && katakanaDone
}

/**
 * The kana course for absolute beginners (BRIEF_V2 G-13, DECISIONS D-117): 15 hiragana then 15 katakana lessons,
 * each kana with an original mnemonic and stroke practice from the dictionary pack's KanjiVG strokes, and a
 * placement check per script that skips it. Finished lessons put the kana into SRS as `KANA` items (a MEANING
 * card: see the kana, type its romaji; optionally a WRITING card).
 *
 * Today integration (owned by TodayPlanner): call [needed] to decide whether the Foundations phase starts here,
 * and [lessonQueue] for the next lesson(s). [needed] is true while the course is unfinished and either the
 * onboarding kanji check scored zero ([recordKanjiCheck]) or the learner enrolled ([enroll]).
 */
class KanaCourse(
    private val srs: SrsRepository,
    /** Also create a WRITING card (draw the kana) per kana. */
    private val writingCards: suspend () -> Boolean = { false },
) {
    val lessonsPerScript: Int = KanaTable.lessons(KanaScript.HIRAGANA).size

    fun lessons(): List<KanaLesson> = KanaTable.lessons()

    fun lesson(id: String): KanaLesson? = lessons().firstOrNull { it.id == id }

    /** Whether the Foundations phase should run the kana course (see the class comment). */
    @Throws(Exception::class)
    suspend fun needed(settings: SettingsRepository): Boolean {
        val trigger = settings.get(KANJI_CHECK_SCORE)?.toIntOrNull() == 0 || settings.bool(ENROLLED, false)
        return trigger && !status(settings).complete
    }

    @Throws(Exception::class)
    suspend fun status(settings: SettingsRepository): KanaCourseStatus {
        val lessons = lessons()
        val present = srs.items(lessons.flatMap { l -> l.chars.map { it.itemId } }).keys
        fun done(script: KanaScript) = lessons.count { l -> l.script == script && l.chars.all { it.itemId in present } }
        return KanaCourseStatus(
            done(KanaScript.HIRAGANA), done(KanaScript.KATAKANA), lessonsPerScript,
            settings.bool(skipKey(KanaScript.HIRAGANA), false), settings.bool(skipKey(KanaScript.KATAKANA), false),
        )
    }

    /** The next [limit] lessons not yet done, hiragana first, skipping scripts the placement check passed. */
    @Throws(Exception::class)
    suspend fun lessonQueue(settings: SettingsRepository, limit: Int = 1): List<KanaLesson> {
        val lessons = lessons()
        val present = srs.items(lessons.flatMap { l -> l.chars.map { it.itemId } }).keys
        val skipped = KanaScript.entries.filter { settings.bool(skipKey(it), false) }.toSet()
        return lessons.filter { l -> l.script !in skipped && !l.chars.all { it.itemId in present } }.take(limit)
    }

    /**
     * Finishes a lesson: adds its kana to SRS and introduces their cards (first review after the first learning
     * step). Returns the introduction review ids (for undo). Idempotent for kana already added.
     */
    @Throws(Exception::class)
    suspend fun completeLesson(lesson: KanaLesson, settings: SettingsRepository): List<String> {
        val directions = listOf(CardDirection.MEANING) + if (writingCards()) listOf(CardDirection.WRITING) else emptyList()
        val existing = srs.items(lesson.chars.map { it.itemId }).keys
        val fresh = lesson.chars.filter { it.itemId !in existing }
        srs.addItems(fresh.map { it.toNewItem(directions) })
        settings.put(ENROLLED, "true")
        return srs.introduce(fresh.flatMap { c -> directions.map { SrsRepository.cardId(c.itemId, it) } })
    }

    /** Stroke paths per character of [kana] (via [strokes], normally the dictionary pack's KanjiVG data). */
    @Throws(Exception::class)
    suspend fun strokeData(kana: KanaChar, strokes: suspend (String) -> List<String>): List<Pair<String, List<String>>> =
        kana.strokeChars.map { it to strokes(it) }

    // --- Placement ------------------------------------------------------------------------------------------

    /** [count] random base kana of [script] to read (type the romaji). */
    fun placementQuestions(script: KanaScript, seed: Long, count: Int = 10): List<KanaChar> =
        KanaTable.chars(script).filter { it.group == KanaGroup.BASE }.shuffled(Random(seed)).take(count)

    /** Grades the check and, when passed, skips the script (its lessons leave the queue). */
    @Throws(Exception::class)
    suspend fun gradePlacement(script: KanaScript, answers: List<KanaPlacementAnswer>, settings: SettingsRepository): KanaPlacementResult {
        val result = KanaPlacementResult(script, answers.count { it.correct }, answers.size)
        if (result.passed) settings.put(skipKey(script), "true")
        return result
    }

    @Throws(Exception::class)
    suspend fun setSkipped(script: KanaScript, skipped: Boolean, settings: SettingsRepository) = settings.put(skipKey(script), skipped.toString())

    @Throws(Exception::class)
    suspend fun enroll(settings: SettingsRepository) = settings.put(ENROLLED, "true")

    companion object {
        /** Synced: how many kanji the learner knew in the onboarding check (0 → start with kana). */
        const val KANJI_CHECK_SCORE = "onboarding.kanjiCheckScore"
        const val ENROLLED = "kana.enrolled"
        private const val SKIP_PREFIX = "kana.skip."

        fun skipKey(script: KanaScript) = SKIP_PREFIX + script.name.lowercase()

        /** Onboarding calls this with the kanji check result; a score of 0 starts Foundations with the kana course. */
        @Throws(Exception::class)
        suspend fun recordKanjiCheck(settings: SettingsRepository, known: Int) = settings.put(KANJI_CHECK_SCORE, known.coerceAtLeast(0).toString())

        /** Kana items come before every path level (level 0). */
        const val LEVEL = 0
    }
}

internal fun KanaChar.toNewItem(directions: List<CardDirection>) = NewItem(
    id = itemId,
    kind = ItemKind.KANA,
    primaryText = kana,
    reading = Kana.toHiragana(kana),
    meanings = romaji,
    acceptedReadings = romaji,
    source = ItemSource.PACK,
    directions = directions,
    level = KanaCourse.LEVEL,
    packId = "kana",
    context = """{"type":"kana","script":"${script.name}","group":"${group.name}"}""",
)
