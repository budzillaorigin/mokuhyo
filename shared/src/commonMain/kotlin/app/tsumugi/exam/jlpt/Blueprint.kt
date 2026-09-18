package app.tsumugi.exam.jlpt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Published JLPT structure, loaded from `tools/items/jlpt_blueprints.json` (bundled in exam.sqlite). */
@Serializable
data class JlptBlueprints(val scoring: ScoringRules = ScoringRules(), val levels: List<LevelBlueprint>) {
    fun level(level: Int): LevelBlueprint? = levels.firstOrNull { it.level == level }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): JlptBlueprints = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class ScoringRules(
    val sectionMax: Int = 60,
    val combinedMax: Int = 120,
    val sectionMinimum: Int = 19,
    val combinedMinimum: Int = 38,
)

@Serializable
data class LevelBlueprint(val level: Int, val passMark: Int, val sections: List<SectionBlueprint>) {
    /** Score groups in report order (e.g. language, reading, listening; or language_reading, listening for N4/N5). */
    val groups: List<String> get() = sections.flatMap { it.scoreGroups }.distinct()
    val totalMinutes: Int get() = sections.sumOf { it.minutes }
    val itemCount: Int get() = sections.sumOf { s -> s.items.sumOf { it.count } }
}

@Serializable
data class SectionBlueprint(
    val id: String,
    val title: String,
    val minutes: Int,
    val scoreGroups: List<String>,
    val items: List<ItemSpec>,
) {
    val isListening: Boolean get() = scoreGroups == listOf(JlptScoring.LISTENING)
}

@Serializable
data class ItemSpec(val type: String, val title: String, val count: Int, val group: String)
