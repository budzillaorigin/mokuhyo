package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.opi.OpiPack
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListeningKitTest {
    @Test
    fun theKitsPromptsSpanEveryPhaseAndAreStable() {
        val pack = OpiPack.parse(generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "content/packs/es/opi.json") }.first { it.isFile }.readText())
        val picked = ListeningKit.pick(pack, 10)
        assertEquals(10, picked.size)
        assertEquals(picked, ListeningKit.pick(pack, 10), "the same prompts every time, so engines are compared on the same text")
        assertTrue(picked.any { it.first.contains("-rp-") }, "a role-play opening is included")
        val phaseOf = pack.questions.associate { it.id to it.phase }
        listOf("warmup", "level_check", "probe", "winddown").forEach { p -> assertTrue(picked.any { phaseOf[it.first] == p }, "$p: $picked") }
    }

    private val a = VoiceSpec("a-voice", "es", "female", "piper", "MIT")
    private val b = VoiceSpec("b-voice", "es", "female", "piper", "MIT")
    private val os = VoiceSpec("Monica", "es", "female", "os", "OS")

    /** The voice service's real fallback: A fails, so B's audio comes back. */
    private val aFails = object : SpeechOutput {
        override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double) =
            SpokenAudio(byteArrayOf(2), b)
        override fun voicesFor(language: String) = listOf(a, b)
    }

    @Test
    fun aCandidateThatFellBackIsMissingNeverAnotherVoicesAudio() = runBlocking {
        // QC 2026-10-30 V5: the kit filed B's WAV under A's name.
        val takes = ListeningKit.render(aFails, "es", listOf("q1" to "¿Cómo se llama?"), listOf(a, b))
        assertEquals(listOf("piper:b-voice"), takes.rendered.map { it.source }, "only B's own render is B's")
        assertEquals(listOf(ListeningKit.Missing("piper:a-voice", "q1", "fell back to piper:b-voice")), takes.missing)
    }

    @Test
    fun candidatesAreExplicitAndRestrictedSourcesNeedOptIn() {
        val listed = listOf(a, b, os)
        assertEquals(listOf(a, b), ListeningKit.candidates(listed, null, allowRestricted = false).first)
        val (picked, missing) = ListeningKit.candidates(listed, listOf("piper:b-voice", "os:Monica", "piper:nope"), allowRestricted = false)
        assertEquals(listOf(b), picked)
        assertEquals(listOf("piper:nope", "os:Monica"), missing.map { it.first })
        assertEquals(listOf(b, os), ListeningKit.candidates(listed, listOf("piper:b-voice", "os:Monica"), allowRestricted = true).first)
    }

    @Test
    fun anExternalAuditionSetJoinsWithItsMetadataAndMissingPromptsAreListed() {
        val dir = Files.createTempDirectory("audition").toFile()
        File(dir, "audition.json").writeText("""{"label":"cand-x","engine":"ext","model":"m","revision":"r1","weight_sha256":"aa","reference_sha256":"bb","settings":{"seed":1}}""")
        File(dir, "es").mkdirs()
        File(dir, "es/q1.wav").writeBytes(byteArrayOf(1))
        val set = ListeningKit.ExternalAudition.read(dir)
        val takes = ListeningKit.imported(set, "es", listOf("q1" to "x", "q2" to "y"))
        assertEquals(listOf("external:cand-x" to "q1"), takes.rendered.map { it.source to it.promptId })
        assertEquals(listOf("q2"), takes.missing.map { it.promptId })
        File(dir, "audition.json").writeText("""{"label":"cand-x","engine":"ext"}""")
        assertFailsWith<IllegalArgumentException> { ListeningKit.ExternalAudition.read(dir) }
    }
}
