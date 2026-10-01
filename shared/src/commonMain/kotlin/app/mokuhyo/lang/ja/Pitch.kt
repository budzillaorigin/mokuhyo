package app.mokuhyo.lang.ja

enum class PitchPattern { HEIBAN, ATAMADAKA, NAKADAKA, ODAKA }

/**
 * Tokyo-dialect pitch accent for a word of [moraCount] morae with the drop after mora [downstep]
 * (0 = heiban, no drop).
 */
data class PitchAccent(val downstep: Int, val moraCount: Int) {

    val pattern: PitchPattern
        get() = when {
            downstep == 0 -> PitchPattern.HEIBAN
            downstep == 1 -> PitchPattern.ATAMADAKA
            downstep >= moraCount -> PitchPattern.ODAKA
            else -> PitchPattern.NAKADAKA
        }

    /**
     * High (true) / low (false) per mora, plus one trailing entry for a following particle
     * (length = moraCount + 1). First mora is low unless the accent is on it; after the downstep all low.
     */
    fun heights(): List<Boolean> = List(moraCount + 1) { i ->
        val position = i + 1
        when (downstep) {
            0 -> position != 1
            1 -> position == 1
            else -> position in 2..downstep
        }
    }
}

object Pitch {
    /** Parses Kanjium-style accent lists: "0,2" → [0, 2]. Blank and malformed parts are ignored. */
    fun parse(accents: String): List<Int> =
        accents.split(',').mapNotNull { it.trim().toIntOrNull() }

    fun accent(reading: String, downstep: Int): PitchAccent = PitchAccent(downstep, Mora.count(reading))
}

/** What kind of word heads an inflected unit, for [PitchRules]. */
enum class InflectingClass { VERB, ADJECTIVE, OTHER }

/**
 * One inflected word as the tokenizer splits it: a head morpheme plus the auxiliaries (助動詞) and conjunctive
 * て/で that follow it. Readings are kana (either script).
 *
 * @property lemmaDownstep the dictionary form's accent (0 = heiban), from the pitch table.
 * @property lemmaReading the dictionary form's reading, e.g. たべる / たかい.
 * @property stem the head morpheme's own reading as it appears, e.g. たべ (食べ), かい (書い), たかかっ (高かっ).
 * @property headIsLemma the head appears in its dictionary form (高い in 高いです).
 * @property ichidan the verb is 一段 (its stem-final mora carries the accent of the dictionary form).
 * @property headForm the head's IPADIC conjugation form, e.g. 連用タ接続, 連用テ接続.
 * @property endings the readings of the following morphemes, in order, e.g. [まし, た].
 */
data class InflectedWord(
    val wordClass: InflectingClass,
    val lemmaDownstep: Int,
    val lemmaReading: String,
    val stem: String,
    val headIsLemma: Boolean,
    val ichidan: Boolean = false,
    val headForm: String? = null,
    val endings: List<String> = emptyList(),
)

/**
 * Accent of conjugated forms from the dictionary form's accent (F-21). A deliberately small table of the standard
 * Tokyo rules, derived from the usual textbook descriptions of verb and adjective accent (e.g. the conjugation
 * appendix of NHK's pronunciation-accent dictionary and Vance, *The Sounds of Japanese*, ch. 8). Positions count
 * morae from the start of the whole word, as in [PitchAccent.downstep]:
 *
 * | form | heiban lemma | accented lemma (downstep d) |
 * |---|---|---|
 * | verb + ます / まし(た) | drop after ま | drop after ま |
 * | verb + ませ(ん) / ましょ(う) | drop after せ / しょ | drop after せ / しょ |
 * | verb + た・て (だ・で) | heiban | godan: d; ichidan: d − 1 (min 1) — 食べる² → 食べた¹, 書く¹ → 書いた¹ |
 * | verb + ない | heiban | drop before な — 食べない² |
 * | adjective かっ + た | drop before かった — 赤かった² | d − 1 (min 1) — 高い² → 高かった¹ |
 * | adjective く / くて | heiban | d − 1 (min 1) — 高く¹ |
 * | dictionary form + です / でし(た) | drop after で | d |
 * | dictionary form + だ | heiban | d |
 *
 * Only the first ending decides: once the drop has happened, later endings (the た of ました) stay low.
 * Anything not in the table returns null, and the learner sees "accent unknown" rather than a guess.
 */
object PitchRules {
    fun downstep(w: InflectedWord): Int? {
        val stemMorae = Mora.count(Kana.toHiragana(w.stem))
        val first = w.endings.firstOrNull()?.let(Kana::toHiragana)
        val d = w.lemmaDownstep
        if (first == null) {
            return when {
                w.headIsLemma -> d
                w.wordClass == InflectingClass.ADJECTIVE && w.headForm == "連用テ接続" -> if (d == 0) 0 else (d - 1).coerceAtLeast(1)
                else -> null
            }
        }
        if (w.headIsLemma) {
            return when (first) {
                "です", "でし" -> if (d == 0) Mora.count(Kana.toHiragana(w.lemmaReading)) + 1 else d
                "だ" -> d
                else -> null
            }
        }
        return when (w.wordClass) {
            InflectingClass.VERB -> when (first) {
                "ます", "まし" -> stemMorae + 1
                "ませ", "ましょ" -> stemMorae + 2
                "た", "だ", "て", "で", "たら", "だら", "たり", "だり" ->
                    if (d == 0) 0 else (if (w.ichidan) d - 1 else d).coerceIn(1, stemMorae.coerceAtLeast(1))
                "ない", "なかっ", "なく", "なけれ" -> if (d == 0) 0 else stemMorae.coerceAtLeast(1)
                else -> null
            }
            InflectingClass.ADJECTIVE -> when {
                w.headForm == "連用タ接続" && first in PAST ->
                    if (d == 0) (Mora.count(Kana.toHiragana(w.lemmaReading)) - 1).coerceAtLeast(1) else (d - 1).coerceAtLeast(1)
                w.headForm == "連用テ接続" && first == "て" -> if (d == 0) 0 else (d - 1).coerceAtLeast(1)
                else -> null
            }
            InflectingClass.OTHER -> null
        }
    }

    private val PAST = setOf("た", "たら", "たり")
}
