package app.mokuhyo.numbers

import com.ibm.icu.text.RuleBasedNumberFormat
import com.ibm.icu.util.ULocale

/**
 * [NumberGrammar] from ICU4J's spell-out rules (CLDR data) plus each language's way of telling the time and the date
 * (BRIEF_PHASE8 N-02). Written forms are what a native writes; spoken forms are spelled out for the voice service.
 */
class IcuNumbers(override val language: String) : NumberGrammar {
    private val locale = ULocale(
        when (language) { "pt-BR" -> "pt_BR"; "zh-Hans" -> "zh_Hans"; else -> language },
    )
    private val rbnf = RuleBasedNumberFormat(locale, RuleBasedNumberFormat.SPELLOUT)
    private val sets = rbnf.ruleSetNames.toSet()

    private fun spell(n: Long, set: String? = null): String =
        (if (set != null && set in sets) rbnf.format(n, set) else rbnf.format(n)).replace("­", "")

    override fun cardinal(n: Long): String = when (language) {
        "ko" -> spell(n, "%spellout-cardinal-sinokorean")
        "de" -> spell(n, "%spellout-cardinal-s")
        "es" -> spell(n, "%spellout-numbering")
        "fr", "pt-BR" -> spell(n, "%spellout-cardinal-masculine")
        "ar" -> spell(n, "%spellout-cardinal-masculine")
        else -> spell(n, "%spellout-cardinal")
    }

    override fun ordinal(n: Long): String = when (language) {
        "ja" -> "第" + spell(n, "%spellout-cardinal")
        "zh-Hans" -> "第" + spell(n, "%spellout-cardinal")
        "ko" -> spell(n, "%spellout-ordinal-native")
        "de" -> spell(n, "%spellout-ordinal")
        "fr", "es", "pt-BR", "ru", "ar" -> spell(n, "%spellout-ordinal-masculine")
        "fa" -> persianOrdinal(n)
        else -> spell(n, "%spellout-ordinal")
    }

    override val point: String get() = POINT[language] ?: "point"

    override fun time(hour: Int, minute: Int, clock24: Boolean): Pair<String, String> {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val pm = hour >= 12
        val mm = minute.toString().padStart(2, '0')
        val hh = hour.toString().padStart(2, '0')
        val m = minute.toLong()
        return when (language) {
            "ja" -> if (clock24) "${hour}時${mm}分" to "${cardinal(hour.toLong())}時" + (if (minute == 0) "" else "${cardinal(m)}分")
                else "${if (pm) "午後" else "午前"}${h12}時" + (if (minute == 0) "" else "${mm}分") to "${if (pm) "午後" else "午前"}${cardinal(h12.toLong())}時" + (if (minute == 0) "" else "${cardinal(m)}分")
            "zh-Hans" -> {
                fun hourWord(x: Int) = if (x == 2) "两" else cardinal(x.toLong())
                if (clock24) "${hour}:${mm}" to "${hourWord(hour)}点" + (if (minute == 0) "整" else "${cardinal(m)}分")
                else "${if (pm) "下午" else "上午"}${h12}:${mm}" to "${if (pm) "下午" else "上午"}${hourWord(h12)}点" + (if (minute == 0) "整" else "${cardinal(m)}分")
            }
            "ko" -> {
                val native = spell(h12.toLong(), "%spellout-cardinal-native-attributive")
                if (clock24) "${hour}시 ${minute}분" to "${spell(hour.toLong(), "%spellout-cardinal-sinokorean")} 시" + (if (minute == 0) " 정각" else " ${cardinal(m)} 분")
                else "${if (pm) "오후" else "오전"} ${h12}시" + (if (minute == 0) "" else " ${minute}분") to "${if (pm) "오후" else "오전"} $native 시" + (if (minute == 0) "" else " ${cardinal(m)} 분")
            }
            "de" -> if (clock24) "$hh:$mm Uhr" to "${cardinal(hour.toLong())} Uhr" + (if (minute == 0) "" else " ${cardinal(m)}")
                else "$h12:$mm Uhr ${if (pm) "nachmittags" else "vormittags"}" to "${cardinal(h12.toLong())} Uhr" + (if (minute == 0) "" else " ${cardinal(m)}") + if (pm) " nachmittags" else " vormittags"
            "fr" -> {
                fun heures(x: Int) = if (x == 1) "une heure" else "${spell(x.toLong(), "%spellout-cardinal-feminine")} heures"
                if (clock24) "${hour} h $mm" to (if (hour == 0) "minuit" else heures(hour)) + (if (minute == 0) "" else " ${cardinal(m)}")
                else "$h12 h $mm" to heures(h12) + (if (minute == 0) "" else " ${cardinal(m)}") + if (pm) " de l'après-midi" else " du matin"
            }
            "es" -> {
                fun hora(x: Int) = if (x == 1) "la una" else "las ${spell(x.toLong(), "%spellout-cardinal-feminine")}"
                if (clock24) "$hh:$mm" to hora(hour) + (if (minute == 0) " en punto" else " ${cardinal(m)}")
                else "$h12:$mm ${if (pm) "p. m." else "a. m."}" to hora(h12) + (if (minute == 0) "" else " y ${cardinal(m)}") + if (pm) " de la tarde" else " de la mañana"
            }
            "pt-BR" -> {
                fun horas(x: Int) = "${spell(x.toLong(), "%spellout-cardinal-feminine")} ${if (x == 1) "hora" else "horas"}"
                if (clock24) "${hour}h$mm" to horas(hour) + (if (minute == 0) "" else " e ${cardinal(m)} ${if (minute == 1) "minuto" else "minutos"}")
                else "${h12}h$mm" to horas(h12) + (if (minute == 0) "" else " e ${cardinal(m)}") + if (pm) " da tarde" else " da manhã"
            }
            "ru" -> { // the 24-hour clock is the norm in Russian military use, so the 12-hour form reads the same
                val hWord = ruPlural(hour.toLong(), "час", "часа", "часов")
                val mWord = ruPlural(m, "минута", "минуты", "минут")
                "$hh:$mm" to "${spell(hour.toLong(), "%spellout-cardinal-masculine")} $hWord" +
                    (if (minute == 0) " ровно" else " ${spell(m, "%spellout-cardinal-feminine")} $mWord")
            }
            "ar" -> "$hh:$mm" to "الساعة ${spell(hour.toLong(), "%spellout-cardinal-feminine")}" + (if (minute == 0) "" else " و${spell(m, "%spellout-cardinal-feminine")} دقيقة")
            "fa" -> "$hh:$mm" to "ساعت ${cardinal(hour.toLong())}" + (if (minute == 0) "" else " و ${cardinal(m)} دقیقه")
            "id" -> "pukul $hh.$mm" to "pukul ${cardinal(hour.toLong())}" + (if (minute == 0) "" else " lewat ${cardinal(m)} menit")
            else -> "$hh:$mm" to "${cardinal(hour.toLong())} ${cardinal(m)}"
        }
    }

