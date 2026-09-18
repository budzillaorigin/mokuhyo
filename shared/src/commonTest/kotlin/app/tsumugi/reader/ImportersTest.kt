package app.tsumugi.reader

import app.tsumugi.integrations.anki.Zip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportersTest {

    /** A made-up easy-news style page (our own text, not copied from any site). */
    private val page = """
        <!DOCTYPE html>
        <html lang="ja"><head><meta charset="utf-8"><title>やさしいニュース | サンプル</title>
        <meta property="og:title" content="町に新しい図書館ができました">
        <script>var x = "スクリプトの文字";</script><style>p { color: red }</style></head>
        <body>
          <header><nav><ul><li><a href="/">ホーム</a></li><li><a href="/news">ニュース一覧をみる</a></li></ul></nav></header>
          <div class="wrap">
            <aside>人気の記事ランキングはこちらです</aside>
            <article>
              <h1>町に新しい図書館ができました</h1>
              <p><ruby>町<rt>まち</rt></ruby>に<ruby>新<rp>(</rp><rt>あたら</rt><rp>)</rp></ruby>しい<ruby>図書館<rt>としょかん</rt></ruby>ができました。本がたくさんあります。</p>
              <p>子どもから大人まで、だれでも無料で使うことができます。&nbsp;開いている時間は朝九時から夜八時までです。</p>
              <p>図書館の人は「たくさんの人に来てほしい」と話していました。</p>
            </article>
          </div>
          <footer><p>このサイトについて・お問い合わせはこちらまでどうぞ</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun extractsArticleWithoutChromeOrRubyReadings() {
        val a = WebArticleExtractor.extract(page)
        assertEquals("町に新しい図書館ができました", a.title)
        val lines = a.body.lines()
        assertEquals(3, lines.size, a.body)
        assertEquals("町に新しい図書館ができました。本がたくさんあります。", lines[0])
        assertTrue(lines[1].startsWith("子どもから大人まで"))
        assertFalse("ホーム" in a.body || "ランキング" in a.body || "お問い合わせ" in a.body || "スクリプト" in a.body)
        assertFalse("まち" in lines[0] || "としょかん" in a.body, "ruby readings dropped")
    }

    @Test
    fun entitiesAndCharsets() {
        assertEquals("A&B <x> ☃ 𠮷  ", Html.decodeEntities("A&amp;B &lt;x&gt; &#9731; &#x20BB7; &nbsp;"))
        assertEquals("Shift_JIS", detectCharset(ByteArray(0), "text/html; charset=Shift_JIS"))
        assertEquals("EUC-JP", detectCharset("<html><head><meta charset=\"euc-jp\">".encodeToByteArray(), null))
        assertEquals("Shift_JIS", detectCharset("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=x-sjis\">".encodeToByteArray(), "text/html"))
        assertEquals("UTF-8", detectCharset("<p>hi</p>".encodeToByteArray(), null))
        // 日本語 in Shift_JIS.
        val sjis = byteArrayOf(0x93.toByte(), 0xFA.toByte(), 0x96.toByte(), 0x7B, 0x8C.toByte(), 0xEA.toByte())
        assertEquals("日本語", decodeText(sjis, "Shift_JIS"))
    }

    @Test
    fun pastedText() {
        val t = TextImporter.import("  今日は  いい天気ですね。\n\n\n散歩に行きましょう。 ")
        assertEquals("今日は いい天気ですね。\n散歩に行きましょう。", t.body)
        assertEquals("今日は いい天気ですね。", t.title)
        assertEquals(SourceKind.PASTE, t.kind)
        assertEquals("あいうえおかきくけこさしすせそたちつてと…", TextImporter.defaultTitle("あいうえおかきくけこさしすせそたちつてとなにぬねの"))
    }

    @Test
    fun rss2() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel>
            <title>やさしい町のニュース</title><link>https://example.jp/</link>
            <item><title>図書館ができました</title><link>https://example.jp/a/1</link>
              <pubDate>Mon, 14 Sep 2026 09:00:00 +0900</pubDate>
              <description><![CDATA[<p>新しい<b>図書館</b>です。</p>]]></description></item>
            <item><title>雨の日&amp;風の日</title><link>https://example.jp/a/2</link><description>&lt;p&gt;明日は雨です。&lt;/p&gt;</description></item>
            </channel></rss>"""
        val feed = FeedParser.parse(xml)
        assertEquals("やさしい町のニュース", feed.title)
        assertEquals(listOf("https://example.jp/a/1", "https://example.jp/a/2"), feed.items.map { it.link })
        assertEquals("雨の日&風の日", feed.items[1].title)
        assertEquals("新しい図書館です。", feed.items[0].summary)
        assertEquals("明日は雨です。", feed.items[1].summary)
        assertEquals("Mon, 14 Sep 2026 09:00:00 +0900", feed.items[0].published)
    }

    @Test
    fun atomAndRdf() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title type="text">ブログ</title>
            <entry><title>最初の記事</title><link rel="alternate" href="https://blog.example/1"/>
            <updated>2026-09-01T10:00:00Z</updated><summary>こんにちは</summary></entry></feed>"""
        val a = FeedParser.parse(atom)
        assertEquals("ブログ", a.title)
        assertEquals(FeedItem("最初の記事", "https://blog.example/1", "2026-09-01T10:00:00Z", "こんにちは"), a.items.single())

        val rdf = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/">
            <channel rdf:about="https://x.example/"><title>RDFの例</title></channel>
            <item rdf:about="https://x.example/9"><title>九番</title><link>https://x.example/9</link><dc:date>2026-09-02</dc:date></item>
            </rdf:RDF>"""
        val r = FeedParser.parse(rdf)
        assertEquals("RDFの例", r.title)
        assertEquals("https://x.example/9", r.items.single().link)
        assertEquals("2026-09-02", r.items.single().published)
    }

    /** Opening of 宮沢賢治「注文の多い料理店」(public domain, Aozora Bunko), in Aozora's text format. */
    private val aozora = """
        注文の多い料理店
        宮沢賢治

        -------------------------------------------------------
        【テキスト中に現れる記号について】

        《》：ルビ
        （例）紳士《しんし》

        ｜：ルビの付く文字列の始まりを特定する記号
        -------------------------------------------------------

        ［＃３字下げ］注文の多い料理店［＃「注文の多い料理店」は中見出し］

        　二人の若い紳士《しんし》が、すっかりイギリスの兵隊のかたちをして、ぴかぴかする鉄砲《てっぽう》をかついで、
        　白熊《しろくま》のような犬を二疋《ひき》つれて、だいぶ｜山奥《やまおく》の、木の葉のかさかさしたとこを、こんなことを云《い》いながら、あるいておりました。

        底本：「注文の多い料理店」新潮文庫、新潮社
        入力：（サンプル）
    """.trimIndent()

    @Test
    fun aozoraText() {
        val t = AozoraImporter.parseText(aozora)
        assertEquals("注文の多い料理店", t.title)
        assertEquals("宮沢賢治", t.author)
        val lines = t.body.lines()
        assertEquals("注文の多い料理店", lines[0], "annotations removed")
        assertTrue(lines[1].startsWith("　二人の若い紳士が、"))
        assertFalse("《" in t.body || "｜" in t.body || "［＃" in t.body || "底本" in t.body || "記号" in t.body)
        val shinshi = t.ruby.first { it.base == "紳士" }
        assertEquals("しんし", shinshi.reading)
        assertEquals("紳士", t.body.substring(shinshi.start, shinshi.start + 2))
        val yamaoku = t.ruby.first { it.reading == "やまおく" }
        assertEquals("山奥", yamaoku.base)
        assertEquals("山奥", t.body.substring(yamaoku.start, yamaoku.start + 2))
        assertTrue(t.ruby.any { it.base == "二疋" && it.reading == "ひき" })
    }

    @Test
    fun aozoraCatalogue() {
        val csv = "﻿作品ID,作品名,作品名読み,姓,名,作品著作権フラグ,テキストファイルURL\n" +
            "000001,注文の多い料理店,ちゅうもんのおおいりょうりてん,宮沢,賢治,なし,https://www.aozora.gr.jp/cards/000081/files/43754_ruby_17594.zip\n" +
            "000002,\"テスト, 作品\",てすと,山田,太郎,あり,https://example/2.zip\n" +
            "000003,テキストなし,,鈴木,花子,なし,\n"
        val works = AozoraImporter.parseCatalogue(csv)
        assertEquals(1, works.size)
        assertEquals(AozoraWork("000001", "注文の多い料理店", "宮沢賢治", "https://www.aozora.gr.jp/cards/000081/files/43754_ruby_17594.zip", "ちゅうもんのおおいりょうりてん"), works[0])
        assertEquals(listOf("a", "b, c", "d\"e"), Csv.parse("a,\"b, c\",\"d\"\"e\"\n").single())
    }

    @Test
    fun aozoraZipIsShiftJis() {
        // "猫《ねこ》" + newline in Shift_JIS inside a zip, as Aozora ships works.
        val text = byteArrayOf(0x94.toByte(), 0x4C, 0x81.toByte(), 0x73, 0x82.toByte(), 0xCB.toByte(), 0x82.toByte(), 0xB1.toByte(), 0x81.toByte(), 0x74, 0x0A)
        val zip = Zip.write(listOf("work.txt" to ("題\n作者\n\n".encodeToSjisAscii() + text)))
        val t = AozoraImporter.textFromZip(zip)
        assertEquals("猫", t.body)
        assertEquals(RubyHint(0, "猫", "ねこ"), t.ruby.single())
    }

    /** ASCII-only helper plus the two kanji lines (題 = 91 E8, 作者 = 8D EC 8E D2) in Shift_JIS. */
    private fun String.encodeToSjisAscii(): ByteArray =
        byteArrayOf(0x91.toByte(), 0xE8.toByte(), 0x0A, 0x8D.toByte(), 0xEC.toByte(), 0x8E.toByte(), 0xD2.toByte(), 0x0A, 0x0A)

    @Test
    fun epub() {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>小さな本</dc:title><dc:creator>作者名</dc:creator></metadata>
            <manifest><item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
            <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/><item id="css" href="style.css" media-type="text/css"/></manifest>
            <spine page-progression-direction="rtl"><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        fun chapter(body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>章</title></head>
            <body class="vrtl"><p>$body</p><br/></body></html>"""
        val epub = Zip.write(listOf(
            "mimetype" to "application/epub+zip".encodeToByteArray(),
            "META-INF/container.xml" to container.encodeToByteArray(),
            "OEBPS/content.opf" to opf.encodeToByteArray(),
            "OEBPS/text/ch1.xhtml" to chapter("第一章。<ruby>猫<rt>ねこ</rt></ruby>がいた。").encodeToByteArray(),
            "OEBPS/text/ch 2.xhtml" to chapter("第二章。犬もいた。").encodeToByteArray(),
        ))
        val t = EpubImporter.import(epub)
        assertEquals("小さな本", t.title)
        assertEquals("作者名", t.author)
        assertEquals("第一章。猫がいた。\n第二章。犬もいた。", t.body)
        assertEquals("OEBPS/a/c.xhtml", EpubImporter.resolve("OEBPS/text/", "../a/c.xhtml#frag"))
    }

    @Test
    fun drmEpubIsRejected() {
        val epub = Zip.write(listOf(
            "META-INF/encryption.xml" to "<encryption><EncryptedData><CipherData><CipherReference URI=\"OEBPS/ch1.xhtml\"/></CipherData></EncryptedData></encryption>".encodeToByteArray(),
            "META-INF/container.xml" to "<container/>".encodeToByteArray(),
        ))
        assertFailsWith<ImportException> { EpubImporter.import(epub) }
    }
}
