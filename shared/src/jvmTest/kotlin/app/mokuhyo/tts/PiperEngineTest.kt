package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real bundled Piper (voices/build.sh) with real voices (tools/voices/manifest.py --fetch). Every test prints
 * SKIP and returns — never fails — when the build or the voice files are missing, so CI without them stays green.
 */
class PiperEngineTest {
    private val catalog by lazy { VoiceCatalog.discover() }

    private fun catalogOrSkip(test: String): VoiceCatalog? {
        val c = catalog
        if (!c.usable) {
            println("SKIP $test: no Piper build or voices (${c.source}; piper=${c.piperExecutable}, voices=${c.voices.size}); " +
                "run voices/build.sh and tools/voices/manifest.py --fetch")
            return null
        }
        return c
    }

    private fun voice(c: VoiceCatalog, preferred: List<String>): VoiceSpec =
        preferred.firstNotNullOfOrNull { id -> c.voices.firstOrNull { it.spec.id == id }?.spec } ?: c.voices.first().spec

    private val sentences = mapOf(
        "es" to "Buenos días. Hoy vamos a practicar la comprensión auditiva.",
        "fr" to "Bonjour. Aujourd'hui, nous allons pratiquer la compréhension orale.",
        "de" to "Guten Morgen. Heute üben wir das Hörverstehen.",
        "pt-BR" to "Bom dia. Hoje vamos praticar a compreensão oral.",
        "ru" to "Доброе утро. Сегодня мы будем тренировать понимание на слух.",
        "fa" to "صبح بخیر. امروز درک شنیداری را تمرین می‌کنیم.",
    )

    @Test
    fun synthesizesOneSentencePerAvailableVoice(): Unit = runBlocking {
        val c = catalogOrSkip("synthesizesOneSentencePerAvailableVoice") ?: return@runBlocking
        PiperEngine(c).use { engine ->
            for (v in c.voices) {
                val text = sentences[v.spec.language] ?: continue
                val wav = assertNotNull(engine.synthesize(text, v.spec, 1.0), "${v.spec.id} produced audio")
                val info = assertNotNull(Wav.info(wav), "${v.spec.id}: valid WAV")
                println("piper ${v.spec.id}: ${"%.2f".format(info.durationSeconds)} s @ ${info.sampleRate} Hz, ${wav.size} bytes")
                assertEquals(1, info.channels)
                assertEquals(16, info.bitsPerSample)
                assertTrue(info.durationSeconds in 1.5..12.0, "${v.spec.id}: plausible duration ${info.durationSeconds}")
            }
        }
    }

    @Test
    fun serviceUsesBundledVoiceForEachLanguage(): Unit = runBlocking {
        val c = catalogOrSkip("serviceUsesBundledVoiceForEachLanguage") ?: return@runBlocking
        VoiceService(listOf(PiperEngine(c), OsSpeechEngine())).use { service ->
            for (lang in c.voices.map { it.spec.language }.distinct()) {
                val spoken = assertNotNull(service.synthesize(sentences[lang] ?: continue, lang), lang)
                assertEquals("piper", spoken.voice.engine, "$lang is spoken by the bundled voice")
            }
            val female = service.voicesFor("es").firstOrNull { it.engine == "piper" && it.gender == "female" }
            if (female != null) {
                val spoken = assertNotNull(service.synthesize("Hola.", "es", VoiceSpec("?", "es", "female", "piper", "")))
                assertEquals(female.id, spoken.voice.id, "requested gender picks the female es voice")
                assertEquals(female.speaker, spoken.voice.speaker)
            }
        }
    }

    /** BRIEF_PHASE8 N-07 gate: a dialogue renders with distinct voices for distinct speakers in every language that has two. */
    @Test
    fun dialoguesRenderWithDistinctVoices(): Unit = runBlocking {
        val c = catalogOrSkip("dialoguesRenderWithDistinctVoices") ?: return@runBlocking
        PiperEngine(c).use { engine ->
            for (lang in c.voices.map { it.spec.language }.distinct()) {
                val voices = c.voices.filter { it.spec.language == lang }.map { it.spec }
                if (voices.size < 2) continue
                val cast = app.mokuhyo.lang.VoiceRotation.assign(voices, listOf("a" to "male", "b" to "female"), "$lang-dialogue-1")
                val a = assertNotNull(engine.synthesize(sentences.getValue(lang), cast.getValue("a"), 1.0))
                val b = assertNotNull(engine.synthesize(sentences.getValue(lang), cast.getValue("b"), 1.0))
                println("dialogue $lang: ${cast.getValue("a").id}/${cast.getValue("a").speaker} vs ${cast.getValue("b").id}/${cast.getValue("b").speaker}")
                assertTrue(cast.getValue("a") != cast.getValue("b") && !a.contentEquals(b), "$lang: two speakers, two voices")
            }
        }
    }

