package app.mokuhyo.speech

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Listening clips as Ogg Opus (RFC 7845), 16 kHz mono speech at 24 kbit/s: about 3 KB per second instead of 32 KB
 * for WAV. Pure Java (Concentus), so the same code encodes at build time and decodes in the app on every OS.
 */
object OggOpus {
    private const val RATE = 16_000
    private const val FRAME = 320 // 20 ms
    private const val PRE_SKIP = 312 // in 48 kHz samples (RFC 7845 §4.2 typical encoder delay)

    fun encode(pcm: ShortArray, bitrate: Int = 24_000): ByteArray {
        val enc = OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
        enc.bitrate = bitrate
        enc.complexity = 10
        val packets = ArrayList<ByteArray>()
        val buf = ByteArray(4000)
        var i = 0
        while (i < pcm.size) {
            val frame = ShortArray(FRAME)
            pcm.copyInto(frame, 0, i, minOf(pcm.size, i + FRAME))
            val n = enc.encode(frame, 0, FRAME, buf, 0, buf.size)
            packets += buf.copyOf(n)
            i += FRAME
        }
        val out = ByteArrayOutputStream()
        val serial = 0x4d4f4b55 // "MOKU"
        var seq = 0
        writePage(out, listOf(opusHead()), 0, serial, seq++, bos = true, eos = false)
        writePage(out, listOf(opusTags()), 0, serial, seq++, bos = false, eos = false)
        var granule = PRE_SKIP.toLong()
        val chunk = 50 // packets per page (1 s)
        packets.chunked(chunk).forEachIndexed { idx, group ->
            granule += group.size.toLong() * FRAME * 3
            val last = (idx + 1) * chunk >= packets.size
            writePage(out, group, granule, serial, seq++, bos = false, eos = last)
        }
        if (packets.isEmpty()) writePage(out, emptyList(), granule, serial, seq, bos = false, eos = true)
        return out.toByteArray()
    }

    /** Decodes an Ogg Opus stream to 16 kHz mono PCM16. */
    fun decode(ogg: ByteArray): ShortArray {
        val packets = readPackets(ogg)
        require(packets.size >= 2 && String(packets[0], 0, 8, Charsets.US_ASCII) == "OpusHead") { "not an Ogg Opus stream" }
        val dec = OpusDecoder(RATE, 1)
        val out = ArrayList<ShortArray>()
        val frame = ShortArray(RATE * 120 / 1000)
        for (p in packets.drop(2)) {
            val n = dec.decode(p, 0, p.size, frame, 0, frame.size, false)
            out += frame.copyOf(n)
        }
        val skip = PRE_SKIP / 3
        val total = out.sumOf { it.size }
        val all = ShortArray(total).also { a -> var o = 0; out.forEach { it.copyInto(a, o); o += it.size } }
        return if (all.size > skip) all.copyOfRange(skip, all.size) else all
    }

    fun isOggOpus(bytes: ByteArray): Boolean = bytes.size > 36 && String(bytes, 0, 4, Charsets.US_ASCII) == "OggS"

    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(1); putShort(PRE_SKIP.toShort()); putInt(RATE); putShort(0); put(0)
    }.array()

    private fun opusTags(): ByteArray {
        val vendor = "Mokuhyo (Concentus)".toByteArray()
        return ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.US_ASCII)); putInt(vendor.size); put(vendor); putInt(0)
        }.array()
    }

    private fun writePage(out: ByteArrayOutputStream, packets: List<ByteArray>, granule: Long, serial: Int, seq: Int, bos: Boolean, eos: Boolean) {
        val lacing = ArrayList<Int>()
        packets.forEach { p ->
            var left = p.size
            while (left >= 255) { lacing += 255; left -= 255 }
            lacing += left
        }
        require(lacing.size <= 255) { "too many segments for one page" }
        val body = packets.fold(ByteArrayOutputStream()) { acc, p -> acc.also { it.write(p) } }.toByteArray()
        val header = ByteBuffer.allocate(27 + lacing.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OggS".toByteArray(Charsets.US_ASCII)); put(0)
            put(((if (bos) 0x02 else 0) or (if (eos) 0x04 else 0)).toByte())
            putLong(granule); putInt(serial); putInt(seq); putInt(0); put(lacing.size.toByte())
            lacing.forEach { put(it.toByte()) }
        }.array()
        val page = header + body
        val crc = crc32(page)
        page[22] = crc.toByte(); page[23] = (crc ushr 8).toByte(); page[24] = (crc ushr 16).toByte(); page[25] = (crc ushr 24).toByte()
        out.write(page)
    }

    private fun readPackets(ogg: ByteArray): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var partial = ByteArrayOutputStream()
        var pos = 0
        while (pos + 27 <= ogg.size) {
            require(ogg[pos] == 'O'.code.toByte() && ogg[pos + 1] == 'g'.code.toByte()) { "bad Ogg page at $pos" }
            val nSeg = ogg[pos + 26].toInt() and 0xFF
            var data = pos + 27 + nSeg
            for (s in 0 until nSeg) {
                val len = ogg[pos + 27 + s].toInt() and 0xFF
                partial.write(ogg, data, len)
                data += len
                if (len < 255) {
                    packets += partial.toByteArray()
                    partial = ByteArrayOutputStream()
                }
            }
            pos = data
        }
        return packets
    }

    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
