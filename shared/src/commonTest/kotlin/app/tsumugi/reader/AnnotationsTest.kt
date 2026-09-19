package app.tsumugi.reader

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.recordings.ImageStore
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.Verdict
import app.tsumugi.sync.FakeSyncServer
import app.tsumugi.sync.SyncEngine
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** BRIEF_V2 §6.4: annotations (and their sync), screenshot pages, the per-document word list and drill, guides. */
class AnnotationsTest {
    private val clock = TestClock()

    private inner class Device(val id: String, server: FakeSyncServer = FakeSyncServer()) {
        val driver = inMemoryDriver(TsumugiDatabase.Schema)
        val db = TsumugiDatabase(driver)
        val repo = ReaderRepository(db, clock)
        val annotations = ReaderAnnotations(db, clock)
        val engine = SyncEngine(driver, db, SrsRepository(db, id, clock), id, server, null, clock)
    }

    private val body = "吾輩は猫である。名前はまだ無い。\nどこで生れたかとんと見当がつかぬ。"

    @Test
    fun annotationsPersistByOffsetsAndReanchorWhenTheTextMoves() = runTest {
        val d = Device("a")
        val id = d.repo.save(ImportedText("猫", body, SourceKind.PASTE))
        val doc = d.repo.document(id)!!
        val box = d.annotations.add(doc, AnnotationKind.BOX, 0, 3)
        val note = d.annotations.add(doc, AnnotationKind.NOTE, 8, 10, note = "名前 = name")
        d.annotations.add(doc, AnnotationKind.GRAMMAR, 3, 8, grammarPointId = "n5-desu")
        assertEquals("吾輩は", box.quote)
        assertEquals(listOf(0, 3, 8), d.annotations.forDocument(doc).map { it.start })

        d.annotations.update(note.id, note = "名前: name", color = "yellow")
        assertEquals("yellow", d.annotations.annotation(note.id)!!.color)
        d.annotations.delete(box.id)
        assertNull(d.annotations.annotation(box.id))
        assertEquals(2, d.annotations.forDocument(doc).size)

        // Same text elsewhere: the offsets move with it; text that's gone is detached, not drawn in the wrong place.
        assertEquals(4 until 7, ReaderAnnotations.anchor("はじめに吾輩は猫", 0, 3, "吾輩は"))
        assertNull(ReaderAnnotations.anchor("別の文章", 0, 3, "吾輩は"))
    }

    @Test
    fun annotationsSyncPerRowOnTheSameTextAcrossDevices() = runTest {
        val server = FakeSyncServer()
        val a = Device("device-a", server)
        val b = Device("device-b", server)
        // Each device has its own copy of the same web article (reader documents don't sync).
        val docA = a.repo.document(a.repo.save(ImportedText("記事", body, SourceKind.URL, "https://example.jp/a")))!!
        val docB = b.repo.document(b.repo.save(ImportedText("記事", body, SourceKind.URL, "https://example.jp/a")))!!
        assertEquals(a.annotations.docKey(docA), b.annotations.docKey(docB))
        assertEquals("url:https://example.jp/a", a.annotations.docKey(docA))

        val hl = a.annotations.add(docA, AnnotationKind.HIGHLIGHT, 3, 4, color = "pink")
        a.engine.sync(); b.engine.sync()
        assertEquals(listOf("猫"), b.annotations.forDocument(docB).map { it.quote })

        // Concurrent edits: the later one wins; a delete (tombstone) reaches the other device.
        clock.advance(1.minutes)
        b.annotations.update(hl.id, note = "cat")
        clock.advance(1.minutes)
        a.annotations.update(hl.id, note = "ねこ")
        b.engine.sync(); a.engine.sync(); b.engine.sync()
        assertEquals("ねこ", b.annotations.annotation(hl.id)!!.note)
        assertEquals("ねこ", a.annotations.annotation(hl.id)!!.note)
        clock.advance(1.minutes)
        b.annotations.delete(hl.id)
        b.engine.sync(); a.engine.sync()
        assertTrue(a.annotations.forDocument(docA).isEmpty())

        // Pasted text without a URL is keyed by its content.
        assertTrue(ReaderAnnotations.docKeyOf(null, body).startsWith("sha256:"))
        assertEquals(ReaderAnnotations.docKeyOf(null, body), ReaderAnnotations.docKeyOf("", body))
    }

