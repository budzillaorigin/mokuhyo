package app.tsumugi.jp

import app.tsumugi.jp.Conjugation.CAUSATIVE_PASSIVE_SHORT
import app.tsumugi.jp.Conjugation.CONDITIONAL_BA
import app.tsumugi.jp.Conjugation.CONDITIONAL_TARA
import app.tsumugi.jp.Conjugation.DESIRE_TAI
import app.tsumugi.jp.Conjugation.IMPERATIVE
import app.tsumugi.jp.Conjugation.NON_PAST_NEGATIVE
import app.tsumugi.jp.Conjugation.PAST
import app.tsumugi.jp.Conjugation.PAST_NEGATIVE
import app.tsumugi.jp.Conjugation.POLITE
import app.tsumugi.jp.Conjugation.POLITE_NEGATIVE
import app.tsumugi.jp.Conjugation.POTENTIAL
import app.tsumugi.jp.Conjugation.TE
import app.tsumugi.jp.Conjugation.VOLITIONAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class ConjugatorTest {

    private fun forms(word: String, cls: WordClass) = Conjugator.conjugate(word, cls) ?: fail("no table for $word")

    @Test
    fun godanEndings() {
        val expected = mapOf(
            "買う" to listOf("買わない", "買います", "買った", "買って", "買える", "買おう", "買え"),
            "書く" to listOf("書かない", "書きます", "書いた", "書いて", "書ける", "書こう", "書け"),
            "泳ぐ" to listOf("泳がない", "泳ぎます", "泳いだ", "泳いで", "泳げる", "泳ごう", "泳げ"),
            "話す" to listOf("話さない", "話します", "話した", "話して", "話せる", "話そう", "話せ"),
            "待つ" to listOf("待たない", "待ちます", "待った", "待って", "待てる", "待とう", "待て"),
            "死ぬ" to listOf("死なない", "死にます", "死んだ", "死んで", "死ねる", "死のう", "死ね"),
            "遊ぶ" to listOf("遊ばない", "遊びます", "遊んだ", "遊んで", "遊べる", "遊ぼう", "遊べ"),
            "読む" to listOf("読まない", "読みます", "読んだ", "読んで", "読める", "読もう", "読め"),
            "帰る" to listOf("帰らない", "帰ります", "帰った", "帰って", "帰れる", "帰ろう", "帰れ"),
        )
        for ((word, rows) in expected) {
            val f = forms(word, WordClass.GODAN)
            assertEquals(rows, listOf(NON_PAST_NEGATIVE, POLITE, PAST, TE, POTENTIAL, VOLITIONAL, IMPERATIVE).map { f[it] }, word)
        }
    }

    @Test
    fun godanIrregulars() {
        assertEquals("行った", forms("行く", WordClass.GODAN)[PAST])
        assertEquals("行って", forms("行く", WordClass.GODAN)[TE])
        assertEquals("ない", forms("ある", WordClass.GODAN)[NON_PAST_NEGATIVE])
        assertEquals("なかった", forms("ある", WordClass.GODAN)[PAST_NEGATIVE])
        assertEquals("問うて", forms("問う", WordClass.GODAN)[TE])
        assertEquals("請うた", forms("請う", WordClass.GODAN)[PAST])
        assertEquals("くださいます", forms("くださる", WordClass.GODAN)[POLITE])
        assertEquals("ください", forms("くださる", WordClass.GODAN)[IMPERATIVE])
        assertEquals("書かされる", forms("書く", WordClass.GODAN)[CAUSATIVE_PASSIVE_SHORT])
        assertNull(forms("話す", WordClass.GODAN)[CAUSATIVE_PASSIVE_SHORT])
    }

    @Test
    fun ichidanKuruSuru() {
        val taberu = forms("食べる", WordClass.ICHIDAN)
        assertEquals("食べられる", taberu[POTENTIAL])
        assertEquals("食べろ", taberu[IMPERATIVE])
        assertEquals("くれ", forms("くれる", WordClass.ICHIDAN)[IMPERATIVE])

        val kuru = forms("くる", WordClass.KURU)
        assertEquals(listOf("こない", "きます", "きた", "こい", "くれば"),
            listOf(NON_PAST_NEGATIVE, POLITE, PAST, IMPERATIVE, CONDITIONAL_BA).map { kuru[it] })
        assertEquals("来ない", forms("来る", WordClass.KURU)[NON_PAST_NEGATIVE])

        val benkyou = forms("勉強する", WordClass.SURU)
        assertEquals(listOf("勉強しない", "勉強します", "勉強できる", "勉強したい"),
            listOf(NON_PAST_NEGATIVE, POLITE, POTENTIAL, DESIRE_TAI).map { benkyou[it] })
    }

    @Test
    fun adjectives() {
        val takai = forms("高い", WordClass.ADJ_I)
        assertEquals(listOf("高くない", "高かった", "高くて", "高ければ", "高かったら"),
            listOf(NON_PAST_NEGATIVE, PAST, TE, CONDITIONAL_BA, CONDITIONAL_TARA).map { takai[it] })
        val ii = forms("いい", WordClass.ADJ_I)
        assertEquals(listOf("よくない", "よかった", "いいです"), listOf(NON_PAST_NEGATIVE, PAST, POLITE).map { ii[it] })
        val kirei = forms("綺麗", WordClass.ADJ_NA)
        assertEquals(listOf("綺麗じゃない", "綺麗だった", "綺麗じゃありません"),
            listOf(NON_PAST_NEGATIVE, PAST, POLITE_NEGATIVE).map { kirei[it] })
    }

    @Test
    fun tableFromJmdictPos() {
        assertEquals("勉強しました", Conjugator.table("勉強", listOf("n", "vs"))!!.toMap()[Conjugation.POLITE_PAST])
        assertEquals("行って", Conjugator.table("行く", listOf("v5k-s", "vi"))!!.toMap()[TE])
        assertEquals("よかった", Conjugator.table("いい", listOf("adj-ix"))!!.toMap()[PAST])
        assertNull(Conjugator.table("猫", listOf("n")))
    }

    @Test
    fun fromJmdictPos() {
        assertEquals(WordClass.GODAN, WordClass.fromJmdictPos("v5k-s"))
        assertEquals(WordClass.ICHIDAN, WordClass.fromJmdictPos("v1"))
        assertEquals(WordClass.KURU, WordClass.fromJmdictPos("vk"))
        assertEquals(WordClass.SURU, WordClass.fromJmdictPos("vs"))
        assertEquals(WordClass.ADJ_I, WordClass.fromJmdictPos("adj-ix"))
        assertEquals(WordClass.ADJ_NA, WordClass.fromJmdictPos("adj-na"))
        assertNull(WordClass.fromJmdictPos("n"))
    }

    /** Every generated form must deinflect back to its dictionary form with the right class. */
    @Test
    fun roundTripThroughDeinflector() {
        val words = listOf(
            "買う" to WordClass.GODAN, "言う" to WordClass.GODAN, "書く" to WordClass.GODAN, "歩く" to WordClass.GODAN,
            "泳ぐ" to WordClass.GODAN, "急ぐ" to WordClass.GODAN, "話す" to WordClass.GODAN, "出す" to WordClass.GODAN,
            "待つ" to WordClass.GODAN, "持つ" to WordClass.GODAN, "死ぬ" to WordClass.GODAN, "遊ぶ" to WordClass.GODAN,
            "呼ぶ" to WordClass.GODAN, "読む" to WordClass.GODAN, "飲む" to WordClass.GODAN, "帰る" to WordClass.GODAN,
            "取る" to WordClass.GODAN, "行く" to WordClass.GODAN, "ある" to WordClass.GODAN, "問う" to WordClass.GODAN,
            "くださる" to WordClass.GODAN, "なさる" to WordClass.GODAN, "いらっしゃる" to WordClass.GODAN,
            "食べる" to WordClass.ICHIDAN, "見る" to WordClass.ICHIDAN, "起きる" to WordClass.ICHIDAN,
            "教える" to WordClass.ICHIDAN, "くれる" to WordClass.ICHIDAN,
            "来る" to WordClass.KURU, "くる" to WordClass.KURU,
            "する" to WordClass.SURU, "勉強する" to WordClass.SURU, "運転する" to WordClass.SURU,
            "高い" to WordClass.ADJ_I, "楽しい" to WordClass.ADJ_I, "いい" to WordClass.ADJ_I, "かっこいい" to WordClass.ADJ_I,
            "ない" to WordClass.ADJ_I,
            "綺麗" to WordClass.ADJ_NA, "静か" to WordClass.ADJ_NA, "元気" to WordClass.ADJ_NA,
        )
        val failures = mutableListOf<String>()
        for ((word, cls) in words) {
            for ((row, surface) in forms(word, cls)) {
                val ok = Deinflector.deinflect(surface).any { it.term == word && cls in it.wordClasses }
                if (!ok) failures += "$word/$row=$surface"
            }
        }
        if (failures.isNotEmpty()) fail("Round-trip failures (${failures.size}): ${failures.joinToString()}")
    }
}
