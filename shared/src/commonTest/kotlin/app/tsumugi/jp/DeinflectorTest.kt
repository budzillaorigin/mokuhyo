package app.tsumugi.jp

import app.tsumugi.jp.WordClass.ADJ_I
import app.tsumugi.jp.WordClass.ADJ_NA
import app.tsumugi.jp.WordClass.GODAN
import app.tsumugi.jp.WordClass.ICHIDAN
import app.tsumugi.jp.WordClass.KURU
import app.tsumugi.jp.WordClass.SURU
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class DeinflectorTest {

    /** Asserts every surface form deinflects to [expected] with [cls] among the candidate's classes. */
    private fun check(expected: String, cls: WordClass, vararg surfaces: String) {
        val failures = surfaces.filterNot { s ->
            Deinflector.deinflect(s).any { it.term == expected && cls in it.wordClasses }
        }
        if (failures.isNotEmpty()) fail("Expected $expected ($cls) from: ${failures.joinToString()}")
    }

    @Test
    fun godanKu() = check(
        "書く", GODAN,
        "書かない", "書きます", "書いた", "書いて", "書かなかった", "書きました", "書きません", "書きませんでした",
        "書ける", "書かれる", "書かせる", "書かされる", "書かせられる", "書こう", "書きましょう", "書け", "書けば",
        "書いたら", "書きたい", "書きたくない", "書きたかった", "書いている", "書いてる", "書いていた", "書いてた",
        "書いています", "書いていました", "書いとく", "書いておく", "書いてある", "書いてしまう", "書いちゃう",
        "書いちゃった", "書かず", "書かずに", "書かなきゃ", "書かなくちゃ", "書かなければ", "書かないで",
        "書きながら", "書きなさい", "書きそう", "書きすぎる", "書きやすい", "書くな", "書いたり", "書いても",
        "書けない", "書けません", "書けなかった", "書かれない", "書かせない",
    )

    @Test
    fun godanGu() = check(
        "泳ぐ", GODAN,
        "泳がない", "泳ぎます", "泳いだ", "泳いで", "泳げる", "泳がれる", "泳がせる", "泳ごう", "泳げ", "泳げば",
        "泳いだら", "泳ぎたい", "泳いでいる", "泳いじゃう", "泳いどく", "泳いでる",
    )

    @Test
    fun godanSu() = check(
        "話す", GODAN,
        "話さない", "話します", "話した", "話して", "話せる", "話される", "話させる", "話そう", "話せ", "話せば",
        "話したら", "話したい", "話している", "話しちゃった", "話させられる", "話さなかった",
    )

    @Test
    fun godanTsu() = check(
        "待つ", GODAN,
        "待たない", "待ちます", "待った", "待って", "待てる", "待たれる", "待たせる", "待とう", "待て", "待てば",
        "待ったら", "待ちたい", "待っている", "待っちゃう", "待たされる", "待たされちゃった",
    )

    @Test
    fun godanNu() = check(
        "死ぬ", GODAN,
        "死なない", "死にます", "死んだ", "死んで", "死ねる", "死なれる", "死なせる", "死のう", "死ね", "死ねば",
        "死んだら", "死にたい", "死んじゃう",
    )

    @Test
    fun godanBu() = check(
        "遊ぶ", GODAN,
        "遊ばない", "遊びます", "遊んだ", "遊んで", "遊べる", "遊ばれる", "遊ばせる", "遊ぼう", "遊べ", "遊べば",
        "遊んだら", "遊びたい", "遊んでいる", "遊んでる",
    )

    @Test
    fun godanMu() = check(
        "読む", GODAN,
        "読まない", "読みます", "読んだ", "読んで", "読める", "読まれる", "読ませる", "読もう", "読め", "読めば",
        "読んだら", "読みたい", "読んでいる", "読んじゃった", "読まされる", "読んどく", "読まれていました",
    )

    @Test
    fun godanRu() = check(
        "帰る", GODAN,
        "帰らない", "帰ります", "帰った", "帰って", "帰れる", "帰られる", "帰らせる", "帰ろう", "帰れ", "帰れば",
        "帰ったら", "帰りたい", "帰っている",
    )

    @Test
    fun godanU() = check(
        "買う", GODAN,
        "買わない", "買います", "買った", "買って", "買える", "買われる", "買わせる", "買おう", "買え", "買えば",
        "買ったら", "買いたい", "買っている", "買っちゃう", "買わされる",
    )

    @Test
    fun godanIrregulars() {
        check("行く", GODAN, "行った", "行って", "行ったら", "行かない", "行きます", "行ける", "行こう", "行け",
            "行っている", "行っちゃった", "行かなきゃ", "行かされていた")
        check("いく", GODAN, "いった", "いって")
        check("ある", GODAN, "ない", "なかった", "あった", "あります", "ありません", "あれば")
        check("問う", GODAN, "問うた", "問うて", "問わない", "問います")
        check("請う", GODAN, "請うた", "請うて")
        check("くださる", GODAN, "くださいます", "ください")
        check("いらっしゃる", GODAN, "いらっしゃいます", "いらっしゃい")
        check("なさる", GODAN, "なさいます")
        check("言う", GODAN, "言っとく", "言った", "言わない")
        check("飲む", GODAN, "飲んじゃう", "飲みたくなかった")
    }

    @Test
    fun ichidan() {
        check(
            "食べる", ICHIDAN,
            "食べない", "食べます", "食べた", "食べて", "食べなかった", "食べました", "食べません", "食べませんでした",
            "食べられる", "食べれる", "食べさせる", "食べさせられる", "食べよう", "食べましょう", "食べろ", "食べよ",
            "食べれば", "食べたら", "食べたい", "食べたくない", "食べたかった", "食べたくなかった", "食べている",
            "食べてる", "食べていた", "食べてた", "食べています", "食べていました", "食べていません", "食べてない",
            "食べちゃう", "食べちゃった", "食べてしまった", "食べとく", "食べておく", "食べてある", "食べず",
            "食べずに", "食べぬ", "食べなきゃ", "食べなくちゃ", "食べなければ", "食べないで", "食べながら",
            "食べなさい", "食べそう", "食べすぎる", "食べすぎた", "食べやすい", "食べにくい", "食べるな",
            "食べさせられなかった", "食べられません", "食べられなかった", "食べさせない", "食べたり", "食べても",
            "食べてみる", "食べてください", "食べてくれる", "食べてもらう", "食べていく", "食べてくる",
        )
        check("見る", ICHIDAN, "見ない", "見ます", "見た", "見て", "見られる", "見れる", "見させる", "見よう",
            "見ろ", "見れば", "見たら", "見たい", "見ている", "見てる", "見ちゃった")
        check("起きる", ICHIDAN, "起きない", "起きます", "起きた", "起きて", "起きられる", "起きろ", "起きれば", "起きよう")
        check("くれる", ICHIDAN, "くれ", "くれない", "くれた")
        check("いる", ICHIDAN, "いない", "います", "いた", "いて")
        check("見せる", ICHIDAN, "見せてもらいました")
    }

    @Test
    fun kuru() {
        check("来る", KURU, "来ない", "来ます", "来た", "来て", "来なかった", "来ました", "来られる", "来れる",
            "来させる", "来させられる", "来よう", "来い", "来れば", "来たら", "来たい", "来ている", "来ちゃう")
        check("くる", KURU, "こない", "きます", "きた", "きて", "こられる", "これる", "こさせる", "こよう", "こい",
            "くれば", "きたら", "きたい", "こなかった", "きている")
        check("やって来る", KURU, "やって来た", "やって来ない")
    }

    @Test
    fun suru() {
        check("する", SURU, "しない", "します", "した", "して", "しなかった", "しました", "しません", "できる",
            "される", "させる", "させられる", "しよう", "しましょう", "しろ", "せよ", "すれば", "したら", "したい",
            "している", "しちゃう", "せず", "せずに", "しなきゃ", "しないで")
        check("勉強する", SURU, "勉強しました", "勉強しない", "勉強した", "勉強して", "勉強できる", "勉強させる",
            "勉強させられる", "勉強しよう", "勉強しろ", "勉強すれば", "勉強したい", "勉強している", "勉強していました",
            "勉強しています", "勉強せずに")
    }

    @Test
    fun adjectiveI() {
        check("高い", ADJ_I, "高くない", "高かった", "高くなかった", "高くて", "高く", "高さ", "高ければ",
            "高かったら", "高そう", "高すぎる", "高いです", "高かったです", "高くないです", "高くありません",
            "高くありませんでした", "高くなかったです")
        check("いい", ADJ_I, "よくない", "よかった", "よくなかった", "よくて", "よく", "よければ", "よかったら",
            "よさそう", "いいです", "よかったです")
        check("かっこいい", ADJ_I, "かっこよかった", "かっこよくない")
        check("ない", ADJ_I, "なかった", "なさそう", "なくて")
        check("楽しい", ADJ_I, "楽しくない", "楽しかった", "楽しくて", "楽しそう", "楽しければ")
        check("寒い", ADJ_I, "寒かった", "寒くない", "寒さ")
        check("大きい", ADJ_I, "大きくない", "大きかった")
        check("美味しい", ADJ_I, "美味しかった", "美味しくない")
    }

    @Test
    fun adjectiveNa() {
        check("綺麗", ADJ_NA, "綺麗だ", "綺麗だった", "綺麗じゃない", "綺麗じゃなかった", "綺麗ではない", "綺麗です",
            "綺麗でした", "綺麗で", "綺麗な", "綺麗に", "綺麗なら", "綺麗じゃありません", "綺麗だったら")
        check("静か", ADJ_NA, "静かだった", "静かな", "静かに", "静かじゃない")
    }

    @Test
    fun chainedAuxiliaries() {
        check("書く", GODAN, "書かせられませんでした", "書けなかったら")
        check("話す", GODAN, "話させてください")
        check("待つ", GODAN, "待たされちゃった")
        check("食べる", ICHIDAN, "食べさせられなかった", "食べられなくて")
        check("読む", GODAN, "読まれていました")
    }

    @Test
    fun reasonsAreOrderedFromDictionaryFormOutward() {
        val d = Deinflector.deinflect("食べさせられなかった").first { it.term == "食べる" && ICHIDAN in it.wordClasses }
        assertEquals(listOf("causative", "passive", "negative", "past"), d.reasons)
    }

    @Test
    fun inputItselfComesFirst() {
        val first = Deinflector.deinflect("猫").first()
        assertEquals("猫", first.term)
        assertTrue(first.reasons.isEmpty())
        assertEquals(WordClass.entries.toSet(), first.wordClasses)
    }

    @Test
    fun emptyInputHasNoCandidates() = assertTrue(Deinflector.deinflect("").isEmpty())
}
