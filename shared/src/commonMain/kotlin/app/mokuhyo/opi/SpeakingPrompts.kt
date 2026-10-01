package app.mokuhyo.opi

import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Role
import app.mokuhyo.ai.Validation
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.ScriptCheck
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One line of a conversation transcript. */
@Serializable
data class Turn(val speaker: Speaker, val text: String)

@Serializable
enum class Speaker { LEARNER, PARTNER }

/** OPI phases (BRIEF §6.2). */
@Serializable
enum class OpiPhase {
    @SerialName("warmup") WARMUP,
    @SerialName("level_check") LEVEL_CHECK,
    @SerialName("probe") PROBE,
    @SerialName("roleplay") ROLEPLAY,
    @SerialName("winddown") WINDDOWN,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun of(wire: String): OpiPhase? = entries.firstOrNull { it.wireName == wire }
    }
}

/** Public-domain ILR speaking descriptions, paraphrased, used to steer the interviewer and the rater. */
object IlrSpeaking {
    val descriptors = mapOf(
        "0+" to "memorized words and phrases for immediate needs; cannot sustain sentences",
        "1" to "creates simple sentences on familiar personal topics; asks and answers simple questions; survival needs",
        "1+" to "handles most routine social situations with connected sentences; narrates and describes, but breaks down under complication",
        "2" to "handles routine work and social situations; narrates in past, present and future; describes in paragraphs; concrete topics",
        "2+" to "often handles abstract topics and supported opinion, but not consistently; some breakdown in complex argument",
        "3" to "supports opinions, hypothesizes, discusses abstract and unfamiliar topics in extended discourse with good control",
    )

    fun describe(level: IlrLevel) = descriptors[level.label] ?: ""
}

private fun languageName(code: String) = Languages.of(code)?.nameEnglish ?: code

private fun system(vararg lines: String) = ChatMessage(Role.SYSTEM, lines.joinToString("\n"))

private fun user(text: String) = ChatMessage(Role.USER, text)

private fun transcript(history: List<Turn>, learner: String, partner: String): String =
    history.joinToString("\n") { t -> (if (t.speaker == Speaker.LEARNER) learner else partner) + ": " + t.text }

private fun issues(vararg problems: String?): List<String> = problems.filterNotNull()

