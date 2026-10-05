package app.mokuhyo.numbers

import kotlin.random.Random

private fun Int.pad(n: Int) = toString().padStart(n, '0')

/**
 * Number grammar of a language (BRIEF_PHASE8 N-02), supplied by the [app.mokuhyo.lang.LanguageModule]: spelled-out
 * cardinals and ordinals, and written + spoken forms of times and dates. The spoken form is what the voice service
 * reads; the written form is what a local would write.
 */
interface NumberGrammar {
    val language: String

    fun cardinal(n: Long): String

    fun ordinal(n: Long): String

    /** Clock time: (written, spoken). [clock24] false = the 12-hour form with the language's day-period word. */
    fun time(hour: Int, minute: Int, clock24: Boolean): Pair<String, String>

    /** Calendar date: (written, spoken). */
    fun date(year: Int, month: Int, day: Int): Pair<String, String>

    /** Each digit read separately (phone numbers, frequencies, grids, tail numbers). */
    fun digitByDigit(digits: String): String = digits.filter { it.isDigit() }.map { cardinal((it - '0').toLong()) }.joinToString(" ")

    /** The word for "decimal point" when reading frequencies digit by digit. */
    val point: String
}

/** Spelling alphabets (BRIEF_PHASE8 N-02): NATO everywhere, plus national alphabets for the partner force's own script. */
object SpellingAlphabets {
    val NATO = mapOf(
        'A' to "Alfa", 'B' to "Bravo", 'C' to "Charlie", 'D' to "Delta", 'E' to "Echo", 'F' to "Foxtrot", 'G' to "Golf", 'H' to "Hotel",
        'I' to "India", 'J' to "Juliett", 'K' to "Kilo", 'L' to "Lima", 'M' to "Mike", 'N' to "November", 'O' to "Oscar", 'P' to "Papa",
        'Q' to "Quebec", 'R' to "Romeo", 'S' to "Sierra", 'T' to "Tango", 'U' to "Uniform", 'V' to "Victor", 'W' to "Whiskey", 'X' to "X-ray",
        'Y' to "Yankee", 'Z' to "Zulu",
    )

    /** NATO words as Japanese speakers write and say them (katakana). */
    val NATO_JA = mapOf(
        'A' to "アルファ", 'B' to "ブラボー", 'C' to "チャーリー", 'D' to "デルタ", 'E' to "エコー", 'F' to "フォックストロット", 'G' to "ゴルフ",
        'H' to "ホテル", 'I' to "インディア", 'J' to "ジュリエット", 'K' to "キロ", 'L' to "リマ", 'M' to "マイク", 'N' to "ノベンバー", 'O' to "オスカー",
        'P' to "パパ", 'Q' to "ケベック", 'R' to "ロメオ", 'S' to "シエラ", 'T' to "タンゴ", 'U' to "ユニフォーム", 'V' to "ビクター", 'W' to "ウィスキー",
        'X' to "エックスレイ", 'Y' to "ヤンキー", 'Z' to "ズールー",
    )

    /** 和文通話表 (Japanese radiotelephony kana alphabet), the common kana. */
    val WABUN = mapOf(
        'ア' to "朝日のア", 'イ' to "いろはのイ", 'ウ' to "上野のウ", 'エ' to "英語のエ", 'オ' to "大阪のオ", 'カ' to "為替のカ", 'キ' to "切手のキ",
        'ク' to "クラブのク", 'ケ' to "景色のケ", 'コ' to "子供のコ", 'サ' to "桜のサ", 'シ' to "新聞のシ", 'ス' to "すずめのス", 'セ' to "世界のセ",
        'ソ' to "そろばんのソ", 'タ' to "煙草のタ", 'チ' to "ちどりのチ", 'ツ' to "つるかめのツ", 'テ' to "手紙のテ", 'ト' to "東京のト", 'ナ' to "名古屋のナ",
        'ニ' to "日本のニ", 'ヌ' to "沼津のヌ", 'ネ' to "ねずみのネ", 'ノ' to "野原のノ", 'ハ' to "はがきのハ", 'ヒ' to "飛行機のヒ", 'フ' to "富士山のフ",
        'ヘ' to "平和のヘ", 'ホ' to "保険のホ", 'マ' to "マッチのマ", 'ミ' to "三笠のミ", 'ム' to "無線のム", 'メ' to "明治のメ", 'モ' to "もみじのモ",
        'ヤ' to "大和のヤ", 'ユ' to "弓矢のユ", 'ヨ' to "吉野のヨ", 'ラ' to "ラジオのラ", 'リ' to "りんごのリ", 'ル' to "留守居のル", 'レ' to "れんげのレ",
        'ロ' to "ローマのロ", 'ワ' to "わらびのワ", 'ン' to "おしまいのン",
    )

