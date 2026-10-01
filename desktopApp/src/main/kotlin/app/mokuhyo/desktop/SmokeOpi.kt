package app.mokuhyo.desktop

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.LocalLlamaModel
import app.mokuhyo.ai.ModelKind
import app.mokuhyo.ai.Tier
import app.mokuhyo.ai.TierAdvisor
import app.mokuhyo.ai.WhisperRecognizer
import app.mokuhyo.ai.jni.NativeLibrary
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.opi.OpiSession
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.tts.OsVoice
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.TimeSource

/**
 * `--smoke-opi` (BRIEF §11.2 Phase 1 gate): loads the Tier A model from a local path, runs one Japanese OPI turn
 * with synthesized audio in (the OS voice speaks an answer, Whisper transcribes it) and the model's next question out.
 *
 *   Mokuhyo --smoke-opi --model <tier-A .gguf> --whisper <ggml-*.bin> [--cpu]
 */
object SmokeOpi {
    fun run(args: Array<String>): Int = runBlocking {
        System.setProperty("java.awt.headless", "true")
        val model = Smoke.arg(args, "--model")?.let(::File) ?: return@runBlocking fail("--model <path to the Tier A gguf> is required")
        val whisper = Smoke.arg(args, "--whisper")?.let(::File) ?: return@runBlocking fail("--whisper <path to ggml-*.bin> is required")
        if (!model.isFile || !whisper.isFile) return@runBlocking fail("model or whisper file missing")
        NativeLibrary.configure(preferCpu = "--cpu" in args)
        val runtime = JniRuntime.tryCreate("--cpu" in args)
        println("smoke-opi: native ${runtime.status}")
        val llm = runtime.llm() ?: return@runBlocking fail("native library not available")
        val stt = runtime.stt() ?: return@runBlocking fail("native library not available")

        val manifest = app.mokuhyo.ai.ModelManager.parseManifest(Resources.text("models/manifest.json"))
        val info = TierAdvisor.defaultModel(Tier.A, manifest.models)!!
        val clock = TimeSource.Monotonic
        val gateway = AiGateway({ LocalLlamaModel(llm, info, model.absolutePath) })
        val session = OpiSession(banks = emptyMap(), gateway = gateway, startLevel = IlrLevel.L1)

        var t = clock.markNow()
        val q1 = session.next() ?: return@runBlocking fail("no first interviewer turn")
        println("smoke-opi: interviewer [${q1.engine ?: "scripted"}] ${q1.japanese}  (${t.elapsedNow().inWholeMilliseconds} ms incl. model load)")
        if (q1.engine == null) return@runBlocking fail("the interviewer turn did not come from the model")

        val answer = "はじめまして。私はアメリカから来ました。今は東京で日本語を勉強しています。趣味は料理です。"
        val wav = OsVoice.synthesize(answer, "ja") ?: return@runBlocking fail("no OS voice to synthesize the answer (needs macOS Kyoko or a Windows ja-JP voice)")
        val pcm = AudioIO.toPcm16kMono(wav)
        println("smoke-opi: synthesized answer ${pcm.size / 16} ms of audio")
        t = clock.markNow()
        val transcript = WhisperRecognizer(stt, whisper.absolutePath, manifest.models.first { it.kind == ModelKind.STT }.name).transcribe(pcm, "ja")
        println("smoke-opi: whisper transcript \"${transcript.text}\" (${t.elapsedNow().inWholeMilliseconds} ms)")
        val overlap = charOverlap(answer, transcript.text)
        println("smoke-opi: transcript overlap with the spoken answer ${(overlap * 100).toInt()}%")
        if (overlap < 0.6) return@runBlocking fail("transcript does not match the spoken answer")

        session.answer(transcript.text)
        t = clock.markNow()
        val q2 = session.next() ?: return@runBlocking fail("no second interviewer turn")
        println("smoke-opi: interviewer [${q2.engine ?: "scripted"}] ${q2.japanese}  (${t.elapsedNow().inWholeMilliseconds} ms)")
        if (q2.engine == null) return@runBlocking fail("the follow-up did not come from the model")
        println("smoke-opi: OK")
        0
    }

    private fun charOverlap(expected: String, actual: String): Double {
        fun bag(s: String) = s.filter { it.isLetterOrDigit() }.groupingBy { it }.eachCount()
        val a = bag(expected)
        val b = bag(actual)
        val common = a.entries.sumOf { (c, n) -> minOf(n, b[c] ?: 0) }
        return common.toDouble() / a.values.sum().coerceAtLeast(1)
    }

    private fun fail(msg: String): Int {
        println("smoke-opi: FAIL $msg")
        return 1
    }
}
