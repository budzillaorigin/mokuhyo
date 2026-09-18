package app.tsumugi.l10n

import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.ExamMode
import app.tsumugi.exam.jlpt.JlptItemType
import app.tsumugi.srs.Rating
import app.tsumugi.study.AnswerMode
import app.tsumugi.study.LearningPhase
import app.tsumugi.study.TodayBlockKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** BRIEF_V2 G-14: every shared-core label exists in English and Japanese. */
class LabelsTest {

    private fun both(label: (AppLocale) -> String, name: String) {
        val en = label(AppLocale.EN)
        val ja = label(AppLocale.JA)
        assertTrue(en.isNotBlank() && '.' !in en.take(1), "$name: missing English")
        assertTrue(ja.any { it.code > 0x3000 } || ja == en, "$name: Japanese label should be Japanese: $ja")
        assertTrue(!en.contains('{') && !ja.contains('{'), "$name: unfilled placeholder")
    }

    @Test
    fun everyEnumHasBothLanguages() {
        Stage.entries.forEach { s -> both({ Labels.stage(s, it) }, "stage $s") }
        ItemKind.entries.forEach { k -> both({ Labels.kind(k, it) }, "kind $k") }
        CardDirection.entries.forEach { d -> both({ Labels.direction(d, it) }, "direction $d") }
        AnswerMode.entries.forEach { m -> both({ Labels.mode(m, it) }, "mode $m") }
        TodayBlockKind.entries.forEach { b -> both({ Labels.block(b, it) }, "block $b") }
        LearningPhase.entries.forEach { p -> both({ Labels.phase(p, it) }, "phase $p") }
        Rating.entries.forEach { r -> both({ Labels.rating(r, it) }, "rating $r") }
        ExamKind.entries.forEach { k -> both({ Labels.examKind(k, it) }, "exam $k") }
        ExamMode.entries.forEach { m -> both({ Labels.examMode(m, it) }, "exam mode $m") }
        JlptItemType.entries.forEach { t -> both({ Labels.itemType(t, it) }, "item type $t") }
        for (key in Labels.keys) {
            assertNotEquals(key, Labels.text(key, AppLocale.EN, 1, 2), key)
            assertNotEquals(key, Labels.text(key, AppLocale.JA, 1, 2), key)
        }
    }

    @Test
    fun englishLabelsMatchTheEnumsOwnLabels() {
        Stage.entries.forEach { assertEquals(it.label, Labels.stage(it, AppLocale.EN)) }
        ItemKind.entries.forEach { assertEquals(it.label, Labels.kind(it, AppLocale.EN)) }
        LearningPhase.entries.forEach { assertEquals(it.label, Labels.phase(it, AppLocale.EN)) }
        ExamMode.entries.forEach { assertEquals(it.title, Labels.examMode(it, AppLocale.EN)) }
    }

    @Test
    fun placeholdersAndPlurals() {
        assertEquals("1 review waiting. A few minutes keeps your streak going.", Labels.text("reminder.body", AppLocale.EN, 1))
        assertEquals("3 reviews waiting. A few minutes keeps your streak going.", Labels.text("reminder.body", AppLocale.EN, 3))
        assertEquals("3件の復習が待っています。数分で連続記録を続けられます。", Labels.text("reminder.body", AppLocale.JA, 3))
        assertEquals("100件中40件（残りは明日）", Labels.text("today.reviews.capped", AppLocale.JA, 40, 100))
        assertEquals("no.such.key", Labels.text("no.such.key", AppLocale.EN))
    }

    @Test
    fun localeFromLanguageTag() {
        assertEquals(AppLocale.JA, AppLocale.of("ja-JP"))
        assertEquals(AppLocale.JA, AppLocale.of("ja"))
        assertEquals(AppLocale.EN, AppLocale.of("en-US"))
        assertEquals(AppLocale.EN, AppLocale.of(null))
        val before = L10n.locale
        L10n.setLanguage("ja_JP")
        assertEquals("復習", Labels.block(TodayBlockKind.REVIEWS))
        L10n.locale = before
    }
}
