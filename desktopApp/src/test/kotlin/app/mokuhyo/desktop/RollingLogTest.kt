package app.mokuhyo.desktop

import app.mokuhyo.ai.AiCall
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-00b: the rolling log records AI calls without prompt text and rotates. */
class RollingLogTest {
    @Test
    fun recordsAndRotates() {
        val dir = Files.createTempDirectory("log").toFile()
        val log = RollingLog(dir, maxBytes = 400, keep = 2)
        repeat(30) { log.ai(AiCall("topic_turn", "on-device EuroLLM 9B Instruct", 1200L + it, "fallback", "the model took too long")) }
        assertTrue(log.tail(1).single().contains("task=topic_turn engine=on-device EuroLLM 9B Instruct ms=1229 outcome=fallback reason=\"the model took too long\""))
        assertTrue(File(dir, "mokuhyo.log.1").isFile && File(dir, "mokuhyo.log.2").isFile)
        assertEquals(false, File(dir, "mokuhyo.log.3").exists(), "keeps two old files")
        dir.deleteRecursively()
    }
}