    override fun date(year: Int, month: Int, day: Int): Pair<String, String> {
        val y = year.toLong()
        val d = day.toLong()
        return when (language) {
            "ja" -> "${year}年${month}月${day}日" to "${cardinal(y)}年${cardinal(month.toLong())}月${cardinal(d)}日"
            "zh-Hans" -> "${year}年${month}月${day}日" to "${year.toString().map { ZH_DIGITS[it - '0'] }.joinToString("")}年${cardinal(month.toLong())}月${cardinal(d)}日"
            "ko" -> "${year}년 ${month}월 ${day}일" to "${cardinal(y)} 년 ${KO_MONTHS[month - 1]} ${cardinal(d)} 일"
            "de" -> "$day. ${DE_MONTHS[month - 1]} $year" to "der ${spell(d, "%spellout-ordinal")} ${DE_MONTHS[month - 1]} ${cardinal(y)}"
            "fr" -> "$day ${FR_MONTHS[month - 1]} $year" to "le ${if (day == 1) "premier" else cardinal(d)} ${FR_MONTHS[month - 1]} ${spell(y, "%spellout-numbering-year")}"
            "es" -> "$day de ${ES_MONTHS[month - 1]} de $year" to "${if (day == 1) "primero" else cardinal(d)} de ${ES_MONTHS[month - 1]} de ${cardinal(y)}"
            "pt-BR" -> "$day de ${PT_MONTHS[month - 1]} de $year" to "${if (day == 1) "primeiro" else cardinal(d)} de ${PT_MONTHS[month - 1]} de ${cardinal(y)}"
            "ru" -> "$day ${RU_MONTHS_GEN[month - 1]} $year г." to "${spell(d, "%spellout-ordinal-neuter")} ${RU_MONTHS_GEN[month - 1]} ${spell(y, "%spellout-ordinal-masculine-genitive")} года"
            "ar" -> "$day ${AR_MONTHS[month - 1]} $year" to "${spell(d, "%spellout-cardinal-masculine")} ${AR_MONTHS[month - 1]} ${spell(y, "%spellout-cardinal-masculine")}"
            "fa" -> "$day ${FA_MONTHS[month - 1]} $year" to "${persianOrdinal(d)} ${FA_MONTHS[month - 1]} ${cardinal(y)}"
            "id" -> "$day ${ID_MONTHS[month - 1]} $year" to "${cardinal(d)} ${ID_MONTHS[month - 1]} ${cardinal(y)}"
            else -> "$year-$month-$day" to "${cardinal(d)} ${cardinal(month.toLong())} ${cardinal(y)}"
        }
    }

    private fun ruPlural(n: Long, one: String, few: String, many: String): String {
        val n100 = n % 100
        val n10 = n % 10
        return when {
            n100 in 11..14 -> many
            n10 == 1L -> one
            n10 in 2..4 -> few
            else -> many
        }
    }

    /** Persian ordinals: یکم, دوم, سوم, … سی‌ام, چهلم (ICU has no Persian ordinal rules). */
    private fun persianOrdinal(n: Long): String {
        if (n == 1L) return "یکم"
        val c = spell(n, "%spellout-cardinal")
        return when {
            c.endsWith("سه") -> c.dropLast(2) + "سوم"
            c.endsWith("ی") -> c + "‌ام"
            else -> c + "م"
        }
    }

    companion object {
        private val POINT = mapOf("ja" to "点", "zh-Hans" to "点", "ko" to "점", "de" to "Komma", "fr" to "virgule", "es" to "punto", "pt-BR" to "vírgula",
            "ru" to "точка", "ar" to "فاصلة", "fa" to "ممیز", "id" to "koma")
        private const val ZH_DIGITS = "〇一二三四五六七八九"
        private val KO_MONTHS = listOf("일 월", "이 월", "삼 월", "사 월", "오 월", "유월", "칠 월", "팔 월", "구 월", "시월", "십일 월", "십이 월")
        private val DE_MONTHS = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
        private val FR_MONTHS = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")
        private val ES_MONTHS = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
        private val PT_MONTHS = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
        private val RU_MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
        private val AR_MONTHS = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
        private val FA_MONTHS = listOf("ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن", "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر")
        private val ID_MONTHS = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
    }
}
