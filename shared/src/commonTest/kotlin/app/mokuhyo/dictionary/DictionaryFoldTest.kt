package app.mokuhyo.dictionary

import app.mokuhyo.testing.testFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DictionaryFold] against the vectors tools/packs/test_build_dictionary.py also checks dict_fold.fold against, so
 * the builder's fold keys and the app's query keys cannot drift.
 */
class DictionaryFoldTest {
    private val vectorsPath = "shared/src/commonTest/resources/dictionary/fold_vectors.json"

    private fun findUp(relative: String): Path {
        var dir: Path? = testFileSystem.canonicalize(".".toPath())
        while (dir != null) {
            val candidate = dir / relative
            if (testFileSystem.exists(candidate)) return candidate
            dir = dir.parent
        }
        error("$relative not found above the working directory")
    }

    @Test
    fun sharedVectors() {
        val text = testFileSystem.read(findUp(vectorsPath)) { readUtf8() }
        val vectors = Json.parseToJsonElement(text).jsonObject.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 30)
        for (v in vectors) {
            val o = v.jsonObject
            val lang = o.getValue("lang").jsonPrimitive.content
            val input = o.getValue("input").jsonPrimitive.content
            val expected = o.getValue("expected").jsonPrimitive.content
            assertEquals(expected, DictionaryFold.fold(input, lang), "$lang ${o["note"]}: \"$input\"")
        }
    }

    @Test
    fun neverNfkcFoldsCompatibilityCharacters() {
        // NFKC would turn 〜 into ~ and ㍿ into 株式会社; the fold leaves anything outside its width table alone.
        assertEquals("〜", DictionaryFold.fold("〜", "ja"))
        assertEquals("㍿", DictionaryFold.fold("㍿", "ja"))
        assertEquals("ﬁ", DictionaryFold.fold("ﬁ", "fr"))
    }
}
