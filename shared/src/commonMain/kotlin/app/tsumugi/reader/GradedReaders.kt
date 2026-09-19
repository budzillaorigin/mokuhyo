package app.tsumugi.reader

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.GradeReadingSummary
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.readers.db.ReadersDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

// --- Graded readers (BRIEF_V2 §6.4, §8 Phase 12; DECISIONS D-200..D-209) ------------------------------------------
//
// The pack is content/packs/readers.sqlite (tools/packs/readers/build_readers.py, schema readers.sq). A story opens
// in the reader like any graded passage (ReaderPackRepository); GradedStory adds what the reader document needs
// around it: vocabulary list, comprehension questions, the genre task set and the read-along lines.

/** One of the six reader levels: "N6" (level 0, [jlpt] 6) … "N1". */
data class ReaderLevelInfo(
    val level: String,
    val label: String,
    val jlpt: Int,
    val ilr: String,
    val storyCount: Int,
    val textScoreMin: Int,
    val textScoreMax: Int,
    /** Target length of the post-reading summary, in characters. */
    val summaryMin: Int,
    val summaryMax: Int,
    /** "ja" (N3 and up) or "en": the language of question stems and task prompts. */
    val questionLanguage: String,
)

data class StoryVocab(val entryId: Long, val word: String, val reading: String, val gloss: String)

/** A comprehension (close-reading) question. [id] is `<story id>-q<ord + 1>`, the key used in quiz attempts. */
data class StoryQuestion(
    val id: String,
    val ord: Int,
    val type: String,
    val language: String,
    val stem: String,
    val choices: List<String>,
    val answer: Int,
    val explanation: String,
)

enum class ReaderTaskKind { PREDICTION, SKIM, CLOSE, OUTPUT }

data class RubricCriterion(val id: String, val en: String, val ja: String)

/**
 * One genre task (tools/packs/readers/tasks.json) for a story, placeholders filled in: [prompt] is in the level's
 * language ([promptJa] from N3 up, [promptEn] below). [seconds] is the skim/scan timer; [minChars]/[maxChars] the
 * summary length for OUTPUT, whose answer is graded by the learner's model ([GradedReaderService.gradeSummary]).
 */
data class ReaderTask(
    val kind: ReaderTaskKind,
    val ord: Int,
    val prompt: String,
    val promptJa: String,
    val promptEn: String,
    val seconds: Int? = null,
    val find: List<String> = emptyList(),
    val minChars: Int? = null,
    val maxChars: Int? = null,
    val rubric: List<RubricCriterion> = emptyList(),
)

/** A story's tasks: before reading, a timed skim/scan, close reading (then [GradedStory.questions]), and output. */
data class ReaderTaskSet(
    val genre: String,
    val prediction: ReaderTask?,
    val skim: ReaderTask?,
    val close: List<ReaderTask>,
    val output: ReaderTask?,
) {
    val all: List<ReaderTask> get() = listOfNotNull(prediction, skim) + close + listOfNotNull(output)
}

data class CastMember(val name: String, val voice: String)

/**
 * One read-along line: a sentence of the body ([start], [end]: String indices, trimmed), who says it ("" =
 * narration) and its voice hint (narration | female | male | male-senior). [clipKey] is the audio pack key.
 */
data class ReadAlongLine(
    val index: Int,
    val start: Int,
    val end: Int,
    val speaker: String,
    val voice: String,
    val clipKey: String,
)

/** A graded story opened as a reader document, with everything around it. */
data class GradedStory(
    val passage: GradedPassage,
    val level: String,
    val genre: String,
    val topic: String,
    val titleEn: String,
    val textScore: Int,
    val label: String,
    /** Share of the story's words within its level (glossed vocabulary included), from the build. */
    val coverage: Double,
    val cast: List<CastMember>,
    val vocabulary: List<StoryVocab>,
    val questions: List<StoryQuestion>,
    val tasks: ReaderTaskSet,
    val lines: List<ReadAlongLine>,
) {
    val id: String get() = passage.summary.id
    val title: String get() = passage.summary.title
    val body: String get() = passage.body

    /** Rule 10: AI-drafted and not yet reviewed; the UI shows the "AI-generated" badge. */
    val isAiGenerated: Boolean get() = passage.summary.source != "verified"

    fun text(line: ReadAlongLine): String = body.substring(line.start, line.end)
}

