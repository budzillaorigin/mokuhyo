package app.mokuhyo.lang

import kotlin.test.Test
import kotlin.test.assertEquals

class IcuSentencesTest {
    @Test
    fun icuSplitsEveryLaunchLanguageOnlyOnceTheNextSentenceHasBegun() {
        val cases = mapOf(
            "ja" to "はい、わかりました。お仕事は何ですか？", "zh-Hans" to "好的，谢谢。你在哪儿工作？", "ko" to "네, 알겠습니다. 어디에서 일하세요?",
            "ar" to "حسنا، شكرا. أين تعمل الآن؟", "fa" to "خیلی خوب. کجا کار می‌کنید؟", "ru" to "Хорошо, спасибо. Где вы работаете?",
            "es" to "¡Muy bien! ¿Dónde trabaja usted?", "fr" to "Très bien. Où travaillez-vous ?", "de" to "Sehr gut. Wo arbeiten Sie?",
            "pt-BR" to "Ótimo, obrigado. Onde você trabalha?", "id" to "Baik, terima kasih. Di mana Anda bekerja?",
        )
        cases.forEach { (lang, text) ->
            val s = IcuSentences(lang)
            assertEquals(2, s.ends(text, final = true).size, lang)
            assertEquals(1, s.ends(text, final = false).size, lang)
        }
    }

    @Test
    fun abbreviationsDontEndASentence() {
        assertEquals(1, IcuSentences("es").ends("El Sr. García trabaja aquí. ¿Y", final = false).size)
        assertEquals(1, IcuSentences("fr").ends("M. Dupont est là. Et", final = false).size)
    }
}
