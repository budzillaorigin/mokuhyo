package app.tsumugi.android.platform

import android.content.Context
import app.tsumugi.ai.LocalSttBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device speech-to-text with whisper.cpp (MIT, CPU/NEON) behind the shared [LocalSttBridge].
 * Results are JSON segments `[{"t0": ms, "t1": ms, "text": "…"}]`; work runs on one dedicated thread, where
 * `onDone` also fires. `libtsumugi_whisper.so` loads lazily on the first [load].
 */
class WhisperJni(context: Context) : LocalSttBridge {
    @Suppress("unused") private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "tsumugi-whisper", 16L * 1024 * 1024).apply { isDaemon = true }
    }
    @Volatile private var handle = 0L

    override fun isLoaded(): Boolean = handle != 0L

    override fun load(modelPath: String, onDone: (String?) -> Unit) {
        worker.execute {
            NativeLibs.check("tsumugi_whisper")?.let { onDone(it); return@execute }
            handle.takeIf { it != 0L }?.let { WhisperNative.nativeFree(it) }
            handle = 0L
            if (!File(modelPath).isFile) { onDone("Speech model not found: $modelPath"); return@execute }
            handle = WhisperNative.nativeInit(modelPath)
            onDone(if (handle == 0L) "Couldn't load the speech model at $modelPath" else null)
        }
    }

    override fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit) {
        worker.execute {
            val h = handle
            if (h == 0L) { onDone(null, "No speech model loaded"); return@execute }
            val n = WhisperNative.nativeTranscribe(h, samples, language, NativeLibs.threads())
            if (n < 0) { onDone(null, "Transcription failed ($n)"); return@execute }
            val segments = JSONArray()
            for (i in 0 until n) {
                val (t0, t1) = WhisperNative.nativeSegmentTimes(h, i)
                segments.put(
                    JSONObject()
                        .put("t0", t0)
                        .put("t1", t1)
                        .put("text", String(WhisperNative.nativeSegmentText(h, i), Charsets.UTF_8).trim()),
                )
            }
            onDone(segments.toString(), null)
        }
    }
}

/** JNI entry points in `src/main/cpp/whisper_jni.cpp`. */
internal object WhisperNative {
    @JvmStatic external fun nativeInit(path: String): Long
    @JvmStatic external fun nativeFree(handle: Long)
    /** Number of segments, or a negative error code. */
    @JvmStatic external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, nThreads: Int): Int
    /** `[t0, t1]` in milliseconds. */
    @JvmStatic external fun nativeSegmentTimes(handle: Long, index: Int): LongArray
    /** UTF-8 bytes of the segment text. */
    @JvmStatic external fun nativeSegmentText(handle: Long, index: Int): ByteArray
}