    @Test
    fun oneProcessServesManyRequests(): Unit = runBlocking {
        val c = catalogOrSkip("oneProcessServesManyRequests") ?: return@runBlocking
        val v = voice(c, listOf("es_ES-davefx-medium", "de_DE-thorsten-medium"))
        PiperEngine(c).use { engine ->
            assertNotNull(engine.synthesize("Primera frase.", v, 1.0))
            val first = engine.liveProcesses().values.single()
            assertNotNull(engine.synthesize("Segunda frase, un poco más larga.", v, 1.0))
            val second = engine.liveProcesses().values.single()
            assertEquals(first.pid(), second.pid(), "the second request reused the running process")
            assertTrue(second.isAlive)
        }
    }

    @Test
    fun idleProcessIsStopped(): Unit = runBlocking {
        val c = catalogOrSkip("idleProcessIsStopped") ?: return@runBlocking
        val v = voice(c, listOf("de_DE-thorsten-medium", "es_ES-davefx-medium"))
        PiperEngine(c, idleTimeoutMs = 500).use { engine ->
            assertNotNull(engine.synthesize("Guten Tag.", v, 1.0))
            val proc = engine.liveProcesses().values.single()
            withTimeout(10_000) { while (proc.isAlive) delay(100) }
            assertTrue(engine.liveProcesses().isEmpty(), "the idle process was removed")
            // The next request starts a fresh process.
            assertNotNull(engine.synthesize("Noch einmal.", v, 1.0))
            assertTrue(engine.liveProcesses().values.single().pid() != proc.pid())
        }
    }

    @Test
    fun cancellationKillsTheProcess(): Unit = runBlocking {
        val c = catalogOrSkip("cancellationKillsTheProcess") ?: return@runBlocking
        val v = voice(c, listOf("es_ES-davefx-medium", "de_DE-thorsten-medium"))
        PiperEngine(c).use { engine ->
            assertNotNull(engine.synthesize("Hola.", v, 1.0)) // warm: the process is running
            val proc = engine.liveProcesses().values.single()
            val long = List(80) { "Esta es una frase bastante larga que tarda en sintetizarse por completo." }.joinToString(" ")
            val job = async(Dispatchers.IO) { engine.synthesize(long, v, 1.0) }
            delay(300)
            job.cancel()
            assertFailsWith<CancellationException> { job.await() }
            withTimeout(5_000) { while (proc.isAlive) delay(50) }
            assertTrue(!proc.isAlive, "cancelling the request killed piper")
            assertNotNull(engine.synthesize("Otra vez.", v, 1.0), "a fresh process serves the next request")
        }
    }

    @Test
    fun speedChangesDuration(): Unit = runBlocking {
        val c = catalogOrSkip("speedChangesDuration") ?: return@runBlocking
        val v = voice(c, listOf("es_ES-davefx-medium", "de_DE-thorsten-medium"))
        val text = sentences[v.language] ?: "Hola, esto es una prueba de velocidad."
        PiperEngine(c).use { engine ->
            // Mean of three renders each: Piper's noise_w makes single durations vary by several percent.
            suspend fun duration(speed: Double) =
                List(3) { Wav.info(assertNotNull(engine.synthesize(text, v, speed)))!!.durationSeconds }.average()
            val normal = duration(1.0)
            val slow = duration(0.8)
            println("piper ${v.id}: speed 1.0 = ${"%.2f".format(normal)} s, speed 0.8 = ${"%.2f".format(slow)} s")
            // length_scale 1.25 lengthens phonemes, not the fixed pauses, and Piper's duration predictor responds
            // sub-linearly: the CLI's own --length_scale 1.25 gives ~1.11-1.13x for these voices. Random noise_w
            // varies durations by ~2 % between runs.
            assertTrue(slow > normal * 1.05, "speed 0.8 is slower ($slow s vs $normal s)")
            assertEquals(1, engine.liveProcesses().size, "both speeds used the same process")
        }
    }
}
