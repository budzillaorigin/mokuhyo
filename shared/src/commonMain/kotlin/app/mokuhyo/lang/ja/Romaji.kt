package app.mokuhyo.lang.ja

/** Result of an incremental IME step: [pending] is a trailing romaji chunk that may still become kana. */
data class ImeResult(val converted: String, val pending: String) {
    val text: String get() = converted + pending
}

/**
 * Romaji ⇄ kana conversion.
 *
 * Input accepts Hepburn, Kunrei-shiki and Nihon-shiki spellings. Uppercase romaji produces katakana
 * (IME convention). ん: `nn`, `n'`, or `n` before a consonant. `nn` directly before a vowel yields ん
 * and leaves the second `n` to start the next mora, so both `konnichiwa` and `konnnichiwa` give
 * こんにちわ and `onna` gives おんな. `nn` before `y` is ん + や-row (`honnya` → ほんや), as in IMEs.
 */
object Romaji {

    private val table: Map<String, String> = buildMap {
        fun row(prefix: String, kana: String, vowels: String = "aiueo") {
            vowels.forEachIndexed { i, v -> put(prefix + v, kana.split(' ')[i]) }
        }
        row("", "あ い う え お")
        row("k", "か き く け こ")
        row("g", "が ぎ ぐ げ ご")
        row("s", "さ し す せ そ")
        row("z", "ざ じ ず ぜ ぞ")
        row("t", "た ち つ て と")
        row("d", "だ ぢ づ で ど")
        row("n", "な に ぬ ね の")
        row("h", "は ひ ふ へ ほ")
        row("b", "ば び ぶ べ ぼ")
        row("p", "ぱ ぴ ぷ ぺ ぽ")
        row("m", "ま み む め も")
        row("r", "ら り る れ ろ")
        row("v", "ゔぁ ゔぃ ゔ ゔぇ ゔぉ")
        row("f", "ふぁ ふぃ ふ ふぇ ふぉ")
        row("x", "ぁ ぃ ぅ ぇ ぉ")
        row("l", "ぁ ぃ ぅ ぇ ぉ")
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ"); put("ye", "いぇ")
        put("wa", "わ"); put("wo", "を"); put("wi", "うぃ"); put("we", "うぇ"); put("wu", "う")
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("ji", "じ"); put("dzu", "づ")

        // 拗音: consonant + y + vowel.
        for ((c, i) in listOf(
            "k" to "き", "g" to "ぎ", "s" to "し", "z" to "じ", "t" to "ち", "c" to "ち", "d" to "ぢ",
            "n" to "に", "h" to "ひ", "b" to "び", "p" to "ぴ", "m" to "み", "r" to "り", "j" to "じ",
            "f" to "ふ", "v" to "ゔ",
        )) {
            row(c + "y", "${i}ゃ ${i}ぃ ${i}ゅ ${i}ぇ ${i}ょ")
        }
        row("sh", "しゃ し しゅ しぇ しょ")
        row("ch", "ちゃ ち ちゅ ちぇ ちょ")
        row("j", "じゃ じ じゅ じぇ じょ")
        row("ts", "つぁ つぃ つ つぇ つぉ")
        put("thi", "てぃ"); put("dhi", "でぃ"); put("twu", "とぅ"); put("dwu", "どぅ")
        put("thu", "てゅ"); put("dhu", "でゅ")

        for (p in listOf("x", "l")) {
            put("${p}ya", "ゃ"); put("${p}yu", "ゅ"); put("${p}yo", "ょ")
            put("${p}tu", "っ"); put("${p}tsu", "っ"); put("${p}wa", "ゎ")
            put("${p}ka", "ゕ"); put("${p}ke", "ゖ")
        }
        put("n'", "ん")
        put("-", "ー")
    }

    private val maxKey = table.keys.maxOf { it.length }

    /** Every proper prefix of a table key: a buffer ending in one of these may still complete. */
    private val prefixes: Set<String> = buildSet {
        for (k in table.keys) for (len in 1 until k.length) add(k.substring(0, len))
        add("tc") // っち via "tch"
    }

    private const val VOWELS = "aeiou"
    private const val DOUBLING = "bcdfghjklmpqrstvwxyz"

    fun toHiragana(input: String): String = convert(input, ime = false).text

    /** Convert as much of [buffer] as possible, keeping a trailing chunk that could still become kana. */
    fun imeConvert(buffer: String): ImeResult = convert(buffer, ime = true)

    /** Commit an IME buffer: trailing `n`/`nn` become ん; other leftover latin is kept as typed. */
    fun finalize(buffer: String): String {
        val r = convert(buffer, ime = true)
        val tail = when (r.pending.lowercase()) {
            "n", "nn" -> if (r.pending[0].isUpperCase()) "ン" else "ん"
            else -> r.pending
        }
        return r.converted + tail
    }

