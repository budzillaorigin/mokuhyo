package app.tsumugi.audio

/**
 * The pre-rendered audio sets (BRIEF_V2 §5.6, D-090). Each ships as `audio-<id>.zip`, built by
 * `tools/packs/render_audio.py`, and is installed on demand (or bundled, D-097).
 */
enum class AudioSet(val id: String, internal val keyPrefixes: List<String>) {
    EXAM("exam", listOf("exam")),
    DIALOGUES("dialogues", listOf("dialogue")),
    MINIMAL_PAIRS("minimal-pairs", listOf("pair")),
    PITCH("pitch", listOf("pitch")),
    GRAMMAR("grammar", listOf("grammar")),
    READERS("readers", listOf("reader")),

    /**
     * Interest-track audio (`tracks.sqlite`, D-240): track dialogues keep the ordinary [AudioKeys.dialogue] key,
     * because track ids are prefixed and never equal a practice id (D-210, D-214), so the shared dialogue screens
     * look them up unchanged; memorize-and-perform lines use [AudioKeys.performance].
     */
    TRACKS("tracks", listOf("dialogue", "perform")),
    ;

    /** File name of the pack archive, e.g. `audio-pitch.zip`. */
    val fileName: String get() = "audio-$id.zip"

    /** True when [key]'s first segment is one this set's archive may contain. */
    fun owns(key: String): Boolean = key.substringBefore('/') in keyPrefixes

    companion object {
        fun fromId(id: String): AudioSet? = entries.firstOrNull { it.id == id }

        /** The primary set a clip key belongs to, from its first segment (`dialogue/…` → [DIALOGUES]). */
        fun ofKey(key: String): AudioSet? = entries.firstOrNull { it.owns(key) }

        /** Every set that may hold [key], primary first: `dialogue/…` is in [DIALOGUES] or [TRACKS]. */
        fun candidatesOf(key: String): List<AudioSet> = entries.filter { it.owns(key) }
    }
}

/**
 * Which word of a minimal pair. Named so it never shares a simple name with [app.tsumugi.study.PairSide]: the
 * Objective-C export flattens packages, and whichever type loses the coin toss is exported as `PairSide_`.
 */
enum class MinimalPairSide(internal val code: String) { A("a"), B("b") }

/**
 * Clip keys, the one contract between the renderer and the app (D-092). UI code builds keys only through these
 * helpers so a key the renderer wrote is always the key the player asks for:
 *
 * | set | key | source row |
 * |---|---|---|
 * | exam | `exam/<passage or item id>/<line index>` | `exam_passage.script` / `exam_item.script`, 0-based line |
 * | dialogues | `dialogue/<dialogue id>/<ord>` | `dialogue_line(dialogue_id, ord)` |
 * | minimal-pairs | `pair/<pair id>/a` or `/b` | `minimal_pair.id`, side a = `text_a` |
 * | pitch | `pitch/<item id>` | `items.json` in the pitch pack ([PitchTestItem.id]) |
 * | grammar | `grammar/<point id>/<ord>` | `grammar_example(point_id, ord)` |
 * | readers | `reader/<story id>/<sentence index>` | `readers.sqlite` `reader_sentence(story_id, idx)` |
 * | tracks | `dialogue/<track dialogue id>/<ord>` | `tracks.sqlite` `track_dialogue_line(dialogue_id, ord)` |
 * | tracks | `perform/<drill id>/<line index>` | `tracks.sqlite` `track_drill` of type perform, 0-based `lines` index |
 */
object AudioKeys {
    /** One line of an exam listening script. [ownerId] is the passage id, or the item id for per-item scripts. */
    fun exam(ownerId: String, lineIndex: Int): String = "exam/$ownerId/$lineIndex"

    /** Every line of an exam script, in order. */
    fun examScript(ownerId: String, lineCount: Int): List<String> = List(lineCount) { exam(ownerId, it) }

    /** A practice or track dialogue line (ids never collide: track ids carry their track prefix, D-210). */
    fun dialogue(dialogueId: String, lineOrd: Int): String = "dialogue/$dialogueId/$lineOrd"

    /** One line of a memorize-and-perform script: [lineIndex] is the index into `PerformDrill.lines`, both speakers. */
    fun performance(drillId: String, lineIndex: Int): String = "perform/$drillId/$lineIndex"

    fun minimalPair(pairId: Long, side: MinimalPairSide): String = "pair/$pairId/${side.code}"

    fun pitch(itemId: String): String = "pitch/$itemId"

    fun grammar(pointId: String, exampleOrd: Int): String = "grammar/$pointId/$exampleOrd"

    /** One read-along line of a graded reader: [sentenceIndex] is `reader_sentence.idx` (0-based, in body order). */
    fun reader(storyId: String, sentenceIndex: Int): String = "reader/$storyId/$sentenceIndex"

    /** Every read-along line of a story, in order. */
    fun readerStory(storyId: String, sentenceCount: Int): List<String> = List(sentenceCount) { reader(storyId, it) }

    /**
     * Relative file path of a clip inside an installed set's folder: the key's segments, each escaped to
     * `[A-Za-z0-9._-]` (anything else, and a leading dot, becomes `~XX` per UTF-8 byte) plus `.m4a`. Keys never
     * reach the file system unescaped, so a malformed key can't point outside the pack folder.
     */
    fun relativePath(key: String): String? {
        val segments = key.split('/')
        if (segments.size < 2 || segments.any { it.isEmpty() }) return null
        return segments.joinToString("/") { escape(it) } + ".m4a"
    }

    private fun escape(segment: String): String = buildString {
        segment.encodeToByteArray().forEachIndexed { i, b ->
            val c = (b.toInt() and 0xFF).toChar()
            val safe = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || (c == '.' && i > 0)
            if (safe) append(c) else append('~').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}
