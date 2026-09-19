package app.tsumugi.poetry

import app.tsumugi.linguist.db.Circle_text
import app.tsumugi.linguist.db.LinguistDatabase
import app.tsumugi.linguist.db.Poem
import app.tsumugi.reader.RubyHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A poetry theme: sky, sea, moon, the four seasons, rain. */
data class PoemTheme(val id: String, val ja: String, val en: String)

/** The Aozora Bunko work a poem or circle text comes from, with its public-domain facts and colophon (D-276). */
data class AozoraSource(
    val workId: String,
    val title: String,
    val author: String,
    val authorReading: String,
    val authorEn: String,
    val born: String,
    val died: String,
    /** 新字新仮名, 新字旧仮名, 旧字旧仮名. */
    val orthography: String,
    val cardUrl: String,
    /** 底本, 入力, 校正 and Aozora's volunteers' note: shown under the text. */
    val colophon: String,
)

@Serializable
data class PoemWord(val word: String, val reading: String, val gloss: String, val start: Int = -1)

data class PoemSummary(val id: String, val title: String, val titleEn: String, val author: String, val firstLine: String, val themes: List<String>)

data class PoemDetail(
    val id: String,
    val title: String,
    val titleEn: String,
    val author: String,
    /** Lines, one blank line between stanzas; the text is public domain (Aozora Bunko). */
    val body: String,
    val ruby: List<RubyHint>,
    val vocabulary: List<PoemWord>,
    /** Plain modern Japanese (LLM-drafted, labeled). */
    val paraphrase: String,
    /** English gloss (LLM-drafted, labeled). */
    val gloss: String,
    val note: String,
    val themes: List<String>,
    /** llm until reviewed: the badge sits on the paraphrase, gloss, note and vocabulary, never on the poem. */
    val source: String,
    val work: AozoraSource?,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

data class CircleTextSummary(val id: String, val title: String, val titleEn: String, val author: String, val level: String, val sentenceCount: Int)

data class CircleSentence(val index: Int, val start: Int, val end: Int, val text: String)

data class CircleTextDetail(
    val id: String,
    val title: String,
    val titleEn: String,
    val author: String,
    val level: String,
    val body: String,
    val ruby: List<RubyHint>,
    /** Our English summary (LLM-drafted, labeled). */
    val summaryEn: String,
    val sentences: List<CircleSentence>,
    val source: String,
    val work: AozoraSource?,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

/**
 * The poetry corner and the reading-circle texts of the linguist pack (BRIEF_V2 §6.14; D-276…D-278). Every call is
 * empty (not an error) while the pack isn't installed.
 */
class PoetryRepository(private val db: LinguistDatabase) {
    private val q get() = db.linguistQueries
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(Exception::class)
    suspend fun available(): Boolean = poems().isNotEmpty()

    @Throws(Exception::class)
    suspend fun themes(): List<PoemTheme> = io { safe { q.themes().executeAsList() }.map { PoemTheme(it.id, it.ja, it.en) } }

    @Throws(Exception::class)
    suspend fun poems(theme: String? = null): List<PoemSummary> = io {
        val rows = safe { if (theme == null) q.poems().executeAsList() else q.poemsForTheme(theme).executeAsList() }
        rows.map { p -> PoemSummary(p.id, p.title, p.title_en, p.author, p.body.lineSequence().first { it.isNotBlank() }.trim(), themesOf(p.id)) }
    }

    @Throws(Exception::class)
    suspend fun poem(id: String): PoemDetail? = io {
        val p = safe { listOfNotNull(q.poemById(id).executeAsOneOrNull()) }.firstOrNull() ?: return@io null
        p.toDetail()
    }

    @Throws(Exception::class)
    suspend fun circleTexts(): List<CircleTextSummary> = io {
        safe { q.circleTexts().executeAsList() }.map { CircleTextSummary(it.id, it.title, it.title_en, it.author, it.level, it.sentence_count.toInt()) }
    }

    @Throws(Exception::class)
    suspend fun circleText(id: String): CircleTextDetail? = io {
        val t = safe { listOfNotNull(q.circleText(id).executeAsOneOrNull()) }.firstOrNull() ?: return@io null
        t.toDetail()
    }

    private fun Poem.toDetail() = PoemDetail(
        id, title, title_en, author, body, rubyOf(ruby),
        runCatching { json.decodeFromString(ListSerializer(PoemWord.serializer()), vocabulary) }.getOrDefault(emptyList()),
        paraphrase, gloss, note, themesOf(id), if (verified != 0L) "verified" else source, workOf(work_id),
    )

    private fun Circle_text.toDetail(): CircleTextDetail {
        val sentences = q.circleSentences(id).executeAsList().map { CircleSentence(it.idx.toInt(), it.start_offset.toInt(), it.end_offset.toInt(), it.text) }
        return CircleTextDetail(id, title, title_en, author, level, body, rubyOf(ruby), summary_en, sentences, source, workOf(work_id))
    }

    private fun themesOf(poemId: String): List<String> = safe { q.themesOfPoem(poemId).executeAsList() }

    private fun workOf(id: String): AozoraSource? = safe { listOfNotNull(q.work(id).executeAsOneOrNull()) }.firstOrNull()?.let {
        AozoraSource(it.id, it.title, it.author, it.author_reading, it.author_en, it.born, it.died, it.orthography, it.card_url, it.colophon)
    }

    private fun rubyOf(raw: String): List<RubyHint> =
        runCatching { json.decodeFromString(ListSerializer(PackRuby.serializer()), raw) }.getOrDefault(emptyList()).map { RubyHint(it.start, it.base, it.reading) }

    @Serializable
    private data class PackRuby(val start: Int, val base: String, val reading: String)

    private fun <T> safe(block: () -> List<T>): List<T> = runCatching(block).getOrDefault(emptyList())

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