    /** German spelling alphabet (DIN 5009:2022, city names). */
    val DIN_5009 = mapOf(
        'A' to "Aachen", 'B' to "Berlin", 'C' to "Chemnitz", 'D' to "Düsseldorf", 'E' to "Essen", 'F' to "Frankfurt", 'G' to "Goslar",
        'H' to "Hamburg", 'I' to "Ingelheim", 'J' to "Jena", 'K' to "Köln", 'L' to "Leipzig", 'M' to "München", 'N' to "Nürnberg", 'O' to "Offenbach",
        'P' to "Potsdam", 'Q' to "Quickborn", 'R' to "Rostock", 'S' to "Salzwedel", 'T' to "Tübingen", 'U' to "Unna", 'V' to "Völklingen",
        'W' to "Wuppertal", 'X' to "Xanten", 'Y' to "Ypsilon", 'Z' to "Zwickau",
    )

    /** Russian radiotelephony alphabet (Cyrillic letters). */
    val RUSSIAN = mapOf(
        'А' to "Анна", 'Б' to "Борис", 'В' to "Василий", 'Г' to "Григорий", 'Д' to "Дмитрий", 'Е' to "Елена", 'Ж' to "Женя", 'З' to "Зинаида",
        'И' to "Иван", 'К' to "Константин", 'Л' to "Леонид", 'М' to "Михаил", 'Н' to "Николай", 'О' to "Ольга", 'П' to "Павел", 'Р' to "Роман",
        'С' to "Семён", 'Т' to "Татьяна", 'У' to "Ульяна", 'Ф' to "Фёдор", 'Х' to "Харитон", 'Ц' to "Цапля", 'Ч' to "Человек", 'Ш' to "Шура",
        'Щ' to "Щука", 'Э' to "Эхо", 'Ю' to "Юрий", 'Я' to "Яков",
    )

    /** The alphabet for Latin letters (grids, call signs) as the partner force says it on the radio. */
    fun latin(lang: String): Map<Char, String> = when (lang) {
        "ja" -> NATO_JA
        else -> NATO // partner air forces use the NATO alphabet for Latin letters on the radio
    }

    /** A national alphabet for the language's own script, when one exists. */
    fun national(lang: String): Map<Char, String>? = when (lang) {
        "ja" -> WABUN
        "de" -> DIN_5009
        "ru" -> RUSSIAN
        else -> null
    }

    fun spell(word: String, alphabet: Map<Char, String>): String = word.uppercase().mapNotNull { alphabet[it] }.joinToString(" ")
}

/** The kinds of number items (BRIEF_PHASE8 N-02). */
enum class NumberKind(val title: String, val instruction: String) {
    TIME("Times", "Type the time you hear as HH:MM (24-hour)."),
    DATE("Dates", "Type the date you hear as YYYY-MM-DD."),
    GRID("Grid references", "Type the MGRS grid reference you hear, letters and digits."),
    BEARING("Bearing and range", "Type the bearing in degrees and the range, e.g. 270/15."),
    CALLSIGN("Call signs", "Type the call sign you hear, e.g. VIPER 21."),
    TAIL("Tail numbers", "Type the aircraft tail number you hear."),
    PHONE("Phone numbers", "Type the phone number you hear, digits only."),
    FREQUENCY("Frequencies", "Type the frequency you hear, e.g. 243.000."),
    COUNT("Counts", "Type the number you hear."),
    SPELLING("Spelling alphabet", "Type the word that was spelled."),
}

