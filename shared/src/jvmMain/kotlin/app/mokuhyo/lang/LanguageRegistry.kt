package app.mokuhyo.lang

import java.io.File

/**
 * Builds the [LanguageModule] for every launch language from installed packs (BRIEF §4). [packsDir] holds
 * `<lang>/dictionary.sqlite` (and `ja/tokenizer.sqlite`); [dictionaryOpener] opens a dictionary pack (supplied by the
 * dictionary module so this file doesn't depend on its storage); [speech] lists voices.
 */
class LanguageRegistry(
    private val packsDir: File?,
    private val dictionaryOpener: (File, String) -> DictionaryPack? = { _, _ -> null },
    private val speech: () -> SpeechOutput? = { null },
) {
    private val modules = HashMap<String, LanguageModule>()

    val codes: List<String> get() = Languages.all.map { it.code }

    @Synchronized
    fun module(code: String): LanguageModule = modules.getOrPut(code) { build(Languages.require(code)) }

    fun all(): List<LanguageModule> = codes.map(::module)

    fun packFile(code: String, name: String): File? = packsDir?.let { File(File(it, code), name) }?.takeIf { it.isFile }

    private fun numbers(code: String) = app.mokuhyo.numbers.IcuNumbers(code)

    private fun build(info: LanguageInfo): LanguageModule {
        val dictionary = packFile(info.code, "dictionary.sqlite")?.let { dictionaryOpener(it, info.code) }
        val voices = { speech()?.voicesFor(info.code).orEmpty() }
        return when (info.code) {
            "ja" -> {
                val ja = JapaneseText(packFile("ja", "tokenizer.sqlite"))
                BaseLanguageModule(info, Scripts.of("ja"), ja::segment, dictionary, voices, IcuReadingAids("ja", ja::ruby), numbers = numbers(info.code))
            }
            "ko" -> {
                val icu = IcuSegmenter("ko")
                BaseLanguageModule(info, Scripts.of("ko"), icu::segment, dictionary, voices, IcuReadingAids("ko"), KoreanParticles::candidates, numbers(info.code))
            }
            else -> {
                val icu = IcuSegmenter(info.code)
                BaseLanguageModule(info, Scripts.of(info.code), icu::segment, dictionary, voices, IcuReadingAids(info.code), numbers = numbers(info.code))
            }
        }
    }
}
