package app.tsumugi.integrations.anki

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.srs.CardState
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.fileDriver
import app.tsumugi.testing.inMemoryDriver
import app.tsumugi.testing.newTempDir
import app.tsumugi.testing.testFileSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** BRIEF §11 ApkgRoundTripTest: NihongoShark-format package → import → export → re-import → equality. */
class ApkgRoundTripTest {

    private val fs = testFileSystem
    private val work = newTempDir("apkg-test")

    private class Setup(val db: TsumugiDatabase, val srs: SrsRepository)

    private fun setup(): Setup {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        return Setup(db, SrsRepository(db, "device", TestClock()))
    }

    private fun importer(s: Setup, media: Boolean = false) =
        AnkiImporter(s.srs, fs, work, ::fileDriver, if (media) work / "media" else null)

    private fun exporter(s: Setup) = AnkiExporter(s.db, fs, work, ::fileDriver, clock = TestClock())

    @Test
    fun importsNihongoSharkPackage() = runTest {
        val s = setup()
        val result = importer(s, media = true).import(AnkiFixtures.legacyApkg)

        assertEquals(AnkiPackageFormat.LEGACY_ANKI2, result.format)
        assertEquals(5, result.notes)
        assertEquals(7, result.cards)
        assertEquals(12, result.reviews, "orphan revlog rows (unknown card ids) are skipped")
        assertEquals(3, result.nihongoSharkNotes)
        assertEquals(1, result.suspended)
        assertEquals(2, result.media)
        assertEquals(listOf("anki:1600000000000", "anki:1600000000001", "anki:1600000000002", "anki:1600000000100", "anki:1600000000101"), result.itemIds)

        val go = assertNotNull(s.srs.item("anki:1600000000000"))
        assertEquals(ItemKind.KANJI, go.kind)
        assertEquals(ItemSource.ANKI, go.source)
        assertEquals("語", go.primaryText)
        assertEquals(listOf("word"), go.meanings)
        assertEquals(listOf("ゴ", "かた"), go.acceptedReadings)
        assertEquals("Five mouths telling words.\nSay it!", go.myStory)
        assertEquals(go.myStory, s.srs.saveNoteProbe("k:語"), "myStory also lands on the kanji-path item")
        assertEquals(listOf("ニチ", "ジツ", "ひ", "び", "か"), s.srs.item("anki:1600000000001")!!.acceptedReadings)

        val goCard = s.srs.cardsForItems(listOf("anki:1600000000000")).single()
        assertEquals(CardDirection.RECALL, goCard.direction, "NihongoShark's KeywordToKanji template is recall")
        assertEquals(CardState.REVIEW, goCard.fsrs.state, "3,3,1,3 replayed: the Good after the lapse finishes relearning")
        assertEquals(1, goCard.fsrs.lapses)
        assertTrue(s.srs.cardsForItems(listOf("anki:1600000000002")).single().suspended)

        val neko = s.srs.item("anki:1600000000100")!!
        assertEquals(ItemKind.CUSTOM, neko.kind)
        assertEquals("猫", neko.primaryText)
        assertEquals(listOf("cat"), neko.meanings)
        val nekoCards = s.srs.cardsForItems(listOf(neko.id)).associateBy { it.direction }
        assertEquals(setOf(CardDirection.RECOGNITION, CardDirection.RECALL), nekoCards.keys)
        assertEquals(CardState.LEARNING, nekoCards.getValue(CardDirection.RECOGNITION).fsrs.state, "Good, then Hard at the 1-day step")
        assertEquals(CardState.NEW, s.srs.cardsForItems(listOf("anki:1600000000101")).first().fsrs.state, "unreviewed cards await lessons")

        assertEquals(72, fs.read(work / "media" / "stroke_語.png") { readByteArray() }.size)
    }

    @Test
    fun reimportIsIdempotent() = runTest {
        val s = setup()
        importer(s).import(AnkiFixtures.legacyApkg)
        importer(s).import(AnkiFixtures.legacyApkg)
        assertEquals(12, s.db.srsQueries.reviewCount().executeAsOne())
        assertEquals(5, s.db.srsQueries.itemCount().executeAsList().sumOf { it.COUNT })
    }

