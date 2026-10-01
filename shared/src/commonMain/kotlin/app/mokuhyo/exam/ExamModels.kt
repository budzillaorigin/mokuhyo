package app.mokuhyo.exam

import kotlinx.serialization.Serializable

enum class ExamKind(val title: String) {
    DLPT_READING("DLPT Reading"),
    DLPT_LISTENING("DLPT Listening"),
    OPI("OPI"),
    ;

    val isDlpt: Boolean get() = this == DLPT_READING || this == DLPT_LISTENING
}

enum class ExamMode(val title: String) {
    /** Untimed practice: instant feedback, replayable audio, tap-to-define. */
    PRACTICE("Practice"),
    /** DLPT full length (~3 h) and 30/60-minute slices. */
    FULL("Full length"),
    SLICE_60("60-minute slice"),
    SLICE_30("30-minute slice"),
    INTERVIEW("Interview"),
    ;

    /** Strict modes play listening audio once and hide answers until the end, like the real tests. */
    val strict: Boolean get() = this == FULL || this == SLICE_60 || this == SLICE_30
}

/** One line of listening audio, rendered with on-device TTS ([voice] = "female" | "male"). */
@Serializable
data class ScriptLine(val speaker: String = "", val voice: String = "female", val text: String)

data class ExamPassage(
    val id: String,
    val exam: ExamKind,
    val level: String,
    val textType: String,
    val title: String,
    val body: String,
    val script: List<ScriptLine>,
    val source: String,
    val verified: Boolean,
) {
    val aiGenerated: Boolean get() = source == "llm" && !verified
}

data class ExamItem(
    val id: String,
    val bank: String,
    val exam: ExamKind,
    val level: String,
    val type: String,
    val passageId: String?,
    val stem: String,
    val choices: List<String>,
    val answer: Int,
    val explanation: String,
    val script: List<ScriptLine>,
    val refs: List<String>,
    val source: String,
    val verified: Boolean,
) {
    val aiGenerated: Boolean get() = source == "llm" && !verified
    val hasAudio: Boolean get() = script.isNotEmpty()
}

/** Item-bank file format (docs/CONTENT_PACKS.md "Exam item banks"); also what users import. */
@Serializable
data class ExamBankFile(
    val bank: String,
    val title: String = bank,
    val license: String = "",
    val attribution: String = "",
    val passages: List<PassageJson> = emptyList(),
    val items: List<ItemJson> = emptyList(),
) {
    @Serializable
    data class PassageJson(
        val id: String,
        val exam: String,
        val level: String,
        val textType: String = "",
        val title: String = "",
        val body: String = "",
        val script: List<ScriptLine> = emptyList(),
        val source: String = "human",
        val verified: Boolean = false,
    )

    @Serializable
    data class ItemJson(
        val id: String,
        val exam: String,
        val level: String,
        val type: String,
        val passageId: String? = null,
        val stem: String,
        val choices: List<String>,
        val answer: Int,
        val explanation: String = "",
        val script: List<ScriptLine> = emptyList(),
        val refs: List<String> = emptyList(),
        val source: String = "human",
        val verified: Boolean = false,
        val ord: Int = 0,
    )
}
