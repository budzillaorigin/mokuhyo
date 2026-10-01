package app.mokuhyo.report

import app.mokuhyo.history.ConversationRepository
import app.mokuhyo.history.HistoryRepository
import app.mokuhyo.lang.Languages
import app.mokuhyo.srs.ReviewService
import app.mokuhyo.db.MokuhyoDatabase
import java.time.Instant
import kotlin.time.Instant as KInstant

/** Assembles [ReportData] for a learner from the database (BRIEF §8.3 contents). */
class ReportBuilder(
    private val db: MokuhyoDatabase,
    private val history: HistoryRepository,
    private val conversations: ConversationRepository,
    private val reviews: ReviewService,
) {
    fun build(learnerId: String, learnerName: String, languages: List<String>, from: Instant?, to: Instant?, includeTranscripts: Boolean, appVersion: String): ReportData {
        fun inRange(ms: Long) = (from == null || ms >= from.toEpochMilli()) && (to == null || ms <= to.toEpochMilli())
        val sections = languages.mapNotNull { Languages.of(it) }.map { l ->
            val estimates = history.estimates(learnerId, l.code).filter { inRange(it.at) }
            val attempts = history.attempts(learnerId, l.code).filter { inRange(it.startedAt) }.sortedBy { it.startedAt }
            val attemptRows = attempts.map { a ->
                val answers = history.answers(a)
                ReportData.AttemptRow(Instant.ofEpochMilli(a.startedAt), a.modality, a.mode, answers.count { it.correct }, answers.size, a.ilrEstimate, a.provisional == 1L)
            }
            // Weak areas: text types and question types under 60 % across tests and practice in the period.
            val byText = HashMap<String, IntArray>()
            val byType = HashMap<String, IntArray>()
            attempts.forEach { a ->
                val form = history.form(a)
                val answers = history.answers(a).associateBy { it.itemId }
                history.scoring(a)?.byType?.forEach { t -> byText.getOrPut(t.key) { IntArray(2) }.let { it[0] += t.correct; it[1] += t.total } }
                form.items.forEach { i ->
                    val right = answers[i.id]?.correct == true
                    byType.getOrPut(i.type) { IntArray(2) }.let { it[0] += if (right) 1 else 0; it[1] += 1 }
                }
            }
            fun weak(m: Map<String, IntArray>) = m.filter { it.value[1] >= 3 && it.value[0] * 100 / it.value[1] < 60 }
                .map { it.key to it.value[0] * 100 / it.value[1] }.sortedBy { it.second }
            val convs = conversations.list(learnerId, l.code).filter { inRange(it.startedAt) }.sortedBy { it.startedAt }
            val errors = db.srsQueries.itemsAll().executeAsList().filter { it.learnerId == learnerId && it.lang == l.code && it.kind == "ERROR" && it.deleted == null }
                .map { it.front to it.back.substringBefore('\n') }
            ReportData.LanguageSection(
                code = l.code, name = "${l.nameEnglish} ${l.nameNative}",
                trends = listOf("READING", "LISTENING", "SPEAKING").associateWith { m ->
                    estimates.filter { it.modality == m }.map { ReportData.Point(Instant.ofEpochMilli(it.at), it.value, it.provisional) }
                },
                attempts = attemptRows,
                conversations = convs.map { c ->
                    val r = conversations.rating(c)
                    ReportData.ConversationRow(Instant.ofEpochMilli(c.startedAt), c.kind, c.topic, r?.estimate, r?.nextSteps.orEmpty(), r?.engine != null)
                },
                weakTextTypes = weak(byText), weakQuestionTypes = weak(byType), recurringErrors = errors,
                reviewItems = reviews.total(learnerId, l.code), reviewsDue = reviews.dueCount(learnerId, l.code),
                reviewsDone = reviews.reviewsSince(learnerId, l.code, KInstant.fromEpochMilliseconds(from?.toEpochMilli() ?: 0)),
                transcripts = if (!includeTranscripts) emptyList() else convs.map { c ->
                    val stored = conversations.stored(c)
                    ReportData.Transcript(Instant.ofEpochMilli(c.startedAt), c.topic ?: c.kind.lowercase().replace('_', ' '),
                        stored.transcript.map { t -> (if (t.speaker.name == "LEARNER") "You" else "Interviewer") to t.text })
                },
            )
        }
        return ReportData(learnerName, Instant.now(), from, to, appVersion, sections, includeTranscripts)
    }
}
