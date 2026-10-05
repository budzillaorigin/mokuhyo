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

    /**
     * `--eval-coherence --fixtures F --endpoint URL --models a,b --out O [--packs DIR] [--interviews N]` (BRIEF_PHASE8
     * N-00b): N whole practice interviews per language and model through the real [app.mokuhyo.opi.OpiSession], the
     * candidate answering with the fixture's learner turns. One row per interviewer turn — the question, the
     * transcript before it, and whether it came from the scripted bank — for eval_speaking.py --coherence to judge.
     */
    fun coherence(args: Array<String>): Int = runBlocking {
        val fixtures = Smoke.arg(args, "--fixtures")?.split(",")?.map { File(it) } ?: return@runBlocking fail("--fixtures")
        val endpoint = Smoke.arg(args, "--endpoint") ?: return@runBlocking fail("--endpoint")
        val models = Smoke.arg(args, "--models")?.split(",") ?: return@runBlocking fail("--models")
        val out = File(Smoke.arg(args, "--out") ?: "eval-coherence.jsonl").apply { parentFile?.mkdirs(); writeText("") }
        val packs = Smoke.arg(args, "--packs")?.let(::File) ?: Resources.repoDir?.let { File(it, "content/packs") } ?: return@runBlocking fail("--packs")
        val interviews = Smoke.arg(args, "--interviews")?.toInt() ?: 2
        // The candidate is simulated by the reference model answering each actual question at a set level: fixture
        // answers don't reply to the question asked, which made coherent interviewers look incoherent.
        val candidateModel = Smoke.arg(args, "--candidate-model")
        val candidate = candidateModel?.let { OpenAICompatibleModel(Java.create(), endpoint, null, it, timeouts = NetTimeouts(5_000, 300_000, null)) }
        val registry = app.mokuhyo.lang.LanguageRegistry(packs)
        for (fx in fixtures) {
            val f = json.decodeFromString(Fixtures.serializer(), fx.readText())
            val pack = app.mokuhyo.opi.OpiPack.parse(File(packs, "${f.language}/opi.json").readText())
            val answers = f.rating.flatMap { c -> c.history.filter { it.speaker == app.mokuhyo.opi.Speaker.LEARNER }.map { it.text } }
            val module = registry.module(f.language)
            for (m in models) {
                val model = OpenAICompatibleModel(Java.create(), endpoint, null, m, timeouts = NetTimeouts(5_000, 300_000, null))
                val gateway = app.mokuhyo.ai.AiGateway({ model }, app.mokuhyo.ai.AiSettings(timeoutMs = 300_000))
                repeat(interviews) { k ->
                    val session = app.mokuhyo.opi.OpiSession(f.language, pack.profile, pack.questions, pack.rolePlays, gateway,
                        { t -> module.segment(t).count { it.isWord } }, random = kotlin.random.Random(k))
                    var turn = 0
                    while (true) {
                        val before = session.transcript
                        val line = session.next() ?: break
                        out.appendText(kotlinx.serialization.json.buildJsonObject {
                            put("lang", kotlinx.serialization.json.JsonPrimitive(f.language)); put("model", kotlinx.serialization.json.JsonPrimitive(m))
                            put("interview", kotlinx.serialization.json.JsonPrimitive(k)); put("turn", kotlinx.serialization.json.JsonPrimitive(turn))
                            put("phase", kotlinx.serialization.json.JsonPrimitive(line.phase.wireName)); put("question", kotlinx.serialization.json.JsonPrimitive(line.text))
                            put("scripted", kotlinx.serialization.json.JsonPrimitive(line.engine == null))
                            put("reason", kotlinx.serialization.json.JsonPrimitive(line.fallbackReason ?: ""))
                            put("before", kotlinx.serialization.json.JsonPrimitive(before.takeLast(4).joinToString("\n") {
                                (if (it.speaker == app.mokuhyo.opi.Speaker.PARTNER) "Interviewer: " else "Candidate: ") + it.text }))
                        }.toString() + "\n")
                        val level = listOf("1", "2", "1+", "2+")[k % 4]
                        val reply = candidate?.let { c ->
                            runCatching {
                                c.complete(app.mokuhyo.ai.CompletionRequest(listOf(
                                    app.mokuhyo.ai.ChatMessage(app.mokuhyo.ai.Role.SYSTEM, "You are a learner of ${module.nameEnglish} at ILR speaking level $level " +
                                        "in a practice interview. Answer the interviewer's last question in ${module.nameEnglish} only, as a learner at that level " +
                                        "would (short and simple at 1, a paragraph at 2), with the errors typical of that level. Reply with the answer only."),
                                    app.mokuhyo.ai.ChatMessage(app.mokuhyo.ai.Role.USER, (session.transcript.takeLast(6).joinToString("\n") {
                                        (if (it.speaker == app.mokuhyo.opi.Speaker.PARTNER) "Interviewer: " else "Candidate: ") + it.text })),
                                ), maxTokens = 220, temperature = 0.7)).text.trim()
                            }.getOrNull()
                        }?.takeIf { it.isNotBlank() } ?: answers[(k * 7 + turn) % answers.size]
                        session.answer(reply)
                        turn++
                        if (turn > 30) break
                    }
                    println("eval-coherence: ${f.language} $m interview $k: $turn turns, ${session.scriptedTurns} scripted")
                }
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
