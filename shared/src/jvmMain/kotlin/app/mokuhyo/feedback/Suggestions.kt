package app.mokuhyo.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * "Suggest a term" and "Flag this item" (BRIEF_PHASE8 N-10): learner notes kept in `<data dir>/suggestions.json`, never
 * sent anywhere. They travel in the `.mokuhyo` bundle (merged by id) and can be exported on their own for the
 * curator, who ingests them with `tools/items/review.py suggestions <file>`.
 */
@Serializable
data class Suggestion(
    val id: String,
    val created: String,
    val lang: String,
    /** suggest_term | flag */
    val type: String,
    /** term | passage | item | turn | card | persona | dialogue | scenario | none */
    val targetKind: String,
    val targetId: String = "",
    val text: String,
    /** Quoted context (the term, the sentence, the learner's turn). */
    val context: String = "",
)

@Serializable
data class SuggestionFile(val format: String = FORMAT, val suggestions: List<Suggestion> = emptyList()) {
    companion object {
        const val FORMAT = "mokuhyo-suggestions/1"
    }
}

class SuggestionStore(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun all(): List<Suggestion> = read().suggestions

    @Synchronized
    fun add(lang: String, type: String, targetKind: String, targetId: String, text: String, context: String = ""): Suggestion {
        require(type in TYPES) { "type must be one of $TYPES" }
        require(text.isNotBlank()) { "say what you suggest" }
        val s = Suggestion(UUID.randomUUID().toString(), Instant.now().toString(), lang, type, targetKind, targetId, text.trim().take(2000), context.take(500))
        write(read().let { it.copy(suggestions = it.suggestions + s) })
        return s
    }

    /** Adds the suggestions of [other] not already here (by id); returns how many were added. Never removes any. */
    @Synchronized
    fun merge(other: SuggestionFile): Int {
        val mine = read()
        val ids = mine.suggestions.map { it.id }.toSet()
        val added = other.suggestions.filter { it.id !in ids }
        if (added.isNotEmpty()) write(mine.copy(suggestions = mine.suggestions + added))
        return added.size
    }

    fun encode(f: SuggestionFile = read()): String = json.encodeToString(SuggestionFile.serializer(), f)

    fun decode(text: String): SuggestionFile = json.decodeFromString(SuggestionFile.serializer(), text).also {
        require(it.format == SuggestionFile.FORMAT) { "not a Mokuhyo suggestions file" }
    }

    /** A standalone export for the curator. */
    fun exportTo(out: File) = out.writeText(encode())

    private fun read(): SuggestionFile = if (file.isFile) decode(file.readText()) else SuggestionFile()

    private fun write(f: SuggestionFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(f))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        val TYPES = setOf("suggest_term", "flag")
    }
}
