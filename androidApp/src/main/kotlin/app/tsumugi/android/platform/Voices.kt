package app.tsumugi.android.platform

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.api.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Speaks Japanese lines and waits until they finish, so dialogues and listening items can play line by line.
 * Uses the learner's VOICEVOX server when configured in Settings → AI & speech, else the on-device Android voices.
 * Two voices ("female" / "male" hints): distinct installed Japanese voices when the device has several, and a
 * pitch difference either way. Offline voices are preferred over network ones.
 */
class Voices(context: Context, private val graph: AppGraph) {
    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var player: MediaPlayer? = null

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        val ok = status == TextToSpeech.SUCCESS && runCatching { tts.setLanguage(Locale.JAPAN) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        ready.complete(ok)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { finish(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { finish(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId) }
        })
    }

    private fun finish(id: String?) {
        id?.let { pending.remove(it)?.complete(Unit) }
    }

    /** Whether a Japanese voice is installed (Android settings → Text-to-speech). */
    suspend fun available(): Boolean = ready.await() || graph.ai.synthesizer() != null

    /** Speaks [text] and suspends until it has been said. Cancelling stops the speech. */
    suspend fun say(text: String, voice: String? = null, rate: Float = 1f) {
        if (text.isBlank()) return
        graph.ai.synthesizer()?.let { synth ->
            val wav = runCatching { synth.synthesize(text, null, rate.toDouble()) }.getOrNull()
            if (wav != null) {
                playWav(wav)
                return
            }
        }
        if (!ready.await()) return
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        withContext(Dispatchers.Main) {
            voiceFor(voice)?.let { tts.voice = it }
            tts.setPitch(if (voice == "male") 0.85f else if (voice == "female") 1.1f else 1f)
            tts.setSpeechRate(rate)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        } finally {
            pending.remove(id)
        }
    }

    /** Speaks a list of (text, voice) lines in order. */
    suspend fun sayAll(lines: List<Pair<String, String?>>, rate: Float = 1f) {
        for ((text, voice) in lines) say(text, voice, rate)
    }

    fun stop() {
        tts.stop()
        player?.let { runCatching { it.stop() } }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun shutdown() {
        stop()
        player?.release()
        player = null
        tts.shutdown()
    }

    private fun voiceFor(hint: String?): Voice? {
        val japanese = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "ja" }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
        if (japanese.isEmpty()) return null
        val offline = japanese.filter { !it.isNetworkConnectionRequired }.ifEmpty { japanese }
        return when (hint) {
            "male" -> offline.getOrNull(1) ?: offline.first()
            else -> offline.first()
        }
    }

    private suspend fun playWav(wav: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("tts", ".wav", appContext.cacheDir).apply { writeBytes(wav) }
        }
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val mp = MediaPlayer()
                    player = mp
                    mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                    mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                    try {
                        mp.setDataSource(file.path)
                        mp.prepare()
                        mp.start()
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    cont.invokeOnCancellation { runCatching { mp.stop() } }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                player?.release()
                player = null
            }
            file.delete()
        }
    }
}

/** A [Voices] tied to the composition; shut down when the screen leaves. */
@Composable
fun rememberVoices(): Voices {
    val context = LocalContext.current
    val voices = remember { Voices(context, (context.applicationContext as TsumugiApplication).graph) }
    DisposableEffect(voices) { onDispose { voices.shutdown() } }
    return voices
}

/** A [SpeechInput] tied to the composition; cancelled when the screen leaves. */
@Composable
fun rememberSpeechInput(): SpeechInput {
    val context = LocalContext.current
    val input = remember { SpeechInput(context, (context.applicationContext as TsumugiApplication).graph) }
    DisposableEffect(input) { onDispose { input.cancel() } }
    return input
}
