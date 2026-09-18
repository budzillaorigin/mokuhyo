package app.tsumugi.integrations.anki

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZstdZipTest {

    @Test
    fun zstdVectorsFromReferenceCompressor() {
        // Vectors produced by python-zstandard (libzstd): Huffman literals (1 and 4 streams), FSE and predefined
        // sequence tables, repeat offsets, multi-block frames, raw blocks, RLE, no content size + checksum,
        // multiple frames with a skippable frame in between, and a ~94 KB SQLite file.
        for ((name, v) in AnkiFixtures.zstdVectors) {
            val (compressed, size, crc) = v
            val out = Zstd.decompress(compressed)
            assertEquals(size, out.size, "$name size")
            assertEquals(crc, Crc32.of(out), "$name CRC")
        }
        assertEquals(9, AnkiFixtures.zstdVectors.size)
    }

    @Test
    fun zstdSmallExact() {
        val (compressed, _, _) = AnkiFixtures.zstdVectors.getValue("hello")
        assertEquals("Hello, zstd! Hello, zstd! Hello!", Zstd.decompress(compressed).decodeToString())
        assertTrue(Zstd.isZstd(compressed))
        assertTrue(!Zstd.isZstd("PK".encodeToByteArray()))
    }

    @Test
    fun zstdRejectsGarbage() {
        assertFailsWith<AnkiFormatException> { Zstd.decompress(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
    }

    @Test
    fun checksumsMatchKnownValues() {
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.hex("abc".encodeToByteArray()))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.hex(ByteArray(0)))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Sha1.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun zipWriteThenRead() {
        val entries = listOf("a.txt" to "hello".encodeToByteArray(), "日本語.bin" to ByteArray(1000) { it.toByte() }, "empty" to ByteArray(0))
        val read = Zip.read(Zip.write(entries))
        assertEquals(entries.map { it.first }, read.keys.toList())
        for ((name, data) in entries) assertContentEquals(data, read.getValue(name))
    }

    @Test
    fun zipReadsDeflatedEntries() {
        // The legacy fixture was written by Python's zipfile with ZIP_DEFLATED.
        val entries = Zip.read(AnkiFixtures.legacyApkg)
        assertEquals(setOf("collection.anki2", "media", "0", "1"), entries.keys)
        assertEquals("SQLite format 3", entries.getValue("collection.anki2").copyOfRange(0, 15).decodeToString())
        assertEquals(72, entries.getValue("0").size)
    }

    @Test
    fun anki21bPackageIsUnpacked() {
        val pkg = AnkiPackage.read(AnkiFixtures.modernApkg)
        assertEquals(AnkiPackageFormat.ZSTD_ANKI21B, pkg.format)
        assertEquals("SQLite format 3", pkg.collection.copyOfRange(0, 15).decodeToString())
        assertEquals(setOf("neko.mp3"), pkg.media.keys)
        assertContentEquals(ByteArray(256 * 40) { (it % 256).toByte() }, pkg.media.getValue("neko.mp3"))
    }
}
