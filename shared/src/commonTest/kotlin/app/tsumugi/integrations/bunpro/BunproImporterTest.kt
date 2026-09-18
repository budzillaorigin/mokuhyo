package app.tsumugi.integrations.bunpro

import kotlin.test.Test
import kotlin.test.assertEquals

class BunproImporterTest {

    @Test
    fun parsesCsvWithHeaderQuotesAndLevelWords() {
        val csv = "﻿Grammar,Meaning,SRS Level\n〜てください,\"please do, kindly\",5\nだろう,probably,Burned\n〜たい,want to,\n"
        assertEquals(
            listOf(BunproRow("〜てください", 5), BunproRow("だろう", 12), BunproRow("〜たい", 1)),
            BunproImporter.parse(csv),
        )
    }

    @Test
    fun parsesTsv() {
        assertEquals(listOf(BunproRow("のに", 3)), BunproImporter.parse("title\tstage\nのに\t3\n"))
    }

    @Test
    fun normalizesTitles() {
        assertEquals("てください", GrammarTitles.normalize("〜 てください"))
        assertEquals("てください", GrammarTitles.normalize("～テクダサイ"))
        assertEquals("ために", GrammarTitles.normalize("ために (purpose)"))
    }

    @Test
    fun levelMapping() {
        assertEquals(0, BunproImporter.wkStage(0))
        assertEquals(3, BunproImporter.wkStage(3))
        assertEquals(5, BunproImporter.wkStage(5))
        assertEquals(9, BunproImporter.wkStage(12))
    }
}
