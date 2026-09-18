package app.tsumugi.dictionary

import app.tsumugi.jp.FuriganaSegment
import kotlinx.serialization.json.Json

/** Decoders for the compact encodings used in the dictionary pack (see dictionary.sq header). */
internal object PackCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** JSON string array; "" encodes both [] and ["*"], which callers treat as "empty / applies to all". */
    fun strings(value: String): List<String> =
        if (value.isEmpty()) emptyList() else json.decodeFromString<List<String>>(value)

    /** "食=た|べ|物=もの" → [食(た), べ, 物(もの)]. */
    fun furigana(value: String): List<FuriganaSegment> =
        value.split('|').filter { it.isNotEmpty() }.map { part ->
            val eq = part.indexOf('=')
            if (eq < 0) FuriganaSegment(part) else FuriganaSegment(part.substring(0, eq), part.substring(eq + 1))
        }
}