/** The graded-reader pack over readers.sqlite. [open] returns null while the pack isn't installed (empty state). */
class PackReaderRepository(private val open: suspend () -> ReadersDatabase?) : ReaderPackRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun <T> io(block: (ReadersDatabase) -> T): T? {
        val db = open() ?: return null
        return withContext(Dispatchers.IO) { block(db) }
    }

    override suspend fun packs(): List<ReaderPackInfo> = io { db ->
        val q = db.readersQueries
        val count = q.storyCounts().executeAsList().sumOf { it.stories }.toInt()
        if (count == 0) {
            emptyList()
        } else {
            listOf(ReaderPackInfo(PACK_ID, "Graded readers", q.metaValue("pack_version").executeAsOneOrNull() ?: "0", count))
        }
    }.orEmpty()

    override suspend fun passages(packId: String?, jlpt: Int?): List<GradedPassageSummary> {
        if (packId != null && packId != PACK_ID) return emptyList()
        return io { db ->
            val q = db.readersQueries
            if (jlpt == null) {
                q.allStories { id, _, level, jl, genre, topic, title, titleEn, chars, score, label, ilr, source, verified ->
                    summary(id, level, jl, genre, topic, title, titleEn, chars, score, label, ilr, source, verified)
                }.executeAsList()
            } else {
                q.storiesAtJlpt(jlpt.toLong()) { id, _, level, jl, genre, topic, title, titleEn, chars, score, label, ilr, source, verified ->
                    summary(id, level, jl, genre, topic, title, titleEn, chars, score, label, ilr, source, verified)
                }.executeAsList()
            }
        }.orEmpty()
    }

    override suspend fun passage(id: String): GradedPassage? = io { db ->
        db.readersQueries.storyById(id).executeAsOneOrNull()?.let { s ->
            GradedPassage(
                summary(s.id, s.level, s.jlpt, s.genre, s.topic, s.title, s.title_en, s.chars, s.text_score, s.label, s.ilr, s.source, s.verified),
                s.body, rubyOf(s.ruby), AUTHOR,
            )
        }
    }

    /** The six levels with their story counts, easiest first; empty without the pack. */
    @Throws(Exception::class)
    suspend fun levels(): List<ReaderLevelInfo> = io { db ->
        val q = db.readersQueries
        val counts = q.storyCounts().executeAsList().associate { it.level to it.stories.toInt() }
        q.levels().executeAsList().map {
            ReaderLevelInfo(
                it.level, it.label, it.jlpt.toInt(), it.ilr, counts[it.level] ?: 0, it.text_score_min.toInt(),
                it.text_score_max.toInt(), it.summary_min.toInt(), it.summary_max.toInt(), it.question_language,
            )
        }
    }.orEmpty()

    /** A story with its vocabulary list, questions, genre tasks and read-along lines; null if unknown. */
    @Throws(Exception::class)
    suspend fun story(id: String): GradedStory? = io { db ->
        val q = db.readersQueries
        val s = q.storyById(id).executeAsOneOrNull() ?: return@io null
        val level = q.levels().executeAsList().firstOrNull { it.level == s.level }
        val lang = level?.question_language ?: if (s.jlpt <= 3) "ja" else "en"
        val summary = summary(s.id, s.level, s.jlpt, s.genre, s.topic, s.title, s.title_en, s.chars, s.text_score, s.label, s.ilr, s.source, s.verified)
        val vocabulary = q.vocabulary(id).executeAsList().map { StoryVocab(it.entry_id, it.word, it.reading, it.gloss) }
        val questions = q.questions(id).executeAsList().map {
            StoryQuestion("$id-q${it.ord + 1}", it.ord.toInt(), it.type, it.lang, it.stem, stringsOf(it.choices), it.answer.toInt(), it.explanation)
        }
        val lines = q.sentences(id).executeAsList().map {
            ReadAlongLine(it.idx.toInt(), it.start_offset.toInt(), it.end_offset.toInt(), it.speaker, it.voice, app.tsumugi.audio.AudioKeys.reader(id, it.idx.toInt()))
        }
        val placeholders = mapOf(
            "title" to s.title,
            "seconds" to s.skim_seconds.toString(),
            "min" to (level?.summary_min ?: 0).toString(),
            "max" to (level?.summary_max ?: 0).toString(),
        )
        val tasks = q.tasks(s.genre).executeAsList().map { t ->
            val kind = ReaderTaskKind.valueOf(t.kind)
            val detail = runCatching { json.parseToJsonElement(t.detail).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            val ja = fill(t.prompt_ja, placeholders)
            val en = fill(t.prompt_en, placeholders)
            ReaderTask(
                kind = kind, ord = t.ord.toInt(), prompt = if (lang == "ja") ja else en, promptJa = ja, promptEn = en,
                seconds = s.skim_seconds.toInt().takeIf { kind == ReaderTaskKind.SKIM },
                find = (detail["find"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                minChars = level?.summary_min?.toInt()?.takeIf { kind == ReaderTaskKind.OUTPUT },
                maxChars = level?.summary_max?.toInt()?.takeIf { kind == ReaderTaskKind.OUTPUT },
                rubric = (detail["rubric"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { r ->
                    val o = r as? JsonObject ?: return@mapNotNull null
                    RubricCriterion(o.str("id"), o.str("en"), o.str("ja"))
                }.orEmpty(),
            )
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.ord }))
        GradedStory(
            passage = GradedPassage(summary, s.body, rubyOf(s.ruby), AUTHOR),
            level = s.level, genre = s.genre, topic = s.topic, titleEn = s.title_en, textScore = s.text_score.toInt(),
            label = s.label, coverage = s.coverage, cast = castOf(s.cast_json), vocabulary = vocabulary, questions = questions,
            tasks = ReaderTaskSet(
                s.genre,
                tasks.firstOrNull { it.kind == ReaderTaskKind.PREDICTION },
                tasks.firstOrNull { it.kind == ReaderTaskKind.SKIM },
                tasks.filter { it.kind == ReaderTaskKind.CLOSE },
                tasks.firstOrNull { it.kind == ReaderTaskKind.OUTPUT },
            ),
            lines = lines,
        )
    }

    private fun summary(
        id: String, level: String, jlpt: Long, genre: String, topic: String, title: String, titleEn: String, chars: Long,
        score: Long, label: String, ilr: String, source: String, verified: Long,
    ) = GradedPassageSummary(
        id = id, packId = PACK_ID, title = title, jlpt = jlpt.toInt(), ilr = ilr, length = chars.toInt(),
        source = if (source == "verified" || verified != 0L) "verified" else "llm",
        level = level, genre = genre, topic = topic, titleEn = titleEn, textScore = score.toInt(), label = label,
    )

    private fun stringsOf(text: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), text) }.getOrDefault(emptyList())

    private fun castOf(text: String): List<CastMember> = runCatching {
        json.parseToJsonElement(text).jsonArray.map { CastMember(it.jsonObject.str("name"), it.jsonObject.str("voice")) }
    }.getOrDefault(emptyList())

    private fun rubyOf(text: String): List<RubyHint> = runCatching {
        json.parseToJsonElement(text).jsonArray.map {
            val o = it.jsonObject
            RubyHint(o["start"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0, o.str("base"), o.str("reading"))
        }
    }.getOrDefault(emptyList())

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        /** The pack id in [ReaderPackInfo] and in reader documents' `pack://graded/<story id>` URLs. */
        const val PACK_ID = "graded"
        const val AUTHOR = "Tsumugi contributors (AI-drafted)"

        private val placeholder = Regex("""\{(\w+)\}""")

        internal fun fill(template: String, values: Map<String, String>): String =
            placeholder.replace(template) { m -> values[m.groupValues[1]] ?: m.value }
    }
}

