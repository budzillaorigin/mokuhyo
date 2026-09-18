package app.tsumugi.srs

import kotlin.test.Test
import kotlin.test.assertEquals

class AnswerCheckerTest {

    private fun meaning(answer: String, vararg meanings: String, synonyms: List<String> = emptyList()) =
        AnswerChecker.checkMeaning(answer, meanings.toList(), synonyms).verdict

    @Test
    fun meaningExactIgnoringCaseArticlesAndParentheses() {
        assertEquals(Verdict.CORRECT, meaning("Eat", "to eat"))
        assertEquals(Verdict.CORRECT, meaning("to eat", "eat"))
        assertEquals(Verdict.CORRECT, meaning("  the Sun ", "sun"))
        assertEquals(Verdict.CORRECT, meaning("counter", "counter (for long objects)"))
        assertEquals(Verdict.CORRECT, meaning("one's self", "one's self"))
        assertEquals(Verdict.CORRECT, meaning("rice-field", "rice field"))
    }

    @Test
    fun meaningTypoToleranceOnlyForLongWords() {
        assertEquals(Verdict.CLOSE, meaning("mountian", "mountain"), "transposition")
        assertEquals(Verdict.CLOSE, meaning("languag", "language"), "deletion")
        assertEquals(Verdict.CLOSE, meaning("schoool", "school"), "insertion")
        assertEquals(Verdict.WRONG, meaning("dig", "dog"), "short words must be exact")
        assertEquals(Verdict.WRONG, meaning("mountains high", "mountain"))
        assertEquals(Verdict.WRONG, meaning("mointian", "mountain"), "two edits")
    }

    @Test
    fun meaningSynonyms() {
        assertEquals(Verdict.CORRECT, meaning("chow down", "to eat", synonyms = listOf("chow down")))
        assertEquals(Verdict.WRONG, meaning("", "eat"))
    }

    @Test
    fun readingExactAfterRomajiAndKatakanaFolding() {
        val r = listOf("たべる")
        assertEquals(Verdict.CORRECT, AnswerChecker.checkReading("たべる", r).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkReading("taberu", r).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkReading("タベル", r).verdict)
        assertEquals(Verdict.WRONG, AnswerChecker.checkReading("たべた", r).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkReading("gakkou", listOf("がっこう")).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkReading("konnichiha", listOf("こんにちは")).verdict)
    }

    @Test
    fun readingKanjidicNotationAndWrongKind() {
        assertEquals(Verdict.CORRECT, AnswerChecker.checkReading("しょく", listOf("ショク", "ジキ")).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkReading("たべる", listOf("た.べる")).verdict)
        assertEquals(
            Verdict.WRONG_KIND,
            AnswerChecker.checkReading("たべる", readings = listOf("ショク"), otherReadings = listOf("た.べる")).verdict,
        )
    }

    @Test
    fun damerauLevenshtein() {
        assertEquals(0, AnswerChecker.damerauLevenshtein("abc", "abc"))
        assertEquals(1, AnswerChecker.damerauLevenshtein("abc", "acb"))
        assertEquals(1, AnswerChecker.damerauLevenshtein("abc", "ab"))
        assertEquals(3, AnswerChecker.damerauLevenshtein("", "abc"))
        assertEquals(2, AnswerChecker.damerauLevenshtein("kitten", "sittin"))
    }

    // --- F-35 ---

    @Test
    fun meaningsInAnyScriptMatch() {
        assertEquals(Verdict.CORRECT, AnswerChecker.checkMeaning("café", listOf("café")).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkMeaning("Café", listOf("café")).verdict) // decomposed é
        assertEquals(Verdict.CORRECT, AnswerChecker.checkMeaning("ねこ", listOf("cat"), synonyms = listOf("ねこ")).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkMeaning("猫", listOf("cat"), synonyms = listOf("猫")).verdict)
        assertEquals(Verdict.CORRECT, AnswerChecker.checkMeaning("naïve", listOf("naïve (adj.)")).verdict)
        assertEquals(Verdict.WRONG, AnswerChecker.checkMeaning("犬", listOf("cat"), synonyms = listOf("猫")).verdict)
    }

    @Test
    fun meaningNormalizationFoldsCaseAndWidth() {
        assertEquals("café", AnswerChecker.normalizeMeaning("ＣＡＦé"))
        assertEquals("eat", AnswerChecker.normalizeMeaning("ｔｏ　ｅａｔ"))
        assertEquals("ねこ", AnswerChecker.normalizeMeaning("「ねこ」（口語）"))
        assertEquals("well known", AnswerChecker.normalizeMeaning("well-known!"))
    }
}