    private fun convert(input: String, ime: Boolean): ImeResult {
        val out = StringBuilder()
        val lower = input.lowercase()
        var i = 0
        while (i < input.length) {
            val c = lower[i]
            val upper = input[i].isUpperCase()
            fun emit(kana: String) = out.append(if (upper) Kana.toKatakana(kana) else kana)

            if (c !in 'a'..'z' && c != '-' && c != '\'') {
                out.append(input[i]); i++; continue
            }
            val rest = lower.substring(i)

            if (c == 'n') {
                val next = rest.getOrNull(1)
                when {
                    next == null -> {
                        if (ime) return ImeResult(out.toString(), input.substring(i))
                        emit("ん"); i++; continue
                    }
                    next == 'n' -> {
                        val after = rest.getOrNull(2)
                        if (after == null && ime) return ImeResult(out.toString(), input.substring(i))
                        emit("ん")
                        i += if (after != null && after in VOWELS) 1 else 2
                        continue
                    }
                    next == '\'' -> { emit("ん"); i += 2; continue }
                    next !in VOWELS && next != 'y' -> { emit("ん"); i++; continue }
                }
            }

            // Sokuon: doubled consonant, or t before ch.
            val next = rest.getOrNull(1)
            if ((next == c && c in DOUBLING) || (c == 't' && rest.startsWith("tch"))) {
                emit("っ"); i++; continue
            }

            var matched = false
            for (len in minOf(maxKey, rest.length) downTo 1) {
                val kana = table[rest.substring(0, len)] ?: continue
                emit(kana)
                i += len
                matched = true
                break
            }
            if (matched) continue

            if (ime && rest in prefixes) return ImeResult(out.toString(), input.substring(i))
            out.append(input[i]); i++
        }
        return ImeResult(out.toString(), "")
    }

    // --- kana → romaji -----------------------------------------------------------------------------

    private val reverse: Map<String, String> = buildMap {
        fun row(kana: String, roman: String) {
            val k = kana.split(' ')
            val r = roman.split(' ')
            k.indices.forEach { put(k[it], r[it]) }
        }
        row("あ い う え お", "a i u e o")
        row("か き く け こ", "ka ki ku ke ko")
        row("が ぎ ぐ げ ご", "ga gi gu ge go")
        row("さ し す せ そ", "sa shi su se so")
        row("ざ じ ず ぜ ぞ", "za ji zu ze zo")
        row("た ち つ て と", "ta chi tsu te to")
        row("だ ぢ づ で ど", "da ji zu de do")
        row("な に ぬ ね の", "na ni nu ne no")
        row("は ひ ふ へ ほ", "ha hi fu he ho")
        row("ば び ぶ べ ぼ", "ba bi bu be bo")
        row("ぱ ぴ ぷ ぺ ぽ", "pa pi pu pe po")
        row("ま み む め も", "ma mi mu me mo")
        row("や ゆ よ", "ya yu yo")
        row("ら り る れ ろ", "ra ri ru re ro")
        row("わ ゐ ゑ を ゔ", "wa i e wo vu")
        row("ぁ ぃ ぅ ぇ ぉ ゃ ゅ ょ ゎ ゕ ゖ", "a i u e o ya yu yo wa ka ke")
        for ((kana, roman) in listOf(
            "き" to "k", "ぎ" to "g", "に" to "n", "ひ" to "h", "び" to "b", "ぴ" to "p", "み" to "m", "り" to "r",
        )) {
            row("${kana}ゃ ${kana}ゅ ${kana}ょ", "${roman}ya ${roman}yu ${roman}yo")
        }
        row("しゃ しゅ しょ しぇ", "sha shu sho she")
        row("じゃ じゅ じょ じぇ", "ja ju jo je")
        row("ちゃ ちゅ ちょ ちぇ", "cha chu cho che")
        row("ぢゃ ぢゅ ぢょ", "ja ju jo")
        row("ふぁ ふぃ ふぇ ふぉ ふゅ", "fa fi fe fo fyu")
        row("てぃ でぃ とぅ どぅ てゅ でゅ", "ti di tu du tyu dyu")
        row("うぃ うぇ うぉ いぇ", "wi we wo ye")
        row("ゔぁ ゔぃ ゔぇ ゔぉ", "va vi ve vo")
        row("つぁ つぃ つぇ つぉ", "tsa tsi tse tso")
    }

    /** Modified Hepburn. ん before a vowel or y is written n'; っ doubles the next consonant (っち → tchi). */
    fun fromKana(kana: String): String {
        val s = Kana.toHiragana(kana)
        val parts = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val two = if (i + 1 < s.length) reverse[s.substring(i, i + 2)] else null
            if (two != null) {
                parts += two; i += 2; continue
            }
            parts += when (val c = s[i]) {
                'っ' -> "\u0000" // placeholder, resolved against the following mora below
                'ん' -> "n"
                'ー' -> "-"
                else -> reverse[c.toString()] ?: c.toString()
            }
            i++
        }
        return buildString {
            parts.forEachIndexed { idx, p ->
                val next = parts.getOrNull(idx + 1)
                when {
                    p == "\u0000" -> when {
                        next == null || next.isEmpty() || next[0] !in 'a'..'z' || next[0] in VOWELS -> {}
                        next.startsWith("ch") -> append('t')
                        else -> append(next[0])
                    }
                    p == "n" && s.isNotEmpty() && next != null && next.isNotEmpty() &&
                        (next[0] in VOWELS || next[0] == 'y') -> append("n'")
                    else -> append(p)
                }
            }
        }
    }
}
