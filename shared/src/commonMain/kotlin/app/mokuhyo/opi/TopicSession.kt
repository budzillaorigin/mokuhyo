package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiResult
import app.mokuhyo.exam.IlrLevel
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/** One exchange of a topic conversation with the feedback on the learner's turn. */
@Serializable
data class TopicExchange(
    val learner: String,
    val reply: String,
    val replyEnglish: String = "",
    val corrected: String? = null,
    val changes: List<TopicTurn.Change> = emptyList(),
    val rewrite: String? = null,
    val vocabulary: List<TopicTurn.Vocab> = emptyList(),
    val turnLevel: String? = null,
    val engine: String? = null,
    /** Cultural/pragmatic flags (BRIEF_PHASE8 C-06); feedback only, never part of the level. */
    val pragmatics: List<PragmaticFlag> = emptyList(),
)

/**
 * Topic conversation (BRIEF §6.3): the learner talks about a topic; after each turn the partner replies at the
 * learner's rolling level and corrections, a natural rewrite and vocabulary notes appear. The rolling level is the
 * median of the last five turn levels (stable against one long or short answer). Recurring corrections are reported
 * for the review queue.
 */
class TopicSession(
    val language: String,
    private val profile: OpiProfile,
    val topic: Topic,
    private val gateway: AiGateway,
    startLevel: IlrLevel = IlrLevel.L1,
    private val clock: Clock = Clock.System,
    /** Set for scenario role-plays (BRIEF_PHASE8 C-03): the partner plays the scenario's role. */
    val rolePlay: RolePlayContext? = null,
    /** The partner persona (BRIEF_PHASE8 C-08). */
    val persona: PersonaContext? = null,
    /** Pragmatics rules the learner's turns are checked against (C-06/C-07). */
    val culturalNotes: List<String> = emptyList(),
    /** Live and After action run the same correction pass; Off asks only for the partner's reply (BRIEF_PHASE8 C-11). */
    val mode: CorrectionsMode = CorrectionsMode.LIVE,
) {
    val startedAt: Instant = clock.now()
    private val history = mutableListOf(Turn(Speaker.PARTNER, topic.opener))
    private val levels = mutableListOf<IlrLevel>()
    val exchanges = mutableListOf<TopicExchange>()

    var rollingLevel: IlrLevel = startLevel
        private set

    /** Rolling level after each turn (stored with the conversation). */
    val levelTrack = mutableListOf<String>()

    val transcript: List<Turn> get() = history.toList()

    /** Sends the learner's turn; returns the exchange, or null when no model is available (topic mode needs one). */
    suspend fun say(text: String): TopicExchange? {
        val said = text.trim()
        if (said.isEmpty()) return null
        history += Turn(Speaker.LEARNER, said)
        val input = TopicTurn.Input(language, profile.registerNotes, topic.title, topic.domain, rollingLevel, history.toList(), rolePlay, persona, culturalNotes)
        if (!mode.records) {
            val reply = when (val r = gateway.run(PartnerReply(), input)) {
                is AiResult.Ok -> TopicExchange(said, r.value.reply, r.value.replyEnglish, engine = r.engine)
                else -> {
                    history.removeAt(history.lastIndex)
                    return null
                }
            }
            history += Turn(Speaker.PARTNER, reply.reply)
            exchanges += reply
            return reply
        }
        val exchange = when (val r = gateway.run(TopicTurn(), input)) {
            is AiResult.Ok -> r.value.let { o ->
                TopicExchange(said, o.reply, o.replyEnglish, o.corrected.takeIf { it.trim() != said }, o.changes, o.rewrite, o.vocabulary, o.turnLevel, r.engine,
                    o.pragmatics)
            }
            else -> {
                history.removeAt(history.lastIndex)
                return null
            }
        }
        history += Turn(Speaker.PARTNER, exchange.reply)
        exchange.turnLevel?.let(IlrLevel::parse)?.let { levels += it }
        rollingLevel = levels.takeLast(5).sorted().let { it[it.size / 2] }
        levelTrack += rollingLevel.label
        exchanges += exchange
        return exchange
    }

    /** "Say it again": drop the last exchange so the learner can redo it. */
    fun redoLast(): Boolean {
        if (exchanges.isEmpty()) return false
        exchanges.removeAt(exchanges.lastIndex)
        repeat(2) { if (history.size > 1) history.removeAt(history.lastIndex) }
        if (levels.isNotEmpty()) levels.removeAt(levels.lastIndex)
        if (levelTrack.isNotEmpty()) levelTrack.removeAt(levelTrack.lastIndex)
        rollingLevel = levels.takeLast(5).sorted().let { if (it.isEmpty()) rollingLevel else it[it.size / 2] }
        return true
    }

    /** What `conversation_turn_feedback` stores for this session (empty in Off mode). Turn index = the learner line's transcript index. */
    val records: List<TurnFeedbackRecord>
        get() = if (!mode.records) emptyList() else exchanges.mapIndexed { i, ex -> TurnFeedbackRecord.of(2 * i + 1, ex) }

    /** Corrections that came up at least twice in this conversation (fed to the review queue as ERROR items). */
    fun recurringErrors(): List<TopicTurn.Change> =
        exchanges.flatMap { it.changes }.groupBy { it.from.lowercase().trim() to it.to.lowercase().trim() }.values.filter { it.size >= 2 }.map { it.first() }
}
