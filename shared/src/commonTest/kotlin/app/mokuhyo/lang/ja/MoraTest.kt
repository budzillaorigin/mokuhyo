package app.mokuhyo.lang.ja

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoraTest {

    private val cases = listOf(
        "か" to listOf("か"),
        "きゃ" to listOf("きゃ"),
        "とうきょう" to listOf("と", "う", "きょ", "う"),
        "きって" to listOf("き", "っ", "て"),
        "がっこう" to listOf("が", "っ", "こ", "う"),
        "しんぶん" to listOf("し", "ん", "ぶ", "ん"),
        "ラーメン" to listOf("ラ", "ー", "メ", "ン"),
        "コーヒー" to listOf("コ", "ー", "ヒ", "ー"),
        "ファン" to listOf("ファ", "ン"),
        "ティー" to listOf("ティ", "ー"),
        "ヴァイオリン" to listOf("ヴァ", "イ", "オ", "リ", "ン"),
        "しゅっちょう" to listOf("しゅ", "っ", "ちょ", "う"),
        "りょこう" to listOf("りょ", "こ", "う"),
        "おばあさん" to listOf("お", "ば", "あ", "さ", "ん"),
        "おばさん" to listOf("お", "ば", "さ", "ん"),
        "にっぽん" to listOf("に", "っ", "ぽ", "ん"),
        "ちょっと" to listOf("ちょ", "っ", "と"),
        "ぎゅうにゅう" to listOf("ぎゅ", "う", "にゅ", "う"),
        "チェック" to listOf("チェ", "ッ", "ク"),
        "ウィキ" to listOf("ウィ", "キ"),
        "ゃ" to listOf("ゃ"), // a lone small kana still counts
        "っゃ" to listOf("っ", "ゃ"), // small kana never attach to っ
        "" to emptyList(),
    )

    @Test
    fun splitsIntoMorae() {
        val failures = cases.mapNotNull { (input, expected) ->
            val actual = Mora.split(input)
            if (actual == expected) null else "$input → $actual (expected $expected)"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun countsMorae() {
        assertEquals(4, Mora.count("とうきょう"))
        assertEquals(3, Mora.count("きって"))
        assertEquals(2, Mora.count("にほ"))
        assertEquals(5, Mora.count("おばあさん"))
        assertEquals(4, Mora.count("おばさん"))
        assertEquals(1, Mora.count("ん"))
        assertEquals(0, Mora.count(""))
    }
}
