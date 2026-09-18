package app.tsumugi.exam

import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.jlpt.JlptBlueprints
import app.tsumugi.platform.normalizeNfc

/**
 * Structural checks for an item bank (docs/CONTENT_PACKS.md "Exam item banks"), run on user imports. Mirrors the
 * schema part of tools/packs/build_exam.py; the build also checks ILR length/kanji bands, which imports skip.
 */
object ExamBankValidator {
    private val jlptLevels = setOf("N5", "N4", "N3", "N2", "N1")
    private val dlptTypes = setOf("main_idea", "detail", "inference", "purpose", "vocabulary_in_context", "tone")

    fun validate(bank: ExamBankFile, blueprints: JlptBlueprints?): List<String> {
        val errors = mutableListOf<String>()
        if (bank.bank.isBlank()) errors += "bank: id is empty"
        val passageIds = mutableSetOf<String>()
        bank.passages.forEach { p ->
            if (!passageIds.add(p.id)) errors += "passage ${p.id}: duplicate id"
            val exam = examOf(p.exam)
            if (exam == null) errors += "passage ${p.id}: unknown exam '${p.exam}'"
            else if (!levelOk(exam, p.level)) errors += "passage ${p.id}: bad level '${p.level}' for ${p.exam}"
            if (p.body.isBlank() && p.script.isEmpty()) errors += "passage ${p.id}: needs a body or a script"
            if (!isNfc(p.body) || p.script.any { !isNfc(it.text) }) errors += "passage ${p.id}: text is not NFC"
        }
        val itemIds = mutableSetOf<String>()
        bank.items.forEach { i ->
            if (!itemIds.add(i.id)) errors += "item ${i.id}: duplicate id"
            val exam = examOf(i.exam)
            if (exam == null) {
                errors += "item ${i.id}: unknown exam '${i.exam}'"
            } else {
                if (!levelOk(exam, i.level)) errors += "item ${i.id}: bad level '${i.level}' for ${i.exam}"
                if (exam == ExamKind.JLPT && blueprints != null) {
                    val level = i.level.removePrefix("N").toIntOrNull()
                    val types = level?.let { blueprints.level(it) }?.sections?.flatMap { s -> s.items.map { it.type } }.orEmpty()
                    if (i.type !in types) errors += "item ${i.id}: type '${i.type}' isn't on the N$level blueprint"
                }
                if (exam.isDlpt && i.type !in dlptTypes) errors += "item ${i.id}: unknown DLPT type '${i.type}'"
            }
            if (i.stem.isBlank() && i.script.isEmpty()) errors += "item ${i.id}: empty stem"
            if (i.choices.size !in 3..4) errors += "item ${i.id}: needs 3 or 4 choices"
            if (i.choices.any { it.isBlank() } || i.choices.toSet().size != i.choices.size) errors += "item ${i.id}: choices must be distinct and non-empty"
            if (i.answer !in i.choices.indices) errors += "item ${i.id}: answer ${i.answer} out of range"
            if (i.passageId != null && i.passageId !in passageIds) errors += "item ${i.id}: unknown passage ${i.passageId}"
            if (!isNfc(i.stem) || i.choices.any { !isNfc(it) }) errors += "item ${i.id}: text is not NFC"
        }
        return errors
    }

    fun examOf(name: String): ExamKind? = ExamKind.entries.firstOrNull { it.name == name && it != ExamKind.OPI }

    private fun levelOk(exam: ExamKind, level: String): Boolean =
        if (exam == ExamKind.JLPT) level in jlptLevels else IlrLevel.parse(level) != null

    private fun isNfc(text: String): Boolean = normalizeNfc(text) == text
}
