package app.tsumugi.speaking

import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.jp.Kana
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
) {
    /** Word targets for [sentence]; particles attach to the preceding word, punctuation is dropped. */
    suspend fun targets(sentence: String): List<WordTarget> {
        val morphemes = analyzer()?.analyze(sentence).orEmpty()
        val dict = dictionary()
        val words = mutableListOf<WordTarget>()
        for (m in morphemes) {
            val reading = Kana.toHiragana(m.pronunciation ?: m.reading ?: m.surface).filter { Kana.isKana(it) }
            if (reading.isEmpty()) continue
            if (m.pos.firstOrNull() in PARTICLES && words.isNotEmpty()) {
                words[words.lastIndex] = words.last().copy(followedByParticle = true)
                continue
            }
            val accent = dict?.pitchAccents(m.baseForm.takeIf { m.surface == m.baseForm } ?: m.surface, Kana.toHiragana(m.reading ?: m.surface))?.firstOrNull()
            words += WordTarget(reading, accent)
        }
        return words
    }

    /**
     * Scores one recording. [pcm16k] is 16 kHz mono in [-1, 1]; [transcript] is what the recognizer heard (any script;
     * converted to kana through the tokenizer); [segments] are recognizer timestamps when available.
     */
    suspend fun analyze(sentence: String, transcript: String?, pcm16k: FloatArray, segments: List<Pair<LongRange, String>>? = null): PronunciationReport {
        val targets = targets(sentence)
        val heard = transcript?.let { kanaOf(it) }
        return withContext(Dispatchers.Default) { PronunciationAnalyzer.analyze(targets, heard, pcm16k, segments) }
    }

    /** Shadowing: compare the learner's recording with the model audio (both 16 kHz mono). */
    suspend fun shadowing(reference: FloatArray, attempt: FloatArray): ShadowingReport =
        withContext(Dispatchers.Default) { PronunciationAnalyzer.shadowingCompare(reference, attempt) }

    /** Reading of arbitrary text in hiragana (via the tokenizer when installed). */
    suspend fun kanaOf(text: String): String {
        val morphemes = analyzer()?.analyze(text) ?: return Kana.toHiragana(text)
        return morphemes.joinToString("") { Kana.toHiragana(it.pronunciation ?: it.reading ?: it.surface) }
            .filter { Kana.isKana(it) }
    }

    private companion object {
        val PARTICLES = setOf("助詞", "助動詞")
    }
}
