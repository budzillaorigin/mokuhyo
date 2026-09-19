package app.tsumugi.onomatopoeia

/** The three kinds of Japanese sound-symbolic words (BRIEF_V2 §6.8). */
enum class OnomatopoeiaType(val labelJa: String, val labelEn: String) {
    /** Imitates a real sound, voices included (ざあざあ, ワンワン). */
    GIONGO("擬音語", "sound"),
    /** Depicts a state, look, movement or manner without sound (きらきら, のろのろ). */
    GITAIGO("擬態語", "state or manner"),
    /** Depicts an inner feeling (いらいら, わくわく). */
    GIJOUGO("擬情語", "feeling"),
    ;

    companion object {
        fun of(code: String): OnomatopoeiaType? = entries.firstOrNull { it.name == code.uppercase() }
    }
}

/** A theme group with its original SVG glyph (one per theme, never per word; D-236). [count] = words in the theme. */
data class OnomatopoeiaTheme(
    val id: String,
    val order: Int,
    val title: String,
    val titleJa: String,
    val blurb: String,
    /** A complete `<svg>` document, viewBox 0 0 64 64, drawn with `currentColor` so it follows the text color. */
    val svg: String,
    val count: Int,
)

/**
 * One onomatopoeia (a JMdict entry tagged on-mim). [glosses] are JMdict's (CC BY-SA 4.0); [feel] and [feelJa] are our
 * own one-line descriptions ("" when not written yet). [aiGenerated]: theme, type and feel are LLM-drafted and not yet
 * reviewed, so the UI shows the badge (CLAUDE.md rule 10). Words nobody has described yet are grouped by keyword rules
 * and show only gloss and examples (no badge).
 */
data class OnomatopoeiaWord(
    val entryId: Long,
    /** Frequency order, 1 = most frequent. */
    val order: Int,
    val text: String,
    val variants: List<String>,
    val type: OnomatopoeiaType,
    val theme: String,
    val glosses: List<String>,
    val feel: String,
    val feelJa: String,
    val exampleIds: List<Long>,
    val aiGenerated: Boolean,
) {
    val hasFeel: Boolean get() = feel.isNotBlank()
}

/** A Tatoeba example sentence (CC BY 2.0 FR). */
data class OnomatopoeiaExample(val sentenceId: Long, val japanese: String, val english: String)

data class OnomatopoeiaDetail(val word: OnomatopoeiaWord, val theme: OnomatopoeiaTheme?, val examples: List<OnomatopoeiaExample>)

enum class OnomatopoeiaQuizKind {
    /** A scene (the feel line) is shown; pick the word that fits. */
    WORD_FOR_SCENE,
    /** A word is shown; pick the scene that fits it. */
    SCENE_FOR_WORD,
}

/**
 * One quiz question. [prompt] is the scene (WORD_FOR_SCENE) or the word (SCENE_FOR_WORD); [choices] are words or scenes
 * accordingly, with [answer] the index of the right one. [options] are the words behind the choices, in the same order,
 * so the UI can reveal each option's word and feel after answering.
 */
data class OnomatopoeiaQuestion(
    val kind: OnomatopoeiaQuizKind,
    val target: OnomatopoeiaWord,
    val prompt: String,
    /** The scene in Japanese, when the target has one (WORD_FOR_SCENE, monolingual mode). */
    val promptJa: String,
    val choices: List<String>,
    val options: List<OnomatopoeiaWord>,
    val answer: Int,
) {
    fun isCorrect(choice: Int): Boolean = choice == answer
}
