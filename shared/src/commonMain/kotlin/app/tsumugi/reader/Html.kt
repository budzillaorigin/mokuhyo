package app.tsumugi.reader

/** A tolerant HTML/XHTML element tree: children are [HtmlNode]s or text [String]s (entities already decoded). */
internal class HtmlNode(val tag: String, val attrs: Map<String, String>, val parent: HtmlNode?) {
    val children = ArrayList<Any>()

    fun elements(): Sequence<HtmlNode> = sequence {
        for (c in children) if (c is HtmlNode) {
            yield(c)
            yieldAll(c.elements())
        }
    }

    fun first(tag: String): HtmlNode? = elements().firstOrNull { it.tag == tag }
}

/** Small forgiving HTML parser and text utilities (no platform XML/HTML library needed). */
internal object Html {
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    private val RAW_TEXT = setOf("script", "style", "textarea", "title", "noscript")
    private val CLOSES_P = setOf(
        "p", "div", "ul", "ol", "table", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "header", "footer",
    )
    private val ATTR = Regex("""([^\s=/>"']+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?""")

    fun parse(html: String): HtmlNode {
        val root = HtmlNode("#root", emptyMap(), null)
        var current = root
        var i = 0
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                current.children += decodeEntities(text.toString())
                text.clear()
            }
        }
        while (i < html.length) {
            val c = html[i]
            if (c != '<') { text.append(c); i++; continue }
            when {
                html.startsWith("<!--", i) -> {
                    flushText()
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) html.length else end + 3
                }
                html.startsWith("<![CDATA[", i) -> {
                    val end = html.indexOf("]]>", i + 9)
                    val stop = if (end < 0) html.length else end
                    flushText()
                    current.children += html.substring(i + 9, stop)
                    i = if (end < 0) html.length else end + 3
                }
                i + 1 < html.length && (html[i + 1] == '!' || html[i + 1] == '?') -> {
                    flushText()
                    val end = html.indexOf('>', i)
                    i = if (end < 0) html.length else end + 1
                }
                i + 1 < html.length && html[i + 1] == '/' -> {
                    flushText()
                    val end = html.indexOf('>', i)
                    val name = html.substring(i + 2, if (end < 0) html.length else end).trim().lowercase().substringAfter(':')
                    var n: HtmlNode? = current
                    while (n != null && n.tag != name) n = n.parent
                    if (n != null && n.parent != null) current = n.parent
                    i = if (end < 0) html.length else end + 1
                }
                i + 1 < html.length && html[i + 1].isLetter() -> {
                    flushText()
                    val end = tagEnd(html, i)
                    val inner = html.substring(i + 1, end)
                    val selfClosing = inner.endsWith("/")
                    val body = inner.removeSuffix("/")
                    val rawName = body.takeWhile { !it.isWhitespace() }
                    val name = rawName.lowercase().substringAfter(':')
                    val attrs = HashMap<String, String>()
                    ATTR.findAll(body.drop(rawName.length)).forEach { m ->
                        val key = m.groupValues[1].lowercase().substringAfter(':')
                        attrs[key] = decodeEntities(m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: "")
                    }
                    if (name in CLOSES_P && current.tag == "p") current = current.parent ?: root
                    if ((name == "li" && current.tag == "li") || (name == "rt" && current.tag == "rt")) current = current.parent ?: root
                    val node = HtmlNode(name, attrs, current)
                    current.children += node
                    i = end + 1
                    if (name in RAW_TEXT && !selfClosing) {
                        // Search for the close tag as written (e.g. </dc:title>), not the prefix-free name.
                        val close = html.indexOf("</$rawName", i, ignoreCase = true)
                        val stop = if (close < 0) html.length else close
                        node.children += if (name == "title" || name == "textarea") decodeEntities(html.substring(i, stop)) else html.substring(i, stop)
                        val closeEnd = if (close < 0) html.length else html.indexOf('>', close).let { if (it < 0) html.length else it + 1 }
                        i = closeEnd
                    } else if (!selfClosing && name !in VOID) {
                        current = node
                    }
                }
                else -> { text.append(c); i++ }
            }
        }
        flushText()
        return root
    }

    /** End index of the tag starting at [start] ('>' outside quotes). */
    private fun tagEnd(html: String, start: Int): Int {
        var quote: Char? = null
        var i = start + 1
        while (i < html.length) {
            val c = html[i]
            if (quote != null) { if (c == quote) quote = null } else if (c == '"' || c == '\'') quote = c else if (c == '>') return i
            i++
        }
        return html.length - 1
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·",
        "copy" to "©", "reg" to "®", "times" to "×", "laquo" to "«", "raquo" to "»", "yen" to "¥",
    )
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v.startsWith("#x") || v.startsWith("#X") -> v.substring(2).toIntOrNull(16)?.let(::codePointString) ?: m.value
                v.startsWith("#") -> v.substring(1).toIntOrNull()?.let(::codePointString) ?: m.value
                else -> NAMED[v] ?: m.value
            }
        }
    }

    private fun codePointString(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }

    /** Elements whose content is never article text. Ruby readings (rt/rp) are dropped, keeping the base text. */
    val SKIP = setOf(
        "script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg", "button", "iframe", "template",
        "select", "rt", "rp", "head", "figcaption",
    )
    private val BLOCK = setOf(
        "p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "main", "blockquote",
        "tr", "table", "pre", "dd", "dt", "hr", "body",
    )

    /** Visible text of [node] with one paragraph per line. */
    fun text(node: HtmlNode, skip: Set<String> = SKIP): String {
        val sb = StringBuilder()
        fun walk(n: HtmlNode) {
            if (n.tag in skip) return
            val block = n.tag in BLOCK
            if (block) sb.append('\n')
            for (c in n.children) if (c is String) sb.append(c) else walk(c as HtmlNode)
            if (block) sb.append('\n')
        }
        walk(node)
        return normalizeText(sb.toString())
    }

    /** Collapses spaces, trims lines, and keeps one line per paragraph. */
    fun normalizeText(s: String): String =
        s.replace(' ', ' ')
            .replace('\r', '\n')
            .lines()
            .map { it.replace(SPACES, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private val SPACES = Regex("[ \\t\\u3000]+")

    fun isJapanese(c: Char): Boolean =
        c in '぀'..'ヿ' || c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == '〆'

    fun japaneseCount(s: String): Int = s.count(::isJapanese)
}

/** Title and main text of a web page, readability-style (BRIEF §5.8 "readability extraction on device"). */
data class ExtractedArticle(val title: String, val body: String)

object WebArticleExtractor {

    /**
     * Picks the page's main content: every paragraph-like element (<p>, <li>, <div>/<td> with direct text) scores
     * its Japanese character count for its parent and half for its grandparent; the best-scoring container wins
     * (an explicit <article>/<main> is preferred when it holds most of that text). Navigation, headers, footers,
     * scripts, forms and ruby readings are dropped; paragraphs become lines.
     */
    fun extract(html: String): ExtractedArticle {
        val root = Html.parse(html)
        val title = title(root)
        val scores = HashMap<HtmlNode, Double>()
        for (el in root.elements()) {
            if (el.tag !in setOf("p", "li", "div", "td", "dd", "blockquote", "section")) continue
            if (hasSkippedAncestor(el)) continue
            val direct = el.children.filterIsInstance<String>().joinToString("") +
                el.children.filterIsInstance<HtmlNode>().filter { it.tag in INLINE }.joinToString("") { Html.text(it) }
            val count = if (el.tag == "p") Html.japaneseCount(Html.text(el)) else Html.japaneseCount(direct)
            if (count < MIN_PARAGRAPH) continue
            val parent = if (el.tag == "p" || el.tag == "li") el.parent else el
            parent?.let { scores[it] = (scores[it] ?: 0.0) + count }
            parent?.parent?.let { scores[it] = (scores[it] ?: 0.0) + count / 2.0 }
        }
        val best = scores.maxByOrNull { it.value }?.key
        val explicit = root.elements().firstOrNull { (it.tag == "article" || it.tag == "main") && !hasSkippedAncestor(it) }
        val container = when {
            best == null -> explicit ?: root.first("body") ?: root
            explicit != null && isAncestor(explicit, best) -> best
            explicit != null && Html.japaneseCount(Html.text(explicit)) >= Html.japaneseCount(Html.text(best)) -> explicit
            else -> best
        }
        var body = Html.text(container)
        // Drop a leading copy of the title (article bodies often repeat the headline).
        if (title.isNotEmpty() && body.startsWith(title)) body = body.removePrefix(title).trimStart('\n')
        return ExtractedArticle(title, body)
    }

    /** Full visible text of an (X)HTML document's body, e.g. an EPUB chapter. */
    fun bodyText(html: String): String {
        val root = Html.parse(html)
        return Html.text(root.first("body") ?: root)
    }

    private fun title(root: HtmlNode): String {
        val og = root.elements().firstOrNull { it.tag == "meta" && (it.attrs["property"] == "og:title" || it.attrs["name"] == "og:title") }
            ?.attrs?.get("content")?.trim()
        if (!og.isNullOrEmpty()) return og
        val h1 = root.elements().firstOrNull { it.tag == "h1" && !hasSkippedAncestor(it) }?.let { Html.text(it) }
        val t = root.first("title")?.let { (it.children.firstOrNull() as? String)?.trim() }
        return (h1?.takeIf { it.isNotBlank() } ?: t).orEmpty().lines().firstOrNull().orEmpty().trim()
    }

    private fun hasSkippedAncestor(n: HtmlNode): Boolean {
        var p = n.parent
        while (p != null) { if (p.tag in Html.SKIP) return true; p = p.parent }
        return false
    }

    private fun isAncestor(a: HtmlNode, b: HtmlNode): Boolean {
        var p: HtmlNode? = b
        while (p != null) { if (p === a) return true; p = p.parent }
        return false
    }

    private val INLINE = setOf("span", "a", "b", "strong", "em", "i", "ruby", "rb", "mark", "small", "u", "font")
    private const val MIN_PARAGRAPH = 8
}
