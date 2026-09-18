package app.tsumugi.study

import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.jp.strokes.HandwritingRecognizer
import app.tsumugi.jp.strokes.Point
import app.tsumugi.jp.strokes.RawResult
import app.tsumugi.jp.strokes.RawWritingChecker
import app.tsumugi.jp.strokes.Strictness
import app.tsumugi.jp.strokes.SvgPath
import app.tsumugi.jp.strokes.SvgTemplateSource
import app.tsumugi.jp.strokes.WritingSession
import app.tsumugi.srs.SrsRepository
import app.tsumugi.domain.Stage

/**
 * Skritter-style writing (BRIEF §5.7) and handwriting search (§5.3) on top of the KanjiVG strokes in the
 * dictionary pack. Stroke grading and recognition are shared Kotlin; the apps only capture strokes.
 */
class WritingService(private val dictionary: DictionaryRepository, private val srs: SrsRepository) {

    val recognizer: HandwritingRecognizer by lazy { HandwritingRecognizer(SvgTemplateSource { dictionary.allStrokePaths() }) }

    /** The kanji's strokes as polylines in KanjiVG's 109×109 space, or empty when KanjiVG doesn't have it. */
    suspend fun template(kanji: String): List<List<Point>> = dictionary.strokes(kanji).map { SvgPath.flatten(it.path) }

    /** A guided (template-visible) session for one character. */
    suspend fun guided(kanji: String, strictness: Strictness = Strictness.NORMAL): WritingSession? =
        template(kanji).takeIf { it.isNotEmpty() }?.let { WritingSession(it, strictness) }

    /** Raw ("no template") check for a writing review; the learner then confirms a rating. */
    suspend fun checkRaw(kanji: String, strokes: List<List<Point>>): RawResult? =
        template(kanji).takeIf { it.isNotEmpty() }?.let { RawWritingChecker.check(it, strokes) }

    /** Today's writing block: a few kanji the learner already knows (Guru or better), least practised first. */
    suspend fun practiceSet(count: Int = 3): List<String> {
        val stages = srs.stages()
        return stages.filter { (id, stage) -> id.startsWith("k:") && stage >= Stage.GURU }
            .keys.map { it.removePrefix("k:") }
            .shuffled()
            .take(count)
    }

    companion object {
        const val CANVAS = 109f
    }
}
