package app.tsumugi.grammar

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.GradeProduction
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.Stage
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.grammar.db.Grammar_point
import app.tsumugi.integrations.bunpro.GrammarTitles
import app.tsumugi.jp.Deinflector
import app.tsumugi.jp.Kana
import app.tsumugi.jp.Romaji
import app.tsumugi.srs.AnswerChecker
import app.tsumugi.srs.CardState
import app.tsumugi.srs.CheckResult
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
    /** Cloze with the point's title, structure and meaning shown as the hint (young cards; BRIEF §5.5 fill-in with hint). */
    FILL_HINT,
    /** Reading recognition: the whole sentence with the construction marked; choose what it means from four. */
    MEANING_CHOICE,
    /** Translate the English sentence using the point; graded by `grade_production` + rule checks, or self-graded. */
    PRODUCTION,
}

data class GrammarExercise(
    val point: GrammarPoint,
    val example: GrammarExample,
    val kind: ExerciseKind,
    /** BUILD: the sentence's chunks in shuffled order (the grammar construction is one chunk). */
    val chunks: List<String>,
    /** MEANING_CHOICE: four meanings, one of them [point]'s. */
    val choices: List<String> = emptyList(),
) {
    /** The sentence with the construction blanked, e.g. "宿題を＿＿＿。". */
    val prompt: String get() = example.before + BLANK + example.after

    /** MEANING_CHOICE: the sentence with the construction in 【】, e.g. "切符を買っ【ておいた】。". */
    val marked: String get() = example.before + "【" + example.answer + "】" + example.after

    /** MEANING_CHOICE: index of the right choice. */
    val correctChoice: Int get() = choices.indexOf(point.meaning)

    /** FILL_HINT: what the hint shows besides the translation. */
    val pointHint: String get() = "${point.title} (${point.structure}): ${point.meaning}"

    companion object {
        const val BLANK = "＿＿＿"
    }
}

data class GrammarPointStatus(
    val point: GrammarPoint,
    val stage: Stage?,
    /** The pack has no example sentence for this point yet: its cards are held out of reviews (BRIEF_V2 F-20). */
    val noExamples: Boolean = false,
)

/** What a miss did to the point's ghost card, so an undo of that answer can take it back exactly (BRIEF_V2 F-20). */
data class GhostSpawn(
    val cardId: String,
    /** Reviews the spawn recorded (the ghost's introduction, or the AGAIN that revived a retired ghost). */
    val reviewIds: List<String>,
    /** The ghost was retired (suspended) and has been revived. */
    val revived: Boolean,
)

data class GrammarPointDetail(val point: GrammarPoint, val examples: List<GrammarExample>, val stage: Stage?, val myNote: String)

/** A textbook the grammar pack has a chapter mapping for (numbers only, no book content; DECISIONS D-104). */
data class Textbook(val id: String, val title: String, val unit: String)

/** One chapter of a textbook-order path, with the pack's points it covers. */
data class TextbookChapter(val chapter: String, val number: Int, val points: List<GrammarPointStatus>)

/** Rule checks on a production answer, done without a model (BRIEF §5.5 "rule checks"). */
data class ProductionChecks(
    /** The answer contains Japanese at all. */
    val japanese: Boolean,
    /** The point's detection pattern matched the answer; null when the point has no usable pattern. */
    val constructionFound: Boolean?,
    /** The construction's form deinflects to the same base as the model answer's (conjugation sanity); null when unknown. */
    val formConsistent: Boolean?,
    /** Identical to the model answer after kana/punctuation normalization. */
    val matchesModel: Boolean,
)

/**
 * The grade of a production answer. [modelAnswer] is always shown. With a model, [verdict] comes from the
 * `grade_production` rubric capped by the rule checks, and [rubric] holds the scores and feedback (AI-generated,
 * labelled with [engine]). Without one (or when the model failed, [unavailable]), [selfGrade] is true: the UI shows the
 * model answer and the learner grades themselves.
 */
