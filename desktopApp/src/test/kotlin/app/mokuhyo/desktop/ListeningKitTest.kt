package app.mokuhyo.desktop

import app.mokuhyo.opi.OpiPack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListeningKitTest {
    @Test
    fun theKitsPromptsSpanEveryPhaseAndAreStable() {
        val pack = OpiPack.parse(generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "content/packs/es/opi.json") }.first { it.isFile }.readText())
        val picked = ListeningKit.pick(pack, 10)
        assertEquals(10, picked.size)
        assertEquals(picked, ListeningKit.pick(pack, 10), "the same prompts every time, so engines are compared on the same text")
        assertTrue(picked.any { it.first.contains("-rp-") }, "a role-play opening is included")
        val phaseOf = pack.questions.associate { it.id to it.phase }
        listOf("warmup", "level_check", "probe", "winddown").forEach { p -> assertTrue(picked.any { phaseOf[it.first] == p }, "$p: $picked") }
    }
}
