package app.tsumugi.integrations.wanikani

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.IntegrationKind
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.RelationKind
import app.tsumugi.jp.Kana
import app.tsumugi.platform.Secrets
import app.tsumugi.srs.ImportedReview
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.PathService
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
data class WaniKaniConfig(
    val username: String = "",
    val level: Int = 0,
    /** Two-way sync: post Tsumugi reviews of WaniKani items back to WaniKani. Off by default (DECISIONS §14 #4). */
    val postReviews: Boolean = false,
    /** Set when WaniKani refused a write with 401/403: the token is read-only. */
    val readOnly: Boolean = false,
)

/** `updated_after` cursors per endpoint, stored in `integration.cursor`. */
@Serializable
data class WaniKaniCursors(
    val assignments: String? = null,
    val reviewStatistics: String? = null,
    val studyMaterials: String? = null,
)

data class WaniKaniImportResult(
    val username: String,
    /** The user's WaniKani level; the UI may offer PathService.skipToLevel. */
    val wanikaniLevel: Int,
    val matchedToPath: Int,
    /** Subjects with no Tsumugi path equivalent, kept as local-only `wk:<id>` items. */
    val wanikaniOnlyItems: Int,
    /** Image-only or WaniKani-specific radicals, which Tsumugi can't represent. */
    val skippedRadicals: Int,
    val seededAssignments: Int,
    val updatedAssignments: Int,
    val studyMaterials: Int,
    val reviewsImported: Int,
)

/**
 * WaniKani API v2 import and optional two-way review sync (BRIEF §5.4, §8.3, §9.1).
 *
 * Terms of service: the token stays in the keychain/keystore ([Secrets], key [TOKEN_KEY]) and is only sent to
 * api.wanikani.com. WaniKani subjects that have a Tsumugi equivalent map onto our own path items (content from
 * our EDRDG-derived pack). Subjects without one become `wk:<subject id>` items holding the meanings/readings the
 * API returned; they exist only in this user's local database, for display to the token owner, and are never
 * written to content packs or shown to anyone else. Mnemonics are never requested fields of our models and are
 * never stored. Only the user's own study materials (notes, synonyms) are copied.
 */
