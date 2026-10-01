package app.mokuhyo.lang

/** Writing direction of a script. */
enum class Direction { LTR, RTL }

/**
 * The launch languages (BRIEF §2) as plain data. Everything language-specific beyond these facts hangs off the
 * per-language `LanguageModule` (BRIEF §4); this table is what the shell needs before any pack is loaded.
 */
data class LanguageInfo(
    /** BCP-47 code, the key for packs, data and settings. */
    val code: String,
    val nameEnglish: String,
    val nameNative: String,
    val direction: Direction = Direction.LTR,
    /** whisper.cpp language code. */
    val whisperCode: String,
)

object Languages {
    val all: List<LanguageInfo> = listOf(
        LanguageInfo("ja", "Japanese", "日本語", whisperCode = "ja"),
        LanguageInfo("es", "Spanish", "Español", whisperCode = "es"),
        LanguageInfo("fr", "French", "Français", whisperCode = "fr"),
        LanguageInfo("de", "German", "Deutsch", whisperCode = "de"),
        LanguageInfo("pt-BR", "Portuguese (Brazil)", "Português (Brasil)", whisperCode = "pt"),
        LanguageInfo("ru", "Russian", "Русский", whisperCode = "ru"),
        LanguageInfo("zh-Hans", "Chinese (Mandarin, Simplified)", "中文（简体）", whisperCode = "zh"),
        LanguageInfo("ko", "Korean", "한국어", whisperCode = "ko"),
        LanguageInfo("ar", "Arabic (MSA)", "العربية", Direction.RTL, whisperCode = "ar"),
        LanguageInfo("fa", "Persian (Farsi)", "فارسی", Direction.RTL, whisperCode = "fa"),
        LanguageInfo("id", "Indonesian", "Bahasa Indonesia", whisperCode = "id"),
    )

    private val byCode = all.associateBy { it.code }

    fun of(code: String): LanguageInfo? = byCode[code]

    fun require(code: String): LanguageInfo = byCode[code] ?: error("unknown language '$code'")
}
