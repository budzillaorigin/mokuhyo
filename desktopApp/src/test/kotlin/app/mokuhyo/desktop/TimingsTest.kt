package app.mokuhyo.desktop

import app.mokuhyo.desktop.ui.diagnosticRows
import app.mokuhyo.speech.TimelineSetup
import app.mokuhyo.speech.TimingReport
import app.mokuhyo.speech.TurnStage
import app.mokuhyo.speech.TurnTimeline
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimingsTest {
    @Test
    fun lastSessionSurvivesARestartAndExportsTimingsOnly() {
        val dir = Files.createTempDirectory("mokuhyo-timings").toFile()
        val timings = Timings(dir) { null }
        val t = timings.start("conv-1", "OPI", "es", TimelineSetup(modelId = "phi-4-mini-instruct-q4km", backend = "cpu"))
        t.beginTurn(cold = true)
        t.mark(TurnStage.OUTPUT_START)
        timings.turnDone(t)
        t.beginTurn()
        t.markBefore(TurnStage.LAST_SPEECH, 1_500)
        t.mark(TurnStage.VAD_ENDPOINT)
        t.mark(TurnStage.OUTPUT_START)
        timings.turnDone(t)

        // A new app start reads the saved file.
        val reloaded = Timings(dir) { null }.last()!!
        assertEquals(2, reloaded.sessions.single().turns.size)
        val rows = diagnosticRows(reloaded)
        assertEquals("0 (cold)", rows[0][0])
        assertTrue(rows[1][1].toLong() >= 1_500, "first audio includes the VAD tail")

        val files = Timings.export(reloaded, java.io.File(dir, "out/report"))
        assertEquals(listOf("report.json", "report.md"), files.map { it.name })
        assertEquals(reloaded, TimingReport.parse(files[0].readText()))
        assertTrue(TurnTimeline.FIRST_AUDIO in files[1].readText())
    }

    @Test
    fun benchEndpointIsTheVadTailAfterTheLastSpeech() {
        // One second of a 200 Hz tone, then nothing: the live VAD stops 1.5 s after the tone.
        val tone = ShortArray(16_000) { (sin(2 * PI * 200 * it / 16_000) * 8_000).toInt().toShort() }
        assertEquals(1_500, BenchInterview.endpointTailMs(tone))
        assertEquals(1_500, BenchInterview.endpointTailMs(tone + ShortArray(8_000)))
    }

    @Test
    fun diagnosticsNeverShowText() {
        val t = TurnTimeline("s", "TOPIC", "ja")
        t.beginTurn()
        val r = Timings(Files.createTempDirectory("mokuhyo-timings").toFile()) { null }.report(listOf(t.snapshot()), real = false)
        assertFalse(diagnosticRows(r).flatten().any { it.any { c -> c.code > 0x2E80 } }, "rows hold numbers and stage names only")
    }
}
