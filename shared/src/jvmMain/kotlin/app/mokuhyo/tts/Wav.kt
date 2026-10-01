package app.mokuhyo.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE reader: enough to check a synthesizer's output and measure it. */
object Wav {
    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Int) {
        val durationSeconds: Double
            get() = if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) 0.0
            else dataBytes.toDouble() / (sampleRate * channels * (bitsPerSample / 8))
    }

    /** Parses the header chunks, or null when [bytes] is not a PCM WAV with a fmt and a data chunk. */
    fun info(bytes: ByteArray): Info? {
        if (bytes.size < 44) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var fmt: Triple<Int, Int, Int>? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (size < 0) return null
            when (id) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    fmt = Triple(b.getShort(body + 2).toInt(), b.getInt(body + 4), b.getShort(body + 14).toInt())
                }
                "data" -> {
                    val f = fmt ?: return null
                    // Streams written before their length is known carry 0 or 0xFFFFFFFF; trust the bytes we have.
                    val available = bytes.size - body
                    val dataBytes = if (size == 0 || size > available) available else size
                    return Info(sampleRate = f.second, channels = f.first, bitsPerSample = f.third, dataBytes = dataBytes)
                }
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    /** True for a WAV holding at least [minSeconds] of audio. */
    fun isValid(bytes: ByteArray?, minSeconds: Double = 0.05): Boolean =
        bytes != null && (info(bytes)?.durationSeconds ?: 0.0) >= minSeconds
}
