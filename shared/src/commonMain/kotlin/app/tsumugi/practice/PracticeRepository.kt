package app.tsumugi.practice

import app.tsumugi.practice.db.Dialogue as DialogueRow
import app.tsumugi.practice.db.Minimal_pair
import app.tsumugi.practice.db.PracticeDatabase
import app.tsumugi.practice.db.Scenario as ScenarioRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Read access to the speaking & listening practice pack (content/packs/practice.sqlite, built by
 * tools/packs/build_practice.py). [level] arguments are JLPT levels (5 = N5); null means every level.
 */
class PracticeRepository(private val db: PracticeDatabase) {

    private val q get() = db.practiceQueries
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(Exception::class)
    suspend fun scenarios(level: Int? = null): List<Scenario> = io {
        (if (level == null) q.allScenarios() else q.scenariosAtLevel(level.toLong())).executeAsList().map { it.toModel() }
    }

    @Throws(Exception::class)
    suspend fun scenario(id: String): Scenario? = io { q.scenarioById(id).executeAsOneOrNull()?.toModel() }

    @Throws(Exception::class)
    suspend fun scriptedTurns(scenarioId: String): List<ScriptedTurn> = io {
        q.scriptedTurns(scenarioId).executeAsList().map { ScriptedTurn(it.partner_ja, it.partner_en, it.intent, it.sample_answer) }
    }

    /** The OPI practice bank for an ILR level ("0+", "1", "1+", "2", "2+", "3"); empty if the level is unknown. */
    @Throws(Exception::class)
    suspend fun opiBank(ilr: String): OpiBank = io {
        val questions = q.opiQuestions(ilr).executeAsList().map {
            OpiQuestion(OpiPhase.valueOf(it.phase), it.prompt_ja, it.prompt_en, it.note, it.source)
        }
        OpiBank(ilr, questions, q.opiChecklist(ilr).executeAsList())
    }

    @Throws(Exception::class)
    suspend fun dialogues(level: Int? = null): List<DialogueSummary> = io {
        (if (level == null) q.allDialogues() else q.dialoguesAtLevel(level.toLong())).executeAsList()
            .map { DialogueSummary(it.id, it.title, it.jlpt.toInt(), it.topic, it.source) }
    }

    @Throws(Exception::class)
    suspend fun dialogue(id: String): Dialogue? = io {
        q.dialogueById(id).executeAsOneOrNull()?.let { toModel(it) }
    }

    /** Most common pairs first. */
    @Throws(Exception::class)
    suspend fun minimalPairs(category: MinimalPairCategory? = null, limit: Int = 50): List<MinimalPair> = io {
        val rows = if (category == null) q.minimalPairs(limit.toLong()) else q.minimalPairsIn(category.name, limit.toLong())
        rows.executeAsList().map { it.toModel() }
    }

    @Throws(Exception::class)
    suspend fun packVersion(): String? = io { q.metaValue("pack_version").executeAsOneOrNull() }

    private fun ScenarioRow.toModel() = Scenario(
        id = id, titleEn = title_en, titleJa = title_ja, jlpt = jlpt.toInt(), ilr = ilr, category = category,
        setting = setting, learnerRole = learner_role, partnerRole = partner_role,
        register = Register.valueOf(register.uppercase()),
        goals = json.decodeFromString(goals), vocabulary = json.decodeFromString(vocabulary),
        phrases = json.decodeFromString(phrases), systemPrompt = system_prompt, source = source,
    )

    private fun toModel(row: DialogueRow): Dialogue {
        val lines = q.dialogueLines(row.id).executeAsList().map {
            DialogueLine(it.speaker, it.ja, it.en, json.decodeFromString(it.gaps), json.decodeFromString(it.chunks))
        }
        val questions = q.dialogueQuestions(row.id).executeAsList().map {
            ComprehensionQuestion(it.question_en, json.decodeFromString(it.choices), it.answer.toInt())
        }
        return Dialogue(
            row.id, row.title, row.jlpt.toInt(), row.topic, json.decodeFromString(row.speakers), lines, questions, row.source,
        )
    }

    private fun Minimal_pair.toModel() = MinimalPair(
        id = id,
        category = MinimalPairCategory.valueOf(category),
        a = PairWord(entry_a, text_a, reading_a, accent_a?.toInt(), gloss_a),
        b = PairWord(entry_b, text_b, reading_b, accent_b?.toInt(), gloss_b),
    )

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
