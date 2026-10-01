package app.mokuhyo.desktop

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.CompletionRequest
import app.mokuhyo.ai.LanguageModel
import app.mokuhyo.ai.OpenAICompatibleModel
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.lang.ScriptCheck
import app.mokuhyo.net.NetTimeouts
import app.mokuhyo.opi.OpiInterviewerTurn
import app.mokuhyo.opi.OpiPhase
import app.mokuhyo.opi.OpiRate
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.TopicTurn
import app.mokuhyo.opi.Turn
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `--eval-speaking --fixtures F --endpoint URL --models a,b --out O` (BRIEF §6.1, used by tools/models/eval_speaking.py):
 * runs the app's own speaking prompts and validators against each model on an OpenAI-compatible endpoint, for the
 * 60-prompt fixture set of each language, and writes raw outputs plus per-prompt metrics as JSON:
 * - jsonValid: the first answer parsed into the task's schema;
 * - valid: it also passed the task's validators (language purity, minimal edits, quotes from the candidate, …);
 * - purity: every target-language field is in the language (ScriptCheck);
 * - level: for rating prompts, the model's estimate (scored against the intended level by the Python side).
 * Register and agreement are judged by the reference model in Python.
 */
object EvalSpeaking {
    @Serializable
    data class Fixtures(val language: String, val registerNotes: String, val interviewer: List<InterviewerCase>, val topic: List<TopicCase>, val rating: List<RatingCase>)

    @Serializable
    data class InterviewerCase(val id: String, val phase: String, val level: String, val history: List<Turn>)

    @Serializable
    data class TopicCase(val id: String, val topic: String, val domain: String, val level: String, val history: List<Turn>)

    @Serializable
    data class RatingCase(val id: String, val intendedLevel: String, val history: List<Turn>)

    @Serializable
    data class Row(
        val model: String, val language: String, val id: String, val kind: String, val jsonValid: Boolean, val valid: Boolean,
        val purity: Boolean, val problems: List<String>, val output: String, val level: String? = null, val intendedLevel: String? = null,
        val ms: Long,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun run(args: Array<String>): Int = runBlocking {
        val fixtures = Smoke.arg(args, "--fixtures")?.split(",")?.map { File(it) } ?: return@runBlocking fail("--fixtures")
        val endpoint = Smoke.arg(args, "--endpoint") ?: return@runBlocking fail("--endpoint")
        val models = Smoke.arg(args, "--models")?.split(",") ?: return@runBlocking fail("--models")
        val out = File(Smoke.arg(args, "--out") ?: "eval-speaking.jsonl")
        val limit = Smoke.arg(args, "--limit")?.toInt() ?: Int.MAX_VALUE
        out.parentFile?.mkdirs()
        out.writeText("")
        for (fx in fixtures) {
            val f = json.decodeFromString(Fixtures.serializer(), fx.readText())
            for (m in models) {
                val model = OpenAICompatibleModel(Java.create(), endpoint, null, m, timeouts = NetTimeouts(5_000, 300_000, null))
                var n = 0
                f.interviewer.take(limit).forEach { c ->
                    val phase = OpiPhase.of(c.phase) ?: OpiPhase.LEVEL_CHECK
                    val input = OpiInterviewerTurn.Input(f.language, f.registerNotes, phase, IlrLevel.parse(c.level)!!, c.history, 1)
                    out.appendText(json.encodeToString(Row.serializer(), case(model, m, f.language, c.id, "interviewer", OpiInterviewerTurn(), input) { o ->
                        listOfNotNull(ScriptCheck.requireLanguage("utterance", o.utterance, f.language)) to null
                    }) + "\n")
                    n++
                }
                f.topic.take(limit).forEach { c ->
                    val input = TopicTurn.Input(f.language, f.registerNotes, c.topic, c.domain, IlrLevel.parse(c.level)!!, c.history)
                    out.appendText(json.encodeToString(Row.serializer(), case(model, m, f.language, c.id, "topic", TopicTurn(), input) { o ->
                        listOfNotNull(ScriptCheck.requireLanguage("reply", o.reply, f.language), ScriptCheck.requireLanguage("rewrite", o.rewrite, f.language)) to o.turnLevel
                    }) + "\n")
                    n++
                }
                f.rating.take(limit).forEach { c ->
                    val input = OpiRate.Input(f.language, c.history, f.registerNotes)
                    out.appendText(json.encodeToString(Row.serializer(), case(model, m, f.language, c.id, "rating", OpiRate(), input) { o ->
                        emptyList<String>() to o.estimate
                    }.copy(intendedLevel = c.intendedLevel)) + "\n")
                    n++
                }
                println("eval-speaking: ${f.language} $m $n prompts")
            }
        }
        0
    }

    private suspend fun <I, O> case(
        model: LanguageModel, name: String, lang: String, id: String, kind: String, task: PromptTask<I, O>, input: I,
        extra: (O) -> Pair<List<String>, String?>,
    ): Row {
        val messages: List<ChatMessage> = AiGateway.withJsonContract(task.messages(input), task.schema)
        val start = System.currentTimeMillis()
        val text = runCatching {
            model.complete(CompletionRequest(messages, maxTokens = task.maxTokens, temperature = task.temperature, jsonSchema = task.schema)).text
        }.getOrElse { return Row(name, lang, id, kind, false, false, false, listOf("error: ${it.message}"), "", ms = System.currentTimeMillis() - start) }
        val ms = System.currentTimeMillis() - start
        val parsed = AiGateway.extractJsonObject(text)?.let { runCatching { lenient.decodeFromString(task.serializer, it) }.getOrNull() }
            ?: return Row(name, lang, id, kind, false, false, false, listOf("output was not valid JSON for the schema"), text.take(2000), ms = ms)
        val problems = task.validate(input, parsed, ValidationContext())
        val (purityProblems, level) = extra(parsed)
        return Row(name, lang, id, kind, true, problems.isEmpty(), purityProblems.isEmpty(), problems, text.take(2000), level, ms = ms)
    }

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun fail(msg: String): Int {
        println("eval-speaking: FAIL $msg")
        return 1
    }

    @Suppress("unused")
    private val speakerRef = Speaker.LEARNER
}
