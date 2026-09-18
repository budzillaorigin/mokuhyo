package app.tsumugi.grammar

import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.Stage
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.grammar.db.Grammar_point
import app.tsumugi.jp.Deinflector
import app.tsumugi.jp.Kana
import app.tsumugi.jp.Romaji
import app.tsumugi.srs.CardState
import app.tsumugi.srs.CheckResult
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

data class GrammarPoint(
    val id: String,
    val jlpt: Int,
    val order: Int,
    val title: String,
    val structure: String,
    val meaning: String,
    val nuance: String,
    val mistakes: List<String>,
    val related: List<String>,
    val textbooks: Map<String, String>,
    /** LLM-drafted explanations show an "AI-generated" badge until reviewed (CLAUDE.md rule 10). */
    val source: ItemSource,
) {
    val itemId: String get() = GrammarService.itemId(id)
}

data class GrammarExample(
    val japanese: String,
    val english: String,
    val blankStart: Int,
    val blankEnd: Int,
    /** "tatoeba" (human-written, CC BY 2.0 FR) or "llm" (labelled AI-generated). */
    val source: String,
) {
    val before: String get() = japanese.substring(0, blankStart)
    val answer: String get() = japanese.substring(blankStart, blankEnd)
    val after: String get() = japanese.substring(blankEnd)
    val isAiGenerated: Boolean get() = source == "llm"
}

enum class ExerciseKind {
    /** Type the missing grammar in a sentence (the English translation is the hint). */
    CLOZE,
    /** Put shuffled chunks of the sentence in order. */
    BUILD,
}

data class GrammarExercise(
    val point: GrammarPoint,
    val example: GrammarExample,
    val kind: ExerciseKind,
    /** BUILD: the sentence's chunks in shuffled order (the grammar construction is one chunk). */
    val chunks: List<String>,
) {
    /** The sentence with the construction blanked, e.g. "宿題を＿＿＿。". */
    val prompt: String get() = example.before + BLANK + example.after

    companion object {
        const val BLANK = "＿＿＿"
    }
}

data class GrammarPointStatus(val point: GrammarPoint, val stage: Stage?)

data class GrammarPointDetail(val point: GrammarPoint, val examples: List<GrammarExample>, val stage: Stage?, val myNote: String)

/**
 * Grammar as SRS (BRIEF §5.5): JLPT-ordered points, cloze and sentence-building reviews over real example
 * sentences, and Bunpro-style ghost reviews for misses. Content comes from the read-only grammar pack; progress
 * lives in [SrsRepository] under item ids "g:<point id>".
 */
