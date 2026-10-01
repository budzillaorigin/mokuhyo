package app.mokuhyo.lang.ja

/** Inflection class of a dictionary form. Drives both deinflection and conjugation tables. */
enum class WordClass {
    ICHIDAN,
    GODAN,
    KURU,
    SURU,
    ADJ_I,
    ADJ_NA,
    ;

    companion object {
        /** Maps a JMdict part-of-speech code (e.g. "v5k", "adj-i") to its inflection class, or null if it doesn't inflect. */
        fun fromJmdictPos(pos: String): WordClass? = when (pos) {
            "v1", "v1-s" -> ICHIDAN
            "v5k", "v5k-s", "v5r", "v5r-i", "v5aru", "v5u", "v5u-s", "v5g", "v5s", "v5t", "v5n", "v5b", "v5m" -> GODAN
            "vk" -> KURU
            "vs", "vs-i", "vs-s" -> SURU
            "adj-i", "adj-ix" -> ADJ_I
            "adj-na" -> ADJ_NA
            else -> null
        }
    }
}
