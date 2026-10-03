package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiResult
import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Role
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.ScriptCheck
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Consecutive interpretation (BRIEF_PHASE8 N-01): a chunk of 1–3 sentences is played (target language or English),
 * the learner may take notes during a pause, then renders it by voice in the other language; each chunk is graded
 * for accuracy, completeness and register. Feedback is presented After-action style (at the end) by default.
 */
enum class InterpretDirection(val title: String) { TO_ENGLISH("Into English"), FROM_ENGLISH("From English") }

enum class InterpretVariant(val title: String) {
    CONSECUTIVE("Consecutive"),
    /** The chunk plays through the radio channel of the degraded-audio chain, with brevity words (N-06). */
    RADIO_RELAY("Radio relay"),
    /** A notice or order on screen, timed, rendered aloud. */
    SIGHT("Sight translation"),
}

/** One chunk: the text to render and, when the source has one, a reference translation. */
@Serializable
data class Chunk(val id: String, val source: String, val sourceLang: String, val reference: String = "", val speakerVoice: String = "female")

/** A line of the source material with its translation (dialogue lines, passage sentences). */
data class SourceLine(val text: String, val english: String, val voice: String = "female")

object Chunker {
    private val END = Regex("(?<=[.!?。！？؟])\\s*")

    /** Sentences of [text] (keeps the end mark); for unspaced scripts too. */
    fun sentences(text: String): List<String> = END.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Chunks from [lines] in [direction]: each line is a chunk; a line longer than [maxSentences] sentences is split, and
     * short consecutive lines (under [minChars] characters, e.g. a greeting) are joined (up to [maxSentences] sentences per chunk).
     */
    fun chunks(lines: List<SourceLine>, lang: String, direction: InterpretDirection, idPrefix: String, maxSentences: Int = 3, minChars: Int = 20): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var bufSource = mutableListOf<String>()
        var bufRef = mutableListOf<String>()
        var voice = "female"
        fun flush() {
            if (bufSource.isEmpty()) return
            val (src, ref) = bufSource.joinToString(" ") to bufRef.joinToString(" ")
            out += if (direction == InterpretDirection.TO_ENGLISH) Chunk("$idPrefix-${out.size + 1}", src, lang, ref, voice)
            else Chunk("$idPrefix-${out.size + 1}", ref, "en", src, voice)
            bufSource = mutableListOf(); bufRef = mutableListOf()
        }
        for (l in lines) {
            val s = sentences(l.text)
            if (s.size > maxSentences) {
                flush()
                s.chunked(maxSentences).forEach { part ->
                    bufSource += part.joinToString(" ")
                    bufRef += "" // a split line has no per-part reference
                    voice = l.voice
                    flush()
                }
                continue
            }
            val tooShort = l.text.length < minChars
            if (bufSource.isNotEmpty() && (!tooShort || sentences(bufSource.joinToString(" ")).size + s.size > maxSentences)) flush()
            bufSource += l.text
            bufRef += l.english
            voice = l.voice
            if (!tooShort) flush()
        }
        flush()
        return out.filter { it.source.isNotBlank() }
    }
}

/** `grade_interpretation`: accuracy, completeness and register of one rendered chunk, with what was missed and a better version. */
class GradeInterpretation : PromptTask<GradeInterpretation.Input, GradeInterpretation.Output> {
    data class Input(val chunk: Chunk, val targetLang: String, val rendering: String)

    @Serializable
    data class Output(
        val accuracy: Int,
        val completeness: Int,
        val register: Int,
        val omissions: List<String> = emptyList(),
        val distortions: List<String> = emptyList(),
        @SerialName("better_version") val betterVersion: String,
        val note: String = "",
    ) {
        /** 0–100 from the three 0–5 scores (accuracy counts double). */
        val score: Int get() = ((accuracy * 2 + completeness + register) * 100) / 20
    }

    override val name = "grade_interpretation"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val maxTokens = 700
    override val schema = JsonSchema.Obj(listOf(
        "accuracy" to JsonSchema.Integer, "completeness" to JsonSchema.Integer, "register" to JsonSchema.Integer,
        "omissions" to JsonSchema.Arr(JsonSchema.Str(maxLength = 160), maxItems = 4),
        "distortions" to JsonSchema.Arr(JsonSchema.Str(maxLength = 160), maxItems = 4),
        "better_version" to JsonSchema.Str(maxLength = 600), "note" to JsonSchema.Str(maxLength = 300),
    ))

