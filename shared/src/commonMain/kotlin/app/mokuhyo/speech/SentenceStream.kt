package app.mokuhyo.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Decodes the string value of the top-level key [field] from a JSON object that arrives in pieces (model tokens), as
 * soon as each character is known (BRIEF_INTERVIEW_SPEED N3). Escapes are decoded, including `\uXXXX` and surrogate
 * pairs split across pieces. Text outside that one value — other keys, partial JSON — never comes out.
 */
class JsonStringField(private val field: String) {
    private var depth = 0
    private var inString = false
    private var escape = false
    private var hex: StringBuilder? = null
    private var highSurrogate: Char? = null
    private var keyNext = false
    private var collectingKey = false
    private val key = StringBuilder()
    private var lastKey: String? = null
    private var expectValue = false
    private var inTarget = false

    /** The value's closing quote has been seen: nothing more will come out. */
    var closed = false
        private set

    fun feed(piece: String): String {
        val out = StringBuilder()
        for (c in piece) step(c, out)
        return out.toString()
    }

    private fun emit(c: Char, out: StringBuilder) {
        when {
            inTarget -> out.append(c)
            collectingKey -> key.append(c)
        }
    }

    private fun emitCode(code: Int, out: StringBuilder) {
        val c = code.toChar()
        when {
            c.isHighSurrogate() -> highSurrogate = c
            c.isLowSurrogate() -> highSurrogate?.let { emit(it, out); emit(c, out) }.also { highSurrogate = null }
            else -> { highSurrogate = null; emit(c, out) }
        }
    }

    private fun step(c: Char, out: StringBuilder) {
        if (inString) {
            val h = hex
            when {
                h != null -> {
                    h.append(c)
                    if (h.length == 4) {
                        hex = null
                        h.toString().toIntOrNull(16)?.let { emitCode(it, out) }
                    }
                }
                escape -> {
                    escape = false
                    when (c) {
                        'u' -> hex = StringBuilder()
                        'n' -> emit('\n', out)
                        't' -> emit('\t', out)
                        'r' -> emit('\r', out)
                        'b' -> emit('\b', out)
                        'f' -> emit('\u000C', out)
                        else -> emit(c, out) // \" \\ \/
                    }
                }
                c == '\\' -> escape = true
                c == '"' -> {
                    inString = false
                    if (inTarget) {
                        inTarget = false
                        closed = true
                    } else if (collectingKey) {
                        collectingKey = false
                        lastKey = key.toString()
                    }
                }
                else -> emit(c, out)
            }
            return
        }
        when (c) {
            '{', '[' -> { depth++; keyNext = depth == 1 && c == '{' }
            '}', ']' -> depth--
            '"' -> {
                inString = true
                when {
                    expectValue -> { expectValue = false; inTarget = !closed }
                    depth == 1 && keyNext -> { keyNext = false; collectingKey = true; key.clear() }
                }
            }
            ':' -> expectValue = depth == 1 && lastKey == field
            ',' -> { lastKey = null; expectValue = false; keyNext = depth == 1 }
            else -> if (!c.isWhitespace()) expectValue = false
        }
    }
}

/** Where complete sentences end in a growing text. */
fun interface SentenceSplitter {
    /**
     * End offsets (exclusive, in order) of the complete sentences in [text]. A sentence is complete only once the text
     * goes on after it (so "3." isn't cut before "5"), or when [final]: then the rest of the text is the last sentence.
     */
    fun ends(text: String, final: Boolean): List<Int>
}

/**
 * Boundaries no splitter may use (QC 2026-10-30 V1; the same rules as tools/voices/textsplit.py, tested against the
 * shared fixtures/segmentation_cases.json): inside a number (`3.5`, `8.000`, `12:30`), after an abbreviation or an
 * initial (`Sr. García`, `J. R. R. Tolkien`), before a lowercase word (`Bueno... no sé`, `"Ready?" he asked`), and
 * after a closed quote that a quotative particle continues (`「はい。」と言った`; `「はい。」そう…` still splits).
 */
object SentenceGuard {
    const val TERMINATORS = ".!?…。！？؟؛।"
    private const val NO_SPACE_NEEDED = "。！？؟؛।"
    const val CLOSERS = "\"'”’」』）)]»›"
    private const val QUOTATIVE = "とっ"
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "sr", "sra", "srta", "dra", "lic", "ing", "m", "mm", "mme", "mlle", "hr", "fr",
        "z", "b", "nr", "vs", "etc", "gen", "col", "lt", "sgt", "capt", "cpt", "maj", "adm", "no", "tel",
        "г", "гг", "ул", "д", "т", "е", "им", "проф", "св",
    )

    /** Whether a sentence may end at [end] (exclusive; trailing spaces before it are allowed) in [text]. */
    fun allows(text: String, end: Int): Boolean {
        var e = end
        while (e > 0 && text[e - 1].isWhitespace()) e--
        var t = e
        while (t > 0 && text[t - 1] in CLOSERS) t--
        val closed = t < e
        var r = t
        while (r > 0 && text[r - 1] in TERMINATORS) r--
        if (r == t) return true // not punctuation (a line break, say)
        val run = text.substring(r, t)
        var k = e
        while (k < text.length && text[k].isWhitespace()) k++
        val spaced = k > e
        val next = text.getOrNull(k) ?: return true
        if (run.any { it in NO_SPACE_NEEDED }) return !(closed && !spaced && next in QUOTATIVE)
        if (!spaced) return false
        if (next.isLowerCase()) return false
        if (run == "." && !closed) {
            var w = r
            while (w > 0 && text[w - 1].isLetter()) w--
            val word = text.substring(w, r)
            if (word.lowercase() in ABBREVIATIONS || (word.length == 1 && word[0].isUpperCase())) return false
        }
        return true
    }
}

