package app.tsumugi.integrations.imiwa

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ImiwaImporterTest {

    @Test
    fun tabSeparatedWithoutHeader() {
        val words = ImiwaImporter.parse("食べる\tたべる\tto eat\n猫\tねこ\tcat; kitty\r\n\n学校\tがっこう\tschool\n")
        assertEquals(
            listOf(ImiwaWord("食べる", "たべる", "to eat"), ImiwaWord("猫", "ねこ", "cat; kitty"), ImiwaWord("学校", "がっこう", "school")),
            words,
        )
    }

    @Test
    fun csvWithBomHeaderQuotesAndExtraColumns() {
        val text = "﻿Word,Reading,Meaning,JLPT\n\"見る\",みる,\"to see, to look\",N5\n\"He said \"\"hi\"\"\",,greeting,\n"
        val words = ImiwaImporter.parse(text)
        assertEquals(ImiwaWord("見る", "みる", "to see, to look"), words[0])
        assertEquals(ImiwaWord("He said \"hi\"", "", "greeting"), words[1])
    }

    @Test
    fun extraUnnamedColumnsJoinIntoMeaning() {
        val words = ImiwaImporter.parse("本;ほん;book;origin")
        assertEquals(ImiwaWord("本", "ほん", "book; origin"), words.single())
    }

    @Test
    fun importToListDedupesAndResolves() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val words = ImiwaImporter.parse("猫\tねこ\tcat\n犬\tいぬ\tdog\n猫\tねこ\tcat again")
        val result = ImiwaImporter.importToList(db, "imiwa favourites", words, TestClock()) { w ->
            if (w.text == "猫") "jmdict:1467640" else null
        }
        assertEquals(2, result.imported)
        assertEquals(1, result.resolved)
        assertEquals(1, result.skipped)
        val entries = db.userQueries.listEntries(result.listId).executeAsList()
        assertEquals(listOf("jmdict:1467640", "text:犬|いぬ"), entries.map { it.ref })
        assertEquals("imiwa favourites", db.userQueries.lists().executeAsList().single().name)

        val csv = ImiwaImporter.exportCsv(db, result.listId)
        assertEquals("word,reading,meaning\r\n猫,ねこ,cat\r\n犬,いぬ,dog\r\n", csv)
        assertEquals(listOf(ImiwaWord("猫", "ねこ", "cat"), ImiwaWord("犬", "いぬ", "dog")), ImiwaImporter.parse(csv))
    }
}
