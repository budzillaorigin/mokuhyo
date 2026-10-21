@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package app.mokuhyo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import app.mokuhyo.backup.Backup
import app.mokuhyo.backup.Bundle
import app.mokuhyo.desktop.ui.*
import app.mokuhyo.desktop.ui.exam.SkillScreen
import app.mokuhyo.desktop.ui.exam.SessionPreview
import app.mokuhyo.exam.Skill
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.lang.Languages
import app.mokuhyo.net.*
import app.mokuhyo.opi.*
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.srs.ReviewService
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/** Synthetic data only. Defect probes assert the observed defect, not release acceptance. */
class FeatureQcTest {
    private val out = File(System.getenv("QC_OUT") ?: "/tmp/mokuhyo-feature-qc/probes").apply { mkdirs() }
    private fun app() = AppGraph(AppDirs.ensure(Files.createTempDirectory("feature-qc-").toFile()))
    private fun backup(a: AppGraph) = Backup(File(a.dataDir, "db/mokuhyo.sqlite"), a.db, a.dataDir, a.learnerId, "0.4.0")
    private val packs = Bundle.PacksList(listOf("es"), emptyList(), emptyList())
    private fun note(s: String) { println("FEATURE-QC $s"); File(out, "observations.log").appendText(s + "\n") }

    @Test fun ungradedReviewItemLosesItsScheduleOnImport() {
        val a = app(); val b = app()
        try {
            val id = a.reviews.add(a.learnerId, "es", ReviewService.Kind.WORD, "qc:word", "hola", "hello")
            assertEquals(1L, a.reviews.dueCount(a.learnerId, "es"))
            val f = File(out, "ungraded.mokuhyo")
            backup(a).export(f, listOf("es"), packs)
            backup(b).importBundle(f)
            assertEquals(1L, b.reviews.total(b.learnerId, "es"))
            assertNull(b.reviews.card(id))
            assertEquals(0L, b.reviews.dueCount(b.learnerId, "es"))
            note("ungraded backup: before due=1; imported total=1, card=null, due=0")
        } finally { a.close(); b.close() }
    }

    @Test fun selfRatingReplacesFactAndDoesNotMergeIntoEarlierImport() {
        val a = app(); val b = app()
        try {
            val id = a.conversations.newId()
            val s = StoredConversation(listOf(Turn(Speaker.LEARNER, "Synthetic answer.")))
            fun save(r: OpiRating?) = a.conversations.save(id, a.learnerId, "es", "OPI", null, 0, s, r, emptyList(), null)
            save(null)
            val f = File(out, "before-rating.mokuhyo")
            backup(a).export(f, listOf("es"), packs); backup(b).importBundle(f)
            save(OpiRating("2", selfRated = true))
            assertEquals(1, a.db.historyQueries.conversationsAll().executeAsList().size)
            backup(a).export(f, listOf("es"), packs); backup(b).importBundle(f)
            assertEquals("2", a.conversations.rating(a.conversations.get(id)!!)!!.estimate)
            assertNull(b.conversations.rating(b.conversations.get(id)!!))
            note("self-rating: original row replaced (row count=1); source ILR=2, second import ILR=null")
        } finally { a.close(); b.close() }
    }