// --- Read along ---------------------------------------------------------------------------------------------------

/** A read-along line with its place in the audio, when the readers audio pack has every line of the story. */
data class TimedLine(val line: ReadAlongLine, val startMs: Long?, val endMs: Long?)

/**
 * The read-along model (D-207): one audio clip per sentence (`AudioKeys.reader`), played in order. With the pack
 * installed and every clip present, [timed] is true and each line carries its start/end in the concatenated
 * audio, so the reader highlights the sentence being spoken. Otherwise there is no highlight timing at all (the
 * reader shows the text and the TTS fallback reads it); a partial set of clips is treated as missing, so the
 * highlight never drifts.
 */
data class ReadAlongTrack(val storyId: String, val lines: List<TimedLine>, val timed: Boolean) {
    val totalMs: Long get() = if (timed) lines.lastOrNull()?.endMs ?: 0 else 0

    /** The line playing at [positionMs] into the story's audio, or null (untimed, or past the end). */
    fun lineAt(positionMs: Long): TimedLine? {
        if (!timed || positionMs < 0) return null
        return lines.firstOrNull { it.startMs!! <= positionMs && positionMs < it.endMs!! }
    }

    /** The line containing the character at [offset] of the body (for tap-to-play). */
    fun lineAtOffset(offset: Int): TimedLine? = lines.firstOrNull { offset >= it.line.start && offset < it.line.end }