    @Test
    fun screenshotsBecomeADocumentWithPageImages() = runTest {
        val d = Device("a")
        val fs = FakeFileSystem()
        val images = ImageStore(d.db, fs, "/data".toPath(), "a", clock)
        val import = ScreenshotImport(d.db, d.repo, images)
        val p1 = import.screenshotFile()
        val p2 = import.screenshotFile("png")
        fs.write(p1.path.toPath()) { write(ByteArray(10) { 1 }) }
        fs.write(p2.path.toPath()) { write(ByteArray(10) { 2 }) }
        val id = import.importPages(listOf(ScreenshotPage(p1, "吾輩は猫である。"), ScreenshotPage(p2, " 名前はまだ無い。 ")))
        val doc = d.repo.document(id)!!
        assertEquals(SourceKind.SCREENSHOT, doc.kind)
        assertEquals("吾輩は猫である。\n\n名前はまだ無い。", doc.body)
        assertEquals("吾輩は猫である。", doc.title)
        val pages = import.pageImages(id)
        assertEquals(listOf(0 to 8, 10 to 18), pages.map { it.start to it.end })
        assertEquals("名前はまだ無い。", doc.body.substring(pages[1].start, pages[1].end))
        assertTrue(pages.all { it.path != null })
        assertTrue(import.pageImages("other").isEmpty())
    }

    @Test
    fun lookedUpWordsFillTheDocumentListAndDrillInContext() = runTest {
        val d = Device("a")
        val vocab = DocumentVocabulary(d.db, clock)
        vocab.recordLookup("doc", "猫", "ねこ", "cat", 1467640L, "吾輩は猫である。", 3, 4)
        clock.advance(1.minutes)
        vocab.recordLookup("doc", "名前", "なまえ", "name", null, "名前はまだ無い。", 0, 2)
        vocab.recordLookup("doc", "猫", "ねこ", "cat", 1467640L, "猫が好き。", 0, 1) // looked up again elsewhere
        val words = vocab.words("doc")
        assertEquals(listOf("猫", "名前"), words.map { it.text })
        assertEquals(2, words[0].lookups)
        assertEquals("猫が好き。", words[0].sentence, "the latest sentence is kept")
        assertEquals("text:名前|なまえ", words[1].ref)

        val drill = vocab.drill("doc")
        val card = drill.current!!
        assertEquals("" to "猫", card.before to card.word)
        assertEquals("が好き。", card.after)
        assertTrue(drill.answerReading("neko").correct)
        assertEquals(Verdict.WRONG, drill.answerMeaning("surname").check!!.verdict)
        assertTrue(drill.finished)
        assertEquals(1 to 2, drill.score)

        vocab.remove("doc", "text:名前|なまえ")
        assertEquals(1, vocab.words("doc").size)
        vocab.recordLookup("doc", "名前", "なまえ", "name", null, "名前はまだ無い。", 0, 2)
        assertEquals(2, vocab.words("doc").size, "looking a removed word up again brings it back")
        vocab.forget("doc")
        assertTrue(vocab.words("doc").isEmpty())
    }

    @Test
    fun guidesAreLinksOnlyWithUniqueIdsAndHttpsUrls() {
        val all = GuidesLibrary.all
        assertTrue(all.size >= 60, "${all.size} guides")
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids are unique")
        assertTrue(all.all { it.url.startsWith("https://") && it.title.isNotBlank() && it.site.isNotBlank() })
        val particles = GuidesLibrary.forGrammarPoint("n5-wa-topic", listOf("particles"))
        assertEquals(setOf("taekim-particles", "tofugu-wa"), particles.take(2).map { it.id }.toSet(), "direct links first")
        assertTrue(particles.size > 2)
        assertTrue(GuidesLibrary.forTopic("keigo").isNotEmpty())
        assertFalse("overview" !in GuidesLibrary.topics)
        assertNotNull(all.firstOrNull { it.site == "Imabi" })
    }
}