    private fun name(code: String) = if (code == "en") "English" else Languages.of(code)?.nameEnglish ?: code

    override fun messages(input: Input): List<ChatMessage> = listOf(
        ChatMessage(Role.SYSTEM, "You grade a military linguist's consecutive interpretation from ${name(input.chunk.sourceLang)} into " +
            "${name(input.targetLang)}. Score 0–5 each: accuracy (meaning carried over correctly, numbers and names exact), completeness " +
            "(nothing important left out), register (appropriate formality and military register). omissions and distortions: short English " +
            "notes, empty when none. better_version: a good ${name(input.targetLang)} rendering of the source. note: one English sentence of advice. " +
            "Judge meaning, not word-for-word closeness; speech-to-text errors in the rendering are not the learner's fault."),
        ChatMessage(Role.USER, "Source (${name(input.chunk.sourceLang)}): ${input.chunk.source}\n" +
            (if (input.chunk.reference.isNotBlank()) "Reference translation: ${input.chunk.reference}\n" else "") +
            "Learner's rendering (${name(input.targetLang)}): ${input.rendering}"),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = listOfNotNull(
        listOf(output.accuracy, output.completeness, output.register).firstOrNull { it !in 0..5 }?.let { "scores must be 0–5 (got $it)" },
        if (output.betterVersion.isBlank()) "better_version is empty" else null,
        if (input.targetLang == "en") ScriptCheck.requireEnglish("better_version", output.betterVersion)
        else ScriptCheck.requireLanguage("better_version", output.betterVersion, input.targetLang),
    )
}

@Serializable
data class InterpretResult(
    val chunk: Chunk,
    val rendering: String,
    val grade: GradeInterpretation.Output? = null,
    val engine: String? = null,
)

/**
 * One interpretation session: chunks in order, each rendering graded (immediately, or at the end when
 * [gradeAtEnd] — the After-action default). [results] goes into the conversation record and the PDF report.
 */
class InterpretSession(
    val language: String,
    val direction: InterpretDirection,
    val variant: InterpretVariant,
    val chunks: List<Chunk>,
    private val gateway: AiGateway,
    val gradeAtEnd: Boolean = true,
) {
    private val renderings = mutableListOf<String>()
    val results = mutableListOf<InterpretResult>()
    val current: Chunk? get() = chunks.getOrNull(renderings.size)
    val finished: Boolean get() = renderings.size >= chunks.size
    val targetLang: String get() = if (direction == InterpretDirection.TO_ENGLISH) "en" else language

    /** Records the learner's rendering of the current chunk; grades now unless [gradeAtEnd]. Returns the result when graded. */
    suspend fun render(text: String): InterpretResult? {
        val chunk = current ?: return null
        renderings += text.trim()
        return if (gradeAtEnd) null else grade(chunk, text.trim()).also { results += it }
    }

    /** Grades everything not yet graded (the end of an After-action session). */
    suspend fun finish(): List<InterpretResult> {
        chunks.take(renderings.size).forEachIndexed { i, c ->
            if (results.none { it.chunk.id == c.id }) results += grade(c, renderings[i])
        }
        results.sortBy { r -> chunks.indexOfFirst { it.id == r.chunk.id } }
        return results.toList()
    }

    private suspend fun grade(chunk: Chunk, rendering: String): InterpretResult {
        if (rendering.isBlank()) return InterpretResult(chunk, rendering)
        return when (val r = gateway.run(GradeInterpretation(), GradeInterpretation.Input(chunk, targetLang, rendering))) {
            is AiResult.Ok -> InterpretResult(chunk, rendering, r.value, r.engine)
            else -> InterpretResult(chunk, rendering)
        }
    }

    /** Mean score of the graded chunks (0–100), or null. */
    fun meanScore(): Int? = results.mapNotNull { it.grade?.score }.takeIf { it.isNotEmpty() }?.average()?.toInt()
}