/** `opi_interviewer_turn`: the interviewer's next question in a practice OPI-style interview, in any language. */
class OpiInterviewerTurn(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<OpiInterviewerTurn.Input, OpiInterviewerTurn.Output> {
    data class Input(
        val language: String,
        val registerNotes: String,
        val phase: OpiPhase,
        val workingLevel: IlrLevel,
        val history: List<Turn> = emptyList(),
        val turnsInPhase: Int = 0,
        /** A role-play situation to set up (English), when the phase is roleplay. */
        val rolePlay: String? = null,
        /** Topic domains already covered, so the interviewer moves to a new one. */
        val usedDomains: List<String> = emptyList(),
    )

    @Serializable
    data class Output(
        val utterance: String,
        val english: String = "",
        @SerialName("next_phase") val nextPhase: OpiPhase,
        val topic: String = "",
        val domain: String = "",
    )

    override val name = "opi_interviewer_turn"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.6
    override val maxTokens = 400
    override val schema = JsonSchema.Obj(
        listOf(
            "utterance" to JsonSchema.Str(maxLength = 300),
            "english" to JsonSchema.Str(maxLength = 300),
            "next_phase" to JsonSchema.Str(enum = OpiPhase.entries.map { it.wireName }),
            "topic" to JsonSchema.Str(maxLength = 80),
            "domain" to JsonSchema.Str(enum = DOMAINS),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> {
        val lang = languageName(input.language)
        val aim = if (input.phase == OpiPhase.PROBE) IlrLevel.lowerRange.getOrElse(IlrLevel.lowerRange.indexOf(input.workingLevel) + 1) { IlrLevel.L3 } else input.workingLevel
        // The system message and the transcript only grow between turns; everything that changes per turn comes last,
        // so the engine reuses its cached prompt prefix (a long interview stays fast on small machines).
        return listOf(
            system(
                "You are a friendly, professional interviewer running a practice oral proficiency interview in $lang. It is practice, not an official test.",
                "Register: ${input.registerNotes}",
                "Phases: warmup (easy personal questions), level_check (questions at the working level), probe (one level harder, to find where " +
                    "speech breaks down: ask to narrate, compare, support an opinion or hypothesize), roleplay (set up the situation and play your role), winddown (easy closing).",
                "Write utterance in natural $lang only (no English, no romanization). One question or prompt, short enough to say in one breath. Never correct the candidate.",
                "Never repeat or rephrase a question you already asked, and never repeat the candidate's words back as your question; build on what they said or move to a new topic.",
                "english is an English translation of your utterance. next_phase is the phase for the following turn: stay, or move forward when this phase has done its job; never go back.",
                "topic is a two-to-five-word English label; domain is one of: ${DOMAINS.joinToString()}.",
            ),
            user(
                (if (input.history.isEmpty()) "The interview is starting.\n" else "Interview so far:\n" + transcript(input.history, "Candidate", "Interviewer") + "\n\n") +
                    "Now: phase ${input.phase.wireName} (turn ${input.turnsInPhase + 1} of this phase). Working level hypothesis: ILR ${input.workingLevel.label} — " +
                    "${IlrSpeaking.describe(input.workingLevel)}. Aim this question at ILR ${aim.label}: ${IlrSpeaking.describe(aim)}." +
                    (input.rolePlay?.let { " Role-play to set up now: $it" } ?: "") +
                    (if (input.usedDomains.isNotEmpty()) " Topic areas already covered: ${input.usedDomains.joinToString()}; prefer a new one." else "") +
                    " Your next turn.",
            ),
        )
    }

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        ScriptCheck.requireLanguage("utterance", output.utterance, input.language),
        Validation.length("utterance", output.utterance, max = 300),
        input.history.firstOrNull { similar(it.text, output.utterance) }
            ?.let { if (it.speaker == Speaker.PARTNER) "utterance repeats an earlier question (\"${it.text.take(60)}\")" else "utterance repeats the candidate's words" },
        if (output.nextPhase < input.phase) "next_phase goes back to an earlier phase" else null,
        if (output.english.isNotBlank()) ScriptCheck.requireEnglish("english", output.english) else null,
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)

    companion object {
        /**
         * Near-duplicate questions: character-bigram Jaccard similarity ≥ 0.7 after folding. Works the same for spaced
         * and unspaced scripts: "¿Qué te parece hacer un viaje a España?" ≈ "¿Qué te parece si hacemos un viaje a
         * España?", but "お名前は何ですか" ≠ "お仕事は何ですか".
         */
        fun similar(a: String, b: String): Boolean {
            fun bigrams(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.windowed(2).toSet()
            val x = bigrams(a)
            val y = bigrams(b)
            if (x.isEmpty() || y.isEmpty()) return a.trim() == b.trim()
            return x.intersect(y).size.toDouble() / x.union(y).size >= 0.7
        }

        val DOMAINS = listOf("personal", "family", "work", "community", "current_events", "travel", "military", "hypothetical", "abstract", "roleplay")
    }
}

/**
 * `opi_rate`: an unofficial ILR speaking estimate from the candidate's side of an interview (BRIEF §6.2): per-factor
 * evidence (functions, context/content, accuracy, text type) with quotes, sustained and breakdown levels, the
 * estimate, and three concrete next steps.
 */
class OpiRate(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<OpiRate.Input, OpiRate.Output> {
    data class Input(val language: String, val history: List<Turn>, val registerNotes: String = "")

    @Serializable
    data class Factor(val level: String, val evidence: String, val quotes: List<String> = emptyList())

    @Serializable
    data class Output(
        val functions: Factor,
        @SerialName("context_content") val contextContent: Factor,
        val accuracy: Factor,
        @SerialName("text_type") val textType: Factor,
        @SerialName("sustained_level") val sustainedLevel: String,
        @SerialName("breakdown_level") val breakdownLevel: String? = null,
        val estimate: String,
        val rationale: String,
        @SerialName("next_steps") val nextSteps: List<String>,
    )

    override val name = "opi_rate"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val maxTokens = 1200

    private val factor = JsonSchema.Obj(
        listOf(
            "level" to JsonSchema.Str(enum = LEVELS),
            "evidence" to JsonSchema.Str(maxLength = 400),
            "quotes" to JsonSchema.Arr(JsonSchema.Str(maxLength = 200), maxItems = 3),
        ),
    )

    override val schema = JsonSchema.Obj(
        listOf(
            "functions" to factor,
            "context_content" to factor,
            "accuracy" to factor,
            "text_type" to factor,
            "sustained_level" to JsonSchema.Str(enum = LEVELS),
            "breakdown_level" to JsonSchema.Str(enum = LEVELS),
            "estimate" to JsonSchema.Str(enum = LEVELS),
            "rationale" to JsonSchema.Str(maxLength = 1200),
            "next_steps" to JsonSchema.Arr(JsonSchema.Str(maxLength = 240), maxItems = 3),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "You rate a practice speaking interview in ${languageName(input.language)} on the ILR scale (0+, 1, 1+, 2, 2+, 3). It is an unofficial practice estimate.",
            "ILR speaking levels: " + IlrSpeaking.descriptors.entries.joinToString("; ") { "${it.key}: ${it.value}" } + ".",
            if (input.registerNotes.isNotBlank()) "Register in this language: ${input.registerNotes}" else "",
            "Judge only the candidate's lines. Rate each factor: functions (tasks handled: describe, narrate, support opinion, hypothesize), " +
                "context/content (topics handled: personal, routine, concrete, abstract), accuracy (grammar, vocabulary, pronunciation as reflected in the transcript, register), " +
                "text type (words, sentences, connected sentences, paragraphs, extended discourse).",
            "Each factor gets an ILR level, one or two English sentences of evidence and up to 3 short verbatim quotes from the candidate.",
            "sustained_level is the highest level the candidate sustained across the interview; breakdown_level is the lowest level where speech broke down (omit or repeat sustained if none).",
            "estimate is the overall level: the sustained level, never the best single answer. rationale (English) explains it. next_steps: exactly three concrete, specific practice actions in English.",
        ),
        user(
            "Interview transcript (speech-to-text, so ignore missing punctuation):\n" + transcript(input.history, "Candidate", "Interviewer") +
                "\n\nRate the candidate now. The overall estimate is the sustained level. Write every evidence, rationale and next_steps " +
                "text in ENGLISH, even though the interview was in ${languageName(input.language)}; only the quotes stay in ${languageName(input.language)}.",
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> {
        val candidate = input.history.filter { it.speaker == Speaker.LEARNER }.joinToString(" ") { it.text }
        val factors = listOf(output.functions, output.contextContent, output.accuracy, output.textType)
        val sustained = IlrLevel.parse(output.sustainedLevel)
        val estimate = IlrLevel.parse(output.estimate)
        return issues(
            if (input.history.none { it.speaker == Speaker.LEARNER }) "there is nothing from the candidate to rate" else null,
            factors.firstOrNull { IlrLevel.parse(it.level) == null }?.let { "unknown factor level ${it.level}" },
            if (estimate == null) "unknown estimate ${output.estimate}" else null,
            // An estimate above the sustained level is capped by normalize (the ILR rating is the sustained level).
            ScriptCheck.requireEnglish("rationale", output.rationale),
            output.nextSteps.firstNotNullOfOrNull { ScriptCheck.requireEnglish("next step", it) },
            Validation.length("rationale", output.rationale, min = 20, max = 1200),
            if (output.nextSteps.size !in 2..4) "next_steps must have three items" else null,
            // Evidence quotes must come from the candidate: a rating whose quotes are mostly invented is rejected;
            // [normalize] drops the odd non-verbatim one.
            factors.flatMap { it.quotes }.filter { it.isNotBlank() }.let { qs ->
                val invented = qs.count { !looselyContains(candidate, it) }
                if (qs.isNotEmpty() && invented * 2 > qs.size) "most quotes are not in the candidate's speech (e.g. \"${qs.first { !looselyContains(candidate, it) }}\")" else null
            },
        )
    }

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)

    companion object {
        val LEVELS = IlrLevel.lowerRange.map { it.label }

        /** Keeps only verbatim quotes, caps the estimate at the sustained level, keeps three next steps. */
        fun normalize(input: Input, out: Output): Output {
            val candidate = input.history.filter { it.speaker == Speaker.LEARNER }.joinToString(" ") { it.text }
            fun clean(f: Factor) = f.copy(quotes = f.quotes.filter { it.isNotBlank() && looselyContains(candidate, it) })
            val sustained = IlrLevel.parse(out.sustainedLevel)
            val estimate = IlrLevel.parse(out.estimate)
            val capped = if (sustained != null && estimate != null && estimate > sustained) sustained.label else out.estimate
            return out.copy(functions = clean(out.functions), contextContent = clean(out.contextContent), accuracy = clean(out.accuracy),
                textType = clean(out.textType), estimate = capped, nextSteps = out.nextSteps.take(3))
        }

        private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        fun looselyContains(haystack: String, quote: String): Boolean = squash(haystack).contains(squash(quote).take(60))
    }
}

/**
 * `topic_turn` (BRIEF §6.3): the conversation partner's reply at the learner's rolling level, plus feedback on the
 * learner's last turn: a minimal correction with its changes, a natural rewrite, 1–3 vocabulary notes, and an updated
 * rolling level estimate.
 */
class TopicTurn(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<TopicTurn.Input, TopicTurn.Output> {
    data class Input(
        val language: String,
        val registerNotes: String,
        val topic: String,
        val domain: String,
        val rollingLevel: IlrLevel,
        val history: List<Turn>,
    )

    @Serializable
    data class Change(val from: String, val to: String, val why: String)

    @Serializable
    data class Vocab(val word: String, val meaning: String, val example: String = "")

    @Serializable
    data class Output(
        val reply: String,
        @SerialName("reply_english") val replyEnglish: String = "",
        val corrected: String,
        val changes: List<Change> = emptyList(),
        val rewrite: String,
        val vocabulary: List<Vocab> = emptyList(),
        @SerialName("turn_level") val turnLevel: String,
    )

    override val name = "topic_turn"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.5
    override val maxTokens = 900
    override val schema = JsonSchema.Obj(
        listOf(
            "reply" to JsonSchema.Str(maxLength = 400),
            "reply_english" to JsonSchema.Str(maxLength = 400),
            "corrected" to JsonSchema.Str(maxLength = 600),
            "changes" to JsonSchema.Arr(
                JsonSchema.Obj(listOf("from" to JsonSchema.Str(maxLength = 120), "to" to JsonSchema.Str(maxLength = 120), "why" to JsonSchema.Str(maxLength = 200))),
                maxItems = 5,
            ),
            "rewrite" to JsonSchema.Str(maxLength = 600),
            "vocabulary" to JsonSchema.Arr(
                JsonSchema.Obj(listOf("word" to JsonSchema.Str(maxLength = 60), "meaning" to JsonSchema.Str(maxLength = 120), "example" to JsonSchema.Str(maxLength = 200))),
                maxItems = 3,
            ),
            "turn_level" to JsonSchema.Str(enum = OpiRate.LEVELS),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> {
        val lang = languageName(input.language)
        return listOf(
            system(
                "You are a conversation partner and tutor for a learner of $lang. Topic: ${input.topic} (${input.domain.replace('_', ' ')}).",
                "Register: ${input.registerNotes}",
                "The learner speaks at about ILR ${input.rollingLevel.label} (${IlrSpeaking.describe(input.rollingLevel)}). Reply in natural $lang at that level, " +
                    "1–3 sentences, and keep the conversation going with a question. reply_english translates your reply.",
                "Then give feedback on the learner's LAST turn only: corrected = their sentence with the fewest changes that make it correct and appropriate " +
                    "(identical if nothing is wrong); changes lists each change (from, to, why in English); rewrite = how a native speaker would naturally say it; " +
                    "vocabulary = 1–3 useful words or phrases for this topic (word in $lang, meaning and example in English/$lang).",
                "turn_level = the ILR level the learner's last turn demonstrates.",
            ),
            user("Conversation so far:\n" + transcript(input.history, "Learner", "Partner")),
        )
    }

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> {
        val last = input.history.lastOrNull { it.speaker == Speaker.LEARNER }?.text.orEmpty()
        return issues(
            ScriptCheck.requireLanguage("reply", output.reply, input.language),
            ScriptCheck.requireLanguage("rewrite", output.rewrite, input.language),
            if (last.isNotBlank()) Validation.minimalEdit(last, output.corrected) else null,
            if (IlrLevel.parse(output.turnLevel) == null) "unknown turn_level" else null,
            if (output.vocabulary.size > 3) "at most three vocabulary notes" else null,
        )
    }

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)
}
