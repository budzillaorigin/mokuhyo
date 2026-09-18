package app.tsumugi.study

import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.grammar.ExerciseKind
import app.tsumugi.grammar.GhostSpawn
import app.tsumugi.grammar.GrammarExercise
import app.tsumugi.grammar.GrammarService
import app.tsumugi.srs.AnswerChecker
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.StudyCard
import app.tsumugi.srs.StudyItem
import app.tsumugi.srs.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** How a card is answered. */
enum class AnswerMode {
    /** Type an English meaning (typo-tolerant). */
    MEANING,
    /** Type a reading in kana (romaji converts as you type). */
    READING,
    /** Flashcard: reveal, then grade yourself Again/Hard/Good/Easy. */
    SELF_GRADED,
    /** Grammar: type the construction missing from a sentence. */
    CLOZE,
    /** Grammar: put shuffled chunks of a sentence in order. */
    BUILD,
    /** Kanji: draw it from memory, compare with the reference, then grade yourself (BRIEF §5.7 raw mode). */
    WRITING,
}

data class ReviewPrompt(
    val card: StudyCard,
    val item: StudyItem,
    val mode: AnswerMode,
    /** True for a missed card re-asked at the end of the session; practice answers are not recorded. */
    val practice: Boolean = false,
    /** Grammar cards: the sentence exercise chosen for this review. */
    val exercise: GrammarExercise? = null,
) {
    /** "Kanji · Meaning", "Vocabulary · Reading", … */
    val label: String
        get() = "${item.kind.label} · " + when (card.direction) {
            CardDirection.MEANING -> "Meaning"
            CardDirection.READING -> "Reading"
            CardDirection.RECALL -> "Recall"
            CardDirection.CLOZE, CardDirection.GHOST -> if (mode == AnswerMode.BUILD) "Build the sentence" else "Fill the gap"
            CardDirection.WRITING -> "Write it"
            else -> "Recognition"
        }

    /** What to show as the question. RECALL cards ask from the meaning side. */
    val question: String
        get() = when {
            exercise != null && mode == AnswerMode.BUILD -> exercise.example.english
            exercise != null -> exercise.prompt
            card.direction == CardDirection.RECALL || card.direction == CardDirection.WRITING ->
                item.meanings.joinToString(", ") + (item.reading?.let { " ($it)" } ?: "")
            else -> item.primaryText
        }

    /** Cloze hint: the translation and the grammar point being practised. */
    val hint: String?
        get() = exercise?.let { if (mode == AnswerMode.CLOZE) "${it.example.english}  ·  ${it.point.meaning}" else it.point.title }

    /** The accepted answers, shown after answering. */
    val expected: List<String>
        get() = when (mode) {
            AnswerMode.MEANING -> item.meanings
            AnswerMode.READING -> item.acceptedReadings
            AnswerMode.SELF_GRADED -> if (card.direction == CardDirection.RECALL) listOf(item.primaryText) else item.meanings
            AnswerMode.CLOZE -> listOfNotNull(exercise?.example?.answer)
            AnswerMode.BUILD -> listOfNotNull(exercise?.example?.japanese)
            AnswerMode.WRITING -> listOf(item.primaryText)
        }
}

data class KindTally(val correct: Int, val total: Int)

data class ReviewSummary(
    val reviewed: Int,
    val correct: Int,
    val byKind: Map<ItemKind, KindTally>,
    val missed: List<StudyItem>,
    /** Items whose cards have lapsed [SrsRepository.LEECH_LAPSES]+ times: suggest rewriting the mnemonic. */
    val leeches: List<StudyItem>,
) {
    val accuracy: Double get() = if (reviewed == 0) 0.0 else correct.toDouble() / reviewed

    /** [byKind] as a list in kind order. */
    val kinds: List<KindResult> get() = byKind.map { (k, t) -> KindResult(k, t.correct, t.total) }.sortedBy { it.kind.ordinal }
}

data class KindResult(val kind: ItemKind, val correct: Int, val total: Int)

sealed interface ReviewState {
    data class Asking(
        val prompt: ReviewPrompt,
        val remaining: Int,
        val done: Int,
        /** Set when the answer was a valid reading of the wrong kind: try again, nothing recorded. */
        val hint: String? = null,
        val wrappingUp: Boolean = false,
    ) : ReviewState

    /** Self-graded card with its answer revealed; waiting for a rating. */
    data class Revealed(val prompt: ReviewPrompt, val remaining: Int, val done: Int) : ReviewState

    data class Answered(
        val prompt: ReviewPrompt,
        val given: String,
        val verdict: Verdict,
        /** The accepted answer the given one matched (for CLOSE: the intended spelling). */
        val matched: String?,
        val remaining: Int,
        val done: Int,
        val canUndo: Boolean,
    ) : ReviewState {
        val correct: Boolean get() = verdict == Verdict.CORRECT || verdict == Verdict.CLOSE
    }

    data class Finished(val summary: ReviewSummary) : ReviewState
}

