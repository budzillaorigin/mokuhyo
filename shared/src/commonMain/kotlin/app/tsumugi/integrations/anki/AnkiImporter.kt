package app.tsumugi.integrations.anki

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.jp.Kana
import app.tsumugi.srs.ImportedReview
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.time.Instant

/**
 * What an imported item remembers about its Anki origin (stored as the item's `context` JSON), so it can be
 * exported back with the same note id, guid, notetype and fields (BRIEF §5.4: .apkg round-trips).
 */
@Serializable
data class AnkiItemContext(val anki: AnkiNoteRef)

@Serializable
data class AnkiNoteRef(
    val noteId: Long,
    val guid: String,
    val notetype: AnkiNotetypeRef,
    /** Raw field HTML by field name, in notetype order. */
    val fields: Map<String, String>,
    val tags: String = "",
    val deck: String? = null,
    /** Our card direction name → the Anki card it came from. */
    val cards: Map<String, AnkiCardRef> = emptyMap(),
)

@Serializable
data class AnkiNotetypeRef(
    val id: Long,
    val name: String,
    val cloze: Boolean = false,
    val fields: List<String>,
    val templates: List<AnkiTemplateRef>,
    val css: String = "",
)

@Serializable
data class AnkiTemplateRef(val ord: Int, val name: String, val qfmt: String = "", val afmt: String = "")

@Serializable
data class AnkiCardRef(val id: Long, val ord: Int)

data class AnkiImportResult(
    val format: AnkiPackageFormat,
    val notes: Int,
    val cards: Int,
    val reviews: Int,
    val media: Int,
    /** Notes recognised as NihongoShark kanji notes (imported as KANJI items with their myStory). */
    val nihongoSharkNotes: Int,
    val suspended: Int,
    val itemIds: List<String>,
    val warnings: List<String>,
)

/**
 * Imports an Anki package (.apkg: legacy `collection.anki2/anki21` or zstd `collection.anki21b`) into the
 * SRS: notes become items, cards become cards, the review log is replayed through FSRS.
 *
 * @param workDir scratch directory for the extracted SQLite collection.
 * @param openDriver opens a SQLite file at an absolute path (apps: platform driver with [RawSqliteSchema]).
 * @param mediaDir where media files are written (null = don't extract media).
 */
