package app.mokuhyo.lang

/**
 * Speaker variety (BRIEF_PHASE8 N-07): which voice reads a line. Passages rotate through the language's voices by a
 * stable hash of their id, distinct speakers in one script get distinct voices whenever the language has enough of
 * the line's gender, and a persona keeps one voice of their gender across sessions.
 */
object VoiceRotation {
    /** A stable non-negative hash (String.hashCode is stable on the JVM, but spell it out so packs don't depend on it). */
    fun stableHash(key: String): Int {
        var h = 0x811C9DC5.toInt()
        key.forEach { c -> h = (h xor c.code) * 0x01000193 }
        return h and Int.MAX_VALUE
    }

    /**
     * The voice for each speaker of a script ([speakers] = (speaker name, gender) in order of first appearance), keyed
     * by [key] (the passage or dialogue id). Same-gender speakers take consecutive voices from a rotated start, so two
     * speakers never share a voice while the gender has two or more.
     */
    fun assign(voices: List<VoiceSpec>, speakers: List<Pair<String, String>>, key: String): Map<String, VoiceSpec> {
        if (voices.isEmpty()) return emptyMap()
        val start = stableHash(key)
        val used = HashMap<String, Int>()
        val taken = HashSet<VoiceSpec>()
        return speakers.associate { (name, gender) ->
            val pool = voices.filter { it.gender == gender }.ifEmpty { voices }
            val n = used.getOrDefault(gender, 0)
            used[gender] = n + 1
            // Prefer a voice no earlier speaker has, starting from the rotated position.
            val ordered = List(pool.size) { pool[(start + n + it) % pool.size] }
            name to (ordered.firstOrNull { it !in taken } ?: ordered.first()).also { taken += it }
        }
    }

    /** A persona's voice: one of their gender, chosen by persona id so it stays the same every session. */
    fun forPersona(voices: List<VoiceSpec>, personaId: String, gender: String): VoiceSpec? {
        val pool = voices.filter { it.gender == gender }.ifEmpty { voices }
        return pool.takeIf { it.isNotEmpty() }?.get(stableHash(personaId) % pool.size)
    }
}
