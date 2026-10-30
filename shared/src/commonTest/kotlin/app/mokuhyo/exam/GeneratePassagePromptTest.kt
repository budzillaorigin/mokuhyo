package app.mokuhyo.exam

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** QC 2026-10-30 V9: listening scripts get a spoken-genre instruction, not the written "authentic document" one. */
class GeneratePassagePromptTest {
    private fun prompt(skill: Skill, textType: String) = GeneratePassage().messages(
        GeneratePassage.Input("es", skill, "2", textType, "un tema", { it.split(' ').size }),
    ).joinToString("\n") { it.content }

    @Test
    fun listeningAsksForASpokenGenreWithRelationshipPurposeRegisterAndSituation() {
        for ((type, marker) in listOf("conversation" to "relationship set the register", "briefing" to "for what purpose")) {
            val text = prompt(Skill.LISTENING, type)
            assertFalse("document" in text, "$type: $text")
            assertTrue(marker in text && "[pause]" in text, "$type: $text")
        }
    }

    @Test
    fun readingKeepsTheWrittenDocumentInstruction() {
        assertTrue("authentic document of its type written by a native speaker" in prompt(Skill.READING, "report"))
    }
}