/**
 * Sentence ends by punctuation, for every launch language: `. ! ? …` followed by a space (Latin, Cyrillic, Hangul,
 * Arabic-script text), the full-width `。！？` and `؟ ؛ ।` even with no space after them, closing quotes and brackets
 * staying with their sentence. The JVM uses ICU's sentence rules first ([app.mokuhyo.lang.IcuSentences]); this is the
 * portable fallback.
 */
object PunctuationSentences : SentenceSplitter {
    private const val TERMINATORS = ".!?…。！？؟؛।"
    private const val NO_SPACE_NEEDED = "。！？؟؛।…"
    private const val CLOSERS = "\"'”’」』）)]»›"

    override fun ends(text: String, final: Boolean): List<Int> {
        val out = ArrayList<Int>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in TERMINATORS) {
                var j = i + 1
                while (j < text.length && (text[j] in TERMINATORS || text[j] in CLOSERS)) j++
                val boundary = when {
                    j >= text.length -> false
                    text[j].isWhitespace() -> true
                    else -> text.substring(i, j).any { it in NO_SPACE_NEEDED }
                }
                if (boundary && SentenceGuard.allows(text, j) && text.substring(start, j).any { it.isLetterOrDigit() }) {
                    out += j
                    start = j
                }
                i = j
                continue
            }
            i++
        }
        if (final && start < text.length && text.substring(start).any { it.isLetterOrDigit() }) out += text.length
        return out
    }
}

/**
 * Complete, checked sentences from a streamed reply (N3): [JsonStringField] pulls the spoken field out of the JSON,
 * [splitter] finds sentence ends, and [check] vets each sentence (script, language, repetition) before it may be
 * spoken. The first sentence that fails stops the stream: nothing after it comes out, and [problem] says why.
 */
class SentenceStream(
    field: String,
    private val splitter: SentenceSplitter,
    private val check: (sentence: String, spoken: List<String>) -> String?,
) {
    private val json = JsonStringField(field)
    private val text = StringBuilder()
    private var taken = 0

    /** Sentences already handed out (and so possibly already playing). */
    val spoken = ArrayList<String>()

    var problem: String? = null
        private set

    /** The spoken field so far, decoded. */
    val textSoFar: String get() = text.toString()

    /** Feeds one piece of model output; returns the sentences it completed. */
    fun feed(piece: String): List<String> {
        if (problem != null) return emptyList()
        text.append(json.feed(piece))
        return take(final = json.closed)
    }

    /** Generation ended: the rest of the field is the last sentence. */
    fun finish(): List<String> = if (problem != null) emptyList() else take(final = true)

    private fun take(final: Boolean): List<String> {
        val all = text.toString()
        val out = ArrayList<String>()
        for (end in splitter.ends(all, final)) {
            if (end <= taken) continue
            val sentence = all.substring(taken, end).trim()
            taken = end
            if (sentence.isEmpty()) continue
            check(sentence, spoken)?.let { problem = it; return out }
            spoken += sentence
            out += sentence
        }
        return out
    }
}

/**
 * Speaks a reply sentence by sentence as the sentences arrive (N3): sentence N+1 is synthesized while sentence N plays,
 * through one bounded slot. [synth] returns null when nothing can speak it; [play] returns false when playback failed.
 * Either stops the reply (remaining sentences are drained, never spoken), and [Outcome] says how far it got.
 * Cancelling the caller stops synthesis and playback.
 */
class SpokenReply<A : Any>(private val synth: suspend (String) -> A?, private val play: suspend (A) -> Boolean) {
    data class Outcome(val played: Int, val failed: Boolean)

    suspend fun speak(sentences: ReceiveChannel<String>): Outcome = coroutineScope {
        val audio = Channel<A?>(1)
        val stop = CompletableDeferred<Unit>()
        launch {
            try {
                for (s in sentences) {
                    if (stop.isCompleted) continue // keep draining so the sender never blocks
                    val a = synth(s)
                    audio.send(a)
                    if (a == null) stop.complete(Unit)
                }
            } finally {
                audio.close()
            }
        }
        var played = 0
        for (a in audio) {
            if (stop.isCompleted) continue
            if (a == null || !play(a)) stop.complete(Unit) else played++
        }
        Outcome(played, stop.isCompleted)
    }
}
