package app.tsumugi.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The renderer (tools/packs/render_audio.py) writes exactly these keys; D-092. */
class AudioKeysTest {
    @Test
    fun keysMatchTheRenderer() {
        assertEquals("exam/jla-n1-qr-01/0", AudioKeys.exam("jla-n1-qr-01", 0))
        assertEquals(listOf("exam/dl-0p-announcement-001/0", "exam/dl-0p-announcement-001/1"),
            AudioKeys.examScript("dl-0p-announcement-001", 2))
        assertEquals("dialogue/n5-morning/3", AudioKeys.dialogue("n5-morning", 3))
        assertEquals("pair/480/a", AudioKeys.minimalPair(480, PairSide.A))
        assertEquals("pair/480/b", AudioKeys.minimalPair(480, PairSide.B))
        assertEquals("pitch/p1401000", AudioKeys.pitch("p1401000"))
        assertEquals("grammar/n1-aete/1", AudioKeys.grammar("n1-aete", 1))
        assertEquals("reader/gr-n4-012/7", AudioKeys.reader("gr-n4-012", 7))
        assertEquals(listOf("reader/gr-n6-001/0", "reader/gr-n6-001/1"), AudioKeys.readerStory("gr-n6-001", 2))
    }

    @Test
    fun readerKeysBelongToTheReadersSet() {
        // render_audio.py writes reader/<story id>/<reader_sentence.idx> into audio-readers.zip.
        assertEquals(AudioSet.READERS, AudioSet.ofKey(AudioKeys.reader("gr-n1-020", 0)))
        assertEquals("audio-readers.zip", AudioSet.READERS.fileName)
        assertEquals(AudioSet.READERS, AudioSet.fromId("readers"))
        assertEquals("reader/gr-n3-004/12.m4a", AudioKeys.relativePath(AudioKeys.reader("gr-n3-004", 12)))
    }

    @Test
    fun keysMapToTheirSet() {
        assertEquals(AudioSet.EXAM, AudioSet.ofKey(AudioKeys.exam("x", 0)))
        assertEquals(AudioSet.DIALOGUES, AudioSet.ofKey(AudioKeys.dialogue("x", 0)))
        assertEquals(AudioSet.MINIMAL_PAIRS, AudioSet.ofKey(AudioKeys.minimalPair(1, PairSide.A)))
        assertEquals(AudioSet.PITCH, AudioSet.ofKey(AudioKeys.pitch("p1")))
        assertEquals(AudioSet.GRAMMAR, AudioSet.ofKey(AudioKeys.grammar("x", 0)))
        assertNull(AudioSet.ofKey("lyrics/1"))
        assertEquals("audio-minimal-pairs.zip", AudioSet.MINIMAL_PAIRS.fileName)
        assertEquals(AudioSet.MINIMAL_PAIRS, AudioSet.fromId("minimal-pairs"))
    }

    @Test
    fun relativePathsStayInsideThePack() {
        assertEquals("exam/dr-2p-editorial-001/0.m4a", AudioKeys.relativePath("exam/dr-2p-editorial-001/0"))
        assertEquals("pitch/~2E..m4a", AudioKeys.relativePath("pitch/.."))
        assertEquals("grammar/n3-~E3~81~AF/0.m4a", AudioKeys.relativePath("grammar/n3-は/0"))
        assertEquals("exam/a~5Cb/0.m4a", AudioKeys.relativePath("exam/a\\b/0"))
        assertNull(AudioKeys.relativePath("pitch"))
        assertNull(AudioKeys.relativePath("pitch//x"))
        assertNull(AudioKeys.relativePath("/pitch/x"))
    }
}