data class ProductionGrade(
    val modelAnswer: String,
    val english: String,
    val checks: ProductionChecks,
    val verdict: Verdict?,
    val rubric: GradeProduction.Output?,
    val engine: String?,
    val unavailable: String?,
) {
    val selfGrade: Boolean get() = verdict == null
}

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

    @Throws(Exception::class)
    suspend fun levels(): List<Int> = io { q.allPoints().executeAsList().map { it.jlpt.toInt() }.distinct() }

    @Throws(Exception::class)
    suspend fun points(jlpt: Int): List<GrammarPointStatus> {
        val points = io { q.pointsAtLevel(jlpt.toLong()).executeAsList().map { it.toPoint() } }
        val stages = srs.stagesFor(points.map { it.itemId })
        val empty = io { points.filter { q.examplesFor(it.id).executeAsList().isEmpty() }.map { it.id }.toSet() }
        return points.map { GrammarPointStatus(it, stages[it.itemId], noExamples = it.id in empty) }
    }

    /**
     * Holds every grammar card whose point has no example sentence in this pack out of reviews, and releases
     * ones whose point now has examples. Run when the pack is opened (AppGraph), so a card never counts as due
     * while no exercise can be built for it. Device-local: packs differ per device (DECISIONS D-045).
     */
    @Throws(Exception::class)
    suspend fun syncExampleAvailability() {
        val cards = srs.cardsOfKind(ItemKind.GRAMMAR)
        if (cards.isEmpty()) return
        val pointIds = cards.map { it.itemId.removePrefix("g:") }.toSet()
        val withExamples = io { pointIds.filter { q.examplesFor(it).executeAsList().isNotEmpty() }.toSet() }
        val (ok, missing) = cards.partition { it.itemId.removePrefix("g:") in withExamples }
        srs.setBlocked(missing.filter { it.blockedReason != SrsRepository.BLOCK_NO_EXAMPLES }.map { it.id }, SrsRepository.BLOCK_NO_EXAMPLES)
        srs.setBlocked(ok.filter { it.blockedReason == SrsRepository.BLOCK_NO_EXAMPLES }.map { it.id }, null)
    }

    /** Point ids whose cards are held out of reviews because the pack has no example yet ("no examples yet"). */
    @Throws(Exception::class)
    suspend fun pointsWithoutExamples(): List<String> =
        srs.blockedCards().filterValues { it == SrsRepository.BLOCK_NO_EXAMPLES }.keys
            .map { it.substringBeforeLast('#').removePrefix("g:") }.distinct().sorted()

    @Throws(Exception::class)
    suspend fun point(id: String): GrammarPointDetail? {
        val point = io { q.pointsByIds(listOf(id)).executeAsOneOrNull()?.toPoint() } ?: return null
        return GrammarPointDetail(point, examples(id), srs.stages()[point.itemId], srs.note(point.itemId).myStory)
    }

    @Throws(Exception::class)
    suspend fun examples(pointId: String): List<GrammarExample> = io {
        q.examplesFor(pointId).executeAsList().map {
            GrammarExample(it.ja, it.en, it.blank_start.toInt(), it.blank_end.toInt(), it.source)
        }
    }

    /** Every point's detection patterns, for finding grammar in reader sentences. Invalid regexes are skipped. */
    @Throws(Exception::class)
    suspend fun detectionPatterns(): List<Pair<String, Regex>> = io {
        q.allPatterns().executeAsList().mapNotNull { row -> runCatching { row.point_id to Regex(row.regex) }.getOrNull() }
    }

    /** Normalized title/alias → point, for matching imports (Bunpro) to our points. */
    @Throws(Exception::class)
    suspend fun titleIndex(): Map<String, GrammarPoint> = io {
        val points = q.allPoints().executeAsList().map { it.toPoint() }
        val byId = points.associateBy { it.id }
        val index = HashMap<String, GrammarPoint>()
        q.allAliases().executeAsList().forEach { a -> byId[a.point_id]?.let { index[a.alias] = it } }
        points.forEach { p -> index.getOrPut(GrammarTitles.normalize(p.title)) { p } }
        index
    }

    /**
     * Next points to learn, skipping ones already started: easiest JLPT level first in teaching order, or with
     * [textbook] ("genki", "tobira", "quartet") in that book's chapter order, then everything the book doesn't cover
     * in JLPT order (so the queue never runs dry).
     */
    @Throws(Exception::class)
    suspend fun lessonQueue(limit: Int, textbook: String? = null): List<GrammarPoint> {
        val started = srs.stages().keys
        val points = io { q.allPoints().executeAsList() }.map { it.toPoint() }
        val ordered = if (textbook == null) points else {
            val (inBook, rest) = points.partition { it.textbooks.containsKey(textbook) }
            inBook.sortedWith(compareBy<GrammarPoint> { chapterNumber(it.textbooks.getValue(textbook)) }.thenByDescending { it.jlpt }.thenBy { it.order }) + rest
        }
        return ordered.filter { it.itemId !in started }.take(limit)
    }

    /** Textbooks with a chapter mapping in this pack (pack_meta "textbooks", else the ids found on points). */
    @Throws(Exception::class)
    suspend fun textbooks(): List<Textbook> = io {
        val meta = q.metaValue(META_TEXTBOOKS).executeAsOneOrNull()
            ?.let { runCatching { json.decodeFromString<List<TextbookMeta>>(it) }.getOrNull() }
        val used = q.allPoints().executeAsList().flatMap { json.decodeFromString<Map<String, String>>(it.textbooks).keys }.toSet()
        meta?.filter { it.id in used }?.map { Textbook(it.id, it.title, it.unit) }
            ?: used.sorted().map { Textbook(it, it.replaceFirstChar { c -> c.uppercase() }, "Chapter") }
    }

    /**
     * A textbook-order path (BRIEF §5.5 paths, BRIEF_V2 G-05): the pack's points grouped by [textbook] chapter, in
     * chapter order, with the learner's stage per point. Empty when the pack maps nothing to that book.
     */
    @Throws(Exception::class)
    suspend fun path(textbook: String): List<TextbookChapter> {
        val points = io { q.allPoints().executeAsList() }.map { it.toPoint() }.filter { it.textbooks.containsKey(textbook) }
        val stages = srs.stagesFor(points.map { it.itemId })
        return points.groupBy { it.textbooks.getValue(textbook) }
            .map { (chapter, ps) ->
                TextbookChapter(
                    chapter, chapterNumber(chapter),
                    ps.sortedWith(compareByDescending<GrammarPoint> { it.jlpt }.thenBy { it.order }).map { GrammarPointStatus(it, stages[it.itemId]) },
                )
            }
            .sortedWith(compareBy<TextbookChapter> { it.number }.thenBy { it.chapter })
    }

    /** Adds points to reviews (one cloze card each) and introduces them. */
    @Throws(Exception::class)
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

    /**
     * A fresh exercise for a review: a random example, cloze or (when chunkable) sentence building. With [variety]
     * (BRIEF_V2 G-05) the kind also depends on the card's [stage]: young cards get fill-in-with-hint and meaning
     * recognition, Guru cards mostly cloze and building with some production, Master and above mostly production.
     */
    @Throws(Exception::class)
    suspend fun exercise(pointId: String, random: Random = Random.Default, stage: Stage? = null, variety: Boolean = false): GrammarExercise? {
        val point = io { q.pointsByIds(listOf(pointId)).executeAsOneOrNull()?.toPoint() } ?: return null
        val examples = examples(pointId).ifEmpty { return null }
        val example = examples.random(random)
        if (variety) {
            val kind = pickKind(stage, random, example)
            when (kind) {
                ExerciseKind.FILL_HINT, ExerciseKind.PRODUCTION, ExerciseKind.CLOZE -> return GrammarExercise(point, example, kind, emptyList())
                ExerciseKind.MEANING_CHOICE -> meaningChoices(point, random)?.let { return GrammarExercise(point, example, kind, emptyList(), it) }
                ExerciseKind.BUILD -> chunks(example).takeIf { it.size >= MIN_BUILD_CHUNKS }
                    ?.let { return GrammarExercise(point, example, kind, it.shuffledDistinctFrom(random)) }
            }
            // Recognition without enough other points, or building without a dictionary: a gap to fill instead.
            return GrammarExercise(point, example, if (stage == null || stage == Stage.APPRENTICE) ExerciseKind.FILL_HINT else ExerciseKind.CLOZE, emptyList())
        }
        val chunks = if (random.nextInt(3) == 0) chunks(example) else emptyList()
        return if (chunks.size >= MIN_BUILD_CHUNKS) {
            GrammarExercise(point, example, ExerciseKind.BUILD, chunks.shuffledDistinctFrom(random))
        } else {
            GrammarExercise(point, example, ExerciseKind.CLOZE, emptyList())
        }
    }

    private fun pickKind(stage: Stage?, random: Random, example: GrammarExample): ExerciseKind {
        val canProduce = example.english.isNotBlank()
        val roll = random.nextInt(100)
        return when (stage) {
            null, Stage.APPRENTICE -> when {
                roll < 50 -> ExerciseKind.FILL_HINT
                roll < 80 -> ExerciseKind.MEANING_CHOICE
                else -> ExerciseKind.CLOZE
            }
            Stage.GURU -> when {
                roll < 45 -> ExerciseKind.CLOZE
                roll < 70 -> ExerciseKind.BUILD
                roll < 85 -> ExerciseKind.MEANING_CHOICE
                canProduce -> ExerciseKind.PRODUCTION
                else -> ExerciseKind.CLOZE
            }
            else -> when {
                roll < 30 -> ExerciseKind.CLOZE
                roll < 50 -> ExerciseKind.BUILD
                canProduce -> ExerciseKind.PRODUCTION
                else -> ExerciseKind.CLOZE
            }
        }
    }

    /** The point's meaning and three other points' meanings (same level first), shuffled; null with too few points. */
    private suspend fun meaningChoices(point: GrammarPoint, random: Random): List<String>? {
        val others = io { q.allPoints().executeAsList() }.map { it.toPoint() }
            .filter { it.id != point.id && it.meaning.isNotBlank() && !it.meaning.equals(point.meaning, ignoreCase = true) }
        val sameLevel = others.filter { it.jlpt == point.jlpt }.shuffled(random)
        val rest = others.filter { it.jlpt != point.jlpt }.shuffled(random)
        val distractors = (sameLevel + rest).map { it.meaning }.distinctBy { it.lowercase() }.take(3)
        if (distractors.size < 3) return null
        return (distractors + point.meaning).shuffled(random)
    }

    /** Rule checks for a production answer (no model needed). */
    @Throws(Exception::class)
    suspend fun productionChecks(exercise: GrammarExercise, answer: String): ProductionChecks {
        val given = normalize(answer)
        val patterns = io { q.patternsFor(exercise.point.id).executeAsList() }.mapNotNull { runCatching { Regex(it) }.getOrNull() }
        val match = patterns.firstNotNullOfOrNull { it.find(given) }
        val formConsistent = match?.let {
            // The construction plus the kana that inflect it ("ておき" → "ておきました"), like a cloze answer.
            var end = it.range.last + 1
            while (end < given.length && Kana.isHiragana(given[end])) end++
            val form = given.substring(it.range.first, end)
            val expected = normalize(exercise.example.answer)
            Deinflector.deinflect(form).map { d -> d.term }.toSet().intersect(Deinflector.deinflect(expected).map { d -> d.term }.toSet()).isNotEmpty()
        }
        return ProductionChecks(
            // Romaji converts to kana first; leftover Latin letters mean it wasn't Japanese.
            japanese = given.none { it in 'a'..'z' || it in 'A'..'Z' } && (given.any { Kana.isKana(it) } || Kana.containsKanji(given)),
            constructionFound = if (patterns.isEmpty()) null else match != null,
            formConsistent = formConsistent,
            matchesModel = given.isNotEmpty() && given == normalize(exercise.example.japanese),
        )
    }

    /**
     * Grades a production answer (BRIEF §5.5): rule checks, then `grade_production` when [gateway] has a model. The
     * verdict is CORRECT at 5–6 of 6 with the construction present, CLOSE at 3+ (or 5–6 without it), else WRONG. An
     * answer identical to the model answer is CORRECT without asking the model. No model → self-grade.
     */
    @Throws(Exception::class)
    suspend fun gradeProduction(exercise: GrammarExercise, answer: String, gateway: AiGateway?, level: String = "N${exercise.point.jlpt}"): ProductionGrade {
        val checks = productionChecks(exercise, answer)
        fun grade(verdict: Verdict?, rubric: GradeProduction.Output? = null, engine: String? = null, unavailable: String? = null) =
            ProductionGrade(exercise.example.japanese, exercise.example.english, checks, verdict, rubric, engine, unavailable)
        if (!checks.japanese) return grade(Verdict.WRONG)
        if (checks.matchesModel) return grade(Verdict.CORRECT)
        if (gateway == null || !gateway.hasModel()) return grade(null, unavailable = "no AI model is set up")
        val input = GradeProduction.Input(
            exercise.example.english, exercise.example.japanese, exercise.point.title, exercise.point.structure,
            exercise.point.meaning, answer.trim(), level, checks.constructionFound,
        )
        return when (val result = gateway.run(GradeProduction(), input)) {
            is AiResult.Ok -> {
                val r = result.value
                val constructionOk = checks.constructionFound != false && checks.formConsistent != false
                val verdict = when {
                    r.total >= GradeProduction.CORRECT_TOTAL && constructionOk -> Verdict.CORRECT
                    r.total >= GradeProduction.CLOSE_TOTAL -> Verdict.CLOSE
                    else -> Verdict.WRONG
                }
                grade(verdict, r, result.engine)
            }
            is AiResult.Fallback -> grade(null, unavailable = result.reason)
            is AiResult.Unavailable -> grade(null, unavailable = result.reason)
        }
    }

    /**
     * Cloze answers: exact match (romaji converts, katakana folds) is correct; another valid form of the same
     * construction (matches the point's patterns and shares a dictionary form) is accepted as CLOSE.
     */
    @Throws(Exception::class)
    suspend fun check(exercise: GrammarExercise, answer: String): CheckResult {
        if (exercise.kind == ExerciseKind.MEANING_CHOICE) {
            val meaning = exercise.point.meaning
            val picked = answer.trim().toIntOrNull()?.let { exercise.choices.getOrNull(it) } ?: answer.trim()
            val ok = picked.equals(meaning, ignoreCase = true) || AnswerChecker.checkMeaning(picked, listOf(meaning), emptyList()).accepted
            return CheckResult(if (ok) Verdict.CORRECT else Verdict.WRONG, meaning)
        }
        if (exercise.kind == ExerciseKind.PRODUCTION) {
            val c = productionChecks(exercise, answer)
            return CheckResult(if (c.matchesModel) Verdict.CORRECT else Verdict.WRONG, exercise.example.japanese)
        }
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
    @Throws(Exception::class)
    suspend fun spawnGhost(itemId: String): GhostSpawn? {
        val cardId = SrsRepository.cardId(itemId, CardDirection.GHOST)
        val existing = srs.card(cardId)
        return if (existing == null) {
            val item = srs.item(itemId) ?: return null
            srs.addItems(listOf(
                NewItem(
                    id = item.id, kind = item.kind, primaryText = item.primaryText, reading = item.reading,
                    meanings = item.meanings, acceptedReadings = item.acceptedReadings, source = item.source,
                    directions = listOf(CardDirection.GHOST), jlpt = item.jlpt, packId = PACK_ID, refId = item.id.removePrefix("g:"),
                ),
            ))
            GhostSpawn(cardId, srs.introduce(listOf(cardId)), revived = false)
        } else if (existing.suspended) {
            srs.setSuspended(cardId, false)
            GhostSpawn(cardId, listOf(srs.review(cardId, Rating.AGAIN).reviewId), revived = true)
        } else {
            null
        }
    }

    /** Takes back a [spawnGhost] whose triggering answer was undone: its reviews are retracted and a revived ghost retires again. */
    @Throws(Exception::class)
    suspend fun undoGhost(spawn: GhostSpawn) {
        srs.retractReviews(spawn.cardId, spawn.reviewIds)
        if (spawn.revived) srs.setSuspended(spawn.cardId, true)
    }

    /**
     * Two correct ghost answers in a row graduate it off the learning steps; then it retires (suspended).
     * Returns true when this answer retired it.
     */
    @Throws(Exception::class)
    suspend fun ghostAnswered(cardId: String, correct: Boolean): Boolean {
        if (correct && srs.card(cardId)?.fsrs?.state == CardState.REVIEW) {
            srs.setSuspended(cardId, true)
            return true
        }
        return false
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

    @Serializable
    private data class TextbookMeta(val id: String, val title: String, val unit: String = "Chapter")

    companion object {
        const val PACK_ID = "grammar"
        /** pack_meta key: JSON [{"id":"genki","title":"GENKI (3rd ed.)","unit":"Lesson"}, …] from textbooks.json. */
        const val META_TEXTBOOKS = "textbooks"
        const val GENKI = "genki"
        const val TOBIRA = "tobira"
        const val QUARTET = "quartet"

        /** "L12" → 12, "12" → 12, "II-3" → 3; chapters without a number sort last. */
        fun chapterNumber(chapter: String): Int = Regex("""\d+""").findAll(chapter).lastOrNull()?.value?.toIntOrNull() ?: Int.MAX_VALUE
        const val GHOST_CORRECT_TO_RETIRE = 2
        private const val MIN_BUILD_CHUNKS = 4
        private const val MAX_BUILD_CHUNKS = 8

        fun itemId(pointId: String) = "g:$pointId"
    }
}