/** One generated item: [spoken] is read by the voice service; [answer] is the canonical typed answer. */
data class NumberItem(val id: String, val kind: NumberKind, val spoken: String, val written: String, val answer: String) {
    /** Loose comparison: case, spaces and separators don't matter. */
    fun accepts(typed: String): Boolean = norm(typed) == norm(answer)

    companion object {
        fun norm(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }.let { digitsOnly(it) }

        /** Arabic-Indic and Persian digits count as the same digits. */
        private fun digitsOnly(s: String): String = s.map { c ->
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                in '０'..'９' -> '0' + (c - '０')
                else -> c
            }
        }.joinToString("")
    }
}

/**
 * Generates number items for a language (no model needed, so the drill runs offline on any tier). Deterministic for a
 * given [random]. Call signs and spelled words come from small built-in lists.
 */
class NumberItems(private val g: NumberGrammar, private val random: Random) {
    private var n = 0
    private fun id(kind: NumberKind) = "${g.language}-num-${kind.name.lowercase()}-${++n}"

    fun item(kind: NumberKind): NumberItem = when (kind) {
        NumberKind.TIME -> {
            val h = random.nextInt(24)
            val m = random.nextInt(12) * 5
            val (w, s) = g.time(h, m, clock24 = random.nextBoolean() || h == 0)
            NumberItem(id(kind), kind, s, w, "${h.pad(2)}:${m.pad(2)}")
        }
        NumberKind.DATE -> {
            val y = 2024 + random.nextInt(5)
            val mo = 1 + random.nextInt(12)
            val d = 1 + random.nextInt(28)
            val (w, s) = g.date(y, mo, d)
            NumberItem(id(kind), kind, s, w, "${y.pad(4)}-${mo.pad(2)}-${d.pad(2)}")
        }
        NumberKind.GRID -> {
            val zone = "${10 + random.nextInt(50)}${"CDEFGHJKLMNPQRSTUVWX"[random.nextInt(20)]}"
            val square = "${"ABCDEFGHJKLMNPQRSTUVWXYZ"[random.nextInt(24)]}${"ABCDEFGHJKLMNPQRSTUV"[random.nextInt(20)]}"
            val digits = (1..8).map { random.nextInt(10) }.joinToString("")
            val grid = "$zone$square$digits"
            NumberItem(id(kind), kind, spellMixed(grid), "$zone $square ${digits.take(4)} ${digits.drop(4)}", grid)
        }
        NumberKind.BEARING -> {
            val b = random.nextInt(36) * 10
            val r = 2 + random.nextInt(40)
            NumberItem(id(kind), kind, "${g.digitByDigit(b.pad(3))}, ${g.cardinal(r.toLong())}", "${b.pad(3)}° / $r", "$b/$r")
        }
        NumberKind.CALLSIGN -> {
            val word = CALLSIGNS[random.nextInt(CALLSIGNS.size)]
            val num = 10 + random.nextInt(90)
            NumberItem(id(kind), kind, "$word ${g.digitByDigit(num.toString())}", "$word $num", "$word $num")
        }
        NumberKind.TAIL -> {
            val tail = "${random.nextInt(100).pad(2)}-${random.nextInt(10000).pad(4)}"
            NumberItem(id(kind), kind, g.digitByDigit(tail), tail, tail)
        }
        NumberKind.PHONE -> {
            val phone = "0" + (1..9).map { random.nextInt(10) }.joinToString("")
            NumberItem(id(kind), kind, g.digitByDigit(phone), phone.chunked(3).joinToString(" "), phone)
        }
        NumberKind.FREQUENCY -> {
            val mhz = 225 + random.nextInt(175)
            val khz = random.nextInt(40) * 25
            val f = "$mhz.${khz.pad(3)}"
            NumberItem(id(kind), kind, "${g.digitByDigit(mhz.toString())} ${g.point} ${g.digitByDigit(khz.pad(3))}", f, f)
        }
        NumberKind.COUNT -> {
            val c = listOf(random.nextInt(2, 20), random.nextInt(20, 200), random.nextInt(200, 5000)).random(random).toLong()
            NumberItem(id(kind), kind, g.cardinal(c), c.toString(), c.toString())
        }
        NumberKind.SPELLING -> {
            val national = SpellingAlphabets.national(g.language)
            val word = if (national != null && random.nextBoolean()) NATIONAL_WORDS[g.language]!!.random(random) else WORDS.random(random)
            val alphabet = if (word.any { it.uppercaseChar() in SpellingAlphabets.NATO }) SpellingAlphabets.latin(g.language) else national!!
            NumberItem(id(kind), kind, SpellingAlphabets.spell(word, alphabet), word, word)
        }
    }

