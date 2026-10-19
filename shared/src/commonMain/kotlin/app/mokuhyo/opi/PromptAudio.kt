package app.mokuhyo.opi

import app.mokuhyo.ai.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The index of a pack's prerendered interview prompts (BRIEF_INTERVIEW_SPEED N5): `<pack>/prompts.json` lists every
 * scripted bank question and role-play opening with the hash of the text it should say. A rendered clip lives at
 * `<pack>/audio/prompts/<id>.ogg` (Ogg Opus, 24 kHz mono) and its entry carries the clip's own hash, length and size,
 * plus the engine, voice and credit line that made it. An entry without [Entry.audioSha256] (or whose text changed
 * since it was rendered) has no usable clip: the app speaks that prompt with the live voice instead.
 */
@Serializable
data class PromptAudioIndex(
    val format: String = FORMAT,
    val language: String,
    val rate: Int = 24_000,
    val renderVersion: Int = RENDER_VERSION,
    val prompts: List<Entry>,
) {
    @Serializable
    data class Entry(
        val id: String,
        /** "question" or "roleplay". */
        val kind: String,
        val phase: String,
        val level: String,
        val domain: String? = null,
        val textSha256: String,
        val audioSha256: String? = null,
        val seconds: Double? = null,
        val bytes: Long? = null,
        /** "piper", "voicevox", "chatterbox-multilingual" … */
        val engine: String? = null,
        val voice: String? = null,
        /** The credit the voice's terms require (e.g. "VOICEVOX:春日部つむぎ"), shown in Settings → About & licenses. */
        val credit: String? = null,
    ) {
        val rendered: Boolean get() = audioSha256 != null

        fun relativePath(): String = "audio/prompts/$id.ogg"
    }

    /** The entry for [id] if its clip was rendered from [text] exactly as the pack says it now. */
    fun usable(id: String, text: String): Entry? = prompts.firstOrNull { it.id == id && it.rendered && it.textSha256 == textHash(text) }

    fun toJson(): String = json.encodeToString(serializer(), this)

    /** The pack size the rendered clips add, and how many are still missing. */
    val renderedBytes: Long get() = prompts.sumOf { it.bytes ?: 0 }
    val missing: Int get() = prompts.count { !it.rendered }

    companion object {
        const val FORMAT = "mokuhyo-prompt-audio/1"
        const val RENDER_VERSION = 1
        const val FILE = "prompts.json"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun parse(text: String): PromptAudioIndex = json.decodeFromString(serializer(), text)

        fun textHash(text: String): String = Sha256.hex(text.trim().encodeToByteArray())

        /**
         * The index for [pack]: every bank question and role-play opening, keeping what [previous] knew about clips
         * that still match their text (a reworded prompt loses its clip until it is rendered again).
         */
        fun build(pack: OpiPack, previous: PromptAudioIndex? = null): PromptAudioIndex {
            val old = previous?.prompts?.associateBy { it.id }.orEmpty()
            fun keep(e: Entry) = old[e.id]?.takeIf { it.textSha256 == e.textSha256 && it.rendered }
                ?.let { e.copy(audioSha256 = it.audioSha256, seconds = it.seconds, bytes = it.bytes, engine = it.engine, voice = it.voice, credit = it.credit) } ?: e
            val questions = pack.questions.map { q -> Entry(q.id, "question", q.phase, q.level, q.domain, textHash(q.prompt)) }
            val roleplays = pack.rolePlays.map { r -> Entry(r.id, "roleplay", OpiPhase.ROLEPLAY.wireName, r.level, r.domain, textHash(r.opening)) }
            return PromptAudioIndex(language = pack.language, prompts = (questions + roleplays).map(::keep).sortedBy { it.id })
        }

        /** The text each prompt id says, from the pack. */
        fun texts(pack: OpiPack): Map<String, String> = pack.questions.associate { it.id to it.prompt } + pack.rolePlays.associate { it.id to it.opening }
    }
}
