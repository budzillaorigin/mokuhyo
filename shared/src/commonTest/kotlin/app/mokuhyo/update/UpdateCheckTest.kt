package app.mokuhyo.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    private fun rel(tag: String, pre: Boolean = false, draft: Boolean = false) = Release(tag, tag, pre, draft, "https://example/$tag")

    @Test
    fun versionsOrderLikeSemver() {
        val v = listOf("0.1.0-dev", "0.1.0-rc.1", "0.1.0-rc.2", "0.1.0", "0.1.1", "0.2.0", "1.0.0").map { Version.parse(it)!! }
        assertEquals(v, v.shuffled().sorted())
        assertEquals(Version.parse("v1.2.3"), Version.parse("1.2.3+build.7"))
        assertNull(Version.parse("packs-2026-10-01"))
    }

    @Test
    fun picksNewestAppReleaseAndIgnoresPacksAndDrafts() {
        val list = listOf(rel("packs-2026-10-01", pre = true), rel("v0.1.0", pre = true), rel("v0.2.0", pre = true), rel("v0.3.0", draft = true))
        assertEquals("0.2.0", UpdateChecker.newest(list, "0.1.0")?.second)
        assertNull(UpdateChecker.newest(list, "0.2.0"))
        assertEquals("0.1.0", UpdateChecker.newest(list.take(2), "0.1.0-dev")?.second)
    }

    @Test
    fun stableBuildsIgnorePreReleases() {
        val list = listOf(rel("v1.0.0"), rel("v1.1.0-rc.1", pre = true))
        assertNull(UpdateChecker.newest(list, "1.0.0"))
        assertTrue(UpdateChecker.newest(list + rel("v1.1.0"), "1.0.0")?.second == "1.1.0")
    }
}
