package app.tsumugi.kanji

import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.EntrySummary
import app.tsumugi.dictionary.KanjiInfo
import app.tsumugi.dictionary.PackCodec
import app.tsumugi.dictionary.codePointStrings
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.jp.Kana
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** What a direct part does in its kanji (BRIEF_V2 §6.15, D-281): gives the meaning, the sound, or only the shape. */
enum class ComponentRole { SEMANTIC, PHONETIC, FORM }

/**
 * One direct part of a kanji. [role] is null for packs built before Phase 13 (no roles, KRADFILE parts only).
 * [reading]/[match] describe a phonetic part ("same" or "related" on'yomi, or "shared" by a family). [derived]: show the
 * "derived" label (the heuristic's guess, not reviewed yet).
 */
data class KanjiComponent(
    val component: String,
    val role: ComponentRole?,
    val position: String,
    val reading: String?,
    val match: String?,
    val seriesId: String?,
    val kanjiVgPhonetic: Boolean,
    val derived: Boolean,
)

data class SeriesMember(val kanji: String, val onyomi: List<String>, val match: String)

/** A sound series (声符 family): [phonetic] and the kanji built on it that read alike. [derived]: not reviewed yet. */
data class SoundSeries(val phonetic: String, val readings: List<String>, val members: List<SeriesMember>, val derived: Boolean) {
    /** Members other than the phonetic itself. */
    val family: List<SeriesMember> get() = members.filter { it.kanji != phonetic }
}

enum class GraphNodeKind { KANJI, COMPONENT, CONTAINER, SERIES, WORD }

enum class GraphEdgeKind { PART, USED_IN, SOUND, WORD }

/** How nodes are colored: by JLPT level or by frequency. */
enum class GraphColoring { JLPT, FREQUENCY }

/**
 * A node of the explorer graph. [id] is `k:<kanji>` for characters (components, containers and series members are
 * characters too) and `w:<entry id>` for words, so tapping a node re-centers with [KanjiExplorer.neighborhood] or
 * [KanjiExplorer.wordNeighborhood]. [colorBucket] is 0 (easiest / most frequent) … 4, and 5 for unknown.
 */
data class GraphNode(
    val id: String,
    val label: String,
    val kind: GraphNodeKind,
    val isFocus: Boolean,
    val jlpt: Int?,
    /** KANJIDIC2 frequency (kanji) or frequency-list position (words); null when unranked. */
    val frequency: Int?,
    val colorBucket: Int,
    val role: ComponentRole? = null,
    val reading: String = "",
    val gloss: String = "",
    val entryId: Long? = null,
)

data class GraphEdge(val from: String, val to: String, val kind: GraphEdgeKind)

/** A capped one-hop neighborhood ("focus" mode) around one kanji or word. [hidden] counts neighbors left out by the cap. */
data class KanjiNeighborhood(
    val focusId: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val hidden: Int,
    val coloring: GraphColoring,
) {
    /** A deterministic layout of this graph for a force-directed view, in unit coordinates, focus at the center. */
    fun layout(iterations: Int = ForceLayout.DEFAULT_ITERATIONS): List<NodePosition> =
        ForceLayout.layout(nodes.map { it.id }, edges.map { it.from to it.to }, pinned = focusId, iterations = iterations)
}

/** Kanji found from typed components (a component-combination search). [unknown]: parts no kanji uses. */
data class ComponentSearchResult(val components: List<String>, val kanji: List<KanjiInfo>, val unknown: List<String>)

/**
 * The kanji explorer (BRIEF_V2 §6.15): component trees (KanjiVG), KRADFILE parts, compounds (the dictionary's
 * kanji→word index), functional components and sound series (build_phonetics.py), over the dictionary pack.
 * Everything is local; tables an older pack lacks fall back to KRADFILE or come back empty.
 */
class KanjiExplorer(private val db: DictionaryDatabase, private val dictionary: DictionaryRepository) {
    private val q get() = db.dictionaryQueries
    private val parts get() = db.kanjiPartsQueries
    private val json = Json { ignoreUnknownKeys = true }

    // --- Components and sound series --------------------------------------------------------------------------

    /**
     * The direct parts of [literal] with their roles. Falls back to the KanjiVG tree without roles, then to KRADFILE
     * components (packs built before Phase 13).
     */
    @Throws(Exception::class)
    suspend fun components(literal: String): List<KanjiComponent> = io { componentsBlocking(literal) }

