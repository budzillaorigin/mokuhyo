package app.mokuhyo.lang

import app.mokuhyo.speech.PunctuationSentences
import app.mokuhyo.speech.SentenceSplitter
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale

/**
 * ICU sentence boundaries for a streamed reply (BRIEF_INTERVIEW_SPEED N3), with ICU's abbreviation suppressions
 * (`@ss=standard`, so "Sr." or "M." doesn't end a sentence where ICU has data for the language). A boundary counts
 * only once text follows it, so a sentence still being generated is never cut; the last one completes when [final].
 * Boundaries ICU finds inside the text that [PunctuationSentences] doesn't (a line break, say) are kept too.
 */
class IcuSentences(language: String) : SentenceSplitter {
    private val prototype: BreakIterator = BreakIterator.getSentenceInstance(ULocale("${ULocale.forLanguageTag(language).language}@ss=standard"))

    override fun ends(text: String, final: Boolean): List<Int> {
        val it = prototype.clone() as BreakIterator
        it.setText(text)
        val out = ArrayList<Int>()
        var last = 0
        var b = it.next()
        while (b != BreakIterator.DONE) {
            val complete = b < text.length && text.substring(b).isNotBlank()
            if ((complete || (final && b == text.length)) && text.substring(last, b).any { c -> c.isLetterOrDigit() }) {
                // ICU includes the trailing space; the stream trims it.
                out += b
                last = b
            }
            b = it.next()
        }
        return out
    }
}
