package app.mokuhyo.desktop

import app.mokuhyo.ai.Tier
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.settings.Settings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppGraphTest {
    private fun graph(dir: java.io.File = Files.createTempDirectory("mokuhyo-test").toFile()) = AppGraph(AppDirs.ensure(dir))

    @Test
    fun createsLearnerOnceAndKeepsIt() {
        val dir = Files.createTempDirectory("mokuhyo-test").toFile()
        val first = graph(dir).also { it.close() }.learnerId
        val second = graph(dir).also { it.close() }.learnerId
        assertEquals(first, second)
        assertTrue(java.io.File(dir, "db/mokuhyo.sqlite").exists())
    }

    @Test
    fun firstRunChoicesPersist() {
        val dir = Files.createTempDirectory("mokuhyo-test").toFile()
        graph(dir).apply {
            assertFalse(firstRunDone)
            setChosenLanguages(listOf("es", "ar"))
            chooseTier(Tier.B)
            settings.put(Settings.Key.FIRST_RUN_DONE, "true")
            close()
        }
        graph(dir).apply {
            assertTrue(firstRunDone)
            assertEquals(listOf("es", "ar"), chosenLanguages())
            assertEquals("es", language.value)
            assertEquals(Tier.B, tier)
            assertEquals("eurollm-9b-instruct-q4km", chosenModel()?.id)
            close()
        }
    }

    @Test
    fun deviceAndLearnerSettingsAreScoped() {
        graph().apply {
            setLanguage("fa")
            chooseTier(Tier.A)
            val rows = db.settingsQueries.all().executeAsList().associate { it.key to it.scope }
            assertEquals("learner", rows["learner.language"])
            assertEquals("device", rows["device.model.tier"])
            close()
        }
    }

    @Test
    fun bundledManifestIsReadable() {
        graph().apply {
            assertEquals(10, manifest.models.size)
            assertNotNull(manifest.models.first().provenance)
            close()
        }
    }

    @Test
    fun smokeRendersAFrame() {
        val dir = Files.createTempDirectory("mokuhyo-smoke").toFile()
        assertEquals(0, Smoke.run(arrayOf("--smoke", "--allow-no-native", "--data-dir", dir.absolutePath)))
    }
}
