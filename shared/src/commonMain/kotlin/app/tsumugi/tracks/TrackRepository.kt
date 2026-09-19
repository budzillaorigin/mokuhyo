package app.tsumugi.tracks

import app.tsumugi.practice.ComprehensionQuestion
import app.tsumugi.practice.Dialogue
import app.tsumugi.practice.DialogueLine
import app.tsumugi.practice.DialogueSummary
import app.tsumugi.practice.Register
import app.tsumugi.practice.Scenario
import app.tsumugi.practice.ScriptedTurn
import app.tsumugi.tracks.db.Track as TrackRow
import app.tsumugi.tracks.db.Track_dialogue
import app.tsumugi.tracks.db.Track_drill
import app.tsumugi.tracks.db.Track_scenario
import app.tsumugi.tracks.db.Track_word
import app.tsumugi.tracks.db.TracksDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Read access to the tracks pack (content/packs/tracks.sqlite, tools/packs/build_tracks.py). Scenarios and dialogues
 * come back as the practice models, so `RoleplaySession` and the listening screens take them as they are.
 */
class TrackRepository(private val db: TracksDatabase) {
    private val q get() = db.tracksQueries
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(Exception::class)
    suspend fun tracks(): List<Track> = io { q.allTracks().executeAsList().map { it.toModel() } }

    @Throws(Exception::class)
    suspend fun track(id: String): Track? = io { q.trackById(id).executeAsOneOrNull()?.toModel() }

    /** The word list in study order. */
    @Throws(Exception::class)
    suspend fun words(trackId: String): List<TrackWord> = io { q.words(trackId).executeAsList().map { it.toModel() } }

    /** Lessons for the track page: consecutive words of one topic, at most [size] per lesson. */
    @Throws(Exception::class)
    suspend fun lessons(trackId: String, size: Int = DEFAULT_LESSON_SIZE): List<TrackLesson> = lessonsOf(trackId, words(trackId), size)

    @Throws(Exception::class)
    suspend fun kanji(trackId: String): List<TrackKanji> = io {
        q.kanji(trackId).executeAsList().map {
            TrackKanji(it.literal, it.keyword, json.decodeFromString(it.components), it.breakdown, it.hint, json.decodeFromString(it.words), it.source)
        }
    }

    @Throws(Exception::class)
    suspend fun scenarios(trackId: String): List<Scenario> = io { q.scenarios(trackId).executeAsList().map { it.toModel() } }

    @Throws(Exception::class)
    suspend fun scenario(id: String): Scenario? = io { q.scenarioById(id).executeAsOneOrNull()?.toModel() }

    @Throws(Exception::class)
    suspend fun scriptedTurns(scenarioId: String): List<ScriptedTurn> = io {
        q.scriptedTurns(scenarioId).executeAsList().map { ScriptedTurn(it.partner_ja, it.partner_en, it.intent, it.sample_answer) }
    }

    @Throws(Exception::class)
    suspend fun dialogues(trackId: String): List<DialogueSummary> = io {
        q.dialogues(trackId).executeAsList().map { DialogueSummary(it.id, it.title, it.jlpt.toInt(), it.topic, it.source) }
    }

    @Throws(Exception::class)
    suspend fun dialogue(id: String): Dialogue? = io { q.dialogueById(id).executeAsOneOrNull()?.let { toModel(it) } }

    /** Drills of a track, optionally of one [type]; rows of unknown types (a newer pack) are skipped. */
    @Throws(Exception::class)
    suspend fun drills(trackId: String, type: DrillType? = null): List<TrackDrill> = io {
        (if (type == null) q.drills(trackId) else q.drillsOfType(trackId, type.code)).executeAsList().mapNotNull { it.toModel() }
    }

    @Throws(Exception::class)
    suspend fun drill(id: String): TrackDrill? = io { q.drillById(id).executeAsOneOrNull()?.toModel() }

