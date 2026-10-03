package app.mokuhyo.exam

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Authentic-format reading (BRIEF_PHASE8 N-08): a reading passage may carry `format` and structured `formatData`, which
 * the app renders as the real thing — a sign, a visitor-badge form, a phone message thread, a shift-log page, a
 * municipal notice, a schedule board. `body` still holds the plain text (validators, tap-to-define, the overlap gate).
 */
object Formats {
    const val SIGNAGE = "signage"
    const val BADGE_FORM = "badge_form"
    const val CHAT = "chat"
    const val SHIFT_LOG = "shift_log"
    const val MUNICIPAL_NOTICE = "municipal_notice"
    const val SCHEDULE_BOARD = "schedule_board"
    val ALL = listOf(SIGNAGE, BADGE_FORM, CHAT, SHIFT_LOG, MUNICIPAL_NOTICE, SCHEDULE_BOARD)

    /** The ILR bands each format is drafted at (0+–2). */
    val LEVELS = mapOf(
        SIGNAGE to listOf("0+", "1"), BADGE_FORM to listOf("0+", "1"), CHAT to listOf("1", "1+"), SHIFT_LOG to listOf("1+", "2"),
        MUNICIPAL_NOTICE to listOf("1+", "2"), SCHEDULE_BOARD to listOf("0+", "1"),
    )

    @Serializable
    data class Signage(val lines: List<String>, val kind: String = "info") // info | warning | prohibition | mandatory

    @Serializable
    data class Field(val label: String, val value: String = "")

    @Serializable
    data class BadgeForm(val title: String, val fields: List<Field>, val footer: String = "")

    @Serializable
    data class Message(val from: String, val text: String, val time: String = "", val me: Boolean = false)

    @Serializable
    data class Chat(val app: String = "sms", val title: String = "", val messages: List<Message>)

    @Serializable
    data class LogEntry(val time: String, val entry: String, val initials: String = "")

    @Serializable
    data class ShiftLog(val unit: String, val date: String, val entries: List<LogEntry>)

    @Serializable
    data class Notice(val issuer: String, val title: String, val paragraphs: List<String>, val date: String = "", val contact: String = "")

    @Serializable
    data class Board(val title: String, val columns: List<String>, val rows: List<List<String>>)

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Parsed
    data class SignageP(val v: Signage) : Parsed
    data class BadgeFormP(val v: BadgeForm) : Parsed
    data class ChatP(val v: Chat) : Parsed
    data class ShiftLogP(val v: ShiftLog) : Parsed
    data class NoticeP(val v: Notice) : Parsed
    data class BoardP(val v: Board) : Parsed

    /** The typed content of [data] for [format], or null when it doesn't parse (the plain body is shown instead). */
    fun parse(format: String?, data: JsonElement?): Parsed? = runCatching {
        if (format == null || data == null) return null
        when (format) {
            SIGNAGE -> SignageP(json.decodeFromJsonElement(Signage.serializer(), data))
            BADGE_FORM -> BadgeFormP(json.decodeFromJsonElement(BadgeForm.serializer(), data))
            CHAT -> ChatP(json.decodeFromJsonElement(Chat.serializer(), data))
            SHIFT_LOG -> ShiftLogP(json.decodeFromJsonElement(ShiftLog.serializer(), data))
            MUNICIPAL_NOTICE -> NoticeP(json.decodeFromJsonElement(Notice.serializer(), data))
            SCHEDULE_BOARD -> BoardP(json.decodeFromJsonElement(Board.serializer(), data))
            else -> null
        }
    }.getOrNull()

    /** The plain text a format shows (what `body` must contain). */
    fun text(p: Parsed): String = when (p) {
        is SignageP -> p.v.lines.joinToString("\n")
        is BadgeFormP -> (listOf(p.v.title) + p.v.fields.map { "${it.label}: ${it.value}" } + p.v.footer).filter { it.isNotBlank() }.joinToString("\n")
        is ChatP -> p.v.messages.joinToString("\n") { "${it.from}: ${it.text}" }
        is ShiftLogP -> (listOf("${p.v.unit} ${p.v.date}") + p.v.entries.map { "${it.time} ${it.entry}" }).joinToString("\n")
        is NoticeP -> (listOf(p.v.issuer, p.v.title) + p.v.paragraphs + listOf(p.v.date, p.v.contact)).filter { it.isNotBlank() }.joinToString("\n")
        is BoardP -> (listOf(p.v.title, p.v.columns.joinToString(" | ")) + p.v.rows.map { it.joinToString(" | ") }).joinToString("\n")
    }
}
