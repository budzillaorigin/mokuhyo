package app.mokuhyo.opi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Exemplar answers (BRIEF_PHASE8 N-05): `packs/<lang>/exemplars.json` — for 30 interview prompts per language, model
 * answers at ILR 1+, 2 and 3 with a two-line note on why each is that level and not the next. AI-drafted, badged.
 */
@Serializable
data class Exemplar(
    val id: String,
    val questionId: String,
    val prompt: String,
    val english: String = "",
    val level: String,
    val response: String,
    val note: String,
    val audio: String? = null,
    val source: String = "llm",
    val verified: Boolean = false,
)

@Serializable
data class ExemplarPack(val format: String = "mokuhyo-exemplars/1", val lang: String, val license: String = "", val exemplars: List<Exemplar> = emptyList()) {
    /** The questions with their 1+/2/3 answers, in pack order. */
    fun byQuestion(): List<Pair<Exemplar, List<Exemplar>>> =
        exemplars.groupBy { it.questionId }.values.map { group -> group.first() to group.sortedBy { LEVELS.indexOf(it.level) } }

    /**
     * Exemplars for an interviewer [question]: the same bank question when it is one, else the most similar prompt
     * (character-bigram overlap), else nothing.
     */
    fun forQuestion(question: String): List<Exemplar> {
        exemplars.filter { it.prompt == question }.takeIf { it.isNotEmpty() }?.let { return it.sortedBy { e -> LEVELS.indexOf(e.level) } }
        fun bigrams(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.windowed(2).toSet()
        val q = bigrams(question)
        val best = byQuestion().maxByOrNull { (head, _) -> val b = bigrams(head.prompt); if (q.isEmpty() || b.isEmpty()) 0.0 else (q intersect b).size.toDouble() / (q union b).size }
        val score = best?.let { (head, _) -> val b = bigrams(head.prompt); (q intersect b).size.toDouble() / (q union b).size.coerceAtLeast(1) } ?: 0.0
        return if (score >= 0.35) best!!.second else emptyList()
    }

    companion object {
        val LEVELS = listOf("1+", "2", "3")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ExemplarPack = json.decodeFromString(serializer(), text)
    }
}