class AnkiImporter(
    private val srs: SrsRepository,
    private val fs: FileSystem,
    private val workDir: Path,
    private val openDriver: (path: String) -> SqlDriver,
    private val mediaDir: Path? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Throws(Exception::class)
    suspend fun import(apkg: ByteArray): AnkiImportResult {
        val (pkg, collection) = withContext(Dispatchers.IO) {
            val pkg = AnkiPackage.read(apkg)
            pkg to readCollection(pkg.collection)
        }
        val warnings = ArrayList<String>()
        val plan = plan(collection, warnings)

        srs.addItems(plan.items)
        for ((itemId, story) in plan.stories) srs.saveNote(itemId, myStory = story)
        for ((pathItemId, story) in plan.pathStories) {
            val existing = srs.item(pathItemId)
            if (existing == null || existing.myStory.isBlank()) srs.saveNote(pathItemId, myStory = story)
        }
        val reviews = collection.revlog.mapNotNull { r ->
            val cardId = plan.cardIds[r.cid] ?: return@mapNotNull null
            if (r.ease !in 1..4) return@mapNotNull null
            ImportedReview(cardId, Instant.fromEpochMilliseconds(r.id), Rating.entries[r.ease - 1], r.id.toString(), SOURCE)
        }
        if (reviews.isNotEmpty()) srs.importReviews(reviews)
        for (cardId in plan.suspended) srs.setSuspended(cardId, true)

        val reviewed = reviews.map { it.cardId }.toSet()
        val unreviewed = collection.cards.count { it.type == 2 && plan.cardIds[it.id] !in reviewed }
        if (unreviewed > 0) warnings += "$unreviewed review cards had no review history; they start as new cards"

        var media = 0
        if (mediaDir != null && pkg.media.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                fs.createDirectories(mediaDir)
                for ((name, bytes) in pkg.media) {
                    val safe = name.substringAfterLast('/').substringAfterLast('\\')
                    if (safe.isEmpty() || safe == "." || safe == "..") continue
                    fs.write(mediaDir / safe) { write(bytes) }
                    media++
                }
            }
        }
        return AnkiImportResult(
            format = pkg.format,
            notes = collection.notes.size,
            cards = plan.cardIds.size,
            reviews = reviews.size,
            media = media,
            nihongoSharkNotes = plan.nihongoShark,
            suspended = plan.suspended.size,
            itemIds = plan.items.map { it.id },
            warnings = warnings,
        )
    }

    private fun readCollection(bytes: ByteArray): AnkiCollection {
        fs.createDirectories(workDir)
        val file = workDir / "anki-import-${Random.nextLong().toULong()}.anki2"
        fs.write(file) { write(bytes) }
        try {
            val driver = openDriver(file.toString())
            try {
                return AnkiCollection.read(driver)
            } finally {
                driver.close()
            }
        } finally {
            for (suffix in listOf("", "-journal", "-wal", "-shm")) fs.delete(file.parent!! / (file.name + suffix), mustExist = false)
        }
    }

    // --- Mapping ------------------------------------------------------------------------------------------

    private class Plan(
        val items: List<NewItem>,
        val cardIds: Map<Long, String>,
        val stories: Map<String, String>,
        val pathStories: Map<String, String>,
        val suspended: List<String>,
        val nihongoShark: Int,
    )

    private fun plan(c: AnkiCollection, warnings: MutableList<String>): Plan {
        val items = ArrayList<NewItem>()
        val cardIds = HashMap<Long, String>()
        val stories = LinkedHashMap<String, String>()
        val pathStories = LinkedHashMap<String, String>()
        val suspended = ArrayList<String>()
        var nihongoShark = 0
        val cardsByNote = c.cards.groupBy { it.nid }

        for (note in c.notes) {
            val nt = c.notetypes[note.mid] ?: fallbackNotetype(note).also {
                warnings += "note ${note.id} uses an unknown notetype ${note.mid}"
            }
            val fields = nt.fields.mapIndexed { i, name -> name to note.fields.getOrElse(i) { "" } }.toMap(LinkedHashMap())
            val mapped = mapFields(nt, fields)
            if (mapped.isNihongoShark) nihongoShark++
            val noteCards = cardsByNote[note.id].orEmpty().sortedBy { it.ord }
            val deck = noteCards.firstOrNull()?.let { c.decks[it.did] }
            val baseId = "anki:${note.id}"

            // Assign each Anki card a (item, direction). Extra templates beyond recognition/recall and cloze
            // deletions become their own items so card ids stay unique.
            val perItem = LinkedHashMap<String, LinkedHashMap<CardDirection, AnkiCard>>()
            for (card in noteCards) {
                val template = nt.templates.firstOrNull { it.ord == card.ord }
                val direction = when {
                    nt.isCloze -> CardDirection.CLOZE
                    template != null && isRecallTemplate(template.name) -> CardDirection.RECALL
                    card.ord == 1 && nt.templates.size == 2 -> CardDirection.RECALL
                    else -> CardDirection.RECOGNITION
                }
                val base = perItem.getOrPut(baseId) { LinkedHashMap() }
                val target = if (direction in base) perItem.getOrPut("$baseId:${card.ord}") { LinkedHashMap() } else base
                target[direction] = card
            }
            if (perItem.isEmpty()) perItem[baseId] = LinkedHashMap()

            val ntRef = AnkiNotetypeRef(
                nt.id, nt.name, nt.isCloze, nt.fields,
                nt.templates.map { AnkiTemplateRef(it.ord, it.name, it.qfmt, it.afmt) }, nt.css,
            )
            for ((itemId, cards) in perItem) {
                val ref = AnkiNoteRef(
                    noteId = note.id,
                    guid = note.guid,
                    notetype = ntRef,
                    fields = fields,
                    tags = note.tags.trim(),
                    deck = deck,
                    cards = cards.entries.associate { (d, card) -> d.name to AnkiCardRef(card.id, card.ord) },
                )
                items += NewItem(
                    id = itemId,
                    kind = if (mapped.isNihongoShark) ItemKind.KANJI else ItemKind.CUSTOM,
                    primaryText = mapped.primary.ifEmpty { "(Anki note ${note.id})" },
                    reading = mapped.readings.firstOrNull(),
                    meanings = mapped.meanings,
                    acceptedReadings = mapped.readings,
                    source = ItemSource.ANKI,
                    directions = cards.keys.toList().ifEmpty { listOf(CardDirection.RECOGNITION) },
                    packId = PACK_ID,
                    refId = note.id.toString(),
                    context = json.encodeToString(AnkiItemContext(ref)),
                )
                for ((d, card) in cards) {
                    val cardId = SrsRepository.cardId(itemId, d)
                    cardIds[card.id] = cardId
                    if (card.queue == -1) suspended += cardId
                }
                if (mapped.story.isNotBlank()) stories[itemId] = mapped.story
            }
            if (mapped.isNihongoShark && mapped.story.isNotBlank() && mapped.primary.length == 1 && Kana.containsKanji(mapped.primary)) {
                pathStories["k:${mapped.primary}"] = mapped.story
            }
        }
        return Plan(items, cardIds, stories, pathStories, suspended, nihongoShark)
    }

    private class Mapped(
        val primary: String,
        val readings: List<String>,
        val meanings: List<String>,
        val story: String,
        val isNihongoShark: Boolean,
    )

    private fun mapFields(nt: AnkiNotetype, fields: Map<String, String>): Mapped {
        val byKey = fields.entries.associate { AnkiText.key(it.key) to it.value }
        fun find(vararg keys: String): String? = keys.firstNotNullOfOrNull { byKey[it] }
        fun findContaining(vararg parts: String): String? =
            byKey.entries.firstOrNull { (k, _) -> parts.any { k.contains(it) } }?.value

        val kanji = find("kanji", "character", "kanjicharacter")
        val keyword = findContaining("keyword")
        val story = find("mystory", "story", "mnemonic", "mystorymnemonic") ?: findContaining("story")
        val nihongoShark = kanji != null && keyword != null &&
            (story != null || nt.templates.any { AnkiText.key(it.name).contains("keywordtokanji") })

        if (nihongoShark) {
            val on = findContaining("onyomi").orEmpty()
            val kun = findContaining("kunyomi").orEmpty()
            val readings = (AnkiText.splitList(on) + AnkiText.splitList(kun))
                .map { r -> r.substringBefore('.').trim('-', ' ') }.filter { it.isNotEmpty() }.distinct()
            return Mapped(
                primary = AnkiText.oneLine(kanji.orEmpty()),
                readings = readings,
                meanings = AnkiText.splitList(keyword.orEmpty()).ifEmpty { listOf(AnkiText.oneLine(keyword.orEmpty())) },
                story = AnkiText.plain(story.orEmpty()),
                isNihongoShark = true,
            )
        }

        val values = fields.values.toList()
        val primaryRaw = find("expression", "japanese", "word", "vocab", "vocabulary", "kanji", "front", "term", "sentence", "question", "text")
            ?: values.firstOrNull { AnkiText.oneLine(it).isNotEmpty() }.orEmpty()
        val readingRaw = find("reading", "kana", "furigana", "hiragana", "yomi", "pronunciation", "readings")
        val meaningRaw = find("meaning", "english", "definition", "translation", "back", "gloss", "answer", "keyword", "meanings")
            ?: values.firstOrNull { it != primaryRaw && it != readingRaw && AnkiText.oneLine(it).isNotEmpty() }
        return Mapped(
            primary = AnkiText.oneLine(primaryRaw),
            readings = readingRaw?.let(AnkiText::splitList).orEmpty(),
            meanings = meaningRaw?.let(AnkiText::splitList).orEmpty(),
            story = story?.let(AnkiText::plain).orEmpty(),
            isNihongoShark = false,
        )
    }

    private fun isRecallTemplate(name: String): Boolean {
        val k = AnkiText.key(name)
        return listOf("keywordtokanji", "recall", "production", "reverse", "englishto", "meaningto", "backtofront", "entoja")
            .any { k.contains(it) }
    }

    private fun fallbackNotetype(note: AnkiNote) = AnkiNotetype(
        id = note.mid,
        name = "Unknown",
        isCloze = false,
        fields = note.fields.indices.map { "Field ${it + 1}" },
        templates = listOf(AnkiTemplate(0, "Card 1", "{{Field 1}}", "{{FrontSide}}<hr id=answer>{{Field 2}}")),
        css = "",
    )

    companion object {
        const val SOURCE = "anki"
        const val PACK_ID = "anki"
    }
}
