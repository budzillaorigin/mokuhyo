package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.FakeModel
import app.mokuhyo.backup.Backup
import app.mokuhyo.backup.Bundle
import app.mokuhyo.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BRIEF_PHASE8 N-03 gate: a scripted five-session run completes; the counterpart's prompt recalls earlier facts (golden);
 * branches follow the learner's choices; the storyline state round-trips through the `.mokuhyo` bundle.
 */
class StorylineTest {
    private val persona = PersonaContext("Capitán Ruiz", "mi capitán", "Peer officer", "Fuerza Aérea Mexicana", "usted; rank first", 3, 4)
    private val turn = """{"reply":"Entendido. ¿Algo más?","reply_english":"Understood. Anything else?","corrected":"Buenos días, mi capitán.","changes":[],
        "rewrite":"Buenos días, mi capitán.","vocabulary":[],"turn_level":"1+","pragmatics":[]}"""

    private fun summary(day: Int, choice: String) = """{"summary":"Day $day went fine.","facts":["Fact from day $day: the learner is called Sgt. Lee"],"choice":"$choice"}"""

    private fun machine(name: String): Triple<File, StorylineRepository, Backup> {
        val dir = Files.createTempDirectory("mokuhyo-story-$name").toFile()
        val (_, db) = DatabaseFactory.open(File(dir, "db/mokuhyo.sqlite"))
        db.userQueries.insertLearner(name, name, 0)
        return Triple(dir, StorylineRepository(db), Backup(File(dir, "db/mokuhyo.sqlite"), db, dir, name, "test"))
    }

    @Test
    fun fiveSessionsCompleteWithMemoryAndBranches() = runTest {
        val (_, repo, _) = machine("A")
        var state = repo.start("A", "es", "es-peer_officer")
        val choices = listOf("rapport", "reported_late", "coordinated", "deescalated", "constructive")
        val prompts = mutableListOf<String>()
        for (n in 1..5) {
            val (day, role) = StorylineRunner.rolePlay(state, persona)
            assertEquals(n, day.n)
            if (n == 3) assertTrue("Yesterday's report came late" in role.situation, "day 3 branches on day 2's choice")
            if (n == 2) assertTrue("welcomed you warmly" in role.situation, "day 2 branches on day 1's choice")
            val model = FakeModel(turn)
            val session = TopicSession("es", OpiProfile("es", "usted"), Topic(day.id, "military_operations", day.title, ""), AiGateway({ model }),
                rolePlay = role, persona = persona, memory = state.memory)
            session.say("Buenos días, mi capitán.")
            prompts += model.requests.single().messages.first().content
            val rec = StorylineRunner.summarize(AiGateway({ FakeModel(summary(n, choices[n - 1])) }), "es", day, "conv-$n", session.transcript)
            repo.addDay(state.id, rec)
            state = assertNotNull(repo.current("A", "es"))
        }
        assertTrue(state.completed)
        assertEquals(choices, state.days.map { it.choice })
        // Recall golden: on day 5 the counterpart's prompt carries the facts of days 1–4, and day 1 carries none.
        assertTrue("What you remember from earlier days" !in prompts[0])
        (1..4).forEach { d -> assertTrue("Fact from day $d: the learner is called Sgt. Lee" in prompts[4], "day 5 recalls day $d") }
        assertTrue("Day 4: Day 4 went fine." in prompts[4])
    }

    @Test
    fun aBadSummaryFallsBackAndStateRoundTripsThroughTheBundle() = runTest {
        val (dirA, repoA, backupA) = machine("A")
        val s = repoA.start("A", "es", "es-senior_counterpart")
        val day1 = Storyline.day(1)!!
        val rec = StorylineRunner.summarize(AiGateway({ FakeModel("""{"summary":"x","facts":[],"choice":"nonsense"}""", """{"summary":"x","facts":[],"choice":"nonsense"}""") }),
            "es", day1, "c1", listOf(Turn(Speaker.LEARNER, "Hola")))
        assertEquals("rapport", rec.choice, "an invalid choice falls back to the default branch")
        repoA.addDay(s.id, rec)
        val bundle = File(dirA, "s.mokuhyo")
        backupA.export(bundle, listOf("es"), Bundle.PacksList(emptyList(), emptyList(), emptyList()))
        val (_, repoB, backupB) = machine("B")
        backupB.importBundle(bundle)
        val got = assertNotNull(repoB.current("B", "es"))
        assertEquals(2, got.nextDay)
        assertEquals("es-senior_counterpart", got.personaId)
        assertEquals(rec, got.days.single())
    }
}
