package app.tsumugi.speaking

import app.tsumugi.jp.InflectedWord
import app.tsumugi.jp.InflectingClass
import app.tsumugi.jp.PitchRules
import app.tsumugi.jp.tokenizer.Morpheme
import app.tsumugi.jp.tokenizer.MorphologicalAnalyzer
import app.tsumugi.jp.tokenizer.baseReading
import app.tsumugi.speech.WordTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F-21: auxiliaries stay in the target, and conjugated words get their accent from the lemma. */
class PronunciationTargetsTest {
    /** IPADIC-shaped morphemes (what LatticeTokenizer returns for these words). */
    private fun m(
        surface: String, pos: List<String>, base: String, reading: String,
        type: String? = null, form: String? = null,
    ) = Morpheme(surface, 0, surface.length, pos, type, form, base, reading, reading, false)

    private val tabe = m("食べ", listOf("動詞", "自立"), "食べる", "タベ", "一段", "連用形")
    private val masu = m("ます", listOf("助動詞"), "ます", "マス", "特殊・マス", "基本形")
    private val mashi = m("まし", listOf("助動詞"), "ます", "マシ", "特殊・マス", "連用形")
    private val ta = m("た", listOf("助動詞"), "た", "タ", "特殊・タ", "基本形")
    private val takakat = m("高かっ", listOf("形容詞", "自立"), "高い", "タカカッ", "形容詞・アウオ段", "連用タ接続")
    private val hon = m("本", listOf("名詞", "一般"), "本", "ホン")
    private val wo = m("を", listOf("助詞", "格助詞", "一般"), "を", "ヲ")
    private val yon = m("読ん", listOf("動詞", "自立"), "読む", "ヨン", "五段・マ行", "連用タ接続")
    private val da = m("だ", listOf("助動詞"), "だ", "ダ", "特殊・タ", "基本形")
    private val kuten = m("。", listOf("記号", "句点"), "。", "。")

    /** Kanjium-style accents for dictionary forms only: the inflected surface is never in the table. */
    private val accents = mapOf(("食べる" to "たべる") to 2, ("高い" to "たかい") to 2, ("本" to "ほん") to 1, ("読む" to "よむ") to 1)
    private val pitch: suspend (String, String) -> List<Int> = { w, r -> listOfNotNull(accents[w to r]) }

    private suspend fun targets(vararg ms: Morpheme): List<WordTarget> {
        val analyzer = object : MorphologicalAnalyzer {
            override suspend fun analyze(text: String) = ms.toList()
        }
        return PronunciationService({ analyzer }, { null }, pitch).targets("unused")
    }

    @Test
    fun tabemasuKeepsMasuAndDropsAfterMa() = runTest {
        // たべま↓す: the drop is after ま (mora 3), whatever the verb's own accent.
        assertEquals(listOf(WordTarget("たべます", 3)), targets(tabe, masu, kuten))
    }

    @Test
    fun tabemashita() = runTest {
        assertEquals(listOf(WordTarget("たべました", 3)), targets(tabe, mashi, ta))
    }

    @Test
    fun takakatta() = runTest {
        // たか↓い → た↓かかった: the accent moves one mora towards the start.
        assertEquals(listOf(WordTarget("たかかった", 1)), targets(takakat, ta))
    }

    @Test
    fun onlyParticlesAttachToThePreviousWord() = runTest {
        assertEquals(
            listOf(WordTarget("ほん", 1, followedByParticle = true), WordTarget("よんだ", 1)),
            targets(hon, wo, yon, da, kuten),
        )
    }

    @Test
    fun unknownLemmaGivesUnknownAccent() = runTest {
        val kaki = m("書き", listOf("動詞", "自立"), "書く", "カキ", "五段・カ行イ音便", "連用形")
        assertEquals(listOf(WordTarget("かきます", null)), targets(kaki, masu))
    }

    @Test
    fun lemmaReadingSwapsTheInflectedTail() {
        assertEquals("たべる", tabe.baseReading())
        assertEquals("たかい", takakat.baseReading())
        assertEquals("よむ", yon.baseReading())
        assertEquals("くる", m("来", listOf("動詞", "自立"), "来る", "キ").baseReading())
    }

    @Test
    fun ruleTable() {
        fun verb(d: Int, stem: String, ichidan: Boolean, vararg endings: String) =
            PitchRules.downstep(InflectedWord(InflectingClass.VERB, d, "", stem, headIsLemma = false, ichidan = ichidan, endings = endings.toList()))
        assertEquals(1, verb(2, "たべ", true, "た"))       // た↓べた
        assertEquals(1, verb(1, "かい", false, "た"))      // か↓いた
        assertEquals(2, verb(2, "はなし", false, "た"))    // はな↓した
        assertEquals(0, verb(0, "いっ", false, "た"))      // いった (heiban)
        assertEquals(2, verb(2, "たべ", true, "ない"))     // たべ↓ない
        assertEquals(4, verb(2, "たべ", true, "ませ", "ん")) // たべませ↓ん
        assertNull(verb(2, "たべ", true, "らしい"))
        fun adj(d: Int, lemma: String, form: String, vararg endings: String) =
            PitchRules.downstep(InflectedWord(InflectingClass.ADJECTIVE, d, lemma, "", headIsLemma = false, headForm = form, endings = endings.toList()))
        assertEquals(2, adj(0, "あかい", "連用タ接続", "た"))   // あか↓かった
        assertEquals(1, adj(2, "たかい", "連用テ接続", "て"))   // た↓かくて
        // Copula after a dictionary form.
        assertEquals(5, PitchRules.downstep(InflectedWord(InflectingClass.OTHER, 0, "がくせい", "がくせい", headIsLemma = true, endings = listOf("です"))))
        assertEquals(1, PitchRules.downstep(InflectedWord(InflectingClass.OTHER, 1, "ねこ", "ねこ", headIsLemma = true, endings = listOf("です"))))
    }
}
