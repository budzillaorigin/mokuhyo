package app.mokuhyo.numbers

import app.mokuhyo.lang.LanguageRegistry
import app.mokuhyo.lang.Languages
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BRIEF_PHASE8 N-02 gate: number rendering per language (cardinal, ordinal, time, date), a 500-item generated set
 * that validates, and the adaptive drill. No model, no network: the drill runs offline on any tier.
 */
class NumbersTest {
    private class Expect(val cardinal: String, val ordinal3: String, val time: Pair<String, String>, val date: Pair<String, String>)

    private val expected = mapOf(
        "ja" to Expect("千二百三十四", "第三", "14時30分" to "十四時三十分", "2026年10月3日" to "二千二十六年十月三日"),
        "ko" to Expect("천이백삼십사", "셋째", "14시 30분" to "십사 시 삼십 분", "2026년 10월 3일" to "이천이십육 년 시월 삼 일"),
        "de" to Expect("eintausendzweihundertvierunddreißig", "dritte", "14:30 Uhr" to "vierzehn Uhr dreißig",
            "3. Oktober 2026" to "der dritte Oktober zweitausendsechsundzwanzig"),
        "fr" to Expect("mille deux cent trente-quatre", "troisième", "14 h 30" to "quatorze heures trente",
            "3 octobre 2026" to "le trois octobre deux mille vingt-six"),
        "es" to Expect("mil doscientos treinta y cuatro", "tercero", "14:30" to "las catorce treinta",
            "3 de octubre de 2026" to "tres de octubre de dos mil veintiséis"),
        "pt-BR" to Expect("mil duzentos e trinta e quatro", "terceiro", "14h30" to "catorze horas e trinta minutos",
            "3 de outubro de 2026" to "três de outubro de dois mil e vinte e seis"),
        "ru" to Expect("одна тысяча двести тридцать четыре", "третий", "14:30" to "четырнадцать часов тридцать минут",
            "3 октября 2026 г." to "третье октября две тысячи двадцать шестого года"),
        "ar" to Expect("ألف ومائتان وأربعة وثلاثون", "الثالث", "14:30" to "الساعة أربعة عشر وثلاثون دقيقة", "3 أكتوبر 2026" to "ثلاثة أكتوبر ألفي وستة وعشرون"),
        "fa" to Expect("یک هزار و دویست و سی و چهار", "سوم", "14:30" to "ساعت چهارده و سی دقیقه", "3 اکتبر 2026" to "سوم اکتبر دو هزار و بیست و شش"),
        "id" to Expect("seribu dua ratus tiga puluh empat", "ketiga", "pukul 14.30" to "pukul empat belas lewat tiga puluh menit",
            "3 Oktober 2026" to "tiga Oktober dua ribu dua puluh enam"),
        "zh-Hans" to Expect("一千二百三十四", "第三", "14:30" to "十四点三十分", "2026年10月3日" to "二〇二六年十月三日"),
    )

    @Test
    fun renderingPerLanguage() {
        val registry = LanguageRegistry(null)
        assertEquals(Languages.all.map { it.code }.toSet(), expected.keys)
        expected.forEach { (lang, e) ->
            val g = registry.module(lang).numbers!!
            assertEquals(e.cardinal, g.cardinal(1234), "$lang cardinal")
            assertEquals(e.ordinal3, g.ordinal(3), "$lang ordinal")
            assertEquals(e.time, g.time(14, 30, clock24 = true), "$lang time")
            assertEquals(e.date, g.date(2026, 10, 3), "$lang date")
            assertTrue(g.cardinal(0).isNotBlank() && g.ordinal(1).isNotBlank(), lang)
            assertTrue(g.time(0, 0, true).second.isNotBlank() && g.time(23, 55, false).second.isNotBlank(), lang)
        }
    }

    @Test
    fun twelveHourForms() {
        assertEquals("午後2時" to "午後二時", IcuNumbers("ja").time(14, 0, clock24 = false))
        assertEquals("下午2:00" to "下午两点整", IcuNumbers("zh-Hans").time(14, 0, clock24 = false))
        assertEquals("오후 2시" to "오후 두 시", IcuNumbers("ko").time(14, 0, clock24 = false))
        assertEquals("2:00 p. m." to "las dos de la tarde", IcuNumbers("es").time(14, 0, clock24 = false))
    }

    @Test
    fun fiveHundredItemSetValidates() {
        expected.keys.forEach { lang ->
            val items = NumberItems(IcuNumbers(lang), Random(42)).set(500)
            assertEquals(500, items.size)
            assertEquals(500, items.map { it.id }.toSet().size, "$lang ids unique")
            assertEquals(NumberKind.entries.toSet(), items.map { it.kind }.toSet(), "$lang covers every kind")
            items.forEach { i ->
                assertTrue(i.spoken.isNotBlank() && i.written.isNotBlank() && i.answer.isNotBlank(), "$lang ${i.id}")
                assertTrue(i.accepts(i.answer), "$lang ${i.id} accepts its own answer")
                assertTrue(i.accepts(i.answer.lowercase().replace(":", " ")), "$lang ${i.id} loose answer")
            }
        }
    }

    @Test
    fun drillAdaptsToErrors() {
        val drill = NumberDrill(NumberItems(IcuNumbers("es"), Random(1)), listOf(NumberKind.TIME, NumberKind.GRID), Random(2))
        repeat(40) {
            val wrong = drill.current.kind == NumberKind.GRID
            drill.answer(if (wrong) "nope" else drill.current.answer)
        }
        assertTrue(drill.weight(NumberKind.GRID) > drill.weight(NumberKind.TIME))
        assertEquals(1.0, drill.weight(NumberKind.TIME))
        assertTrue(drill.errors.getValue(NumberKind.GRID) > 0 && NumberKind.TIME !in drill.errors)
    }

    @Test
    fun answersIgnoreScriptDigitsAndSeparators() {
        val item = NumberItem("x", NumberKind.TIME, "", "", "14:30")
        assertTrue(item.accepts("١٤:٣٠") && item.accepts("۱۴۳۰") && item.accepts("１４：３０") && item.accepts("14 30"))
    }
}
