package app.mokuhyo.ai.prompts

import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Role

/** One line of a conversation transcript, used by role-play and the OPI simulator. */
data class Turn(val speaker: Speaker, val text: String)

enum class Speaker { LEARNER, PARTNER }

/** Every prompt in the library, by name (BRIEF §7.2). Tasks with fallback hooks are built with defaults here. */
object PromptLibrary {
    val all: List<PromptTask<*, *>> = listOf(
        CorrectSentence(),
        NaturalRewrite(),
        TranslateSentence(),
        RoleplayTurn(),
        OpiInterviewerTurn(),
        OpiRate(),
        FreeTalkTurn(),
        GradeProduction(),
        GradeTranslation(),
    )

    val names: List<String> get() = all.map { it.name }
}

internal const val HOUSE_RULES =
    "You are a careful Japanese teacher inside a study app. Be accurate; if you are not sure, say so rather than guess. " +
        "Write Japanese fields in natural standard Japanese (kanji and kana, no romaji). Write explanation fields in plain English."

internal fun system(vararg lines: String) = ChatMessage(Role.SYSTEM, (listOf(HOUSE_RULES) + lines).joinToString("\n"))

internal fun user(text: String) = ChatMessage(Role.USER, text)

internal fun transcript(history: List<Turn>, learner: String, partner: String): String =
    history.joinToString("\n") { t -> (if (t.speaker == Speaker.LEARNER) learner else partner) + ": " + t.text }

internal fun issues(vararg problems: String?): List<String> = problems.filterNotNull()