class WaniKaniSync(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val secrets: Secrets,
    private val clientFactory: (token: String) -> WaniKaniClient,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val wk get() = db.wanikaniQueries
    private val user get() = db.userQueries

    fun isConnected(): Boolean = secrets.get(TOKEN_KEY) != null

    fun config(): WaniKaniConfig? =
        user.integration(KIND).executeAsOneOrNull()?.let { json.decodeFromString(WaniKaniConfig.serializer(), it.config) }

    /** Validates [token] against /user, then stores it and the integration row. Throws [WaniKaniException] on 401. */
    @Throws(Exception::class)
    suspend fun connect(token: String): WkUser {
        val client = clientFactory(token.trim())
        val me = try { client.user() } finally { client.close() }
        secrets.put(TOKEN_KEY, token.trim())
        io { saveConfig(WaniKaniConfig(me.username, me.level), cursors(), null) }
        return me
    }

    @Throws(Exception::class)
    suspend fun disconnect() = io {
        secrets.remove(TOKEN_KEY)
        user.removeIntegration(KIND)
        wk.clearAll()
    }

    @Throws(Exception::class)
    suspend fun setPostReviews(enabled: Boolean) = io {
        val current = config() ?: return@io
        saveConfig(current.copy(postReviews = enabled, readOnly = if (enabled) false else current.readOnly), cursors(), lastSync())
    }

    /**
     * Imports progress: assignments seed FSRS history, stage changes since the last import become single
     * reviews, the user's study materials become notes. Incremental through `updated_after` cursors.
     */
    @Throws(Exception::class)
    suspend fun import(pathItems: List<PathItem>, progress: (String) -> Unit = {}): WaniKaniImportResult {
        val token = secrets.get(TOKEN_KEY) ?: throw WaniKaniException(401, "WaniKani is not connected")
        val client = clientFactory(token)
        try {
            val cursors = io { cursors() }
            progress("Checking account…")
            val me = client.user()
            progress("Downloading review statistics…")
            val stats = client.reviewStatistics(cursors.reviewStatistics)
            progress("Downloading assignments…")
            val assignments = client.assignments(cursors.assignments)
            progress("Downloading study materials…")
            val materials = client.studyMaterials(cursors.studyMaterials)

            val mapped = io { wk.mappedSubjectIds().executeAsList().toSet() }
            val needed = (assignments.items.map { it.data.subjectId } + materials.items.map { it.data.subjectId })
                .filter { it !in mapped }.distinct()
            progress("Downloading ${needed.size} subjects…")
            val subjects = needed.chunked(SUBJECT_CHUNK).flatMap { client.subjects(it).items }

            progress("Matching to the Tsumugi path…")
            val mapping = mapSubjects(subjects, pathItems)
            srs.addItems(mapping.newItems)
            io {
                db.transaction {
                    for ((subjectId, target) in mapping.targets) wk.putSubject(subjectId, target.itemId, target.subjectType)
                }
            }

            progress("Converting SRS stages…")
            val statsBySubject = stats.items.associate { it.data.subjectId to it.data }
            val seeded = seedAssignments(assignments.items, statsBySubject)
            srs.importReviews(seeded.reviews)

            progress("Importing your notes and synonyms…")
            val notes = importStudyMaterials(materials.items.map { it.data })

            val newCursors = WaniKaniCursors(
                assignments = assignments.dataUpdatedAt ?: cursors.assignments,
                reviewStatistics = stats.dataUpdatedAt ?: cursors.reviewStatistics,
                studyMaterials = materials.dataUpdatedAt ?: cursors.studyMaterials,
            )
            io {
                val cfg = (config() ?: WaniKaniConfig()).copy(username = me.username, level = me.level)
                saveConfig(cfg, newCursors, clock.now().toEpochMilliseconds())
            }
            return WaniKaniImportResult(
                username = me.username,
                wanikaniLevel = me.level,
                matchedToPath = mapping.matched,
                wanikaniOnlyItems = mapping.wanikaniOnly,
                skippedRadicals = mapping.skippedRadicals,
                seededAssignments = seeded.seeded,
                updatedAssignments = seeded.updated,
                studyMaterials = notes,
                reviewsImported = seeded.reviews.size,
            )
        } finally {
            client.close()
        }
    }

    // --- Two-way: posting reviews back ----------------------------------------------------------------------

    /**
     * Queues a Tsumugi review of a WaniKani subject for posting (offline-safe; sent by [flushQueue]).
     * No-op (returns false) unless the user turned two-way sync on and the token isn't known to be read-only.
     */
    @Throws(Exception::class)
    suspend fun postReview(subjectId: Long, incorrectMeaning: Int, incorrectReading: Int, at: Instant = clock.now()): Boolean = io {
        val cfg = config() ?: return@io false
        if (!cfg.postReviews || cfg.readOnly) return@io false
        wk.enqueueReview(subjectId, incorrectMeaning.toLong(), incorrectReading.toLong(), at.toEpochMilliseconds())
        true
    }

    /** [postReview] by Tsumugi item id; false when the item didn't come from WaniKani. */
    @Throws(Exception::class)
    suspend fun postReviewForItem(itemId: String, incorrectMeaning: Int, incorrectReading: Int, at: Instant = clock.now()): Boolean {
        val subject = io { wk.subjectForItem(itemId).executeAsOneOrNull() } ?: return false
        return postReview(subject, incorrectMeaning, incorrectReading, at)
    }

    @Throws(Exception::class)
    suspend fun queuedReviewCount(): Int = io { wk.queueSize().executeAsOne().toInt() }

    /**
     * Sends queued reviews oldest first (WaniKani reconciles them by `created_at`). Stops at the first transient
     * failure so order is kept; a 401/403 marks the token read-only and turns two-way sync off.
     * Returns how many reviews were accepted.
     */
    @Throws(Exception::class)
    suspend fun flushQueue(): Int {
        val token = secrets.get(TOKEN_KEY) ?: return 0
        val queued = io { wk.queuedReviews().executeAsList() }
        if (queued.isEmpty()) return 0
        val client = clientFactory(token)
        var sent = 0
        try {
            for (q in queued) {
                val status = client.postReview(
                    q.subject_id, q.incorrect_meaning.toInt(), q.incorrect_reading.toInt(), Instant.fromEpochMilliseconds(q.created_at),
                )
                when (status) {
                    in 200..299 -> { io { wk.dequeueReview(q.id) }; sent++ }
                    401, 403 -> {
                        io {
                            val cfg = config() ?: WaniKaniConfig()
                            saveConfig(cfg.copy(readOnly = true, postReviews = false), cursors(), lastSync())
                        }
                        break
                    }
                    422 -> io { wk.dequeueReview(q.id) } // rejected for good (e.g. not available for review)
                    else -> break
                }
            }
        } finally {
            client.close()
        }
        return sent
    }

    // --- Mapping ---------------------------------------------------------------------------------------------

    private class Target(val itemId: String, val subjectType: String)

    private class Mapping(
        val targets: Map<Long, Target>,
        val newItems: List<NewItem>,
        val matched: Int,
        val wanikaniOnly: Int,
        val skippedRadicals: Int,
    )

    private fun mapSubjects(subjects: List<WkResource<WkSubject>>, pathItems: List<PathItem>): Mapping {
        val pathById = pathItems.associateBy { it.id }
        val vocabByText = pathItems.filter { it.kind == ItemKind.VOCAB }.groupBy { it.text }
        val targets = HashMap<Long, Target>()
        val newItems = ArrayList<NewItem>()
        var matched = 0
        var wkOnly = 0
        var skipped = 0
        for (s in subjects) {
            val chars = s.data.characters
            val pathItem = when (s.type) {
                "radical" -> chars?.let { pathById["r:$it"] }
                "kanji" -> chars?.let { pathById["k:$it"] }
                "vocabulary", "kana_vocabulary" -> chars?.let { vocabByText[it]?.firstOrNull() }
                else -> null
            }
            when {
                pathItem != null -> {
                    targets[s.id] = Target(pathItem.id, s.type)
                    newItems += pathItem.toNewItem()
                    matched++
                }
                s.type == "radical" -> skipped++
                chars != null && s.type in WK_ITEM_TYPES -> {
                    val item = s.toWaniKaniItem(chars)
                    targets[s.id] = Target(item.id, s.type)
                    newItems += item
                    wkOnly++
                }
            }
        }
        return Mapping(targets, newItems, matched, wkOnly, skipped)
    }

    private fun PathItem.toNewItem() = NewItem(
        id = id,
        kind = kind,
        primaryText = text,
        reading = readings.firstOrNull(),
        meanings = meanings,
        acceptedReadings = readings,
        source = ItemSource.PACK,
        directions = PathService.directionsFor(kind),
        level = level,
        jlpt = jlpt,
        packId = PathService.PACK_ID,
        refId = entryId?.toString() ?: text,
        relations = prerequisites.map { it to RelationKind.COMPONENT },
    )

    /** Local-only item for a subject Tsumugi's path doesn't have (see class KDoc on terms of use). */
    private fun WkResource<WkSubject>.toWaniKaniItem(chars: String): NewItem {
        val kanaVocab = type == "kana_vocabulary"
        val meanings = data.meanings.filter { it.acceptedAnswer }.sortedByDescending { it.primary }.map { it.meaning }
        val readings = if (kanaVocab) listOf(chars) else data.readings.filter { it.acceptedAnswer }
            .sortedByDescending { it.primary }.map { Kana.toHiragana(it.reading) }.distinct()
        val kind = if (type == "kanji") ItemKind.KANJI else ItemKind.VOCAB
        return NewItem(
            id = "wk:$id",
            kind = kind,
            primaryText = chars,
            reading = readings.firstOrNull(),
            meanings = meanings,
            acceptedReadings = readings,
            source = ItemSource.WANIKANI,
            directions = if (kanaVocab) listOf(CardDirection.MEANING) else PathService.directionsFor(kind),
            level = null,
            packId = "wanikani",
            refId = id.toString(),
        )
    }

    // --- Assignments -> reviews ------------------------------------------------------------------------------

    private class Seeded(val reviews: List<ImportedReview>, val seeded: Int, val updated: Int)

    private suspend fun seedAssignments(
        assignments: List<WkResource<WkAssignment>>,
        stats: Map<Long, WkReviewStatistic>,
    ): Seeded {
        val now = clock.now()
        val params = srs.scheduler.parameters
        val reviews = ArrayList<ImportedReview>()
        var seeded = 0
        var updated = 0
        for (a in assignments) {
            val data = a.data
            if (data.hidden || data.startedAt == null || data.srsStage <= 0) continue
            val target = io { wk.subjectById(data.subjectId).executeAsOneOrNull() } ?: continue
            val cards = srs.cardsForItems(listOf(target.item_id))
            if (cards.isEmpty()) continue
            val previous = io { wk.assignmentById(a.id).executeAsOneOrNull() }
            val stat = stats[data.subjectId]

            if (previous == null) {
                val startedAt = runCatching { Instant.parse(data.startedAt) }.getOrDefault(now)
                val last = WaniKaniStageMapping.estimateLastReview(data, now)
                val seedLength = WaniKaniStageMapping.seedLength(data.srsStage, params)
                for (card in cards) {
                    val (correct, incorrect) = when (card.direction) {
                        CardDirection.READING -> (stat?.readingCorrect ?: 0) to (stat?.readingIncorrect ?: 0)
                        else -> (stat?.meaningCorrect ?: 0) to (stat?.meaningIncorrect ?: 0)
                    }
                    val lapses = WaniKaniStageMapping.lapsesFor(correct, incorrect, seedLength)
                    WaniKaniStageMapping.syntheticReviews(data.srsStage, startedAt, last, lapses, params)
                        .forEachIndexed { n, (at, rating) ->
                            reviews += ImportedReview(card.id, at, rating, "wk-seed:${a.id}:${card.direction.name}:$n", SOURCE)
                        }
                }
                seeded++
            } else if (previous.srs_stage.toInt() != data.srsStage) {
                // A stage change since the last import is one review done on WaniKani: up = recalled, down = missed.
                val at = a.dataUpdatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now
                val rating = if (data.srsStage > previous.srs_stage) Rating.GOOD else Rating.AGAIN
                for (card in cards) {
                    reviews += ImportedReview(card.id, minOf(at, now), rating, "wk-update:${a.id}:${card.direction.name}:${a.dataUpdatedAt}", SOURCE)
                }
                updated++
            }
            io { wk.putAssignment(a.id, data.subjectId, data.srsStage.toLong(), a.dataUpdatedAt) }
        }
        return Seeded(reviews, seeded, updated)
    }

    // --- Study materials -----------------------------------------------------------------------------------

    private suspend fun importStudyMaterials(materials: List<WkStudyMaterial>): Int {
        var count = 0
        for (m in materials) {
            val itemId = io { wk.subjectById(m.subjectId).executeAsOneOrNull() }?.item_id ?: continue
            val existing = srs.item(itemId) ?: continue
            val synonyms = (existing.synonyms + m.meaningSynonyms).distinct()
            var story = existing.myStory
            for ((label, note) in listOf("meaning" to m.meaningNote, "reading" to m.readingNote)) {
                val text = note?.trim().orEmpty()
                if (text.isNotEmpty() && text !in story) {
                    story = listOf(story, "[Your WaniKani $label note] $text").filter { it.isNotBlank() }.joinToString("\n\n")
                }
            }
            srs.saveNote(itemId, myStory = story, synonyms = synonyms)
            count++
        }
        return count
    }

    // --- Integration row ------------------------------------------------------------------------------------

    private fun cursors(): WaniKaniCursors =
        user.integration(KIND).executeAsOneOrNull()?.cursor?.let { json.decodeFromString(WaniKaniCursors.serializer(), it) }
            ?: WaniKaniCursors()

    private fun lastSync(): Long? = user.integration(KIND).executeAsOneOrNull()?.last_sync_at

    private fun saveConfig(config: WaniKaniConfig, cursors: WaniKaniCursors, lastSyncAt: Long?) {
        user.putIntegration(
            KIND,
            json.encodeToString(WaniKaniConfig.serializer(), config),
            lastSyncAt,
            json.encodeToString(WaniKaniCursors.serializer(), cursors),
        )
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val TOKEN_KEY = "wanikani.token"
        const val SOURCE = "wanikani"
        private val KIND = IntegrationKind.WANIKANI.name
        private const val SUBJECT_CHUNK = 100
        private val WK_ITEM_TYPES = setOf("kanji", "vocabulary", "kana_vocabulary")
    }
}
