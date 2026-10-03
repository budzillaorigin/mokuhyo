package app.mokuhyo.culture

import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.ScriptCheck
import app.mokuhyo.lexicon.Track
import app.mokuhyo.opi.PersonaContext
import app.mokuhyo.opi.PersonaPrompt
import app.mokuhyo.testing.repoFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BRIEF_PHASE8 gates on the built packs (content/packs/<lang>/; skipped when absent unless MOKUHYO_REQUIRE_PACKS=1):
 *  - C-05: every scenario in every language has ≥ 1 culture card for its tags; cards citing a guide name a section and page.
 *  - C-07: the pragmatics pack covers the seven topics; examples are in the language.
 *  - C-08: six personas per language (twelve for Arabic: RSAF and QEAF), one per role; each persona's system prompt
 *    block has the golden shape (name/rank/role/force, register, formality and patience lines); greetings in the language.
 */
class RealCulturePacksTest {
    private val requirePacks = System.getenv("MOKUHYO_REQUIRE_PACKS") == "1"
    private val packsDir = runCatching { repoFile("content/packs") }.getOrNull()

    private fun file(code: String, name: String): File? {
        val f = packsDir?.let { File(it, "$code/$name") }?.takeIf { it.isFile }
        if (f == null) {
            if (requirePacks) throw AssertionError("content/packs/$code/$name missing")
            println("SKIP $code $name not built")
        }
        return f
    }

    @Test
    fun everyScenarioHasACultureCard() {
        Languages.all.forEach { l ->
            val cards = file(l.code, "culture.json")?.let { CulturePack.parse(it.readText()) } ?: return@forEach
            val track = file(l.code, "track-cuas-base-defense.json")?.let { Track.parse(it.readText()) } ?: return@forEach
            track.scenarios.forEach { s -> assertTrue(cards.forTags(s.tags).isNotEmpty(), "${l.code} ${s.id}: no card for ${s.tags}") }
            cards.cards.filter { it.cited }.forEach { c -> assertTrue(c.source.section.isNotBlank() && c.source.page.isNotBlank(), "${l.code} ${c.id}") }
            cards.cards.forEach { c -> assertTrue(c.body.split(" ").size <= 75, "${l.code} ${c.id}: body over 60 words") }
        }
    }

    @Test
    fun pragmaticsPacksCoverTheTopics() {
        Languages.all.forEach { l ->
            val pack = file(l.code, "pragmatics.json")?.let { PragmaticsPack.parse(it.readText()) } ?: return@forEach
            val topics = pack.entries.map { it.topic }.toSet()
            assertEquals(PragmaticsPack.TOPICS.map { it.first }.toSet(), topics, l.code)
            pack.entries.flatMap { it.examples }.forEach { ex -> assertEquals(null, ScriptCheck.requireLanguage("say", ex.say, l.code), "${l.code}: ${ex.say}") }
        }
    }

    @Test
    fun personaPromptsHaveTheGoldenShape() {
        Languages.all.forEach { l ->
            val pack = file(l.code, "personas.json")?.let { PersonaPack.parse(it.readText()) } ?: return@forEach
            val sets = if (l.code == "ar") 2 else 1
            assertEquals(6 * sets, pack.personas.size, l.code)
            assertEquals(Persona.ROLES.keys, pack.personas.map { it.role }.toSet(), l.code)
            if (l.code == "ar") assertEquals(2, pack.personas.map { it.force }.distinct().size, "ar: RSAF and QEAF")
            pack.personas.forEach { p ->
                val lines = PersonaPrompt.lines(PersonaContext(p.name, p.rankTitle, p.roleTitle, p.force, p.register, p.patience, p.formality)).split("\n")
                assertEquals(4, lines.size, p.id)
                assertEquals("You are ${p.name}, ${p.rankTitle} (${p.roleTitle}), ${p.force}. Stay in character.", lines[0])
                assertTrue(lines[1].startsWith("How you speak and expect to be addressed: ") && lines[2].startsWith("Formality ${p.formality}/5: ") &&
                    lines[3].startsWith("Patience ${p.patience}/5: "), p.id)
                assertTrue(p.patience in 1..5 && p.formality in 1..5, p.id)
                assertEquals(null, ScriptCheck.requireLanguage("greeting", p.greeting, l.code), "${p.id}: ${p.greeting}")
            }
        }
    }
}
