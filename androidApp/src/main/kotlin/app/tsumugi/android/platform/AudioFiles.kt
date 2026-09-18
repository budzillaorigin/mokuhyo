package app.tsumugi.android.platform

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.tsumugi.api.AppGraph
import app.tsumugi.media.MediaHash
import app.tsumugi.media.PcmSource
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.ReferenceClip
import app.tsumugi.speech.Audio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import app.tsumugi.recordings.Recording as StoredRecording

/*
 * Platform audio I/O for G-01 shadowing, G-03 recordings and G-04 subtitles/clips: WAV files, decoding any media
 * file to 16 kHz mono PCM (MediaExtractor + MediaCodec), and simple file playback. No scoring or merge logic here.
 */

/** Writes 16 kHz mono PCM16 as a WAV file. */
object WavFile {
    fun write(file: File, pcm: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        val data = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }
        val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { body.putShort(it) }
        file.outputStream().use {
            it.write(header.array())
            it.write(body.array())
        }
    }
}

/** Decodes the first audio track of a media file (any container/codec the device supports) to 16 kHz mono PCM. */
object AudioDecoder {
    fun durationMs(context: Context, uri: Uri): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = audioTrack(ex)
            val format = ex.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) return format.getLong(MediaFormat.KEY_DURATION) / 1000
        } finally {
            ex.release()
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun audioTrack(ex: MediaExtractor): Int =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IOException("The file has no audio track")

    /** Samples of [startMs, endMs) as 16 kHz mono floats in [-1, 1]. Cancellable between codec buffers. */
    suspend fun decode(context: Context, uri: Uri, startMs: Long = 0, endMs: Long = Long.MAX_VALUE / 1000): FloatArray = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = audioTrack(ex)
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val startUs = startMs * 1000
            val endUs = if (endMs >= Long.MAX_VALUE / 1000) Long.MAX_VALUE else endMs * 1000
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floatPcm = false
            var out = FloatArray(1 shl 16)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = c.getInputBuffer(inIndex)!!
                        val size = ex.readSampleData(buffer, 0)
                        if (size < 0 || ex.sampleTime > endUs) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = c.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                        val shorts = if (floatPcm) null else buffer.asShortBuffer()
                        val floats = if (floatPcm) buffer.asFloatBuffer() else null
                        for (i in 0 until frames) {
                            val t = info.presentationTimeUs + i * 1_000_000L / rate
                            if (t >= endUs) {
                                outputDone = true
                                break
                            }
                            var sum = 0f
                            for (ch in 0 until channels) {
                                sum += if (floats != null) floats.get(i * channels + ch) else shorts!!.get(i * channels + ch) / 32768f
                            }
                            if (t < startUs) continue
                            if (n == out.size) out = out.copyOf(out.size * 2)
                            out[n++] = sum / channels
                        }
                        c.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            Audio.resample(out.copyOf(n), rate, SAMPLE_RATE)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}

/** [PcmSource] over a media file or content URI, decoded one window at a time (D-113). */
class MediaPcmSource(private val context: Context, private val uri: Uri) : PcmSource {
    override val durationMs: Long by lazy { AudioDecoder.durationMs(context, uri) }

    override suspend fun read(startMs: Long, endMs: Long): ShortArray =
        AudioDecoder.toPcm16(AudioDecoder.decode(context, uri, startMs, endMs))
}

/** The shared content key ([MediaHash]) of a content URI or file, read through a file descriptor. */
suspend fun mediaHash(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Can't open the file")
    pfd.use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { channel ->
            val size = channel.size()
            MediaHash.ofReader(size) { pos, buffer, off, len -> channel.read(ByteBuffer.wrap(buffer, off, len), pos) }
        }
    }
}

/** Plays an audio file to the end (or until cancelled) at [speed]. */
object AudioFilePlayer {
    suspend fun play(path: String, speed: Float = 1f) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            fun done() {
                runCatching { mp.release() }
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnCompletionListener { done() }
            mp.setOnErrorListener { _, _, _ -> done(); true }
            try {
                mp.setDataSource(path)
                mp.prepare()
                if (speed != 1f) mp.playbackParams = mp.playbackParams.setSpeed(speed)
                mp.start()
            } catch (e: Exception) {
                done()
            }
            cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
        }
    }
}

/**
 * Stores a microphone recording (G-03): `recordings.newRecording()` → WAV file → `register`. [reference] is what the
 * learner was imitating, for side-by-side playback.
 */
suspend fun AppGraph.storeRecording(audio: Recording, kind: RecordingKind, ref: String?, reference: ReferenceClip? = null): StoredRecording {
    val pending = recordings.newRecording("wav")
    withContext(Dispatchers.IO) { WavFile.write(File(pending.path), audio.pcm) }
    return recordings.register(pending, kind, ref, audio.durationMs, referenceKey = reference?.key)
}

/**
 * The model audio a learner imitates (G-01 shadowing, G-03 side-by-side). One abstraction so an audio-pack clip
 * (rule 20) can replace system TTS later without touching the screens: [FileClipPlayer] plays any file,
 * [TtsClipPlayer] speaks the text.
 */
interface ClipPlayer {
    val reference: ReferenceClip
    suspend fun play(speed: Float = 1f)

    /** The reference as 16 kHz mono floats for the shadowing comparison, or null when it can't be rendered. */
    suspend fun pcm(): FloatArray?
}

class FileClipPlayer(private val context: Context, private val path: String, override val reference: ReferenceClip) : ClipPlayer {
    override suspend fun play(speed: Float) = AudioFilePlayer.play(path, speed)
    override suspend fun pcm(): FloatArray? = runCatching { AudioDecoder.decode(context, Uri.fromFile(File(path))) }.getOrNull()
}

/** System TTS (or the learner's VOICEVOX server) until the pre-rendered audio pack ships (§5.6). */
class TtsClipPlayer(private val context: Context, private val voices: Voices, private val text: String) : ClipPlayer {
    override val reference: ReferenceClip = ReferenceClip.tts(text)
    private var rendered: FloatArray? = null

    override suspend fun play(speed: Float) = voices.say(text, rate = speed)

    override suspend fun pcm(): FloatArray? {
        rendered?.let { return it }
        val file = File(context.cacheDir, "shadow-ref-${text.hashCode()}.wav")
        return try {
            if (!voices.synthesizeToFile(text, file)) null
            else AudioDecoder.decode(context, Uri.fromFile(file)).also { rendered = it }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            file.delete()
        }
    }
}
