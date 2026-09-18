package app.tsumugi.speaking

import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.jp.InflectedWord
import app.tsumugi.jp.InflectingClass
import app.tsumugi.jp.Kana
import app.tsumugi.jp.PitchRules
import app.tsumugi.jp.tokenizer.Morpheme
import app.tsumugi.jp.tokenizer.baseReading
import app.tsumugi.jp.tokenizer.MorphologicalAnalyzer
import app.tsumugi.speech.PronunciationAnalyzer
import app.tsumugi.speech.PronunciationReport
import app.tsumugi.speech.ShadowingReport
import app.tsumugi.speech.WordTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pronunciation panel (BRIEF §5.10): turns a target sentence into per-word readings with Kanjium accents
 * (tokenizer + dictionary pitch table), then runs the heuristic analyzer on the learner's recording.
 */
class PronunciationService(
    private val analyzer: suspend () -> MorphologicalAnalyzer?,
    private val dictionary: suspend () -> DictionaryRepository?,
    /** Accent list for (word, reading); defaults to the dictionary's Kanjium table. Tests pass a fake. */
    private val pitchLookup: (suspend (String, String) -> List<Int>)? = null,
) {
    /**
     * Word targets for [sentence] (F-21). A word is a content morpheme plus its auxiliaries (助動詞) and the
     * conjunctive て/で, whose kana stay in the target (食べ+まし+た → たべました). Only particles (助詞) attach to the
     * previous word as [WordTarget.followedByParticle]; punctuation is dropped. The accent is looked up for the
     * lemma (食べる, 高い) and carried to the conjugated form by [PitchRules].
     */
    @Throws(Exception::class)
    suspend fun targets(sentence: String): List<WordTarget> {
        val morphemes = analyzer()?.analyze(sentence).orEmpty()
        val lookup: suspend (String, String) -> List<Int> = pitchLookup ?: run {
            val dict = dictionary()
            val fn: suspend (String, String) -> List<Int> = { w, r -> dict?.pitchAccents(w, r).orEmpty() }
            fn
        }
        return buildTargets(morphemes, lookup)
    }

    /**
     * Scores one recording. [pcm16k] is 16 kHz mono in [-1, 1]; [transcript] is what the recognizer heard (any script;
     * converted to kana through the tokenizer); [segments] are recognizer timestamps when available.
     */
    @Throws(Exception::class)
    suspend fun analyze(sentence: String, transcript: String?, pcm16k: FloatArray, segments: List<Pair<LongRange, String>>? = null): PronunciationReport {
        val targets = targets(sentence)
        val heard = transcript?.let { kanaOf(it) }
        return withContext(Dispatchers.Default) { PronunciationAnalyzer.analyze(targets, heard, pcm16k, segments) }
    }

    /** Shadowing: compare the learner's recording with the model audio (both 16 kHz mono). */
    @Throws(Exception::class)
    suspend fun shadowing(reference: FloatArray, attempt: FloatArray): ShadowingReport =
        withContext(Dispatchers.Default) { PronunciationAnalyzer.shadowingCompare(reference, attempt) }

    /** Reading of arbitrary text in hiragana (via the tokenizer when installed). */
    @Throws(Exception::class)
    suspend fun kanaOf(text: String): String {
        val morphemes = analyzer()?.analyze(text) ?: return Kana.toHiragana(text)
        return morphemes.joinToString("") { Kana.toHiragana(it.pronunciation ?: it.reading ?: it.surface) }
            .filter { Kana.isKana(it) }
    }

    internal companion object {
        /** Groups morphemes into words and resolves each word's accent (see [targets]). */
        suspend fun buildTargets(morphemes: List<Morpheme>, pitch: suspend (String, String) -> List<Int>): List<WordTarget> {
            val units = ArrayList<MutableList<Morpheme>>()
            val particleAfter = HashSet<Int>()
            // Whether the last word can still take auxiliaries (not after a particle or punctuation).
            var open = false
            for (m in morphemes) {
                val pos = m.pos.firstOrNull()
                val current = units.lastOrNull()
                val headPos = current?.firstOrNull()?.pos?.firstOrNull()
                when {
                    pos == SYMBOL -> open = false
                    morphemeKana(m).isEmpty() -> Unit
                    pos == AUXILIARY && open -> current!!.add(m)
                    pos == PARTICLE && open && m.surface in CONJUNCTIVE && m.pos.getOrNull(1) == "接続助詞" &&
                        headPos in INFLECTING -> current!!.add(m)
                    pos == PARTICLE && current != null -> {
                        particleAfter.add(units.lastIndex)
                        open = false
                    }
                    else -> {
                        units.add(mutableListOf(m))
                        open = true
                    }
                }
            }
            return units.mapIndexed { i, unit ->
                val reading = unit.joinToString("") { morphemeKana(it) }
                WordTarget(reading, accentOf(unit, pitch), followedByParticle = i in particleAfter)
            }.filter { it.reading.isNotEmpty() }
        }

        private suspend fun accentOf(unit: List<Morpheme>, pitch: suspend (String, String) -> List<Int>): Int? {
            val head = unit.first()
            val lemmaReading = head.baseReading() ?: return null
            val lemmaDownstep = pitch(head.baseForm, lemmaReading).firstOrNull() ?: return null
            val headIsLemma = head.surface == head.baseForm
            if (unit.size == 1 && headIsLemma) return lemmaDownstep
            val wordClass = when (head.pos.firstOrNull()) {
                "動詞" -> InflectingClass.VERB
                "形容詞" -> InflectingClass.ADJECTIVE
                else -> InflectingClass.OTHER
            }
            return PitchRules.downstep(
                InflectedWord(
                    wordClass = wordClass,
                    lemmaDownstep = lemmaDownstep,
                    lemmaReading = lemmaReading,
                    stem = morphemeKana(head),
                    headIsLemma = headIsLemma,
                    ichidan = head.conjugationType?.startsWith("一段") == true,
                    headForm = head.conjugationForm,
                    endings = unit.drop(1).map { morphemeKana(it) },
                ),
            )
        }

        private fun morphemeKana(m: Morpheme): String = Kana.toHiragana(m.pronunciation ?: m.reading ?: m.surface).filter { Kana.isKana(it) }

        const val PARTICLE = "助詞"
        const val AUXILIARY = "助動詞"
        const val SYMBOL = "記号"
        val INFLECTING = setOf("動詞", "形容詞")
        val CONJUNCTIVE = setOf("て", "で")
    }
}
