package app.mokuhyo.lang.ja

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanaConversionTest {

    private fun checkAll(cases: List<Pair<String, String>>, convert: (String) -> String) {
        val failures = cases.mapNotNull { (input, expected) ->
            val actual = convert(input)
            if (actual == expected) null else "$input → $actual (expected $expected)"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // --- Kana ---------------------------------------------------------------------------------------

    @Test
    fun foldsKatakanaToHiragana() = checkAll(
        listOf(
            "カタカナ" to "かたかな",
            "ラーメン" to "らーめん", // ー is not folded
            "ヴァイオリン" to "ゔぁいおりん",
            "ヶ月" to "ゖ月", // ヶ (U+30F6) is the last folded code point
            "ヷ" to "ヷ", // U+30F7 is outside the fold range, like common.py
            "ｶﾀｶﾅ" to "ｶﾀｶﾅ", // half-width is never normalized
            "漢字とカナ" to "漢字とかな",
            "abc" to "abc",
            "" to "",
        ),
        Kana::toHiragana,
    )

    @Test
    fun hiraganaToKatakana() = checkAll(
        listOf(
            "ひらがな" to "ヒラガナ",
            "きゃっと" to "キャット",
            "ゔ" to "ヴ",
            "らーめん" to "ラーメン",
            "漢字" to "漢字",
        ),
        Kana::toKatakana,
    )

    @Test
    fun classifiesCharacters() {
        assertTrue(Kana.isHiragana('あ'))
        assertTrue(Kana.isHiragana('ゞ'))
        assertFalse(Kana.isHiragana('ア'))
        assertTrue(Kana.isKatakana('ア'))
        assertTrue(Kana.isKatakana('ー'))
        assertTrue(Kana.isKatakana('ヾ'))
        assertFalse(Kana.isKatakana('・'))
        assertTrue(Kana.isKana('ん'))
        assertFalse(Kana.isKana('漢'))
        assertTrue(Kana.isKanji('漢'.code))
        assertTrue(Kana.isKanji('々'.code))
        assertTrue(Kana.isKanji('〆'.code))
        assertTrue(Kana.isKanji(0x3400))
        assertTrue(Kana.isKanji(0x20B9F)) // 𠮟 (Ext B)
        assertFalse(Kana.isKanji('あ'.code))
        assertFalse(Kana.isKanji('Ａ'.code))
    }

    @Test
    fun stringPredicates() {
        assertTrue(Kana.containsKanji("食べる"))
        assertTrue(Kana.containsKanji("𠮟る")) // 𠮟る via surrogate pair
        assertFalse(Kana.containsKanji("たべる"))
        assertTrue(Kana.isAllKana("たべる"))
        assertTrue(Kana.isAllKana("コーヒー"))
        assertFalse(Kana.isAllKana(""))
        assertFalse(Kana.isAllKana("食べる"))
        assertTrue(Kana.isJapanese("hello 世界"))
        assertTrue(Kana.isJapanese("。"))
        assertTrue(Kana.isJapanese("ｶ"))
        assertFalse(Kana.isJapanese("hello"))
    }

    // --- Romaji → kana ------------------------------------------------------------------------------

    @Test
    fun romajiToHiraganaHepburn() = checkAll(
        listOf(
            "aiueo" to "あいうえお",
            "sushi" to "すし",
            "chichi" to "ちち",
            "tsunami" to "つなみ",
            "fuji" to "ふじ",
            "shashin" to "しゃしん",
            "kyou" to "きょう",
            "ryokou" to "りょこう",
            "jagaimo" to "じゃがいも",
            "benkyou" to "べんきょう",
            "gyuunyuu" to "ぎゅうにゅう",
            "hyaku" to "ひゃく",
            "myaku" to "みゃく",
            "byouin" to "びょういん",
            "happyou" to "はっぴょう",
            "wo" to "を",
            "watashi" to "わたし",
        ),
        Romaji::toHiragana,
    )

    @Test
    fun romajiToHiraganaKunreiAndNihonShiki() = checkAll(
        listOf(
            "si" to "し",
            "ti" to "ち",
            "tu" to "つ",
            "hu" to "ふ",
            "zi" to "じ",
            "di" to "ぢ",
            "du" to "づ",
            "dzu" to "づ",
            "sya" to "しゃ",
            "tya" to "ちゃ",
            "cya" to "ちゃ",
            "zya" to "じゃ",
            "jya" to "じゃ",
            "zyo" to "じょ",
            "tyotto" to "ちょっと",
        ),
        Romaji::toHiragana,
    )

    @Test
    fun romajiN() = checkAll(
        listOf(
            "hon" to "ほん",
            "honya" to "ほにゃ",
            "hon'ya" to "ほんや",
            "honnya" to "ほんや",
            "kanji" to "かんじ",
            "konnichiwa" to "こんにちわ",
            "konnnichiwa" to "こんにちわ",
            "onna" to "おんな",
            "kannna" to "かんな",
            "shinbun" to "しんぶん",
            "sannin" to "さんにん",
            "nn" to "ん",
            "n" to "ん",
            "ninja" to "にんじゃ",
        ),
        Romaji::toHiragana,
    )

    @Test
    fun romajiSokuonSmallKanaAndSymbols() = checkAll(
        listOf(
            "kitte" to "きって",
            "gakkou" to "がっこう",
            "matcha" to "まっちゃ",
            "macchi" to "まっち",
            "zasshi" to "ざっし",
            "xa" to "ぁ",
            "la" to "ぁ",
            "xtu" to "っ",
            "ltu" to "っ",
            "xtsu" to "っ",
            "xya" to "ゃ",
            "xwa" to "ゎ",
            "ra-men" to "らーめん",
            "fa" to "ふぁ",
            "fo-ku" to "ふぉーく",
            "vaiorin" to "ゔぁいおりん",
            "vu" to "ゔ",
            "wi" to "うぃ",
            "we" to "うぇ",
            "ye" to "いぇ",
            "thi" to "てぃ",
            "che" to "ちぇ",
            "she" to "しぇ",
            "je" to "じぇ",
        ),
        Romaji::toHiragana,
    )

    @Test
    fun romajiUppercaseGivesKatakanaAndPassesThroughOthers() = checkAll(
        listOf(
            "KOOHII" to "コオヒイ",
            "RA-MEN" to "ラーメン",
            "PAN" to "パン",
            "KITTO" to "キット",
            "Shi" to "シ",
            "tabeta!" to "たべた!",
            "123" to "123",
            "食べru" to "食べる",
            "q" to "q",
        ),
        Romaji::toHiragana,
    )

    // --- IME ----------------------------------------------------------------------------------------

    @Test
    fun imeKeepsIncompleteTail() {
        assertEquals(ImeResult("", "k"), Romaji.imeConvert("k"))
        assertEquals(ImeResult("", "sh"), Romaji.imeConvert("sh"))
        assertEquals(ImeResult("し", ""), Romaji.imeConvert("shi"))
        assertEquals(ImeResult("", "ky"), Romaji.imeConvert("ky"))
        assertEquals(ImeResult("た", "n"), Romaji.imeConvert("tan"))
        assertEquals(ImeResult("た", "nn"), Romaji.imeConvert("tann"))
        assertEquals(ImeResult("たん", "k"), Romaji.imeConvert("tank"))
        assertEquals(ImeResult("き", "t"), Romaji.imeConvert("kit"))
        assertEquals(ImeResult("きっ", "t"), Romaji.imeConvert("kitt"))
        assertEquals(ImeResult("ま", "tc"), Romaji.imeConvert("matc"))
        assertEquals(ImeResult("", "xt"), Romaji.imeConvert("xt"))
        assertEquals(ImeResult("", "ts"), Romaji.imeConvert("ts"))
        assertEquals(ImeResult("", "dz"), Romaji.imeConvert("dz"))
        assertEquals("たn", Romaji.imeConvert("tan").text)
    }

    @Test
    fun imeTypingSequenceProducesWord() {
        // Simulates a text field: each keystroke re-runs the IME on the field's current content.
        fun type(word: String): String {
            var field = ""
            for (c in word) field = Romaji.imeConvert(field + c).text
            return Romaji.finalize(field)
        }
        assertEquals("こんにちわ", type("konnichiwa"))
        assertEquals("おんな", type("onna"))
        assertEquals("ほん", type("hon"))
        assertEquals("ほんや", type("hon'ya"))
        assertEquals("がっこう", type("gakkou"))
        assertEquals("まっちゃ", type("matcha"))
        assertEquals("しんぶん", type("shinbun"))
        assertEquals("カタカナ", type("KATAKANA"))
    }

    @Test
    fun imeFinalize() {
        assertEquals("ほん", Romaji.finalize("hon"))
        assertEquals("ほん", Romaji.finalize("honn"))
        assertEquals("ホン", Romaji.finalize("HON"))
        assertEquals("すk", Romaji.finalize("suk")) // other leftovers are kept as typed
        assertEquals("すし", Romaji.finalize("すし"))
    }

    // --- Kana → romaji ------------------------------------------------------------------------------

    @Test
    fun kanaToHepburn() = checkAll(
        listOf(
            "すし" to "sushi",
            "ちち" to "chichi",
            "つなみ" to "tsunami",
            "ふじ" to "fuji",
            "しゃしん" to "shashin",
            "きょう" to "kyou",
            "がっこう" to "gakkou",
            "まっちゃ" to "matcha",
            "ほんや" to "hon'ya",
            "げんいん" to "gen'in",
            "しんぶん" to "shinbun",
            "ラーメン" to "ra-men",
            "ヴァイオリン" to "vaiorin",
            "ぢ" to "ji",
            "づ" to "zu",
            "を" to "wo",
            "ティー" to "ti-",
            "ファン" to "fan",
            "ちぇっく" to "chekku",
        ),
        Romaji::fromKana,
    )

    @Test
    fun romajiRoundTrip() {
        val words = listOf("べんきょう", "がっこう", "しんぶん", "ほんや", "りょこう", "まっちゃ", "こんにちわ", "ふじさん")
        for (w in words) assertEquals(w, Romaji.toHiragana(Romaji.fromKana(w)), w)
    }
}