    private fun componentsBlocking(literal: String): List<KanjiComponent> {
        val roles = runCatching { parts.rolesOf(literal).executeAsList() }.getOrDefault(emptyList())
        val positions = runCatching { parts.elementsOf(literal).executeAsList() }.getOrDefault(emptyList())
            .filter { it.depth == 1L }.associate { it.element to it.position }
        if (roles.isNotEmpty()) {
            return roles.map { r ->
                val ev = evidence(r.evidence)
                KanjiComponent(
                    r.component, runCatching { ComponentRole.valueOf(r.role) }.getOrNull(), positions[r.component].orEmpty(),
                    ev.str("reading").ifEmpty { null }, ev.str("match").ifEmpty { null }, r.series.ifEmpty { null },
                    (ev["kvgPhon"] as? JsonPrimitive)?.booleanOrNull == true, r.source != VERIFIED,
                )
            }
        }
        if (positions.isNotEmpty()) {
            return positions.map { (el, pos) -> KanjiComponent(el, null, pos, null, null, null, false, true) }
        }
        return q.componentsOf(literal).executeAsList().map { KanjiComponent(it, null, "", null, null, null, false, true) }
    }

    /** The sound series with phonetic [phonetic], or null. */
    @Throws(Exception::class)
    suspend fun series(phonetic: String): SoundSeries? = io {
        runCatching { parts.seriesById(phonetic).executeAsOneOrNull() }.getOrNull()?.let { toSeries(it.phonetic, it.readings, it.members, it.source) }
    }

    /**
     * The series [literal] belongs to: as a member through its phonetic part, else the one it heads (青 heads 青-series).
     */
    @Throws(Exception::class)
    suspend fun seriesOf(literal: String): SoundSeries? {
        val via = components(literal).firstOrNull { it.role == ComponentRole.PHONETIC && it.seriesId != null }?.seriesId
        return series(via ?: literal)
    }

    /** Every sound series, largest first (a browsable list). */
    @Throws(Exception::class)
    suspend fun allSeries(): List<SoundSeries> = io {
        runCatching { parts.allSeries().executeAsList() }.getOrDefault(emptyList()).map { toSeries(it.phonetic, it.readings, it.members, it.source) }
    }