    companion object {
        /** Builds the track from [clipMs] (clip key → duration in ms, null when the clip isn't installed). */
        fun of(story: GradedStory, clipMs: (String) -> Long?): ReadAlongTrack {
            val durations = story.lines.map { clipMs(it.clipKey) }
            if (story.lines.isEmpty() || durations.any { it == null || it <= 0 }) {
                return ReadAlongTrack(story.id, story.lines.map { TimedLine(it, null, null) }, timed = false)
            }
            var at = 0L
            val timed = story.lines.zip(durations).map { (line, ms) -> TimedLine(line, at, at + ms!!).also { at += ms } }
            return ReadAlongTrack(story.id, timed, timed = true)
        }
    }
}

// --- Comprehension score (the roadmap hook) -----------------------------------------------------------------------

/** A submitted graded-reader quiz. */
data class ReaderQuizResult(val storyId: String, val level: String, val correct: Int, val total: Int, val attemptId: String) {
    val percent: Double get() = if (total == 0) 0.0 else 100.0 * correct / total
}

@Serializable
internal data class ReaderAnswer(val itemId: String, val choice: Int? = null, val correct: Boolean)

@Serializable
internal data class ReaderScoring(val correct: Int, val total: Int, val storyId: String)

/**
 * Graded-reader quiz results (D-206). Each submitted quiz is an immutable `exam_attempt` row with exam
 * `GRADED_READER`, mode `READER` and the reader level, so it syncs by union like other attempts and needs no new
 * table. [comprehensionPercent] is the roadmap's READER_COMPREHENSION measure: the share of questions answered
 * correctly on the latest attempt of each of the most recent [RECENT_STORIES] stories, or null until
 * [MIN_STORIES] different stories have been answered (one lucky quiz shouldn't meet a milestone).
 */
