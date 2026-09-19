package app.tsumugi.reader

import app.tsumugi.db.Reader_doc_vocab
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.srs.AnswerChecker
import app.tsumugi.srs.CheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

/** A word looked up in a document, with the sentence it was looked up in. */
data class DocumentWord(
    val ref: String,
    val text: String,
    val reading: String,
    val gloss: String,
    val entryId: Long?,
    val sentence: String,
    val wordStart: Int,
    val wordEnd: Int,
    val lookups: Int,
    val firstLookedUpAt: Long,
)

/** A context flashcard: the word shown inside its sentence ([before] + [word] + [after]). */
data class ContextCard(
    val ref: String,
    val sentence: String,
    val before: String,
    val word: String,
    val after: String,
    val reading: String,
    val gloss: String,
    val entryId: Long?,
)

/** How one context card went in a drill. */
data class ContextCardResult(val card: ContextCard, val correct: Boolean, val check: CheckResult?)

/**
 * The document's context-flashcard drill (BRIEF_V2 §6.4): each looked-up word inside its sentence. The learner types
 * the reading ([answerReading], kana/romaji, AnswerChecker rules) or the meaning ([answerMeaning]), or reveals and
 * grades themselves ([grade]). A practice pass over the list, not SRS; [DocumentVocabulary.addAllToReviews] puts the
 * words into reviews with the sentence as context.
 */
class ContextDrill(val cards: List<ContextCard>) {
    private var index = 0
    private val results = ArrayList<ContextCardResult>()

    val current: ContextCard? get() = cards.getOrNull(index)
    val position: Int get() = index
    val finished: Boolean get() = index >= cards.size
    val answered: List<ContextCardResult> get() = results.toList()

    /** (correct, answered) so far. */
    val score: Pair<Int, Int> get() = results.count { it.correct } to results.size

    fun answerReading(given: String): ContextCardResult = record(AnswerChecker.checkReading(given, listOf(currentOrThrow().reading)))

    fun answerMeaning(given: String): ContextCardResult =
        record(AnswerChecker.checkMeaning(given, currentOrThrow().gloss.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }))

    /** Self-grading after revealing the back. */
    fun grade(correct: Boolean): ContextCardResult {
        val r = ContextCardResult(currentOrThrow(), correct, null)
        results += r
        index++
        return r
    }

    private fun record(check: CheckResult): ContextCardResult {
        val r = ContextCardResult(currentOrThrow(), check.accepted, check)
        results += r
        index++
        return r
    }

    private fun currentOrThrow(): ContextCard = current ?: throw IllegalStateException("the drill is finished")
}

/**
 * The automatic vocabulary list of a reader document (BRIEF_V2 §6.4, Japan Reader; DECISIONS D-165): every word the
 * learner looks up in a document is added to that document's list with the sentence it was in, and "Drill" turns the
 * list into context flashcards. Device-local like the document; words sent to reviews become ordinary synced items.
 */
class DocumentVocabulary(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val q get() = db.readerNotesQueries

    /** Records a lookup of [token] in [sentence] (the reader calls this whenever the word popup opens). */
    @Throws(Exception::class)
    suspend fun recordLookup(documentId: String, token: ReaderToken, sentence: ReaderSentence, gloss: String) =
        recordLookup(
            documentId, token.dictionaryForm ?: token.surface, token.lemmaReading ?: token.reading.orEmpty(), gloss, token.entryId,
            sentence.text, token.start - sentence.start, token.end - sentence.start,
        )

    /** Records a lookup; [wordStart]/[wordEnd] are offsets of the looked-up surface in [sentence]. */
    @Throws(Exception::class)
    suspend fun recordLookup(
        documentId: String,
        word: String,
        reading: String,
        gloss: String,
        entryId: Long?,
        sentence: String,
        wordStart: Int,
        wordEnd: Int,
    ) {
        val text = normalizeNfc(word.trim())
        if (text.isEmpty()) return
        val line = sentence // offsets refer to it as the reader shows it (already NFC)
        val (ws, we) = if (wordStart in 0 until wordEnd && wordEnd <= line.length) wordStart to wordEnd else (line.indexOf(text).takeIf { it >= 0 }?.let { it to it + text.length } ?: (0 to 0))
        val ref = entryId?.let { "jmdict:$it" } ?: "text:$text|$reading"
        val now = clock.now().toEpochMilliseconds()
        withContext(Dispatchers.IO) {
            db.transaction {
                q.insertDocVocab(documentId, ref, text, reading, gloss, entryId, line, ws.toLong(), we.toLong(), now, now)
                q.bumpDocVocab(now, line, ws.toLong(), we.toLong(), documentId, ref)
            }
        }
    }

    @Throws(Exception::class)
    suspend fun words(documentId: String): List<DocumentWord> = withContext(Dispatchers.IO) { q.docVocab(documentId).executeAsList().map { it.toWord() } }

    @Throws(Exception::class)
    suspend fun remove(documentId: String, ref: String) {
        withContext(Dispatchers.IO) { q.removeDocVocab(documentId, ref) }
    }

    /** Context cards for the whole list, shuffled with [seed] (null keeps lookup order). */
    @Throws(Exception::class)
    suspend fun drill(documentId: String, seed: Long? = null): ContextDrill {
        val cards = words(documentId).map { it.toCard() }
        return ContextDrill(if (seed == null) cards else cards.shuffled(Random(seed)))
    }

    /**
     * Adds every dictionary word of the list to reviews with its sentence as context (the card shows the word inside
     * it). Returns how many were added; words without a dictionary entry are skipped.
     */
    @Throws(Exception::class)
    suspend fun addAllToReviews(
        documentId: String,
        entry: suspend (Long) -> DictionaryEntry?,
        addToReviews: suspend (DictionaryEntry, String) -> String,
    ): Int {
        var added = 0
        for (w in words(documentId)) {
            val e = w.entryId?.let { entry(it) } ?: continue
            addToReviews(e, w.sentence)
            added++
        }
        return added
    }

    /** Drops the list and the document's extras (the document was deleted). */
    @Throws(Exception::class)
    suspend fun forget(documentId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                q.deleteDocVocab(documentId)
                q.deleteDocMeta(documentId)
            }
        }
    }

    private fun Reader_doc_vocab.toWord() = DocumentWord(
        ref, text, reading, gloss, entry_id, sentence, word_start.toInt(), word_end.toInt(), lookups.toInt(), first_at,
    )

    companion object {
        fun cardOf(w: DocumentWord): ContextCard = w.toCard()

        private fun DocumentWord.toCard(): ContextCard {
            val s = sentence
            val ws = wordStart.coerceIn(0, s.length)
            val we = wordEnd.coerceIn(ws, s.length)
            val shown = if (we > ws) s.substring(ws, we) else text
            return ContextCard(ref, s, s.substring(0, ws), shown, s.substring(we), reading, gloss, entryId)
        }
    }
}