    /** Letters by the radio alphabet, digits one by one. */
    private fun spellMixed(s: String): String {
        val alphabet = SpellingAlphabets.latin(g.language)
        return s.map { c -> if (c.isDigit()) g.cardinal((c - '0').toLong()) else alphabet[c.uppercaseChar()] ?: c.toString() }.joinToString(" ")
    }

    fun set(count: Int, kinds: List<NumberKind> = NumberKind.entries): List<NumberItem> = List(count) { item(kinds[it % kinds.size]) }

    companion object {
        val CALLSIGNS = listOf("VIPER", "HAWK", "RAVEN", "COBRA", "EAGLE", "TALON", "SABER", "GHOST", "LANCER", "TITAN")
        val WORDS = listOf("RADAR", "DRONE", "GATE", "TOWER", "BRAVO", "NORTH", "HANGAR", "FUEL", "CLEAR", "SECTOR")
        val NATIONAL_WORDS = mapOf("ja" to listOf("アサヒ", "ムセン", "ヒコウキ", "サクラ", "トウキョウ"), "de" to listOf("TOR", "WACHE", "FUNK", "LAGER", "PISTE"),
            "ru" to listOf("РАДАР", "ПОСТ", "ВОРОТА", "БАЗА", "СВЯЗЬ"))
    }
}

/**
 * The numbers drill (BRIEF_PHASE8 N-02): items come from [NumberItems]; the next kind is chosen adaptively, weighting
 * kinds the learner has got wrong more (each error doubles the kind's weight, each right answer halves it, floor 1).
 */
class NumberDrill(private val items: NumberItems, private val kinds: List<NumberKind> = NumberKind.entries, private val random: Random) {
    private val weights = kinds.associateWith { 1.0 }.toMutableMap()
    val errors = mutableMapOf<NumberKind, Int>()
    var answered = 0
        private set
    var right = 0
        private set
    var current: NumberItem = next()
        private set

    private fun next(): NumberItem {
        val total = weights.values.sum()
        var r = random.nextDouble() * total
        val kind = weights.entries.firstOrNull { r -= it.value; r < 0 }?.key ?: kinds.last()
        return items.item(kind)
    }

    /** Grades [typed] against the current item, adapts, and moves on; returns whether it was right. */
    fun answer(typed: String): Boolean {
        val ok = current.accepts(typed)
        answered++
        val k = current.kind
        if (ok) {
            right++
            weights[k] = (weights.getValue(k) / 2).coerceAtLeast(1.0)
        } else {
            errors[k] = (errors[k] ?: 0) + 1
            weights[k] = weights.getValue(k) * 2
        }
        current = next()
        return ok
    }

    fun weight(kind: NumberKind): Double = weights.getValue(kind)
}
