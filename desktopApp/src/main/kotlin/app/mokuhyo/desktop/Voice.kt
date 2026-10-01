package app.mokuhyo.desktop

import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.tts.OsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Speech output for the app. Phase 2 puts the bundled voice service in front of the OS voice. */
object Voice {
    private val samples = mapOf(
        "ja" to "こんにちは。今日もよろしくお願いします。", "es" to "Hola. Vamos a practicar un poco hoy.",
        "fr" to "Bonjour. Pratiquons un peu aujourd'hui.", "de" to "Hallo. Lass uns heute ein bisschen üben.",
        "pt-BR" to "Olá. Vamos praticar um pouco hoje.", "ru" to "Здравствуйте. Давайте немного попрактикуемся.",
        "zh-Hans" to "你好。我们今天练习一下吧。", "ko" to "안녕하세요. 오늘 조금 연습해 봅시다.",
        "ar" to "مرحبا. لنتدرب قليلا اليوم.", "fa" to "سلام. بیایید امروز کمی تمرین کنیم.", "id" to "Halo. Mari kita berlatih sedikit hari ini.",
    )

    /** Plays a sample sentence; returns an error message, or null when it played. */
    suspend fun speakSample(app: AppGraph, lang: String): String? = withContext(Dispatchers.IO) {
        val wav = OsVoice.synthesize(samples[lang] ?: samples.getValue("es"), lang)
            ?: return@withContext "No voice is available for this language yet on this computer."
        runCatching { AudioIO.play(wav, app.settings.get(Settings.Key.OUTPUT_DEVICE)) }.exceptionOrNull()?.let { "Couldn't play audio: ${it.message}" }
    }
}
