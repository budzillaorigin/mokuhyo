package app.mokuhyo.lexicon

import kotlin.test.Test
import kotlin.test.assertEquals

/** BRIEF_PHASE8 N-13 gate: the side-by-side view's rows for 1, 2 and 3 enabled languages (snapshot of the row model). */
class SideBySideTest {
    private fun term(id: String, domain: String, en: String, t: String, kind: String, src: String = "", radio: Boolean = false, priority: Int = 1) =
        TrackTerm(id = id, domain = domain, priority = priority, termEn = en, definitionEn = "d", term = t, termKind = kind, radioEnglish = radio,
            equivalents = listOf(Equivalent(t, kind, src, if (src.isEmpty()) "" else "1")), status = "checked", badge = if (src.isEmpty()) "unconfirmed-term" else "unreviewed")

    private val ja = Track(id = "cuas-base-defense", lang = "ja", title = "t", version = "1", terms = listOf(
        term("cuas-001", "cuas", "unmanned aircraft system (UAS)", "無人航空機システム", "calque", "ja-doj-2026-ja"),
        term("brev-002", "brevity", "BOGEY", "BOGEY", "loanword", radio = true),
        term("bd-001", "base-defense", "base defense operations center (BDOC)", "基地防衛作戦センター", "calque", priority = 2),
    ))
    private val fr = Track(id = "cuas-base-defense", lang = "fr", title = "t", version = "1", terms = listOf(
        term("cuas-001", "cuas", "unmanned aircraft system (UAS)", "système de drone", "native", "nato-aap-06-2019"),
        term("brev-002", "brevity", "BOGEY", "BOGEY", "loanword", radio = true),
    ))
    private val ar = Track(id = "cuas-base-defense", lang = "ar", title = "t", version = "1", terms = listOf(
        term("cuas-001", "cuas", "unmanned aircraft system (UAS)", "نظام الطائرات بدون طيار", "calque"),
        term("bd-001", "base-defense", "base defense operations center (BDOC)", "مركز عمليات الدفاع عن القاعدة", "calque", priority = 2),
    ))

    private fun snapshot(rows: List<SideBySide.Row>) = rows.joinToString("\n") { r ->
        r.termEn + " | " + r.cells.joinToString(" | ") { c -> c?.let { "${it.lang}:${it.term}(${it.kind}${if (it.radioEnglish) ",radio" else ""}${if (it.confirmed) ",✓" else ""})" } ?: "—" }
    }

    @Test
    fun oneLanguage() = assertEquals(
        "BOGEY | ja:BOGEY(loanword,radio)\n" +
            "unmanned aircraft system (UAS) | ja:無人航空機システム(calque,✓)\n" +
            "base defense operations center (BDOC) | ja:基地防衛作戦センター(calque)",
        snapshot(SideBySide.rows(listOf(ja))),
    )

    @Test
    fun twoLanguages() = assertEquals(
        "BOGEY | ja:BOGEY(loanword,radio) | fr:BOGEY(loanword,radio)\n" +
            "unmanned aircraft system (UAS) | ja:無人航空機システム(calque,✓) | fr:système de drone(native,✓)\n" +
            "base defense operations center (BDOC) | ja:基地防衛作戦センター(calque) | —",
        snapshot(SideBySide.rows(listOf(ja, fr))),
    )

    @Test
    fun threeLanguagesFilteredByDomainAndQuery() {
        assertEquals(
            "base defense operations center (BDOC) | ja:基地防衛作戦センター(calque) | — | ar:مركز عمليات الدفاع عن القاعدة(calque)",
            snapshot(SideBySide.rows(listOf(ja, fr, ar), domain = "base-defense")),
        )
        assertEquals(listOf("cuas-001"), SideBySide.rows(listOf(ja, fr, ar), query = "drone").map { it.id })
    }
}
