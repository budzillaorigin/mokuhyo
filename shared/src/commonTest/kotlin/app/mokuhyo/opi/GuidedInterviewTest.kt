package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.speech.FallbackKind
import app.mokuhyo.speech.TimingReport
import app.mokuhyo.speech.TurnTimeline
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_INTERVIEW_SPEED N5: guided turns come from the bank with their prerendered prompt, and the rate is recorded. */
class GuidedInterviewTest {
    private val phases = OpiPhase.entries.filter { it != OpiPhase.ROLEPLAY }
    private val bank = phases.flatMap { p -> IlrLevel.lowerRange.flatMap { l -> (1..4).map { i ->
        BankQuestion("es-q-${p.wireName}-${l.label}-$i", p.wireName, l.label, "Pregunta ${p.wireName} ${l.label} número $i", "Question $i", "personal")
    } } }
    private val rolePlays = listOf(RolePlay("es-rp-1-01", "1", "Hotel", "Receptionist", "Bienvenido al hotel. ¿En qué puedo ayudarle?", "Welcome."))
    private val pack = OpiPack("es", OpiProfile("es", "usted"), bank, rolePlays)

    @Test
    fun withNoModelTheWholeSessionIsGuidedPhaseByPhaseWithItsPrompts() = runTest {
        val s = OpiSession("es", pack.profile, bank, rolePlays, AiGateway({ null }), { it.split(' ').size }, IlrLevel.L1)
        val timeline = TurnTimeline("g", "OPI", "es")
        val ids = PromptAudioIndex.build(pack).prompts.map { it.id }.toSet()
        var n = 0
        while (true) {
            timeline.beginTurn(cold = n == 0)
            val line = s.next() ?: break
            assertNull(line.engine)
            val id = assertNotNull(line.bankId, "every guided line names its prerendered prompt")
            assertTrue(id in ids)
            val expectedPhase = if (line.phase == OpiPhase.ROLEPLAY) "roleplay" else line.phase.wireName
            assertTrue(id.contains(expectedPhase) || id.startsWith("es-rp"), "$id fits phase $expectedPhase")
            timeline.note { fallback = FallbackKind.ofReason(line.fallbackReason) }
            timeline.mark(app.mokuhyo.speech.TurnStage.OUTPUT_START)
            s.answer("Respuesta número $n con varias palabras para el nivel")
            n++
        }
        assertEquals(s.turns.size, s.scriptedTurns, "100 % guided")
        val summary = TimingReport.summarize(listOf(timeline.snapshot()))
        assertEquals(1.0, summary.fallbackRate, "the fallback rate is recorded")
        assertEquals(FallbackKind.NO_MODEL.wire, timeline.snapshot().turns.first().fallback)
    }

    @Test
    fun theIndexMatchesClipsToTheTextTheyWereRenderedFrom() {
        val first = PromptAudioIndex.build(pack)
        assertEquals(bank.size + rolePlays.size, first.prompts.size)
        val id = bank.first().id
        val rendered = first.copy(prompts = first.prompts.map { if (it.id == id) it.copy(audioSha256 = "ab", bytes = 1_000, seconds = 1.2) else it })
        assertNotNull(rendered.usable(id, bank.first().prompt))
        assertNull(rendered.usable(id, "Una pregunta distinta"), "a reworded prompt never plays its old clip")
        // Rebuilding keeps a clip only while its text is unchanged.
        val reworded = pack.copy(questions = listOf(bank.first().copy(prompt = "Otra pregunta")) + bank.drop(1))
        assertNull(PromptAudioIndex.build(reworded, rendered).prompts.first { it.id == id }.audioSha256)
        assertEquals("ab", PromptAudioIndex.build(pack, rendered).prompts.first { it.id == id }.audioSha256)
        assertEquals(rendered, PromptAudioIndex.parse(rendered.toJson()))
    }
}