    @Throws(Exception::class)
    suspend fun situations(trackId: String): List<TrackSituation> = io {
        q.situations(trackId).executeAsList().map { TrackSituation(it.id, it.track_id, it.title_en, it.title_ja, json.decodeFromString(it.can_do), it.source) }
    }

    @Throws(Exception::class)
    suspend fun tasks(trackId: String): List<CultureTask> = io {
        q.tasks(trackId).executeAsList().map {
            val p = json.decodeFromString<TaskPayload>(it.payload)
            CultureTask(it.id, it.track_id, it.title_en, it.title_ja, p.place, p.before, p.during, p.after, p.phrases, p.etiquette, it.source)
        }
    }

    @Throws(Exception::class)
    suspend fun readings(trackId: String): List<TrackReading> = io {
        q.readings(trackId).executeAsList().map { TrackReading(it.id, it.track_id, it.title, it.ilr, it.genre, it.body, json.decodeFromString(it.questions), it.source) }
    }

    @Throws(Exception::class)
    suspend fun links(trackId: String): List<TrackLink> = io { q.links(trackId).executeAsList().map { TrackLink(it.title, it.url, it.note) } }

    @Throws(Exception::class)
    suspend fun packVersion(): String? = io { q.metaValue("pack_version").executeAsOneOrNull() }

    @Serializable
    private data class TaskPayload(
        val place: String, val before: List<String>, val during: List<String>, val after: List<String>,
        val phrases: List<String> = emptyList(), val etiquette: List<String> = emptyList(),
    )

    private fun TrackRow.toModel() = Track(
        id, title_en, title_ja, description, inspired_by, jlpt_min.toInt(), jlpt_max.toInt(), ilr,
        json.decodeFromString(counts), license, attribution, source,
    )

    private fun Track_word.toModel() = TrackWord(
        track_id, ord.toInt(), entry_id, text, reading, gloss, topic, category, jlpt?.toInt(),
        accents.split(',').mapNotNull { it.trim().toIntOrNull() }, note, source,
    )

    private fun Track_scenario.toModel() = Scenario(
        id = id, titleEn = title_en, titleJa = title_ja, jlpt = jlpt.toInt(), ilr = ilr, category = category, setting = setting,
        learnerRole = learner_role, partnerRole = partner_role, register = Register.valueOf(register.uppercase()),
        goals = json.decodeFromString(goals), vocabulary = json.decodeFromString(vocabulary), phrases = json.decodeFromString(phrases),
        systemPrompt = system_prompt, source = source,
    )

    private fun toModel(row: Track_dialogue): Dialogue {
        val lines = q.dialogueLines(row.id).executeAsList().map {
            DialogueLine(it.speaker, it.ja, it.en, json.decodeFromString(it.gaps), json.decodeFromString(it.chunks))
        }
        val questions = q.dialogueQuestions(row.id).executeAsList().map {
            ComprehensionQuestion(it.question_en, json.decodeFromString(it.choices), it.answer.toInt())
        }
        return Dialogue(row.id, row.title, row.jlpt.toInt(), row.topic, json.decodeFromString(row.speakers), lines, questions, row.source)
    }

    private fun Track_drill.toModel(): TrackDrill? =
        runCatching { DrillPayloads.parse(id, track_id, type, topic, jlpt?.toInt(), payload, source) }.getOrNull()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DEFAULT_LESSON_SIZE = 8

        /** Splits a word list into lessons: a new lesson at every topic change or after [size] words. */
        fun lessonsOf(trackId: String, words: List<TrackWord>, size: Int = DEFAULT_LESSON_SIZE): List<TrackLesson> {
            val out = mutableListOf<TrackLesson>()
            var current = mutableListOf<TrackWord>()
            for (w in words) {
                if (current.isNotEmpty() && (current.size >= size || current.last().topic != w.topic)) {
                    out += TrackLesson(trackId, out.size, current.first().topic, current)
                    current = mutableListOf()
                }
                current += w
            }
            if (current.isNotEmpty()) out += TrackLesson(trackId, out.size, current.first().topic, current)
            return out
        }
    }
}
