package app.tsumugi.coverage

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.jp.Kana
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the learner knows of a word or kanji (BRIEF_V2 §6.1: Guru+ = known, Apprentice = learning). */
enum class WordState { KNOWN, LEARNING, UNKNOWN }

/**
 * An immutable picture of what the learner knows, built once from the SRS state and the known-word list and reused
 * for every coverage, deck and 1T computation until something changes ([LearnerKnowledge] checks a fingerprint).
 *
 * Words are matched by JMdict id first (`jmdict:<id>` and path `v:<id>` items, marked words), then by written form
 * (WaniKani-only `wk:` items and Anki notes, whose primary text is the word). Kanji are known when their kanji item
 * is at Guru, or when they appear in a known word (DECISIONS D-151).
 */
class KnowledgeSnapshot internal constructor(
    private val entries: Map<Long, WordState>,
    private val texts: Map<String, WordState>,
    private val kanjiStates: Map<String, WordState>,
    /** Changes whenever the inputs change; coverage caches key on it. */
    val version: String,
) {
    /** The learner's state for a word: by JMdict id, else by its dictionary form or surface. */
    fun word(entryId: Long?, lemma: String? = null, surface: String? = null): WordState {
        entryId?.let { id -> entries[id]?.let { return it } }
        lemma?.let { l -> texts[l]?.let { return it } }
        surface?.let { s -> texts[s]?.let { return it } }
        return WordState.UNKNOWN
    }

    fun kanji(literal: String): WordState = kanjiStates[literal] ?: WordState.UNKNOWN

    /** Number of distinct words known (Guru+ or marked known), by JMdict id. */
    val knownWordCount: Int get() = entries.count { it.value == WordState.KNOWN }

    val knownKanjiCount: Int get() = kanjiStates.count { it.value == WordState.KNOWN }

    companion object {
        /** Nothing known: coverage from a clean slate (media deck statistics). */
        val EMPTY = KnowledgeSnapshot(emptyMap(), emptyMap(), emptyMap(), "empty")

        internal fun stateOf(stage: Stage): WordState = if (stage >= Stage.GURU) WordState.KNOWN else WordState.LEARNING

        internal fun better(a: WordState?, b: WordState): WordState = when {
            a == null -> b
            a == WordState.KNOWN || b == WordState.KNOWN -> WordState.KNOWN
            a == WordState.LEARNING || b == WordState.LEARNING -> WordState.LEARNING
            else -> WordState.UNKNOWN
        }

        /**
         * Builds a snapshot. [stages] = item id → stage of started items; [items] = (item id, kind, primary text) of
         * vocabulary-like and kanji items; [known] = (entry id, text) of words marked known.
         */
        fun build(
            stages: Map<String, Stage>,
            items: List<Triple<String, String, String>>,
            known: List<Pair<Long, String>>,
            version: String,
        ): KnowledgeSnapshot {
            val entries = HashMap<Long, WordState>()
            val texts = HashMap<String, WordState>()
            val kanji = HashMap<String, WordState>()
            fun addText(text: String, state: WordState) {
                if (text.isBlank() || text.length > MAX_WORD_TEXT) return
                texts[text] = better(texts[text], state)
            }
            for ((id, kind, text) in items) {
                val stage = stages[id] ?: continue
                val state = stateOf(stage)
                val entryId = entryIdOf(id)
                when {
                    kind == ItemKind.KANJI.name && text.codePointCount() == 1 && Kana.containsKanji(text) -> kanji[text] = better(kanji[text], state)
                    entryId != null -> {
                        entries[entryId] = better(entries[entryId], state)
                        addText(text, state)
                    }
                    else -> addText(text, state)
                }
            }
            // Items the stages know but the item query didn't return (e.g. kinds outside it) still count by id.
            for ((id, stage) in stages) {
                val entryId = entryIdOf(id) ?: continue
                entries[entryId] = better(entries[entryId], stateOf(stage))
            }
            for ((entryId, text) in known) {
                entries[entryId] = WordState.KNOWN
                addText(text, WordState.KNOWN)
            }
            // Kanji inside known (or learning) words: someone who reads 学校 reads 学 and 校 in it.
            for ((text, state) in texts) {
                for (cp in text.codePointStrings()) {
                    if (Kana.containsKanji(cp) && state != WordState.UNKNOWN) kanji[cp] = better(kanji[cp], state)
                }
            }
            return KnowledgeSnapshot(entries, texts, kanji, version)
        }

        /** The JMdict id of a word item (`jmdict:<id>`, path `v:<id>`), else null. */
        fun entryIdOf(itemId: String): Long? = when {
            itemId.startsWith("jmdict:") -> itemId.removePrefix("jmdict:").toLongOrNull()
            itemId.startsWith("v:") -> itemId.removePrefix("v:").toLongOrNull()
            else -> null
        }

        private const val MAX_WORD_TEXT = 16
    }
}

/**
 * Builds and caches the learner's [KnowledgeSnapshot] (BRIEF_V2 §6.1 coverage overlay). One full pass over the
 * started cards and the word items, reused until the `knowledgeFingerprint` query (review count and last review, item
 * count and last change, known-word count and last change) changes: any review, undo, lesson, import, sync pull or
 * "mark known" invalidates it, and nothing else pays for a rebuild (DECISIONS D-153).
 */
class LearnerKnowledge(private val db: TsumugiDatabase, private val srs: SrsRepository) {
    private val lock = Mutex()
    private var cached: KnowledgeSnapshot? = null

    @Throws(Exception::class)
    suspend fun snapshot(): KnowledgeSnapshot = lock.withLock {
        val version = fingerprint()
        cached?.takeIf { it.version == version }?.let { return@withLock it }
        val stages = srs.stages()
        val (items, known) = withContext(Dispatchers.IO) {
            db.decksQueries.knowledgeItems().executeAsList().map { Triple(it.id, it.kind, it.primary_text) } to
                db.decksQueries.knownWordIds().executeAsList().map { it.entry_id to it.text }
        }
        KnowledgeSnapshot.build(stages, items, known, version).also { cached = it }
    }

    /** The current fingerprint; cheap (a handful of aggregate queries over indexed columns). */
    @Throws(Exception::class)
    suspend fun fingerprint(): String = withContext(Dispatchers.IO) {
        val f = db.decksQueries.knowledgeFingerprint().executeAsOne()
        "${f.reviews}:${f.last_review}:${f.items}:${f.last_item}:${f.known}:${f.last_known}"
    }

    /** Drops the cache (tests; the fingerprint normally makes this unnecessary). */
    fun invalidate() {
        cached = null
    }
}

internal fun String.codePointCount(): Int = codePointStrings().size

/** Splits into code points as strings (keeps surrogate pairs together; rare kanji are outside the BMP). */
internal fun String.codePointStrings(): List<String> {
    val out = ArrayList<String>(length)
    var i = 0
    while (i < length) {
        val n = if (this[i].isHighSurrogate() && i + 1 < length) 2 else 1
        out += substring(i, i + n)
        i += n
    }
    return out
}