class GradedReaderScores(
    private val db: TsumugiDatabase,
    private val deviceId: () -> String,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val q get() = db.examAttemptQueries

    /** Scores [choices] (index per question, null = unanswered) against [story] and stores the attempt. */
    @Throws(Exception::class)
    suspend fun submit(story: GradedStory, choices: List<Int?>, startedAtMs: Long? = null): ReaderQuizResult = withContext(Dispatchers.IO) {
        val answers = story.questions.mapIndexed { i, question ->
            val choice = choices.getOrNull(i)
            ReaderAnswer(question.id, choice, choice == question.answer)
        }
        val correct = answers.count { it.correct }
        val id = Uuid.random().toString()
        val now = clock.now().toEpochMilliseconds()
        q.insertAttempt(
            id, EXAM, story.level, MODE, startedAtMs ?: now, now,
            json.encodeToString(ListSerializer(ReaderAnswer.serializer()), answers),
            json.encodeToString(ReaderScoring.serializer(), ReaderScoring(correct, answers.size, story.id)),
            "${story.level} · ${story.title} · $correct/${answers.size}", deviceId(),
        )
        ReaderQuizResult(story.id, story.level, correct, answers.size, id)
    }

    /** The latest result for each story answered, newest first. */
    @Throws(Exception::class)
    suspend fun latestByStory(limit: Int = 500): List<ReaderQuizResult> = withContext(Dispatchers.IO) {
        q.attempts(EXAM, limit.toLong()).executeAsList().mapNotNull { row ->
            val s = runCatching { json.decodeFromString(ReaderScoring.serializer(), row.scoring) }.getOrNull() ?: return@mapNotNull null
            ReaderQuizResult(s.storyId, row.level, s.correct, s.total, row.id)
        }.distinctBy { it.storyId }
    }

    /** Percent correct over the latest attempts of the most recent stories, or null before [MIN_STORIES]. */
    @Throws(Exception::class)
    suspend fun comprehensionPercent(): Double? {
        val recent = latestByStory().take(RECENT_STORIES).filter { it.total > 0 }
        if (recent.size < MIN_STORIES) return null
        val total = recent.sumOf { it.total }
        return (1000.0 * recent.sumOf { it.correct } / total).roundToInt() / 10.0
    }

    companion object {
        const val EXAM = "GRADED_READER"
        const val MODE = "READER"
        const val RECENT_STORIES = 20
        const val MIN_STORIES = 3
    }
}

// --- The service the apps use -------------------------------------------------------------------------------------

/** The learner's graded summary (always AI-generated, rule 10). */
sealed interface SummaryGradeResult {
    data class Graded(val grade: GradeReadingSummary.Output, val engine: String) : SummaryGradeResult {
        val source: String get() = "llm"
    }

    /** No model, or the model failed: the UI explains [reason]; the summary is kept, nothing is faked. */
    data class Unavailable(val reason: String) : SummaryGradeResult
}

/**
 * Graded readers for the apps: levels and stories from the pack, the read-along track (timings from the readers
 * audio pack when installed), quiz scoring for the roadmap, and the post-reading summary graded by the model.
 */
class GradedReaderService(
    val repository: PackReaderRepository,
    val scores: GradedReaderScores,
    private val clipMs: suspend () -> Map<String, Long>?,
    private val gateway: suspend () -> AiGateway,
) {
    @Throws(Exception::class)
    suspend fun levels(): List<ReaderLevelInfo> = repository.levels()

    /** Stories at [jlpt] (6 = level 0 … 1), or all levels when null. */
    @Throws(Exception::class)
    suspend fun stories(jlpt: Int? = null): List<GradedPassageSummary> = repository.passages(PackReaderRepository.PACK_ID, jlpt)

    @Throws(Exception::class)
    suspend fun story(id: String): GradedStory? = repository.story(id)

    /** The read-along track of [story]; untimed when the readers audio pack isn't installed or lacks a line. */
    @Throws(Exception::class)
    suspend fun readAlong(story: GradedStory): ReadAlongTrack {
        val clips = clipMs().orEmpty()
        return ReadAlongTrack.of(story) { clips[it] }
    }

    @Throws(Exception::class)
    suspend fun submitQuiz(story: GradedStory, choices: List<Int?>): ReaderQuizResult = scores.submit(story, choices)

    @Throws(Exception::class)
    suspend fun comprehensionPercent(): Double? = scores.comprehensionPercent()

    /** Grades [summary] against [story] with the learner's model (the OUTPUT task). */
    @Throws(Exception::class)
    suspend fun gradeSummary(story: GradedStory, summary: String): SummaryGradeResult {
        if (summary.isBlank()) return SummaryGradeResult.Unavailable("Write a summary first")
        val task = story.tasks.output
        val input = GradeReadingSummary.Input(
            passage = story.body, summary = summary.trim(), level = if (story.level == "N6") "N5" else story.level,
            instruction = task?.promptEn.orEmpty(),
        )
        return when (val r = gateway().run(GradeReadingSummary(), input)) {
            is AiResult.Ok -> SummaryGradeResult.Graded(r.value, r.engine)
            is AiResult.Fallback -> SummaryGradeResult.Unavailable(r.reason)
            is AiResult.Unavailable -> SummaryGradeResult.Unavailable(r.reason)
        }
    }
}
