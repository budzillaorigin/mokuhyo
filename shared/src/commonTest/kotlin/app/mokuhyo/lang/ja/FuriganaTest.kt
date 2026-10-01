package app.mokuhyo.lang.ja

import kotlin.test.Test
import kotlin.test.assertEquals

class FuriganaTest {

    private fun seg(ruby: String, rt: String? = null) = FuriganaSegment(ruby, rt)

    @Test
    fun parsesPackJson() {
        assertEquals(
            listOf(seg("漢", "かん"), seg("字", "じ")),
            Furigana.parseSegments("""[{"ruby":"漢","rt":"かん"},{"ruby":"字","rt":"じ"}]"""),
        )
        assertEquals(
            listOf(seg("食", "た"), seg("べ"), seg("物", "もの")),
            Furigana.parseSegments("""[{"ruby":"食","rt":"た"},{"ruby":"べ"},{"ruby":"物","rt":"もの"}]"""),
        )
        assertEquals(emptyList(), Furigana.parseSegments("[]"))
        assertEquals(listOf(seg("お")), Furigana.parseSegments("""[{"ruby":"お","extra":1}]"""))
    }

    @Test
    fun alignsKanjiAroundKanaAnchors() {
        assertEquals(listOf(seg("食", "た"), seg("べ"), seg("物", "もの")), Furigana.align("食べ物", "たべもの"))
        assertEquals(listOf(seg("お"), seg("茶", "ちゃ")), Furigana.align("お茶", "おちゃ"))
        assertEquals(
            listOf(seg("取", "と"), seg("り"), seg("扱", "あつか"), seg("い")),
            Furigana.align("取り扱い", "とりあつかい"),
        )
        assertEquals(listOf(seg("行", "い"), seg("って")), Furigana.align("行って", "いって"))
        assertEquals(listOf(seg("食", "た"), seg("べる")), Furigana.align("食べる", "たべる"))
        assertEquals(listOf(seg("見", "み"), seg("る")), Furigana.align("見る", "みる"))
        assertEquals(
            listOf(seg("思", "おも"), seg("い"), seg("出", "で")),
            Furigana.align("思い出", "おもいで"),
        )
        assertEquals(
            listOf(seg("日本", "にほん"), seg("の"), seg("本", "ほん")),
            Furigana.align("日本の本", "にほんのほん"),
        )
    }

    @Test
    fun singleKanjiRunCannotBeSplitWithoutData() {
        assertEquals(listOf(seg("漢字", "かんじ")), Furigana.align("漢字", "かんじ"))
        assertEquals(listOf(seg("今日", "きょう")), Furigana.align("今日", "きょう"))
    }

    @Test
    fun kanaOnlyTextNeedsNoRuby() {
        assertEquals(listOf(seg("たべる")), Furigana.align("たべる", "たべる"))
        assertEquals(listOf(seg("コーヒー")), Furigana.align("コーヒー", "こーひー"))
    }

    @Test
    fun katakanaInsensitiveAndKeepsReadingScript() {
        assertEquals(listOf(seg("お"), seg("茶", "チャ")), Furigana.align("お茶", "オチャ"))
        assertEquals(listOf(seg("ドイツ"), seg("語", "ご")), Furigana.align("ドイツ語", "どいつご"))
    }

    @Test
    fun fallsBackToSingleSegment() {
        assertEquals(listOf(seg("食べる", "のむ")), Furigana.align("食べる", "のむ")) // unmatchable
        assertEquals(listOf(seg("子の子", "こののこ")), Furigana.align("子の子", "こののこ")) // ambiguous
        assertEquals(emptyList(), Furigana.align("", ""))
    }

    @Test
    fun handlesSurrogatePairKanji() {
        assertEquals(listOf(seg("𠮟", "しか"), seg("る")), Furigana.align("𠮟る", "しかる"))
    }
}
