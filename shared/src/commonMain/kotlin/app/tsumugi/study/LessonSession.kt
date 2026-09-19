package app.tsumugi.study

import app.tsumugi.domain.CardDirection
import app.tsumugi.srs.AnswerChecker
import app.tsumugi.srs.PathItem
import app.tsumugi.srs.PathService
import app.tsumugi.srs.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

data class LessonQuestion(val item: PathItem, val direction: CardDirection) {
    val mode: AnswerMode get() = if (direction == CardDirection.READING) AnswerMode.READING else AnswerMode.MEANING
    val expected: List<String> get() = if (direction == CardDirection.READING) item.readings else item.meanings
}

sealed interface LessonState {
    /** Teaching screen for item [index] of [items]. */
    data class Presenting(val items: List<PathItem>, val index: Int) : LessonState {
        val item: PathItem get() = items[index]
        val isLast: Boolean get() = index == items.lastIndex
    }

    data class Quizzing(val question: LessonQuestion, val remaining: Int, val hint: String? = null) : LessonState

    data class QuizFeedback(val question: LessonQuestion, val given: String, val verdict: Verdict, val remaining: Int) : LessonState {
        val correct: Boolean get() = verdict == Verdict.CORRECT || verdict == Verdict.CLOSE
    }

    /** All items answered correctly; they are now in the review queue. */
    data class Complete(val items: List<PathItem>) : LessonState
}

/**
 * A lesson batch (BRIEF §5.4): teach each item (keyword, readings, components, the user's own mnemonic), then a
 * quiz that repeats each question until it's answered correctly. Quiz answers are practice only; finishing the
 * batch introduces the items' cards into the review queue.
 */
class LessonSession(
    private val items: List<PathItem>,
    private val random: Random,
    /** Called once with every item when the quiz is done: joins them to the collection and the review queue. */
    private val complete: suspend (List<PathItem>) -> Unit,
) {
    /** A kanji-path lesson batch: finishing it completes the items on [path]. */
    constructor(path: PathService, items: List<PathItem>, random: Random = Random.Default) : this(items, random, { path.completeLessons(it) })

    /** The items of this batch in teaching order (another source can merge them into a larger batch, D-213). */
    val batch: List<PathItem> get() = items

    /** Runs this batch's completion for [done] when a merged session finishes (TrackService.mixInto). */
    internal suspend fun completeItems(done: List<PathItem>) = complete(done)

    private val quiz = ArrayDeque<LessonQuestion>()
    private val _state = MutableStateFlow<LessonState>(LessonState.Presenting(items, 0))
    val state: StateFlow<LessonState> = _state.asStateFlow()

    /** Teaching screens: forward (the last item starts the quiz). */
    fun nextItem() {
        val s = _state.value as? LessonState.Presenting ?: return
        if (s.isLast) startQuiz() else _state.value = s.copy(index = s.index + 1)
    }

    fun previousItem() {
        val s = _state.value as? LessonState.Presenting ?: return
        if (s.index > 0) _state.value = s.copy(index = s.index - 1)
    }

    fun startQuiz() {
        quiz.clear()
        quiz += items.flatMap { item -> PathService.directionsFor(item.kind).map { LessonQuestion(item, it) } }.shuffled(random)
        _state.value = LessonState.Quizzing(quiz.first(), quiz.size - 1)
    }

    fun submit(answer: String) {
        val s = _state.value as? LessonState.Quizzing ?: return
        val q = s.question
        val check = when (q.mode) {
            AnswerMode.READING -> AnswerChecker.checkReading(answer, q.item.readings, q.item.otherReadings)
            else -> AnswerChecker.checkMeaning(answer, q.item.meanings)
        }
        if (check.verdict == Verdict.WRONG_KIND) {
            _state.value = s.copy(hint = "That's a valid reading, but not the one we're looking for.")
            return
        }
        quiz.removeFirst()
        if (!check.accepted) quiz.addLast(q)
        _state.value = LessonState.QuizFeedback(q, answer, check.verdict, quiz.size)
    }

    /** After quiz feedback: next question, or finish the batch. */
    @Throws(Exception::class)
    suspend fun next() {
        if (_state.value !is LessonState.QuizFeedback) return
        val q = quiz.firstOrNull()
        if (q == null) {
            complete(items)
            _state.value = LessonState.Complete(items)
        } else {
            _state.value = LessonState.Quizzing(q, quiz.size - 1)
        }
    }

    /** Go back to the teaching screens for the current question's item. */
    fun reviewItem() {
        val s = _state.value
        val item = (s as? LessonState.Quizzing)?.question?.item ?: (s as? LessonState.QuizFeedback)?.question?.item ?: return
        _state.value = LessonState.Presenting(items, items.indexOf(item).coerceAtLeast(0))
    }
}
