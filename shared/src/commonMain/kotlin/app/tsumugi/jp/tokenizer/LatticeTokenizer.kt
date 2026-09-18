package app.tsumugi.jp.tokenizer

import app.tsumugi.tokenizer.db.TokenizerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Viterbi morphological analyzer over the IPADIC-derived tokenizer pack (BRIEF §5.2).
 *
 * The standard lattice method (as published for MeCab; Kuromoji, Apache-2.0, served as an algorithm
 * reference — no code copied): every dictionary word and unknown-word candidate starting at each position becomes
 * a node; the cheapest path from BOS to EOS by word costs plus connection costs is the analysis. Unknown words
 * follow char.def/unk.def: a character category decides whether unknown candidates are always added (invoke),
 * whether same-category runs are grouped, and up to which length single candidates are added. Whitespace between
 * words is skipped, as MeCab does. Text is analyzed sentence by sentence (split after 。！？ and newlines).
 */
class LatticeTokenizer(private val db: TokenizerDatabase) : MorphologicalAnalyzer {

    private class Entry(
        val length: Int,
        val leftId: Int,
        val rightId: Int,
        val cost: Int,
        val features: String,
        val base: String,
        val reading: String,
        val pronunciation: String,
        val unknown: Boolean,
    )

    private class Category(val invoke: Boolean, val group: Boolean, val length: Int)

    private class Tables(
        val costs: ShortArray,
        val backwardSize: Int,
        val categories: List<Category>,
        val primary: ByteArray,
        val masks: IntArray,
        val unknowns: List<List<Entry>>,
        val long: Map<Char, List<Pair<String, Entry>>>,
        /** Index of char.def's SPACE category (-1 if absent): whitespace between words is skipped. */
        val space: Int,
    )

    private class Node(val start: Int, val end: Int, val entry: Entry?, val total: Int, val prev: Node?)

    private val loadLock = Mutex()
    private var tables: Tables? = null
    private val cache = HashMap<String, List<Entry>>()