    private inner class View(content: @Composable () -> Unit, width: Int = 1280, height: Int = 1000, density: Float = 1f) : AutoCloseable {
        val scene = ImageComposeScene(width, height, Density(density)) { MokuhyoTheme { content() } }
        var frame = 0L
        fun pump(n: Int = 8) { repeat(n) { scene.render(frame++ * 16_000_000L); Thread.sleep(25) } }
        fun nodes() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        fun until(label: String, predicate: () -> Boolean) {
            repeat(160) { pump(1); if (predicate()) return }
            error("$label: ${texts()}")
        }
        fun click(label: String) {
            until(label) { label in texts() }
            var n: SemanticsNode? = nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == label } }
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            assertNotNull(n, "click action for $label").config[SemanticsActions.OnClick].action!!.invoke()
            pump()
        }
        fun shot(name: String) {
            pump()
            File(out, "$name.png").writeBytes(scene.render(frame++ * 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        override fun close() = scene.close()
    }

    @Test fun allScriptsAndNineDestinationsRenderOffline() {
        System.setProperty("java.awt.headless", "true")
        NetworkPolicy.disabled = true
        val a = app()
        try {
            a.setLanguage("es")
            for (d in Destination.entries) View({ Shell(a, start = d) }, 1024, 700).use { v ->
                v.pump(20); v.shot("shell-${d.name.lowercase()}")
                note("render ${d.name}: ${v.texts().take(20).joinToString(" / ")}")
            }
            for (lang in Languages.all.map { it.code }) {
                a.setLanguage(lang)
                View({ SessionPreview(a, Skill.READING, "0+", answer = false) }, 1024, 700).use { v ->
                    v.pump(20); v.shot("reading-$lang"); assertTrue(v.texts().any { it.contains("Question 1") })
                }
            }
            a.setLanguage("es")
            for (density in listOf(1.5f, 2f)) View({ Shell(a) }, (1024*density).toInt(), (700*density).toInt(), density).use { it.shot("home-scale-$density") }
            note("rendered 11 scripts, nine destinations, 1024x700 logical at 1x/1.5x/2x; headless, no physical input/audio claim")
        } finally { a.close(); NetworkPolicy.disabled = false }
    }

    @Test fun readingPracticeAndTimedResumeAcrossThreeScripts() {
        System.setProperty("java.awt.headless", "true")
        NetworkPolicy.disabled = true
        try { for (lang in listOf("ja", "ar", "es")) {
            val a = app(); a.setLanguage(lang)
            try {
                View({ SkillScreen(a, Skill.READING) }).use { v ->
                    v.click("Start practice"); v.click("1")
                    assertTrue(v.texts().any { it == "Correct" || it.startsWith("Not quite") || it.startsWith("Correct") }, v.texts().toString())
                    v.click("Finish")
                    v.until("practice result") { v.texts().any { it.contains("Unofficial", true) } }
                    v.shot("practice-result-$lang")
                }
                assertEquals(1, a.history.attempts(a.learnerId, lang).size)
                View({ SkillScreen(a, Skill.READING) }).use { v ->
                    v.click("Test"); v.click("Start test"); v.click("1")
                    assertNotNull(a.db.historyQueries.inProgress(a.learnerId, lang).executeAsOneOrNull())
                }
                val dir = a.dataDir; a.close()
                val b = AppGraph(AppDirs.ensure(dir))
                try {
                    View({ SkillScreen(b, Skill.READING) }).use { v ->
                        v.click("Test"); v.click("Resume")
                        v.click("Submit test"); v.click("Keep going")
                        v.click("Submit test"); v.click("Submit")
                        v.until("test result") { v.texts().any { it.contains("Unofficial", true) } }
                        v.shot("test-result-$lang")
                    }
                    assertEquals(2, b.history.attempts(b.learnerId, lang).size)
                    assertTrue(b.history.estimates(b.learnerId, lang).all { it.provisional })
                    assertEquals(0, b.history.attempts(b.learnerId, if (lang == "es") "ar" else "es").size)
                    note("$lang: real reading UI practice→feedback→save, timed test→close→reopen→resume→cancel submit→submit; provisional, per-language isolation")
                } finally { b.close() }
            } finally { runCatching { a.close() } }
        } } finally { NetworkPolicy.disabled = false }
    }

    @Test fun historyShowsScoreWithoutRequiredDisclaimer() {
        val a = app()
        try {
            a.setLanguage("es")
            a.conversations.save("qc-score", a.learnerId, "es", "OPI_TEST", null, 0, StoredConversation(emptyList()), OpiRating("2", engine = "synthetic-fixture"), emptyList(), null)
            View({ HistoryList(a) }).use { v ->
                v.pump(); v.click("Open")
                assertTrue(v.texts().any { "ILR 2" in it })
                assertFalse(v.texts().any { it.contains("unofficial", true) })
                v.shot("history-missing-disclaimer")
                note("History score and detail: ILR 2 displayed; no unofficial-practice disclaimer in semantics")
            }
        } finally { a.close() }
    }

    @Test fun lexiconLanguageSwitchKeepsPreviousLanguagesDrills() {
        val a = app()
        try {
            a.setLanguage("es")
            View({ app.mokuhyo.desktop.ui.lexicon.LexiconScreen(a) }).use { v ->
                v.click("Drills")
                val spanish = a.lexicon("es")!!.drills.map { it.prompt }.first { it in v.texts() }
                a.setLanguage("ar"); v.pump(20)
                assertTrue(v.texts().any { it.contains("Arabic") })
                assertTrue(spanish in v.texts(), v.texts().toString())
                assertFalse(a.lexicon("ar")!!.drills.any { it.prompt == spanish })
                v.shot("lexicon-language-stale")
                note("Lexicon es→ar while Drills is open: Arabic heading with previous Spanish drill: $spanish")
            }
        } finally { a.close() }
    }

    @Test fun firstRunSkipAndReviewGradeUndo() {
        val a = app()
        try {
            View({ FirstRun(a) {} }).use { v ->
                v.click("Continue"); v.click("Continue")
                v.until("hardware scan") { v.texts().any { it.startsWith("Memory:") } }
                v.click("Continue"); v.click("Reading/Listening only for now"); v.click("Finish")
                assertTrue(a.firstRunDone)
                assertTrue(a.settings.bool(app.mokuhyo.settings.Settings.Key.READING_ONLY))
                note("first-run real UI: welcome→languages→hardware→skip model→audio-check page→Finish; no audio-device claim")
            }
            a.setLanguage("es")
            val id = a.reviews.add(a.learnerId, "es", ReviewService.Kind.WORD, "qc:review", "hola", "hello")
            View({ ReviewScreen(a) }).use { v ->
                v.click("Show answer (Space)"); v.click("3 Good")
                v.until("empty queue") { "Nothing due" in v.texts() }
                assertEquals(0L, a.reviews.dueCount(a.learnerId, "es"))
                v.click("Undo last answer")
                assertEquals(1L, a.reviews.dueCount(a.learnerId, "es"))
                assertEquals(0, a.reviews.card(id)!!.reps)
                note("Review real UI: reveal→Good→empty queue→Undo restores due card; review fact retained as tombstone")
            }
        } finally { a.close() }
    }

    @Test fun realHttpEngineIsBlockedBeforeSocketConnection() = runBlocking {
        val accepted = AtomicInteger()
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) runCatching { server.accept().use { s ->
                accepted.incrementAndGet()
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
            } }
        }
        val client = mokuhyoHttpClient(Java.create(), NetTimeouts.API)
        try {
            NetworkPolicy.disabled = false
            val url = "http://127.0.0.1:${server.localPort}/qc"
            client.get(url); assertEquals(1, accepted.get())
            NetworkPolicy.disabled = true
            assertFailsWith<NetworkDisabledException> { client.get(url) }
            Thread.sleep(200); assertEquals(1, accepted.get())
            note("real Java HTTP engine: positive control accepted=1; --no-network policy request refused, accepted remains=1")
        } finally { client.close(); NetworkPolicy.disabled = false; server.close(); worker.join(1000) }
    }

    @Test fun listeningSpaceShortcutIsAdvertisedButUnhandled() {
        val qcVoice = app.mokuhyo.lang.VoiceSpec("qc-synthetic", "es", "female", "piper", "CC0")
        SpeechProvider.set(object : app.mokuhyo.lang.SpeechOutput {
            override fun voicesFor(language: String) = listOf(qcVoice)
            override suspend fun synthesize(text: String, language: String, voice: app.mokuhyo.lang.VoiceSpec?, speed: Double) =
                app.mokuhyo.lang.SpokenAudio(app.mokuhyo.speech.AudioIO.wav(ShortArray(1600) { (it % 50 * 400).toShort() }), voice ?: qcVoice)
        })
        val a = app()
        try {
            a.setLanguage("es")
            View({ SessionPreview(a, Skill.LISTENING, "0+", answer = false) }).use { v ->
                v.until("play control") { "▶ Play" in v.texts() }
                fun key(k: androidx.compose.ui.input.key.Key): Boolean {
                    val event = androidx.compose.ui.input.key.KeyEvent(k, androidx.compose.ui.input.key.KeyEventType.KeyDown)
                    val handled = v.scene.sendKeyEvent(event)
                    v.pump()
                    return handled
                }
                assertTrue(v.texts().any { it.contains("Space play/pause") })
                assertFalse(key(androidx.compose.ui.input.key.Key.Spacebar))
                assertFalse(v.texts().any { it.startsWith("Couldn't start playback") })
                assertTrue(key(androidx.compose.ui.input.key.Key.One), "positive control: same focused scene handles choice key")
                assertTrue(v.texts().any { it.startsWith("Correct") || it.startsWith("Not quite") })
                v.click("▶ Play")
                v.until("output error") { v.texts().any { it.startsWith("Couldn't start playback") } }
                v.shot("listening-keyboard")
                note("Listening keyboard: focused scene handles 1; Space unhandled despite advertised shortcut; mouse action reaches real playback and reports missing output. Synthetic WAV; not a speech pass.")
            }
        } finally { a.close(); SpeechProvider.set(null) }
    }

    @Test fun privacyCopyOmitsLanInferenceAndUrlImports() {
        val a = app()
        try {
            assertFalse(a.settings.bool(app.mokuhyo.settings.Settings.Key.UPDATE_CHECK))
            View({ SettingsScreen(a) }).use { v ->
                v.click("Privacy & updates")
                assertTrue(v.texts().any { it.contains("goes online only") && it.contains("on this computer") })
                assertFalse(v.texts().any { it.contains("lexicon", true) || it.contains("local network", true) })
                v.shot("privacy-network-copy")
                note("Privacy with network policy enabled: claims only model/update/localhost detection; omits lexicon URL import and LAN inference. Update check default=false.")
            }
        } finally { a.close() }
    }
}