/**
 * One review session over due cards (BRIEF §5.4 review UX): typed answers with instant grading, wrong answers
 * re-asked at the end as practice, undo of the last answer, wrap-up mode, and a summary with leech detection.
 * Both apps render [state] and call the actions; no review logic lives in the UI.
 *
 * Every action is serialized by one [Mutex] (BRIEF_V2 F-09): a second `submit()` while the first is still writing
 * waits, then finds the prompt already answered and does nothing, so a double tap records one review and advances
 * one card. The non-suspending [reveal] and [wrapUp] never wait: reveal is ignored and wrap-up is deferred to the end
 * of the action in flight.
 */
class ReviewSession(
    private val srs: SrsRepository,
    cards: List<StudyCard>,
    private val items: Map<String, StudyItem>,
    private val clock: Clock = Clock.System,
    random: Random = Random.Default,
    /** Exercises for grammar cards, by card id (cards without one are skipped). */
    exercises: Map<String, GrammarExercise> = emptyMap(),
    private val grammar: GrammarService? = null,
) {
    private val queue = ArrayDeque(
        cards.filter { it.itemId in items }
            .filter { !it.direction.isGrammar || it.id in exercises }
            .shuffled(random)
            .map { card ->
                val exercise = exercises[card.id]
                val mode = when (exercise?.kind) {
                    ExerciseKind.BUILD -> AnswerMode.BUILD
                    ExerciseKind.CLOZE -> AnswerMode.CLOZE
                    null -> modeFor(card.direction)
                }
                ReviewPrompt(card, items.getValue(card.itemId), mode, exercise = exercise)
            },
    )
    private val results = ArrayList<Pair<ReviewPrompt, Boolean>>()
    private var lastOutcome: SrsRepository.ReviewOutcome? = null
    /** Side effects of the last recorded answer on grammar ghosts, undone together with it (BRIEF_V2 F-20). */
    private var lastGhostSpawn: GhostSpawn? = null
    private var lastGhostRetired: String? = null
    private val mutex = Mutex()
    private var pendingWrapUp: Int? = null
    private var shownAt: Instant = clock.now()
    private var wrappingUp = false

    private val _state = MutableStateFlow(nextState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    val isEmpty: Boolean get() = results.isEmpty() && queue.isEmpty()

    /** Typed answer for MEANING/READING prompts. */
    @Throws(Exception::class)
    suspend fun submit(answer: String) = locked { submitLocked(answer) }

    private suspend fun submitLocked(answer: String) {
        val asking = _state.value as? ReviewState.Asking ?: return
        val prompt = asking.prompt
        val item = prompt.item
        val check = when (prompt.mode) {
            AnswerMode.MEANING -> AnswerChecker.checkMeaning(answer, item.meanings, item.synonyms)
            AnswerMode.READING -> AnswerChecker.checkReading(answer, item.acceptedReadings)
            AnswerMode.CLOZE, AnswerMode.BUILD -> grammar?.check(prompt.exercise ?: return, answer) ?: return
            AnswerMode.SELF_GRADED, AnswerMode.WRITING -> return
        }
        if (check.verdict == Verdict.WRONG_KIND) {
            _state.value = asking.copy(hint = "That's a valid reading, but not the one we're looking for.")
            return
        }
        val rating = if (check.accepted) Rating.GOOD else Rating.AGAIN
        record(prompt, rating, answer, check.accepted)
        queue.removeFirst()
        if (!check.accepted && !prompt.practice) queue.addLast(prompt.copy(practice = true))
        _state.value = ReviewState.Answered(prompt, answer, check.verdict, check.matched, queue.size, done(), canUndo = !prompt.practice)
    }

    /** Self-graded and writing prompts: show the answer (for writing, after drawing). */
    fun reveal() {
        if (!mutex.tryLock()) return
        try {
            val asking = _state.value as? ReviewState.Asking ?: return
            if (asking.prompt.mode != AnswerMode.SELF_GRADED && asking.prompt.mode != AnswerMode.WRITING) return
            _state.value = ReviewState.Revealed(asking.prompt, asking.remaining, asking.done)
        } finally {
            mutex.unlock()
        }
    }

    /** Self-graded prompt: record the rating and move on. */
    @Throws(Exception::class)
    suspend fun grade(rating: Rating) = locked { gradeLocked(rating) }

    private suspend fun gradeLocked(rating: Rating) {
        val revealed = _state.value as? ReviewState.Revealed ?: return
        record(revealed.prompt, rating, null, rating != Rating.AGAIN)
        queue.removeFirst()
        if (rating == Rating.AGAIN && !revealed.prompt.practice) queue.addLast(revealed.prompt.copy(practice = true))
        advance()
    }

    /** After feedback: go to the next card. */
    @Throws(Exception::class)
    suspend fun next() = locked {
        if (_state.value is ReviewState.Answered) advance()
    }

    /** Take back the last answer (e.g. a typo): the review is removed and the same card is asked again. */
    @Throws(Exception::class)
    suspend fun undo() = locked { undoLocked() }

    private suspend fun undoLocked() {
        val answered = _state.value as? ReviewState.Answered ?: return
        val outcome = lastOutcome ?: return
        if (!answered.canUndo) return
        srs.undo(outcome)
        lastGhostSpawn?.let { grammar?.undoGhost(it) }
        lastGhostRetired?.let { srs.setSuspended(it, false) }
        lastOutcome = null
        lastGhostSpawn = null
        lastGhostRetired = null
        results.removeAt(results.lastIndex)
        if (!answered.correct) queue.removeLast()
        queue.addFirst(answered.prompt)
        shownAt = clock.now()
        _state.value = ReviewState.Asking(answered.prompt, queue.size - 1, done(), wrappingUp = wrappingUp)
    }

    /** Finish soon: keep only the next [keep] cards (plus practice for anything missed). */
    fun wrapUp(keep: Int = WRAP_UP_SIZE) {
        if (!mutex.tryLock()) {
            pendingWrapUp = keep
            return
        }
        try {
            wrapUpLocked(keep)
        } finally {
            mutex.unlock()
        }
    }

    private fun wrapUpLocked(keep: Int) {
        wrappingUp = true
        while (queue.count { !it.practice } > keep) {
            val lastNew = queue.indexOfLast { !it.practice }
            queue.removeAt(lastNew)
        }
        val s = _state.value
        if (s is ReviewState.Asking) _state.value = s.copy(remaining = queue.size - 1, wrappingUp = true)
    }

    /** End now and show the summary. */
    @Throws(Exception::class)
    suspend fun finish() = locked {
        queue.clear()
        _state.value = ReviewState.Finished(summary())
    }

    /** Runs one action under the session lock, then applies a wrap-up requested while it ran. */
    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } finally {
            pendingWrapUp?.let {
                pendingWrapUp = null
                wrapUpLocked(it)
            }
        }
    }

    private suspend fun record(prompt: ReviewPrompt, rating: Rating, answer: String?, correct: Boolean) {
        lastGhostSpawn = null
        lastGhostRetired = null
        if (prompt.practice) {
            lastOutcome = null
            return
        }
        val now = clock.now()
        lastOutcome = srs.review(prompt.card.id, rating, now - shownAt, answer, correct, now)
        results += prompt to correct
        if (grammar != null && prompt.card.direction.isGrammar) {
            if (prompt.card.direction == CardDirection.GHOST) {
                if (grammar.ghostAnswered(prompt.card.id, correct)) lastGhostRetired = prompt.card.id
            } else if (!correct) {
                lastGhostSpawn = grammar.spawnGhost(prompt.item.id)
            }
        }
    }

    private suspend fun advance() {
        shownAt = clock.now()
        _state.value = if (queue.isEmpty()) ReviewState.Finished(summary()) else nextState()
    }

    private fun nextState(): ReviewState {
        val next = queue.firstOrNull() ?: return ReviewState.Finished(summaryNow())
        return ReviewState.Asking(next, queue.size - 1, done(), wrappingUp = wrappingUp)
    }

    private fun done() = results.size

    private suspend fun summary(): ReviewSummary {
        val base = summaryNow()
        val touched = results.map { it.first.card.id }.distinct()
        val leeches = touched.mapNotNull { srs.card(it) }
            .filter { it.fsrs.lapses >= SrsRepository.LEECH_LAPSES }
            .mapNotNull { items[it.itemId] }
            .distinctBy { it.id }
        return base.copy(leeches = leeches)
    }

    private fun summaryNow(): ReviewSummary {
        val byKind = results.groupBy { it.first.item.kind }
            .mapValues { (_, r) -> KindTally(r.count { it.second }, r.size) }
        val missed = results.filterNot { it.second }.map { it.first.item }.distinctBy { it.id }
        return ReviewSummary(results.size, results.count { it.second }, byKind, missed, leeches = emptyList())
    }

    companion object {
        const val WRAP_UP_SIZE = 10

        fun modeFor(direction: CardDirection): AnswerMode = when (direction) {
            CardDirection.MEANING -> AnswerMode.MEANING
            CardDirection.READING -> AnswerMode.READING
            CardDirection.CLOZE, CardDirection.GHOST -> AnswerMode.CLOZE
            CardDirection.WRITING -> AnswerMode.WRITING
            else -> AnswerMode.SELF_GRADED
        }

        private val CardDirection.isGrammar: Boolean get() = this == CardDirection.CLOZE || this == CardDirection.GHOST

        /** A session over everything due now (up to [limit] cards). Grammar cards need [grammar] for exercises. */
        @Throws(Exception::class)
        suspend fun start(
            srs: SrsRepository,
            grammar: GrammarService? = null,
            limit: Int = 500,
            clock: Clock = Clock.System,
            random: Random = Random.Default,
        ): ReviewSession {
            val cards = srs.dueCards(limit, clock.now())
            val items = srs.items(cards.map { it.itemId }.toSet())
            val exercises = if (grammar == null) emptyMap() else cards
                .filter { it.direction.isGrammar }
                .mapNotNull { c -> grammar.exercise(c.itemId.removePrefix("g:"), random)?.let { c.id to it } }
                .toMap()
            return ReviewSession(srs, cards, items, clock, random, exercises, grammar)
        }
    }
}
