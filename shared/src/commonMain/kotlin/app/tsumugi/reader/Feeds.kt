package app.tsumugi.reader

import app.tsumugi.platform.normalizeNfc

data class FeedItem(val title: String, val link: String, val published: String?, val summary: String)

data class ParsedFeed(val title: String, val items: List<FeedItem>)

/**
 * RSS 2.0, RSS 1.0 (RDF) and Atom, parsed with the tolerant [Html] tree builder (no XML library): feeds in the
 * wild are often not well-formed, and we only need a handful of fields.
 */
object FeedParser {
    fun parse(xml: String): ParsedFeed {
        val root = Html.parse(xml)
        val entries = root.elements().filter { it.tag == "item" || it.tag == "entry" }.toList()
        val feedTitle = root.elements()
            .firstOrNull { it.tag == "title" && entries.none { e -> isInside(it, e) } }
            ?.let { text(it) }.orEmpty()
        val items = entries.mapNotNull { e ->
            val title = child(e, "title")?.let { text(it) }.orEmpty()
            val link = link(e) ?: return@mapNotNull null
            val published = listOf("pubdate", "published", "updated", "date").firstNotNullOfOrNull { child(e, it)?.let(::text) }
            val summaryHtml = listOf("description", "summary", "content", "encoded").firstNotNullOfOrNull { child(e, it)?.let(::raw) }.orEmpty()
            FeedItem(title.ifEmpty { link }, link, published, Html.text(Html.parse(summaryHtml)))
        }
        return ParsedFeed(feedTitle, items)
    }

    private fun link(entry: HtmlNode): String? {
        val links = entry.children.filterIsInstance<HtmlNode>().filter { it.tag == "link" }
        links.firstOrNull { it.attrs["href"] != null && (it.attrs["rel"] == null || it.attrs["rel"] == "alternate") }
            ?.let { return it.attrs["href"]!!.trim() }
        // RSS: <link>url</link>. The HTML-ish parser treats <link> as void, so the URL is the following text node.
        for ((i, c) in entry.children.withIndex()) {
            if (c is HtmlNode && c.tag == "link") {
                val inner = text(c).trim()
                if (inner.isNotEmpty()) return inner
                val next = entry.children.getOrNull(i + 1) as? String
                if (next != null && next.isNotBlank()) return next.trim()
            }
        }
        entry.children.filterIsInstance<HtmlNode>().firstOrNull { it.tag == "guid" }?.let { g -> text(g).takeIf { it.startsWith("http") } }
            ?.let { return it }
        return entry.attrs["about"]
    }

    private fun child(n: HtmlNode, tag: String): HtmlNode? = n.elements().firstOrNull { it.tag == tag }

    private fun text(n: HtmlNode): String = normalizeNfc(Html.text(n, emptySet()).replace('\n', ' ').trim())

    /** Inner text of an element as markup (descriptions often carry escaped HTML). */
    private fun raw(n: HtmlNode): String = buildString {
        fun walk(x: HtmlNode) {
            for (c in x.children) if (c is String) append(c) else walk(c as HtmlNode)
        }
        walk(n)
    }

    private fun isInside(n: HtmlNode, ancestor: HtmlNode): Boolean {
        var p = n.parent
        while (p != null) { if (p === ancestor) return true; p = p.parent }
        return false
    }
}

/** User-added feeds (NHK Easy, blogs, news): fetched on this device only, when the user asks (BRIEF §4, §5.8). */
class FeedService(private val repo: ReaderRepository, private val web: UrlImporter) {

    /** Subscribes to [url] after checking it parses as a feed; returns the feed with its current items. */
    suspend fun add(url: String): Pair<ReaderFeed, List<FeedItem>> {
        val parsed = FeedParser.parse(web.fetchText(url))
        if (parsed.items.isEmpty() && parsed.title.isEmpty()) throw ImportException("That URL doesn't look like an RSS or Atom feed")
        val feed = repo.addFeed(url, parsed.title.ifEmpty { url })
        repo.markFetched(feed.id)
        return feed to parsed.items
    }

    suspend fun feeds(): List<ReaderFeed> = repo.feeds()

    suspend fun items(feed: ReaderFeed): List<FeedItem> {
        val parsed = FeedParser.parse(web.fetchText(feed.url))
        repo.markFetched(feed.id)
        return parsed.items
    }

    /** Fetches the item's article and saves it as a document; returns the document id. */
    suspend fun importItem(item: FeedItem): String {
        val text = runCatching { web.import(item.link, SourceKind.RSS) }.getOrElse {
            // Some feeds carry the whole article in the item; fall back to it rather than failing.
            if (item.summary.isBlank()) throw it
            ImportedText(item.title, item.summary, SourceKind.RSS, item.link)
        }
        return repo.save(text.copy(title = item.title.ifEmpty { text.title }))
    }

    suspend fun remove(feedId: String) = repo.deleteFeed(feedId)
}
