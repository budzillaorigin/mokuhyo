package app.tsumugi.writing

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.CorrectSentence
import app.tsumugi.ai.prompts.NaturalRewrite
import app.tsumugi.coverage.DifficultyScore
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.db.Writing_draft
import app.tsumugi.reader.GradedStory
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.reader.SummaryGradeResult
import app.tsumugi.thesaurus.ExpressionCluster
import app.tsumugi.thesaurus.ThesaurusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A saved draft (synced, D-275). [taskRef] is set when an output task (a graded reader's, §6.4) created it. */
data class StudioDraft(
    val id: String,
    val title: String,
    val body: String,
    val taskRef: String?,
    val taskPrompt: String?,
    val targetRegister: SpeechRegister?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** The graded-reader story an output task belongs to ("reader:<id>"), or null. */
    val readerStoryId: String? get() = taskRef?.takeIf { it.startsWith(READER_TASK) }?.removePrefix(READER_TASK)

    companion object {
        const val READER_TASK = "reader:"
    }
}

/** The model's correction of one sentence (rule 10: labeled), or why there is none. */
data class SentenceCorrection(
    val start: Int,
    val end: Int,
    val sentence: String,
    val result: CorrectSentence.Output?,
    val engine: String?,
    val unavailable: String?,
) {
    val source: String get() = "llm"
}

/** A plain word in the draft the thesaurus can improve on, with the clusters to open. */
data class PhraseFlag(val start: Int, val end: Int, val surface: String, val lemma: String, val clusters: List<ExpressionCluster>)

/** A register rewrite of one sentence by the model (existing natural_rewrite prompt, labeled). */
data class RegisterRewrite(val sentence: String, val register: SpeechRegister, val rewrite: String?, val notes: String, val engine: String?, val unavailable: String?)

/**
 * The writing studio (BRIEF_V2 §6.13; D-275): compose Japanese text, and on demand get corrections (the existing
 * correct_sentence prompt, sentence by sentence), the rule-based register check, thesaurus suggestions for the plain
 * words the draft leans on, and readability (the §6.4 difficulty score). Drafts sync. Graded-reader output tasks
 * (§6.4) open here as drafts that carry the task.
 */
class WritingStudio(
    private val userDb: TsumugiDatabase,
    private val thesaurus: suspend () -> ThesaurusRepository?,
    private val analyzer: suspend () -> ReaderAnalyzer?,
    private val gateway: suspend () -> AiGateway,
    private val difficulty: suspend (String) -> DifficultyScore?,
    private val gradeReaderSummary: suspend (storyId: String, text: String) -> SummaryGradeResult? = { _, _ -> null },
    private val clock: Clock = Clock.System,
) {
    private val q get() = userDb.workbenchQueries

    // --- Drafts -----------------------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun drafts(): List<StudioDraft> = io { q.liveDrafts().executeAsList().map { it.toDraft() } }

    @Throws(Exception::class)
    suspend fun draft(id: String): StudioDraft? = io { q.draftById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toDraft() }

    /** Starts a free-writing draft. */
    @Throws(Exception::class)
    suspend fun createDraft(title: String = "", body: String = "", targetRegister: SpeechRegister? = null): StudioDraft =
        put(Uuid.random().toString(), title, body, null, null, targetRegister, null)

    /** Saves the draft's text (last writer wins across devices). */
    @Throws(Exception::class)
    suspend fun update(id: String, title: String, body: String, targetRegister: SpeechRegister?): StudioDraft? {
        val old = draft(id) ?: return null
        return put(id, title, body, old.taskRef, old.taskPrompt, targetRegister, old.createdAt)
    }

    @Throws(Exception::class)
    suspend fun delete(id: String) = io { q.deleteDraft(clock.now().toEpochMilliseconds(), id) }

    /**
     * The draft for a graded reader's post-reading output task (§6.4): reopens the learner's draft for that story, or
     * starts one carrying the task text (Japanese from N3, English below, as the reader shows it).
     */
    @Throws(Exception::class)
    suspend fun draftForReaderTask(story: GradedStory): StudioDraft {
        val ref = StudioDraft.READER_TASK + story.id
        io { q.draftForTask(ref).executeAsOneOrNull() }?.let { return it.toDraft() }
        val task = story.tasks.output
        val prompt = task?.prompt?.takeIf { it.isNotBlank() } ?: task?.promptEn.orEmpty()
        val register = if (story.level in setOf("N6", "N5", "N4")) SpeechRegister.POLITE else null
        return put(Uuid.random().toString(), story.title, "", ref, prompt, register, null)
    }

    /** Grades a reader-task draft with the story's summary rubric (grade_reading_summary; labeled), or null if not one. */
    @Throws(Exception::class)
    suspend fun gradeReaderTask(draft: StudioDraft): SummaryGradeResult? {
        val storyId = draft.readerStoryId ?: return null
        return gradeReaderSummary(storyId, draft.body)
    }

    // --- On-demand checks -------------------------------------------------------------------------------------

    /** Corrections sentence by sentence (up to [maxSentences]), with the learner's model. Nothing is faked offline. */
    @Throws(Exception::class)
    suspend fun corrections(text: String, level: String = "N3", maxSentences: Int = 20): List<SentenceCorrection> {
        val spans = RegisterChecker.split(text).filter { (s, e) -> text.substring(s, e).any { !it.isWhitespace() } }.take(maxSentences)
        if (spans.isEmpty()) return emptyList()
        val ai = gateway()
        return spans.map { (s, e) ->
            val sentence = text.substring(s, e)
            when (val r = ai.run(CorrectSentence(), CorrectSentence.Input(sentence, level))) {
                is AiResult.Ok -> SentenceCorrection(s, e, sentence, r.value, r.engine, null)
                is AiResult.Fallback -> SentenceCorrection(s, e, sentence, null, null, r.reason)
                is AiResult.Unavailable -> SentenceCorrection(s, e, sentence, null, null, r.reason)
            }
        }
    }

    /** The register check (rules, offline). */
    fun registerCheck(text: String, target: SpeechRegister? = null): RegisterReport = RegisterChecker.check(text, target)

    /** One sentence rewritten in [register] by the model (for an outlier of the register check). */
    @Throws(Exception::class)
    suspend fun rewrite(sentence: String, register: SpeechRegister): RegisterRewrite {
        val target = when (register) {
            SpeechRegister.CASUAL -> NaturalRewrite.Register.CASUAL
            SpeechRegister.POLITE -> NaturalRewrite.Register.POLITE
            SpeechRegister.FORMAL -> NaturalRewrite.Register.FORMAL
        }
        return when (val r = gateway().run(NaturalRewrite(), NaturalRewrite.Input(sentence, target))) {
            is AiResult.Ok -> RegisterRewrite(sentence, register, r.value.rewrite, r.value.notes, r.engine, null)
            is AiResult.Fallback -> RegisterRewrite(sentence, register, null, "", null, r.reason)
            is AiResult.Unavailable -> RegisterRewrite(sentence, register, null, "", null, r.reason)
        }
    }

    /**
     * Plain words the thesaurus has richer expressions for (their clusters' `plain` lists), in text order. Empty
     * without the dictionary pack or a pack without thesaurus tables.
     */
    @Throws(Exception::class)
    suspend fun suggestions(text: String): List<PhraseFlag> {
        val repo = thesaurus() ?: return emptyList()
        val reader = analyzer() ?: return emptyList()
        val tokens = reader.paragraphs(text).flatMap { range -> reader.paragraph(text, range).sentences.flatMap { it.tokens } }
        val lemmas = tokens.map { it.dictionaryForm ?: it.surface }
        val index = repo.clustersForLemmas(lemmas.toSet())
        if (index.isEmpty()) return emptyList()
        val clusters = repo.clusters().associateBy { it.id }
        return tokens.mapNotNull { t ->
            val lemma = t.dictionaryForm ?: t.surface
            index[lemma]?.mapNotNull { clusters[it] }?.takeIf { it.isNotEmpty() }?.let { PhraseFlag(t.start, t.end, t.surface, lemma, it) }
        }
    }

    /** Readability: the §6.4 difficulty score of the draft (null without the dictionary pack or for empty text). */
    @Throws(Exception::class)
    suspend fun readability(text: String): DifficultyScore? = if (text.isBlank()) null else difficulty(text)

    private suspend fun put(
        id: String,
        title: String,
        body: String,
        taskRef: String?,
        taskPrompt: String?,
        register: SpeechRegister?,
        createdAt: Long?,
    ): StudioDraft = io {
        val now = clock.now().toEpochMilliseconds()
        q.putDraft(id, title.trim(), body, taskRef, taskPrompt, register?.name, createdAt ?: now, now)
        q.draftById(id).executeAsOne().toDraft()
    }

    private fun Writing_draft.toDraft() = StudioDraft(
        id, title, body, task_ref, task_prompt, target_register?.let { r -> SpeechRegister.entries.firstOrNull { it.name == r } }, created_at, updated_at,
    )

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
