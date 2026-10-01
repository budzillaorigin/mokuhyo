package app.mokuhyo.settings

import app.mokuhyo.db.MokuhyoDatabase

/**
 * Key/value settings over the `setting` table. [Scope.DEVICE] values (model tier, audio devices, GPU choice,
 * Ollama use) never leave this computer; [Scope.LEARNER] values travel in the `.mokuhyo` bundle (BRIEF §8.2).
 */
class Settings(private val db: MokuhyoDatabase) {
    enum class Scope(val id: String) { DEVICE("device"), LEARNER("learner") }

    fun get(key: Key): String? = db.settingsQueries.get(key.id).executeAsOneOrNull()

    fun put(key: Key, value: String) = db.settingsQueries.put(key.id, value, key.scope.id)

    fun remove(key: Key) = db.settingsQueries.remove(key.id)

    fun bool(key: Key, default: Boolean = false): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

    fun all(): Map<String, String> = db.settingsQueries.all().executeAsList().associate { it.key to it.value_ }

    enum class Key(val id: String, val scope: Scope) {
        // learner preferences
        CURRENT_LANGUAGE("learner.language", Scope.LEARNER),
        LANGUAGES("learner.languages", Scope.LEARNER),
        LEARNER_ID("learner.id", Scope.LEARNER),
        THEME("learner.theme", Scope.LEARNER),
        // device-local
        FIRST_RUN_DONE("device.firstRunDone", Scope.DEVICE),
        MODEL_TIER("device.model.tier", Scope.DEVICE),
        MODEL_ID("device.model.id", Scope.DEVICE),
        STT_MODEL_ID("device.stt.model", Scope.DEVICE),
        PREFER_CPU("device.native.preferCpu", Scope.DEVICE),
        USE_OLLAMA("device.ollama.use", Scope.DEVICE),
        OLLAMA_MODEL("device.ollama.model", Scope.DEVICE),
        INPUT_DEVICE("device.audio.input", Scope.DEVICE),
        OUTPUT_DEVICE("device.audio.output", Scope.DEVICE),
        UPDATE_CHECK("device.updates.auto", Scope.DEVICE),
        UPDATE_LAST_CHECK("device.updates.lastCheck", Scope.DEVICE),
        READING_ONLY("device.readingOnly", Scope.DEVICE),
        DEVELOPER("device.developer", Scope.DEVICE),
    }
}
