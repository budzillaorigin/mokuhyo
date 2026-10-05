package app.mokuhyo.lang

/**
 * Everything language-specific hangs off one module per BCP-47 code (BRIEF §4). Nothing else in `shared/` may
 * hard-code a language (CLAUDE.md rule 4). Modules are built by [LanguageRegistry] from the language's pack.
 */
interface LanguageModule {
    val info: LanguageInfo
    val code: String get() = info.code
    val nameEnglish: String get() = info.nameEnglish
    val nameNative: String get() = info.nameNative
    val script: ScriptInfo

    /** Words, numbers and punctuation with offsets into [text]. ICU word break by default. */
    fun segment(text: String): List<Token>

    /** Dictionary forms for [token] (inflected form → lemma via the pack's form index); [token]'s text if none. */
    fun lemma(token: Token): List<String>

    /** Comparison key: case, width, diacritics/tashkeel, ё/е… folded so learner input matches the key loosely. */
    fun normalizeForCompare(s: String): String

    val dictionary: DictionaryPack?
    val voices: List<VoiceSpec>

    /** whisper.cpp language code. */
    val sttLanguage: String get() = info.whisperCode

    val readingAids: ReadingAids

    /** Number grammar for the numbers drill (BRIEF_PHASE8 N-02); null when the platform has none. */
    val numbers: app.mokuhyo.numbers.NumberGrammar? get() = null
}

/** Writing-system facts the UI and the segmenter need. */
data class ScriptInfo(
    val direction: Direction,
    /** True for scripts written without spaces between words (ja, zh). */
    val needsSegmentation: Boolean,
    val hasSpaces: Boolean,
    val hasCase: Boolean,
    /** Bundled Noto family that covers the script, e.g. "Noto Sans JP". */
    val fontFamily: String,
)

/** One segment of a text. [start]/[end] are UTF-16 offsets into the original string. */
data class Token(
    val text: String,
    val start: Int,
    val end: Int,
    /** True for words (letters/ideographs), false for spaces, punctuation and symbols. */
    val isWord: Boolean,
    /** Dictionary form when the segmenter knows it (ja lattice tokenizer); else null. */
    val lemmaHint: String? = null,
    /** Reading when the segmenter knows it (ja: hiragana); else null. */
    val reading: String? = null,
)

/** Optional reading helps per language (BRIEF §4 table). Each returns null when it has nothing to add. */
interface ReadingAids {
    /** Pronunciation guide above a word: furigana (ja), pinyin (zh). */
    fun ruby(token: Token): String? = null

    /** Latin transcription of a whole text: romaji, pinyin, RR (ko), scientific (ru), ALA-LC-like (ar, fa). */
    fun romanize(text: String): String? = null

    /** Arabic only: strip short vowels (tashkeel) when the learner turns them off. */
    fun stripVowelMarks(text: String): String = text

    val available: Set<Aid> get() = emptySet()

    enum class Aid { RUBY, ROMANIZATION, VOWEL_MARKS, TRADITIONAL }

    object None : ReadingAids
}

/** A voice the voice service can speak with (BRIEF §3.3). */
data class VoiceSpec(
    val id: String,
    val language: String,
    /** "female" | "male"; scripts ask for one of these per speaker. */
    val gender: String,
    /** "piper" (bundled, out-of-process), "kokoro", "os" (system voice), "clips" (pre-rendered pack only). */
    val engine: String,
    val license: String,
    /** Speaker index for multi-speaker models, else null. */
    val speaker: Int? = null,
)
