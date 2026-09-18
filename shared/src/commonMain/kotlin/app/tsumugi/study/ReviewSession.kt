package app.tsumugi.study

import app.tsumugi.ai.AiGateway
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.grammar.ExerciseKind
import app.tsumugi.grammar.GhostSpawn
import app.tsumugi.grammar.GrammarExercise
import app.tsumugi.grammar.GrammarService
import app.tsumugi.grammar.ProductionGrade
import app.tsumugi.l10n.Labels
import app.tsumugi.srs.CheckResult
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
    /** Grammar: type the missing construction with the point's title and meaning as a hint (young cards). */
    FILL_HINT,
    /** Grammar: pick the meaning of the marked construction from [ReviewPrompt.choices] (submit the index or the text). */
    MEANING_CHOICE,
    /**
     * Grammar: translate [ReviewPrompt.question] into Japanese using the point. Graded by `grade_production` when a
     * model is set up (Answered.production); otherwise the model answer is revealed (Revealed.production) and the
     * learner grades themselves.
     */
    PRODUCTION,
    /** Minimal pair: play [ReviewPrompt.minimalPair]'s `played` word, the learner picks A or B (submit "a"/"b" or the word). */
    MINIMAL_PAIR,
}

data class ReviewPrompt(
    val card: StudyCard,
    val item: StudyItem,
    val mode: AnswerMode,
    /** True for a missed card re-asked at the end of the session; practice answers are not recorded. */
    val practice: Boolean = false,
    /** Grammar cards: the sentence exercise chosen for this review. */
    val exercise: GrammarExercise? = null,
    /** Minimal-pair cards: the pair and which word to play. */
    val minimalPair: MinimalPairPrompt? = null,
) {
    /** "Kanji · Meaning", "Vocabulary · Reading", … in the shared string table's language (G-14). */
    val label: String
        get() = "${Labels.kind(item.kind)} · " + when {
            card.direction == CardDirection.RECALL -> Labels.direction(CardDirection.RECALL)
            card.direction == CardDirection.CLOZE || card.direction == CardDirection.GHOST || mode != AnswerMode.SELF_GRADED -> Labels.mode(mode)
            else -> Labels.mode(AnswerMode.SELF_GRADED)
        }

    /** MEANING_CHOICE: the four meanings to choose from. */
    val choices: List<String> get() = exercise?.choices.orEmpty()

    /** What to show as the question. RECALL cards ask from the meaning side. */
    val question: String
        get() = when {
            minimalPair != null -> minimalPair.question
            exercise != null && (mode == AnswerMode.BUILD || mode == AnswerMode.PRODUCTION) -> exercise.example.english
            exercise != null && mode == AnswerMode.MEANING_CHOICE -> exercise.marked
            exercise != null -> exercise.prompt
            card.direction == CardDirection.RECALL || card.direction == CardDirection.WRITING ->
                item.meanings.joinToString(", ") + (item.reading?.let { " ($it)" } ?: "")
            else -> item.primaryText
        }

    /** Cloze hint: the translation and the grammar point being practised. */
    val hint: String?
        get() = exercise?.let {
            when (mode) {
                AnswerMode.CLOZE -> "${it.example.english}  ·  ${it.point.meaning}"
                AnswerMode.FILL_HINT -> "${it.example.english}  ·  ${it.pointHint}"
                AnswerMode.MEANING_CHOICE -> null
                AnswerMode.PRODUCTION -> "${it.point.title} (${it.point.structure})"
                else -> it.point.title
            }
        }

    /** The accepted answers, shown after answering. */
    val expected: List<String>
        get() = when (mode) {
            AnswerMode.MEANING -> item.meanings
            AnswerMode.READING -> item.acceptedReadings
            AnswerMode.SELF_GRADED -> if (card.direction == CardDirection.RECALL) listOf(item.primaryText) else item.meanings
            AnswerMode.CLOZE, AnswerMode.FILL_HINT -> listOfNotNull(exercise?.example?.answer)
            AnswerMode.BUILD, AnswerMode.PRODUCTION -> listOfNotNull(exercise?.example?.japanese)
            AnswerMode.MEANING_CHOICE -> listOfNotNull(exercise?.point?.meaning)
            AnswerMode.WRITING -> listOf(item.primaryText)
            AnswerMode.MINIMAL_PAIR -> listOfNotNull(minimalPair?.answer)
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

    /**
     * Self-graded card with its answer revealed; waiting for a rating. A PRODUCTION answer without a model lands here
     * too, with the learner's [given] answer and [production] (the model answer and the rule checks).
     */
    data class Revealed(
        val prompt: ReviewPrompt,
        val remaining: Int,
        val done: Int,
        val given: String? = null,
        val production: ProductionGrade? = null,
    ) : ReviewState

    data class Answered(
        val prompt: ReviewPrompt,
        val given: String,
        val verdict: Verdict,
        /** The accepted answer the given one matched (for CLOSE: the intended spelling). */
        val matched: String?,
        val remaining: Int,
        val done: Int,
        val canUndo: Boolean,
        /** PRODUCTION: the model-graded result (rubric, feedback, model answer). */
        val production: ProductionGrade? = null,
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
    /** Grades PRODUCTION answers; null (or no model in it) means the learner self-grades against the model answer. */
    private val gateway: AiGateway? = null,
) {
    private val queue = ArrayDeque(
        cards.filter { it.itemId in items }
            .filter { !it.direction.isGrammar || it.id in exercises }
            .shuffled(random)
            .map { card ->
                val item = items.getValue(card.itemId)
                val exercise = exercises[card.id]
                val pair = if (item.kind == ItemKind.MINIMAL_PAIR) MinimalPairPrompt.of(item, random) else null
                val mode = when (exercise?.kind) {
                    ExerciseKind.BUILD -> AnswerMode.BUILD
                    ExerciseKind.CLOZE -> AnswerMode.CLOZE
                    ExerciseKind.FILL_HINT -> AnswerMode.FILL_HINT
                    ExerciseKind.MEANING_CHOICE -> AnswerMode.MEANING_CHOICE
                    ExerciseKind.PRODUCTION -> AnswerMode.PRODUCTION
                    null -> if (pair != null) AnswerMode.MINIMAL_PAIR else modeFor(card.direction)
                }
                ReviewPrompt(card, item, mode, exercise = exercise, minimalPair = pair)
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
        var production: ProductionGrade? = null
        val check = when (prompt.mode) {
            AnswerMode.MEANING -> AnswerChecker.checkMeaning(answer, item.meanings, item.synonyms)
            AnswerMode.READING -> AnswerChecker.checkReading(answer, item.acceptedReadings)
            AnswerMode.CLOZE, AnswerMode.BUILD, AnswerMode.FILL_HINT, AnswerMode.MEANING_CHOICE ->
                grammar?.check(prompt.exercise ?: return, answer) ?: return
            AnswerMode.PRODUCTION -> {
                if (answer.isBlank()) return
                val grade = grammar?.gradeProduction(prompt.exercise ?: return, answer, gateway) ?: return
                if (grade.selfGrade) {
                    _state.value = ReviewState.Revealed(prompt, asking.remaining, asking.done, answer.trim(), grade)
                    return
                }
                production = grade
                CheckResult(grade.verdict ?: Verdict.WRONG, grade.modelAnswer)
            }
            AnswerMode.MINIMAL_PAIR -> prompt.minimalPair?.check(answer) ?: return
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
        _state.value = ReviewState.Answered(prompt, answer, check.verdict, check.matched, queue.size, done(), canUndo = !prompt.practice, production = production)
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
        record(revealed.prompt, rating, revealed.given, rating != Rating.AGAIN)
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

        /**
         * A session over everything due now (up to [limit] cards; Today passes its budget cap, G-01). Grammar cards
         * need [grammar] for exercises. With [grammarVariety] (G-05) grammar cards also get fill-in-with-hint, meaning
         * recognition and production exercises by stage; production is graded through [gateway] when it has a model.
         */
        @Throws(Exception::class)
        suspend fun start(
            srs: SrsRepository,
            grammar: GrammarService? = null,
            limit: Int = 500,
            clock: Clock = Clock.System,
            random: Random = Random.Default,
            grammarVariety: Boolean = false,
            gateway: AiGateway? = null,
        ): ReviewSession {
            val cards = srs.dueCards(limit, clock.now())
            val items = srs.items(cards.map { it.itemId }.toSet())
            val exercises = if (grammar == null) emptyMap() else cards
                .filter { it.direction.isGrammar }
                .mapNotNull { c -> grammar.exercise(c.itemId.removePrefix("g:"), random, c.stage, grammarVariety)?.let { c.id to it } }
                .toMap()
            return ReviewSession(srs, cards, items, clock, random, exercises, grammar, gateway)
        }
    }
}