    private fun toSeries(phonetic: String, readings: String, members: String, source: String): SoundSeries {
        val list = runCatching { json.parseToJsonElement(members) as? JsonArray }.getOrNull().orEmpty().mapNotNull { it as? JsonObject }.map { m ->
            SeriesMember(m.str("k"), (m["on"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(), m.str("match"))
        }
        return SoundSeries(phonetic, PackCodec.strings(readings), list, source != VERIFIED)
    }

    // --- Graph ----------------------------------------------------------------------------------------------------

    /**
     * One hop around [literal], capped at [maxNodes] (focus included, at least [MIN_NODES]): its parts, its sound-series
     * siblings, kanji that use it, and words written with it, most frequent first. Parts are always shown; the rest share
     * what's left (series up to a fifth, then kanji and words half and half, each taking what the other doesn't use).
     */
    @Throws(Exception::class)
    suspend fun neighborhood(literal: String, maxNodes: Int = DEFAULT_MAX_NODES, coloring: GraphColoring = GraphColoring.JLPT): KanjiNeighborhood? {
        val cap = maxNodes.coerceAtLeast(MIN_NODES)
        val focus = kanjiInfo(listOf(literal)).firstOrNull()
        val comps = components(literal).filter { it.component != literal }
        if (focus == null && comps.isEmpty()) return null
        val series = seriesOf(literal)
        val siblingsAll = series?.members?.map { it.kanji }?.filter { it != literal && comps.none { c -> c.component == it } }.orEmpty()
        val usedInAll = io { runCatching { parts.kanjiContaining(literal, (cap * 2).toLong()).executeAsList() }.getOrDefault(emptyList()) }
            .filter { it != literal && it !in siblingsAll }
        val wordIds = io { q.wordsWithKanji(literal, (cap * 2).toLong()).executeAsList() }

        val shownComps = comps.take(cap / 3)
        var left = cap - 1 - shownComps.size
        val siblings = siblingsAll.take(minOf(left / 5 + if (siblingsAll.isNotEmpty()) 1 else 0, left))
        left -= siblings.size
        var usedN = minOf(usedInAll.size, left / 2)
        val wordN = minOf(wordIds.size, left - usedN)
        usedN = minOf(usedInAll.size, left - wordN)
        val usedIn = usedInAll.take(usedN)
        val words = dictionary.summaries(wordIds.take(wordN))
        val ords = dictionary.frequencyOrds(words.map { it.id })

        val infos = kanjiInfo((shownComps.map { it.component } + siblings + usedIn).distinct()).associateBy { it.literal }
        val nodes = ArrayList<GraphNode>()
        val edges = ArrayList<GraphEdge>()
        val focusId = kanjiId(literal)
        nodes += kanjiNode(literal, focus, GraphNodeKind.KANJI, coloring, isFocus = true)
        for (c in shownComps) {
            nodes += kanjiNode(c.component, infos[c.component], GraphNodeKind.COMPONENT, coloring, role = c.role)
            edges += GraphEdge(focusId, kanjiId(c.component), GraphEdgeKind.PART)
        }
        for (s in siblings) {
            nodes += kanjiNode(s, infos[s], GraphNodeKind.SERIES, coloring)
            edges += GraphEdge(focusId, kanjiId(s), GraphEdgeKind.SOUND)
        }
        for (k in usedIn) {
            nodes += kanjiNode(k, infos[k], GraphNodeKind.CONTAINER, coloring)
            edges += GraphEdge(kanjiId(k), focusId, GraphEdgeKind.USED_IN)
        }
        for (w in words) {
            nodes += wordNode(w, ords[w.id], coloring, isFocus = false)
            edges += GraphEdge(focusId, wordId(w.id), GraphEdgeKind.WORD)
        }
        val hidden = (comps.size - shownComps.size) + (siblingsAll.size - siblings.size) + (usedInAll.size - usedIn.size) + (wordIds.size - words.size)
        return KanjiNeighborhood(focusId, nodes.distinctBy { it.id }, edges.distinct(), hidden, coloring)
    }

    /** One hop around a word: its kanji, and other common words sharing them, capped at [maxNodes]. */
    @Throws(Exception::class)
    suspend fun wordNeighborhood(entryId: Long, maxNodes: Int = DEFAULT_MAX_NODES, coloring: GraphColoring = GraphColoring.JLPT): KanjiNeighborhood? {
        val cap = maxNodes.coerceAtLeast(MIN_NODES)
        val word = dictionary.summaries(listOf(entryId)).firstOrNull() ?: return null
        val chars = word.headword.codePointStrings().filter { Kana.containsKanji(it) }.distinct().take(cap / 2)
        val infos = kanjiInfo(chars).associateBy { it.literal }
        val focusId = wordId(entryId)
        val nodes = arrayListOf(wordNode(word, dictionary.frequencyOrds(listOf(entryId))[entryId], coloring, isFocus = true))
        val edges = ArrayList<GraphEdge>()
        for (c in chars) {
            nodes += kanjiNode(c, infos[c], GraphNodeKind.KANJI, coloring)
            edges += GraphEdge(focusId, kanjiId(c), GraphEdgeKind.WORD)
        }
        var left = cap - nodes.size
        val perKanji = if (chars.isEmpty()) 0 else (left / chars.size).coerceAtLeast(1)
        var hidden = 0
        val others = LinkedHashMap<Long, String>()
        for (c in chars) {
            val ids = io { q.wordsWithKanji(c, (perKanji * 2 + 1).toLong()).executeAsList() }.filter { it != entryId && it !in others }
            val take = ids.take(minOf(perKanji, left))
            hidden += ids.size - take.size
            take.forEach { others[it] = c }
            left -= take.size
        }
        val summaries = dictionary.summaries(others.keys.toList())
        val ords = dictionary.frequencyOrds(summaries.map { it.id })
        for (w in summaries) {
            nodes += wordNode(w, ords[w.id], coloring, isFocus = false)
            edges += GraphEdge(kanjiId(others.getValue(w.id)), wordId(w.id), GraphEdgeKind.WORD)
        }
        return KanjiNeighborhood(focusId, nodes.distinctBy { it.id }, edges.distinct(), hidden, coloring)
    }

    // --- Component-combination search (BRIEF_V2 §6.15 dictionary polish) ---------------------------------------------

    /**
     * Kanji containing every component typed in [input] ("言五口", "氵 青", "木+目"), most frequent first. A component
     * matches anywhere in the KanjiVG tree or as a KRADFILE radical, so both 青 and 氵 find 清. Separators (space, +,
     * ＋, 、, comma) are ignored.
     */
    @Throws(Exception::class)
    suspend fun componentSearch(input: String, limit: Int = DEFAULT_SEARCH_LIMIT): ComponentSearchResult = io {
        val pieces = input.codePointStrings().filter { it.isNotBlank() && it !in SEPARATORS }.distinct()
        if (pieces.isEmpty()) return@io ComponentSearchResult(emptyList(), emptyList(), emptyList())
        val keys = q.allRadicals().executeAsList().flatMap { listOf(it.radical to it.radical, it.display to it.radical) }.toMap()
        val unknown = ArrayList<String>()
        var result: Set<String>? = null
        for (p in pieces) {
            val hits = runCatching { parts.kanjiWithPart(p, keys[p] ?: p).executeAsList().toSet() }
                .getOrElse { q.kanjiWithAllRadicals(listOf(keys[p] ?: p), 1L).executeAsList().map { it.literal }.toSet() }
            if (hits.isEmpty()) unknown += p
            result = result?.intersect(hits) ?: hits
        }
        val found = result.orEmpty().filter { it !in pieces }
        val infos = found.chunked(SQL_CHUNK).flatMap { kanjiInfo(it) }
            .sortedWith(compareBy<KanjiInfo> { it.frequency ?: Int.MAX_VALUE }.thenBy { it.strokeCount }.thenBy { it.literal })
        ComponentSearchResult(pieces, infos.take(limit), unknown)
    }

    /** A kanji's parts as a component query, for "find kanji with these parts" from a kanji page. */
    @Throws(Exception::class)
    suspend fun componentQuery(literal: String): String = components(literal).joinToString(" ") { it.component }

    private fun kanjiInfo(literals: List<String>): List<KanjiInfo> {
        if (literals.isEmpty()) return emptyList()
        val rows = q.kanjiByLiteral(literals).executeAsList().associateBy { it.literal }
        return literals.mapNotNull { rows[it] }.map {
            KanjiInfo(
                it.literal, it.grade?.toInt(), it.stroke_count.toInt(), it.freq?.toInt(), it.jlpt_new?.toInt(), it.jlpt_old?.toInt(),
                it.heisig?.toInt(), it.heisig6?.toInt(), PackCodec.strings(it.meanings), PackCodec.strings(it.onyomi),
                PackCodec.strings(it.kunyomi), PackCodec.strings(it.nanori),
            )
        }
    }

    private fun kanjiNode(literal: String, info: KanjiInfo?, kind: GraphNodeKind, coloring: GraphColoring, isFocus: Boolean = false, role: ComponentRole? = null) =
        GraphNode(
            kanjiId(literal), literal, kind, isFocus, info?.jlpt, info?.frequency,
            bucket(coloring, info?.jlpt, info?.frequency, KANJI_FREQ_STEPS), role,
            info?.onyomi?.firstOrNull() ?: info?.kunyomi?.firstOrNull().orEmpty(), info?.keyword.orEmpty(),
        )

    private fun wordNode(w: EntrySummary, ord: Int?, coloring: GraphColoring, isFocus: Boolean) =
        GraphNode(
            wordId(w.id), w.headword, GraphNodeKind.WORD, isFocus, w.jlpt, ord, bucket(coloring, w.jlpt, ord, WORD_FREQ_STEPS),
            reading = w.reading, gloss = w.glossPreview.substringBefore(';'), entryId = w.id,
        )

    private fun evidence(raw: String): JsonObject = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DEFAULT_MAX_NODES = 30
        const val MIN_NODES = 5
        const val DEFAULT_SEARCH_LIMIT = 60
        private const val VERIFIED = "verified"
        private const val SQL_CHUNK = 500
        private val SEPARATORS = setOf("+", "＋", "、", ",", "，", "・")
        private val KANJI_FREQ_STEPS = listOf(500, 1000, 1500, 2000, 2500)
        private val WORD_FREQ_STEPS = listOf(1000, 2500, 5000, 7500, 10000)

        fun kanjiId(literal: String) = "k:$literal"
        fun wordId(entryId: Long) = "w:$entryId"

        /** 0 (N5 / most frequent) … 4 (N1 / least frequent ranked), 5 = unknown. */
        internal fun bucket(coloring: GraphColoring, jlpt: Int?, frequency: Int?, steps: List<Int>): Int = when (coloring) {
            GraphColoring.JLPT -> if (jlpt != null && jlpt in 1..5) 5 - jlpt else UNKNOWN_BUCKET
            GraphColoring.FREQUENCY -> frequency?.let { f -> steps.indexOfFirst { f <= it }.takeIf { it >= 0 } } ?: UNKNOWN_BUCKET
        }

        const val UNKNOWN_BUCKET = 5
    }
}
