package app.mokuhyo.desktop

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiSettings
import app.mokuhyo.ai.GgufReader
import app.mokuhyo.ai.LocalLlamaModel
import app.mokuhyo.ai.ModelKind
import app.mokuhyo.ai.WhisperRecognizer
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.opi.OpiSession
import app.mokuhyo.platform.AppDirs
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.tts.VoiceService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.TimeSource

/**
 * `--smoke-opi-full --language xx --model <gguf> --model-id <manifest id> --whisper <bin> [--fixtures <eval json>]`
 * (BRIEF §11.2 Phase 4 gate): a full OPI test-mode interview on a real model through the embedded engine. The
 * candidate's answers (simulated learner speech at ILR 2 from the eval fixtures) are spoken by the voice service and
 * transcribed by Whisper; the model rates the interview with evidence; the conversation and recordings are saved,
 * then reloaded and checked so the interview can be replayed.
 */
object SmokeOpiFull {
    fun run(args: Array<String>): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val lang = Smoke.arg(args, "--language") ?: return@runBlocking fail("--language")
        val modelFile = Smoke.arg(args, "--model")?.let(::File)?.takeIf { it.isFile } ?: return@runBlocking fail("--model <gguf>")
        val whisper = Smoke.arg(args, "--whisper")?.let(::File)?.takeIf { it.isFile } ?: return@runBlocking fail("--whisper <bin>")
        val dataDir = Smoke.arg(args, "--data-dir")?.let(::File) ?: Files.createTempDirectory("mokuhyo-opi").toFile()
        val app = AppGraph(AppDirs.ensure(dataDir))
        val info = app.manifest.models.firstOrNull { it.id == (Smoke.arg(args, "--model-id") ?: "eurollm-9b-instruct-q4km") }
            ?: return@runBlocking fail("unknown --model-id")
        val pack = app.opi(lang) ?: return@runBlocking fail("no opi pack for $lang (build_packs.py)")
        val module = app.languages.module(lang)
        val runtime = JniRuntime.tryCreate(preferCpu = "--cpu" in args)
        val llm = runtime.llm() ?: return@runBlocking fail("native runtime: ${runtime.status}")
        val stt = runtime.stt() ?: return@runBlocking fail("native runtime: ${runtime.status}")
        println("smoke-opi-full: $lang on ${info.name} (${runtime.status})")
        val gateway = AiGateway({ LocalLlamaModel(llm, info, modelFile.absolutePath, inspect = GgufReader::read) }, AiSettings(timeoutMs = 300_000))
        val recognizer = WhisperRecognizer(stt, whisper.absolutePath, app.manifest.models.first { it.kind == ModelKind.STT }.name)
        val voices = VoiceService.create()
        val answers = learnerAnswers(args, lang)
        if (answers.isEmpty()) return@runBlocking fail("no learner answers (eval fixtures for $lang)")
        val words: (String) -> Int = { s -> module.segment(s).count { it.isWord } }
        val session = OpiSession(lang, pack.profile, pack.questions, pack.rolePlays, gateway, words, IlrLevel.L1, test = true)
        val id = app.conversations.newId()
        val clock = TimeSource.Monotonic
        var turn = 0
        var modelTurns = 0
        val english = ArrayList<String>()
        while (true) {
            val t = clock.markNow()
            val line = session.next() ?: break
            if (line.engine != null) modelTurns++
            english += line.english
            val answer = answers[turn % answers.size]
            val spoken = voices.synthesize(answer, lang) ?: return@runBlocking fail("no voice for $lang")
            val pcm = AudioIO.toPcm16kMono(spoken.wav)
            val heard = recognizer.transcribe(pcm, module.sttLanguage).text
            val wav = AudioIO.wav(pcm)
            val rel = "recordings/$id/turn-%02d.wav".format(turn * 2 + 1)
            File(dataDir, rel).apply { parentFile.mkdirs() }.writeBytes(wav)
            app.conversations.addRecording(id, turn * 2 + 1, rel, pcm.size / 16L, sha(wav))
            session.answer(heard)
            english += ""
            println("smoke-opi-full: [${line.phase.wireName}] ${line.text.take(70)} → \"${heard.take(60)}\" (${t.elapsedNow().inWholeMilliseconds} ms)" +
                (line.fallbackReason?.let { " [scripted: $it]" } ?: ""))
            turn++
        }
        val t = clock.markNow()
        val rating = session.rate()
        println("smoke-opi-full: rating ${rating.estimate} (sustained ${rating.sustained}, breakdown ${rating.breakdown}) in ${t.elapsedNow().inWholeMilliseconds} ms; engine ${rating.engine}" +
            (rating.failure?.let { "; failure: $it" } ?: ""))
        rating.factors.forEach { (k, f) -> println("smoke-opi-full:   $k ILR ${f.level}: ${f.evidence.take(90)} quotes=${f.quotes.size}") }
        rating.nextSteps.forEach { println("smoke-opi-full:   next: $it") }
        app.conversations.save(id, app.learnerId, lang, "OPI_TEST", null, session.startedAt.toEpochMilliseconds(),
            StoredConversation(session.transcript, opiTurns = session.turns, english = english, engine = rating.engine), rating,
            session.turns.mapNotNull { it.levelAfter?.label }, "recordings/$id")
        rating.estimate?.takeIf { rating.engine != null }?.let { app.history.saveSpeakingEstimate(app.learnerId, lang, id, it, 0.5, false) }

