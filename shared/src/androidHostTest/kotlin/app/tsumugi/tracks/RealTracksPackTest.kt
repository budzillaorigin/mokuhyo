package app.tsumugi.tracks

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.tracks.db.TracksDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs against the real content/packs/tracks.sqlite when built (tools/packs/build_tracks.py); skipped otherwise.
 * Every row must load with the app's models, every drill must parse, every model answer must pass its own check, and
 * the rule-based keigo engine must agree with the authored answers (it prints the drills where it doesn't).
 */
class RealTracksPackTest {

    private fun pack(name: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "content/packs/$name") }
        .firstOrNull { it.exists() }

    @Test
    fun tracksPackLoadsAndEveryDrillChecksItsOwnAnswer() = runTest {
        val file = pack("tracks.sqlite") ?: return@runTest println("RealTracksPackTest skipped: no tracks pack")
        val repo = TrackRepository(TracksDatabase(JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("open_mode", "1") })))
        val tracks = repo.tracks()
        assertTrue(tracks.isNotEmpty())
        var keigo = 0
        var ruled = 0
        val disagreements = mutableListOf<String>()
        for (t in tracks) {
            val words = repo.words(t.id)
            assertEquals(t.count("words"), words.size, "${t.id} words")
            assertEquals(t.count("kanji"), repo.kanji(t.id).size, "${t.id} kanji")
            repo.scenarios(t.id).forEach { s -> assertTrue(repo.scriptedTurns(s.id).size >= 3, s.id) }
            repo.dialogues(t.id).forEach { d -> assertNotNull(repo.dialogue(d.id), d.id).also { assertTrue(it.lines.size >= 4) } }
            val drills = repo.drills(t.id)
            assertEquals(t.count("drills"), drills.size, "${t.id}: every drill parses")
            for (d in drills) {
                when (d) {
                    is KeigoDrill -> {
                        keigo++
                        d.answers.forEach { a -> assertTrue(d.check(a).correct, "${d.id}: $a") }
                        val rules = d.ruleForms
                        if (rules.isNotEmpty()) {
                            ruled++
                            if (d.answers.none { a -> AnswerText.normalize(a) in rules.map(AnswerText::normalize) }) {
                                disagreements += "${d.id} ${d.plain} ${d.target.code}/${d.form.code}: answers ${d.answers} rules ${rules.take(6)}"
                            }
                        }
                    }
                    is EmailDrill -> d.blanks.forEachIndexed { i, b -> assertTrue(d.check(i, b.answers.first()).correct, d.id) }
                    is FillInDrill -> assertTrue(d.check(d.answers.first()).correct, d.id)
                    is SynonymDrill -> assertTrue(d.check(d.answer).correct, d.id)
                    is MeaningDrill -> assertTrue(d.check(d.answer).correct, d.id)
                    is UsageDrill -> assertTrue(d.check(d.correct).correct, d.id)
                    is PerformDrill -> {
                        val session = PerformanceSession(d)
                        repeat(FadeLevel.entries.size) {
                            d.learnerLines.forEach { session.deliver(it, d.lines[it].ja) }
                            session.nextRound()
                        }
                        assertTrue(session.finished, d.id)
                    }
                }
            }
            repo.situations(t.id).forEach { assertTrue(it.canDo.isNotEmpty()) }
            repo.tasks(t.id)
            repo.readings(t.id).forEach { r -> assertTrue(r.questions.all { it.answer in it.choices.indices }, r.id) }
            repo.links(t.id).forEach { assertTrue(it.url.startsWith("https://")) }
            println("RealTracksPackTest ${t.id}: ${t.counts}")
        }
        println("RealTracksPackTest keigo: $keigo drills, $ruled with rule forms, ${disagreements.size} where no authored answer is a rule form")
        disagreements.forEach { println("  $it") }
        if (ruled > 0) assertTrue(disagreements.size * 10 <= ruled, "the keigo rules disagree with more than 10% of the drills they cover")
    }
}
