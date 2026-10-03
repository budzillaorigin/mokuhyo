package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiResult
import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Role
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.lang.Languages
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The exercise-week storyline (BRIEF_PHASE8 N-03): five sessions with the same host-nation counterpart, who remembers
 * earlier days. After each day a summary (facts, what happened, the learner's choice) is stored; the next day's
 * situation branches on that choice and the counterpart's prompt carries the remembered facts.
 */
object Storyline {
    @Serializable
    data class Day(
        val n: Int,
        val id: String,
        val title: String,
        /** Situation per branch key ("" = default); the key is the previous day's choice. */
        val situations: Map<String, String>,
        val learnerRole: String,
        val partnerRole: String,
        /** What the summary must classify the learner's handling of this day as. */
        val choices: List<String>,
        val tags: List<String>,
    )

    val DAYS = listOf(
        Day(1, "arrival-handover", "Day 1 — Arrival and handover", mapOf(
            "" to "You have just arrived at the host-nation air base for a week-long joint counter-drone exercise. Your counterpart meets you, " +
                "introduces the base defense operations center and hands over the current situation."),
            "Newly arrived US liaison", "Host-nation counterpart running the base defense operations center", listOf("rapport", "formal"),
            listOf("rank", "hospitality", "meeting")),
        Day(2, "drone-sighting", "Day 2 — Drone sighting", mapOf(
            "rapport" to "Mid-shift, a small drone is seen over the fuel point. Your counterpart, who welcomed you warmly yesterday, is on the radio with you.",
            "formal" to "Mid-shift, a small drone is seen over the fuel point. Your counterpart, still a little reserved after yesterday's formal meeting, is on the radio with you."),
            "US liaison on shift", "Host-nation counterpart on the radio", listOf("reported_promptly", "reported_late"), listOf("radio", "rank")),
        Day(3, "intrusion-qrf", "Day 3 — Intrusion and QRF", mapOf(
            "reported_promptly" to "Before dawn someone cuts the perimeter fence near the place yesterday's drone launched. Because you reported quickly yesterday, " +
                "your counterpart trusts your judgment. Coordinate the quick reaction force with them.",
            "reported_late" to "Before dawn someone cuts the perimeter fence near the place yesterday's drone launched. Yesterday's report came late and your " +
                "counterpart wants faster information today. Coordinate the quick reaction force with them."),
            "US liaison in the operations center", "Host-nation counterpart commanding the response", listOf("coordinated", "uncoordinated"), listOf("radio", "rank", "time")),
        Day(4, "local-national", "Day 4 — Incident with a local national", mapOf(
            "" to "A local farmer is stopped at the gate with a drone controller in his truck; he says he was looking for lost sheep. Your counterpart asks you to " +
                "help handle it with the gate guards."),
            "US liaison at the gate", "Host-nation counterpart and, through them, the farmer", listOf("deescalated", "escalated"), listOf("gate", "refusal", "face")),
        Day(5, "joint-aar", "Day 5 — Joint after-action review", mapOf(
            "" to "The exercise is over. Sit down with your counterpart for the joint after-action review of the week: what worked, what didn't, what each side will change."),
            "US liaison", "Host-nation counterpart", listOf("constructive", "defensive"), listOf("meeting", "face", "rank")),
    )

    fun day(n: Int): Day? = DAYS.firstOrNull { it.n == n }

    /** The situation for day [n] given the previous day's choice. */
    fun situation(n: Int, previousChoice: String?): String {
        val d = day(n) ?: error("no day $n")
        return d.situations[previousChoice.orEmpty()] ?: d.situations[""] ?: d.situations.values.first()
    }
}

/** `storyline_summary`: what the counterpart should remember from a day, and how the learner handled it. */
class StorylineSummary : PromptTask<StorylineSummary.Input, StorylineSummary.Output> {
    data class Input(val language: String, val day: Storyline.Day, val transcript: List<Turn>)

    @Serializable
    data class Output(val summary: String, val facts: List<String> = emptyList(), val choice: String)

    override val name = "storyline_summary"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val maxTokens = 500
    override val schema = JsonSchema.Obj(listOf(
        "summary" to JsonSchema.Str(maxLength = 400),
        "facts" to JsonSchema.Arr(JsonSchema.Str(maxLength = 120), maxItems = 6),
        "choice" to JsonSchema.Str(),
    ))

    override fun messages(input: Input): List<ChatMessage> = listOf(
        ChatMessage(Role.SYSTEM, "You keep the memory of a role-play counterpart in a week-long ${Languages.of(input.language)?.nameEnglish ?: input.language} " +
            "practice exercise. In English: summary (two sentences of what happened), facts (up to six short facts the counterpart would remember: names, " +
            "numbers, places, promises, problems), choice = exactly one of: ${input.day.choices.joinToString()} — how the learner handled ${input.day.title}."),
        ChatMessage(Role.USER, input.transcript.joinToString("\n") { (if (it.speaker == Speaker.LEARNER) "Learner: " else "Counterpart: ") + it.text }),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = listOfNotNull(
        if (output.choice !in input.day.choices) "choice must be one of ${input.day.choices}" else null,
        if (output.summary.isBlank()) "summary is empty" else null,
    )

    override fun fallback(input: Input): Output = Output("The learner completed ${input.day.title}.", emptyList(), input.day.choices.first())
}

/** A started storyline and the days played so far (derived from append-only rows). */
@Serializable
data class StorylineDayRecord(val day: Int, val conversationId: String, val summary: String, val facts: List<String>, val choice: String, val engine: String? = null)

data class StorylineState(val id: String, val lang: String, val personaId: String, val days: List<StorylineDayRecord>) {
    val nextDay: Int get() = (days.maxOfOrNull { it.day } ?: 0) + 1
    val completed: Boolean get() = nextDay > Storyline.DAYS.size
    val memory: List<String> get() = days.sortedBy { it.day }.flatMap { d -> listOf("Day ${d.day}: ${d.summary}") + d.facts }
    val lastChoice: String? get() = days.maxByOrNull { it.day }?.choice
}

/** `storyline` and `storyline_day` rows (BRIEF_PHASE8 N-03): append-only; the state is derived. */
class StorylineRepository(private val db: MokuhyoDatabase, private val clock: Clock = Clock.System) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun start(learnerId: String, lang: String, personaId: String): StorylineState {
        val id = kotlin.uuid.Uuid.random().toString()
        db.storylineQueries.insertStoryline(app.mokuhyo.db.Storyline(id, learnerId, lang, personaId, clock.now().toEpochMilliseconds(), null))
        return StorylineState(id, lang, personaId, emptyList())
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun addDay(storylineId: String, record: StorylineDayRecord) {
        db.storylineQueries.insertStorylineDay(app.mokuhyo.db.Storyline_day("$storylineId:${record.day}", storylineId, record.day.toLong(),
            json.encodeToString(StorylineDayRecord.serializer(), record), clock.now().toEpochMilliseconds(), null))
    }

    /** The learner's current storyline for [lang] (the newest not deleted), or null. */
    fun current(learnerId: String, lang: String): StorylineState? {
        val s = db.storylineQueries.storylines(learnerId, lang).executeAsList().firstOrNull() ?: return null
        val days = db.storylineQueries.storylineDays(s.id).executeAsList().map { json.decodeFromString(StorylineDayRecord.serializer(), it.json) }
        return StorylineState(s.id, s.lang, s.personaId, days.distinctBy { it.day })
    }
}

/** Runs one storyline day: the conversation (via [TopicSession] with role and memory), then the memory summary. */
object StorylineRunner {
    fun rolePlay(state: StorylineState, persona: PersonaContext?): Pair<Storyline.Day, RolePlayContext> {
        val d = Storyline.day(state.nextDay) ?: error("the storyline is complete")
        val partner = persona?.let { "${it.name}, ${it.rankTitle} — ${d.partnerRole}" } ?: d.partnerRole
        return d to RolePlayContext(Storyline.situation(d.n, state.lastChoice), partner, d.learnerRole)
    }

    suspend fun summarize(gateway: AiGateway, language: String, day: Storyline.Day, conversationId: String, transcript: List<Turn>): StorylineDayRecord =
        when (val r = gateway.run(StorylineSummary(), StorylineSummary.Input(language, day, transcript))) {
            is AiResult.Ok -> StorylineDayRecord(day.n, conversationId, r.value.summary, r.value.facts, r.value.choice, r.engine)
            is AiResult.Fallback -> StorylineDayRecord(day.n, conversationId, r.value.summary, r.value.facts, r.value.choice)
            is AiResult.Unavailable -> StorylineSummary().fallback(StorylineSummary.Input(language, day, transcript)).let {
                StorylineDayRecord(day.n, conversationId, it.summary, it.facts, it.choice)
            }
        }
}