        // Replay check: reload everything from the database and the recordings folder.
        val saved = app.conversations.get(id) ?: return@runBlocking fail("conversation not saved")
        val stored = app.conversations.stored(saved)
        val recs = app.conversations.recordings(id)
        val playable = recs.count { r -> File(dataDir, r.path).let { it.isFile && sha(it.readBytes()) == r.sha256 } }
        val reloaded = app.conversations.rating(saved)
        println("smoke-opi-full: saved ${stored.transcript.size} turns, ${recs.size} recordings ($playable verified), estimate ${reloaded?.estimate}, speaking trend ${app.history.latest(app.learnerId, lang)["SPEAKING"]?.value}")
        voices.close()
        app.close()
        val problems = buildList {
            if (session.turns.map { it.phase }.distinct().size < 5) add("not all five phases ran")
            if (modelTurns < session.turns.size / 2) add("most turns fell back to the scripted bank ($modelTurns/${session.turns.size})")
            if (rating.engine == null || rating.estimate == null) add("the model did not rate the interview")
            if (rating.factors.values.sumOf { it.quotes.size } == 0) add("no evidence quotes")
            if (recs.isEmpty() || playable != recs.size) add("recordings not replayable ($playable/${recs.size})")
            if (stored.transcript.size != session.transcript.size) add("transcript not saved intact")
        }
        problems.forEach { println("smoke-opi-full: FAIL $it") }
        println(if (problems.isEmpty()) "smoke-opi-full: OK" else "smoke-opi-full: FAILED")
        if (problems.isEmpty()) 0 else 1
    }

    /** Simulated candidate answers at ILR 2 (and 1+/2+ for variety) from tools/models/eval/<lang>.json. */
    private fun learnerAnswers(args: Array<String>, lang: String): List<String> {
        val file = Smoke.arg(args, "--fixtures")?.let(::File) ?: Resources.repoDir?.let { File(it, "tools/models/eval/$lang.json") }
        val root = file?.takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject } ?: return emptyList()
        return root["rating"]!!.jsonArray.map { it.jsonObject }.filter { it["intendedLevel"]!!.jsonPrimitive.content in setOf("1+", "2", "2+") }
            .flatMap { c -> c["history"]!!.jsonArray.map { it.jsonObject }.filter { it["speaker"]!!.jsonPrimitive.content == "LEARNER" }.map { it["text"]!!.jsonPrimitive.content } }
            .distinct()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun fail(msg: String): Int {
        println("smoke-opi-full: FAIL $msg")
        return 1
    }
}
