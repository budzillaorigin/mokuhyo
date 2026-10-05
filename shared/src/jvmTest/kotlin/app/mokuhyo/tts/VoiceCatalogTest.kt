package app.mokuhyo.tts

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** BRIEF_PHASE8 N-07: speakers of one multi-speaker model share its files through the manifest's "model" field. */
class VoiceCatalogTest {
    @Test
    fun sharedModelServesSeveralSpeakers() {
        val dir = Files.createTempDirectory("voices").toFile()
        File(dir, "de_DE-mls-medium").apply { mkdirs() }.let { d ->
            File(d, "de_DE-mls-medium.onnx").writeBytes(ByteArray(4))
            File(d, "de_DE-mls-medium.onnx.json").writeText("""{"inference":{"length_scale":1.1}}""")
        }
        File(dir, "manifest.json").writeText("""{"voices":[
            {"id":"de_DE-mls-medium-f12","model":"de_DE-mls-medium","language":"de","gender":"female","speaker":12,"license":"CC-BY-4.0"},
            {"id":"de_DE-mls-medium-m6","model":"de_DE-mls-medium","language":"de","gender":"male","speaker":6,"license":"CC-BY-4.0"},
            {"id":"de_DE-missing","language":"de","gender":"male","license":"CC0-1.0"}]}""")
        val cat = VoiceCatalog.fromInstallDir(dir)
        assertEquals(listOf("de_DE-mls-medium-f12" to 12, "de_DE-mls-medium-m6" to 6), cat.voices.map { it.spec.id to it.spec.speaker })
        assertEquals(1, cat.voices.map { it.model }.toSet().size)
        dir.deleteRecursively()
    }
}
