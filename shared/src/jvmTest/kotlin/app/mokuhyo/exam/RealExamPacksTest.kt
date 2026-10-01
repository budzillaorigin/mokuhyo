package app.mokuhyo.exam

import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.history.HistoryRepository
import app.mokuhyo.lang.Languages
import app.mokuhyo.testing.TestClock
import app.mokuhyo.testing.repoFile
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * BRIEF §11.1 gate_exam over the shipped packs (content/packs/<lang>/exam.json): for every language a full Reading
 * and a full Listening form assemble with every ILR band present and no passage repeated; a timed form in Japanese
 * and Spanish is taken end to end and its estimate recorded. Missing packs skip, unless MOKUHYO_REQUIRE_PACKS=1.
 */
class RealExamPacksTest {
    private val requirePacks = System.getenv("MOKUHYO_REQUIRE_PACKS") == "1"
    private val packsDir: File? = runCatching { repoFile("content/packs") }.getOrNull()

    private fun pack(lang: String): ExamContent? {
        val f = packsDir?.let { File(it, "$lang/exam.json") }?.takeIf { it.isFile }
        if (f == null) {
            if (requirePacks) throw AssertionError("content/packs/$lang/exam.json missing")
            println("SKIP $lang exam pack not built")
            return null
        }
        return ExamContent.parse(f.readText())
    }

    @Test
    fun fullFormsAssembleAtEveryBandWithoutRepeats() {
        Languages.all.forEach { l ->
            val content = pack(l.code) ?: return@forEach
            Skill.entries.forEach { skill ->
                val section = content.blueprint.section(skill)
                val form = ExamAssembler.test(content.blueprint, skill, FormLength.FULL, content.pool(skill), content.passages, emptySet(), Random(7))
                val levels = form.items.map { it.item.level }.distinct()
                assertEquals(section.levels, levels, "${l.code} ${skill.id}: levels on the form")
                assertEquals(form.items.size, form.items.map { it.item.id }.distinct().size, "${l.code} ${skill.id}: repeated item")
                val groups = form.items.mapNotNull { it.item.passageId }
                assertEquals(groups.distinct(), groups.distinct().also { ids -> assertTrue(ids.all { it in form.passages }) })
                // No repeats across consecutive forms either, while the pool lasts.
                val next = ExamAssembler.test(content.blueprint, skill, FormLength.SLICE_30, content.pool(skill), content.passages, form.passageIds.toSet(), Random(8))
                assertTrue(next.passageIds.none { it in form.passageIds }, "${l.code} ${skill.id}: repeat across forms")
                if (skill == Skill.LISTENING) assertTrue(form.passages.values.all { it.script.isNotEmpty() }, "${l.code}: listening passage without a script")
                println("${l.code} ${skill.id}: full form ${form.items.size} items, ${form.passageIds.size} passages, shortfalls ${form.shortfalls}")
            }
        }
    }

    @Test
    fun timedFormsInJapaneseAndSpanishRecordEstimates() {
        listOf("ja", "es").forEach { lang ->
            val content = pack(lang) ?: return@forEach
            val (_, db) = DatabaseFactory.inMemory()
            val clock = TestClock()
            val history = HistoryRepository(db, clock)
            Skill.entries.forEach { skill ->
                val form = ExamAssembler.test(content.blueprint, skill, FormLength.SLICE_60, content.pool(skill), content.passages, emptySet(), Random(3))
                val session = ExamSession(form, content.blueprint.section(skill).play, clock)
                session.begin()
                form.items.forEachIndexed { i, f ->
                    session.goTo(i)
                    f.item.passageId?.let { if (skill == Skill.LISTENING && session.canPlayAudio(it)) session.audioPlayed(it) }
                    clock.advance(20.seconds)
                    // A simulated ILR-2 learner: right up to level 2, mostly wrong above.
                    val right = IlrLevel.parse(f.item.level)!! <= IlrLevel.L2 || i % 4 == 0
                    session.choose(if (right) f.item.answer else (f.item.answer + 1) % f.item.choices.size)
                }
                history.saveAttempt("learner", skill, session.submit())
            }
            val latest = history.latest("learner", lang)
            assertEquals(setOf("READING", "LISTENING"), latest.keys, "$lang: estimates recorded")
            latest.values.forEach { p -> assertTrue(p.value in listOf("1+", "2", "2+"), "$lang ${p.modality}: estimate ${p.value}") }
            println("$lang: recorded ${latest.mapValues { it.value.value }}")
        }
    }
}
