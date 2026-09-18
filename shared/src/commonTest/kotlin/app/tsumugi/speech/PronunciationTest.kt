package app.tsumugi.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class PronunciationTest {
    // --- Mora alignment ---

    @Test
    fun perfectMatch() {
        val r = MoraAlignment.align("がっこう", "ガッコウ")
        assertEquals(1.0, r.accuracy)
        assertTrue(r.matches.all { it.outcome == MoraOutcome.HIT })
        assertTrue(r.insertions.isEmpty())
    }

    @Test
    fun droppedLongVowel() {
        val r = MoraAlignment.align("かわいい", "かわい")
        assertEquals(0.75, r.accuracy)
        assertEquals(1, r.matches.count { it.outcome == MoraOutcome.MISSED })
        assertEquals("い", r.matches.single { it.outcome == MoraOutcome.MISSED }.target)
    }

    @Test
    fun droppedSmallTsu() {
        val r = MoraAlignment.align("きって", "きて")
        assertEquals(listOf(MoraOutcome.HIT, MoraOutcome.MISSED, MoraOutcome.HIT), r.matches.map { it.outcome })
        assertEquals("っ", r.matches[1].target)
    }

    @Test
    fun voicingConfusionIsACommonSubstitution() {
        val r = MoraAlignment.align("がっこう", "かっこう")
        assertEquals(0.75, r.accuracy)
        val sub = r.matches.first()
        assertEquals(MoraOutcome.SUBSTITUTED, sub.outcome)
        assertEquals("か", sub.heard)
        assertTrue(sub.commonConfusion)
    }

    @Test
    fun mixedTextUsesTheReadingFunction() {
        val r = MoraAlignment.align("がっこう", "学校", reading = { if (it == "学校") "がっこう" else it })
        assertEquals(1.0, r.accuracy)
    }

    // --- Pitch accent ---

    private val low = 150.0
    private val high = 200.0

    /** はし+が with the given accent: 500 ms silence, three 250 ms morae, 500 ms silence. */
    private fun hashiGa(accent: Int?): FloatArray {
        val heights = when (accent) {
            0 -> listOf(low, high, high)
            1 -> listOf(high, low, low)
            2 -> listOf(low, high, low)
            else -> listOf(180.0, 180.0, 180.0)
        }
        return Signals.withFloor(Signals.concat(Signals.silence(500), Signals.steps(250, *heights.toDoubleArray()), Signals.silence(500)))
    }

    private fun words(downstep: Int) = listOf(WordTarget("はし", downstep, followedByParticle = true), WordTarget("が", null))

    @Test
    fun hashiAccentsAreToldApartFromStepContours() {
        for (spoken in 0..2) for (target in 0..2) {
            for (stt in listOf(null, listOf(500L..999L to "はし", 1000L..1249L to "が"))) {
                val report = PronunciationAnalyzer.analyze(words(target), "はしが", hashiGa(spoken), stt)
                val word = report.words.first()
                val expected = if (spoken == target) PitchVerdict.MATCH else PitchVerdict.WRONG_DROP
                assertEquals(expected, word.verdict, "spoke $spoken, target $target, stt=${stt != null}: ${word.observedMarks}")
                assertEquals(PitchVerdict.UNKNOWN_ACCENT, report.words[1].verdict)
            }
        }
    }

    @Test
    fun accentMarkers() {
        val heiban = PronunciationAnalyzer.analyze(words(0), "はしが", hashiGa(0)).words.first()
        assertEquals("は↑し(が)", heiban.expectedMarks)
        assertEquals("は↑し(が)", heiban.observedMarks)
        val atamadaka = PronunciationAnalyzer.analyze(words(1), "はしが", hashiGa(2)).words.first()
        assertEquals("は↓し(が)", atamadaka.expectedMarks)
        assertEquals("は↑し↓(が)", atamadaka.observedMarks)
    }

    @Test
    fun monotoneIsFlat() {
        val report = PronunciationAnalyzer.analyze(words(2), "はしが", hashiGa(null))
        assertEquals(PitchVerdict.FLAT, report.words.first().verdict)
        assertEquals(0.0, report.pitchAccuracy)
    }

    // --- Report, scores and honesty ---

    @Test
    fun compositeIsBoundedAndCombinesSubScores() {
        val good = PronunciationAnalyzer.analyze(words(0), "はしが", hashiGa(0))
        assertEquals(1.0, good.moraAccuracy)
        assertEquals(1.0, good.pitchAccuracy)
        assertTrue(good.composite in 0..100)
        assertEquals(setOf("mora", "pitch", "fluency"), good.subScores.keys)
        assertTrue(good.rateMoraPerSec in 2.0..6.0, "rate ${good.rateMoraPerSec}")
        val bad = PronunciationAnalyzer.analyze(words(1), "かしか", hashiGa(0))
        assertTrue(bad.composite in 0..100)
        assertTrue(bad.composite < good.composite, "${bad.composite} < ${good.composite}")
    }

    @Test
    fun notesAreHonest() {
        val noTranscript = PronunciationAnalyzer.analyze(words(0), null, hashiGa(0))
        assertNull(noTranscript.moraAccuracy)
        assertTrue(noTranscript.notes.any { it.startsWith("Heuristic") })
        assertTrue(noTranscript.notes.any { "transcript" in it })
        assertTrue(noTranscript.notes.any { "estimated" in it })
        assertTrue("mora" !in noTranscript.subScores)

        val silent = PronunciationAnalyzer.analyze(words(0), "", Signals.withFloor(Signals.silence(2000)))
        assertTrue(silent.composite in 0..100)
        assertNull(silent.pitchAccuracy)
        assertTrue(silent.words.isEmpty())
        assertTrue(silent.notes.any { "Too little voiced audio" in it })
    }

    @Test
    fun pausesLowerFluency() {
        val fluent = Signals.withFloor(Signals.concat(Signals.silence(300), Signals.steps(150, 150.0, 200.0, 200.0, 190.0, 180.0, 170.0, 160.0, 150.0), Signals.silence(300)))
        val halting = Signals.withFloor(
            Signals.concat(
                Signals.silence(300), Signals.steps(150, 150.0, 200.0), Signals.silence(600),
                Signals.steps(150, 200.0, 190.0), Signals.silence(600), Signals.steps(150, 180.0, 170.0),
                Signals.silence(600), Signals.steps(150, 160.0, 150.0), Signals.silence(300),
            ),
        )
        val target = listOf(WordTarget("たべものや", null), WordTarget("です", null))
        val a = PronunciationAnalyzer.analyze(target, null, fluent)
        val b = PronunciationAnalyzer.analyze(target, null, halting)
        assertEquals(0, a.pauses.size)
        assertEquals(3, b.pauses.size)
        assertTrue(b.fluencyScore < a.fluencyScore, "${b.fluencyScore} < ${a.fluencyScore}")
    }

    // --- Shadowing ---

    private fun phrase(scale: Double = 1.0, lead: Int = 300, rising: Boolean = true): FloatArray {
        val contour: (Double) -> Double = if (rising) { t -> if (t < 0.8) 140 + 80 * t / 0.8 else 220 - 90 * (t - 0.8) / 0.7 }
        else { t -> if (t < 0.8) 220 - 80 * t / 0.8 else 140 + 90 * (t - 0.8) / 0.7 }
        return Signals.withFloor(Signals.concat(Signals.silence(lead), Signals.tone(1500, 4) { t -> contour(t) * scale }, Signals.silence(300)))
    }

    @Test
    fun shadowingIdenticalScoresHigh() {
        val r = PronunciationAnalyzer.shadowingCompare(phrase(), phrase())
        assertTrue(r.intonationScore >= 95, "intonation ${r.intonationScore}")
        assertTrue(r.timingScore >= 90, "timing ${r.timingScore}")
        assertTrue(kotlin.math.abs(r.durationRatio - 1.0) < 0.05)
    }

    @Test
    fun shadowingIgnoresVoiceHeightAndLeadingSilence() {
        val r = PronunciationAnalyzer.shadowingCompare(phrase(), phrase(scale = 1.5, lead = 700))
        assertTrue(r.intonationScore >= 85, "intonation ${r.intonationScore}")
        assertTrue(r.timingScore >= 85, "timing ${r.timingScore}")
    }

    @Test
    fun shadowingDifferentMelodyScoresLow() {
        val same = PronunciationAnalyzer.shadowingCompare(phrase(), phrase())
        val different = PronunciationAnalyzer.shadowingCompare(phrase(), phrase(rising = false))
        assertTrue(different.intonationScore < 70, "intonation ${different.intonationScore}")
        assertTrue(different.overall < same.overall)
        val silent = PronunciationAnalyzer.shadowingCompare(phrase(), Signals.withFloor(Signals.silence(1000)))
        assertEquals(0, silent.overall)
        assertTrue(silent.notes.any { "Not enough" in it })
    }

    // --- Performance ---

    @Test
    fun tenSecondsAnalyzeQuickly() {
        val parts = ArrayList<FloatArray>()
        repeat(10) { k ->
            parts += Signals.tone(800, 4) { t -> 130 + 60 * kotlin.math.sin(6.0 * t + k) }
            parts += Signals.silence(200)
        }
        val audio = Signals.withFloor(Signals.concat(*parts.toTypedArray()))
        val target = List(10) { WordTarget("たべました", 3, followedByParticle = false) }
        val transcript = "たべました".repeat(10)
        PronunciationAnalyzer.analyze(target, transcript, audio) // warm-up (JIT)
        val runs = 3
        val mark = TimeSource.Monotonic.markNow()
        repeat(runs) { PronunciationAnalyzer.analyze(target, transcript, audio) }
        val ms = mark.elapsedNow().inWholeMilliseconds / runs
        println("PronunciationAnalyzer: ${audio.size / 16} ms of audio analysed in $ms ms")
        assertTrue(ms < 300, "10 s analysed in $ms ms")
    }
}
