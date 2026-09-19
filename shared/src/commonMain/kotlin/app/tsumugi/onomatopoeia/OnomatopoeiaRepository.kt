package app.tsumugi.onomatopoeia

import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.dictionary.db.Onomatopoeia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * The onomatopoeia module (BRIEF_V2 §6.8) over the dictionary pack's `onomatopoeia` tables. Dictionary packs built
 * before Phase 12 lack them; every call then returns empty results, which the UI shows as an honest empty state
 * ("update the dictionary pack").
 */
class OnomatopoeiaRepository(private val pack: DictionaryDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val q get() = pack.onomatopoeiaQueries
    private val lock = Mutex()
    private var cache: List<OnomatopoeiaWord>? = null

    /** False when the installed dictionary pack has no onomatopoeia tables (or they are empty). */
    @Throws(Exception::class)
    suspend fun available(): Boolean = all().isNotEmpty()

    /** Every word, most frequent first. Loaded once (about 1,300 rows). */
    @Throws(Exception::class)
    suspend fun all(): List<OnomatopoeiaWord> = lock.withLock {
        cache ?: withContext(Dispatchers.IO) {
            runCatching { q.onomatopoeiaAll().executeAsList() }.getOrDefault(emptyList()).mapNotNull { it.toWord() }
        }.also { cache = it }
    }

    /** Themes in display order, with how many words each has. */
    @Throws(Exception::class)
    suspend fun themes(): List<OnomatopoeiaTheme> {
        val counts = all().groupingBy { it.theme }.eachCount()
        val rows = withContext(Dispatchers.IO) { runCatching { q.onomatopoeiaThemes().executeAsList() }.getOrDefault(emptyList()) }
        return rows.map { OnomatopoeiaTheme(it.id, it.ord.toInt(), it.title_en, it.title_ja, it.blurb, it.svg, counts[it.id] ?: 0) }
    }

    /**
     * Words filtered by [theme] and/or [type], most frequent first. [withFeelOnly] keeps the words that have our feel
     * line (the rest show gloss and examples only).
     */
    @Throws(Exception::class)
    suspend fun words(theme: String? = null, type: OnomatopoeiaType? = null, withFeelOnly: Boolean = false): List<OnomatopoeiaWord> =
        all().filter { (theme == null || it.theme == theme) && (type == null || it.type == type) && (!withFeelOnly || it.hasFeel) }

    /** Words whose text, variants, glosses or feel contain [query] (kana folded to hiragana). */
    @Throws(Exception::class)
    suspend fun search(query: String): List<OnomatopoeiaWord> {
        val needle = app.tsumugi.jp.Kana.toHiragana(query.trim()).lowercase()
        if (needle.isEmpty()) return emptyList()
        return all().filter { w ->
            (listOf(w.text) + w.variants).any { app.tsumugi.jp.Kana.toHiragana(it).contains(needle) } ||
                w.glosses.any { it.lowercase().contains(needle) } || w.feel.lowercase().contains(needle)
        }
    }

    @Throws(Exception::class)
    suspend fun detail(entryId: Long): OnomatopoeiaDetail? {
        val word = all().firstOrNull { it.entryId == entryId } ?: return null
        val theme = themes().firstOrNull { it.id == word.theme }
        return OnomatopoeiaDetail(word, theme, examples(word))
    }

    /** The word's Tatoeba examples, in the order the builder chose (short, clear sentences first). */
    @Throws(Exception::class)
    suspend fun examples(word: OnomatopoeiaWord): List<OnomatopoeiaExample> = withContext(Dispatchers.IO) {
        if (word.exampleIds.isEmpty()) return@withContext emptyList()
        val rows = runCatching { q.onomatopoeiaSentences(word.exampleIds).executeAsList() }.getOrDefault(emptyList()).associateBy { it.id }
        word.exampleIds.mapNotNull { id -> rows[id]?.let { OnomatopoeiaExample(it.id, it.ja, it.en) } }
    }

    /** A quiz of up to [count] questions (see [OnomatopoeiaQuiz]); [theme] limits the targets. */
    @Throws(Exception::class)
    suspend fun quiz(count: Int = 10, kind: OnomatopoeiaQuizKind? = null, theme: String? = null, seed: Long? = null): List<OnomatopoeiaQuestion> =
        OnomatopoeiaQuiz.questions(all(), count, seed?.let { Random(it) } ?: Random.Default, kind, theme)

    private fun Onomatopoeia.toWord(): OnomatopoeiaWord? {
        val kind = OnomatopoeiaType.of(type) ?: return null
        return OnomatopoeiaWord(
            entryId = entry_id,
            order = ord.toInt(),
            text = text,
            variants = strings(variants),
            type = kind,
            theme = theme,
            glosses = strings(gloss),
            feel = feel,
            feelJa = feel_ja,
            exampleIds = if (examples.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Long>>(examples) }.getOrDefault(emptyList()),
            aiGenerated = source == "llm", // "rule": rule-classified, no drafted text; "verified": reviewed
        )
    }

    private fun strings(text: String): List<String> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
}