class GrammarService(
    private val pack: GrammarDatabase,
    private val srs: SrsRepository,
    private val dictionary: suspend () -> DictionaryRepository?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val q get() = pack.grammarQueries

    suspend fun levels(): List<Int> = io { q.allPoints().executeAsList().map { it.jlpt.toInt() }.distinct() }

    suspend fun points(jlpt: Int): List<GrammarPointStatus> {
        val points = io { q.pointsAtLevel(jlpt.toLong()).executeAsList().map { it.toPoint() } }
        val stages = srs.stages()
        return points.map { GrammarPointStatus(it, stages[it.itemId]) }
    }

    suspend fun point(id: String): GrammarPointDetail? {
        val point = io { q.pointsByIds(listOf(id)).executeAsOneOrNull()?.toPoint() } ?: return null
        return GrammarPointDetail(point, examples(id), srs.stages()[point.itemId], srs.note(point.itemId).myStory)
    }

    suspend fun examples(pointId: String): List<GrammarExample> = io {
        q.examplesFor(pointId).executeAsList().map {
            GrammarExample(it.ja, it.en, it.blank_start.toInt(), it.blank_end.toInt(), it.source)
        }
    }

    /** Next points to learn: easiest level first, in teaching order, skipping ones already started. */
    suspend fun lessonQueue(limit: Int): List<GrammarPoint> {
        val started = srs.stages().keys
        return io { q.allPoints().executeAsList() }.map { it.toPoint() }.filter { it.itemId !in started }.take(limit)
    }

    /** Adds points to reviews (one cloze card each) and introduces them. */
    suspend fun learn(points: List<GrammarPoint>) {
        srs.addItems(points.map { p ->
            NewItem(
                id = p.itemId, kind = ItemKind.GRAMMAR, primaryText = p.title, reading = p.structure,
                meanings = listOf(p.meaning), acceptedReadings = emptyList(), source = p.source,
                directions = listOf(CardDirection.CLOZE), jlpt = p.jlpt, packId = PACK_ID, refId = p.id,
            )
        })
        srs.introduce(points.map { SrsRepository.cardId(it.itemId, CardDirection.CLOZE) })
    }

    /** A fresh exercise for a review: a random example, cloze or (when chunkable) sentence building. */
    suspend fun exercise(pointId: String, random: Random = Random.Default): GrammarExercise? {
        val point = io { q.pointsByIds(listOf(pointId)).executeAsOneOrNull()?.toPoint() } ?: return null
        val examples = examples(pointId).ifEmpty { return null }
        val example = examples.random(random)
        val chunks = if (random.nextInt(3) == 0) chunks(example) else emptyList()
        return if (chunks.size >= MIN_BUILD_CHUNKS) {
            GrammarExercise(point, example, ExerciseKind.BUILD, chunks.shuffledDistinctFrom(random))
        } else {
            GrammarExercise(point, example, ExerciseKind.CLOZE, emptyList())
        }
    }

    /**
     * Cloze answers: exact match (romaji converts, katakana folds) is correct; another valid form of the same
     * construction (matches the point's patterns and shares a dictionary form) is accepted as CLOSE.
     */
    suspend fun check(exercise: GrammarExercise, answer: String): CheckResult {
        if (exercise.kind == ExerciseKind.BUILD) {
            val ok = normalize(answer) == normalize(exercise.example.japanese)
            return CheckResult(if (ok) Verdict.CORRECT else Verdict.WRONG, exercise.example.japanese)
        }
        val given = normalize(answer)
        val expected = normalize(exercise.example.answer)
        if (given.isEmpty()) return CheckResult(Verdict.WRONG)
        if (given == expected) return CheckResult(Verdict.CORRECT, exercise.example.answer)
        val patterns = io { q.patternsFor(exercise.point.id).executeAsList() }.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        val fitsConstruction = patterns.any { it.containsMatchIn(given) }
        val sameBase = Deinflector.deinflect(given).map { it.term }.toSet()
            .intersect(Deinflector.deinflect(expected).map { it.term }.toSet())
            .isNotEmpty()
        return if (fitsConstruction && sameBase) CheckResult(Verdict.CLOSE, exercise.example.answer) else CheckResult(Verdict.WRONG)
    }

    /**
     * A miss spawns the point's ghost card: an extra card on the learning steps (10 min, then 1 day). A retired
     * ghost is revived with a lapse so it drops back to short intervals.
     */
    suspend fun spawnGhost(itemId: String) {
        val cardId = SrsRepository.cardId(itemId, CardDirection.GHOST)
        val existing = srs.card(cardId)
        if (existing == null) {
            val item = srs.item(itemId) ?: return
            srs.addItems(listOf(
                NewItem(
                    id = item.id, kind = item.kind, primaryText = item.primaryText, reading = item.reading,
                    meanings = item.meanings, acceptedReadings = item.acceptedReadings, source = item.source,
                    directions = listOf(CardDirection.GHOST), jlpt = item.jlpt, packId = PACK_ID, refId = item.id.removePrefix("g:"),
                ),
            ))
            srs.introduce(listOf(cardId))
        } else if (existing.suspended) {
            srs.setSuspended(cardId, false)
            srs.review(cardId, Rating.AGAIN)
        }
    }

    /** Two correct ghost answers in a row graduate it off the learning steps; then it retires (suspended). */
    suspend fun ghostAnswered(cardId: String, correct: Boolean) {
        if (correct && srs.card(cardId)?.fsrs?.state == CardState.REVIEW) srs.setSuspended(cardId, true)
    }

    /** Splits a sentence into phrase-sized chunks for BUILD exercises; the grammar construction is one chunk. */
    private suspend fun chunks(example: GrammarExample): List<String> {
        val dict = dictionary() ?: return emptyList()
        val end = example.japanese.trimEnd('。', '？', '！', '?', '!').length.coerceAtLeast(example.blankEnd)
        val before = phrases(dict, example.japanese.substring(0, example.blankStart))
        val after = phrases(dict, example.japanese.substring(example.blankEnd, end))
        val pieces = before + example.answer + after
        return if (pieces.size in MIN_BUILD_CHUNKS..MAX_BUILD_CHUNKS) pieces else emptyList()
    }

    /** Groups tokens into phrases, closing a phrase after a one-kana particle or once it is 5+ characters. */
    private suspend fun phrases(dict: DictionaryRepository, text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (t in dict.tokenize(text)) {
            buffer.append(t.surface)
            if ((t.surface.length == 1 && Kana.isAllKana(t.surface)) || buffer.length >= 5) {
                out += buffer.toString()
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) out += buffer.toString()
        return out
    }

    private fun List<String>.shuffledDistinctFrom(random: Random): List<String> {
        repeat(10) { val s = shuffled(random); if (s != this) return s }
        return reversed()
    }

    private fun normalize(s: String): String {
        val kana = if (s.any { it in 'a'..'z' || it in 'A'..'Z' }) Romaji.finalize(Romaji.toHiragana(s.lowercase())) else s
        return Kana.toHiragana(kana).filterNot { it.isWhitespace() || it in "。、？！?!.," }
    }

    private fun Grammar_point.toPoint() = GrammarPoint(
        id = id, jlpt = jlpt.toInt(), order = ord.toInt(), title = title, structure = structure, meaning = meaning,
        nuance = nuance, mistakes = json.decodeFromString(mistakes), related = json.decodeFromString(related),
        textbooks = json.decodeFromString(textbooks),
        source = if (source == "verified") ItemSource.VERIFIED else ItemSource.LLM,
    )

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val PACK_ID = "grammar"
        const val GHOST_CORRECT_TO_RETIRE = 2
        private const val MIN_BUILD_CHUNKS = 4
        private const val MAX_BUILD_CHUNKS = 8

        fun itemId(pointId: String) = "g:$pointId"
    }
}
