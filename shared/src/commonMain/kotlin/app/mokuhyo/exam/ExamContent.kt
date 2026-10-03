package app.mokuhyo.exam

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A language's exam pack (`packs/<lang>/exam.json`, built by tools/packs/build_packs.py): the blueprint, the
 * Reading and Listening banks, and the pre-rendered audio index. Parsed once per language.
 */
class ExamContent(
    val language: String,
    val blueprint: ExamBlueprint,
    val passages: Map<String, ExamPassage>,
    val items: List<ExamItem>,
) {
    fun pool(skill: Skill): List<ExamItem> = items.filter { it.exam == skill.exam }

    fun passagesFor(skill: Skill): List<ExamPassage> = passages.values.filter { it.exam == skill.exam }

    /** Items whose passage belongs to [track] (null = every item). */
    fun pool(skill: Skill, track: String?): List<ExamItem> =
        if (track == null) pool(skill) else pool(skill).filter { passages[it.passageId]?.track == track }

    /** Topic tracks present for [skill], with their item counts. */
    fun tracks(skill: Skill): Map<String, Int> =
        pool(skill).mapNotNull { passages[it.passageId]?.track }.groupingBy { it }.eachCount()

    /** Passages per ILR level for [skill] (what the gates and the Home screen count). */
    fun countsByLevel(skill: Skill): Map<String, Int> =
        passagesFor(skill).groupingBy { it.level }.eachCount()

    /** This pack plus locally generated passages and items (bank "local"); they stay labelled and separable. */
    fun withLocal(extraPassages: List<ExamPassage>, extraItems: List<ExamItem>): ExamContent =
        ExamContent(language, blueprint, passages + extraPassages.associateBy { it.id }, items + extraItems)

    @Serializable
    data class PackFile(
        val language: String,
        val version: Int = 1,
        val built: String = "",
        val blueprint: ExamBlueprint,
        val reading: ExamBankFile,
        val listening: ExamBankFile,
        /** passageId → audio file relative to the pack directory. */
        val audio: Map<String, String> = emptyMap(),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ExamContent = from(json.decodeFromString(PackFile.serializer(), text))

        fun from(pack: PackFile): ExamContent {
            val passages = (pack.reading.passages + pack.listening.passages).mapNotNull { p ->
                val exam = ExamBankValidator.examOf(p.exam) ?: return@mapNotNull null
                ExamPassage(
                    id = p.id, exam = exam, language = pack.language, level = p.level, textType = p.textType, title = p.title,
                    body = p.body, script = p.script, source = p.source, verified = p.verified, audio = pack.audio[p.id], track = p.track,
                )
            }.associateBy { it.id }
            val items = (pack.reading.items + pack.listening.items).mapNotNull { i ->
                val exam = ExamBankValidator.examOf(i.exam) ?: return@mapNotNull null
                ExamItem(
                    id = i.id, bank = if (i.exam == "DLPT_READING") pack.reading.bank else pack.listening.bank, exam = exam,
                    level = i.level, type = i.type, passageId = i.passageId, stem = i.stem, choices = i.choices,
                    answer = i.answer, explanation = i.explanation, script = i.script, refs = i.refs, source = i.source, verified = i.verified,
                )
            }
            return ExamContent(pack.language, pack.blueprint, passages, items)
        }
    }
}

/** Rebuilds a test form from saved progress (crash or quit mid-test), with the same choice order. Null if the pack changed. */
fun ExamContent.restore(progress: ExamProgress): ExamForm? {
    val byId = items.associateBy { it.id }
    val sections = progress.sections.map { s ->
        FormSection(s.title, s.minutes, s.items.map { ref ->
            val item = byId[ref.id] ?: return null
            val shown = if (ref.choices.size == item.choices.size && ref.answer >= 0) item.copy(choices = ref.choices, answer = ref.answer) else item
            FormItem(shown, ref.group, ref.typeTitle)
        }, s.listening)
    }
    val exam = ExamBankValidator.examOf(progress.exam) ?: return null
    return ExamForm(exam, language, progress.level, ExamMode.valueOf(progress.mode), sections,
        progress.passageIds.mapNotNull { id -> passages[id]?.let { id to it } }.toMap(), emptyList())
}
