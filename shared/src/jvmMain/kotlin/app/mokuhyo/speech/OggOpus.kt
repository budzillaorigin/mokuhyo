package app.mokuhyo.speech

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Listening clips as Ogg Opus (RFC 7845), mono speech at 16 or 24 kHz and 24–32 kbit/s: about 3–4 KB per second
 * instead of 32–48 KB for WAV. Pure Java (Concentus), so the same code encodes at build time and decodes in the app on
 * every OS. The OpusHead records the input rate, and [decode] gives the audio back at that rate (older packs: 16 kHz).
 */
object OggOpus {
    /** Rates a clip can be encoded at: Whisper's 16 kHz, or 24 kHz to keep a neural voice's upper band (QC 2026-10-19). */
    val RATES = setOf(16_000, 24_000)
    private const val PRE_SKIP = 312 // in 48 kHz samples (RFC 7845 §4.2 typical encoder delay)

    fun encode(pcm: ShortArray, bitrate: Int = 24_000, sampleRate: Int = 16_000): ByteArray {
        require(sampleRate in RATES) { "unsupported Opus rate $sampleRate" }
        val frameSize = sampleRate / 50 // 20 ms
        val enc = OpusEncoder(sampleRate, 1, if (sampleRate > 16_000) OpusApplication.OPUS_APPLICATION_AUDIO else OpusApplication.OPUS_APPLICATION_VOIP)
        enc.bitrate = bitrate
        enc.complexity = 10
        val packets = ArrayList<ByteArray>()
        val buf = ByteArray(4000)
        var i = 0
        while (i < pcm.size) {
            val frame = ShortArray(frameSize)
            pcm.copyInto(frame, 0, i, minOf(pcm.size, i + frameSize))
            val n = enc.encode(frame, 0, frameSize, buf, 0, buf.size)
            packets += buf.copyOf(n)
            i += frameSize
        }
        val out = ByteArrayOutputStream()
        val serial = 0x4d4f4b55 // "MOKU"
        var seq = 0
        writePage(out, listOf(opusHead(sampleRate)), 0, serial, seq++, bos = true, eos = false)
        writePage(out, listOf(opusTags()), 0, serial, seq++, bos = false, eos = false)
        var granule = PRE_SKIP.toLong()
        val chunk = 50 // packets per page (1 s)
        packets.chunked(chunk).forEachIndexed { idx, group ->
            granule += group.size.toLong() * 960 // 20 ms in 48 kHz units
            val last = (idx + 1) * chunk >= packets.size
            writePage(out, group, granule, serial, seq++, bos = false, eos = last)
        }
        if (packets.isEmpty()) writePage(out, emptyList(), granule, serial, seq, bos = false, eos = true)
        return out.toByteArray()
    }

    /** Decodes an Ogg Opus stream to mono PCM16 at the clip's own rate ([rateOf]). */
    fun decode(ogg: ByteArray): ShortArray = decodeWithRate(ogg).first

    /** The decoded samples and their rate. */
    fun decodeWithRate(ogg: ByteArray): Pair<ShortArray, Int> {
        val packets = readPackets(ogg)
        require(packets.size >= 2 && String(packets[0], 0, 8, Charsets.US_ASCII) == "OpusHead") { "not an Ogg Opus stream" }
        val rate = headRate(packets[0])
        val dec = OpusDecoder(rate, 1)
        val out = ArrayList<ShortArray>()
        val frame = ShortArray(rate * 120 / 1000)
        for (p in packets.drop(2)) {
            val n = dec.decode(p, 0, p.size, frame, 0, frame.size, false)
            out += frame.copyOf(n)
        }
        val skip = PRE_SKIP * rate / 48_000
        val total = out.sumOf { it.size }
        val all = ShortArray(total).also { a -> var o = 0; out.forEach { it.copyInto(a, o); o += it.size } }
        return (if (all.size > skip) all.copyOfRange(skip, all.size) else all) to rate
    }

    /** The input sample rate recorded in the clip's OpusHead (16 kHz for anything this app didn't write at another rate). */
    fun rateOf(ogg: ByteArray): Int = readPackets(ogg).firstOrNull()?.let(::headRate) ?: 16_000

    private fun headRate(head: ByteArray): Int =
        (if (head.size >= 16) ByteBuffer.wrap(head, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int else 16_000).takeIf { it in RATES } ?: 16_000

    fun isOggOpus(bytes: ByteArray): Boolean = bytes.size > 36 && String(bytes, 0, 4, Charsets.US_ASCII) == "OggS"

    private fun opusHead(rate: Int): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(1); putShort(PRE_SKIP.toShort()); putInt(rate); putShort(0); put(0)
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