    @Throws(Exception::class)
    override suspend fun analyze(text: String): List<Morpheme> = withContext(Dispatchers.IO) {
        val t = loaded()
        val out = ArrayList<Morpheme>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in SENTENCE_ENDS) {
                analyzeSentence(t, text, start, i + 1, out)
                start = i + 1
            }
        }
        if (start < text.length) analyzeSentence(t, text, start, text.length, out)
        out
    }

    private suspend fun loaded(): Tables = loadLock.withLock {
        tables ?: withContext(Dispatchers.IO) { load() }.also { tables = it }
    }

    private fun load(): Tables {
        val q = db.tokenizerQueries
        val conn = q.connection().executeAsOne()
        val bytes = conn.costs
        val costs = ShortArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }

        val categoryRows = q.categories().executeAsList()
        val index = categoryRows.withIndex().associate { (i, c) -> c.name to i }
        val categories = categoryRows.map { Category(it.invoke != 0L, it.grp != 0L, it.length.toInt()) }
        val default = index.getValue("DEFAULT")
        val primary = ByteArray(0x10000) { default.toByte() }
        val masks = IntArray(0x10000) { 1 shl default }
        for (r in q.ranges().executeAsList()) {
            val names = r.categories.split(' ').filter { it.isNotEmpty() && it in index }
            if (names.isEmpty()) continue
            val mask = names.fold(0) { m, n -> m or (1 shl index.getValue(n)) }
            for (c in r.first.toInt()..minOf(r.last.toInt(), 0xFFFF)) {
                primary[c] = index.getValue(names.first()).toByte()
                masks[c] = mask
            }
        }

        val unknowns = List(categories.size) { ArrayList<Entry>() }
        for (u in q.unknowns().executeAsList()) {
            val i = index[u.category] ?: continue
            unknowns[i] += Entry(0, u.left_id.toInt(), u.right_id.toInt(), u.cost.toInt(), u.features, "", "", "", unknown = true)
        }

        val long = q.longMorphemes(MAX_BATCH_LENGTH.toLong()).executeAsList()
            .map { it.surface to entry(it.surface, it.left_id, it.right_id, it.cost, it.features, it.base, it.reading, it.pronunciation) }
            .groupBy { it.first.first() }

        return Tables(costs, conn.backward_size.toInt(), categories, primary, masks, unknowns, long, index["SPACE"] ?: -1)
    }

    private fun entry(surface: String, left: Long, right: Long, cost: Long, features: String, base: String, reading: String, pron: String) =
        Entry(surface.length, left.toInt(), right.toInt(), cost.toInt(), features, base, reading, pron, unknown = false)

    /** Dictionary entries for every substring of [s] up to [MAX_BATCH_LENGTH] chars, fetched in few queries. */
    private fun prefetch(s: String) {
        val missing = LinkedHashSet<String>()
        for (i in s.indices) {
            for (len in 1..minOf(MAX_BATCH_LENGTH, s.length - i)) {
                val sub = s.substring(i, i + len)
                if (sub !in cache) missing += sub
            }
        }
        if (missing.isEmpty()) return
        if (cache.size > CACHE_LIMIT) cache.clear()
        val found = HashMap<String, MutableList<Entry>>()
        for (chunk in missing.chunked(SQL_CHUNK)) {
            db.tokenizerQueries.lookup(chunk).executeAsList().forEach {
                found.getOrPut(it.surface) { ArrayList() } +=
                    entry(it.surface, it.left_id, it.right_id, it.cost, it.features, it.base, it.reading, it.pronunciation)
            }
        }
        for (sub in missing) cache[sub] = found[sub].orEmpty()
    }

    private fun analyzeSentence(t: Tables, text: String, from: Int, to: Int, out: MutableList<Morpheme>) {
        val s = text.substring(from, to)
        val n = s.length
        prefetch(s)
        val ends = arrayOfNulls<ArrayList<Node>>(n + 1)
        ends[0] = arrayListOf(Node(0, 0, null, 0, null))
        var eos: Node? = null

        for (p in 0..n) {
            val prevs = ends[p] ?: continue
            var q = p
            while (q < n && isSpace(t, s[q])) q++
            if (q == n) {
                val best = cheapest(t, prevs, 0) ?: continue
                val total = best.total + connection(t, best, 0)
                if (eos == null || total < eos.total) eos = Node(n, n, null, total, best)
                continue
            }
            for ((length, e) in candidates(t, s, q)) {
                val best = cheapest(t, prevs, e.leftId) ?: continue
                val node = Node(q, q + length, e, best.total + connection(t, best, e.leftId) + e.cost, best)
                (ends[q + length] ?: ArrayList<Node>().also { ends[q + length] = it }) += node
            }
        }

        val path = ArrayList<Node>()
        var node = eos?.prev
        while (node != null && node.entry != null) {
            path += node
            node = node.prev
        }
        for (m in path.asReversed()) out += morpheme(s, m, from)
    }

    private fun cheapest(t: Tables, prevs: List<Node>, leftId: Int): Node? {
        var best: Node? = null
        var bestCost = Int.MAX_VALUE
        for (prev in prevs) {
            val c = prev.total + connection(t, prev, leftId)
            if (c < bestCost) {
                bestCost = c
                best = prev
            }
        }
        return best
    }

    private fun connection(t: Tables, prev: Node, leftId: Int): Int {
        val right = prev.entry?.rightId ?: 0
        return t.costs[right * t.backwardSize + leftId].toInt()
    }

    /** Dictionary words and unknown-word candidates starting at [q], as (length, entry). */
    private fun candidates(t: Tables, s: String, q: Int): List<Pair<Int, Entry>> {
        val out = ArrayList<Pair<Int, Entry>>()
        for (len in 1..minOf(MAX_BATCH_LENGTH, s.length - q)) {
            cache[s.substring(q, q + len)]?.forEach { out += len to it }
        }
        t.long[s[q]]?.forEach { (surface, e) -> if (s.startsWith(surface, q)) out += surface.length to e }

        val category = t.primary[s[q].code].toInt()
        val def = t.categories[category]
        if (def.invoke || out.isEmpty()) {
            val bit = 1 shl category
            var run = 1
            while (q + run < s.length && run < MAX_GROUP && (t.masks[s[q + run].code] and bit) != 0) run++
            val lengths = LinkedHashSet<Int>()
            if (def.group) lengths += run
            for (len in 1..def.length) if (len <= run) lengths += len
            for (len in lengths) t.unknowns[category].forEach { out += len to it }
        }
        return out
    }

    private fun isSpace(t: Tables, c: Char) = t.primary[c.code].toInt() == t.space

    private fun morpheme(s: String, node: Node, offset: Int): Morpheme {
        val e = node.entry!!
        val surface = s.substring(node.start, node.end)
        val f = e.features.split(',')
        fun field(i: Int) = f.getOrNull(i)?.takeIf { it != "*" && it.isNotEmpty() }
        val reading = e.reading.ifEmpty { null }
        return Morpheme(
            surface = surface,
            start = offset + node.start,
            end = offset + node.end,
            pos = (0..3).mapNotNull(::field),
            conjugationType = field(4),
            conjugationForm = field(5),
            baseForm = e.base.ifEmpty { surface },
            reading = reading,
            pronunciation = e.pronunciation.ifEmpty { reading },
            isUnknown = e.unknown,
        )
    }

    private companion object {
        const val MAX_BATCH_LENGTH = 8
        const val MAX_GROUP = 24
        const val SQL_CHUNK = 400
        const val CACHE_LIMIT = 200_000
        const val SENTENCE_ENDS = "。！？\n"
    }
}