    @Test
    fun exportThenReimportRoundTrips() = runTest {
        val a = setup()
        val first = importer(a).import(AnkiFixtures.legacyApkg)
        val apkg = exporter(a).export(first.itemIds, deckName = "Round trip")

        val pkg = AnkiPackage.read(apkg)
        assertEquals(AnkiPackageFormat.LEGACY_ANKI2, pkg.format)

        val b = setup()
        val second = importer(b).import(apkg)
        assertEquals(first.itemIds, second.itemIds)
        assertEquals(first.nihongoSharkNotes, second.nihongoSharkNotes)
        assertEquals(first.suspended, second.suspended)

        for (id in first.itemIds) {
            val x = a.srs.item(id)!!
            val y = b.srs.item(id)!!
            assertEquals(listOf(x.kind, x.primaryText, x.meanings, x.acceptedReadings, x.myStory), listOf(y.kind, y.primaryText, y.meanings, y.acceptedReadings, y.myStory), id)
            val cx = a.srs.cardsForItems(listOf(id)).map { it.id to it.fsrs }.sortedBy { it.first }
            val cy = b.srs.cardsForItems(listOf(id)).map { it.id to it.fsrs }.sortedBy { it.first }
            assertEquals(cx, cy, "card state for $id")
        }
        fun reviews(s: Setup) = s.db.srsQueries.allReviews().executeAsList().map { listOf(it.id, it.card_id, it.ts, it.rating) }.toSet()
        assertEquals(reviews(a), reviews(b))
    }

    @Test
    fun exportsEditedStoryAndOwnItemsWithTsumugiNotetype() = runTest {
        val s = setup()
        importer(s).import(AnkiFixtures.legacyApkg)
        s.srs.saveNote("anki:1600000000000", myStory = "My own story <3")
        s.srs.addItems(
            listOf(
                NewItem("k:山", ItemKind.KANJI, "山", "さん", listOf("mountain"), listOf("さん", "やま"), ItemSource.PACK, listOf(CardDirection.MEANING, CardDirection.READING)),
            ),
        )
        val apkg = exporter(s).export(listOf("anki:1600000000000", "k:山"))
        val pkg = AnkiPackage.read(apkg)
        val file = work / "check.anki2"
        fs.write(file) { write(pkg.collection) }
        val driver = fileDriver(file.toString())
        val col = try { AnkiCollection.read(driver) } finally { driver.close() }

        assertEquals(2, col.notes.size)
        val ns = col.notes.first { it.id == 1600000000000 }
        assertEquals("My own story &lt;3", ns.fields[2])
        val mountain = col.notes.first { it.guid == "tsumugi:k:山" }
        assertEquals("Tsumugi", col.notetypes.getValue(mountain.mid).name)
        assertEquals(listOf("山", "さん、やま", "mountain", "", "k:山"), mountain.fields)
        assertEquals(listOf(0, 1), col.cards.filter { it.nid == mountain.id }.map { it.ord }.sorted())
        assertEquals(setOf("Round trip", "Tsumugi", "Default").intersect(col.decks.values.toSet()), setOf("Tsumugi", "Default"))
    }

    @Test
    fun importsModernAnki21bPackage() = runTest {
        val s = setup()
        val result = importer(s, media = true).import(AnkiFixtures.modernApkg)
        assertEquals(AnkiPackageFormat.ZSTD_ANKI21B, result.format)
        assertEquals(2, result.notes)
        assertEquals(4, result.cards)
        assertEquals(4, result.reviews)
        assertEquals(0, result.nihongoSharkNotes)
        val neko = s.srs.item("anki:1600000000100")!!
        assertEquals(listOf("cat"), neko.meanings)
        assertTrue(neko.context!!.contains("Japanese::Animals"))
        assertContentEquals(ByteArray(256 * 40) { (it % 256).toByte() }, fs.read(work / "media" / "neko.mp3") { readByteArray() })
    }

    /** Reads an item's story through the public API (the note exists even when the item doesn't). */
    private suspend fun SrsRepository.saveNoteProbe(itemId: String): String {
        addItems(listOf(NewItem(itemId, ItemKind.KANJI, itemId, null, emptyList(), emptyList(), ItemSource.PACK, emptyList())))
        return item(itemId)!!.myStory
    }
}
