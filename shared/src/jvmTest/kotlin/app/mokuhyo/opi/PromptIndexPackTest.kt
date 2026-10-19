package app.mokuhyo.opi

import app.mokuhyo.testing.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptIndexPackTest {
    /** es (Piper) and ja (VOICEVOX) are the N5 pilot: their indexes are committed; clips are rendered at build time. */
    @Test
    fun theShippedPilotIndexesAreUpToDateWithTheirBanks() {
        for (lang in listOf("es", "ja")) {
            val idx = PromptAudioIndex.parse(repoFile("content/packs/$lang/prompts.json").readText())
            val pack = OpiPack.parse(repoFile("content/packs/$lang/opi.json").readText())
            assertEquals(PromptAudioIndex.build(pack, idx), idx, "$lang/prompts.json is stale: run Mokuhyo --prompt-audio --language $lang")
            assertTrue(idx.prompts.size == pack.questions.size + pack.rolePlays.size)
        }
    }
}
