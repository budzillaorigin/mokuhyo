package app.tsumugi.integrations.anki

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Writes items as an Anki package (.apkg) using the legacy schema-11 `collection.anki2`, which every current
 * Anki version imports. Items that came from Anki keep their note id, guid, notetype and fields (so a
 * re-import updates the same notes); everything else uses a "Tsumugi" notetype with Meaning / Reading /
 * Recall templates. The review log is exported as Anki revlog entries so Anki (or FSRS in Anki) can pick up
 * the history.
 */
class AnkiExporter(
    private val db: TsumugiDatabase,
    private val fs: FileSystem,
    private val workDir: Path,
    private val openDriver: (path: String) -> SqlDriver,
    private val mediaDir: Path? = null,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(Exception::class)
    suspend fun export(itemIds: Collection<String>, deckName: String = "Tsumugi"): ByteArray = withContext(Dispatchers.IO) {
        val q = db.srsQueries
        val items = itemIds.distinct().chunked(500).flatMap { q.itemsByIds(it).executeAsList() }
        val nowMs = clock.now().toEpochMilliseconds()
        val nowSec = nowMs / 1000
        val crt = nowSec - nowSec % 86_400
        val deckId = nowMs
        val ids = IdSource(nowMs)

        // Group items into Anki notes (items split off from one Anki note share its note id).
        class NoteOut(val id: Long, val guid: String, val nt: AnkiNotetypeRef, val values: MutableList<String>, val tags: String)
        class CardOut(val id: Long, val nid: Long, val ord: Int, val card: app.tsumugi.db.Card)
        val notes = LinkedHashMap<Long, NoteOut>()
        val cards = ArrayList<CardOut>()
        val usedOrds = HashMap<Long, MutableSet<Int>>()
        val models = LinkedHashMap<Long, AnkiNotetypeRef>()

        for (item in items) {
            val story = q.noteFor(item.id).executeAsOneOrNull()?.my_story.orEmpty()
            val ctx = item.context?.takeIf { it.contains("\"anki\"") }
                ?.let { runCatching { json.decodeFromString<AnkiItemContext>(it).anki }.getOrNull() }
            val note: NoteOut
            val cardRefs: Map<String, AnkiCardRef>
            if (ctx != null) {
                note = notes.getOrPut(ctx.noteId) {
                    val values = ctx.notetype.fields.map { ctx.fields[it].orEmpty() }.toMutableList()
                    val storyIndex = ctx.notetype.fields.indexOfFirst { AnkiText.key(it) in STORY_KEYS }
                    if (storyIndex >= 0 && story.isNotBlank() && AnkiText.plain(values[storyIndex]) != story) {
                        values[storyIndex] = AnkiText.htmlEscape(story)
                    }
                    NoteOut(ctx.noteId, ctx.guid, ctx.notetype, values, ctx.tags)
                }
                models[ctx.notetype.id] = ctx.notetype
                cardRefs = ctx.cards
            } else {
                val meanings = decodeList(item.meanings)
                val readings = decodeList(item.accepted_readings)
                val id = ids.next()
                note = NoteOut(
                    id, "tsumugi:${item.id}", BASIC,
                    mutableListOf(
                        AnkiText.htmlEscape(item.primary_text),
                        AnkiText.htmlEscape((listOfNotNull(item.reading) + readings).distinct().joinToString("、")),
                        AnkiText.htmlEscape(meanings.joinToString(", ")),
                        AnkiText.htmlEscape(story),
                        item.id,
                    ),
                    "tsumugi",
                )
                notes[id] = note
                models[BASIC.id] = BASIC
                cardRefs = emptyMap()
            }
            ids.reserve(note.id)
            for (card in q.cardsForItem(item.id).executeAsList()) {
                val ref = cardRefs[card.direction]
                val ord = ref?.ord ?: basicOrd(CardDirection.valueOf(card.direction))
                if (!usedOrds.getOrPut(note.id) { mutableSetOf() }.add(ord)) continue
                val id = ref?.id?.also { ids.reserve(it) } ?: ids.next()
                cards += CardOut(id, note.id, ord, card)
            }
        }

        fs.createDirectories(workDir)
        val file = workDir / "anki-export-${Random.nextLong().toULong()}.anki2"
        fs.delete(file, mustExist = false)
        val driver = openDriver(file.toString())
        val mediaNames = LinkedHashSet<String>()
        try {
            object : TransacterImpl(driver) {}.transaction {
            AnkiSchema11.statements.forEach { driver.exec(it) }
            driver.exec(
                "INSERT INTO col VALUES (1, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, '{}')",
                {
                    bindLong(0, crt); bindLong(1, nowMs); bindLong(2, nowMs)
                    bindString(3, colConf(deckId, models.keys.firstOrNull() ?: BASIC.id))
                    bindString(4, modelsJson(models.values, deckId, nowSec))
                    bindString(5, decksJson(deckId, deckName, nowSec))
                    bindString(6, DCONF)
                },
                7,
            )
            for (n in notes.values) {
                n.values.forEach { mediaNames += AnkiText.mediaReferences(it) }
                val flds = n.values.joinToString(FIELD_SEP.toString())
                val first = n.values.firstOrNull().orEmpty()
                driver.exec(
                    "INSERT INTO notes VALUES (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')",
                    {
                        bindLong(0, n.id); bindString(1, n.guid); bindLong(2, n.nt.id); bindLong(3, nowSec)
                        bindString(4, if (n.tags.isBlank()) "" else " ${n.tags.trim()} ")
                        bindString(5, flds); bindString(6, AnkiText.oneLine(first)); bindLong(7, AnkiText.checksum(first))
                    },
                    8,
                )
            }
            val revlogIds = HashSet<Long>()
            var newPosition = 1L
            for (c in cards) {
                val card = c.card
                val (type, queue, due, ivl) = when (card.state) {
                    "NEW" -> listOf(0L, 0L, newPosition++, 0L)
                    "REVIEW" -> {
                        val days = maxOf(1L, ((card.stability ?: 1.0) + 0.5).toLong())
                        listOf(2L, 2L, maxOf(0L, (card.due / 1000 - crt) / 86_400), days)
                    }
                    else -> listOf(2L, 2L, maxOf(0L, (nowSec - crt) / 86_400), 1L)
                }
                driver.exec(
                    "INSERT INTO cards VALUES (?, ?, ?, ?, ?, -1, ?, ?, ?, ?, 2500, ?, ?, 0, 0, 0, 0, '')",
                    {
                        bindLong(0, c.id); bindLong(1, c.nid); bindLong(2, deckId); bindLong(3, c.ord.toLong()); bindLong(4, nowSec)
                        bindLong(5, type); bindLong(6, if (card.suspended != 0L) -1 else queue); bindLong(7, due); bindLong(8, ivl)
                        bindLong(9, card.reps); bindLong(10, card.lapses)
                    },
                    11,
                )
                for (r in db.srsQueries.reviewsForCard(card.id).executeAsList()) {
                    if (r.rating !in 1L..4L) continue
                    var id = r.id.removePrefix("${AnkiImporter.SOURCE}:").toLongOrNull()?.takeIf { r.id.startsWith("${AnkiImporter.SOURCE}:") } ?: r.ts
                    while (!revlogIds.add(id)) id++
                    driver.exec(
                        "INSERT INTO revlog VALUES (?, ?, -1, ?, 0, 0, 0, ?, 1)",
                        { bindLong(0, id); bindLong(1, c.id); bindLong(2, r.rating); bindLong(3, minOf(r.elapsed_ms, 60_000L)) },
                        4,
                    )
                }
            }
            }
        } finally {
            driver.close()
        }
        val collection = fs.read(file) { readByteArray() }
        for (suffix in listOf("", "-journal", "-wal", "-shm")) fs.delete(file.parent!! / (file.name + suffix), mustExist = false)

        val mediaEntries = ArrayList<Pair<String, ByteArray>>()
        val mediaMap = buildJsonObject {
            if (mediaDir != null) {
                for (name in mediaNames) {
                    val path = mediaDir / name.substringAfterLast('/')
                    if (!fs.exists(path)) continue
                    val key = mediaEntries.size.toString()
                    put(key, name)
                    mediaEntries += key to fs.read(path) { readByteArray() }
                }
            }
        }
        Zip.write(listOf("collection.anki2" to collection, "media" to mediaMap.toString().encodeToByteArray()) + mediaEntries)
    }

    private fun decodeList(value: String): List<String> =
        if (value.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())

    private fun colConf(deckId: Long, curModel: Long) = buildJsonObject {
        put("nextPos", 1); put("estTimes", true); putJsonArray("activeDecks") { add(JsonPrimitive(deckId)) }
        put("sortType", "noteFld"); put("timeLim", 0); put("sortBackwards", false); put("addToCur", true)
        put("curDeck", deckId); put("newBury", true); put("newSpread", 0); put("dueCounts", true)
        put("curModel", curModel.toString()); put("collapseTime", 1200)
    }.toString()

    private fun decksJson(deckId: Long, name: String, nowSec: Long): String {
        fun deck(id: Long, name: String) = buildJsonObject {
            put("id", id); put("name", name); put("mod", nowSec); put("usn", -1)
            for (k in listOf("lrnToday", "revToday", "newToday", "timeToday")) putJsonArray(k) { add(JsonPrimitive(0)); add(JsonPrimitive(0)) }
            put("collapsed", false); put("browserCollapsed", false); put("desc", ""); put("dyn", 0); put("conf", 1)
            put("extendNew", 0); put("extendRev", 0)
        }
        return JsonObject(mapOf("1" to deck(1, "Default"), deckId.toString() to deck(deckId, name))).toString()
    }

    private fun modelsJson(models: Collection<AnkiNotetypeRef>, deckId: Long, nowSec: Long): String =
        JsonObject(
            models.associate { m ->
                m.id.toString() to buildJsonObject {
                    put("id", m.id); put("name", m.name); put("type", if (m.cloze) 1 else 0); put("mod", nowSec); put("usn", -1)
                    put("sortf", 0); put("did", deckId)
                    put("tmpls", JsonArray(m.templates.map { t ->
                        buildJsonObject {
                            put("name", t.name); put("ord", t.ord); put("qfmt", t.qfmt); put("afmt", t.afmt)
                            put("bqfmt", ""); put("bafmt", ""); put("did", JsonNull); put("bfont", ""); put("bsize", 0)
                        }
                    }))
                    put("flds", JsonArray(m.fields.mapIndexed { i, f ->
                        buildJsonObject {
                            put("name", f); put("ord", i); put("sticky", false); put("rtl", false); put("font", "Arial"); put("size", 20)
                            putJsonArray("media") {}
                        }
                    }))
                    putJsonArray("tags") {}
                    putJsonArray("vers") {}
                    put("css", m.css.ifEmpty { DEFAULT_CSS })
                    put("latexPre", LATEX_PRE); put("latexPost", "\\end{document}"); put("latexsvg", false)
                    put("req", buildJsonArray {
                        m.templates.forEach { t -> add(buildJsonArray { add(JsonPrimitive(t.ord)); add(JsonPrimitive("any")); add(buildJsonArray { add(JsonPrimitive(0)) }) }) }
                    })
                }
            },
        ).toString()

    /** Hands out unique ids (Anki uses millisecond timestamps), skipping ids already taken. */
    private class IdSource(private var next: Long) {
        private val used = HashSet<Long>()
        fun reserve(id: Long) { used += id }
        fun next(): Long {
            while (next in used) next++
            used += next
            return next++
        }
    }

    companion object {
        private val STORY_KEYS = setOf("mystory", "story", "mnemonic")

        /** The notetype used for items that didn't come from Anki. Fixed id so re-exports update the same notetype. */
        val BASIC = AnkiNotetypeRef(
            id = 1_726_000_000_000L,
            name = "Tsumugi",
            fields = listOf("Front", "Reading", "Meaning", "MyStory", "TsumugiId"),
            templates = listOf(
                AnkiTemplateRef(0, "Meaning", "<div class=jp>{{Front}}</div>", "{{FrontSide}}<hr id=answer>{{Meaning}}<br><small>{{MyStory}}</small>"),
                AnkiTemplateRef(1, "Reading", "{{#Reading}}<div class=jp>{{Front}}</div><div>reading?</div>{{/Reading}}", "{{FrontSide}}<hr id=answer>{{Reading}}"),
                AnkiTemplateRef(2, "Recall", "{{Meaning}}", "{{FrontSide}}<hr id=answer><div class=jp>{{Front}}</div>"),
            ),
        )

        fun basicOrd(direction: CardDirection): Int = when (direction) {
            CardDirection.READING -> 1
            CardDirection.RECALL, CardDirection.PRODUCTION -> 2
            else -> 0
        }

        private const val DEFAULT_CSS = ".card { font-family: sans-serif; font-size: 22px; text-align: center; }\n.jp { font-size: 48px; }"
        private const val LATEX_PRE = "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n\\usepackage[utf8]{inputenc}\n" +
            "\\usepackage{amssymb,amsmath}\n\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n"
        private val DCONF = buildJsonObject {
            putJsonObject("1") {
                put("id", 1); put("name", "Default"); put("mod", 0); put("usn", 0); put("maxTaken", 60); put("autoplay", true)
                put("timer", 0); put("replayq", true); put("dyn", false)
                putJsonObject("new") {
                    put("bury", false); putJsonArray("delays") { add(JsonPrimitive(1.0)); add(JsonPrimitive(10.0)) }
                    put("initialFactor", 2500); putJsonArray("ints") { add(JsonPrimitive(1)); add(JsonPrimitive(4)); add(JsonPrimitive(0)) }
                    put("order", 1); put("perDay", 20)
                }
                putJsonObject("rev") {
                    put("bury", false); put("ease4", 1.3); put("ivlFct", 1.0); put("maxIvl", 36500); put("perDay", 200); put("hardFactor", 1.2)
                }
                putJsonObject("lapse") {
                    putJsonArray("delays") { add(JsonPrimitive(10.0)) }
                    put("leechAction", 1); put("leechFails", 8); put("minInt", 1); put("mult", 0.0)
                }
            }
        }.toString()
    }
}
