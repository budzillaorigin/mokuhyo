package app.mokuhyo.lang

/**
 * Checks that model output meant to be in a language actually is (BRIEF §6.1 "target-language purity"): the text
 * contains the language's script and no runs of another script (English words in Arabic, hanja in Korean, kana in
 * Chinese, …). Upper-case acronyms (NATO, GPS) and digits are allowed. Mirrors tools/langtext.py foreign_script.
 */
object ScriptCheck {
    private fun isHan(c: Char) = c in '㐀'..'䶿' || c in '一'..'鿿'
    private fun isKana(c: Char) = c in '぀'..'ヿ'
    private fun isHangul(c: Char) = c in '가'..'힯' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
    private fun isCyrillic(c: Char) = c in 'Ѐ'..'ӿ'
    private fun isArabic(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'
    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in 'À'..'ɏ'

    private val latinLanguages = setOf("es", "fr", "de", "pt-BR", "id")

    /** True when [c] belongs to [language]'s own script. */
    fun ownScript(c: Char, language: String): Boolean = when (language) {
        "ja" -> isKana(c) || isHan(c) || c == 'ー' || c == '々'
        "zh-Hans" -> isHan(c)
        "ko" -> isHangul(c)
        "ru" -> isCyrillic(c)
        "ar", "fa" -> isArabic(c)
        else -> isLatin(c)
    }

    /** Words/characters from a foreign script found in [text] (empty = clean). */
    fun foreign(text: String, language: String): List<String> {
        val out = ArrayList<String>()
        if (language !in latinLanguages) {
            Regex("[A-Za-zÀ-ɏ]{3,}").findAll(text).map { it.value }.filterNot { w -> w.all { it.isUpperCase() } }.forEach { out += it }
        }
        for (c in text) {
            val bad = when (language) {
                "ko" -> isHan(c) || isKana(c)
                "zh-Hans" -> isKana(c) || isHangul(c)
                "ja" -> isHangul(c)
                "ru" -> isHan(c) || isKana(c) || isHangul(c) || isArabic(c)
                "ar", "fa" -> isHan(c) || isKana(c) || isHangul(c) || isCyrillic(c)
                else -> isHan(c) || isKana(c) || isHangul(c) || isArabic(c) || isCyrillic(c)
            }
            if (bad) out += c.toString()
        }
        return out.distinct()
    }

    /** Very common function words: enough to tell Latin-script languages from English in a sentence or two. */
    private val stopwords = mapOf(
        "en" to ("the and of to is are was were you your what did do does in on at for with this that it be have has will would not " +
            "a an about from by as or but into their they he she his her its which more some little than then there these those also only " +
            "very can could should may might when where how why who").split(" ").toSet(),
        "es" to "el la los las de del que y en un una es por con para no se su al lo como más pero sus le ya o fue muy qué usted está".split(" ").toSet(),
        "fr" to "le la les de des du et en un une est que qui pour pas dans sur au avec ce il elle nous vous je ne se son sa".split(" ").toSet(),
        "de" to "der die das und ist nicht ein eine zu den von mit sich des auf für im dem auch es an als wir sie ich haben wird".split(" ").toSet(),
        "pt-BR" to "o a os as de do da dos das que e em um uma é para com não no na por mais se você foi está ao".split(" ").toSet(),
        "id" to "yang dan di ini itu dengan untuk tidak dari dalam akan pada ke juga ada saya anda kami mereka adalah atau sudah".split(" ").toSet(),
    )

    /** For Latin-script languages: does [text] read as [language] rather than English? (null = can't tell) */
    fun latinLanguageOk(text: String, language: String): Boolean? {
        val own = stopwords[language] ?: return null
        val words = Regex("[\\p{L}']+").findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < 4) return null
        val ownHits = words.count { it in own }
        val englishHits = words.count { it in stopwords.getValue("en") }
        return ownHits >= englishHits
    }

    /** Problem description, or null when [text] is in [language]. */
    fun requireLanguage(field: String, text: String, language: String): String? {
        if (text.isBlank()) return "$field is empty"
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return "$field has no words"
        val own = letters.count { ownScript(it, language) }.toDouble() / letters.length
        if (own < 0.7) return "$field is not in ${Languages.of(language)?.nameEnglish ?: language}"
        val bad = foreign(text, language)
        if (bad.isNotEmpty()) return "$field contains foreign-script words: ${bad.take(4).joinToString(", ")}"
        if (latinLanguageOk(text, language) == false) return "$field is not in ${Languages.of(language)?.nameEnglish ?: language}"
        return null
    }

    /** Problem description if an English field is mostly another script. */
    fun requireEnglish(field: String, text: String): String? {
        if (text.isBlank()) return "$field is empty"
        val letters = text.filter { it.isLetter() }
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (letters.isNotEmpty() && latin < letters.length * 0.8) return "$field is not English"
        // Same script as Spanish, French, …: compare function words.
        val words = Regex("[\\p{L}']+").findAll(text.lowercase()).map { it.value }.toList()
        if (words.size >= 4) {
            val english = words.count { it in stopwords.getValue("en") }
            val other = stopwords.filterKeys { it != "en" }.values.maxOf { set -> words.count { it in set } }
            if (other > english) return "$field is not English"
        }
        return null
    }
}
