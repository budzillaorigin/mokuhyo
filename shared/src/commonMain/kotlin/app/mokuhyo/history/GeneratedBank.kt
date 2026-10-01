package app.mokuhyo.history

import app.mokuhyo.db.Generated_passage
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.exam.ExamItem
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ExamKind
import app.mokuhyo.exam.ScriptLine
import app.mokuhyo.exam.Skill
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The "Generated on this computer" bank (BRIEF §5.4): passages the local model drafted on request, kept apart from the
 * shipped packs, labelled in every view, excluded from shipped-bank statistics and ILR estimates, deletable in bulk
 * (tombstones). Travels in the backup bundle like any other learner data.
 */
class GeneratedBank(private val db: MokuhyoDatabase, private val clock: Clock = Clock.System) {
    @Serializable
    private data class StoredItem(val id: String, val type: String, val stem: String, val choices: List<String>, val answer: Int, val explanation: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(learnerId: String, passage: ExamPassage, items: List<ExamItem>, engine: String) {
        db.generatedQueries.insertGenerated(
            Generated_passage(
                id = passage.id, learnerId = learnerId, lang = passage.language, skill = Skill.of(passage.exam).name, level = passage.level,
                textType = passage.textType, title = passage.title, body = passage.body,
                scriptJson = json.encodeToString(ListSerializer(ScriptLine.serializer()), passage.script),
                itemsJson = json.encodeToString(ListSerializer(StoredItem.serializer()), items.map { StoredItem(it.id, it.type, it.stem, it.choices, it.answer, it.explanation) }),
                audioPath = null, engine = engine, createdAt = clock.now().toEpochMilliseconds(), deleted = null,
            ),
        )
    }

    fun load(learnerId: String, lang: String, skill: Skill): Pair<List<ExamPassage>, List<ExamItem>> {
        val rows = db.generatedQueries.generated(learnerId, lang, skill.name).executeAsList()
        val exam: ExamKind = skill.exam
        val passages = rows.map { r ->
            ExamPassage(r.id, exam, r.lang, r.level, r.textType, r.title, r.body,
                json.decodeFromString(ListSerializer(ScriptLine.serializer()), r.scriptJson), "llm", false, bank = "local")
        }
        val items = rows.flatMap { r ->
            json.decodeFromString(ListSerializer(StoredItem.serializer()), r.itemsJson).map { i ->
                ExamItem(i.id, "local", exam, r.level, i.type, r.id, i.stem, i.choices, i.answer, i.explanation, emptyList(), emptyList(), "llm", false)
            }
        }
        return passages to items
    }

    /** Generated conversation topics (skill SPEAKING_TOPIC: textType = domain, body = opener, level = min level). */
    fun saveTopic(learnerId: String, lang: String, topic: app.mokuhyo.opi.Topic, engine: String) {
        db.generatedQueries.insertGenerated(
            Generated_passage(topic.id, learnerId, lang, "SPEAKING_TOPIC", topic.minLevel, topic.domain, topic.title, topic.opener, "[]", "[]", null, engine,
                clock.now().toEpochMilliseconds(), null),
        )
    }

    fun topics(learnerId: String, lang: String): List<app.mokuhyo.opi.Topic> =
        db.generatedQueries.generated(learnerId, lang, "SPEAKING_TOPIC").executeAsList().map { r ->
            app.mokuhyo.opi.Topic(r.id, r.textType, r.title, r.body, r.level, "llm", false)
        }

    fun count(learnerId: String, lang: String): Int = Skill.entries.sumOf { db.generatedQueries.generated(learnerId, lang, it.name).executeAsList().size }

    /** Removes every generated passage for the language (tombstones; the rows stay for history and backups). */
    fun deleteAll(learnerId: String, lang: String) = db.generatedQueries.tombstoneGenerated(clock.now().toEpochMilliseconds(), learnerId, lang)
}
